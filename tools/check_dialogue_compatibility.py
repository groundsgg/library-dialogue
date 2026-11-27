#!/usr/bin/env python3
"""Check published JVM API and schemas against immutable archived sources."""
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
FROZEN_CONSUMERS = Path('compatibility/consumers/e299b88-v1')
FROZEN_SOURCE_COMMIT = 'f6772e81efd070cf8ca52a770061da5a90b67bf5'
FROZEN_MANIFEST_SHA256 = '8fe1774803de03d9710dc8f6377eb4f932c759d2841d2860b8cb6f2028e5252a'
GRADLE_FLAGS = ('--no-parallel', '--max-workers=1', '--refresh-dependencies')
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
    if classes:
        details=subprocess.run([jdk('javap'),'-v','-classpath',str(jar),*classes],capture_output=True,text=True,check=True).stdout
        for block in details.split('Classfile '):
            owner=re.search(r'^\s*this_class:\s*#\d+\s*//\s*(\S+)',block,re.M)
            permitted=re.search(r'^PermittedSubclasses:\r?\n((?:  \S+\r?\n)+)',block,re.M)
            if owner and permitted:
                name=owner.group(1).replace('/','.')
                if name in result:
                    children=sorted(line.strip().replace('/','.') for line in permitted.group(1).splitlines())
                    result[name]+='PermittedSubclasses:\n'+''.join('  '+child+'\n' for child in children)
    return result

def schemas(jar):
    with zipfile.ZipFile(jar) as archive:
        return {n: hashlib.sha256(archive.read(n)).hexdigest() for n in sorted(archive.namelist()) if n.startswith('schemas/') and n.endswith('.json')}

# These types copy collections or normalize values before exposing them. Kotlin JVM
# records cannot replace primary constructor properties in an init block.
KOTLIN_VALUE_CLASSES = {
    'gg.grounds.dialogue.core.definition.ArgumentValue$Number',
    'gg.grounds.dialogue.core.definition.DialogueDefinition',
    'gg.grounds.dialogue.core.definition.DialogueDefinition$Action',
    'gg.grounds.dialogue.core.definition.DialogueDefinition$Branch',
    'gg.grounds.dialogue.core.definition.DialogueDefinition$Capabilities',
    'gg.grounds.dialogue.core.definition.DialogueDefinition$Condition',
    'gg.grounds.dialogue.core.definition.DialogueDefinition$LocaleSpec',
    'gg.grounds.dialogue.core.definition.DialogueDefinition$Message',
    'gg.grounds.dialogue.core.definition.DialogueDefinition$Presentation',
    'gg.grounds.dialogue.core.port.ActionHandler$ActionRequest',
    'gg.grounds.dialogue.core.port.ActorPort$ActorRequest',
    'gg.grounds.dialogue.core.port.ConditionProvider$ConditionRequest',
    'gg.grounds.dialogue.core.port.HostBindings',
    'gg.grounds.dialogue.core.runtime.DialogueEnd',
    'gg.grounds.dialogue.core.runtime.DialogueStart',
    'gg.grounds.dialogue.core.runtime.DialogueStartResult$Rejected',
    'gg.grounds.dialogue.core.runtime.DialogueView',
    'gg.grounds.dialogue.core.runtime.PresentationPreferences',
    'gg.grounds.dialogue.core.text.LocaleContext',
    'gg.grounds.dialogue.core.text.TextResolver$TextRequest',
    'gg.grounds.dialogue.core.validation.HostCatalog',
    'gg.grounds.dialogue.core.validation.HostCatalog$ProviderSpec',
    'gg.grounds.dialogue.core.validation.ValidationReport',
    'gg.grounds.dialogue.json.DialogueCatalogLoader$ContentSources',
    'gg.grounds.dialogue.json.DialogueCatalogLoader$ValidatedCatalogue',
    'gg.grounds.dialogue.json.DialogueCatalogLoader$ReloadResult',
    'gg.grounds.dialogue.json.DialogueJsonCodec$DecodeResult',
    'gg.grounds.dialogue.json.DialogueJsonCodec$ContextResult',
    'gg.grounds.dialogue.presentation.ChoiceViewport$ChoiceWindow',
    'gg.grounds.dialogue.presentation.DialogueTheme',
    'gg.grounds.dialogue.presentation.TextLayout$Line',
    'gg.grounds.dialogue.presentation.TextLayout$TextLayoutResult',
    'gg.grounds.dialogue.presentation.UiLabelSource$LabelRequest',
    'gg.grounds.dialogue.presentation.UiLabelSource$UiLabels',
    'gg.grounds.dialogue.presentation.WidgetFrame',
    'gg.grounds.dialogue.presentation.WidgetFrame$Scrollbar',
    'gg.grounds.dialogue.minestom.DialogueServices',
    'gg.grounds.dialogue.minestom.MinestomDialogueRequest',
    'gg.grounds.dialogue.minestom.input.InputProfile',
    'gg.grounds.dialogue.minestom.input.TargetSnapshot',
    'gg.grounds.dialogue.minestom.render.EncodedFrame',
    'gg.grounds.dialogue.minestom.render.NpcDialogueSurface$Geometry',
    'gg.grounds.dialogue.testkit.RecordingDialogueRenderer$PresentationSnapshot',
    'gg.grounds.dialogue.testkit.RecordingDialogueSurface$SurfaceSnapshot',
}

def member_signatures(text, owner_is_final):
    lines = text.splitlines()
    signatures = set()
    for index, line in enumerate(lines[:-1]):
        if not lines[index + 1].startswith('    descriptor: '):
            continue
        declaration = line.strip()
        # A method on a final class cannot be overridden regardless of its own final flag.
        if owner_is_final and '(' in declaration:
            declaration = re.sub(r'^((?:public|protected)(?: static)?) final ', r'\1 ', declaration)
        signatures.add((declaration, lines[index + 1].strip()))
    return signatures

def compare_api(before, after):
    for name, original in before.items():
        candidate = after.get(name)
        if candidate is None:
            raise ValueError(f'API_COMPATIBILITY_FAILED: missing type {name}')
        old_header, *_ = original.splitlines()
        new_header, *_ = candidate.splitlines()
        expected_header = old_header
        if name in KOTLIN_VALUE_CLASSES:
            expected_header = old_header.replace(' extends java.lang.Record', '', 1)
        if new_header not in (old_header, expected_header):
            raise ValueError(f'API_COMPATIBILITY_FAILED: declaration changed for {name}')
        old_permitted = original.partition('PermittedSubclasses:\n')[2]
        new_permitted = candidate.partition('PermittedSubclasses:\n')[2]
        if old_permitted != new_permitted:
            raise ValueError(f'API_COMPATIBILITY_FAILED: permitted subclasses changed for {name}')
        owner_is_final = bool(re.match(r'^(public|protected) final class ', old_header))
        missing = member_signatures(original, owner_is_final) - member_signatures(candidate, owner_is_final)
        if missing:
            raise ValueError(f'API_COMPATIBILITY_FAILED: {name}: {sorted(missing)}')

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
        subprocess.run([str(source/'gradlew'), *GRADLE_FLAGS, *tasks, '-PversionOverride='+BASE_VERSION], cwd=source, check=True)
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
    if not (baseline/'tooling-baseline.json').is_file():raise ValueError('TOOLING_BASELINE_MISSING')
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

def validate_frozen_consumers():
    """Reject altered historical source, harness or provenance before any Gradle call."""
    snapshot = ROOT / FROZEN_CONSUMERS
    try:
        manifest_bytes = (snapshot / 'manifest.json').read_bytes()
        manifest = json.loads(manifest_bytes)
    except (OSError, ValueError) as error:
        raise ValueError('FROZEN_CONSUMER_MANIFEST_INVALID') from error
    if (not isinstance(manifest, dict) or manifest.get('format') != 1
            or manifest.get('source_commit') != FROZEN_SOURCE_COMMIT
            or manifest.get('source_path') != 'fixtures'
            or manifest.get('legacy_sdk') != {'commit': COMMIT, 'version': BASE_VERSION, 'java': 25}):
        raise ValueError('FROZEN_CONSUMER_PROVENANCE_FAILED')
    # Pin the manifest itself as well as its file hashes: removing an input or
    # changing both a source and its digest must not silently redefine history.
    if hashlib.sha256(manifest_bytes).hexdigest() != FROZEN_MANIFEST_SHA256:
        raise ValueError('FROZEN_CONSUMER_MANIFEST_HASH_FAILED')
    entries = list(snapshot.rglob('*'))
    if snapshot.is_symlink() or any(entry.is_symlink() for entry in entries):
        raise ValueError('FROZEN_CONSUMER_SYMLINK_REJECTED')
    files = {str(entry.relative_to(snapshot)) for entry in entries if entry.is_file()}
    if files != set(manifest['files']) | {'manifest.json'}:
        raise ValueError('FROZEN_CONSUMER_FILES_CHANGED')
    for name, digest in manifest['files'].items():
        if hashlib.sha256((snapshot / name).read_bytes()).hexdigest() != digest:
            raise ValueError('FROZEN_CONSUMER_HASH_FAILED: ' + name)
    return snapshot


def binary_consumers(repository, version):
    snapshot = validate_frozen_consumers()
    baseline=ROOT/'.evidence/compatibility-baseline-maven'
    if not baseline.exists():
        with tempfile.TemporaryDirectory(dir=ROOT/'.evidence') as temporary:
            capture(COMMIT,Path(temporary)/'baseline')
    for language in ('java','kotlin'):
        with tempfile.TemporaryDirectory(dir=ROOT/'.evidence',prefix='binary-'+language+'-') as temporary:
            folder=Path(temporary)/'consumer'
            shutil.copytree(snapshot/f'{language}-consumer',folder)
            subprocess.run([str(ROOT/'gradlew'),*GRADLE_FLAGS,'-p',str(folder),'classes','-PdialogueRepository='+baseline.as_uri(),'-PdialogueVersion='+BASE_VERSION],capture_output=True,text=True,check=True)
            original=folder/'build/classes'/language/'main'
            frozen=Path(temporary)/'frozen';shutil.copytree(original,frozen)
            def hashes():return {str(p.relative_to(frozen)):hashlib.sha256(p.read_bytes()).hexdigest() for p in frozen.rglob('*.class')}
            before=hashes()
            if not before:raise ValueError('BINARY_CLASSES_MISSING')
            shutil.rmtree(folder/'src/main');shutil.rmtree(folder/'build/classes')
            result=subprocess.run([str(ROOT/'gradlew'),*GRADLE_FLAGS,'-p',str(folder),'runBinaryCompatibility','-PbinaryClassesDir='+str(frozen),'-PdialogueRepository='+Path(repository).resolve().as_uri(),'-PdialogueVersion='+version],capture_output=True,text=True,check=True)
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
