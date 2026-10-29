"""Private, standard-library resource observations for the T031 workload runner.

CPU usage for the backend is a /proc tick delta divided by elapsed monotonic
time: one fully occupied CPU is 100%. Docker CLI percentages and memory usage
are kept separately. Collection itself perturbs the workload; it is measured,
never subtracted from the benchmark or described as cost-free monitoring.
"""
import datetime as dt
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import stat
import subprocess
import threading
import time
import uuid


def _utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def _read_int(path):
    value = Path(path).read_text().strip()
    return None if value == 'max' else int(value)


def _cpuset_count(value):
    cpus = set()
    for item in value.strip().split(','):
        if not item:
            continue
        ends = item.split('-')
        if len(ends) == 1:
            cpus.add(int(ends[0]))
        elif len(ends) == 2 and int(ends[0]) <= int(ends[1]):
            cpus.update(range(int(ends[0]), int(ends[1]) + 1))
        else:
            raise ValueError('invalid_cpuset')
    return len(cpus) or None


def _cgroup_limits(pid):
    """Read applicable v2 ancestors; membership paths are never reported."""
    root = Path('/sys/fs/cgroup')
    result = {'version': None, 'cpuQuotaCores': None, 'cpuSetEffectiveCount': None,
              'memoryMaxBytes': None, 'memoryCurrentBytes': None}
    try:
        memberships = Path(f'/proc/{pid}/cgroup').read_text().splitlines()
        relative = next(line[3:] for line in memberships if line.startswith('0::'))
        directory = (root / relative.lstrip('/')).resolve()
        if not directory.is_relative_to(root.resolve()) or not directory.is_dir():
            # A cgroup namespace may expose its own root rather than host paths.
            directory = root
        if not (directory / 'cgroup.controllers').exists() and not (root / 'cgroup.controllers').exists():
            return result
        result['version'] = 2
        quotas, memory_limits = [], []
        current = directory
        while current.is_relative_to(root):
            try:
                quota, period = (current / 'cpu.max').read_text().split()
                if quota != 'max' and int(period) > 0:
                    quotas.append(int(quota) / int(period))
            except (OSError, ValueError):
                pass
            try:
                limit = _read_int(current / 'memory.max')
                if limit is not None:
                    memory_limits.append(limit)
            except (OSError, ValueError):
                pass
            if result['cpuSetEffectiveCount'] is None:
                try:
                    result['cpuSetEffectiveCount'] = _cpuset_count((current / 'cpuset.cpus.effective').read_text())
                except (OSError, ValueError):
                    pass
            if current == root:
                break
            current = current.parent
        result['cpuQuotaCores'] = min(quotas) if quotas else None
        result['memoryMaxBytes'] = min(memory_limits) if memory_limits else None
        try:
            result['memoryCurrentBytes'] = _read_int(directory / 'memory.current')
        except (OSError, ValueError):
            pass
    except (OSError, ValueError, StopIteration):
        pass
    return result


def _cpu_capacity(pid):
    logical = os.cpu_count()
    try:
        affinity = len(os.sched_getaffinity(pid))
    except (AttributeError, OSError):
        affinity = None
    limits = _cgroup_limits(pid)
    candidates = [float(value) for value in (logical, affinity, limits['cpuQuotaCores'], limits['cpuSetEffectiveCount'])
                  if value is not None and value > 0]
    return {'logicalCpuCount': logical, 'affinityCpuCount': affinity,
            'availableCpuCores': min(candidates) if candidates else None,
            'availableCpuDefinition': 'minimum of logical CPUs, affinity, readable cgroup quota and effective cpuset; not a CPU reservation',
            'cgroupV2': limits}


def _memory():
    wanted = {'MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree'}
    values = {}
    try:
        for line in Path('/proc/meminfo').read_text().splitlines():
            key, _, rest = line.partition(':')
            if key in wanted:
                fields = rest.split()
                values[key + 'Bytes'] = int(fields[0]) * 1024
    except (OSError, ValueError, IndexError):
        pass
    return values


def collect_environment():
    """No environment variables, argv, usernames, hostname or credential files."""
    value = {'os': platform.system(), 'kernelRelease': platform.release(), 'machine': platform.machine(),
             'pythonVersion': platform.python_version(), 'cpu': _cpu_capacity(os.getpid()), 'memory': _memory(),
             'clockTicksPerSecond': os.sysconf('SC_CLK_TCK'), 'pageSizeBytes': os.sysconf('SC_PAGE_SIZE')}
    try:
        value['cpuModel'] = next(line.partition(':')[2].strip() for line in Path('/proc/cpuinfo').read_text().splitlines()
                                 if line.partition(':')[0].strip() in ('model name', 'Hardware'))
    except (OSError, StopIteration):
        value['cpuModel'] = None
    try:
        value['osRelease'] = {key: raw.strip().strip('"') for line in Path('/etc/os-release').read_text().splitlines()
                              for key, separator, raw in [line.partition('=')]
                              if separator and key in ('ID', 'VERSION_ID', 'PRETTY_NAME')}
    except OSError:
        value['osRelease'] = None
    try:
        one, five, fifteen = os.getloadavg()
        value['loadAverage'] = {'oneMinute': one, 'fiveMinutes': five, 'fifteenMinutes': fifteen}
    except (AttributeError, OSError):
        value['loadAverage'] = None
    disk = shutil.disk_usage(Path.cwd())
    value['workspaceFilesystem'] = {'totalBytes': disk.total, 'freeBytes': disk.free}
    value['cgroupMemoryCurrentScope'] = 'the sampler process cgroup, not backend RSS or total host memory'
    return value


def parse_proc_stat(text, page_size):
    """Linux fields after the final ')' tolerate spaces and ')' inside comm."""
    end = text.rfind(')')
    if end < 0 or '(' not in text[:end]:
        raise ValueError('invalid_proc_stat')
    fields = text[end + 1:].split()  # state is field 3, at index zero.
    if len(fields) < 22:
        raise ValueError('short_proc_stat')
    return {'state': fields[0], 'userTicks': int(fields[11]), 'systemTicks': int(fields[12]),
            'threads': int(fields[17]), 'startTimeTicks': int(fields[19]),
            'virtualMemoryBytes': int(fields[20]), 'rssBytes': int(fields[21]) * page_size}


def cpu_interval(previous, current, ticks_per_second, available_cores):
    """Missing, cross-phase, reused PID and reset counters have no invented rate."""
    if previous is None:
        return {'cpuCorePercent': None, 'cpuAvailablePercent': None, 'cpuIntervalSeconds': 0.0, 'cpuUnavailableReason': 'first_observation'}
    if previous['phaseId'] != current['phaseId']:
        return {'cpuCorePercent': None, 'cpuAvailablePercent': None, 'cpuIntervalSeconds': 0.0, 'cpuUnavailableReason': 'phase_boundary'}
    if previous['startTimeTicks'] != current['startTimeTicks']:
        return {'cpuCorePercent': None, 'cpuAvailablePercent': None, 'cpuIntervalSeconds': 0.0, 'cpuUnavailableReason': 'pid_reused'}
    elapsed = (current['monotonicNs'] - previous['monotonicNs']) / 1e9
    ticks = current['userTicks'] + current['systemTicks'] - previous['userTicks'] - previous['systemTicks']
    if elapsed <= 0 or ticks < 0 or ticks_per_second <= 0:
        return {'cpuCorePercent': None, 'cpuAvailablePercent': None, 'cpuIntervalSeconds': 0.0, 'cpuUnavailableReason': 'invalid_interval_or_counter_reset'}
    core = ticks / ticks_per_second / elapsed * 100.0
    return {'cpuCorePercent': core, 'cpuAvailablePercent': core / available_cores if available_cores else None,
            'cpuIntervalSeconds': elapsed, 'cpuUnavailableReason': None}


def _read_process(pid, page_size):
    value = parse_proc_stat(Path(f'/proc/{pid}/stat').read_text(), page_size)
    errors = []
    try:
        value['io'] = {key: int(number.strip()) for line in Path(f'/proc/{pid}/io').read_text().splitlines()
                       for key, _, number in [line.partition(':')]
                       if key in ('rchar', 'wchar', 'syscr', 'syscw', 'read_bytes', 'write_bytes', 'cancelled_write_bytes')}
    except (OSError, ValueError) as failure:
        value['io'] = None; errors.append({'source': 'proc_io', 'type': type(failure).__name__})
    try:
        value['openFileDescriptors'] = len(list(Path(f'/proc/{pid}/fd').iterdir()))
    except OSError as failure:
        value['openFileDescriptors'] = None; errors.append({'source': 'proc_fd', 'type': type(failure).__name__})
    value['observationErrors'] = errors
    return value


def parse_memory_bytes(text):
    match = re.fullmatch(r'\s*([0-9]+(?:\.[0-9]+)?)\s*(B|kB|KB|KiB|MB|MiB|GB|GiB|TB|TiB)\s*', text)
    if not match:
        return None
    units = {'B': 1, 'kB': 1000, 'KB': 1000, 'KiB': 1024, 'MB': 1000 ** 2, 'MiB': 1024 ** 2,
             'GB': 1000 ** 3, 'GiB': 1024 ** 3, 'TB': 1000 ** 4, 'TiB': 1024 ** 4}
    return int(float(match.group(1)) * units[match.group(2)])


def _percentage(text):
    try:
        value = float(text.rstrip('%'))
        return value if math.isfinite(value) and value >= 0 else None
    except (ValueError, AttributeError):
        return None


def _docker_rows(container_ids):
    result = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *container_ids],
                            capture_output=True, text=True, timeout=8, check=False)
    if result.returncode:
        return [], [{'source': 'docker_stats', 'type': 'NonzeroExit', 'returnCode': result.returncode}]
    rows, errors = [], []
    for line in result.stdout.splitlines():
        try:
            raw = json.loads(line)
            if not isinstance(raw, dict):
                raise ValueError('unexpected_docker_row')
            identifier = raw.get('ID') or raw.get('Container')
            if not isinstance(identifier, str) or not re.fullmatch(r'[0-9a-f]{12,64}', identifier):
                raise ValueError('unexpected_container_id')
            if not any(identifier.startswith(wanted) or wanted.startswith(identifier) for wanted in container_ids):
                raise ValueError('unrequested_container_id')
            if any(row['containerId'] == identifier for row in rows):
                raise ValueError('duplicate_container_id')
            usage, separator, limit = str(raw.get('MemUsage', '')).partition('/')
            rows.append({'containerId': identifier, 'cpuPercentDocker': _percentage(raw.get('CPUPerc')),
                         'memoryUsageBytesDocker': parse_memory_bytes(usage),
                         'memoryLimitBytesDocker': parse_memory_bytes(limit) if separator else None,
                         'memoryPercentDocker': _percentage(raw.get('MemPerc')),
                         'cpuPercentRaw': raw.get('CPUPerc'), 'memoryUsageRaw': raw.get('MemUsage'),
                         'memoryPercentRaw': raw.get('MemPerc'), 'pids': int(raw['PIDs']) if str(raw.get('PIDs', '')).isdigit() else None})
        except (ValueError, TypeError, KeyError, json.JSONDecodeError) as failure:
            errors.append({'source': 'docker_stats_row', 'type': type(failure).__name__})
    if len(rows) != len(container_ids):
        errors.append({'source': 'docker_stats', 'type': 'ContainerCountMismatch', 'expected': len(container_ids), 'observed': len(rows)})
    return rows, errors


class ResourceSampler:
    def __init__(self, pid, directory, docker_container_ids, interval_seconds=1.0):
        if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0:
            raise ValueError('positive_backend_pid_required')
        if (not isinstance(interval_seconds, (int, float)) or isinstance(interval_seconds, bool)
                or not math.isfinite(interval_seconds) or interval_seconds < 0.1):
            raise ValueError('sampling_interval_must_be_at_least_0_1_seconds')
        self.directory = Path(directory)
        if (not self.directory.is_dir() or self.directory.is_symlink()
                or stat.S_IMODE(self.directory.stat().st_mode) != 0o700):
            raise ValueError('existing_private_0700_output_directory_required')
        ids = list(docker_container_ids)
        if any(not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{12,64}', value) for value in ids) or len(ids) != len(set(ids)):
            raise ValueError('unique_docker_container_ids_required')
        self.pid, self.container_ids, self.interval = pid, ids, float(interval_seconds)
        self.capacity = _cpu_capacity(pid)
        self.client_pid, self.client_capacity = os.getpid(), _cpu_capacity(os.getpid())
        self.ticks_per_second, self.page_size = os.sysconf('SC_CLK_TCK'), os.sysconf('SC_PAGE_SIZE')
        self._lock, self._write_lock = threading.Lock(), threading.Lock()
        self._event, self._threads, self._rows, self._errors = threading.Event(), [], [], []
        self._phase, self._phase_id, self._phase_events = 'unmarked', 0, []
        self._started, self._stopped, self._summary = False, False, None
        self._last_process = None
        self._last_client = None
        self._thread_cpu_ns = {}
        self._busy_ns = {'backend': 0, 'docker': 0}
        self._stream = None

    def start(self):
        if self._started:
            raise RuntimeError('resource_sampler_already_started')
        descriptor = os.open(self.directory / 'resources.jsonl', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.fchmod(descriptor, 0o600)
        self._stream = os.fdopen(descriptor, 'w', buffering=1)
        self._started = True
        self._start_ns, self._started_at = time.monotonic_ns(), _utc()
        self._phase_events.append({'name': self._phase, 'phaseId': self._phase_id, 'monotonicNs': self._start_ns})
        for kind in ('backend', 'docker') if self.container_ids else ('backend',):
            thread = threading.Thread(target=self._loop, args=(kind,), name='performance-resource-' + kind, daemon=True)
            self._threads.append(thread); thread.start()
        return self

    def mark_phase(self, name):
        if not self._started or self._stopped:
            raise RuntimeError('active_resource_sampler_required')
        if not isinstance(name, str) or not name or len(name) > 128 or any(ord(value) < 32 for value in name):
            raise ValueError('public_phase_label_required')
        with self._lock:
            self._phase, self._phase_id = name, self._phase_id + 1
            self._phase_events.append({'name': name, 'phaseId': self._phase_id, 'monotonicNs': time.monotonic_ns()})

    def _phase_snapshot(self):
        with self._lock:
            return self._phase, self._phase_id

    def _observe(self, kind):
        phase, phase_id = self._phase_snapshot()
        began = time.monotonic_ns()
        row = {'kind': kind, 'observedAt': _utc(), 'monotonicNs': began,
               'phase': phase, 'phaseId': phase_id, 'secondsSinceStart': (began - self._start_ns) / 1e9}
        if kind == 'backend':
            try:
                current = _read_process(self.pid, self.page_size)
                current.update({'phaseId': phase_id, 'monotonicNs': began})
                row.update(current); row['processAvailable'] = True
                row.update(cpu_interval(self._last_process, current, self.ticks_per_second, self.capacity['availableCpuCores']))
                self._last_process = current
            except (OSError, ValueError, IndexError) as failure:
                row.update({'processAvailable': False, 'cpuCorePercent': None, 'cpuAvailablePercent': None,
                            'cpuIntervalSeconds': 0.0, 'observationErrors': [{'source': 'proc_stat', 'type': type(failure).__name__}]})
                self._last_process = None
            try:
                client = _read_process(self.client_pid, self.page_size)
                client.update({'phaseId': phase_id, 'monotonicNs': began})
                client.update(cpu_interval(self._last_client, client, self.ticks_per_second, self.client_capacity['availableCpuCores']))
                client['processAvailable'] = True
                self._last_client = client
                row['client'] = client
            except (OSError, ValueError, IndexError) as failure:
                row['client'] = {'processAvailable': False, 'observationErrors': [{'source': 'client_proc_stat', 'type': type(failure).__name__}]}
                self._last_client = None
            row['hostMemory'] = _memory()
            try: row['hostLoadAverage'] = list(os.getloadavg())
            except (AttributeError, OSError): row['hostLoadAverage'] = None
            _, ending_phase_id = self._phase_snapshot()
            row['phaseBoundaryCrossed'] = phase_id != ending_phase_id
            if row['phaseBoundaryCrossed']:
                missing = {'cpuCorePercent': None, 'cpuAvailablePercent': None, 'cpuIntervalSeconds': 0.0,
                           'cpuUnavailableReason': 'phase_changed_during_observation'}
                row.update(missing)
                row['client'].update(missing)
        else:
            try: row['containers'], row['observationErrors'] = _docker_rows(self.container_ids)
            except (OSError, subprocess.SubprocessError) as failure:
                row['containers'], row['observationErrors'] = [], [{'source': 'docker_stats', 'type': type(failure).__name__}]
            _, ending_phase_id = self._phase_snapshot()
            row['phaseBoundaryCrossed'] = phase_id != ending_phase_id
            row['observationCompletedAt'] = _utc()
        row['completedMonotonicNs'] = time.monotonic_ns()
        row['collectionDurationMs'] = (row['completedMonotonicNs'] - began) / 1e6
        return row

    def _loop(self, kind):
        cpu_began = time.thread_time_ns()
        try:
            while not self._event.is_set():
                began = time.monotonic_ns()
                row = self._observe(kind)
                with self._write_lock:
                    self._stream.write(json.dumps(row, separators=(',', ':')) + '\n')
                    self._rows.append(row)
                spent = time.monotonic_ns() - began
                self._busy_ns[kind] += spent
                self._event.wait(max(0.0, self.interval - spent / 1e9))
        except Exception as failure:
            with self._lock:
                self._errors.append({'source': kind + '_sampling_thread', 'type': type(failure).__name__})
            self._event.set()
        finally:
            self._thread_cpu_ns[kind] = time.thread_time_ns() - cpu_began

    def stop(self):
        if not self._started:
            raise RuntimeError('resource_sampler_not_started')
        if self._stopped:
            return self._summary
        self._stopped = True
        stop_ns, stopped_at = time.monotonic_ns(), _utc()
        self._event.set()
        for thread in self._threads:
            thread.join(timeout=12)
        if any(thread.is_alive() for thread in self._threads):
            raise RuntimeError('resource_sampling_thread_did_not_stop')
        self._stream.flush(); self._stream.close()
        windows = []
        for index, event in enumerate(self._phase_events):
            end = self._phase_events[index + 1]['monotonicNs'] if index + 1 < len(self._phase_events) else stop_ns
            windows.append({**event, 'durationSeconds': max(0.0, (end - event['monotonicNs']) / 1e9)})
        phases = {}
        for window in windows:
            value = phases.setdefault(window['name'], {'durationSeconds': 0.0, 'phaseIds': []})
            value['durationSeconds'] += window['durationSeconds']; value['phaseIds'].append(window['phaseId'])
        for name, value in phases.items():
            backend = [r for r in self._rows if r['kind'] == 'backend' and r['phase'] == name and r['completedMonotonicNs'] <= stop_ns]
            valid = [r for r in backend if r.get('cpuCorePercent') is not None]
            coverage = sum(r['cpuIntervalSeconds'] for r in valid)
            resource_rows = [r for r in backend if r.get('processAvailable') and not r.get('phaseBoundaryCrossed')]
            rss = [r['rssBytes'] for r in resource_rows]
            value.update({'backendSamples': len(backend), 'backendUnavailableSamples': sum(not r.get('processAvailable') for r in backend),
                          'backendBoundarySamplesExcluded': sum(r.get('phaseBoundaryCrossed', False) for r in backend),
                          'backendCpuIntervals': len(valid), 'backendCpuObservedSeconds': coverage,
                          'backendCpuCorePercentMean': sum(r['cpuCorePercent'] * r['cpuIntervalSeconds'] for r in valid) / coverage if coverage else None,
                          'backendCpuCorePercentPeak': max((r['cpuCorePercent'] for r in valid), default=None),
                          'backendCpuAvailablePercentMean': sum(r['cpuAvailablePercent'] * r['cpuIntervalSeconds'] for r in valid) / coverage if coverage and all(r['cpuAvailablePercent'] is not None for r in valid) else None,
                          'backendRssBytesPeak': max(rss, default=None),
                          'backendThreadsPeak': max((r['threads'] for r in resource_rows), default=None),
                          'dockerSamplesCrossingPhaseBoundary': sum(r.get('phaseBoundaryCrossed', False) for r in self._rows if r['kind'] == 'docker' and r['phase'] == name)})
            clients = [row['client'] for row in backend if row.get('client', {}).get('processAvailable') and not row.get('phaseBoundaryCrossed')]
            client_cpu = [row for row in clients if row.get('cpuCorePercent') is not None]
            client_coverage = sum(row['cpuIntervalSeconds'] for row in client_cpu)
            value.update({'clientSamples': len(clients), 'clientCpuObservedSeconds': client_coverage,
                          'clientCpuCorePercentMean': sum(row['cpuCorePercent'] * row['cpuIntervalSeconds'] for row in client_cpu) / client_coverage if client_coverage else None,
                          'clientCpuCorePercentPeak': max((row['cpuCorePercent'] for row in client_cpu), default=None),
                          'clientCpuAvailablePercentMean': sum(row['cpuAvailablePercent'] * row['cpuIntervalSeconds'] for row in client_cpu) / client_coverage if client_coverage and all(row['cpuAvailablePercent'] is not None for row in client_cpu) else None,
                          'clientRssBytesPeak': max((row['rssBytes'] for row in clients), default=None)})
            containers = {}
            for row in self._rows:
                if row['kind'] != 'docker' or row['phase'] != name or row['phaseBoundaryCrossed'] or row['completedMonotonicNs'] > stop_ns:
                    continue
                for sample in row['containers']:
                    containers.setdefault(sample['containerId'], []).append(sample)
            value['dockerContainers'] = {}
            for identifier, samples in containers.items():
                cpus = [s['cpuPercentDocker'] for s in samples if s['cpuPercentDocker'] is not None]
                memories = [s['memoryUsageBytesDocker'] for s in samples if s['memoryUsageBytesDocker'] is not None]
                value['dockerContainers'][identifier] = {'samples': len(samples), 'cpuPercentDockerSampleMean': sum(cpus) / len(cpus) if cpus else None,
                                                        'cpuPercentDockerPeak': max(cpus, default=None), 'memoryUsageBytesDockerPeak': max(memories, default=None)}
        seconds = max(0.0, (stop_ns - self._start_ns) / 1e9)
        self._summary = {'formatVersion': 1, 'backendPid': self.pid, 'clientPid': self.client_pid, 'startedAt': self._started_at, 'stoppedAt': stopped_at,
                         'rawFile': 'resources.jsonl', 'requestedIntervalSeconds': self.interval, 'observationSeconds': seconds,
                         'backendCpuCapacity': self.capacity, 'clientCpuCapacity': self.client_capacity,
                         'clientProcessScope': 'the Python workload runner including workload generation, validation and both sampler threads',
                         'clockTicksPerSecond': self.ticks_per_second, 'pageSizeBytes': self.page_size,
                         'cpuSemantics': {'backendCorePercent': '100% is one occupied CPU; values may exceed 100%',
                                          'backendAvailablePercent': 'backend core percent divided by availableCpuCores; not clamped',
                                          'backendMean': 'elapsed-time-weighted valid same-phase /proc CPU intervals; first and cross-phase intervals are excluded',
                                          'docker': 'Docker CLI CPU/MemUsage semantics retained; memory usage is not backend RSS; CPU mean is a sample mean'},
                         'sampling': {'backendSamples': sum(r['kind'] == 'backend' for r in self._rows),
                                      'dockerCalls': sum(r['kind'] == 'docker' for r in self._rows),
                                      'backendCollectionWallSeconds': self._busy_ns['backend'] / 1e9,
                                      'dockerCollectionWallSeconds': self._busy_ns['docker'] / 1e9,
                                      'pythonSamplingThreadCpuSeconds': {k: v / 1e9 for k, v in self._thread_cpu_ns.items()},
                                      'collectionWallCanOverlapBetweenThreads': True,
                                      'dockerCliAndDaemonCpuNotIncludedInPythonThreadCpu': True,
                                      'dockerScheduleIsBestEffort': True,
                                      'errors': self._errors + [error for row in self._rows for error in row.get('observationErrors', [])]
                                                + [{**error, 'process': 'client'} for row in self._rows for error in row.get('client', {}).get('observationErrors', [])]},
                         'phaseWindows': windows, 'phases': phases,
                         'measurementLimitations': ['Sampling and Docker CLI/daemon work perturb the workload and are not subtracted.',
                                                   'Requested interval is not an achieved fixed cadence; raw monotonic timestamps and collection durations are authoritative.',
                                                   'Short phases can have no usable CPU interval or Docker sample; missing measurements remain null.',
                                                   'Peak values are observed sample peaks, not a guarantee of continuous high-water marks.']}
        output = self.directory / 'resource-summary.json'
        temporary = self.directory / ('.resource-summary-' + uuid.uuid4().hex + '.tmp')
        try:
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, 'w') as stream: json.dump(self._summary, stream, indent=2); stream.write('\n')
            os.replace(temporary, output)
        finally:
            temporary.unlink(missing_ok=True)
        return self._summary
