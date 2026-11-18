#!/usr/bin/env python3
"""Strict accepted-C API and schema gate, built from immutable archived sources."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile
from verify_public_artifacts import artifact

ROOT = Path(__file__).resolve().parents[1]
COMMIT = 'e299b88d56df3e745ac45b319bdc88199d1ddaee'
BASE_VERSION = '0.0.0-compat-e299b88-SNAPSHOT'
MODULES = ('dialogue-core', 'dialogue-json', 'dialogue-presentation', 'dialogue-minestom')

def jdk(name):
    return str(Path(os.environ['JAVA_HOME']) / 'bin' / name) if os.environ.get('JAVA_HOME') else shutil.which(name)

def api_signature(jar):
    with zipfile.ZipFile(jar) as archive:
        classes = sorted(n[:-6].replace('/', '.') for n in archive.namelist()
                         if n.endswith('.class') and '/internal/' not in n and n != 'module-info.class')
    result = {}
    # javap's class header establishes visibility (including nested types).
    for cls in classes:
        out = subprocess.run([jdk('javap'), '-protected', '-s', '-classpath', str(jar), cls], capture_output=True, text=True, check=True).stdout
        lines = [s for s in out.splitlines() if not s.startswith('Compiled from')]
        if lines and re.match(r'^(public|protected) ', lines[0]):
            result[cls] = '\n'.join(lines) + '\n'
    return result

def schemas(jar):
    with zipfile.ZipFile(jar) as archive:
        return {n: hashlib.sha256(archive.read(n)).hexdigest() for n in sorted(archive.namelist()) if n.startswith('schemas/') and n.endswith('.json')}

def compare_api(before, after):
    if before != after: raise ValueError('API_COMPATIBILITY_FAILED')

def compare_schemas(before, after):
    if before != after: raise ValueError('SCHEMA_COMPATIBILITY_FAILED')

def validate_baseline(value):
    if value.get('commit') != COMMIT or value.get('java') != 25 or value.get('version') != BASE_VERSION:
        raise ValueError('BASELINE_PROVENANCE_FAILED')

def capture(commit, output):
    actual = subprocess.check_output(['git', 'rev-parse', commit], cwd=ROOT, text=True).strip()
    if actual != COMMIT: raise ValueError('BASELINE_PROVENANCE_FAILED')
    output.mkdir(parents=True, exist_ok=True)
    scratch = ROOT / '.evidence/compatibility-capture'
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=scratch) as temp:
        source = Path(temp)
        tar = source / 'source.tar'
        with tar.open('wb') as handle: subprocess.run(['git', 'archive', COMMIT], cwd=ROOT, stdout=handle, check=True)
        subprocess.run(['tar', '-xf', str(tar), '-C', str(source)], check=True)
        tasks = [f':{m}:publishAllPublicationsToReviewRepository' for m in MODULES]
        subprocess.run([str(source/'gradlew'), *tasks, '-PversionOverride='+BASE_VERSION], cwd=source, check=True)
        repository = source / '.evidence/maven'
        api = {}
        for module in MODULES:
            jar = artifact(repository/'gg/grounds'/module/BASE_VERSION, module, BASE_VERSION, 'jar')
            sig = api_signature(jar)
            (output/'api').mkdir(exist_ok=True)
            target = output/'api'/f'{module}.txt'
            target.write_text(json.dumps(sig, indent=2, sort_keys=True)+'\n')
            api[module] = hashlib.sha256(target.read_bytes()).hexdigest()
        sj = artifact(repository/'gg/grounds/dialogue-json'/BASE_VERSION, 'dialogue-json', BASE_VERSION, 'jar')
        value = {'commit':COMMIT, 'java':25, 'version':BASE_VERSION, 'source':'git archive; isolated Gradle publication', 'api':api, 'schemas':schemas(sj)}
        (output/'baseline.json').write_text(json.dumps(value, indent=2, sort_keys=True)+'\n')
        # Kept only as ignored binary-consumer input, never part of the committed baseline.
        shutil.copytree(repository, ROOT/'.evidence/compatibility-baseline-maven', dirs_exist_ok=True)

def check(repository, version, baseline):
    value = json.loads((baseline/'baseline.json').read_text()); validate_baseline(value)
    for module in MODULES:
        sig_file = baseline/'api'/f'{module}.txt'
        if hashlib.sha256(sig_file.read_bytes()).hexdigest() != value['api'][module]: raise ValueError('BASELINE_HASH_FAILED')
        jar = artifact(repository/'gg/grounds'/module/version, module, version, 'jar')
        compare_api(json.loads(sig_file.read_text()), api_signature(jar))
        if module == 'dialogue-json': compare_schemas(value['schemas'], schemas(jar))
    tooling=baseline/'tooling-baseline.json'
    if tooling.exists():
        added=json.loads(tooling.read_text())
        if added.get('java')!=25 or set(added.get('api',{}))!={'dialogue-cli','dialogue-testkit'}:raise ValueError('TOOLING_BASELINE_INVALID')
        for module,digest in added['api'].items():
            signature=baseline/'api'/f'{module}.txt'
            if hashlib.sha256(signature.read_bytes()).hexdigest()!=digest:raise ValueError('BASELINE_HASH_FAILED')
            jar=artifact(repository/'gg/grounds'/module/version,module,version,'jar')
            compare_api(json.loads(signature.read_text()),api_signature(jar))
    print('API_COMPATIBILITY_OK'); print('SCHEMA_COMPATIBILITY_OK')

def binary_consumers(repository, version):
    baseline=ROOT/'.evidence/compatibility-baseline-maven'
    if not baseline.exists():
        with tempfile.TemporaryDirectory(dir=ROOT/'.evidence') as temporary:
            capture(COMMIT,Path(temporary)/'baseline')
    for language in ('java','kotlin'):
        with tempfile.TemporaryDirectory(dir=ROOT/'.evidence',prefix='binary-'+language+'-') as temporary:
            folder=Path(temporary)/'consumer'
            shutil.copytree(ROOT/'fixtures'/f'{language}-consumer',folder,ignore=shutil.ignore_patterns('build','.gradle','.kotlin'))
            compile_result=subprocess.run([str(ROOT/'gradlew'),'-p',str(folder),'classes','-PdialogueRepository='+baseline.as_uri(),'-PdialogueVersion='+BASE_VERSION],capture_output=True,text=True,check=True)
            original=folder/'build/classes'/language/'main'
            frozen=Path(temporary)/'frozen';shutil.copytree(original,frozen)
            def hashes():return {str(p.relative_to(frozen)):hashlib.sha256(p.read_bytes()).hexdigest() for p in frozen.rglob('*.class')}
            before=hashes()
            if not before:raise ValueError('BINARY_CLASSES_MISSING')
            shutil.rmtree(folder/'src/main');shutil.rmtree(folder/'build/classes')
            result=subprocess.run([str(ROOT/'gradlew'),'-p',str(folder),'runBinaryCompatibility','-PbinaryClassesDir='+str(frozen),'-PdialogueRepository='+Path(repository).resolve().as_uri(),'-PdialogueVersion='+version],capture_output=True,text=True,check=True)
            if re.search(r'^> Task :compile',result.stdout,re.M) or before!=hashes():raise ValueError('BINARY_CLASSES_RECOMPILED')
            from verify_consumer_origins import verify_logs
            candidate={module:{'sha256':hashlib.sha256(artifact(Path(repository)/'gg/grounds'/module/version,module,version,'jar').read_bytes()).hexdigest()} for module in MODULES}
            verify_logs([result.stdout],candidate,{'dialogue-core','dialogue-json','dialogue-presentation'})
            print(result.stdout,end='')
    print('BINARY_COMPATIBILITY_OK')

def main():
    p=argparse.ArgumentParser();p.add_argument('--binary-consumers',action='store_true');p.add_argument('--capture');p.add_argument('--output',type=Path);p.add_argument('--check',action='store_true');p.add_argument('--repository',type=Path);p.add_argument('--version');p.add_argument('--baseline',type=Path,default=ROOT/'compatibility');a=p.parse_args()
    if a.capture and a.output: capture(a.capture,a.output)
    elif a.binary_consumers and a.repository and a.version: binary_consumers(a.repository,a.version)
    elif a.check and a.repository and a.version: check(a.repository,a.version,a.baseline)
    else:p.error('use --capture COMMIT --output DIR or --check --repository DIR --version VERSION')

if __name__=='__main__':main()
