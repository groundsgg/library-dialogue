#!/usr/bin/env python3
"""Loopback status/ping + exact demo-pack download. This is not visual client acceptance."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROFILES = {'public-26.2': (776, 88, 0), 'grounds-26.3': (777, 97, 1)}


def runtime_paths(profile, config):
    if profile == 'public-26.2':
        if config is not None:
            raise ValueError('Public runtime must not use a Grounds classpath file')
        return []
    if config is None or not config.is_file():
        raise ValueError('Supply the complete explicit Grounds classpath file')
    paths = [Path(item) for item in config.read_text().strip().split(os.pathsep)]
    if not paths or any(not p.is_absolute() or not p.is_file() or p.suffix != '.jar' for p in paths):
        raise ValueError('Every Grounds classpath entry must be an existing absolute JAR')
    minestom = [p for p in paths if p.name.startswith('minestom-')]
    if len(minestom) != 1 or minestom[0].name != 'minestom-2026.11.08-26.3-grounds.jar':
        raise ValueError('Expected only the pinned Grounds Minestom runtime')
    if any(p.name.startswith(('dialogue-', 'npc-')) for p in paths):
        raise ValueError('Application libraries must come from the selected Maven artifacts')
    return paths


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_libraries(libraries, manifest):
    data = json.loads(manifest.read_text())
    expected = data['modules']
    result = {}
    for module in ('dialogue-core', 'dialogue-presentation', 'dialogue-minestom'):
        candidates = [p for p in libraries if p.name.startswith(module + '-')]
        if len(candidates) != 1 or module not in expected or sha256(candidates[0]) != expected[module]['sha256']:
            raise ValueError('Stale or missing Maven library: ' + module + '; publish, verify artifacts and installDist first')
        result[module] = {'name': candidates[0].name, 'sha256': sha256(candidates[0])}
    return result


def varint(value):
    value &= 0xffffffff
    out = bytearray()
    while True:
        part = value & 127
        value >>= 7
        out.append(part | (128 if value else 0))
        if not value:
            return bytes(out)


def read_exact(stream, length):
    out = bytearray()
    while len(out) < length:
        part = stream.read(length - len(out))
        if not part:
            raise EOFError('Truncated packet')
        out.extend(part)
    return bytes(out)


def read_varint(stream):
    value = 0
    for n in range(5):
        part = read_exact(stream, 1)[0]
        value |= (part & 127) << (7 * n)
        if part < 128:
            return value
    raise ValueError('Oversized varint')


def frame(payload):
    return varint(len(payload)) + payload


def read_frame(stream):
    length = read_varint(stream)
    if length > 1_048_576:
        raise ValueError('Oversized status packet')
    return io.BytesIO(read_exact(stream, length))


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--profile', choices=PROFILES, default='public-26.2')
    parser.add_argument('--consumer', choices=['basic', 'npc'], default='basic')
    parser.add_argument('--grounds-classpath-file', type=Path)
    parser.add_argument('--client', type=Path, help='Optional exact installed client JAR metadata check')
    parser.add_argument('--java')
    parser.add_argument('--artifact-manifest', type=Path, default=ROOT / '.evidence/public-artifacts.json')
    args = parser.parse_args()
    runtime = runtime_paths(args.profile, args.grounds_classpath_file)
    if args.client:
        subprocess.run(['python3', str(ROOT / 'tools/check_client_profiles.py'), '--client', str(args.client), '--profile', args.profile], check=True)
    project = ROOT / ('examples/standalone-minestom' if args.consumer == 'basic' else 'fixtures/npc-dialogue-consumer')
    distribution = 'standalone-dialogue-demo' if args.consumer == 'basic' else 'personal-npc-dialogue-consumer'
    libraries = sorted((project / 'build/install' / distribution / 'lib').glob('*.jar'))
    if not libraries:
        raise ValueError('Build the independent consumer with installDist first')
    origins = verify_libraries(libraries, args.artifact_manifest)
    main_class = 'example.DialogueDemo' if args.consumer == 'basic' else 'example.PersonalDialogueDemo'
    if runtime:
        compiled = project / 'build/classes/java/groundsProfile'
        if not compiled.is_dir():
            raise ValueError('Compile the explicit Grounds demo profile first')
        libraries = [p for p in libraries if p.name.startswith(('dialogue-', 'npc-', 'kotlin-stdlib-'))]
        classpath = [compiled, project / 'build/resources/groundsProfile', *runtime, *libraries]
        main_class = 'example.GroundsDialogueDemo' if args.consumer == 'basic' else 'example.GroundsPersonalDialogueDemo'
    else:
        classpath = libraries
    java = args.java or (str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else shutil.which('java'))
    if not java:
        raise ValueError('Set JAVA_HOME to JDK 25 or supply --java')
    port, pack_port = free_port(), free_port()
    while port == pack_port:
        pack_port = free_port()
    command = [java, '-Djava.net.preferIPv4Stack=true', '-Ddialogue.profile=' + args.profile, '-Ddialogue.port=' + str(port), '-Ddialogue.packPort=' + str(pack_port), '-cp', os.pathsep.join(map(str, classpath)), main_class]
    evidence = ROOT / '.evidence'
    evidence.mkdir(exist_ok=True)
    log_path = evidence / ('smoke-' + args.profile + '-' + args.consumer + '.log')
    report = {'profile': args.profile, 'consumer': args.consumer, 'visual_client_verified': False, 'libraries': origins, 'runtime': [{'name': p.name, 'sha256': sha256(p)} for p in runtime]}
    with log_path.open('w') as log:
        process = subprocess.Popen(command, cwd=project, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 25
            while 'DIALOGUE_DEMO_READY' not in log_path.read_text():
                if process.poll() is not None:
                    raise RuntimeError('Demo exited before readiness; inspect ' + str(log_path))
                if time.monotonic() >= deadline:
                    raise TimeoutError('Demo did not become ready')
                time.sleep(.1)
            with socket.create_connection(('127.0.0.1', port), timeout=3) as connection:
                stream = connection.makefile('rb')
                address = b'localhost'
                connection.sendall(frame(b'\0' + varint(-1) + varint(len(address)) + address + struct.pack('>H', port) + varint(1)) + frame(b'\0'))
                response = read_frame(stream)
                assert read_varint(response) == 0
                status = json.loads(read_exact(response, read_varint(response)))
                assert status['version']['protocol'] == PROFILES[args.profile][0], status['version']
                nonce = 0x123456789
                connection.sendall(frame(b'\1' + struct.pack('>q', nonce)))
                pong = read_frame(stream)
                assert read_varint(pong) == 1 and struct.unpack('>q', read_exact(pong, 8))[0] == nonce
                report['minecraft_status'] = status['version']
                report['ping'] = 'passed'
            with urllib.request.urlopen('http://127.0.0.1:' + str(pack_port) + '/dialogue-demo.zip', timeout=3) as response:
                content = response.read(32 * 1024 * 1024 + 1)
                assert response.headers['Content-Type'] == 'application/zip' and len(content) <= 32 * 1024 * 1024
            with zipfile.ZipFile(io.BytesIO(content)) as pack:
                metadata = json.loads(pack.read('pack.mcmeta'))
                protocol, major, minor = PROFILES[args.profile]
                assert metadata['pack']['min_format'] == [major, minor] and metadata['pack']['max_format'] == [major, minor]
                assert 'assets/dialogue/font/mask.json' in pack.namelist()
                assert not any(name.startswith('assets/minecraft/') for name in pack.namelist())
                if args.consumer == 'npc':
                    assert 'assets/npc_demo/items/head.json' in pack.namelist()
            report['pack'] = {'sha256': hashlib.sha256(content).hexdigest(), 'bytes': len(content), 'metadata': metadata}
        finally:
            stop_started = time.monotonic()
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=40)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                raise RuntimeError('Demo cleanup hung')
            report['shutdown_ms'] = round((time.monotonic() - stop_started) * 1000)
    output = log_path.read_text()
    assert 'DEMO_CLEANUP_FAILED' not in output and 'Minestom server stopped successfully' in output, 'Inspect ' + str(log_path)
    path = evidence / ('smoke-' + args.profile + '-' + args.consumer + '.json')
    path.write_text(json.dumps(report, indent=2) + '\n')
    print('MINESTOM_SMOKE_OK ' + args.profile + ' ' + args.consumer + ' pack=' + report['pack']['sha256'])


if __name__ == '__main__':
    main()
