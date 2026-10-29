"""Fail-closed checks for the final acceptance coordinator, without external services."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('final_acceptance', Path(__file__).with_name('verify-final.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FinalAcceptanceSafetyTest(unittest.TestCase):
    def baseline(self):
        names = [f'table_{index}' for index in range(23)] + ['verification_record']
        return {'snapshot': {'tables': {name: {'schemaSha256': 'schema', 'rows': {'old-id': 'all-fields-and-blob-hash'}} for name in names}},
                'migrationHistory': {'allFourteenRows': 'hash'}, 'demoCredentialsSha256': 'credentials', 'envSha256': 'env'}

    def test_parent_pass_cannot_hide_a_failed_child_check(self):
        with self.assertRaises(module.AcceptanceError):
            module.check_names([{'passed': True, 'checks': [{'check': 'real_child', 'passed': False}]}])

    def test_missing_component_evidence_cannot_pass_any_ac(self):
        values = module.gates({})
        self.assertEqual(len(values), 12)
        self.assertFalse(any(values.values()))

    def test_unchanged_full_snapshot_is_accepted(self):
        value = self.baseline(); module.compare_original(value, copy.deepcopy(value))

    def test_same_row_count_cannot_hide_changed_original_blob(self):
        value = self.baseline(); other = copy.deepcopy(value)
        other['snapshot']['tables']['table_0']['rows']['old-id'] = 'changed-hash'
        with self.assertRaises(module.AcceptanceError): module.compare_original(value, other)

    def test_unexpected_fixture_rows_are_rejected(self):
        value = self.baseline(); other = copy.deepcopy(value)
        other['snapshot']['tables']['table_1']['rows']['leftover-fixture'] = 'hash'
        with self.assertRaises(module.AcceptanceError): module.compare_original(value, other)

    def test_only_three_new_final_mvp_verifications_are_allowed(self):
        value = self.baseline(); other = copy.deepcopy(value)
        other['snapshot']['tables']['verification_record']['rows'].update({str(i): 'new' for i in range(3)})
        module.compare_original(value, other, True)
        with self.assertRaises(module.AcceptanceError): module.compare_original(value, other)
        other['snapshot']['tables']['verification_record']['rows']['fourth'] = 'new'
        with self.assertRaises(module.AcceptanceError): module.compare_original(value, other, True)

    def test_changed_migration_history_is_rejected(self):
        value = self.baseline(); other = copy.deepcopy(value); other['migrationHistory']['allFourteenRows'] = 'changed'
        with self.assertRaises(module.AcceptanceError): module.compare_original(value, other)

    def test_evidence_cannot_escape_private_workspace(self):
        with self.assertRaises(module.AcceptanceError): module.local(Path('/tmp/unrelated-report.json'))


if __name__ == '__main__': unittest.main()
