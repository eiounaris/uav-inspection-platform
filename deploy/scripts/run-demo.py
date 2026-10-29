#!/usr/bin/env python3
"""Create a retained four-device business demo, or read-only recheck an existing one."""
import argparse
import datetime as dt
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from backend_env import ROOT, backend_environment, fabric_environment, java21

spec = importlib.util.spec_from_file_location('retained_m2_runtime', ROOT / 'deploy/scripts/verify-mvp.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)
WORK = ROOT / '.local/full-demo'
INSPECTOR = 'com.uav.inspection.demo.RetainedDemoInspector'
API = '/api/v1'
ACCOUNTS = ('owner-admin', 'owner-reviewer', 'owner-approver', 'operator-admin', 'operator-user', 'operator-outsider')
PRIVATE = re.compile(r'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}|-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----|[?&]X-Amz-(?:Signature|Credential)=', re.I)


class FullDemo(runtime.Demo):
    def __init__(self, environment, java, run, origin=None, resuming=False):
        super().__init__(environment, java, run, origin if not resuming else None)
        self.original_run, self.resuming = origin, resuming
        self.source_run = origin
        if origin and (origin / "latest-resume.json").is_file():
            pointer = runtime.safe_json(origin / "latest-resume.json")
            source = (ROOT / pointer["runDirectory"]).resolve()
            if not source.is_relative_to(origin / "resumes"):
                raise runtime.DemoError("Invalid retained resume directory")
            self.source_run = source
        if resuming: self.report["mode"] = "RESUME"
        credentials = runtime.safe_json(self.credentials_path)
        for name in ACCOUNTS:
            password = credentials.get('passwords', {}).get(name)
            if not isinstance(password, str) or not password:
                raise runtime.DemoError('A required existing demo account password is missing')
            self.passwords[name] = password
            self.secrets.append(password)
        for token in (credentials.get('droneTokens') or {}).values():
            if isinstance(token, str): self.secrets.append(token)
        if isinstance(credentials.get('deviceToken'), str): self.secrets.append(credentials['deviceToken'])
        self.tokens, self.users, self.device_tokens = {}, {}, {}
        self.topology = 'inspection.full.' + uuid.uuid4().hex
        self.exchange, self.dead_exchange = self.topology + '.events', self.topology + '.dead.events'
        self.queues = [self.topology + '.finalize', self.topology + '.anchor', self.topology + '.dead']
        self.report.update({'task': 'T029', 'scope': 'RETAINED_BUSINESS_DEMO',
                            'topology': {'exchange': self.exchange, 'queues': self.queues}})

    def safe_write(self, path, value):
        if PRIVATE.search(json.dumps(value, ensure_ascii=False)):
            raise runtime.DemoError('Refusing to persist credentials or signed download addresses')
        super().safe_write(path, value)

    def request(self, label, method, path, token=None, body=None, raw=None, content_type=None,
                expected=200, snapshot=False, code='OK', device=None):
        self.sequence += 1
        trace = 't029-' + self.run.name[-20:] + '-' + str(self.sequence)
        self.check(path.startswith(API + '/') and '\r' not in path and '\n' not in path,
                   'demo_request_uses_only_same_origin_api')
        headers = {'Accept': 'application/json', 'X-Trace-Id': trace}
        if token: headers['Authorization'] = 'Bearer ' + token
        if device:
            headers.update({'X-Device-Id': device, 'X-Device-Token': self.device_tokens[device]})
        data = raw
        if body is not None: data, content_type = json.dumps(body).encode(), 'application/json'
        if content_type: headers['Content-Type'] = content_type
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with runtime.HTTP.open(request, timeout=65) as response:
                status, response_headers, received = response.status, response.headers, response.read()
        except urllib.error.HTTPError as failure:
            status, response_headers, received = failure.code, failure.headers, failure.read()
            failure.close()
        except (OSError, urllib.error.URLError):
            raise runtime.DemoError(label + ': HTTP connection failed; inspect retained resources before retrying writes') from None
        self.report['requests'].append({'case': label, 'method': method, 'path': path, 'status': status, 'traceId': trace})
        self.check(status == expected, label + '_http_' + str(expected))
        self.check('application/json' in response_headers.get('Content-Type', ''), label + '_json')
        try: envelope = json.loads(received)
        except ValueError: raise runtime.DemoError(label + ': response is not valid JSON') from None
        self.check(isinstance(envelope, dict) and set(envelope) == {'code', 'message', 'data', 'traceId'}
                   and envelope['code'] == code, label + '_uniform_' + code.lower() + '_envelope')
        self.check(envelope['traceId'] == trace and response_headers.get('X-Trace-Id') == trace, label + '_trace_matches')
        text = received.decode(errors='replace')
        if method == 'POST' and re.fullmatch(API + r'/evidence-versions/[0-9a-f-]{36}/download-url', path) and expected == 200:
            self.check(not snapshot, 'signed_download_response_is_never_persisted')
            self.check(isinstance(envelope['data'], dict) and set(envelope['data']) == {'url', 'expiresAt'},
                       'signed_download_response_has_expected_shape')
            # Its short-lived URL necessarily embeds the S3 access identifier.
            # Inspect public metadata only; keep the address solely in memory.
            public = {**envelope, 'data': {'expiresAt': envelope['data']['expiresAt']}}
            text = json.dumps(public)
        self.check(not runtime.PRIVATE_FIELDS.search(text) and not any(secret in text for secret in self.secrets),
                   label + '_no_credentials_or_internal_object_keys')
        if snapshot: self.safe_write(self.run / (label + '.json'), envelope)
        return envelope['data']

    def inspect_full(self, phase, filename, input_path=None):
        arguments = [self.java, '-Djava.awt.headless=true', '-Duser.timezone=Asia/Shanghai',
                     '-cp', self.classpath, INSPECTOR, phase]
        if input_path: arguments.append(str(input_path))
        output = self.run / filename
        arguments.append(str(output))
        descriptor = os.open(self.inspector_log, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        with os.fdopen(descriptor, 'ab') as log:
            result = subprocess.run(arguments, env=self.environment, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=150)
        self.check(result.returncode == 0 and output.is_file(), 'readonly_' + phase + '_inspector_completed')
        value = runtime.safe_json(output)
        self.check(value.get('passed') is True, 'readonly_' + phase + '_inspector_passed')
        # The Java helper itself writes only safe hashes/metadata; also apply the CLI guard.
        self.safe_write(output, value)
        return value

    def call(self, label, method, suffix, actor='owner-admin', **options):
        return self.request(label, method, API + suffix, self.tokens[actor], **options)

    def task(self):
        return self.call('read_current_task_lock', 'GET', '/tasks/' + self.resources['taskId'])

    def task_write(self, label, suffix, body, actor='operator-admin'):
        body = {**body, 'expectedLockVersion': self.task()['lockVersion']}
        result = self.call(label, 'POST', '/tasks/' + self.resources['taskId'] + suffix, actor, body=body)
        self.checkpoint()
        return result

    def report_progress(self, device, execution, progress, *, event=None):
        event = event or {'bootId': str(uuid.uuid4()), 'seq': 1, 'eventId': str(uuid.uuid4()),
                          'executionId': execution, 'type': 'PROGRESS', 'progress': progress}
        return self.request('simulated_device_progress', 'POST', API + '/device/reports', body=event, device=device), event

    def upload(self, index, filename):
        data = (ROOT / 'examples/files' / filename).read_bytes()
        body = {'clientRequestId': str(uuid.uuid4()), 'requirementId': self.resources['requirementIds'][index],
                'executionId': self.resources['executionIds'][index], 'filename': filename,
                'contentType': 'image/png', 'size': len(data)}
        uploaded = self.call('create_upload', 'POST', '/upload-sessions', 'operator-user', body=body)
        upload_id = uploaded['uploadId']; self.resources['uploadIds'].append(upload_id); self.checkpoint()
        self.check(uploaded['partCount'] == 1, 'sample_fits_one_real_server_part')
        suffix = '/upload-sessions/' + upload_id
        part = self.call('store_sample_bytes', 'PUT', suffix + '/parts/1', 'operator-user', raw=data, content_type='application/octet-stream')
        self.check(part['sha256'] == runtime.sha(data) and part['size'] == len(data), 'sample_bytes_match_part_receipt')
        self.call('request_real_finalization', 'POST', suffix + '/complete', 'operator-user', expected=202)
        ready = self.poll('wait_for_real_fixed_version', API + suffix, self.tokens['operator-user'],
                          lambda value: value.get('processingStatus') == 'COMPLETED' and value.get('versionId'))
        version = ready['versionId']; self.resources['versionIds'].append(version)
        self.resources['expectedFiles'][version] = {'sha256': runtime.sha(data), 'size': len(data)}; self.checkpoint()
        anchor = self.poll('wait_for_actual_ledger_confirmation', API + '/evidence-versions/' + version + '/anchor',
                           self.tokens['owner-admin'], lambda value: value.get('status') == 'CONFIRMED')
        self.resources['ledger'][version] = {key: anchor.get(key) for key in ('submissionId', 'confirmedTxId', 'blockNumber', 'validationCode')}
        self.checkpoint()
        return version

    def approve_version(self, version):
        self.call('review_exact_fixed_version', 'POST', '/evidence-versions/' + version + '/review', 'owner-reviewer',
                  body={'decision': 'APPROVED', 'expectedReviewStatus': 'PENDING', 'reason': '演示资料已核对，人工通过本固定版本。'})
        self.checkpoint()

    def grant(self, version, name):
        requested = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=24)).isoformat()
        body = {'clientRequestId': str(uuid.uuid4()), 'versionId': version, 'reason': '用于核对该固定版本的巡检资料。', 'requestedValidUntil': requested}
        grant = self.call('request_precise_version_share', 'POST', '/access-grants', 'operator-outsider', body=body)
        self.resources[name] = grant['id']; self.checkpoint()
        valid = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=12)).isoformat()
        grant = self.call('approve_precise_version_share', 'POST', '/access-grants/' + grant['id'] + '/decision', 'owner-approver',
                          body={'decision': 'APPROVED', 'validUntil': valid, 'expectedLockVersion': grant['lockVersion']})
        self.check(grant['status'] == 'APPROVED' and grant['versionId'] == version, 'share_approval_is_exact_version')
        self.checkpoint()
        return grant

    def create_business(self):
        template = runtime.safe_json(ROOT / 'examples/data/t029-task.json')
        self.check(len(template.get('requirements', [])) == 3 and all(value.get('required') is True for value in template['requirements']),
                   'public_sample_has_three_required_materials')
        template['name'] += ' · ' + self.run.name
        task = self.call('create_demo_task', 'POST', '/tasks', body=template)
        self.resources = {'formatVersion': 1, 'taskId': task['id'], 'requirementIds': [next(row['id'] for row in task['requirements'] if row['name'] == value['name']) for value in template['requirements']],
                          'deviceIds': [], 'subtaskIds': [], 'executionIds': [], 'uploadIds': [], 'versionIds': [],
                          'expectedFiles': {}, 'ledger': {}}
        self.checkpoint()
        self.task_write('assign_operator', '/assign', {'operatorOrgId': runtime.OPERATOR, 'operatorAdminId': self.users['operator-admin']['id']}, 'owner-admin')
        for actor, members in [('owner-admin', ['owner-admin', 'owner-reviewer', 'owner-approver']), ('operator-admin', ['operator-admin', 'operator-user'])]:
            current = self.task()
            self.call('maintain_task_members', 'PUT', '/tasks/' + task['id'] + '/members', actor,
                      body={'userIds': [self.users[name]['id'] for name in members], 'expectedLockVersion': current['lockVersion']})
            self.checkpoint()
        suffix = uuid.uuid4().hex[:12]
        for number in range(4):
            device = self.call('register_new_demo_device', 'POST', '/drones', 'operator-admin', body={'code': f'DEMO-{suffix}-{number + 1}'})
            device_id, secret = device['device']['id'], device['deviceToken']
            self.secrets.append(secret); self.device_tokens[device_id] = secret
            self.resources['deviceIds'].append(device_id); self.checkpoint()
        for index, requirement in enumerate(self.resources['requirementIds']):
            task = self.task_write('create_demo_subtask', '/subtasks', {'name': ['北区执行', '中区执行', '南区执行'][index], 'requirementIds': [requirement]})
            subtask = next(value for value in task['subtasks'] if value['id'] not in self.resources['subtaskIds'])
            self.resources['subtaskIds'].append(subtask['id']); self.checkpoint()
            execution = self.call('allocate_initial_device', 'POST', '/subtasks/' + subtask['id'] + '/executions', 'operator-admin',
                             body={'droneId': self.resources['deviceIds'][index], 'expectedLockVersion': subtask['lockVersion']})
            self.resources['executionIds'].append(execution['id']); self.checkpoint()
        self.task_write('start_three_device_task', '/start', {})
        before = None
        for index, progress in enumerate((35, 65, 90)):
            device, execution = self.resources['deviceIds'][index], self.resources['executionIds'][index]
            heartbeat = {'bootId': str(uuid.uuid4()), 'seq': 0, 'eventId': str(uuid.uuid4()), 'executionId': execution, 'type': 'HEARTBEAT'}
            self.request('simulated_device_heartbeat', 'POST', API + '/device/reports', body=heartbeat, device=device)
            value, event = self.report_progress(device, execution, progress)
            if index == 0:
                duplicate = self.report_progress(device, execution, progress, event=event)[0]
                self.check(duplicate['duplicate'] and duplicate['id'] == value['id'], 'duplicate_device_report_reuses_original_event')
                before = execution
        current = next(value for value in self.task()['subtasks'] if value['id'] == self.resources['subtaskIds'][0])
        replacement = self.call('manually_replace_first_device', 'POST', '/subtasks/' + current['id'] + '/executions', 'operator-admin',
                         body={'droneId': self.resources['deviceIds'][3], 'reason': '旧设备电量不足，人工安排备用设备继续北区巡检。', 'expectedLockVersion': current['lockVersion']})
        self.resources['oldExecutionId'] = before
        self.resources['executionIds'][0] = replacement['id']; self.checkpoint()
        self.report_progress(self.resources['deviceIds'][0], before, 99)
        progress = self.call('check_late_report_is_historical', 'GET', '/tasks/' + task['id'] + '/progress', 'operator-admin')
        first = next(value for value in progress['subtasks'] if value['subtaskId'] == current['id'])
        self.check(first['currentExecutionId'] == self.resources['executionIds'][0] and first['progress'] == 0,
                   'closed_execution_late_progress_does_not_pollute_replacement')
        for index in range(3):
            self.report_progress(self.resources['deviceIds'][3 if index == 0 else index], self.resources['executionIds'][index], 100)
        old = self.upload(0, 'inspection-sample.png'); self.resources['oldVersionId'] = old; self.checkpoint()
        self.approve_version(old); old_grant = self.grant(old, 'revokedGrantId')
        new = self.upload(0, 'inspection-revised.png'); self.resources['newVersionId'] = new; self.checkpoint()
        review = self.call('new_version_has_independent_review', 'GET', '/evidence-versions/' + new + '/review', 'owner-reviewer')
        self.check(review['reviewStatus'] == 'PENDING' and review.get('review') is None, 'new_version_does_not_inherit_old_review')
        visible = self.call('old_share_filters_version_list', 'GET', '/evidence-versions?taskId=' + task['id'] + '&page=1&pageSize=20', 'operator-outsider')
        self.check(visible['total'] == 1 and visible['items'][0]['id'] == old, 'old_share_does_not_include_new_version')
        self.call('new_version_before_new_grant_is_forbidden', 'GET', '/evidence-versions/' + new, 'operator-outsider', expected=403, code='FORBIDDEN')
        self.call('version_grant_does_not_open_task', 'GET', '/tasks/' + task['id'], 'operator-outsider', expected=403, code='FORBIDDEN')
        self.approve_version(new)
        for index in (1, 2): self.approve_version(self.upload(index, 'inspection-sample.png'))
        self.finish_business()

    def finish_business(self):
        task = self.task()
        new = self.resources['newVersionId']
        grants = self.call('read_current_share_locks', 'GET', '/access-grants?taskId=' + task['id'] + '&page=1&pageSize=100', 'owner-approver')
        old_grant = next(value for value in grants['items'] if value['id'] == self.resources['revokedGrantId'])
        progress = self.call('all_current_materials_ready', 'GET', '/tasks/' + task['id'] + '/progress', 'operator-admin', snapshot=True)
        self.check(progress['averageProgress'] == 100 and progress['acceptanceReadyRequiredCount'] == 3
                   and progress['historicalVersionCount'] == 0, 'three_current_executions_and_required_versions_are_ready')
        if task['status'] == 'RUNNING':
            task = self.task_write('submit_for_acceptance', '/submit-acceptance', {})
        if task['status'] == 'WAITING_ACCEPTANCE':
            task = self.task_write('formally_complete_task', '/complete', {}, 'owner-admin')
        self.check(task['status'] == 'COMPLETED' and task['completedAt'], 'retained_task_is_formally_completed')
        if old_grant['status'] == 'APPROVED':
            self.call('revoke_old_version_share_after_completion', 'POST', '/access-grants/' + old_grant['id'] + '/revoke', 'owner-approver',
                      body={'reason': '旧版用途结束，撤销旧版授权。', 'expectedLockVersion': old_grant['lockVersion']})
        else:
            self.check(old_grant['status'] == 'REVOKED', 'old_share_is_revoked_or_still_revocable')
        active = next((value for value in grants['items'] if value['versionId'] == new and value['applicantId'] == self.users['operator-outsider']['id']), None)
        if active:
            self.resources['activeGrantId'] = active['id']; self.checkpoint()
            if active['status'] == 'PENDING':
                valid = min(dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=12), dt.datetime.fromisoformat(active['requestedValidUntil'].replace('Z', '+00:00')))
                active = self.call('approve_existing_precise_share', 'POST', '/access-grants/' + active['id'] + '/decision', 'owner-approver',
                                  body={'decision': 'APPROVED', 'validUntil': valid.isoformat(), 'expectedLockVersion': active['lockVersion']})
            self.check(active['status'] == 'APPROVED', 'resumed_precise_share_is_effective')
        else:
            self.grant(new, 'activeGrantId')
        content = (ROOT / 'examples/files/inspection-revised.png').read_bytes()
        changed = bytearray(content); changed[36] ^= 1
        self.safe_write(self.run / 'samples.json', {'original': {'filename': 'inspection-sample.png', 'size': (ROOT / 'examples/files/inspection-sample.png').stat().st_size},
                                                  'revised': {'filename': 'inspection-revised.png', 'size': len(content), 'sha256': runtime.sha(content)},
                                                  'copy': {'changedByteOffset': 36, 'sha256': runtime.sha(changed)}})
        history = self.call('read_existing_demo_verifications', 'GET', '/evidence-versions/' + new + '/verifications?page=1&pageSize=100')
        existing = {(value['source'], value['result'], value['actualSha256']): value for value in history['items']}
        results = []
        for source, outcome, data in [('STORED', 'MATCH', content), ('COPY', 'MATCH', content), ('COPY', 'MISMATCH', bytes(changed))]:
            key = (source, outcome, runtime.sha(data))
            value = existing.get(key)
            if value is None:
                value = self.call('shared_stored_match', 'POST', '/evidence-versions/' + new + '/verifications?source=STORED', 'operator-outsider') if source == 'STORED' else self.copy_request('shared_copy_' + outcome.lower(), new, self.tokens['operator-outsider'], data)
            self.check(value['result'] == outcome and value['actualSha256'] == runtime.sha(data), 'actual_' + source.lower() + '_' + outcome.lower() + '_verification')
            results.append(value)
        self.resources['verificationIds'] = [value['id'] for value in results]; self.checkpoint()
        signed = self.call('issue_short_lived_download', 'POST', '/evidence-versions/' + new + '/download-url', 'operator-outsider')
        url = urllib.parse.urlsplit(signed['url'])
        self.check(url.scheme in ('http', 'https') and not url.username and not url.password, 'download_navigation_uses_http_url_without_userinfo')
        try:
            with runtime.HTTP.open(signed['url'], timeout=30) as response: downloaded, status = response.read(), response.status
        except (OSError, urllib.error.URLError): raise runtime.DemoError('Actual signed object download failed') from None
        self.check(status == 200 and downloaded == content, 'actual_signed_download_bytes_match_fixed_version')
        self.report['actualDownloadHttp200'] = True

    def read_business(self):
        task_id = self.resources['taskId']
        task = self.call('retained_completed_task', 'GET', '/tasks/' + task_id, snapshot=True)
        self.check(task['status'] == 'COMPLETED' and bool(task['completedAt']), 'retained_task_stays_completed')
        self.call('retained_three_device_progress', 'GET', '/tasks/' + task_id + '/progress', snapshot=True)
        self.call('retained_execution_history', 'GET', '/subtasks/' + self.resources['subtaskIds'][0] + '/executions?page=1&pageSize=20', snapshot=True)
        for version in self.resources['versionIds']:
            value = self.call('retained_fixed_version', 'GET', '/evidence-versions/' + version)
            expected = self.resources['expectedFiles'][version]
            self.check(value['sha256'] == expected['sha256'] and value['size'] == expected['size']
                       and value['reviewStatus'] == 'APPROVED' and value['anchor']['status'] == 'CONFIRMED', 'retained_version_bytes_review_and_anchor_match')
        grants = self.call('retained_task_grants', 'GET', '/access-grants?taskId=' + task_id + '&page=1&pageSize=100', 'owner-approver', snapshot=True)
        active = next(value for value in grants['items'] if value['id'] == self.resources['activeGrantId'])
        self.check(next(value for value in grants['items'] if value['id'] == self.resources['revokedGrantId'])['status'] == 'REVOKED', 'old_precise_share_remains_revoked')
        visible = self.call('recipient_current_visible_versions', 'GET', '/evidence-versions?taskId=' + task_id + '&page=1&pageSize=100', 'operator-outsider', snapshot=True)
        self.check(active['status'] in ('APPROVED', 'EXPIRED'), 'retained_latest_share_is_approved_or_naturally_expired')
        allowed = active['status'] == 'APPROVED'
        self.check({value['id'] for value in visible['items']} == ({self.resources['newVersionId']} if allowed else set()), 'recipient_scope_uses_current_precise_grant_and_time')
        for version in self.resources['versionIds']:
            self.call('recipient_exact_version_scope', 'GET', '/evidence-versions/' + version, 'operator-outsider',
                      expected=200 if allowed and version == self.resources['newVersionId'] else 403,
                      code='OK' if allowed and version == self.resources['newVersionId'] else 'FORBIDDEN')
        self.call('recipient_cannot_read_parent_task', 'GET', '/tasks/' + task_id, 'operator-outsider', expected=403, code='FORBIDDEN')
        self.call('recipient_cannot_read_task_audit', 'GET', '/tasks/' + task_id + '/audit-logs?page=1&pageSize=20', 'operator-outsider', expected=403, code='FORBIDDEN')
        self.call('retained_task_audit', 'GET', '/tasks/' + task_id + '/audit-logs?page=1&pageSize=20', snapshot=True)
        self.call('retained_version_relations', 'GET', '/tasks/' + task_id + '/evidence-relations?page=1&pageSize=20', snapshot=True)
        self.report['retainedActiveGrantStatus'] = active['status']

    def execute(self):
        self.report['stage'] = 'preflight'; self.checkpoint()
        runtime.no_other_backends(); self.check_consumers()
        self.inspect_full('preflight', 'preflight.json')
        if self.recheck or self.resuming:
            passed = runtime.safe_json(self.source_run / 'report.json').get('passed')
            self.check(passed is (False if self.resuming else True), 'resume_uses_failed_run_or_recheck_uses_successful_run')
            self.resources = runtime.safe_json(self.source_run / 'resources.json')
            if self.resuming:
                self.check(len(self.resources.get('versionIds', [])) == 4 and all(key in self.resources for key in ('oldVersionId', 'newVersionId', 'revokedGrantId')), 'resume_requires_four_already_fixed_versions')
            self.report['sourceRunDirectory'] = runtime.local_path(self.original_run)
        try:
            self.report['stage'] = 'packaged_http'; self.start()
            for name in ACCOUNTS: self.tokens[name], self.users[name] = self.login(name)
            self.check(self.users['owner-admin']['orgId'] == runtime.OWNER and self.users['operator-admin']['orgId'] == runtime.OPERATOR,
                       'original_demo_users_keep_expected_institutions')
            if self.resuming:
                for version in self.resources['versionIds']:
                    value = self.call('resume_reads_confirmed_reviewed_version', 'GET', '/evidence-versions/' + version)
                    self.check(value['anchor']['status'] == 'CONFIRMED' and value['reviewStatus'] == 'APPROVED', 'resume_requires_four_actual_confirmed_reviewed_versions')
                self.finish_business()
            elif not self.recheck: self.create_business()
            self.read_business(); self.wait_empty_queues()
            self.report['stage'] = 'readonly_inspection'; self.checkpoint()
            fields = ('taskId', 'deviceIds', 'subtaskIds', 'oldExecutionId', 'executionIds', 'uploadIds',
                      'versionIds', 'oldVersionId', 'newVersionId', 'revokedGrantId', 'activeGrantId', 'expectedFiles')
            data = {key: self.resources[key] for key in fields}
            data['preflightReport'] = str((self.original_run if self.recheck or self.resuming else self.run) / 'preflight.json')
            if self.recheck: data['recheckPreflightReport'] = str(self.run / 'preflight.json')
            self.safe_write(self.run / 'inspector-input.json', data)
            self.inspect_full('inspect', 'inspection.json', self.run / 'inspector-input.json')
            self.report.update({'stage': 'complete', 'completedAt': runtime.now(), 'passed': True,
                                'newTaskCount': 0 if self.recheck or self.resuming else 1, 'newLedgerRegistrationCount': 0 if self.recheck or self.resuming else 4,
                                'readOnlyRecheck': self.recheck})
        finally:
            self.stop()
            cleaned = self.cleanup_empty_topology()
            self.check(cleaned, 'only_own_empty_message_topology_deleted_after_shutdown')
            self.check(runtime.sha(self.credentials_path.read_bytes()) == self.original_credentials, 'original_demo_credentials_unchanged')
            self.check(runtime.sha((ROOT / '.env').read_bytes()) == self.original_env, 'original_env_unchanged')
            self.checkpoint()


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--resume', type=Path, help='Continue a failed run after all four versions are actually CONFIRMED and APPROVED; no upload or ledger replay')
    group.add_argument('--recheck', type=Path, help='Read-only recheck a successful .local/full-demo/<runId>; no new verification or transaction')
    arguments = parser.parse_args()
    environment = fabric_environment(backend_environment(), local=True)
    java = java21(environment)
    if not runtime.JAR.is_file(): raise runtime.DemoError('Build the packaged backend first')
    compiled = ROOT / ('backend/target/test-classes/' + INSPECTOR.replace('.', '/') + '.class')
    if not compiled.is_file(): raise runtime.DemoError('Build with -P database-probe,fabric-probe before this demo')
    WORK.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(WORK, 0o700)
    descriptor = os.open(WORK / '.run.lock', os.O_RDWR | os.O_CREAT, 0o600)
    with os.fdopen(descriptor, 'r+') as lock:
        try: fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: raise runtime.DemoError('Another full demo owns the run lock') from None
        origin_argument = arguments.recheck or arguments.resume
        origin = origin_argument.resolve() if origin_argument else None
        if origin and (origin.parent != WORK.resolve() or origin.is_symlink() or not (origin / 'report.json').is_file()):
            raise runtime.DemoError('--recheck requires a successful direct .local/full-demo/<runId> directory')
        name = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
        run = origin / ('resumes' if arguments.resume else 'rechecks') / name if origin else WORK / name
        run.mkdir(parents=True, mode=0o700)
        print('Run directory: ' + runtime.local_path(run), flush=True)
        demo = FullDemo(environment, java, run, origin, bool(arguments.resume))
        try: demo.execute()
        except Exception as failure:
            demo.report.update({'passed': False, 'failureType': type(failure).__name__})
            if isinstance(failure, runtime.DemoError): demo.report['failedCheck'] = str(failure)
            demo.checkpoint()
            raise
        latest = {'formatVersion': 1, 'verifiedAt': runtime.now(), 'mode': demo.report['mode'],
                  'runDirectory': runtime.local_path(run), 'report': runtime.local_path(run / 'report.json')}
        demo.safe_write(origin / ('latest-resume.json' if arguments.resume else 'latest-recheck.json') if origin else WORK / 'latest.json', latest)
        if arguments.resume:
            demo.safe_write(WORK / 'latest.json', {**latest, 'runDirectory': runtime.local_path(origin), 'resourceDirectory': runtime.local_path(run)})
        print('[PASS] T029 retained business demo: ' + runtime.local_path(run / 'report.json'))


if __name__ == '__main__':
    try: main()
    except runtime.DemoError as failure:
        print('[FAIL] ' + str(failure), file=sys.stderr); sys.exit(1)
    except Exception as failure:
        print('[FAIL] Demo failed (' + type(failure).__name__ + '); inspect private local reports', file=sys.stderr); sys.exit(1)
