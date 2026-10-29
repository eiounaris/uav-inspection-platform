#!/usr/bin/env python3
"""Exercise the packaged T028 management UI in Chromium with isolated real business fixtures."""
import datetime as dt
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

from backend_env import ROOT, backend_environment, fabric_environment, java21

WORK = ROOT / '.local/ui-probe'
FIXTURE = 'com.uav.inspection.ui.probe.UiBrowserFixture'


def load_runner():
    spec = importlib.util.spec_from_file_location('t028_mvp_runtime', ROOT / 'deploy/scripts/verify-mvp.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    os.umask(0o077)
    environment = {k: v for k, v in backend_environment().items() if not k.startswith('SPRING_')}
    environment = fabric_environment(environment, local=True)
    environment['TZ'] = 'Asia/Shanghai'
    java = java21(environment)
    if not (ROOT / '.local/browser-tools/node_modules/playwright/index.mjs').is_file():
        raise RuntimeError('Install the local Playwright verification dependency as documented in backend/README.md')
    if not (ROOT / 'frontend-tests/browser.mjs').is_file():
        raise RuntimeError('The browser regression script is missing')
    runner = load_runner()
    runner.no_other_backends()
    WORK.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(WORK, 0o700)
    descriptor = os.open(WORK / '.run.lock', os.O_WRONLY | os.O_CREAT, 0o600)
    with os.fdopen(descriptor, 'w') as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('Another browser verification owns the run lock') from None
        name = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
        run = WORK / name
        run.mkdir(mode=0o700)
        runtime = runner.Demo(environment, java, run)
        # Keep a clearly separate exchange from retained M2 demonstration data.
        runtime.topology = 'inspection.ui.' + uuid.uuid4().hex
        runtime.exchange = runtime.topology + '.events'
        runtime.dead_exchange = runtime.topology + '.dead.events'
        runtime.queues = [runtime.topology + '.finalize', runtime.topology + '.anchor', runtime.topology + '.dead']
        fixture = run / 'fixture.json'
        summary = {'formatVersion': 1, 'passed': False, 'startedAt': runner.now(), 'runDirectory': str(run.relative_to(ROOT)),
                   'packagedJarSha256': runtime.jar_digest}
        node = None
        cleanup_error = None
        browser_error = None
        print('Run directory: ' + str(run.relative_to(ROOT)), flush=True)

        def helper(phase, filename):
            command = [java, '-Djava.awt.headless=true', '-Duser.timezone=Asia/Shanghai', '-cp', runtime.classpath,
                       FIXTURE, phase, str(fixture)]
            result = subprocess.run(command, env=environment, cwd=ROOT, capture_output=True, timeout=100)
            try:
                data = json.loads(result.stdout)
            except ValueError:
                raise RuntimeError('Browser fixture helper did not return safe JSON') from None
            runtime.safe_write(run / filename, data)
            if result.returncode != 0 or data.get('passed') is not True:
                raise RuntimeError('Browser fixture ' + phase + ' failed; inspect ' + str((run / filename).relative_to(ROOT)))
            return data

        try:
            runtime.start()
            runtime.safe_write(run / 'runtime.json', {'formatVersion': 1, 'applicationPid': runtime.process.pid,
                               'baseUrl': runtime.base_url, 'exchange': runtime.exchange, 'queues': runtime.queues,
                               'deadExchange': runtime.dead_exchange, 'backgroundProcessing': 'NORMAL_AUTOMATIC_DEFAULTS'})
            child = dict(environment)
            child.update({'UI_BASE_URL': runtime.base_url, 'UI_RUN_DIRECTORY': str(run), 'UI_JAVA': java,
                          'UI_CLASSPATH': runtime.classpath,
                          'PLAYWRIGHT_BROWSERS_PATH': str(ROOT / '.local/ms-playwright')})
            descriptor = os.open(run / 'browser-run.log', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w') as output:
                node = subprocess.Popen(['node', str(ROOT / 'frontend-tests/browser.mjs')], env=child, cwd=ROOT,
                                        stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
                try:
                    outcome = node.wait(timeout=720)
                except subprocess.TimeoutExpired:
                    os.killpg(node.pid, signal.SIGTERM)
                    try:
                        node.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(node.pid, signal.SIGKILL)
                        node.wait(timeout=10)
                    raise RuntimeError('Browser verification exceeded its bound; inspect the private browser report') from None
            browser = json.loads((run / 'browser.json').read_text())
            if outcome != 0 or browser.get('passed') is not True:
                raise RuntimeError('Browser flow failed; inspect ' + str((run / 'browser.json').relative_to(ROOT)))
            summary['browserPassed'] = True
        except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as failure:
            browser_error = failure
            summary['browserPassed'] = False
        finally:
            try:
                if fixture.is_file():
                    # Let already accepted business requests finish with the original automatic workers.
                    deadline = time.monotonic() + 100
                    while True:
                        inspection = helper('inspect', 'inspection.json')
                        if all(value == 0 for value in inspection.get('pendingWork', {}).values()):
                            break
                        if time.monotonic() >= deadline:
                            raise RuntimeError('Owned business work did not become quiescent; fixtures are preserved')
                        time.sleep(1)
                    runtime.wait_empty_queues()
                runtime.stop()
                if fixture.is_file():
                    cleaned = helper('cleanup', 'cleanup.json')
                    summary['originalBusinessDataUnchanged'] = cleaned.get('originalBusinessDataUnchanged') is True
                if not runtime.cleanup_empty_topology():
                    raise RuntimeError('Own message topology did not become empty')
                summary['ownedRabbitTopologyDeleted'] = True
            except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as failure:
                cleanup_error = failure
                summary['cleanupPassed'] = False
            finally:
                runtime.stop()
                summary['ownApplicationStopped'] = runtime.report.get('ownApplicationStopped') is True
        unchanged = hashlib.sha256(Path(environment['DEMO_CREDENTIALS_PATH']).read_bytes()).hexdigest() == runtime.original_credentials
        unchanged = unchanged and hashlib.sha256((ROOT / '.env').read_bytes()).hexdigest() == runtime.original_env
        summary['originalCredentialsAndEnvUnchanged'] = unchanged
        summary['cleanupPassed'] = cleanup_error is None
        summary['passed'] = browser_error is None and cleanup_error is None and unchanged and summary.get('originalBusinessDataUnchanged') is True
        summary['completedAt'] = runner.now()
        runtime.safe_write(run / 'verify.json', summary)
        if cleanup_error:
            raise RuntimeError(str(cleanup_error)) from None
        if browser_error:
            raise RuntimeError(str(browser_error)) from None
        if summary['passed'] is not True:
            raise RuntimeError('Browser flow did not prove data preservation')
        runtime.safe_write(WORK / 'latest.json', {'run_directory': str(run.relative_to(ROOT)), 'report': str((run / 'verify.json').relative_to(ROOT))})
    print('[PASS] T028 real browser flow: ' + str((run / 'verify.json').relative_to(ROOT)))


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as failure:
        print('[FAIL] ' + str(failure), file=sys.stderr)
        sys.exit(1)
