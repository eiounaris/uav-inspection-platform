#!/usr/bin/env python3
"""Run the T027 crash/recovery matrix against retained local dependencies."""
import argparse
import datetime as dt
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

from backend_env import ROOT, backend_environment

WORK = ROOT / '.local/fault-matrix'
COMPONENTS = (('publish', 'verify-publish-crashes.py'), ('fabric', 'verify-fabric-crashes.py'))
REQUIRED = {
    'publish': ('beforePublishCrashVerified', 'afterConfirmCrashVerified', 'afterCandidatePutCrashVerified',
                'naturalLeaseExpiryVerified', 'defaultAutomaticRecoveryVerified',
                'duplicateDeliveryAfterDoneAcknowledged', 'originalEventIdsPreserved',
                'uniqueBusinessResultsVerified', 'ownedRabbitTopologyDeleted'),
    'fabric': ('defaultScheduledRecoveryEnabled', 'naturalProcessingLeaseExpiryUsed',
               'originalTransactionBytesPreserved', 'originalSavedCommitBytesPreserved',
               'postSubmitRecordReconciliationVerified', 'repeatedUnknownPlansVerified',
               'ownedRabbitTopologyDeleted', 'fabricRecordsRetainedAppendOnly'),
}


def production_runtime_hash():
    directory = ROOT / 'backend/target/classes'
    classpath = ROOT / 'backend/target/database-probe.classpath'
    if not directory.is_dir() or not classpath.is_file():
        raise RuntimeError('Build backend with the database-probe profile before running the matrix')
    digest = hashlib.sha256()
    for path in sorted(p for p in directory.rglob('*') if p.is_file()):
        name = str(path.relative_to(directory)).encode()
        data = path.read_bytes()
        digest.update(len(name).to_bytes(8, 'big'))
        digest.update(name)
        digest.update(len(data).to_bytes(8, 'big'))
        digest.update(data)
    digest.update(classpath.read_bytes())
    return digest.hexdigest()


def private_json(path, value):
    temporary = path.parent / ('.matrix-' + uuid.uuid4().hex + '.tmp')
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            json.dump(value, output, indent=2)
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def local_path(value):
    if not isinstance(value, str):
        raise RuntimeError('Component report path is missing')
    path = (ROOT / value).resolve(strict=True)
    if not path.is_relative_to((ROOT / '.local').resolve()):
        raise RuntimeError('Component report must remain inside .local')
    return path


def module_for(name, script):
    specification = importlib.util.spec_from_file_location('fault_matrix_' + name, script)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resume', type=Path, help='Reuse successful components of a private matrix run')
    arguments = parser.parse_args()
    environment = backend_environment()
    credentials = Path(environment['DEMO_CREDENTIALS_PATH'])
    env_hash = hashlib.sha256((ROOT / '.env').read_bytes()).hexdigest()
    credential_hash = hashlib.sha256(credentials.read_bytes()).hexdigest()
    runtime_hash = production_runtime_hash()
    WORK.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(WORK, 0o700)
    descriptor = os.open(WORK / '.run.lock', os.O_RDWR | os.O_CREAT, 0o600)
    with os.fdopen(descriptor, 'w') as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('Another T027 fault matrix owns the run lock') from None
        if arguments.resume is None:
            name = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
            run = WORK / name
            run.mkdir(mode=0o700)
            summary = {'formatVersion': 1, 'passed': False, 'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                       'envSha256Before': env_hash, 'credentialsSha256Before': credential_hash,
                       'productionRuntimeSha256': runtime_hash, 'components': {}}
        else:
            run = arguments.resume.resolve(strict=True)
            if not run.is_relative_to(WORK.resolve()) or run == WORK.resolve():
                raise RuntimeError('--resume requires one private fault-matrix run directory')
            summary = json.loads((run / 'report.json').read_text())
            if summary.get('envSha256Before') != env_hash or summary.get('credentialsSha256Before') != credential_hash:
                raise RuntimeError('Original configuration or demo credentials differ from this matrix run')
            if summary.get('productionRuntimeSha256') != runtime_hash:
                raise RuntimeError('Production classes or runtime dependencies changed; start a new matrix run')
            summary['passed'] = False
        summary['runDirectory'] = str(run.relative_to(ROOT))
        report = run / 'report.json'
        print('Matrix directory: ' + str(run.relative_to(ROOT)), flush=True)
        private_json(report, summary)
        active = None
        try:
            for component, filename in COMPONENTS:
                active = component
                previous = summary['components'].get(component)
                if previous is not None:
                    evidence = json.loads(local_path(previous['report']).read_text())
                    if evidence.get('passed') is not True:
                        raise RuntimeError('Previously completed component no longer reports success')
                    print('[PASS] Reuse completed ' + component + ' component', flush=True)
                    continue
                script = ROOT / 'deploy/scripts' / filename
                module = module_for(component, script)
                latest = module.WORK / 'latest.json'
                old_latest = latest.read_bytes() if latest.is_file() else None
                log = run / (component + '-launcher-' + uuid.uuid4().hex[:8] + '.log')
                descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                print('Running ' + component + ' crash/recovery component', flush=True)
                with os.fdopen(descriptor, 'w') as output:
                    outcome = subprocess.run([sys.executable, str(script)], cwd=ROOT, env=environment,
                                             stdout=output, stderr=subprocess.STDOUT, timeout=900)
                if outcome.returncode != 0:
                    raise RuntimeError(component + ' component failed; inspect ' + str(log.relative_to(ROOT)))
                if not latest.is_file() or latest.read_bytes() == old_latest:
                    raise RuntimeError(component + ' component did not publish fresh successful evidence')
                index = json.loads(latest.read_text())
                evidence_path = local_path(index.get('report'))
                evidence = json.loads(evidence_path.read_text())
                if evidence.get('passed') is not True or evidence.get('originalBusinessDataUnchanged') is not True:
                    raise RuntimeError(component + ' component did not prove successful original-data preservation')
                if any(evidence.get(field) is not True for field in REQUIRED[component]):
                    raise RuntimeError(component + ' component did not prove every required fault boundary')
                registrations = evidence.get('realLedgerRegistrations')
                if not isinstance(registrations, int) or registrations != 3:
                    raise RuntimeError(component + ' component must prove three actual normal registrations')
                summary['components'][component] = {'passed': True, 'report': str(evidence_path.relative_to(ROOT)),
                                                    'realLedgerRegistrations': registrations}
                private_json(report, summary)
                print('[PASS] ' + component + ' crash/recovery component', flush=True)
            if hashlib.sha256((ROOT / '.env').read_bytes()).hexdigest() != env_hash or hashlib.sha256(credentials.read_bytes()).hexdigest() != credential_hash:
                raise RuntimeError('Original .env or demo credentials changed during the fault matrix')
            if production_runtime_hash() != runtime_hash:
                raise RuntimeError('Production classes or runtime dependencies changed while probes were running')
            summary.update({'passed': True, 'completedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                            'realLedgerRegistrations': sum(item['realLedgerRegistrations'] for item in summary['components'].values()),
                            'originalBusinessDataUnchanged': True, 'envFileUnchanged': True, 'credentialFileUnchanged': True})
            summary.pop('failedComponent', None)
            private_json(report, summary)
            private_json(WORK / 'latest.json', {'run_directory': str(run.relative_to(ROOT)), 'report': str(report.relative_to(ROOT))})
        except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError):
            summary['passed'] = False
            summary['failedComponent'] = active
            private_json(report, summary)
            raise
    print('[PASS] T027 fault matrix: ' + str(report.relative_to(ROOT)))


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as failure:
        print('[FAIL] ' + str(failure), file=sys.stderr)
        sys.exit(1)
