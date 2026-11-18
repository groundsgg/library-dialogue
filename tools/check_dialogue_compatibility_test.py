import importlib.util
import os
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

    def test_foreignBaselineCommitRejected(self):
        tool = self.tool()
        with self.assertRaises(ValueError): tool.validate_baseline({'commit':'wrong'})
