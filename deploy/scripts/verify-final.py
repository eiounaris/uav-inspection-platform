#!/usr/bin/env python3
"""Run fresh AC-01..AC-12 evidence serially against one fixed integrated build."""
import argparse
import datetime as dt
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile

from backend_env import ROOT, backend_environment, fabric_environment, java21

WORK = ROOT / '.local/final-acceptance'
INSPECTOR = 'com.uav.inspection.demo.RetainedDemoInspector'
JAR = ROOT / 'backend/target/inspection-backend-0.1.0.jar'
CHAIN_JAR = ROOT / 'chaincode/target/evidence-chaincode-0.1.0.jar'
COMPONENTS = [
    ('devices', 'verify-devices.py', 'device-probe'),
    ('swarm', 'verify-swarm.py', 'swarm-probe'),
    ('multipart', 'verify-multipart.py', 'multipart-probe'),
    ('verification', 'verify-verifications.py', 'verification-probe'),
    ('sharing', 'verify-sharing.py', 'sharing-probe'),
    ('acceptance', 'verify-acceptance.py', 'acceptance-probe'),
    ('regression', 'verify-regression.py', 'regression-probe'),
    ('cleanup', 'verify-cleanup.py', 'cleanup-probe'),
    ('messaging', 'verify-messaging.py', 'messaging-probe'),
    ('recovery', 'verify-recovery.py', 'recovery-probe'),
    ('faults', 'verify-faults.py', 'fault-matrix'),
    ('chaincode', '../fabric/verify-chaincode.py', None),
    ('ui', 'verify-ui.py', 'ui-probe'),
    ('database', 'verify-database.py', 'database-probe'),
]
PRIVATE = re.compile(rb'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}|-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----|[?&]X-Amz-(?:Signature|Credential)=', re.I)


class AcceptanceError(RuntimeError):
    """Fixed check names and safe local paths only."""


def require(condition, name):
    if not condition: raise AcceptanceError(name)


def sha(data): return hashlib.sha256(data).hexdigest()


def read(path):
    data = json.loads(Path(path).read_text())
    require(isinstance(data, dict), 'json_object_required')
    return data


def local(path):
    path = Path(path).resolve()
    require(path.is_relative_to((ROOT / '.local').resolve()), 'evidence_must_remain_inside_local')
    return path


def rel(path): return str(Path(path).resolve().relative_to(ROOT))


def private_json(path, value):
    data = (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()
    require(not PRIVATE.search(data), 'private_material_cannot_enter_matrix_report')
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.parent / ('.final-' + uuid.uuid4().hex + '.tmp')
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'wb') as output: output.write(data)
        os.replace(temporary, path)
    finally: temporary.unlink(missing_ok=True)


def module(name, path):
    specification = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(value)
    return value


def framed_hash(paths):
    digest = hashlib.sha256()
    for path in sorted(paths):
        name, data = str(path.relative_to(ROOT)).encode(), path.read_bytes()
        digest.update(len(name).to_bytes(8, 'big')); digest.update(name)
        digest.update(len(data).to_bytes(8, 'big')); digest.update(data)
    return digest.hexdigest()


def binding():
    classes = ROOT / 'backend/target/classes'
    paths = [p for p in classes.rglob('*') if p.is_file()]
    require(bool(paths), 'compiled_backend_required')
    with zipfile.ZipFile(JAR) as archive:
        for path in paths:
            require(archive.read('BOOT-INF/classes/' + path.relative_to(classes).as_posix()) == path.read_bytes(),
                    'packaged_backend_matches_all_compiled_classes_and_resources')
    source = [p for directory in ('backend/src/main', 'chaincode/src/main') for p in (ROOT / directory).rglob('*') if p.is_file()]
    source += [ROOT / p for p in ('backend/pom.xml', 'chaincode/pom.xml', 'chaincode/Dockerfile')]
    return {'packagedJarSha256': sha(JAR.read_bytes()), 'chaincodeJarSha256': sha(CHAIN_JAR.read_bytes()),
            'productionClassesSha256': framed_hash(paths), 'productionSourceSha256': framed_hash(source),
            'dependencyClasspathSha256': sha((ROOT / 'backend/target/database-probe.classpath').read_bytes())}


def check_names(documents):
    names = set()
    for value in documents:
        for check in value.get('checks', []):
            if isinstance(check, str): names.add(check)
            elif isinstance(check, dict):
                require(check.get('passed') is True, 'failed_child_check_cannot_be_hidden_by_parent_pass')
                names.add(check.get('check') or check.get('name'))
    return names


def compare_original(before, after, allow_three_verifications=False):
    original, current = before['snapshot']['tables'], after['snapshot']['tables']
    require(set(original) == set(current) and len(original) == 24, 'exact_twenty_four_business_tables_required')
    for name, table in original.items():
        other = current[name]
        require(table['schemaSha256'] == other['schemaSha256'], 'original_schema_unchanged_' + name)
        require(all(other['rows'].get(key) == value for key, value in table['rows'].items()), 'original_rows_unchanged_' + name)
        delta = len(other['rows']) - len(table['rows'])
        require(delta == (3 if allow_three_verifications and name == 'verification_record' else 0), 'only_expected_row_additions_' + name)
    require(before['migrationHistory'] == after['migrationHistory'], 'all_original_migration_fields_unchanged')
    require(before['demoCredentialsSha256'] == after['demoCredentialsSha256'] and before['envSha256'] == after['envSha256'],
            'original_credentials_and_env_unchanged')


def gates(evidence):
    def docs(name): return evidence.get(name, [])
    def checks(name, *required): return set(required).issubset(check_names(docs(name)))
    def flag(name, field): return any(value.get(field) is True for value in docs(name))
    def number(name, field, minimum): return any(isinstance(value.get(field), (int, float)) and value[field] >= minimum for value in docs(name))
    swarm = next((value for value in docs('swarm') if 'fixtureDeviceIds' in value), {})
    results = {row['result'] for value in docs('verification') for row in value.get('verificationResults', [])}
    return {
        'AC-01': flag('regression', 'permissionAndPaginationVerified') and checks('sharing', 'unapproved_download_forbidden_http_403') and flag('regression', 'exactOldNewVersionGrantsVerified'),
        'AC-02': 3 <= len(swarm.get('fixtureDeviceIds', [])) <= 5 and swarm.get('simulator', {}).get('passed') is True and checks('swarm', 'initial_summary_has_three_missing_requirements', 'old_duplicate_replays_without_presence_or_event_mutation'),
        'AC-03': checks('devices', 'online_status_becomes_offline_from_server_time_without_status_write') and flag('devices', 'redisUnavailableFallsBackToMysql') and swarm.get('largerHistoricalVersionNo', 0) > swarm.get('currentVersionNo', 0) and checks('swarm', 'old_device_recovers_online_http_200'),
        'AC-04': flag('multipart', 'distinctJvmProcesses') and checks('multipart', 'resume_uses_a_different_real_jvm_process', 'concurrent_complete_creates_exactly_one_durable_finalize_event', 'new_client_resumed_png_finalizes') and checks('cleanup', 'unreferenced_old_candidate_removed_while_retry_source_parts_remain', 'verifying_source_parts_and_current_live_candidate_are_protected'),
        'AC-05': flag('acceptance', 'returnedAndCorrectedReviewHistoryVerified') and number('acceptance', 'confirmedVersions', 7) and checks('acceptance', 'correction_does_not_inherit_returned_review', 'returned_and_corrected_review_history_preserved_with_real_confirmations') and flag('regression', 'parallelReviewUniqueRecordVerified'),
        'AC-06': flag('chaincode', 'chaincodeContractAndRealReceiptsVerified') and flag('chaincode', 'deployedExecutableMatchesBuild'),
        'AC-07': {'MATCH', 'MISMATCH'} <= results and flag('verification', 'copyNeverChangesFormalData'),
        'AC-08': {'FILE_READ_FAILED', 'CHAIN_QUERY_FAILED', 'ANCHOR_UNCONFIRMED'} <= results and checks('verification', 'missing_private_original_status_200', 'real_ledger_query_response_lost_status_200', 'real_ledger_found_false_status_200', 'unconfirmed_result_is_backed_by_actual_missing_fabric_record'),
        'AC-09': flag('sharing', 'expiryUsesDatabaseDeadline') and checks('sharing', 'unapproved_download_forbidden_http_403', 'unreviewed_unanchored_version_cannot_be_approved_http_409', 'pending_chain_alone_cannot_be_approved_http_409', 'confirmed_but_unreviewed_version_cannot_be_approved_http_409', 'expired_grant_cannot_issue_download_http_403', 'revocation_prevents_new_url_issuance_http_403'),
        'AC-10': flag('sharing', 'existingSignedUrlSurvivesGrantRevocationUntilItsOriginalExpiry') and checks('sharing', 'actual_sigv4_deadline_is_bounded_by_300_and_grant_remaining_lifetime', 'three_successful_task_and_shared_url_issuances_are_audited_not_download_success', 'revocation_prevents_new_url_issuance_http_403'),
        'AC-11': flag('messaging', 'distinctJvmProcesses') and flag('messaging', 'temporaryFixtureCredentialsDeleted') and checks('messaging', 'all_sixteen_real_duplicate_deliveries_are_manual_acked') and flag('faults', 'originalBusinessDataUnchanged') and number('faults', 'realLedgerRegistrations', 6) and flag('faults', 'repeatedUnknownPlansVerified') and flag('recovery', 'defaultScheduledRecoveryEnabled') and flag('recovery', 'originalTransactionAndCommitBytesPreserved'),
        'AC-12': flag('acceptance', 'historicalLateWorkerRejected') and checks('acceptance', 'submit_rejects_missing_required_file_http_409', 'pending_review_and_chain_block_completion_http_409', 'pending_chain_alone_blocks_completion_http_409', 'pending_review_alone_blocks_completion_after_real_confirmation_http_409', 'current_required_version_five_wins_over_larger_historical_six', 'completed_task_rejects_new_upload_http_409', 'completed_task_rejects_draft_completion_http_409', 'idempotent_completed_upload_does_not_add_a_terminal_version') and flag('regression', 'parallelSubmitAndCompleteVerified'),
    }


class Matrix:
    def __init__(self, environment, run):
        self.environment, self.run = environment, run
        self.java = java21(environment)
        self.classpath = os.pathsep.join([str(ROOT / 'backend/target/test-classes'), str(ROOT / 'backend/target/classes'),
                                          (ROOT / 'backend/target/database-probe.classpath').read_text().strip()])
        self.runtime = module('final_runtime', ROOT / 'deploy/scripts/verify-mvp.py')
        self.regression = module('final_exclusivity', ROOT / 'deploy/scripts/verify-regression.py')
        self.secrets = {value.encode() for key, value in environment.items() if len(value) >= 6 and any(part in key for part in ('PASSWORD', 'SECRET', 'ACCESS_KEY', 'TOKEN'))}
        credentials = read(Path(environment['DEMO_CREDENTIALS_PATH']))
        for group in ('passwords', 'droneTokens'):
            self.secrets.update(value.encode() for value in credentials.get(group, {}).values() if isinstance(value, str) and value)
        if credentials.get('deviceToken'): self.secrets.add(credentials['deviceToken'].encode())

    def write(self, path, value):
        encoded = json.dumps(value, ensure_ascii=False).encode()
        require(not any(secret in encoded for secret in self.secrets), 'known_secret_cannot_enter_matrix_report')
        private_json(path, value)

    def exclusive(self):
        self.runtime.no_other_backends()
        self.regression.no_other_consumers(self.environment)

    def command(self, argv, log, timeout=1200):
        descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            process = subprocess.Popen(argv, cwd=ROOT, env=self.environment, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            try: code = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM)
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: os.killpg(process.pid, signal.SIGKILL); process.wait(timeout=10)
                raise AcceptanceError('component_timed_out_inspect_' + rel(log)) from None
        require(code == 0, 'component_failed_inspect_' + rel(log))

    def guard(self, name):
        self.exclusive()
        directory = self.run / 'guards'; directory.mkdir(exist_ok=True, mode=0o700)
        output, log = directory / (name + '.json'), directory / (name + '.log')
        self.command([self.java, '-Djava.awt.headless=true', '-Duser.timezone=Asia/Shanghai', '-cp', self.classpath,
                      INSPECTOR, 'preflight', str(output)], log, 120)
        value = read(output); require(value.get('passed') is True, 'twenty_four_table_guard_failed')
        return value

    def original_objects(self):
        directory = self.run / 'guards'
        suffix = uuid.uuid4().hex[:8]
        output, log = directory / ('original-objects-' + suffix + '.json'), directory / ('original-objects-' + suffix + '.log')
        self.command([self.java, '-Djava.awt.headless=true', '-Duser.timezone=Asia/Shanghai', '-cp', self.classpath,
                      INSPECTOR, 'original-objects', str(self.run / 'baseline.json'), str(output)], log, 180)
        value = read(output)
        expected = len(read(self.run / 'baseline.json')['snapshot']['tables']['evidence_version']['rows'])
        require(value.get('passed') is True and value.get('allOriginalFormalObjectBytesVerified') is True
                and value.get('originalFormalObjectCount') == expected, 'all_original_formal_objects_must_be_re_read')
        return {'passed': True, 'report': rel(output), 'originalFormalObjectCount': expected,
                'allOriginalFormalObjectBytesVerified': True}

    def chain_binding(self, directory):
        sys.path.insert(0, str(ROOT / 'deploy/fabric'))
        chain = module('final_chain_binding', ROOT / 'deploy/fabric/chaincode.py')
        lock, deployed = read(chain.DIRECTORY / 'artifact-lock.json'), read(chain.DIRECTORY / 'deployment.json')
        digest = sha(CHAIN_JAR.read_bytes()); dockerfile = sha((ROOT / 'chaincode/Dockerfile').read_bytes())
        require(digest == lock['jar_sha256'] == deployed['jar_sha256'] and dockerfile == lock['dockerfile_sha256'] == deployed['dockerfile_sha256'], 'chaincode_build_matches_original_deployment_lock')
        services = []
        for org in (1, 2):
            definition = chain.committed(org); require(definition is not None, 'both_committed_chaincode_definitions_exist')
            chain.verified_policy(definition, org)
            chain.verify_approved_package(org, deployed['packages'][str(org)])
            command = list(chain.__dict__['compose'].__globals__['COMPOSE'])
            command += ['--env-file', str(chain.RUNTIME_ENV), '-f', str(ROOT / 'deploy/fabric/chaincode.compose.yml'), 'ps', '-q', f'evidence-org{org}']
            container = subprocess.check_output(command, cwd=ROOT, env=self.environment, text=True).strip()
            require(bool(re.fullmatch(r'[0-9a-f]{12,64}', container)), 'actual_chaincode_container_required')
            image = subprocess.check_output(['docker', 'inspect', '--format', '{{.Image}}', container], text=True).strip()
            actual = subprocess.check_output(['docker', 'exec', container, 'sha256sum', '/app/chaincode.jar'], text=True).split()[0]
            require(image == deployed['image_id'] and actual == digest, 'both_running_chaincode_executables_match_built_jar')
            services.append({'organization': f'Org{org}MSP', 'imageId': image, 'jarSha256': actual})
        value = {'passed': True, 'deployedExecutableMatchesBuild': True, 'jarSha256': digest, 'dockerfileSha256': dockerfile,
                 'actualServices': services, 'bothCommittedPoliciesAndApprovedPackagesVerified': True}
        self.write(directory / 'binding.json', value)
        return value

    def component(self, name, script, work, args=(), index=None):
        directory = self.run / 'components' / (name + '-' + uuid.uuid4().hex[:8])
        directory.mkdir(parents=True, mode=0o700)
        self.exclusive()
        if name == 'chaincode':
            source = ROOT / '.local/fabric/chaincode/verification.json'
            if source.exists():
                shutil.copyfile(source, directory / 'previous-verification.json'); os.chmod(directory / 'previous-verification.json', 0o600)
            bound = self.chain_binding(directory)
            self.command([sys.executable, str(script)], directory / 'launcher.log')
            shutil.copyfile(source, directory / 'verification.json'); os.chmod(directory / 'verification.json', 0o600)
            os.chmod(source, 0o600)
            value = read(directory / 'verification.json'); checks = value['checks']
            require(checks.get('missing_query_both_orgs') is True, 'chaincode_missing_query_verified')
            require(set(checks) == {'missing_query_both_orgs', 'orgb_register_orga_orgb_query', 'same_hash_idempotent', 'different_hash_conflict', 'orga_write_denied', 'invalid_version', 'versions_isolated'}, 'all_chaincode_contract_cases_present')
            require(checks['same_hash_idempotent']['evidence_writes'] == 0 and checks['same_hash_idempotent']['original_tx_id_preserved'], 'same_hash_does_not_repeat_business_registration')
            for key in ('different_hash_conflict', 'orga_write_denied', 'invalid_version'):
                require(checks[key]['rejected_before_submission'] and checks[key]['ledger_unchanged'], 'chaincode_rejections_leave_ledger_unchanged')
            for key in ('orgb_register_orga_orgb_query', 'same_hash_idempotent', 'versions_isolated'):
                require(len(checks[key]['peer_receipts']) == 2 and all(row['validation_code'] == 0 for row in checks[key]['peer_receipts']), 'both_peers_confirm_all_three_actual_valid_transactions')
            require(len(value['records']) == 2, 'two_new_chaincode_business_records')
            summary = {**bound, 'chaincodeContractAndRealReceiptsVerified': True, 'realLedgerRegistrations': 2,
                       'validTransactionsIncludingNoWriteDuplicate': 3, 'verification': rel(directory / 'verification.json')}
            self.write(directory / 'verify.json', summary)
            paths = [directory / 'verify.json', directory / 'verification.json', directory / 'binding.json']
            primary, evidence_root = directory / 'verify.json', directory
        else:
            index = index or ROOT / '.local' / work / 'latest.json'
            previous = index.read_bytes() if index.exists() else None
            self.command([sys.executable, str(script), *args], directory / 'launcher.log', 1800 if name == 'faults' else 1200)
            require(index.exists() and index.read_bytes() != previous, 'fresh_successful_component_index_required_' + name)
            entry = read(index)
            if name == 'database': primary = local(ROOT / entry['reports']['verify'])
            else: primary = local(ROOT / entry['report'])
            require(read(primary).get('passed') is True, 'fresh_component_pass_required_' + name)
            evidence_root = primary.parent
            paths = sorted(evidence_root.rglob('*.json'))
            if name == 'faults':
                for part in read(primary)['components'].values(): paths += sorted(local(ROOT / part['report']).parent.rglob('*.json'))
        documents = [read(path) for path in paths if path.name != 'previous-verification.json']
        check_names(documents)
        require(any(value.get('originalBusinessDataUnchanged') is True or value.get('originalBusinessRowsUnchanged') is True for value in documents)
                or name in ('chaincode', 'database'), 'component_own_original_data_protection_required_' + name)
        sources = [script, ROOT / 'deploy/scripts/backend_env.py']
        launcher = script.read_text()
        for probe in re.findall(r'PROBE\s*=\s*["\']([^"\']+)', launcher):
            path = ROOT / 'backend/src/test/java' / (probe.replace('.', '/') + '.java')
            if path.exists(): sources.append(path)
        source_hash = framed_hash(sources)
        files = [{'path': rel(path), 'sha256': sha(path.read_bytes())} for path in sorted(set(paths))]
        value = {'passed': True, 'report': rel(primary), 'evidenceDirectory': rel(evidence_root), 'files': files,
                 'sourceFiles': [rel(path) for path in sources], 'toolSourceSha256': source_hash,
                 'httpRequests': sum(int(v.get('caseCount', 0)) for v in documents if 'requests' in v),
                 'recordedCheckCount': sum(int(v.get('checkCount', 0)) for v in documents)}
        self.write(directory / 'component.json', value)
        return value, documents

    def artifacts(self, report):
        paths = set(self.run.rglob('*.json')) | set(self.run.rglob('*.log'))
        for item in report['components'].values():
            directory = local(ROOT / item['evidenceDirectory'])
            paths.update(directory.rglob('*.json')); paths.update(directory.rglob('*.log'))
            paths.update(local(ROOT / f['path']) for f in item['files'])
        for path in paths:
            data = path.read_bytes()
            require(path.stat().st_mode & 0o077 == 0, 'private_artifact_permissions_required_' + rel(path))
            require(not PRIVATE.search(data) and not any(secret in data for secret in self.secrets), 'private_artifact_material_detected_' + rel(path))
        require(not any(path.name in ('credentials.json', 'uploader-config.json') for path in paths), 'temporary_fixture_credentials_must_be_deleted')
        return {'passed': True, 'privateJsonAndLogCount': len(paths), 'artifactMode': '0600', 'knownSecretsJwtPrivateKeysAndSignedUrlsAbsent': True,
                'temporaryFixtureCredentialsDeleted': True}


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resume', type=Path, help='Reuse successful fresh components from this final matrix when production artifacts remain identical')
    parser.add_argument('--acceptance-date', type=dt.date.fromisoformat,
                        default=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date(),
                        help='Client acceptance date, ISO format; execution UTC timestamps are recorded separately')
    args = parser.parse_args()
    environment = fabric_environment({k: v for k, v in backend_environment().items() if not k.startswith('SPRING_')}, local=True)
    environment['TZ'] = 'Asia/Shanghai'; java21(environment)
    WORK.mkdir(parents=True, exist_ok=True, mode=0o700); os.chmod(WORK, 0o700)
    lock_fd = os.open(WORK / '.run.lock', os.O_RDWR | os.O_CREAT, 0o600)
    with os.fdopen(lock_fd, 'w') as lock:
        try: fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: raise AcceptanceError('another_final_matrix_owns_run_lock') from None
        run = local(args.resume) if args.resume else WORK / (dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8])
        require(run.parent == WORK.resolve(), 'one_direct_final_matrix_directory_required')
        run.mkdir(exist_ok=bool(args.resume), mode=0o700)
        runner = Matrix(environment, run)
        current_binding = binding()
        if args.resume:
            report = read(run / 'report.json')
            require(report['binding'] == current_binding, 'production_artifacts_changed_start_new_final_matrix')
            report['passed'] = False
        else:
            report = {'formatVersion': 1, 'task': 'T030', 'clientAcceptanceDate': args.acceptance_date.isoformat(), 'passed': False,
                      'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'binding': current_binding, 'components': {}, 'acceptance': {}}
        baseline = read(run / 'baseline.json') if args.resume else runner.guard('initial')
        if not args.resume: runner.write(run / 'baseline.json', baseline)
        else: compare_original(baseline, runner.guard('resume-' + uuid.uuid4().hex[:8]), 'mvp' in report['components'])
        runner.write(run / 'report.json', report)
        planned = [(name, (ROOT / 'deploy/scripts' / script).resolve(), work, (), None) for name, script, work in COMPONENTS]
        # Preserve every successful T029 group as an independent end-to-end read-only check.
        for i, source in enumerate(sorted((ROOT / '.local/full-demo').glob('*/report.json'))):
            directory = source.parent
            if read(source).get('passed') is True or (directory / 'latest-resume.json').exists():
                planned.append((f'retained-full-{i + 1}', ROOT / 'deploy/scripts/run-demo.py', None,
                                ('--recheck', rel(directory)), directory / 'latest-recheck.json'))
        require(any(name.startswith('retained-full-') for name, *_ in planned), 'retained_t029_demonstration_required')
        mvp_index = read(ROOT / '.local/mvp-demo/latest.json')
        mvp = local(ROOT / (mvp_index.get('runDirectory') or mvp_index.get('run_directory')))
        planned.append(('mvp', ROOT / 'deploy/scripts/verify-mvp.py', None, ('--recheck', rel(mvp)), mvp / 'latest-recheck.json'))
        print('Final matrix directory: ' + rel(run), flush=True)
        evidence = {}
        active = None
        try:
            for position, (name, script, work, arguments, index) in enumerate(planned):
                active = name
                existing = report['components'].get(name)
                if existing:
                    require(all(sha(local(ROOT / value['path']).read_bytes()) == value['sha256'] for value in existing['files']), 'retained_component_evidence_changed_' + name)
                    require(framed_hash([ROOT / path for path in existing['sourceFiles']]) == existing['toolSourceSha256'], 'completed_component_tool_source_changed_' + name)
                    evidence[name] = [read(ROOT / value['path']) for value in existing['files'] if Path(value['path']).name != 'previous-verification.json']
                    check_names(evidence[name]); print('[PASS] Reuse this-matrix ' + name, flush=True); continue
                require(binding() == current_binding, 'production_binding_changed_during_acceptance')
                before = runner.guard(f'{position:02d}-{name}-before-' + uuid.uuid4().hex[:6])
                compare_original(baseline, before)
                report['activeComponent'] = name; runner.write(run / 'report.json', report)
                print('Running ' + name, flush=True)
                item, documents = runner.component(name, script, work, arguments, index)
                after = runner.guard(f'{position:02d}-{name}-after-' + uuid.uuid4().hex[:6])
                compare_original(baseline, after, name == 'mvp')
                require(binding() == current_binding, 'production_binding_changed_after_component')
                item['allOriginalTwentyFourTablesVerified'] = True
                report['components'][name] = item; evidence[name] = documents
                runner.write(run / 'report.json', report)
                print('[PASS] ' + name, flush=True)
            outcomes = gates(evidence)
            report['acceptance'] = {key: {'passed': value} for key, value in outcomes.items()}
            require(len(outcomes) == 12 and all(outcomes.values()), 'all_twelve_acceptance_scenarios_must_have_complete_evidence')
            report['originalObjects'] = runner.original_objects()
            compare_original(baseline, runner.guard('final-' + uuid.uuid4().hex[:8]), True)
            runner.exclusive()
            report['artifacts'] = runner.artifacts(report)
            require(binding() == current_binding, 'final_production_binding_unchanged')
            report.update({'passed': True, 'completedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                           'allOriginalBusinessRowsPreserved': True, 'onlyFinalMvpAppendedThreeVerifications': True,
                           'noProjectBackendOrProbeRunning': True, 'allComponentEvidenceFreshWithinThisMatrix': True})
            report.pop('activeComponent', None); report.pop('failedComponent', None)
            runner.write(run / 'report.json', report)
            runner.write(WORK / 'latest.json', {'runDirectory': rel(run), 'report': rel(run / 'report.json')})
        except Exception as failure:
            report['passed'] = False; report['failedComponent'] = active; report['failureType'] = type(failure).__name__
            if isinstance(failure, AcceptanceError): report['failedCheck'] = str(failure)
            runner.write(run / 'report.json', report)
            raise
    print('[PASS] AC-01..AC-12 final matrix: ' + rel(run / 'report.json'))


if __name__ == '__main__':
    try: main()
    except AcceptanceError as failure: print('[FAIL] ' + str(failure), file=sys.stderr); sys.exit(1)
    except Exception as failure: print('[FAIL] Final matrix failed (' + type(failure).__name__ + '); inspect private reports', file=sys.stderr); sys.exit(1)
