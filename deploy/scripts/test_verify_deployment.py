"""Offline checks that a deployment reproduction cannot reuse the original stack."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location('deployment_reproduction', Path(__file__).with_name('verify-deployment.py'))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CleanEnvironmentTest(unittest.TestCase):
    def test_all_existing_stack_endpoints_and_secrets_are_removed_without_mutating_parent(self):
        original = {prefix + 'TEST': 'original-value' for prefix in MODULE.PREFIXES}
        original.update({'JWT_SECRET': 'old-jwt', 'DEMO_CREDENTIALS_PATH': '/old/credentials.json',
                         'JAVA_TOOL_OPTIONS': '-Dspring.config.location=/old/config',
                         '_JAVA_OPTIONS': '-Ddb.password=old', 'JDK_JAVA_OPTIONS': '-Dserver.port=8080',
                         'PYTHONPATH': '/old/modules', 'PROJECT_JAVA_HOME': '/old/java',
                         'JAVA_HOME': '/selected/jdk21', 'PATH': '/usr/bin', 'LANG': 'C.UTF-8'})
        saved = dict(original)
        cleaned = MODULE.clean_environment(original)
        self.assertEqual(cleaned, {'JAVA_HOME': '/selected/jdk21', 'PATH': '/usr/bin', 'LANG': 'C.UTF-8'})
        self.assertEqual(original, saved)


class SourceCopyTest(unittest.TestCase):
    @staticmethod
    def git_output(paths):
        return subprocess.CompletedProcess([], 0, b'\0'.join(os.fsencode(p) for p in paths) + b'\0', b'')

    def test_copy_allowlist_excludes_runtime_even_if_git_lists_it(self):
        allowed = ['README.md', '.env.example', 'backend/src/main/java/App.java', 'chaincode/pom.xml',
                   'deploy/compose.yml', 'simulator/swarm.py']
        forbidden = ['.env', '.env.prod', '.local/demo-credentials.json', 'backend/.env',
                     'deploy/.env.local', 'backend/target/classes/App.class', 'chaincode/target/c.jar',
                     'deploy/scripts/__pycache__/a.pyc', 'frontend-tests/node_modules/a.js', '.git/config',
                     'deploy/secrets/credentials.json', 'deploy/local/runtime.env']
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in allowed + forbidden:
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text('fixture')
            with patch.object(MODULE.subprocess, 'run', return_value=self.git_output(allowed + forbidden)) as execute:
                actual = MODULE.source_files(root)
            self.assertEqual(actual, sorted(map(Path, allowed)))
            self.assertEqual(execute.call_args.kwargs['cwd'], root)

    def test_path_traversal_and_leaf_symlink_are_rejected(self):
        for name in ('../outside/secret', '/tmp/absolute-source'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                with patch.object(MODULE.subprocess, 'run', return_value=self.git_output([name])):
                    with self.assertRaises(MODULE.DeploymentError): MODULE.source_files(Path(directory))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root/'backend').mkdir(); (root/'private').write_text('private')
            (root/'backend/source.java').symlink_to(root/'private')
            with patch.object(MODULE.subprocess, 'run', return_value=self.git_output(['backend/source.java'])):
                with self.assertRaises(MODULE.DeploymentError): MODULE.source_files(root)

    def test_symlinked_parent_cannot_copy_an_external_regular_file(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as outside:
            root = Path(directory); (Path(outside)/'Secret.java').write_text('outside-data')
            (root/'backend').symlink_to(Path(outside), target_is_directory=True)
            with patch.object(MODULE.subprocess, 'run', return_value=self.git_output(['backend/Secret.java'])):
                with self.assertRaises(MODULE.DeploymentError): MODULE.source_files(root)


class ScopedOperationsTest(unittest.TestCase):
    def make_run(self):
        run = object.__new__(MODULE.Reproduction)
        run.copy = Path('/safe/private-run/workspace')
        run.infra_project = 'uav-repro-abc123-infra'
        run.fabric_project = 'uav-repro-abc123-fabric'
        run.command = Mock()
        return run

    def test_infrastructure_stop_uses_only_explicit_clone_project_env_and_compose(self):
        run = self.make_run(); run.compose('stop-copy', 'stop')
        label, args = run.command.call_args.args
        self.assertEqual(label, 'stop-copy')
        self.assertEqual(args, ['docker', 'compose', '--project-name', 'uav-repro-abc123-infra',
                               '--env-file', run.copy/'.env', '-f', run.copy/'deploy/compose.yml', 'stop'])
        self.assertNotIn('down', args)
        self.assertNotIn('-v', args)
        self.assertNotIn(MODULE.ROOT/'.env', args)

    def test_foreign_volume_is_rejected_instead_of_being_accepted_as_reproduced_data(self):
        run = self.make_run()
        listing = subprocess.CompletedProcess([], 0, '123456789abc\n', '')
        mount = [{'Type': 'volume', 'Name': 'uav-inspection_mysql-data'}]
        import json
        details = ' '.join(map(json.dumps, ['123456789abc' * 5 + '1234', '/new-container', 'sha256:image', 'running', mount]))
        inspecting = subprocess.CompletedProcess([], 0, details + '\n', '')
        with patch.object(MODULE.subprocess, 'run', side_effect=[listing, inspecting]):
            with self.assertRaises(MODULE.DeploymentError): run.docker_inventory(run.infra_project)

    def test_invalid_project_never_reaches_docker(self):
        run = self.make_run()
        with patch.object(MODULE.subprocess, 'run') as execute:
            with self.assertRaises(MODULE.DeploymentError): run.docker_inventory('bad project;echo nope')
            execute.assert_not_called()

    def test_only_known_fabric_image_volume_destinations_allow_anonymous_names(self):
        import json
        run = self.make_run()
        cases = [(run.fabric_project, '/etc/hyperledger/fabric', True),
                 (run.fabric_project, '/var/hyperledger', True),
                 (run.fabric_project, '/var/hyperledger/production', False),
                 (run.fabric_project, '/var/lib/mysql', False),
                 (run.infra_project, '/var/hyperledger', False)]
        for project, destination, allowed in cases:
            mount = [{'Type': 'volume', 'Name': 'a' * 64, 'Destination': destination}]
            details = ' '.join(map(json.dumps, ['b' * 64, '/container', 'sha256:image', 'running', mount]))
            responses = [subprocess.CompletedProcess([], 0, '123456789abc\n', ''),
                         subprocess.CompletedProcess([], 0, details + '\n', '')]
            with self.subTest(project=project, destination=destination), patch.object(MODULE.subprocess, 'run', side_effect=responses):
                if allowed:
                    self.assertEqual(run.docker_inventory(project)[0]['volumes'], ['a' * 64])
                else:
                    with self.assertRaises(MODULE.DeploymentError): run.docker_inventory(project)


class FailedFinalizationTest(unittest.TestCase):
    @staticmethod
    def make_run():
        run = object.__new__(MODULE.Reproduction)
        run.fabric_started = run.infra_started = True
        run.infra_project, run.fabric_project = 'uav-repro-test-infra', 'uav-repro-test-fabric'
        run.before = {'baseline': True}; run.report = {}
        run.stop_application = Mock(); run.phase = Mock(); run.python = Mock(); run.compose = Mock()
        run.original_guard = Mock(); run.docker_inventory = Mock(return_value=[{'status': 'exited'}])
        return run

    def test_failure_stopping_fabric_still_stops_infra_and_rechecks_original(self):
        run = self.make_run()
        run.python = Mock(side_effect=MODULE.DeploymentError('own_fabric_stop_failed'))
        with self.assertRaises(MODULE.DeploymentError): run.finish()
        run.compose.assert_called_once_with('final-stop-own-infra', 'stop')
        run.original_guard.assert_called_once_with(False)

    def test_failure_stopping_application_still_attempts_both_stacks_and_original_guard(self):
        run = self.make_run()
        run.stop_application = Mock(side_effect=MODULE.DeploymentError('own_application_stop_failed'))
        with self.assertRaises(MODULE.DeploymentError): run.finish()
        run.python.assert_called_once_with('final-stop-own-fabric', 'deploy/fabric/network.py', 'stop')
        run.compose.assert_called_once_with('final-stop-own-infra', 'stop')
        run.original_guard.assert_called_once_with(False)

    def test_unavailable_inventory_still_rechecks_the_original_stack(self):
        run = self.make_run()
        run.docker_inventory = Mock(side_effect=MODULE.DeploymentError('own_inventory_failed'))
        with self.assertRaises(MODULE.DeploymentError): run.finish()
        run.original_guard.assert_called_once_with(False)


if __name__ == '__main__': unittest.main()
