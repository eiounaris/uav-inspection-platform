#!/usr/bin/env python3
"""Reproduce deployment in a fresh source tree, identities and project-scoped volumes."""
import datetime as dt
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

from backend_env import ROOT, backend_environment, fabric_environment, java21

WORK = ROOT / '.local/deployment-repro'
PREFIXES = ('DB_', 'MYSQL_', 'S3_', 'GARAGE_', 'REDIS_', 'RABBITMQ_', 'MESSAGING_', 'FABRIC_',
            'DEVICE_', 'UPLOAD_', 'VERIFICATION_', 'CLEANUP_', 'CORE_', 'SPRING_', 'COMPOSE_',
            'INFRA_', 'EVIDENCE_', 'SERVER_')
PRIVATE = re.compile(rb'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}|-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----|[?&]X-Amz-(?:Signature|Credential)=', re.I)


class DeploymentError(RuntimeError): pass


def require(value, name):
    if not value: raise DeploymentError(name)


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


FINAL = module('deployment_final', ROOT / 'deploy/scripts/verify-final.py')
MVP = module('deployment_mvp', ROOT / 'deploy/scripts/verify-mvp.py')


def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def read(path): return json.loads(Path(path).read_text())


def private_json(path, value):
    raw = (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()
    require(not PRIVATE.search(raw), 'private_data_in_report')
    MVP.private_bytes(Path(path), raw)


def clean_environment(environment):
    """A source copy must not inherit the original stack's endpoints or credentials."""
    return {key: value for key, value in environment.items()
            if not key.startswith(PREFIXES) and key not in ('JWT_SECRET', 'DEMO_CREDENTIALS_PATH',
                'PROJECT_JAVA_HOME', 'PYTHONPATH', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')}


def source_files(root):
    allowed = ('backend', 'chaincode', 'deploy', 'docs', 'examples', 'simulator', 'frontend-tests')
    result = subprocess.run(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'],
                            cwd=root, capture_output=True, check=True)
    files = []
    for raw in result.stdout.split(b'\0'):
        if not raw: continue
        path = Path(os.fsdecode(raw))
        require(not path.is_absolute() and '..' not in path.parts, 'unsafe_source_path')
        if path.parts[0] not in allowed and path.as_posix() not in ('.gitignore', '.env.example', 'README.md'): continue
        if any(part in ('.local', '.git', 'target', '__pycache__', 'node_modules') for part in path.parts): continue
        if path.as_posix() != '.env.example' and any(part == '.env' or part.startswith('.env.') for part in path.parts): continue
        if path.parts[:2] in (('deploy','local'), ('deploy','secrets')): continue
        file = root / path
        require(file.resolve().is_relative_to(root.resolve())
                and not any((root / Path(*path.parts[:end])).is_symlink() for end in range(1,len(path.parts)+1))
                and file.is_file(), 'source_must_be_regular_file_inside_repository')
        files.append(path)
    require(bool(files), 'source_files_required')
    return sorted(set(files))


def allocate_ports():
    sockets, ports = [], []
    try:
        for _ in range(9):
            connection = socket.socket(); connection.bind(('127.0.0.1', 0))
            sockets.append(connection); ports.append(connection.getsockname()[1])
    finally:
        for connection in sockets: connection.close()
    require(len(set(ports)) == 9, 'nine_distinct_host_ports_required')
    return dict(zip(('DB_PORT', 'REDIS_PORT', 'RABBITMQ_PORT', 'RABBITMQ_MANAGEMENT_PORT', 'S3_PORT',
                     'FABRIC_ORDERER_PORT', 'FABRIC_ORDERER_ADMIN_PORT', 'FABRIC_ORGA_PEER_PORT', 'FABRIC_ORGB_PEER_PORT'), ports))


class Reproduction:
    def __init__(self, directory):
        self.run, self.copy = directory, directory / 'workspace'
        self.original_environment = fabric_environment(backend_environment(), local=True)
        self.java = java21(self.original_environment)
        self.environment = clean_environment(os.environ)
        self.environment.update(JAVA_HOME=str(Path(self.java).parent.parent), SERVER_ADDRESS='127.0.0.1', SERVER_PORT='0')
        suffix = uuid.uuid4().hex[:12]
        self.infra_project, self.fabric_project = 'uav-repro-' + suffix + '-infra', 'uav-repro-' + suffix + '-fabric'
        self.image = 'uav-inspection/repro-' + suffix + ':0.1.0'
        self.ports, self.application = {}, None
        self.infra_started = self.fabric_started = False
        self.sequence = 0
        self.report = {'formatVersion': 1, 'task': 'T032', 'runId': directory.name, 'passed': False,
                       'startedAt': MVP.now(), 'components': [], 'infraProject': self.infra_project,
                       'fabricProject': self.fabric_project, 'chaincodeImage': self.image,
                       'scope': 'fresh source copy and empty business/ledger volumes on the same host; pinned dependency/archive/image caches reused; not a fresh OS or cold-network installation'}

    def checkpoint(self): private_json(self.run / 'report.json', self.report)

    def phase(self, name):
        print('T032 phase: ' + name, flush=True)
        self.report['stage'] = name; self.checkpoint()

    def command(self, label, argv, *, original=False, timeout=900):
        self.sequence += 1
        log = self.run / (f'{self.sequence:02d}-' + label + '.log')
        began = time.perf_counter_ns()
        descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'wb') as output:
            process = subprocess.Popen(list(map(str, argv)), cwd=ROOT if original else self.copy,
                env=self.original_environment if original else self.environment, stdout=output,
                stderr=subprocess.STDOUT, start_new_session=True)
            try: code = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM)
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: os.killpg(process.pid, signal.SIGKILL); process.wait(timeout=10)
                code = -1
        self.report['components'].append({'name': label, 'passed': code == 0, 'returnCode': code,
              'log': str(log.relative_to(ROOT)), 'logSha256': sha(log), 'seconds': (time.perf_counter_ns()-began)/1e9})
        self.checkpoint()
        require(code == 0, 'component_failed_inspect_' + str(log.relative_to(ROOT)))
        return log

    def python(self, label, script, *arguments, timeout=900):
        return self.command(label, [sys.executable, script, *arguments], timeout=timeout)

    def compose(self, label, *arguments):
        return self.command(label, ['docker', 'compose', '--project-name', self.infra_project,
            '--env-file', self.copy / '.env', '-f', self.copy / 'deploy/compose.yml', *arguments])

    def helper(self, name, phase, output, baseline=None, original=False):
        root = ROOT if original else self.copy
        actual_output = output
        if not original:
            checks = self.copy / '.local/deployment-checks'
            checks.mkdir(mode=0o700, exist_ok=True)
            actual_output = checks / output.name
            if baseline:
                actual_baseline = checks / baseline.name
                require(actual_baseline.is_file() and sha(actual_baseline) == sha(baseline), 'scoped_baseline_copy_required')
                baseline = actual_baseline
        cp = os.pathsep.join((str(root / 'backend/target/test-classes'), str(root / 'backend/target/classes'),
                             (root / 'backend/target/database-probe.classpath').read_text().strip()))
        args = [self.java, '-Djava.awt.headless=true', '-Duser.timezone=Asia/Shanghai', '-cp', cp,
                'com.uav.inspection.demo.RetainedDemoInspector', phase]
        if baseline: args.append(baseline)
        args.append(actual_output)
        if not original:
            launcher = ("import os,sys;sys.path.insert(0,'deploy/scripts');"
                        "from backend_env import backend_environment,fabric_environment;"
                        "os.execve(sys.argv[1],sys.argv[1:],fabric_environment(backend_environment(),local=True))")
            args = [sys.executable, '-c', launcher, *args]
        self.command(name, args, original=original, timeout=180)
        if actual_output != output: MVP.private_bytes(output, actual_output.read_bytes())
        value = read(output); require(value.get('passed') is True, name + '_not_passed')
        return value

    def docker_inventory(self, project):
        require(re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,62}', project), 'safe_compose_project_required')
        result = subprocess.run(['docker', 'ps', '-a', '--filter', 'label=com.docker.compose.project=' + project,
                                 '--format', '{{.ID}}'], capture_output=True, text=True, check=True, timeout=20)
        ids = result.stdout.split()
        if not ids: return []
        require(all(re.fullmatch(r'[0-9a-f]{12}', value) for value in ids), 'container_ids_invalid')
        template = '{{json .Id}} {{json .Name}} {{json .Image}} {{json .State.Status}} {{json .Mounts}}'
        result = subprocess.run(['docker', 'inspect', '--format', template, *ids], capture_output=True,
                                text=True, check=True, timeout=20)
        decoder, rows = json.JSONDecoder(), []
        for line in result.stdout.splitlines():
            rest, values = line, []
            while rest.strip():
                value, end = decoder.raw_decode(rest.lstrip()); values.append(value); rest = rest.lstrip()[end:]
            identifier, name, image, status, mounts = values
            volumes = sorted(mount['Name'] for mount in mounts if mount['Type'] == 'volume')
            for mount in (mount for mount in mounts if mount['Type'] == 'volume'):
                volume = mount['Name']
                anonymous_fabric_default = (project.endswith('-fabric') and re.fullmatch(r'[0-9a-f]{64}',volume)
                    and mount['Destination'] in ('/etc/hyperledger/fabric','/var/hyperledger'))
                require(volume.startswith(project + '_') or anonymous_fabric_default, 'volumes_must_be_project_scoped_or_known_fabric_image_anonymous_volumes')
            rows.append({'id': identifier, 'name': name, 'imageId': image, 'status': status, 'volumes': volumes})
        return sorted(rows, key=lambda row: row['name'])

    def original_guard(self, before):
        MVP.no_other_backends()
        label = 'before' if before else 'after'
        value = self.helper('original-' + label, 'preflight', self.run / ('original-' + label + '.json'), original=True)
        self.command('original-chain-' + label, [sys.executable, ROOT / 'deploy/fabric/network.py', 'verify',
            '--output', self.run / ('original-chain-' + label + '.json'),
            *([] if before else ['--compare', self.run / 'original-chain-before.json'])], original=True, timeout=120)
        inventory = self.docker_inventory('uav-inspection') + self.docker_inventory('uav-inspection-fabric')
        image = subprocess.run(['docker', 'image', 'inspect', 'uav-inspection/evidence-chaincode:0.1.0',
                                '--format', '{{.Id}}'], capture_output=True, text=True, check=True, timeout=20).stdout.strip()
        if before:
            self.before = value; self.original_inventory = inventory; self.original_image = image
            self.report['originalBinding'] = FINAL.binding()
            accepted = read(ROOT / read(ROOT / '.local/final-acceptance/latest.json')['report'])
            require(accepted.get('passed') is True and accepted['binding'] == self.report['originalBinding'], 'accepted_original_build_required')
        else:
            FINAL.compare_original(self.before, value)
            require(inventory == self.original_inventory and image == self.original_image, 'original_containers_images_and_volumes_changed')
            require(FINAL.binding() == self.report['originalBinding'], 'original_production_binding_changed')
            objects = self.helper('original-objects', 'original-objects', self.run / 'original-objects.json',
                                  self.run / 'original-before.json', original=True)
            require(objects.get('allOriginalFormalObjectBytesVerified') is True, 'original_objects_must_be_re_read')
            self.report['originalProtection'] = {'passed': True, 'exactTwentyFourTablesUnchanged': True,
                'credentialsEnvMigrationsUnchanged': True, 'originalNetworkAndLedgerUnchanged': True,
                'containersImagesAndVolumesUnchanged': True,
                'originalFormalObjectsVerified': objects['originalFormalObjectCount']}
        self.checkpoint()

    def prepare(self):
        self.phase('protect-original'); self.original_guard(True)
        require(not self.docker_inventory(self.infra_project) and not self.docker_inventory(self.fabric_project), 'fresh_projects_required')
        self.phase('fresh-source-copy')
        self.copy.mkdir(mode=0o700)
        manifest = {}
        for path in source_files(ROOT):
            destination = self.copy / path; destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / path, destination); manifest[str(path)] = sha(destination)
        require(not (self.copy/'.env').exists() and not (self.copy/'.local').exists()
                and not (self.copy/'backend/target').exists() and not (self.copy/'chaincode/target').exists(), 'no_existing_runtime_state_may_be_copied')
        private_json(self.run/'source-manifest.json', manifest)
        local = self.copy/'.local'; local.mkdir(mode=0o700)
        # Only immutable downloaded inputs are reused, never tools, identities or ledger state.
        lock = read(self.copy/'deploy/fabric/versions.json')
        downloads = local/'fabric/downloads'; downloads.mkdir(parents=True, mode=0o700)
        for field in ('binary', 'samples'):
            name = lock[field+'_archive']; path = ROOT/'.local/fabric/downloads'/name
            require(sha(path) == lock[field+'_sha256'], 'pinned_archive_required')
            shutil.copy2(path, downloads/name)
        shutil.copytree(ROOT/'.local/m2', local/'m2')
        self.ports = allocate_ports()
        self.python('new-local-secrets', 'deploy/scripts/init-env.py')
        updates = {**{key:str(value) for key,value in self.ports.items()}, 'INFRA_COMPOSE_PROJECT_NAME': self.infra_project,
            'FABRIC_COMPOSE_PROJECT_NAME': self.fabric_project, 'FABRIC_CHAINCODE_IMAGE': self.image,
            'FABRIC_ORGA_GATEWAY_ENDPOINT': 'localhost:' + str(self.ports['FABRIC_ORGA_PEER_PORT']),
            'FABRIC_ORGB_GATEWAY_ENDPOINT': 'localhost:' + str(self.ports['FABRIC_ORGB_PEER_PORT']),
            'S3_ENDPOINT': 'http://127.0.0.1:' + str(self.ports['S3_PORT']),
            'S3_PUBLIC_ENDPOINT': 'http://127.0.0.1:' + str(self.ports['S3_PORT'])}
        lines, found = [], set()
        for line in (self.copy/'.env').read_text().splitlines():
            key, separator, _ = line.partition('=')
            if separator and key in updates: line = key + '=' + updates[key]; found.add(key)
            lines.append(line)
        lines += [key+'='+value for key,value in updates.items() if key not in found]
        MVP.private_bytes(self.copy/'.env', ('\n'.join(lines)+'\n').encode())
        require(sha(self.copy/'.env') != sha(ROOT/'.env'), 'fresh_secrets_required')
        self.report.update(hostPorts=self.ports, sourceFileCount=len(manifest), sourceManifestSha256=sha(self.run/'source-manifest.json'),
             cleanStart={'existingEnvCopied':False,'existingCredentialsCopied':False,'existingTargetsCopied':False,
                         'existingIdentitiesCopied':False,'existingBusinessOrLedgerDataCopied':False,
                         'dependencyAndPinnedArchiveCachesReused':True})
        self.compose('validate-infra-compose', 'config', '--quiet')

    def build(self):
        self.phase('build-from-source')
        for name in ('chaincode', 'backend'):
            arguments = ['mvn', '-o', '-B', '-ntp', '-Dmaven.repo.local='+str(self.copy/'.local/m2'), '-f', name+'/pom.xml']
            if name == 'backend': arguments += ['-Pdatabase-probe,fabric-probe']
            self.command('build-' + name, [*arguments, 'clean', 'verify'], timeout=900)
            counts = {'tests':0,'failures':0,'errors':0,'skipped':0}
            for file in (self.copy/name/'target/surefire-reports').glob('TEST-*.xml'):
                xml = ET.parse(file).getroot()
                for key in counts: counts[key] += int(xml.attrib.get(key,0))
            require(counts['tests'] > 0 and all(counts[key] == 0 for key in ('failures','errors','skipped')), 'clean_build_tests_required')
            self.report.setdefault('buildTests',{})[name] = counts
        self.python('fresh-build-binding', '-c',
            "import sys,importlib.util,json;sys.path.insert(0,'deploy/scripts');s=importlib.util.spec_from_file_location('binding','deploy/scripts/verify-final.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.private_json(m.ROOT/'.local/fresh-binding.json',m.binding())")
        binding = read(self.copy/'.local/fresh-binding.json')
        require(binding['productionSourceSha256'] == self.report['originalBinding']['productionSourceSha256'], 'same_production_source_required')
        self.report['freshBuildBinding'] = binding

    def initialize_application(self, label):
        log = self.run / (label + '.log')
        descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'wb') as output:
            self.application = subprocess.Popen([sys.executable, 'deploy/scripts/run-backend.py', '--demo', '--ledger'],
                cwd=self.copy, env=self.environment, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            deadline = time.monotonic()+90
            while True:
                require(self.application.poll() is None, 'application_start_failed_inspect_'+label)
                text = log.read_text(errors='replace')
                match = re.search(r'Tomcat started on port (\d+)', text)
                if match and 'Demo data ready' in text: break
                require(time.monotonic()<deadline, 'application_start_timeout'); time.sleep(.25)
            base = 'http://127.0.0.1:'+match.group(1)
            for path in ('/api/v1/system/status', '/', '/ui/app.js'):
                request = MVP.urllib.request.Request(base+path)
                with MVP.HTTP.open(request, timeout=10) as response:
                    raw = response.read(); require(response.status==200 and bool(raw), 'packaged_http_required')
            self.report.setdefault('applicationStarts',[]).append({'label':label,'httpAndStaticUiPassed':True,'port':int(match.group(1)),'logSha256':sha(log)})
        finally: self.stop_application()

    def stop_application(self):
        if self.application and self.application.poll() is None:
            os.killpg(self.application.pid, signal.SIGTERM)
            try: self.application.wait(timeout=35)
            except subprocess.TimeoutExpired: os.killpg(self.application.pid, signal.SIGKILL); self.application.wait(timeout=10)

    def execute(self):
        self.prepare(); self.build()
        self.phase('fresh-infrastructure')
        self.infra_started = True
        self.compose('start-fresh-infra', 'up','-d','--wait','--wait-timeout','240')
        self.python('infra-seed-empty', 'deploy/scripts/verify-infra.py','--phase','seed')
        self.compose('stop-infra-probe', 'stop')
        self.compose('resume-infra-probe', 'up','-d','--wait','--wait-timeout','240')
        self.python('infra-check-empty', 'deploy/scripts/verify-infra.py','--phase','check')
        self.phase('fresh-identities-channel-chaincode')
        self.python('fresh-fabric-materials', 'deploy/fabric/prepare.py')
        original_materials = read(ROOT/'.local/fabric/network/materials.json')
        new_materials = read(self.copy/'.local/fabric/network/materials.json')
        require(original_materials['certificate_fingerprint'] != new_materials['certificate_fingerprint']
                and original_materials['genesis_sha256'] != new_materials['genesis_sha256'], 'new_identities_and_genesis_required')
        self.fabric_started = True
        self.python('start-fresh-fabric', 'deploy/fabric/network.py','up')
        self.python('create-new-channel', 'deploy/fabric/network.py','channel')
        self.python('deploy-fresh-chaincode', 'deploy/fabric/chaincode.py','deploy')
        self.python('new-demo-secrets', 'deploy/scripts/init-demo-credentials.py')
        self.phase('migrate-initialize-and-run-real-simulator')
        self.initialize_application('initial-application')
        self.helper('initialized-database', 'preflight', self.run/'initialized-database.json')
        self.python('retained-business-with-real-simulator', 'deploy/scripts/deployment_demo.py')
        pointer = read(self.copy/'.local/full-demo/latest.json')
        self.demo_run = self.copy/pointer['runDirectory']
        demo = read(self.copy/pointer['report'])
        require(demo.get('passed') is True and demo.get('realSimulator',{}).get('passed') is True, 'real_simulator_and_full_business_required')
        self.report['businessDemo'] = {'report':str((self.copy/pointer['report']).relative_to(ROOT)),
             'reportSha256':sha(self.copy/pointer['report']), 'newLedgerRegistrationCount':demo['newLedgerRegistrationCount'],
             'realSimulator':demo['realSimulator']}
        self.phase('snapshot-before-normal-restart')
        before = self.helper('copy-business-before-restart','preflight',self.run/'copy-before-restart.json')
        self.helper('copy-objects-before','original-objects',self.run/'copy-objects-before.json',self.run/'copy-before-restart.json')
        self.python('copy-chain-before','deploy/fabric/network.py','verify','--output',str(self.run/'copy-chain-before.json'))
        self.env_hash, self.credentials_hash = sha(self.copy/'.env'), sha(self.copy/'.local/demo-credentials.json')
        self.runtime_before = self.docker_inventory(self.infra_project)+self.docker_inventory(self.fabric_project)
        require(len(self.runtime_before)==9, 'nine_new_containers_required')
        self.python('infra-seed-business-restart','deploy/scripts/verify-infra.py','--phase','seed')
        self.phase('normal-stop-and-resume-with-same-volumes')
        self.compose('stop-own-infra','stop')
        self.python('stop-own-fabric','deploy/fabric/network.py','stop')
        stopped = self.docker_inventory(self.infra_project)+self.docker_inventory(self.fabric_project)
        require(len(stopped)==9 and all(item['status']=='exited' for item in stopped), 'all_nine_own_containers_stopped')
        self.compose('resume-own-infra','up','-d','--wait','--wait-timeout','240')
        self.python('resume-own-fabric','deploy/fabric/network.py','up')
        self.python('preserve-existing-materials','deploy/fabric/prepare.py')
        self.python('reuse-existing-channel','deploy/fabric/network.py','channel')
        self.python('reuse-existing-definition','deploy/fabric/chaincode.py','deploy')
        self.python('compare-copy-chain','deploy/fabric/network.py','verify','--output',str(self.run/'copy-chain-after.json'),
                    '--compare',str(self.run/'copy-chain-before.json'))
        self.python('infra-check-business-restart','deploy/scripts/verify-infra.py','--phase','check')
        self.python('preserve-env','deploy/scripts/init-env.py')
        self.python('preserve-demo-secrets','deploy/scripts/init-demo-credentials.py')
        self.initialize_application('restart-application-with-demo')
        self.python('readonly-business-after-restart','deploy/scripts/run-demo.py','--recheck',str(self.demo_run))
        after = self.helper('copy-business-after-restart','preflight',self.run/'copy-after-restart.json')
        FINAL.compare_original(before,after)
        objects = self.helper('copy-objects-after','original-objects',self.run/'copy-objects-after.json',self.run/'copy-before-restart.json')
        require(objects.get('allOriginalFormalObjectBytesVerified') is True, 'all_reproduced_objects_must_survive')
        require(self.docker_inventory(self.infra_project)+self.docker_inventory(self.fabric_project)==self.runtime_before,
                'same_containers_images_and_volumes_required')
        require(sha(self.copy/'.env')==self.env_hash and sha(self.copy/'.local/demo-credentials.json')==self.credentials_hash,
                'new_env_and_credentials_must_survive_restart')
        self.report['restartPreservation'] = {'passed':True,'sameContainersImagesAndVolumes':True,'allTwentyFourBusinessTablesExact':True,
            'schemaMigrationsAndCredentialsExact':True,'formalObjectsVerified':objects['originalFormalObjectCount'],
            'ledgerBlocksHashesIdentitiesAndDefinitionPreserved':True,'infraProbeIncludesRedisAofAndDurableRabbitMessage':True}

    def finish(self):
        failures = []
        try: self.stop_application()
        except Exception as error: failures.append(type(error).__name__)
        self.phase('retain-own-stopped-environment-and-recheck-original')
        if self.fabric_started:
            try: self.python('final-stop-own-fabric','deploy/fabric/network.py','stop')
            except Exception as error: failures.append(type(error).__name__)
        if self.infra_started:
            try: self.compose('final-stop-own-infra','stop')
            except Exception as error: failures.append(type(error).__name__)
        try:
            own = self.docker_inventory(self.infra_project) + self.docker_inventory(self.fabric_project)
            self.report['newEnvironmentRetainedStopped'] = all(item['status'] == 'exited' for item in own)
        except Exception as error:
            failures.append(type(error).__name__); self.report['newEnvironmentRetainedStopped'] = False
        if hasattr(self,'before'):
            try: self.original_guard(False)
            except Exception as error:
                failures.append(type(error).__name__)
                if isinstance(error,DeploymentError): self.report['originalGuardFailedCheck'] = str(error)
        require(not failures and self.report['newEnvironmentRetainedStopped'], 'finish_failed_inspect_components_and_original_guard')


def main():
    os.umask(0o077)
    WORK.mkdir(parents=True,exist_ok=True,mode=0o700)
    with (WORK/'run.lock').open('a') as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: raise DeploymentError('another_deployment_reproduction_is_active')
        directory=WORK/(dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ-')+uuid.uuid4().hex[:8])
        directory.mkdir(mode=0o700); run=Reproduction(directory); failure=None
        try: run.execute()
        except Exception as error:
            failure=type(error).__name__
            if isinstance(error,DeploymentError): run.report['failedCheck']=str(error)
        try: run.finish()
        except Exception as error:
            failure=failure or type(error).__name__;run.report['finishFailureType']=type(error).__name__
            if isinstance(error,DeploymentError):run.report['finishFailedCheck']=str(error)
        run.report.update(passed=failure is None,failureType=failure,completedAt=MVP.now())
        run.checkpoint()
        private_json(WORK/'latest.json',{'passed':failure is None,'runDirectory':str(directory.relative_to(ROOT)),
                                       'report':str((directory/'report.json').relative_to(ROOT))})
        print(json.dumps({'passed':failure is None,'report':str((directory/'report.json').relative_to(ROOT)),
                          'failedCheck':run.report.get('failedCheck'),'finishFailedCheck':run.report.get('finishFailedCheck')}))
        return 0 if failure is None else 1


if __name__=='__main__':sys.exit(main())
