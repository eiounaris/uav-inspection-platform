"""Offline isolation checks for infrastructure Compose and persistence probes."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from deployment_config import bind_probe_project, infrastructure_project


SPEC = importlib.util.spec_from_file_location("isolated_infra_probe", Path(__file__).with_name("verify-infra.py"))
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)


class ExplicitProjectTest(unittest.TestCase):
    def test_ambient_compose_setting_does_not_redirect_a_literal_config(self):
        config = {"INFRA_COMPOSE_PROJECT_NAME": "uav-t032-abc123", "S3_ENDPOINT": "http://127.0.0.1:19000",
                  "S3_ACCESS_KEY": "test-key", "S3_SECRET_KEY": "test-secret", "S3_REGION": "garage",
                  "S3_BUCKET": "uav-test", "RABBITMQ_USERNAME": "test-user", "RABBITMQ_PASSWORD": "test-password"}
        with patch.dict(os.environ, {"COMPOSE_PROJECT_NAME": "unrelated-project",
                                     "INFRA_COMPOSE_PROJECT_NAME": "also-unrelated"}):
            probe = PROBE.InfrastructureProbe(config, "a" * 32)
            with patch.object(PROBE.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "ok\n", "")) as execute:
                self.assertEqual(probe.compose_exec("redis", "true"), "ok")
            arguments = execute.call_args.args[0]
            self.assertEqual(arguments[arguments.index("--project-name") + 1], "uav-t032-abc123")
            self.assertNotIn("INFRA_COMPOSE_PROJECT_NAME", execute.call_args.kwargs["env"])
            self.assertEqual(infrastructure_project({}), "uav-inspection")

    def test_invalid_project_is_rejected_before_any_subprocess(self):
        for value in ("UPPERCASE", "-leading", "has space", "../another", "a;touch /tmp/no", "a" * 64, 12):
            with self.subTest(value=value), patch.object(PROBE.subprocess, "run") as execute:
                with self.assertRaises(PROBE.ProbeError):
                    PROBE.InfrastructureProbe({"INFRA_COMPOSE_PROJECT_NAME": value}, "a" * 32)
                execute.assert_not_called()


class PersistedProjectBindingTest(unittest.TestCase):
    @staticmethod
    def state(path, status="SEEDED", project="uav-t032-abc123"):
        value = {"schema_version": 1, "run_id": "a" * 32, "status": status, "seeded": ["mysql"],
                 "cleaned": [], "verified": False}
        if project is not None:
            value["project_name"] = project
        path.write_text(json.dumps(value))
        return value

    def test_seed_and_check_cannot_resume_another_projects_active_state(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            self.state(path, "SEED_FAILED")
            for phase in ("seed", "check"):
                arguments = argparse.Namespace(phase=phase, run_id=None)
                with self.subTest(phase=phase), self.assertRaises(PROBE.ProbeError):
                    PROBE.select_state(arguments, path, "uav-t032-other")

    def test_original_legacy_state_is_only_accepted_in_the_original_default_project(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            self.state(path, project=None)
            arguments = argparse.Namespace(phase="check", run_id=None)
            state = PROBE.select_state(arguments, path, "uav-inspection")
            self.assertEqual(state["project_name"], "uav-inspection")
            with self.assertRaises(PROBE.ProbeError):
                PROBE.select_state(arguments, path, "uav-t032-abc123")

    def test_new_probe_is_bound_and_completed_state_can_start_a_new_isolated_probe(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            arguments = argparse.Namespace(phase="seed", run_id=None)
            first = PROBE.select_state(arguments, path, "uav-t032-new")
            self.assertEqual(first["project_name"], "uav-t032-new")
            self.state(path, status="CLEANED", project="uav-inspection")
            second = PROBE.select_state(arguments, path, "uav-t032-new")
            self.assertEqual(second["project_name"], "uav-t032-new")
            self.assertNotEqual(second["run_id"], "a" * 32)

    def test_failed_seed_resume_keeps_the_original_run_and_seeded_resource_list(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            original = self.state(path, "SEED_FAILED")
            resumed = PROBE.select_state(argparse.Namespace(phase="seed", run_id=None), path, "uav-t032-abc123")
            self.assertEqual(resumed, original)


if __name__ == "__main__":
    unittest.main()
