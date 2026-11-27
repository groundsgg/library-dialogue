"""R1 regressions compile real Java/Kotlin JARs and exercise source extraction."""
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import check_dialogue_major_candidate as gate
import capture_dialogue_major_reference as capture_tool


class GradleReached(Exception):
    pass


class R1Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.jars = {}
        cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
        dependencies = [('org.jetbrains.kotlin', name, '2.4.0') for name in (
            'kotlin-compiler-embeddable', 'kotlin-build-tools-api', 'kotlin-stdlib',
            'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
        dependencies += [('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                         ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
                         ('org.jetbrains', 'annotations', '13.0')]
        jars = [next((cache / group / name / version).glob('*/' + name + '-' + version + '.jar'))
                for group, name, version in dependencies]
        kotlin_cp = os.pathsep.join(map(str, jars))
        stdlib = str(next((cache / 'org.jetbrains.kotlin/kotlin-stdlib/2.4.0').glob('*/kotlin-stdlib-2.4.0.jar')))
        kotlin_sources = {
            'names-before': '''package sample
                class Api(val number: Int = 1) {
                    @JvmField val `alpha field`: Int = 1
                    @JvmField val `beta field`: Int = 2
                    @JvmField val `odd(field`: Int = 3
                    fun `alpha-foo`(): List<String> = emptyList()
                    fun `beta-foo`(): List<String> = emptyList()
                    fun `alpha method`(): Int = 1
                    fun `beta method`(): Int = 2
                    fun `alpha_foo`(): Int = 3
                    fun `face😀`(): Int = 4
                }''',
            'names-after': '''package sample
                class Api(val number: Int = 1) {
                    @JvmField val `beta field`: Int = 2
                    @JvmField val `odd(field`: Int = 3
                    fun `beta-foo`(): List<String> = emptyList()
                    fun `beta method`(): Int = 2
                    fun `alpha_foo`(): Int = 3
                    fun `face😀`(): Int = 4
                }''',
            'names-generic': '''package sample
                class Api(val number: Int = 1) {
                    @JvmField val `beta field`: Int = 2
                    @JvmField val `odd(field`: Int = 3
                    fun `beta-foo`(): List<Int> = emptyList()
                    fun `beta method`(): Int = 2
                    fun `alpha_foo`(): Int = 3
                    fun `face😀`(): Int = 4
                }''',
            'kotlin-access': '''package sample
                class Api(val number: Int = 1) {
                    fun `access$value`(value: Int = 3): Int = value
                    @JvmSynthetic fun `access$user`(): Int = 4
                }
                object Historical {
                    @JvmStatic @JvmSynthetic fun `access$kept`(): Int = 5
                }''',
        }
        for label, body in kotlin_sources.items():
            folder = cls.root / label
            folder.mkdir()
            source = folder / 'Api.kt'
            source.write_text(body)
            classes = folder / 'classes'
            result = subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin/java'), '-cp', kotlin_cp,
                            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                            '-classpath', stdlib, '-d', str(classes), str(source)],
                           capture_output=True, text=True)
            if result.returncode: raise AssertionError(result.stderr)
            cls.jars[label] = cls.pack(folder, classes)
        folder = cls.root / 'java-access'
        folder.mkdir()
        source = folder / 'Api.java'
        source.write_text('''package sample;
            interface Getter<T> { T access$get(); }
            public class Api implements Getter<String> {
                public String access$get() { return "x"; }
                public Object real$access$get() { return "y"; }
            }''')
        subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin/javac'), '-d', str(folder), str(source)],
                       check=True, capture_output=True)
        cls.jars['java-access'] = cls.pack(folder, folder)

    @classmethod
    def pack(cls, folder, classes):
        jar = folder / 'api.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            for file in classes.rglob('*.class'):
                # JVM permits colons; Kotlin source syntax does not. Preserve valid class bytes.
                archive.writestr(str(file.relative_to(classes)), file.read_bytes().replace(b'alpha_foo', b'alpha:foo'))
        return jar

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def surface(self, label):
        return gate.snapshot(self.jars[label])['api']

    def test_candidate_keeps_real_java_access_bridge_and_suffix_method(self):
        surface = self.surface('java-access')['sample.Api']
        for declaration in ('public java.lang.String access$get();',
                            'public java.lang.Object access$get();',
                            'public java.lang.Object real$access$get();'):
            self.assertIn(declaration, surface)

    def test_candidate_keeps_kotlin_access_default_mask_and_user_synthetics(self):
        surface = self.surface('kotlin-access')
        api = surface['sample.Api']
        self.assertIn('access$value$default(sample.Api, int, int, java.lang.Object)', api)
        self.assertIn('access$user()', api)
        self.assertIn('kotlin.jvm.internal.DefaultConstructorMarker', api)
        self.assertIn('access$kept()', surface['sample.Historical'])
        with zipfile.ZipFile(self.jars['kotlin-access']) as archive:
            member = gate.class_metadata(archive.read('sample/Historical.class'))['members']
        self.assertEqual(0x1019, member['method:access$kept:()I']['flags'])

    def test_exact_names_remain_distinct_and_removals_have_null_after(self):
        before, after = self.surface('names-before'), self.surface('names-after')
        changes = gate.differences('dialogue-core', before, after)
        self.assertEqual(3, len(changes))
        self.assertEqual({
            'public java.util.List<java.lang.String> alpha-foo();',
            'public final int alpha field;', 'public int alpha method();',
        }, {change['before'][0] for change in changes})
        self.assertTrue(all(change['after'] is None for change in changes))
        entries = [dict(change, rationale='Removed explicitly', migration='Use beta') for change in changes]
        gate.check_changes({'dialogue-core': before}, {'dialogue-core': after}, entries)

    def test_surviving_punctuation_identity_requires_its_exact_generic_after(self):
        before, after = self.surface('names-after'), self.surface('names-generic')
        changes = gate.differences('dialogue-core', before, after)
        self.assertEqual(1, len(changes))
        self.assertEqual(['public java.util.List<java.lang.Integer> beta-foo();',
                          'descriptor: ()Ljava/util/List;'], changes[0]['after'])
        entry = dict(changes[0], rationale='Changed generic', migration='Use integers')
        with self.assertRaisesRegex(ValueError, 'AFTER_MISMATCH'):
            gate.check_changes({'dialogue-core': before}, {'dialogue-core': after}, [dict(entry, after=None)])
        gate.check_changes({'dialogue-core': before}, {'dialogue-core': after}, [entry])

    def test_full_index_includes_constructor_spaces_colon_and_modified_utf8(self):
        surface = self.surface('names-before')
        expected = {
            ('dialogue-core', 'sample.Api', 'method', '<init>', '(I)V'),
            ('dialogue-core', 'sample.Api', 'method', 'face😀', '()I'),
            ('dialogue-core', 'sample.Api', 'method', 'alpha:foo', '()I'),
            ('dialogue-core', 'sample.Api', 'method', 'alpha method', '()I'),
            ('dialogue-core', 'sample.Api', 'field', 'alpha field', 'I'),
            ('dialogue-core', 'sample.Api', 'field', 'odd(field', 'I'),
        }
        self.assertTrue(expected <= set(surface.member_index))
        self.assertEqual([], gate.differences('dialogue-core', surface, surface))

    def test_ordered_association_rejects_descriptor_count_and_display_ambiguity(self):
        with zipfile.ZipFile(self.jars['java-access']) as archive:
            metadata = {'sample.Api': gate.class_metadata(archive.read('sample/Api.class'))}
        raw = {'sample.Api': self.surface('java-access')['sample.Api']}
        for changed in (raw['sample.Api'].replace('descriptor: ()V', 'descriptor: (I)V', 1),
                        raw['sample.Api'].replace('    descriptor: ()V\n', '', 1),
                        raw['sample.Api'].replace('real$access$get()', 'access$get()')):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                gate.indexed_surface('dialogue-core', {'sample.Api': changed}, metadata)

    def test_historical_exclusions_reproduce_all_pinned_text_exactly(self):
        reference = gate.ROOT / 'compatibility/major-candidate/reference-1.8'
        counts = {}
        for module in gate.MODULES:
            value = gate.load(reference / (module + '.json'))
            excluded, surface = gate.reference_surface(module, value)
            self.assertEqual(value['api'], dict(surface))
            counts[module] = len(excluded)
        self.assertEqual({'dialogue-cli': 2, 'dialogue-core': 14, 'dialogue-json': 0,
                          'dialogue-minestom': 75, 'dialogue-presentation': 13,
                          'dialogue-testkit': 0}, counts)

    def test_historical_style_exact_tuple_is_retained_only_on_candidate(self):
        module = 'dialogue-core'
        candidate = gate.snapshot(self.jars['kotlin-access'])
        value = {'raw': candidate['raw'], 'classes': candidate['classes'], 'api': dict(candidate['api'])}
        owner = 'sample.Historical'
        value['api'][owner] = value['api'][owner].replace(
            '  public static final int access$kept();\n    descriptor: ()I\n', '')
        excluded, historical = gate.reference_surface(module, value)
        self.assertEqual({(module, owner, 'method', 'access$kept', '()I', 0x1019)}, excluded)
        self.assertNotIn('access$kept()', historical[owner])
        self.assertIn('access$kept()', candidate['api'][owner])

    def test_capture_rejects_every_preexisting_entry_before_archive_or_gradle(self):
        for case in ('nested', 'identical', 'dotfile', 'symlink', 'dangling', 'file'):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                source = root / 'source'
                if case in ('symlink', 'dangling'):
                    target = root / 'target'
                    if case == 'symlink': target.mkdir()
                    source.symlink_to(target, target_is_directory=True)
                    sentinel = target
                elif case == 'file':
                    source.write_bytes(b'caller data')
                    sentinel = source
                else:
                    source.mkdir()
                    name = {'nested': 'dialogue-core/src/main/kotlin/Untracked.kt',
                            'identical': 'gradlew', 'dotfile': '.sentinel'}[case]
                    sentinel = source / name
                    sentinel.parent.mkdir(parents=True, exist_ok=True)
                    sentinel.write_bytes((gate.ROOT / 'gradlew').read_bytes() if case == 'identical' else b'caller data')
                before = sentinel.read_bytes() if sentinel.is_file() else None
                with patch.object(capture_tool.subprocess, 'check_output', side_effect=AssertionError('archive reached')), \
                     patch.object(capture_tool.subprocess, 'run', side_effect=AssertionError('Gradle reached')):
                    with self.assertRaisesRegex(ValueError, 'SOURCE_DIRECTORY'):
                        capture_tool.capture(source, root / 'output')
                if before is not None: self.assertEqual(before, sentinel.read_bytes())
                if case not in ('identical', 'file'): self.assertFalse((source / 'gradlew').exists())
                if case in ('symlink', 'dangling'): self.assertTrue(source.is_symlink())

    def test_capture_extracts_into_absent_or_empty_real_directory_before_gradle(self):
        archive = subprocess.check_output(['git', 'archive', gate.SOURCE_COMMIT], cwd=gate.ROOT)
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            expected = tar.extractfile('dialogue-core/build.gradle.kts').read()
        for existing in (False, True):
            with self.subTest(existing=existing), tempfile.TemporaryDirectory() as temp:
                source = Path(temp) / 'source'
                if existing: source.mkdir()
                real_run = subprocess.run
                def stop_at_gradle(args, **kwargs):
                    if Path(args[0]).name != 'gradlew': return real_run(args, **kwargs)
                    self.assertEqual(expected, (source / 'dialogue-core/build.gradle.kts').read_bytes())
                    self.assertTrue((source / 'gradlew').is_file())
                    raise GradleReached()
                with patch.object(capture_tool.subprocess, 'run', side_effect=stop_at_gradle):
                    with self.assertRaises(GradleReached):
                        capture_tool.capture(source, Path(temp) / 'output')


if __name__ == '__main__':
    unittest.main()
