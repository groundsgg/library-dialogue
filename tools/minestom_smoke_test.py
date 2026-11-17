import pathlib
import tempfile
import unittest
import minestom_smoke as smoke

class RuntimeSelectionTest(unittest.TestCase):
    def test_grounds_requires_explicit_complete_runtime(self):
        with self.assertRaises(ValueError): smoke.runtime_paths('grounds-26.3',None)
    def test_public_cannot_silently_use_grounds_file(self):
        with self.assertRaises(ValueError): smoke.runtime_paths('public-26.2',pathlib.Path('anything.local'))
    def test_mixed_minestom_runtime_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            jars=[root/'minestom-2026.11.08-26.3-grounds.jar',root/'minestom-2026.10.07-26.2.jar']
            for jar in jars: jar.touch()
            config=root/'classpath.local';config.write_text(__import__('os').pathsep.join(map(str,jars)))
            with self.assertRaises(ValueError):smoke.runtime_paths('grounds-26.3',config)
    def test_explicit_runtime_preserves_only_complete_jar_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);jar=root/'minestom-2026.11.08-26.3-grounds.jar';jar.touch();config=root/'classpath.local';config.write_text(str(jar))
            self.assertEqual([jar],smoke.runtime_paths('grounds-26.3',config))
            jar.unlink()
            with self.assertRaises(ValueError):smoke.runtime_paths('grounds-26.3',config)
