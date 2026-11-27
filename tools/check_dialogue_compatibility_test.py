import importlib.util
import contextlib
import io
import shutil
from unittest.mock import patch
import os
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

TOOL = Path(__file__).with_name('check_dialogue_compatibility.py')

class CompatibilityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def jar(self, name, method, schema=b'{}'):
        folder = self.root / name
        folder.mkdir()
        source = folder / 'Api.java'
        source.write_text('package sample; public class Api { public Api(){} '+method+' public static class Nested { public int count; } }')
        subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/javac'), '-d', str(folder), str(source)], check=True, capture_output=True)
        jar = folder / 'sample.jar'
        with zipfile.ZipFile(jar, 'w') as z:
            for p in folder.rglob('*.class'): z.write(p, p.relative_to(folder))
            z.writestr('schemas/v1.json', schema)
        return jar

    def tool(self):
        self.assertTrue(TOOL.exists(), 'compatibility checker missing')
        spec = importlib.util.spec_from_file_location('compat', TOOL)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_removedMemberRejectsActualJar(self):
        tool = self.tool()
        before = self.jar('before','public void keep(){}')
        after = self.jar('after','')
        with self.assertRaises(ValueError): tool.compare_api(tool.api_signature(before), tool.api_signature(after))

    def test_changedSchemaRejectsActualJar(self):
        tool = self.tool()
        before = self.jar('before', '', b'{}')
        after = self.jar('after', '', b'{"type":"object"}')
        with self.assertRaises(ValueError): tool.compare_schemas(tool.schemas(before), tool.schemas(after))

    def test_unmodifiedLegacyApiPasses(self):
        tool = self.tool()
        before = self.jar('before','public void keep(){}')
        after = self.jar('after','public void keep(){}')
        tool.compare_api(tool.api_signature(before), tool.api_signature(after))
        self.assertIn('sample.Api$Nested', tool.api_signature(before))

    def test_preserves_legacy_signatures_with_kotlin_additions(self):
        tool = self.tool()
        header = 'public final class sample.Api {\n'
        method = '  public java.lang.String value();\n    descriptor: ()Ljava/lang/String;\n'
        getter = '  public java.lang.String getValue();\n    descriptor: ()Ljava/lang/String;\n'
        before = {'sample.Api': header + method + '}\n'}
        after = {'sample.Api': header + getter + method.replace('public ', 'public final ') + '}\n'}
        tool.compare_api(before, after)
        with self.assertRaisesRegex(ValueError, 'API_COMPATIBILITY_FAILED'):
            tool.compare_api(before, {'sample.Api': header + getter + '}\n'})

    def test_requires_explicit_exception_to_lose_record_kind(self):
        tool = self.tool()
        name = 'sample.Api'
        before = {name: 'public final class sample.Api extends java.lang.Record {\n}\n'}
        after = {name: 'public final class sample.Api {\n}\n'}
        with self.assertRaisesRegex(ValueError, 'API_COMPATIBILITY_FAILED'):
            tool.compare_api(before, after)
        tool.KOTLIN_VALUE_CLASSES.add(name)
        tool.compare_api(before, after)

    def test_foreignBaselineCommitRejected(self):
        tool = self.tool()
        with self.assertRaises(ValueError): tool.validate_baseline({'commit':'wrong'})

class FrozenConsumerInputsTests(unittest.TestCase):
    def test_invalid_frozen_inputs_rejected_before_any_compile(self):
        import check_dialogue_compatibility as tool
        root = TOOL.parent.parent
        snapshot = Path('compatibility/consumers/e299b88-v1')
        cases = ('missing_manifest', 'invalid_manifest', 'wrong_source', 'wrong_target',
                 'wrong_version', 'changed_hash', 'missing_file', 'changed_source',
                 'changed_harness', 'extra_file', 'symlink')
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                mirror = Path(temporary)
                (mirror / '.evidence/compatibility-baseline-maven').mkdir(parents=True)
                (mirror / 'fixtures').symlink_to(root / 'fixtures', target_is_directory=True)
                frozen = mirror / snapshot
                shutil.copytree(root / snapshot, frozen)
                manifest = frozen / 'manifest.json'
                source = frozen / 'java-consumer/src/main/java/example/Consumer.java'
                data = json.loads(manifest.read_text())
                if case == 'missing_manifest': manifest.unlink()
                elif case == 'invalid_manifest': manifest.write_text('{')
                elif case == 'wrong_source':
                    data['source_commit'] = '0' * 40
                    manifest.write_text(json.dumps(data))
                elif case == 'wrong_target':
                    data['legacy_sdk']['commit'] = '0' * 40
                    manifest.write_text(json.dumps(data))
                elif case == 'wrong_version':
                    data['legacy_sdk']['version'] = '1.8.0'
                    manifest.write_text(json.dumps(data))
                elif case == 'changed_hash':
                    data['files']['java-consumer/src/main/java/example/Consumer.java'] = '0' * 64
                    manifest.write_text(json.dumps(data))
                elif case == 'missing_file': source.unlink()
                elif case == 'changed_source': source.write_text(source.read_text() + '\n// changed\n')
                elif case == 'changed_harness':
                    harness = frozen / 'java-consumer/build.gradle.kts'
                    harness.write_text(harness.read_text() + '\n// changed\n')
                elif case == 'extra_file': (frozen / 'java-consumer/init.gradle').write_text('unexpected')
                elif case == 'symlink':
                    outside = mirror / 'external.java'
                    source.rename(outside)
                    source.symlink_to(outside)
                with patch.object(tool, 'ROOT', mirror), patch.object(
                    tool.subprocess, 'run', side_effect=AssertionError('Gradle ran before validation')
                ):
                    with self.assertRaisesRegex(ValueError, 'FROZEN_CONSUMER_'):
                        tool.binary_consumers(mirror / 'unused-repo', 'candidate')


class BinaryConsumerTests(unittest.TestCase):
    def test_current_candidate_sources_do_not_change_historical_binary_gate(self):
        root = TOOL.parent.parent
        self.assert_current_fixture_independence(root, root / '.evidence/maven')

    def test_current_candidate_sources_work_without_legacy_cache(self):
        root = TOOL.parent.parent
        with tempfile.TemporaryDirectory(dir=root / '.evidence') as temporary:
            cold_root = Path(temporary)
            (cold_root / '.evidence').mkdir()
            (cold_root / 'gradlew').symlink_to(root / 'gradlew')
            (cold_root / '.git').symlink_to(root / '.git')
            (cold_root / 'fixtures').symlink_to(root / 'fixtures', target_is_directory=True)
            snapshot = Path('compatibility/consumers/e299b88-v1')
            shutil.copytree(root / snapshot, cold_root / snapshot)
            # A fresh CI checkout has candidate publications but no legacy Maven cache.
            self.assert_current_fixture_independence(cold_root, root / '.evidence/maven')

    def assert_current_fixture_independence(self, root, repository):
        import check_dialogue_compatibility as tool
        with tempfile.TemporaryDirectory(dir=root / '.evidence') as temporary:
            mirror = Path(temporary)
            (mirror / '.evidence').mkdir()
            legacy_cache = root / '.evidence/compatibility-baseline-maven'
            if legacy_cache.is_dir():
                (mirror / '.evidence/compatibility-baseline-maven').symlink_to(
                    legacy_cache, target_is_directory=True
                )
            (mirror / 'gradlew').symlink_to(root / 'gradlew')
            # git archive must see this mirror as a checkout root on the capture path.
            (mirror / '.git').symlink_to(root / '.git')
            # These current sources cannot compile against the archived SDK. A historical
            # binary check must still execute the separately frozen, qualified consumers.
            for language, extension in (('java', 'java'), ('kotlin', 'kt')):
                current = mirror / 'fixtures' / (language + '-consumer')
                shutil.copytree(root / 'fixtures' / (language + '-consumer'), current,
                                ignore=shutil.ignore_patterns('build', '.gradle', '.kotlin'))
                source = current / 'src/main' / language / 'example' / ('Consumer.' + extension)
                source.write_text('package example; import unavailable.future.sdk.FutureApi;')
            snapshot = Path('compatibility/consumers/e299b88-v1')
            if (root / snapshot).exists(): shutil.copytree(root / snapshot, mirror / snapshot)
            output = io.StringIO()
            with patch.object(tool, 'ROOT', mirror), contextlib.redirect_stdout(output):
                try:
                    tool.binary_consumers(repository, '0.1.0-SNAPSHOT')
                except subprocess.CalledProcessError as failure:
                    self.fail(failure.stdout + failure.stderr)
            self.assertIn('CONSUMER_OK java', output.getvalue())
            self.assertIn('CONSUMER_OK kotlin', output.getvalue())
            self.assertIn('BINARY_COMPATIBILITY_OK', output.getvalue())
    def test_precompiledConsumerRejectsChangedCandidateMethod(self):
        import sys
        sys.path.insert(0,str(TOOL.parent))
        import check_dialogue_compatibility as tool
        self.assertTrue(hasattr(tool,'binary_consumers'),'binary consumer gate missing')
        root=TOOL.parent.parent
        with tempfile.TemporaryDirectory(dir=root/'.evidence') as tmp:
            import shutil
            temp=Path(tmp);repo=temp/'maven';shutil.copytree(root/'.evidence/maven',repo)
            source=temp/'DialogueRuntime.java';source.write_text('package gg.grounds.dialogue.core.runtime; public final class DialogueRuntime {}')
            subprocess.run([tool.jdk('javac'),'-d',str(temp/'classes'),str(source)],check=True,capture_output=True)
            for jar in (repo/'gg/grounds/dialogue-core/0.1.0-SNAPSHOT').glob('*.jar'):
                if jar.name.endswith(('-sources.jar','-javadoc.jar')):continue
                changed=temp/'changed.jar'
                with zipfile.ZipFile(jar) as before,zipfile.ZipFile(changed,'w') as after:
                    for item in before.infolist():
                        after.writestr(item,(temp/'classes/gg/grounds/dialogue/core/runtime/DialogueRuntime.class').read_bytes() if item.filename=='gg/grounds/dialogue/core/runtime/DialogueRuntime.class' else before.read(item))
                shutil.copy2(changed,jar)
                for algorithm in ('sha1','sha256','sha512','md5'):
                    jar.with_name(jar.name+'.'+algorithm).write_text(hashlib.new(algorithm,jar.read_bytes()).hexdigest())
            folder=repo/'gg/grounds/dialogue-core/0.1.0-SNAPSHOT'
            representative=next(p for p in folder.glob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar')))
            for metadata in folder.glob('*.module'):
                data=json.loads(metadata.read_text())
                for variant in data['variants']:
                    for file in variant.get('files',[]):
                        if file['name']=='dialogue-core-0.1.0-SNAPSHOT.jar':
                            file['size']=representative.stat().st_size
                            for algorithm in ('sha1','sha256','sha512','md5'):file[algorithm]=hashlib.new(algorithm,representative.read_bytes()).hexdigest()
                metadata.write_text(json.dumps(data))
                for algorithm in ('sha1','sha256','sha512','md5'):metadata.with_name(metadata.name+'.'+algorithm).write_text(hashlib.new(algorithm,metadata.read_bytes()).hexdigest())
            with self.assertRaises(subprocess.CalledProcessError) as failure:tool.binary_consumers(repo,'0.1.0-SNAPSHOT')
            self.assertIn('NoSuchMethodError',failure.exception.stdout+failure.exception.stderr)

class ToolingBaselineTests(unittest.TestCase):
    def test_toolingApiIsActuallyCompared(self):
        import sys
        sys.path.insert(0,str(TOOL.parent))
        import check_dialogue_compatibility as tool
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);baseline=root/'baseline';(baseline/'api').mkdir(parents=True);repo=root/'repo';hashes={}
            for module in tool.MODULES+('dialogue-testkit','dialogue-cli'):
                folder=repo/'gg/grounds'/module/'1.0.0';folder.mkdir(parents=True)
                with zipfile.ZipFile(folder/(module+'-1.0.0.jar'),'w') as z:z.writestr('marker','empty controlled JAR')
                api=baseline/'api'/(module+'.txt');api.write_text('{}\n');hashes[module]=hashlib.sha256(api.read_bytes()).hexdigest()
            data={'commit':tool.COMMIT,'java':25,'version':tool.BASE_VERSION,'api':{m:hashes[m] for m in tool.MODULES},'schemas':{}}
            (baseline/'baseline.json').write_text(json.dumps(data))
            altered=baseline/'api/dialogue-testkit.txt';altered.write_text(json.dumps({'sample.Missing':'public class sample.Missing {}'}));hashes['dialogue-testkit']=hashlib.sha256(altered.read_bytes()).hexdigest()
            (baseline/'tooling-baseline.json').write_text(json.dumps({'java':25,'api':{m:hashes[m] for m in ('dialogue-testkit','dialogue-cli')}}))
            with self.assertRaisesRegex(ValueError,'API_COMPATIBILITY_FAILED'):tool.check(repo,'1.0.0',baseline)

class ReviewedCompatibilityRegressions(unittest.TestCase):
    def test_removedSealedContractRejectsActualJar(self):
        import check_dialogue_compatibility as tool
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);signatures=[]
            for name,sealed in [('sealed',True),('open',False)]:
                folder=root/name;folder.mkdir();source=folder/'Api.java'
                source.write_text('package sample; public '+('sealed ' if sealed else '')+'interface Api '+('permits Impl ' if sealed else '')+'{} final class Impl implements Api {}')
                subprocess.run([tool.jdk('javac'),'-d',str(folder),str(source)],check=True,capture_output=True)
                jar=folder/'api.jar'
                with zipfile.ZipFile(jar,'w') as z:
                    for cls in folder.rglob('*.class'):z.write(cls,cls.relative_to(folder))
                signatures.append(tool.api_signature(jar))
            with self.assertRaisesRegex(ValueError,'API_COMPATIBILITY_FAILED'):tool.compare_api(*signatures)
    def test_missingToolingManifestCannotDisableGate(self):
        import check_dialogue_compatibility as tool
        import shutil
        root=TOOL.parent.parent
        with tempfile.TemporaryDirectory() as temporary:
            baseline=Path(temporary)/'baseline';shutil.copytree(root/'compatibility',baseline);(baseline/'tooling-baseline.json').unlink()
            with self.assertRaisesRegex(ValueError,'TOOLING_BASELINE_MISSING'):tool.check(root/'.evidence/maven','0.1.0-SNAPSHOT',baseline)
