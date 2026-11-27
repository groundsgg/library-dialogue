"""Major policy regressions exercise real JVM bytecode, not copied API fixtures."""
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class MajorCandidateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.jars = {}
        sources = {
            'before': 'public class Api<T> { public void keep(){} public void keep(int n){} public static class Nested {} }',
            'removed': 'public class Api<T> { public void keep(int n){} public static class Nested {} }',
            'overload': 'public class Api<T> { public void keep(){} public static class Nested {} }',
            'type': 'public class Api<T> { public void keep(){} public void keep(int n){} }',
            'generic-before': 'public class Api { public java.util.List<String> values(){return null;} }',
            'generic-after': 'public class Api { public java.util.List<Integer> values(){return null;} }',
            'supertype': 'public class Api<T> extends java.util.ArrayList<T> { public void keep(){} public void keep(int n){} public static class Nested {} }',
            'header': 'public class Api<T extends Number> { public void keep(){} public void keep(int n){} public static class Nested {} }',
            'sealed': 'public sealed interface Api permits Impl {} final class Impl implements Api {}',
            'unsealed': 'public interface Api {} final class Impl implements Api {}',
            'access': 'public class Api { public static int access$ordinary(){return 1;} public static int access$generated(){return 2;} }',
            'bridge': 'public class Api implements Comparable<Api> { public int compareTo(Api other){return 0;} }',
        }
        for label, body in sources.items():
            folder = cls.root / label
            folder.mkdir()
            source = folder / 'Api.java'
            source.write_text('package sample; ' + body)
            subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin/javac'), '-d', str(folder), str(source)], check=True, capture_output=True)
            jar = folder / 'api.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                for file in folder.rglob('*.class'):
                    archive.write(file, file.relative_to(folder))
                archive.writestr('schemas/v1.json', '{}')
            cls.jars[label] = jar

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def tool(self):
        self.assertTrue((ROOT / 'tools/check_dialogue_major_candidate.py').is_file(), 'major candidate gate missing')
        import check_dialogue_major_candidate as tool
        return tool

    def api(self, label):
        return self.tool().snapshot(self.jars[label])['api']

    def change(self, before, after, kind, owner):
        changes = self.tool().differences('dialogue-core', before, after)
        change = next(c for c in changes if c['kind'] == kind and c['owner'] == owner)
        return dict(change, rationale='Reviewed major migration', migration='Use the replacement API')

    def test_actual_removed_member_requires_exact_reviewed_entry(self):
        tool = self.tool()
        before, after = self.api('before'), self.api('removed')
        with self.assertRaisesRegex(ValueError, 'UNLISTED'):
            tool.check_changes({'dialogue-core': before}, {'dialogue-core': after}, [])
        entry = self.change(before, after, 'member', 'sample.Api')
        tool.check_changes({'dialogue-core': before}, {'dialogue-core': after}, [entry])
        with self.assertRaisesRegex(ValueError, 'UNLISTED|UNUSED'):
            tool.check_changes({'dialogue-core': before}, {'dialogue-core': self.api('overload')}, [entry])
        with self.assertRaisesRegex(ValueError, 'UNUSED'):
            tool.check_changes({'dialogue-core': before}, {'dialogue-core': before}, [entry])

    def test_actual_removed_type_generic_header_and_sealed_contract(self):
        tool = self.tool()
        for old, new, kind, owner in [('before', 'type', 'type', 'sample.Api$Nested'),
                                      ('before', 'header', 'header', 'sample.Api'),
                                      ('before', 'supertype', 'header', 'sample.Api'),
                                      ('sealed', 'unsealed', 'permitted', 'sample.Api')]:
            with self.subTest(kind=kind):
                before, after = self.api(old), self.api(new)
                with self.assertRaisesRegex(ValueError, 'UNLISTED'):
                    tool.check_changes({'dialogue-core': before}, {'dialogue-core': after}, [])
                entry = self.change(before, after, kind, owner)
                tool.check_changes({'dialogue-core': before}, {'dialogue-core': after}, [entry])

    def test_exact_replacement_pair_duplicate_wrong_owner_and_patterns_rejected(self):
        tool = self.tool()
        before, after = self.api('before'), self.api('removed')
        entry = self.change(before, after, 'member', 'sample.Api')
        for field, value in [('module', 'dialogue-json'), ('owner', 'sample.Other'), ('before', '*'), ('after', ['public void missing();', 'descriptor: ()V']), ('rationale', '')]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                tool.check_changes({'dialogue-core': before}, {'dialogue-core': after}, [dict(entry, **{field: value})])
        with self.assertRaisesRegex(ValueError, 'DUPLICATE'):
            tool.check_changes({'dialogue-core': before}, {'dialogue-core': after}, [entry, entry])

    def test_changed_declaration_same_jvm_identity_requires_exact_after(self):
        tool = self.tool()
        before, after = self.api('generic-before'), self.api('generic-after')
        entry = self.change(before, after, 'member', 'sample.Api')
        self.assertIsNotNone(entry['after'], 'changed JVM member declaration needs exact pairing')
        with self.assertRaisesRegex(ValueError, 'AFTER_MISMATCH'):
            tool.check_changes({'dialogue-core': before}, {'dialogue-core': after}, [dict(entry, after=None)])
        tool.check_changes({'dialogue-core': before}, {'dialogue-core': after}, [entry])

    def test_synthetic_flags_filter_accessors_only_and_preserve_bridge(self):
        tool = self.tool()
        jar = self.jars['access']
        with zipfile.ZipFile(jar) as archive:
            original = archive.read('sample/Api.class')
        # Mark only the real member's access flags synthetic in controlled actual bytecode.
        offset = tool.class_metadata(original)['members']['method:access$generated:()I']['offset']
        changed = bytearray(original)
        flags = int.from_bytes(changed[offset:offset + 2], 'big') | 0x1000
        changed[offset:offset + 2] = flags.to_bytes(2, 'big')
        target = self.root / 'synthetic.jar'
        with zipfile.ZipFile(target, 'w') as archive:
            archive.writestr('sample/Api.class', changed)
        value = tool.snapshot(target)
        self.assertIn('access$ordinary', value['api']['sample.Api'])
        self.assertNotIn('access$generated', value['api']['sample.Api'])
        self.assertIn('access$generated', value['raw']['sample.Api'])
        self.assertIn('compareTo(java.lang.Object)', self.api('bridge')['sample.Api'])


    def test_actual_jar_inventory_schema_and_missing_module(self):
        tool = self.tool()
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            for module in tool.MODULES:
                folder = repository / 'gg/grounds' / module / 'test'
                folder.mkdir(parents=True)
                shutil.copy2(self.jars['before'], folder / (module + '-test.jar'))
            before = tool.inventory(repository, 'test')
            json_jar = repository / 'gg/grounds/dialogue-json/test/dialogue-json-test.jar'
            with zipfile.ZipFile(json_jar, 'a') as archive:
                archive.writestr('schemas/extra.json', '{}')
            after = tool.inventory(repository, 'test')
            with self.assertRaisesRegex(ValueError, 'SCHEMA_COMPATIBILITY_FAILED'):
                tool.compare_schemas(before['schemas'], after['schemas'])
            shutil.rmtree(repository / 'gg/grounds/dialogue-cli')
            with self.assertRaises((ValueError, FileNotFoundError)):
                tool.inventory(repository, 'test')

    def test_exact_after_header_permitted_and_member_replacement(self):
        tool = self.tool()
        for old, new, kind in [('before', 'header', 'header'), ('sealed', 'unsealed', 'permitted')]:
            before, after = self.api(old), self.api(new)
            entry = self.change(before, after, kind, 'sample.Api')
            with self.assertRaisesRegex(ValueError, 'AFTER_MISMATCH'):
                tool.check_changes({'dialogue-core': before}, {'dialogue-core': after}, [dict(entry, after='wrong')])
        before, after = self.api('before'), self.api('removed')
        entry = self.change(before, after, 'member', 'sample.Api')
        # Explicit target verification is exact and independent of name inference.
        entry['after'] = ['public void keep(int);', 'descriptor: (I)V']
        tool.check_changes({'dialogue-core': before}, {'dialogue-core': after}, [entry])

    def test_integrity_rejects_mutations_before_reading_jars(self):
        tool = self.tool()
        original = ROOT / 'compatibility/major-candidate'
        self.assertTrue(original.is_dir(), 'pinned reference missing')
        cases = ['reference_manifest', 'reference_file', 'missing_reference', 'legacy', 'candidate_module', 'candidate_role', 'candidate_missing', 'wrong_reference', 'unknown_manifest_field', 'schema_exception', 'target_major', 'duplicate_key', 'candidate_schema', 'missing_inventory_module']
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                shutil.copytree(ROOT / 'compatibility', root / 'compatibility')
                policy = root / 'compatibility/major-candidate'
                candidate = policy / 'candidate.json'
                breaks = policy / 'breaks.json'
                if case == 'reference_manifest':
                    p = policy / 'reference-1.8/manifest.json'; p.write_text(p.read_text() + ' ')
                elif case == 'reference_file':
                    p = policy / 'reference-1.8/dialogue-core.json'; p.write_text('{}')
                elif case == 'missing_reference': (policy / 'reference-1.8/dialogue-cli.json').unlink()
                elif case == 'missing_inventory_module':
                    (root / 'compatibility/api/dialogue-testkit.txt').unlink()
                elif case == 'legacy': (root / 'compatibility/baseline.json').write_text('{}')
                elif case == 'candidate_missing': candidate.unlink()
                elif case.startswith('candidate_'):
                    data = json.loads(candidate.read_text())
                    if case == 'candidate_module': del data['modules']['dialogue-cli']
                    elif case == 'candidate_schema': data['schemas'] = {}
                    else: data['role'] = 'accepted-release'
                    candidate.write_text(json.dumps(data))
                else:
                    data = json.loads(breaks.read_text())
                    if case == 'wrong_reference': data['reference']['commit'] = '0' * 40
                    elif case == 'unknown_manifest_field': data['exceptions'] = []
                    elif case == 'target_major': data['targetMajor'] = 3
                    elif case == 'duplicate_key':
                        breaks.write_text('{"format":1,"format":1}')
                        with self.assertRaises(ValueError): tool.validate_inputs(root / 'compatibility')
                        continue
                    else: data['changes'] = [{'kind': 'schema'}]
                    breaks.write_text(json.dumps(data))
                with self.assertRaises(ValueError): tool.validate_inputs(root / 'compatibility')

    def test_current_six_jars_empty_manifest_and_kotlin_defaults(self):
        tool = self.tool()
        repository = ROOT / '.evidence/maven'
        tool.check(repository, '0.1.0-SNAPSHOT', ROOT / 'compatibility')
        core = tool.snapshot(tool.artifact(repository / 'gg/grounds/dialogue-core/0.1.0-SNAPSHOT', 'dialogue-core', '0.1.0-SNAPSHOT', 'jar'))
        surface = '\n'.join(core['api'].values())
        self.assertIn('$default(', surface)
        self.assertIn('kotlin.jvm.internal.DefaultConstructorMarker', surface)


if __name__ == '__main__':
    unittest.main()
