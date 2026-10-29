"""Offline T031 measurement contracts; no backend, Docker or network is used."""
import importlib.util
import json
from pathlib import Path
import struct
import unittest
from unittest.mock import patch
import zlib


spec = importlib.util.spec_from_file_location('performance_runner', Path(__file__).with_name('run-performance.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PerformanceStatisticsTest(unittest.TestCase):
    def test_percentile_uses_nearest_rank_without_mutating_input(self):
        values = list(range(20, 0, -1))
        self.assertEqual(module.percentile(values, .95), 19)
        self.assertEqual(module.percentile([3, 1, 2], .95), 3)
        self.assertEqual(module.percentile(values, 1), 20)
        self.assertEqual(values, list(range(20, 0, -1)))

    def test_empty_percentile_is_missing_and_invalid_fraction_is_rejected(self):
        self.assertIsNone(module.percentile([], .95))
        for fraction in (0, -.1, 1.1):
            with self.subTest(fraction=fraction), self.assertRaises(module.MVP.DemoError):
                module.percentile([1], fraction)

    def test_summary_preserves_errors_and_separates_success_percentile(self):
        rows = [{'durationMs': 2., 'ok': True, 'connectionReused': True},
                {'durationMs': 999., 'ok': False, 'connectionReused': False}]
        value = module.summarize(rows, 2)
        self.assertEqual(value['samples'], 2)
        self.assertEqual(value['errors'], 1)
        self.assertEqual(value['errorRate'], .5)
        self.assertEqual(value['requestsPerSecond'], 1.)
        self.assertEqual(value['latencyMs']['p95'], 999.)
        self.assertEqual(value['successfulLatencyMs']['samples'], 1)
        self.assertEqual(value['successfulLatencyMs']['p95'], 2.)
        self.assertEqual(value['reusedConnectionRequests'], 1)

    def test_empty_summary_does_not_fabricate_zero_latency_or_success(self):
        value = module.summarize([], 0)
        self.assertEqual(value['samples'], 0)
        self.assertIsNone(value['errorRate'])
        self.assertIsNone(value['requestsPerSecond'])
        self.assertIsNone(value['latencyMs']['p95'])
        self.assertIsNone(value['successfulLatencyMs']['p95'])


class PresenceScopeTest(unittest.TestCase):
    def test_shared_prefix_is_rejected_before_opening_redis(self):
        with patch.object(module.socket, 'create_connection') as connection:
            with self.assertRaises(module.MVP.DemoError):
                module.expired_presence({}, 'inspection:device:seen:', [])
            connection.assert_not_called()

    def test_duplicate_device_scope_is_rejected_before_opening_redis(self):
        with patch.object(module.socket, 'create_connection') as connection:
            with self.assertRaises(module.MVP.DemoError):
                module.expired_presence({}, 'inspection:perf:' + 'a' * 32 + ':', ['00000000-0000-4000-8000-000000000001'] * 5)
            connection.assert_not_called()


class PngFixtureTest(unittest.TestCase):
    @staticmethod
    def chunks(data):
        if data[:8] != b'\x89PNG\r\n\x1a\n':
            raise AssertionError('PNG signature')
        offset, result = 8, []
        while offset < len(data):
            size = struct.unpack_from('>I', data, offset)[0]
            name = data[offset + 4:offset + 8]
            payload = data[offset + 8:offset + 8 + size]
            if len(payload) != size or offset + size + 12 > len(data):
                raise AssertionError('truncated PNG chunk')
            crc = struct.unpack_from('>I', data, offset + size + 8)[0]
            if crc != zlib.crc32(name + payload):
                raise AssertionError('PNG chunk CRC')
            result.append((name, payload))
            offset += size + 12
            if name == b'IEND':
                if offset != len(data) or payload:
                    raise AssertionError('bytes after IEND or nonempty IEND')
                break
        if not result or result[-1][0] != b'IEND':
            raise AssertionError('missing PNG end')
        return result

    def test_exact_size_crc_and_decodable_original_pixel_stream(self):
        original = (module.ROOT / 'examples/files/inspection-sample.png').read_bytes()
        self.assertEqual(len(original), 79)
        for size in (1024**2, 17 * 1024**2):
            with self.subTest(size=size):
                data = module.png_fixture(original, size)
                self.assertEqual(len(data), size)
                chunks = self.chunks(data)
                self.assertEqual(chunks[0][0], b'IHDR')
                width, height, depth, color, compression, filtering, interlace = struct.unpack('>IIBBBBB', chunks[0][1])
                self.assertEqual((width, height, compression, filtering, interlace), (16, 16, 0, 0, 0))
                channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
                pixel_stream = zlib.decompress(b''.join(payload for name, payload in chunks if name == b'IDAT'))
                self.assertEqual(len(pixel_stream), height * (1 + (width * channels * depth + 7) // 8))
                self.assertEqual([payload.split(b'\x00', 1)[0] for name, payload in chunks if name == b'tEXt'], [b'T031'])

    def test_rejects_trailing_padding_and_impossible_small_size(self):
        original = (module.ROOT / 'examples/files/inspection-sample.png').read_bytes()
        for source, size in ((original + b'padding', 1024), (original, len(original))):
            with self.subTest(size=size), self.assertRaises(module.MVP.DemoError):
                module.png_fixture(source, size)


class FakeResponse:
    def __init__(self, connection): self.connection = connection; self.status = 200

    def read(self):
        self.connection.events.append('read-body')
        return json.dumps({'code': 'OK', 'traceId': self.connection.headers['X-Trace-Id'],
                           'data': {'result': 'accepted'}}).encode()

    def getheader(self, name, default=None):
        return {'X-Trace-Id': self.connection.headers['X-Trace-Id'], 'Content-Type': 'application/json'}.get(name, default)


class FakeConnection:
    def __init__(self, events, failure=None):
        self.events, self.failure, self.headers, self.sock = events, failure, {}, None

    def request(self, method, path, body=None, headers=None):
        self.events.append('send')
        if self.failure: raise self.failure
        self.headers = headers; self.sock = object()

    def getresponse(self): return FakeResponse(self)

    def close(self): self.sock = None


class ClientTimingTest(unittest.TestCase):
    def test_http_timer_ends_after_body_before_json_decode(self):
        events, clock = [], iter((1_000_000_000, 1_125_000_000))
        connection = FakeConnection(events)
        original_loads = json.loads
        def tick(): events.append('clock'); return next(clock)
        def decode(payload): events.append('decode'); return original_loads(payload)
        with patch.object(module.http.client, 'HTTPConnection', return_value=connection), \
             patch.object(module.time, 'perf_counter_ns', side_effect=tick), \
             patch.object(module.json, 'loads', side_effect=decode):
            client = module.Client('http://127.0.0.1:12345', token='private-test-token')
            data, row = client.request('GET', '/api/v1/example')
        self.assertEqual(events, ['clock', 'send', 'read-body', 'clock', 'decode'])
        self.assertEqual(row['durationMs'], 125.)
        self.assertTrue(row['ok'])
        self.assertFalse(row['connectionReused'])
        self.assertEqual(data, {'result': 'accepted'})
        self.assertNotIn('private-test-token', json.dumps(row))

    def test_transport_failure_is_a_measured_error_without_provider_text(self):
        connection = FakeConnection([], ConnectionResetError('private-provider-detail'))
        with patch.object(module.http.client, 'HTTPConnection', return_value=connection), \
             patch.object(module.time, 'perf_counter_ns', side_effect=(0, 30_000_000)):
            client = module.Client('http://127.0.0.1:12345')
            data, row = client.request('GET', '/api/v1/example')
        self.assertIsNone(data)
        self.assertFalse(row['ok'])
        self.assertEqual(row['durationMs'], 30.)
        self.assertEqual(row['failureType'], 'ConnectionResetError')
        self.assertNotIn('private-provider-detail', json.dumps(row))

    def test_non_loopback_target_is_rejected_before_connection_creation(self):
        with patch.object(module.http.client, 'HTTPConnection') as connection:
            for url in ('http://example.com:1234', 'http://localhost:1234', 'https://127.0.0.1:1234'):
                with self.subTest(url=url), self.assertRaises(module.MVP.DemoError): module.Client(url)
            connection.assert_not_called()


class ClosedLoopTest(unittest.TestCase):
    def test_worker_warmup_is_excluded_and_minimum_count_is_preserved(self):
        runner = object.__new__(module.Run)
        runner.phase = lambda _: None
        artifacts, count = {}, 0
        runner.write = lambda name, value: artifacts.__setitem__(name, value)
        client = type('OfflineClient', (), {'close': lambda self: None})()
        def operation(_client, _index):
            nonlocal count
            count += 1
            return None, {'ok': True, 'durationMs': float(count), 'connectionReused': count > 1}
        # Warmup does not read this clock. The measured worker first satisfies its
        # count, then sees the ten-second deadline passed; no real sleep is needed.
        with patch.object(module.time, 'perf_counter_ns', side_effect=(0, 11_000_000_000, 12_000_000_000)):
            summary = runner.closed_loop('offline-c1', 1, 2, lambda _: client, operation)
        self.assertEqual(count, 7)
        self.assertEqual(summary['perWorkerSamples'], [2])
        self.assertEqual([r['durationMs'] for r in artifacts['offline-c1.json']['requests']], [6., 7.])
        self.assertEqual(summary['wallSeconds'], 12.)


if __name__ == '__main__': unittest.main()
