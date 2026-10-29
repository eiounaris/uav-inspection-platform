"""Offline deployment-isolation contracts; no Docker, network or private files."""

from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
import settings


class DeploymentSettingsTest(unittest.TestCase):
    def load(self, contents="", environment=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".env").write_text(contents)
            return settings.load_settings(root, environment or {})

    def test_default_namespace_remains_compatible_with_existing_unmarked_materials(self):
        configured = self.load()
        self.assertEqual(configured, settings.DEFAULT_SETTINGS)
        settings.check_namespace({}, configured)
        self.assertEqual(configured.peer_endpoint(1), "localhost:7051")
        self.assertEqual(configured.orderer_endpoint, "localhost:7050")

    def test_environment_overrides_literal_file_without_executing_values(self):
        configured = self.load("FABRIC_COMPOSE_PROJECT_NAME='isolated_file'\nFABRIC_ORDERER_PORT=17050 # host only\n",
                               {"FABRIC_COMPOSE_PROJECT_NAME": "isolated_env", "FABRIC_ORGA_PEER_PORT": "17051"})
        self.assertEqual(configured.project_name, "isolated_env")
        self.assertEqual(configured.orderer_port, 17050)
        self.assertEqual(configured.peer_endpoint(1), "localhost:17051")
        self.assertEqual(configured.compose_environment()["FABRIC_ORDERER_PORT"], "17050")

    def test_project_names_cannot_select_external_or_invalid_compose_scope(self):
        for value in ("", "../uav-inspection-fabric", "UPPER", "$(id)", "a b", "-project", "a" * 64):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                self.load(environment={"FABRIC_COMPOSE_PROJECT_NAME": value})

    def test_ports_are_ascii_distinct_and_inside_tcp_range(self):
        for value in ("0", "65536", "-1", " 7050", "７０５０", "1;id", "not-a-port"):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                self.load(environment={"FABRIC_ORDERER_PORT": value})
        with self.assertRaises(RuntimeError):
            self.load(environment={"FABRIC_ORDERER_PORT": "7051"})

    def test_image_is_an_explicit_reference_without_shell_or_implicit_latest(self):
        for image in ("uav-inspection/evidence-chaincode:t032-run", "localhost:5000/uav/evidence:0.1.0",
                      "uav/evidence@sha256:" + "a" * 64):
            self.assertEqual(self.load(environment={"FABRIC_CHAINCODE_IMAGE": image}).chaincode_image, image)
        for image in ("uav/evidence", "uav/evidence:latest", "$(id)", "uav/evidence:version with space", "uav/evidence\nother"):
            with self.subTest(image=image), self.assertRaises(RuntimeError):
                self.load(environment={"FABRIC_CHAINCODE_IMAGE": image})

    def test_custom_namespace_requires_matching_new_material_marker(self):
        configured = self.load(environment={"FABRIC_COMPOSE_PROJECT_NAME": "isolated-run"})
        with self.assertRaises(RuntimeError):
            settings.check_namespace({}, configured)
        settings.check_namespace({"deployment_namespace": configured.namespace()}, configured)
        with self.assertRaises(RuntimeError):
            settings.check_namespace({"deployment_namespace": configured.namespace()}, settings.DEFAULT_SETTINGS)
        port_drift = self.load(environment={"FABRIC_COMPOSE_PROJECT_NAME": "isolated-run", "FABRIC_ORDERER_PORT": "17050"})
        with self.assertRaises(RuntimeError):
            settings.check_namespace({"deployment_namespace": configured.namespace()}, port_drift)

    def test_literal_parser_rejects_duplicate_and_malformed_entries_without_echoing_secret(self):
        for contents in ("FABRIC_ORDERER_PORT=17050\nFABRIC_ORDERER_PORT=17051\n", "SECRET=hidden-secret\nnot-a-valid-entry\n",
                         "SECRET='hidden-secret\n"):
            with self.subTest(contents=contents):
                with self.assertRaises(RuntimeError) as failure:
                    self.load(contents)
                self.assertNotIn("hidden-secret", str(failure.exception))


class RuntimeIsolationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import network
        import chaincode
        cls.network, cls.chaincode = network, chaincode

    def configured(self):
        with tempfile.TemporaryDirectory() as directory:
            return settings.load_settings(Path(directory), {
                "FABRIC_COMPOSE_PROJECT_NAME": "isolated-run", "FABRIC_ORDERER_PORT": "17050",
                "FABRIC_ORDERER_ADMIN_PORT": "17053", "FABRIC_ORGA_PEER_PORT": "17051",
                "FABRIC_ORGB_PEER_PORT": "19051", "FABRIC_CHAINCODE_IMAGE": "uav-inspection/evidence-chaincode:t032-run"})

    def test_host_peer_and_orderer_endpoints_follow_one_validated_configuration(self):
        configured = self.configured()
        with patch.object(self.network, "SETTINGS", configured), patch.object(self.network, "execute") as execute:
            self.network.peer(2, "channel", "list")
            environment = execute.call_args.args[1]
            self.assertEqual(environment["CORE_PEER_ADDRESS"], "localhost:19051")
            execute.return_value = "{}"
            self.network.orderer("list")
            self.assertIn("localhost:17053", execute.call_args.args[0])
        with patch.object(self.chaincode, "SETTINGS", configured):
            peers = self.chaincode.endorsement_peers()
            self.assertIn("localhost:17051", peers)
            self.assertIn("localhost:19051", peers)

    def test_compose_receives_only_its_selected_scope_and_interpolation_settings(self):
        configured = self.configured()
        with patch.object(self.network, "SETTINGS", configured), patch.object(self.network, "COMPOSE", ["docker", "compose", "--project-name", configured.project_name]), \
                patch.object(self.network, "check_materials") as guard, patch.object(self.network, "RUNTIME_ENV") as runtime, \
                patch.object(self.network.subprocess, "run") as run:
            runtime.exists.return_value = False
            self.network.compose("stop")
            guard.assert_called_once_with()
            command = run.call_args.args[0]
            self.assertEqual(command[:4], ["docker", "compose", "--project-name", "isolated-run"])
            self.assertEqual(command[-1], "stop")
            self.assertEqual(run.call_args.kwargs["env"]["FABRIC_CHAINCODE_IMAGE"], configured.chaincode_image)
            self.assertEqual(run.call_args.kwargs["env"]["FABRIC_ORGB_PEER_PORT"], "19051")

    def test_material_guard_rejects_stop_before_any_docker_command(self):
        with patch.object(self.network, "check_materials", side_effect=RuntimeError("namespace drift")), \
                patch.object(self.network.subprocess, "run") as run:
            with self.assertRaises(RuntimeError):
                self.network.compose("stop")
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
