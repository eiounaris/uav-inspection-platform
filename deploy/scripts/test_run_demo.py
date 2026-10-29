"""Safety boundaries for the retained demo's read-only and credential paths."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('full_demo', Path(__file__).with_name('run-demo.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DemoSafetyTest(unittest.TestCase):
    def reader(self, status):
        demo = module.FullDemo.__new__(module.FullDemo)
        demo.report = {'checks': []}
        versions = ['old', 'new', 'middle', 'south']
        demo.resources = {'taskId': 'task', 'subtaskIds': ['subtask'], 'versionIds': versions,
                          'newVersionId': 'new', 'revokedGrantId': 'revoked', 'activeGrantId': 'active',
                          'expectedFiles': {version: {'sha256': 'a' * 64, 'size': 74} for version in versions}}
        methods, access = [], []

        def call(label, method, suffix, actor='owner-admin', **options):
            methods.append(method)
            if label == 'retained_completed_task': return {'status': 'COMPLETED', 'completedAt': '2026-10-27T00:00:00Z'}
            if label == 'retained_fixed_version':
                return {'sha256': 'a' * 64, 'size': 74, 'reviewStatus': 'APPROVED', 'anchor': {'status': 'CONFIRMED'}}
            if label == 'retained_task_grants':
                return {'items': [{'id': 'active', 'status': status}, {'id': 'revoked', 'status': 'REVOKED'}]}
            if label == 'recipient_current_visible_versions':
                return {'items': [{'id': 'new'}] if status == 'APPROVED' else []}
            if label == 'recipient_exact_version_scope': access.append((suffix, options['expected']))
            return {}

        demo.call = call
        return demo, methods, access

    def test_active_grant_recheck_uses_gets_and_only_opens_its_exact_version(self):
        demo, methods, access = self.reader('APPROVED')
        demo.read_business()
        self.assertEqual(set(methods), {'GET'})
        self.assertEqual([path for path, status in access if status == 200], ['/evidence-versions/new'])
        self.assertEqual(sum(status == 403 for _, status in access), 3)

    def test_expired_grant_recheck_does_not_reapprove_or_write_verifications(self):
        demo, methods, access = self.reader('EXPIRED')
        demo.read_business()
        self.assertEqual(set(methods), {'GET'})
        self.assertTrue(all(status == 403 for _, status in access))
        self.assertEqual(demo.report['retainedActiveGrantStatus'], 'EXPIRED')

    def test_unexpected_share_state_is_not_reported_as_success(self):
        demo, _, _ = self.reader('REVOKED')
        with self.assertRaises(module.runtime.DemoError): demo.read_business()

    def test_signed_download_can_be_received_in_memory_but_never_persisted(self):
        demo = module.FullDemo.__new__(module.FullDemo)
        demo.secrets = ['TEST_S3_ACCESS_IDENTIFIER']
        demo.sequence = 0
        demo.run = Path('private-test-run')
        demo.base_url = 'http://127.0.0.1:8080'
        demo.report = {'checks': [], 'requests': []}
        url = 'http://127.0.0.1:3900/object?X-Amz-Credential=TEST_S3_ACCESS_IDENTIFIER&X-Amz-Signature=SYNTHETIC'

        class Response:
            status = 200
            headers = {'Content-Type': 'application/json', 'X-Trace-Id': 't029-private-test-run-1'}
            def __enter__(self): return self
            def __exit__(self, *args): return False
            def read(self): return json.dumps({'code': 'OK', 'message': 'ok', 'data': {'url': url, 'expiresAt': '2026-10-27T00:00:00Z'},
                                               'traceId': self.headers['X-Trace-Id']}).encode()

        with patch.object(module.runtime.HTTP, 'open', return_value=Response()):
            value = demo.request('signed_memory_only', 'POST', '/api/v1/evidence-versions/' + '0' * 36 + '/download-url')
        self.assertEqual(value['url'], url)
        self.assertNotIn('TEST_S3_ACCESS_IDENTIFIER', json.dumps(demo.report))
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'forbidden.json'
            with self.assertRaises(module.runtime.DemoError): demo.safe_write(target, value)
            self.assertFalse(target.exists())


if __name__ == '__main__': unittest.main()
