#!/usr/bin/env python3
"""T031 closed-loop measurements against the functionally accepted packaged JAR."""
import concurrent.futures
import datetime as dt
import fcntl
import hashlib
import http.client
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import secrets
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid
import zlib

from backend_env import ROOT, backend_environment, fabric_environment, java21
from perf_resources import ResourceSampler, collect_environment


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'deploy/scripts' / filename)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


MVP = module('performance_mvp', 'verify-mvp.py')
FINAL = module('performance_final', 'verify-final.py')
WORK = ROOT / '.local/performance'
FIXTURE = 'com.uav.inspection.ui.probe.UiBrowserFixture'
INSPECTOR = 'com.uav.inspection.performance.PerformanceInspector'
RETAINED = 'com.uav.inspection.demo.RetainedDemoInspector'
PRIVATE = re.compile(r'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}|-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----|[?&]X-Amz-(?:Signature|Credential)=', re.I)


def require(value, name):
    if not value:
        raise MVP.DemoError(name)


def percentile(values, fraction):
    """Nearest-rank percentile; missing observations stay missing."""
    require(0 < fraction <= 1, 'invalid_percentile')
    return sorted(values)[math.ceil(len(values) * fraction) - 1] if values else None


def summarize(rows, seconds):
    latencies = [r['durationMs'] for r in rows]
    errors = sum(not r['ok'] for r in rows)
    successful = [r['durationMs'] for r in rows if r['ok']]
    return {'samples': len(rows), 'errors': errors, 'errorRate': errors / len(rows) if rows else None,
            'wallSeconds': seconds, 'requestsPerSecond': len(rows) / seconds if seconds else None,
            'latencyMs': {'min': min(latencies, default=None), 'mean': sum(latencies) / len(latencies) if latencies else None,
                          'p50': percentile(latencies, .5), 'p95': percentile(latencies, .95), 'max': max(latencies, default=None)},
            'reusedConnectionRequests': sum(r['connectionReused'] for r in rows),
            'successfulLatencyMs': {'samples': len(successful), 'p50': percentile(successful, .5), 'p95': percentile(successful, .95)},
            'percentileMethod': 'nearest rank; includes all measured requests including failures'}


def png_fixture(original, size):
    require(original[-12:] == b'\x00\x00\x00\x00IEND\xaeB`\x82', 'png_iend_required')
    data = b'T031\x00' + b'x' * (size - len(original) - 12 - 5)
    chunk = b'tEXt' + data
    result = original[:-12] + struct.pack('>I', len(data)) + chunk + struct.pack('>I', zlib.crc32(chunk)) + original[-12:]
    require(len(result) == size, 'fixture_exact_size')
    return result


def expired_presence(environment, prefix, device_ids):
    """Read only these five scoped keys; never SCAN/FLUSH or delete shared keys."""
    require(re.fullmatch(r'inspection:perf:[a-f0-9]{32}:', prefix), 'scoped_presence_prefix_required')
    require(len(device_ids) == 5 and len(set(device_ids)) == 5 and all(MVP.UUID.fullmatch(i) for i in device_ids), 'five_scoped_device_ids_required')
    host = environment.get('REDIS_HOST', '127.0.0.1')
    require(host in ('127.0.0.1', 'localhost', '::1'), 'local_redis_required')
    with socket.create_connection((host, int(environment.get('REDIS_PORT', '6379'))), timeout=5) as connection:
        stream = connection.makefile('rb')
        def command(*arguments):
            parts = [str(argument).encode() for argument in arguments]
            connection.sendall(b'*' + str(len(parts)).encode() + b'\r\n' + b''.join(b'$' + str(len(p)).encode() + b'\r\n' + p + b'\r\n' for p in parts))
            line = stream.readline(1024)
            require(line.startswith((b'+', b':')) and line.endswith(b'\r\n'), 'redis_safe_response_required')
            return line[1:-2]
        if environment.get('REDIS_PASSWORD'):
            require(command('AUTH', environment['REDIS_PASSWORD']) == b'OK', 'redis_auth_failed')
        present = int(command('EXISTS', *(prefix + device for device in device_ids)))
        stream.close()
    return {'checkedAt': MVP.now(), 'scopedKeysChecked': 5, 'presentKeys': present, 'allExpired': present == 0,
            'operation': 'EXISTS over exact run prefix + five fixture device UUIDs; no deletion'}


class Client:
    """One connection per worker; timing ends before decoding/validation/logging."""
    def __init__(self, url, token=None, device=None):
        parsed = urllib.parse.urlsplit(url)
        require(parsed.hostname == '127.0.0.1' and parsed.scheme == 'http', 'local_http_only')
        self.connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=65)
        self.token, self.device, self.sequence = token, device, 0

    def close(self): self.connection.close()

    def request(self, method, path, body=None, raw=None, expected=200):
        self.sequence += 1
        trace = 't031-' + uuid.uuid4().hex
        headers = {'Accept': 'application/json', 'X-Trace-Id': trace}
        if self.token: headers['Authorization'] = 'Bearer ' + self.token
        if self.device:
            headers.update({'X-Device-Id': self.device['id'], 'X-Device-Token': self.device['token']})
        data = raw
        if body is not None:
            data = json.dumps(body, separators=(',', ':')).encode(); headers['Content-Type'] = 'application/json'
        elif raw is not None: headers['Content-Type'] = 'application/octet-stream'
        reused = self.connection.sock is not None
        start = time.perf_counter_ns()
        status, payload, response_trace, content_type, failure = 0, b'', None, '', None
        try:
            self.connection.request(method, path, body=data, headers=headers)
            response = self.connection.getresponse()
            status = response.status; payload = response.read()
            response_trace = response.getheader('X-Trace-Id'); content_type = response.getheader('Content-Type', '')
        except (OSError, http.client.HTTPException) as error:
            failure = type(error).__name__; self.connection.close()
        end = time.perf_counter_ns()
        row = {'method': method, 'path': path, 'status': status, 'traceId': trace,
               'startMonotonicNs': start, 'endMonotonicNs': end, 'durationMs': (end - start) / 1e6,
               'connectionReused': reused, 'responseBytes': len(payload), 'ok': False}
        try: envelope = json.loads(payload)
        except (ValueError, UnicodeError): envelope = None
        row['ok'] = (status == expected and isinstance(envelope, dict) and envelope.get('code') == 'OK'
                     and envelope.get('traceId') == trace and response_trace == trace and 'application/json' in content_type)
        if failure: row['failureType'] = failure
        if isinstance(envelope, dict) and envelope.get('code') != 'OK':
            code = envelope.get('code')
            row['responseCode'] = code if isinstance(code, str) and re.fullmatch(r'[A-Z0-9_]{1,80}', code) else 'INVALID'
        return envelope.get('data') if isinstance(envelope, dict) else None, row


class Run:
    def __init__(self, directory):
        self.run = directory
        self.environment = fabric_environment(backend_environment(), local=True)
        self.environment['DEVICE_PRESENCE_KEY_PREFIX'] = 'inspection:perf:' + uuid.uuid4().hex + ':'
        self.java = java21(self.environment)
        self.runtime = MVP.Demo(self.environment, self.java, directory)
        topology = 'inspection.perf.' + uuid.uuid4().hex
        self.runtime.exchange, self.runtime.dead_exchange = topology + '.events', topology + '.dead.events'
        self.runtime.queues = [topology + '.finalize', topology + '.anchor', topology + '.dead']
        self.runtime.report['topology'] = {'exchange': self.runtime.exchange, 'queues': self.runtime.queues}
        self.checkpoint = directory / 'fixture-checkpoint.json'
        self.report = {'formatVersion': 1, 'task': 'T031', 'runId': directory.name, 'passed': False,
                       'startedAt': MVP.now(), 'api': [], 'device': [], 'uploadBatches': [], 'uploads': []}
        self.sampler, self.seeded, self.devices, self.tokens = None, False, [], {}
        for source in ('deploy/scripts/run-performance.py', 'deploy/scripts/perf_resources.py',
                       'backend/src/test/java/com/uav/inspection/performance/PerformanceInspector.java'):
            path = ROOT / source
            self.report.setdefault('measurementTools', {})[source] = MVP.sha(path.read_bytes())
            MVP.private_bytes(directory / ('measured-' + path.name), path.read_bytes())
        defaults = {'MESSAGING_PUBLISH_DELAY_MS': 1000, 'MESSAGING_RECOVERY_DELAY_MS': 10000,
                    'UPLOAD_PART_SIZE': 8388608, 'UPLOAD_MAX_FILE_SIZE': 209715200,
                    'DEVICE_ONLINE_THRESHOLD_SECONDS': 20, 'DEVICE_PRESENCE_CACHE_TTL_SECONDS': 60}
        self.write('effective-config.json', {**{key: int(self.environment.get(key, value)) for key, value in defaults.items()},
                   'mysqlPoolMaximum': 5, 'mysqlPoolMinimumIdle': 1,
                   'ownRedisPresencePrefix': self.environment['DEVICE_PRESENCE_KEY_PREFIX']})

    def write(self, filename, value):
        text = json.dumps(value, ensure_ascii=False, indent=2) + '\n'
        require(not PRIVATE.search(text), 'refusing_private_artifact')
        self.runtime.safe_write(self.run / filename, value)

    def command(self, name, cls, args, payload=None, output=None):
        descriptor = os.open(self.run / (name + '.log'), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'wb') as stream:
            result = subprocess.run([self.java, '-Djava.awt.headless=true', '-Duser.timezone=Asia/Shanghai', '-cp',
                                     self.runtime.classpath, cls, *map(str, args)], cwd=ROOT, env=self.environment,
                                    input=json.dumps(payload).encode() if payload is not None else None,
                                    stdout=stream, stderr=subprocess.STDOUT, timeout=240)
        require(result.returncode == 0, name + '_failed_inspect_private_log')
        value = json.loads(output.read_text() if output else (self.run / (name + '.log')).read_text())
        require(value.get('passed') is True, name + '_not_passed')
        return value

    def phase(self, name):
        print('T031 phase: ' + name, flush=True)
        if self.sampler: self.sampler.mark_phase(name)
        self.report['currentPhase'] = name; self.write('report.json', self.report)

    def api(self, client, method, path, collector=None, **kwargs):
        data, row = client.request(method, '/api/v1' + path, **kwargs)
        if collector is not None: collector.append(row)
        require(row['ok'], 'setup_or_upload_http_failed_' + str(row['status']))
        return data, row

    def containers(self):
        ids = []
        for project in ('uav-inspection', 'uav-inspection-fabric'):
            result = subprocess.run(['docker', 'ps', '--filter', 'label=com.docker.compose.project=' + project,
                                     '--format', '{{.ID}}'], capture_output=True, text=True, timeout=15)
            require(result.returncode == 0, 'docker_inventory_failed')
            ids.extend(result.stdout.split())
        require(bool(ids) and all(re.fullmatch(r'[0-9a-f]{12}', item) for item in ids), 'project_containers_required')
        template = '{{json .Id}} {{json .Name}} {{json .Config.Image}} {{json .HostConfig.Memory}} {{json .HostConfig.NanoCpus}} {{json .HostConfig.CpuQuota}} {{json .HostConfig.CpuPeriod}} {{json .HostConfig.CpusetCpus}}'
        # Explicit format only: a complete inspect would expose service credentials.
        result = subprocess.run(['docker', 'inspect', '--format', template, *ids], capture_output=True, text=True, timeout=15)
        require(result.returncode == 0, 'safe_container_details_failed')
        rows = []
        decoder = json.JSONDecoder()
        for line in result.stdout.splitlines():
            values = []; rest = line
            while rest.strip():
                value, end = decoder.raw_decode(rest.lstrip()); values.append(value); rest = rest.lstrip()[end:]
            rows.append(dict(zip(('id', 'name', 'image', 'memoryLimitBytes', 'nanoCpus', 'cpuQuota', 'cpuPeriod', 'cpuset'), values)))
        self.report['containers'] = rows
        return ids

    def setup(self):
        self.phase('preflight')
        latest = FINAL.read(ROOT / '.local/final-acceptance/latest.json')
        accepted = FINAL.read(ROOT / latest['report'])
        binding = FINAL.binding()
        require(accepted.get('passed') is True and accepted['binding'] == binding, 'accepted_production_binding_required')
        self.report['binding'] = binding; self.report['functionalAcceptance'] = latest['report']
        MVP.no_other_backends(); self.runtime.check_consumers()
        baseline = self.run / 'baseline.json'
        self.before = self.command('baseline', RETAINED, ['preflight', baseline], output=baseline)
        accounts = []
        for name, org, role in [('owner', MVP.OWNER, 'OWNER_TASK_ADMIN'), ('admin', MVP.OPERATOR, 'OPERATOR_TASK_ADMIN'),
                                ('uploader', MVP.OPERATOR, 'OPERATOR_USER')]:
            account = {'id': str(uuid.uuid4()), 'username': 'perf_' + name + '_' + uuid.uuid4().hex[:12],
                       'password': secrets.token_urlsafe(30), 'orgId': org, 'roles': [role]}
            self.runtime.secrets.append(account['password']); accounts.append(account)
        try:
            self.command('fixture-create', FIXTURE, ['create', self.checkpoint], {'accounts': accounts})
        finally:
            # The helper writes a scoped checkpoint before its SQL transaction.
            # A failed final report must not hide a committed fixture.
            self.seeded = self.checkpoint.exists()
        self.runtime.start()
        ids = self.containers()
        environment = collect_environment()
        for name, argv in [('java', [self.java, '-version']), ('docker', ['docker', '--version']), ('compose', ['docker', 'compose', 'version'])]:
            result = subprocess.run(argv, capture_output=True, text=True, timeout=15)
            environment[name + 'Version'] = (result.stdout + result.stderr).strip()
        self.report['environment'] = environment
        for name, account in zip(('owner', 'admin', 'uploader'), accounts):
            client = Client(self.runtime.base_url)
            data, _ = self.api(client, 'POST', '/auth/login', body={'username': account['username'], 'password': account['password']})
            self.tokens[name] = data['accessToken']; self.runtime.secrets.append(data['accessToken']); client.close()
        owner, admin = Client(self.runtime.base_url, self.tokens['owner']), Client(self.runtime.base_url, self.tokens['admin'])
        task, _ = self.api(owner, 'POST', '/tasks', body={'name': 'T031-' + self.run.name, 'scope': '独立性能测量，五机五子任务',
                        'requirements': [{'name': '材料' + str(i), 'required': True} for i in range(5)]})
        self.task_id = task['id']; path = '/tasks/' + self.task_id
        task, _ = self.api(owner, 'POST', path + '/assign', body={'operatorOrgId': MVP.OPERATOR, 'operatorAdminId': accounts[1]['id'], 'expectedLockVersion': task['lockVersion']})
        task, _ = self.api(admin, 'PUT', path + '/members', body={'userIds': [accounts[1]['id'], accounts[2]['id']], 'expectedLockVersion': task['lockVersion']})
        for i in range(5):
            drone, _ = self.api(admin, 'POST', '/drones', body={'code': 'PERF-' + uuid.uuid4().hex[:20]})
            device = {'id': drone['device']['id'], 'token': drone['deviceToken'], 'bootId': str(uuid.uuid4()), 'seq': 0}
            self.runtime.secrets.append(device['token'])
            requirement = next(r for r in task['requirements'] if r['name'] == '材料' + str(i))
            task, _ = self.api(admin, 'POST', path + '/subtasks', body={'name': '子任务' + str(i), 'requirementIds': [requirement['id']], 'expectedLockVersion': task['lockVersion']})
            subtask = next(s for s in task['subtasks'] if s['name'] == '子任务' + str(i))
            execution, _ = self.api(admin, 'POST', '/subtasks/' + subtask['id'] + '/executions', body={'droneId': device['id'], 'expectedLockVersion': subtask['lockVersion']})
            device.update(requirementId=requirement['id'], executionId=execution['id']); self.devices.append(device)
            task, _ = self.api(admin, 'GET', path)
        task, _ = self.api(admin, 'POST', path + '/start', body={'expectedLockVersion': task['lockVersion']})
        require(task['status'] == 'RUNNING', 'five_subtask_task_running')
        owner.close(); admin.close()
        original = (ROOT / 'examples/files/inspection-sample.png').read_bytes()
        self.files = {'79B': original, '1MiB': png_fixture(original, 1024**2), '17MiB': png_fixture(original, 17*1024**2)}
        self.report['fixtures'] = {name: {'size': len(data), 'sha256': MVP.sha(data)} for name, data in self.files.items()}
        self.report['taskId'] = self.task_id
        self.report['devices'] = [{k: d[k] for k in ('id', 'requirementId', 'executionId')} for d in self.devices]
        self.sampler = ResourceSampler(self.runtime.process.pid, self.run, ids).start()
        self.phase('idle'); time.sleep(5)

    def upload(self, index, size_label, measured):
        data, device = self.files[size_label], self.devices[index]
        client = Client(self.runtime.base_url, self.tokens['uploader'])
        request_id = str(uuid.uuid4())
        requests, result = [], None
        def call(method, path, **kwargs):
            return self.api(client, method, path, collector=requests, **kwargs)
        began = time.perf_counter_ns()
        try:
            session, create = call('POST', '/upload-sessions', body={'clientRequestId': request_id,
                'requirementId': device['requirementId'], 'executionId': device['executionId'], 'filename': size_label + '.png',
                'contentType': 'image/png', 'size': len(data)})
            began = create['startMonotonicNs']
            base = '/upload-sessions/' + session['uploadId']; parts = []
            require(session['partCount'] == math.ceil(len(data) / session['partSize']), 'server_part_count')
            chunks = [data[offset:offset+session['partSize']] for offset in range(0, len(data), session['partSize'])]
            for no, chunk in enumerate(chunks, 1):
                part, timing = call('PUT', base + '/parts/' + str(no), raw=chunk)
                require(part['size'] == len(chunk) and part['sha256'] == MVP.sha(chunk), 'part_actual_bytes_match')
                parts.append(timing)
            part_end = parts[-1]['endMonotonicNs']
            _, complete = call('POST', base + '/complete', expected=202)
            deadline = complete['endMonotonicNs'] + 180_000_000_000
            def poll(path):
                remaining = (deadline-time.perf_counter_ns()) / 1e9
                require(remaining > 0, 'confirmation_poll_budget_exceeded')
                client.connection.timeout = min(65, remaining)
                if client.connection.sock: client.connection.sock.settimeout(client.connection.timeout)
                return call('GET', path)
            while True:
                ready, ready_row = poll(base)
                if ready.get('processingStatus') == 'COMPLETED' and ready.get('versionId'): break
                require(ready.get('processingStatus') not in ('FAILED', 'EXPIRED'), 'upload_terminal_failure')
                time.sleep(.25)
            version_id = ready['versionId']
            while True:
                anchor, anchor_row = poll('/evidence-versions/' + version_id + '/anchor')
                if anchor.get('status') == 'CONFIRMED': break
                require(anchor.get('status') != 'FAILED', 'anchor_failed')
                time.sleep(.25)
            require(re.fullmatch(r'[0-9a-f]{64}', anchor['confirmedTxId']), 'actual_confirmed_transaction')
            end = anchor_row['endMonotonicNs']
            result = {'measured': measured, 'sizeLabel': size_label, 'size': len(data), 'sha256': MVP.sha(data),
                    'clientRequestId': request_id, 'requirementId': device['requirementId'], 'executionId': device['executionId'],
                    'uploadId': session['uploadId'], 'versionId': version_id, 'confirmedTxId': anchor['confirmedTxId'],
                    'blockNumber': anchor.get('blockNumber'), 'partCount': session['partCount'], 'createMs': create['durationMs'],
                    'partsTransferMs': (part_end-parts[0]['startMonotonicNs'])/1e6, 'sumPartHttpMs': sum(p['durationMs'] for p in parts),
                    'complete202Ms': complete['durationMs'], 'createToCompletedObservedMs': (ready_row['endMonotonicNs']-began)/1e6,
                    'accepted202ToConfirmedObservedMs': (end-complete['endMonotonicNs'])/1e6,
                    'createToConfirmedObservedMs': (end-began)/1e6, 'startMonotonicNs': began, 'endMonotonicNs': end,
                    'partFirstSendMonotonicNs': parts[0]['startMonotonicNs'], 'partLastAckMonotonicNs': part_end,
                    'accepted202MonotonicNs': complete['endMonotonicNs'], 'completedObservedMonotonicNs': ready_row['endMonotonicNs'],
                    'confirmedObservedMonotonicNs': end}
            return result
        finally:
            client.close()
            self.write('upload-' + request_id + '.json', {'clientRequestId': request_id, 'measured': measured,
                       'sizeLabel': size_label, 'completed': result is not None, 'requests': requests, 'result': result})

    def closed_loop(self, label, concurrency, minimum, make_client, operation):
        self.phase('warmup-' + label)
        barrier = threading.Barrier(concurrency + 1, timeout=90)
        window = {}
        def worker(index):
            client = make_client(index); rows = []
            try:
                for _ in range(5):
                    _, row = operation(client, index); require(row['ok'], 'warmup_http_failure')
                barrier.wait()
                while len(rows) < minimum or time.perf_counter_ns() < window['deadline']:
                    _, row = operation(client, index); row['worker'] = index; rows.append(row)
                return rows
            except Exception:
                barrier.abort(); raise
            finally: client.close()
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = [executor.submit(worker, i) for i in range(concurrency)]
            while barrier.n_waiting != concurrency:
                for future in futures:
                    if future.done(): future.result()
                time.sleep(.01)
            self.phase(label)
            start = time.perf_counter_ns(); window['deadline'] = start + 10_000_000_000
            barrier.wait()
            results = [future.result() for future in futures]
            end = time.perf_counter_ns()
        self.phase('between-phases')
        rows = [r for worker_rows in results for r in worker_rows]
        self.write(label + '.json', {'phase': label, 'concurrency': concurrency, 'minimumPerWorker': minimum,
                   'warmupRequestsPerWorker': 5, 'startMonotonicNs': start, 'endMonotonicNs': end, 'requests': rows})
        summary = {'phase': label, 'concurrency': concurrency, 'perWorkerSamples': list(map(len, results)), **summarize(rows, (end-start)/1e9)}
        require(summary['errors'] == 0, label + '_has_measured_errors')
        return summary

    def measure(self):
        self.setup()
        self.phase('warmup-upload'); warmup = self.upload(0, '79B', False)
        self.report['uploads'].append(warmup)
        endpoints = [('progress', '/tasks/' + self.task_id + '/progress'),
                     ('versions', '/evidence-versions?taskId=' + self.task_id + '&page=1&pageSize=20'),
                     ('version-detail', '/evidence-versions/' + warmup['versionId'])]
        for name, endpoint in endpoints:
            for concurrency in (1, 3, 5):
                result = self.closed_loop('api-' + name + '-c' + str(concurrency), concurrency, 40,
                    lambda _: Client(self.runtime.base_url, self.tokens['owner']),
                    lambda client, _: client.request('GET', '/api/v1' + endpoint))
                self.report['api'].append(result)
        def device_report(client, index):
            device = self.devices[index]; device['seq'] += 1
            return client.request('POST', '/api/v1/device/reports', body={'bootId': device['bootId'], 'seq': device['seq'],
                'eventId': str(uuid.uuid4()), 'executionId': device['executionId'], 'type': 'PROGRESS', 'progress': min(device['seq'], 99)})
        for active in (3, 5):
            self.report['device'].append(self.closed_loop('device-active-' + str(active), active, 30,
                lambda index: Client(self.runtime.base_url, device=self.devices[index]), device_report))
        for size, count, concurrency in [('1MiB', 3, 1), ('1MiB', 3, 3), ('17MiB', 5, 5)]:
            label = 'upload-' + size + '-c' + str(concurrency)
            self.phase(label); start = time.perf_counter_ns()
            results, failures = [], []
            with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
                futures = [executor.submit(self.upload, index, size, True) for index in range(count)]
                for future in concurrent.futures.as_completed(futures):
                    try: results.append(future.result())
                    except Exception as error: failures.append(type(error).__name__)
            end = time.perf_counter_ns(); self.phase('between-phases')
            self.report['uploads'].extend(results)
            self.report['uploadBatches'].append({'phase': label, 'sizeLabel': size, 'files': count, 'successfulFiles': len(results),
                'failedFiles': len(failures), 'failureTypes': failures, 'concurrency': concurrency,
                'wallSeconds': (end-start)/1e9, 'payloadBytes': count*len(self.files[size]), 'versionIds': [r['versionId'] for r in results],
                'complete202Ms': {'p50': percentile([r['complete202Ms'] for r in results], .5), 'p95': percentile([r['complete202Ms'] for r in results], .95)},
                'createToConfirmedObservedMs': {'p50': percentile([r['createToConfirmedObservedMs'] for r in results], .5), 'p95': percentile([r['createToConfirmedObservedMs'] for r in results], .95)}})
            self.write('report.json', self.report)
            require(not failures, 'upload_batch_failed_inspect_per_file_records')

    def finish(self, success):
        if self.sampler:
            self.report['resources'] = self.sampler.stop()
        if not hasattr(self, 'before'):
            self.runtime.stop()
            return
        quiescent = not self.seeded
        if self.seeded:
            self.phase('quiescence-check') if not self.sampler else print('T031 phase: quiescence-check', flush=True)
            deadline = time.monotonic() + 180
            while True:
                inspected = self.command('fixture-inspect-' + uuid.uuid4().hex[:8], FIXTURE, ['inspect', self.checkpoint])
                if not any(inspected['pendingWork'].values()): quiescent = True; break
                require(time.monotonic() < deadline, 'fixture_not_quiescent_retained'); time.sleep(1)
            if success:
                input_path, output_path = self.run / 'timings-input.json', self.run / 'timings.json'
                self.write(input_path.name, {'checkpointPath': str(self.checkpoint), 'versionIds': [r['versionId'] for r in self.report['uploads']],
                    'expectedFiles': {r['versionId']: {'size': r['size'], 'sha256': r['sha256']} for r in self.report['uploads']}})
                inspected = self.command('timings', INSPECTOR, ['inspect', input_path, output_path], output=output_path)
                require(inspected['versionCount'] == 12, 'twelve_actual_valid_registrations_required')
                self.report['verifiedRegistrations'] = inspected['versionCount']
            self.runtime.wait_empty_queues()
        self.runtime.stop()
        if self.seeded and quiescent:
            cleaned = self.command('fixture-cleanup', FIXTURE, ['cleanup', self.checkpoint])
            require(cleaned.get('originalBusinessDataUnchanged') is True, 'cleanup_original_guard_failed')
            self.report['fixtureCleanup'] = cleaned
        require(self.runtime.cleanup_empty_topology(), 'own_topology_not_deleted')
        after_path = self.run / 'after.json'
        after = self.command('after', RETAINED, ['preflight', after_path], output=after_path)
        FINAL.compare_original(self.before, after)
        objects = self.run / 'original-objects.json'
        old = self.command('original-objects', RETAINED, ['original-objects', self.run / 'baseline.json', objects], output=objects)
        original_object_count = len(self.before['snapshot']['tables']['evidence_version']['rows'])
        require(old.get('allOriginalFormalObjectBytesVerified') is True and old.get('originalFormalObjectCount') == original_object_count, 'all_baseline_objects_required')
        require(FINAL.binding() == self.report['binding'], 'production_changed_during_measurement')
        self.report.update(originalTwentyFourTablesUnchanged=True, originalFormalObjectsVerified=original_object_count,
                           originalCredentialsAndEnvUnchanged=True, productionBindingUnchanged=True,
                           ownBackendStopped=True, ownTopologyDeleted=True)
        if success:
            expected_events = sum(item['samples'] for item in self.report['device']) + 5 * (3 + 5)
            # Use the fixture snapshot acquired before cleanup, not a new business query.
            fixture_log = max(self.run.glob('fixture-inspect-*.log'), key=lambda path: path.stat().st_mtime_ns)
            fixture = json.loads(fixture_log.read_text())
            actual_events = fixture['currentTableCounts']['device_event'] - self.before['tableCounts']['device_event']
            require(actual_events == expected_events, 'all_reports_must_create_unique_device_events')
            self.report['deviceEventReconciliation'] = {'measured': expected_events-40, 'warmup': 40, 'actualNewRows': actual_events}
            # Observe natural TTL expiry after the backend has stopped; this is outside measurement.
            deadline = time.monotonic() + int(self.environment.get('DEVICE_PRESENCE_CACHE_TTL_SECONDS', '60')) + 5
            while True:
                presence = expired_presence(self.environment, self.environment['DEVICE_PRESENCE_KEY_PREFIX'], [d['id'] for d in self.devices])
                if presence['allExpired']: break
                require(time.monotonic() < deadline, 'own_presence_keys_did_not_expire')
                time.sleep(1)
            self.report['presenceCleanup'] = presence
        self.report['runtime'] = self.runtime.report


def main():
    os.umask(0o077)
    WORK.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (WORK / 'run.lock').open('a') as lock:
        try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: raise SystemExit('Another performance runner is active')
        directory = WORK / (dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex[:8])
        directory.mkdir(mode=0o700)
        run = Run(directory); failure = None
        try:
            run.measure()
        except Exception as error:
            failure = type(error).__name__
            if isinstance(error, MVP.DemoError): run.report['failedCheck'] = str(error)
        try:
            run.finish(failure is None)
        except Exception as error:
            run.report['cleanupFailureType'] = type(error).__name__
            if isinstance(error, MVP.DemoError): run.report['cleanupFailedCheck'] = str(error)
            failure = failure or type(error).__name__
        finally:
            run.runtime.stop()
            if run.sampler: run.sampler.stop()
        run.report.update(passed=failure is None, failureType=failure, completedAt=MVP.now())
        run.write('report.json', run.report)
        run.write('../latest.json', {'runDirectory': str(directory.relative_to(ROOT)), 'report': str((directory/'report.json').relative_to(ROOT)), 'passed': failure is None})
        print(json.dumps({'passed': failure is None, 'report': str((directory/'report.json').relative_to(ROOT)), 'failedCheck': run.report.get('failedCheck'), 'cleanupFailedCheck': run.report.get('cleanupFailedCheck')}))
        return 0 if failure is None else 1


if __name__ == '__main__': sys.exit(main())
