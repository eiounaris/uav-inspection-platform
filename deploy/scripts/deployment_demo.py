#!/usr/bin/env python3
"""Run the retained business demo with the real three-worker simulator for T032.

Execute from a prepared isolated repository/environment. This deliberately uses
the normal packaged application's messaging and ledger workers, with no fault
injection. It inherits the existing demo's preservation, locking and recheck
rules. The one temporary simulator configuration contains device credentials;
it is private and removed even when the simulator fails.
"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

from backend_env import ROOT


spec = importlib.util.spec_from_file_location('deployment_retained_demo', ROOT / 'deploy/scripts/run-demo.py')
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)
runtime = demo.runtime


class DeploymentDemo(demo.FullDemo):
    def __init__(self, *arguments, **keywords):
        super().__init__(*arguments, **keywords)
        self.report.update(task='T032', scope='DEPLOYMENT_REPRODUCTION_RETAINED_DEMO')

    def task_write(self, label, suffix, body, actor='operator-admin'):
        result = super().task_write(label, suffix, body, actor)
        if label == 'start_three_device_task':
            self.run_real_simulator()
        return result

    def run_real_simulator(self):
        self.check('realSimulator' not in self.report, 'deployment_simulator_started_once')
        devices = []
        for index in range(3):
            identifier = self.resources['deviceIds'][index]
            devices.append({'deviceId': identifier, 'deviceToken': self.device_tokens[identifier],
                            'executionId': self.resources['executionIds'][index],
                            'requirementId': self.resources['requirementIds'][index]})
        config = self.run / 'simulator-private-config.json'
        log = self.run / 'simulator.log'
        state = self.run / 'simulator-state'
        safe_options = {'formatVersion': 1, 'count': 3, 'cycles': 2, 'intervalSeconds': 0.1,
                        'progressStep': 10, 'uploadEveryCycles': 0, 'repeatEveryCycles': 0,
                        'networkOutageCycles': [], 'completeUploads': True,
                        'pngPath': 'examples/files/inspection-sample.png'}
        options = {**safe_options, 'baseUrl': self.base_url, 'stateDirectory': str(state), 'devices': devices}
        self.report['realSimulator'] = {'passed': False, 'options': safe_options,
            'devices': [{key: device[key] for key in ('deviceId', 'executionId', 'requirementId')} for device in devices],
            'temporaryCredentialConfig': runtime.local_path(config), 'temporaryCredentialConfigDeleted': False,
            'stateDirectory': runtime.local_path(state), 'command': 'python3 simulator/swarm.py --config <private-local-config>'}
        self.checkpoint()
        created_config = False
        try:
            self.check(not config.exists() and not log.exists() and not state.exists(),
                       'deployment_simulator_uses_fresh_run_paths')
            runtime.private_bytes(config, (json.dumps(options, ensure_ascii=False) + '\n').encode())
            created_config = True
            self.check(config.stat().st_mode & 0o777 == 0o600, 'simulator_credentials_config_is_0600')
            descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'wb') as output:
                result = subprocess.run([sys.executable, str(ROOT / 'simulator/swarm.py'), '--config', str(config)],
                                        cwd=ROOT, env=self.environment, stdout=output,
                                        stderr=subprocess.STDOUT, timeout=120)
            self.check(result.returncode == 0, 'real_simulator_process_exits_successfully')
            summary_path = state / 'summary.json'
            summary = runtime.safe_json(summary_path)
            self.check(summary.get('formatVersion') == 1 and summary.get('passed') is True
                       and summary.get('deviceCount') == 3 and summary.get('cycles') == 2,
                       'real_simulator_has_three_workers_two_cycles')
            workers = summary.get('devices')
            self.check(isinstance(workers, list) and len(workers) == 3
                       and {worker.get('index') for worker in workers} == {1, 2, 3},
                       'real_simulator_reports_each_worker_once')
            for worker in workers:
                self.check(worker.get('passed') is True and worker.get('pendingReport') is False
                           and worker.get('pendingUpload') is False and worker.get('reportsAccepted') == 2
                           and worker.get('httpRequests') == 2 and worker.get('duplicates') == 0
                           and worker.get('uploadsSubmitted') == 0 and worker.get('partsUploaded') == 0
                           and worker.get('simulatedOfflineCycles') == 0 and worker.get('transientFailures') == 0,
                           'real_simulator_accepts_two_new_reports_per_worker_without_pending_work')
            files = [summary_path, log]
            for device in devices:
                path = state / (device['deviceId'] + '.json')
                value = runtime.safe_json(path)
                self.check(value.get('deviceId') == device['deviceId']
                           and value.get('outstandingReport') is None and value.get('outstandingUpload') is None,
                           'real_simulator_durable_device_state_is_acknowledged')
                files.append(path)
            secrets = [device['deviceToken'] for device in devices]
            for path in files:
                raw = path.read_bytes()
                self.check(path.stat().st_mode & 0o777 == 0o600
                           and not any(secret.encode() in raw for secret in secrets),
                           'retained_simulator_artifacts_are_private_and_contain_no_device_tokens')
            self.report['realSimulator'].update(passed=True, summary=summary,
                reportsAccepted=sum(worker['reportsAccepted'] for worker in workers),
                artifacts=[{'path': runtime.local_path(path), 'sha256': runtime.sha(path.read_bytes())} for path in files])
        finally:
            if created_config:
                config.unlink(missing_ok=True)
            self.report['realSimulator']['temporaryCredentialConfigDeleted'] = not config.exists()
            self.checkpoint()


def main():
    # Reuse the established CLI, private paths, locks, output index and modes.
    original = demo.FullDemo
    demo.FullDemo = DeploymentDemo
    try:
        demo.main()
    finally:
        demo.FullDemo = original


if __name__ == '__main__':
    try:
        main()
    except runtime.DemoError as failure:
        print('[FAIL] ' + str(failure), file=sys.stderr)
        sys.exit(1)
    except Exception as failure:
        print('[FAIL] Deployment demo failed (' + type(failure).__name__ + '); inspect private reports', file=sys.stderr)
        sys.exit(1)
