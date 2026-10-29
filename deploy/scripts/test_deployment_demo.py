"""Offline checks for the deployment demo's temporary device credential boundary."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import uuid


spec = importlib.util.spec_from_file_location('deployment_demo', Path(__file__).with_name('deployment_demo.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DeploymentSimulatorTest(unittest.TestCase):
    def make_demo(self, directory):
        value = module.DeploymentDemo.__new__(module.DeploymentDemo)
        value.run = Path(directory)
        value.resources = {key: [str(uuid.uuid4()) for _ in range(4)]
                           for key in ('deviceIds', 'executionIds', 'requirementIds')}
        value.device_tokens = {identifier: f'{index + 1:064x}' for index, identifier in enumerate(value.resources['deviceIds'])}
        value.report = {'checks': []}
        value.environment = {}
        value.base_url = 'http://127.0.0.1:39001'
        value.checkpoint = lambda: None
        return value

    def run_process(self, value, *, returncode=0, pending=False, token_in_state=False):
        def execute(arguments, **options):
            config = Path(arguments[-1])
            self.assertTrue(config.exists())
            self.assertEqual(config.stat().st_mode & 0o777, 0o600)
            parsed = json.loads(config.read_text())
            self.assertEqual(parsed['count'], 3)
            self.assertEqual(parsed['intervalSeconds'], .1)
            self.assertEqual(parsed['uploadEveryCycles'], 0)
            self.assertEqual(parsed['networkOutageCycles'], [])
            for secret in value.device_tokens.values():
                self.assertNotIn(secret, '\0'.join(arguments))
            if returncode:
                return subprocess.CompletedProcess(arguments, returncode)
            state = Path(parsed['stateDirectory'])
            state.mkdir(mode=0o700)
            workers = []
            for index, device in enumerate(parsed['devices'], 1):
                worker = {'index': index, 'passed': True, 'pendingReport': pending,
                          'pendingUpload': False, 'reportsAccepted': 2, 'httpRequests': 2,
                          'duplicates': 0, 'uploadsSubmitted': 0, 'partsUploaded': 0,
                          'simulatedOfflineCycles': 0, 'transientFailures': 0}
                workers.append(worker)
                item = {'deviceId': device['deviceId'], 'outstandingReport': None, 'outstandingUpload': None}
                if token_in_state: item['leakedToken'] = device['deviceToken']
                module.runtime.private_bytes(state / (device['deviceId'] + '.json'), json.dumps(item).encode())
            module.runtime.private_bytes(state / 'summary.json', json.dumps(
                {'formatVersion': 1, 'passed': True, 'cycles': 2, 'deviceCount': 3, 'devices': workers}).encode())
            options['stdout'].write(b'Device 1: PASS\nDevice 2: PASS\nDevice 3: PASS\n')
            return subprocess.CompletedProcess(arguments, 0)
        return execute

    def temporary_run(self):
        return tempfile.TemporaryDirectory(prefix='t032-deployment-demo-test-', dir=module.ROOT / '.local')

    def assert_deleted(self, value):
        self.assertFalse((value.run / 'simulator-private-config.json').exists())
        self.assertTrue(value.report['realSimulator']['temporaryCredentialConfigDeleted'])
        encoded = json.dumps(value.report)
        self.assertTrue(all(secret not in encoded for secret in value.device_tokens.values()))

    def test_actual_worker_summary_and_state_are_required_and_hashed_without_tokens(self):
        with self.temporary_run() as directory:
            value = self.make_demo(directory)
            with patch.object(module.subprocess, 'run', side_effect=self.run_process(value)):
                value.run_real_simulator()
            self.assert_deleted(value)
            result = value.report['realSimulator']
            self.assertTrue(result['passed'])
            self.assertEqual(result['reportsAccepted'], 6)
            self.assertEqual(len(result['artifacts']), 5)
            for artifact in result['artifacts']:
                path = module.ROOT / artifact['path']
                self.assertEqual(module.runtime.sha(path.read_bytes()), artifact['sha256'])

    def test_nonzero_process_exit_removes_credentials_and_cannot_report_pass(self):
        with self.temporary_run() as directory:
            value = self.make_demo(directory)
            with patch.object(module.subprocess, 'run', side_effect=self.run_process(value, returncode=1)):
                with self.assertRaises(module.runtime.DemoError): value.run_real_simulator()
            self.assert_deleted(value)
            self.assertFalse(value.report['realSimulator']['passed'])

    def test_timeout_removes_temporary_credentials(self):
        with self.temporary_run() as directory:
            value = self.make_demo(directory)
            with patch.object(module.subprocess, 'run', side_effect=subprocess.TimeoutExpired(['simulator'], 120)):
                with self.assertRaises(subprocess.TimeoutExpired): value.run_real_simulator()
            self.assert_deleted(value)

    def test_pending_worker_cannot_hide_behind_top_level_pass(self):
        with self.temporary_run() as directory:
            value = self.make_demo(directory)
            with patch.object(module.subprocess, 'run', side_effect=self.run_process(value, pending=True)):
                with self.assertRaises(module.runtime.DemoError): value.run_real_simulator()
            self.assert_deleted(value)
            self.assertFalse(value.report['realSimulator']['passed'])

    def test_token_in_retained_state_is_rejected(self):
        with self.temporary_run() as directory:
            value = self.make_demo(directory)
            with patch.object(module.subprocess, 'run', side_effect=self.run_process(value, token_in_state=True)):
                with self.assertRaises(module.runtime.DemoError): value.run_real_simulator()
            self.assert_deleted(value)


if __name__ == '__main__': unittest.main()
