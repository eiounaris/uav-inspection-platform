"""Resource measurement semantics; all Docker invocations are mocked."""
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('perf_resources', Path(__file__).with_name('perf_resources.py'))
resources = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resources)


class ResourceMathTest(unittest.TestCase):
    def test_proc_comm_with_spaces_and_closing_parentheses_does_not_shift_cpu_fields(self):
        # /proc field numbers: state=3, utime=14, stime=15, threads=20,
        # starttime=22, vsize=23, rss=24. Other fields deliberately differ.
        fields = {number: str(number * 10) for number in range(3, 53)}
        fields.update({3: 'S', 14: '321', 15: '54', 20: '17', 22: '123456', 23: '819200', 24: '19'})
        raw = '42 (java worker ) nested)) ' + ' '.join(fields[number] for number in range(3, 53))
        parsed = resources.parse_proc_stat(raw, 4096)
        self.assertEqual((parsed['userTicks'], parsed['systemTicks']), (321, 54))
        self.assertEqual((parsed['threads'], parsed['startTimeTicks']), (17, 123456))
        self.assertEqual(parsed['rssBytes'], 19 * 4096)
        self.assertNotIn('java', json.dumps(parsed))
        with self.assertRaises(ValueError): resources.parse_proc_stat('42 (short) S 1 2', 4096)

    def test_tick_cpu_uses_hz_elapsed_seconds_and_an_explicit_core_denominator(self):
        previous = {'phaseId': 7, 'startTimeTicks': 12, 'monotonicNs': 1_000_000_000,
                    'userTicks': 100, 'systemTicks': 50}
        current = {**previous, 'monotonicNs': 3_000_000_000, 'userTicks': 280, 'systemTicks': 70}
        value = resources.cpu_interval(previous, current, 100, 2)
        self.assertEqual(value['cpuCorePercent'], 100)
        self.assertEqual(value['cpuAvailablePercent'], 50)
        # A second elapsed with those same 200 ticks represents two full cores.
        current['monotonicNs'] = 2_000_000_000
        value = resources.cpu_interval(previous, current, 100, 0.5)
        self.assertEqual(value['cpuCorePercent'], 200)
        self.assertEqual(value['cpuAvailablePercent'], 400)  # Never silently clamped.

    def test_absent_phase_crossing_reused_pid_and_reset_counters_have_no_rate(self):
        before = {'phaseId': 1, 'startTimeTicks': 10, 'monotonicNs': 1_000_000_000,
                  'userTicks': 100, 'systemTicks': 10}
        after = {**before, 'monotonicNs': 2_000_000_000, 'userTicks': 200}
        for previous, changed, reason in (
                (None, after, 'first_observation'),
                (before, {**after, 'phaseId': 2}, 'phase_boundary'),
                (before, {**after, 'startTimeTicks': 99}, 'pid_reused'),
                (before, {**after, 'userTicks': 1}, 'invalid_interval_or_counter_reset'),
                (before, {**after, 'monotonicNs': before['monotonicNs']}, 'invalid_interval_or_counter_reset')):
            with self.subTest(reason=reason):
                result = resources.cpu_interval(previous, changed, 100, 8)
                self.assertIsNone(result['cpuCorePercent'])
                self.assertEqual(result['cpuIntervalSeconds'], 0)
                self.assertEqual(result['cpuUnavailableReason'], reason)

    def test_decimal_and_binary_docker_memory_units_are_distinct(self):
        self.assertEqual(resources.parse_memory_bytes(' 1.5 MiB '), 1572864)
        self.assertEqual(resources.parse_memory_bytes('1.5 MB'), 1500000)
        self.assertEqual(resources.parse_memory_bytes('900 B'), 900)
        for invalid in ('unlimited', '-1 MiB', 'nan B', '1 XB'):
            self.assertIsNone(resources.parse_memory_bytes(invalid))


class DockerObservationTest(unittest.TestCase):
    def test_all_requested_containers_are_batched_and_only_resource_fields_survive(self):
        ids = ['a' * 64, 'b' * 64]
        lines = [json.dumps({'ID': identifier[:12], 'CPUPerc': '125.50%', 'MemUsage': '2 MiB / 4 GiB',
                             'MemPerc': '0.05%', 'PIDs': '17', 'Name': 'PRIVATE_CONTAINER_NAME',
                             'other': 'PRIVATE_DIAGNOSTIC'}) for identifier in ids]
        response = subprocess.CompletedProcess([], 0, '\n'.join(lines), 'PRIVATE_DOCKER_STDERR')
        with patch.object(resources.subprocess, 'run', return_value=response) as command:
            rows, errors = resources._docker_rows(ids)
        self.assertEqual(command.call_count, 1)
        self.assertEqual(command.call_args.args[0], ['docker', 'stats', '--no-stream', '--format', '{{json .}}', *ids])
        self.assertEqual(command.call_args.kwargs['timeout'], 8)
        self.assertEqual(errors, [])
        self.assertEqual(rows[0]['cpuPercentDocker'], 125.5)
        self.assertEqual(rows[0]['memoryUsageBytesDocker'], 2 * 1024 ** 2)
        self.assertEqual(rows[0]['memoryLimitBytesDocker'], 4 * 1024 ** 3)
        self.assertNotIn('PRIVATE', json.dumps([rows, errors]))

    def test_missing_or_malformed_rows_and_failure_diagnostics_are_not_invented_samples(self):
        identifier = 'a' * 64
        for stdout in ('[]', '{"ID":"' + 'c' * 12 + '"}', 'PRIVATE_INVALID_JSON'):
            with self.subTest(stdout=stdout):
                response = subprocess.CompletedProcess([], 0, stdout, 'PRIVATE_DOCKER_STDERR')
                with patch.object(resources.subprocess, 'run', return_value=response):
                    rows, errors = resources._docker_rows([identifier])
                self.assertEqual(rows, [])
                self.assertTrue(errors)
                self.assertNotIn('PRIVATE', json.dumps(errors))
        with patch.object(resources.subprocess, 'run', return_value=subprocess.CompletedProcess([], 2, '', 'PRIVATE_FAILURE')):
            rows, errors = resources._docker_rows([identifier])
        self.assertEqual(rows, [])
        self.assertEqual(errors, [{'source': 'docker_stats', 'type': 'NonzeroExit', 'returnCode': 2}])


class ResourceSummaryTest(unittest.TestCase):
    def make_sampler(self, directory):
        os.chmod(directory, 0o700)
        return resources.ResourceSampler(os.getpid(), Path(directory), [], interval_seconds=0.1)

    def test_unequal_intervals_repeated_phases_and_short_phase_keep_honest_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            sampler = self.make_sampler(directory)
            sampler._started = True
            sampler._start_ns, sampler._started_at = 0, 'synthetic-start'
            sampler._stream = io.StringIO()
            sampler._phase_events = [
                {'name': 'unmarked', 'phaseId': 0, 'monotonicNs': 0},
                {'name': 'steady', 'phaseId': 1, 'monotonicNs': 1_000_000_000},
                {'name': 'brief', 'phaseId': 2, 'monotonicNs': 6_000_000_000},
                {'name': 'steady', 'phaseId': 3, 'monotonicNs': 6_500_000_000}]

            def observation(phase, phase_id, second, cpu, elapsed, rss):
                return {'kind': 'backend', 'phase': phase, 'phaseId': phase_id,
                        'monotonicNs': int(second * 1e9), 'completedMonotonicNs': int((second + 0.001) * 1e9),
                        'processAvailable': True, 'cpuCorePercent': cpu, 'cpuAvailablePercent': cpu / 2 if cpu is not None else None,
                        'cpuIntervalSeconds': elapsed, 'rssBytes': rss, 'threads': 2,
                        'client': {'processAvailable': True, 'cpuCorePercent': cpu / 2 if cpu is not None else None,
                                   'cpuAvailablePercent': cpu / 4 if cpu is not None else None,
                                   'cpuIntervalSeconds': elapsed, 'rssBytes': 10}}

            sampler._rows = [observation('steady', 1, 1, None, 0, 100),
                             observation('steady', 1, 2, 50, 1, 200),
                             observation('steady', 1, 5, 100, 3, 300),
                             observation('brief', 2, 6.2, None, 0, 50),
                             observation('steady', 3, 6.6, None, 0, 400),
                             observation('steady', 3, 8.6, 200, 2, 200)]
            for began, completed, crossing, cpu in ((3, 5, False, 20), (5.8, 6.2, True, 9999), (9, 11, False, 9999)):
                sampler._rows.append({'kind': 'docker', 'phase': 'steady', 'phaseId': 1,
                                      'monotonicNs': int(began * 1e9), 'completedMonotonicNs': int(completed * 1e9),
                                      'phaseBoundaryCrossed': crossing, 'containers': [
                                          {'containerId': 'a' * 12, 'cpuPercentDocker': cpu, 'memoryUsageBytesDocker': 100}]})
            with patch.object(resources.time, 'monotonic_ns', return_value=10_000_000_000):
                summary = sampler.stop()
            steady, brief = summary['phases']['steady'], summary['phases']['brief']
            self.assertEqual(steady['durationSeconds'], 8.5)
            self.assertEqual(steady['backendSamples'], 5)
            self.assertEqual(steady['backendCpuObservedSeconds'], 6)
            self.assertEqual(steady['backendCpuCorePercentMean'], 125)
            self.assertEqual(steady['backendCpuCorePercentPeak'], 200)
            self.assertEqual(steady['backendCpuAvailablePercentMean'], 62.5)
            self.assertEqual(steady['backendRssBytesPeak'], 400)
            self.assertEqual(steady['clientCpuCorePercentMean'], 62.5)
            self.assertEqual(steady['dockerContainers']['a' * 12]['samples'], 1)
            self.assertEqual(steady['dockerContainers']['a' * 12]['cpuPercentDockerSampleMean'], 20)
            self.assertEqual(brief['backendSamples'], 1)
            self.assertIsNone(brief['backendCpuCorePercentMean'])
            self.assertEqual(brief['backendCpuObservedSeconds'], 0)
            self.assertIs(sampler.stop(), summary)
            output = Path(directory) / 'resource-summary.json'
            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)
            self.assertEqual(json.loads(output.read_text())['phases']['steady']['backendSamples'], 5)

    def test_short_real_self_sample_has_private_files_client_metrics_and_no_docker_calls(self):
        # Only observes this Python process; does not launch any external service.
        with tempfile.TemporaryDirectory() as directory:
            sampler = self.make_sampler(directory)
            with patch.object(resources.subprocess, 'run', side_effect=AssertionError('Docker must remain disabled')):
                sampler.start()
                sampler.mark_phase('short')
                deadline = time.monotonic() + 2
                while not any(row['phase'] == 'short' for row in sampler._rows) and time.monotonic() < deadline:
                    time.sleep(0.005)
                summary = sampler.stop()
            short = summary['phases']['short']
            self.assertGreaterEqual(short['backendSamples'], 1)
            self.assertEqual(short['clientSamples'], short['backendSamples'])
            # A busy test host can delay stop enough to take another sample.
            # The deterministic brief phase above covers zero-interval nulls.
            if not short['backendCpuIntervals']:
                self.assertIsNone(short['backendCpuCorePercentMean'])
                self.assertIsNone(short['clientCpuCorePercentMean'])
            self.assertEqual(summary['sampling']['dockerCalls'], 0)
            raw = Path(directory) / 'resources.jsonl'
            self.assertEqual(stat.S_IMODE(raw.stat().st_mode), 0o600)
            observations = [json.loads(line) for line in raw.read_text().splitlines()]
            self.assertGreaterEqual(len(observations), 1)
            self.assertIn('client', observations[-1])
            self.assertIn('rssBytes', observations[-1]['client'])
            self.assertGreater(observations[-1]['collectionDurationMs'], 0)

    def test_output_path_and_container_ids_are_constrained_before_any_command(self):
        with tempfile.TemporaryDirectory() as directory:
            os.chmod(directory, 0o755)
            with self.assertRaises(ValueError): resources.ResourceSampler(os.getpid(), Path(directory), [])
            os.chmod(directory, 0o700)
            with self.assertRaises(ValueError): resources.ResourceSampler(os.getpid(), Path(directory), ['--all'])
            with self.assertRaises(ValueError): resources.ResourceSampler(os.getpid(), Path(directory), [], interval_seconds=True)
            raw = Path(directory) / 'resources.jsonl'
            raw.write_text('preserve-existing-run')
            sampler = self.make_sampler(directory)
            with self.assertRaises(FileExistsError): sampler.start()
            self.assertEqual(raw.read_text(), 'preserve-existing-run')


if __name__ == '__main__': unittest.main()
