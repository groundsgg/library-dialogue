import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOL = ROOT / 'tools/verify_public_artifacts.py'
REPO = ROOT / '.evidence/maven'

class PublicationGateTest(unittest.TestCase):
    def test_real_public_artifacts_pass(self):
        result = subprocess.run([sys.executable, str(TOOL)], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('PUBLIC_ARTIFACTS_OK', result.stdout)

    def test_forbidden_dependency_in_an_actual_pom_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory) / 'maven'
            shutil.copytree(REPO, target)
            for pom in (target / 'gg/grounds/dialogue-core/0.1.0-SNAPSHOT').glob('*.pom'):
                tree = ET.parse(pom)
                ns = '{http://maven.apache.org/POM/4.0.0}'
                deps = ET.SubElement(tree.getroot(), ns + 'dependencies')
                dep = ET.SubElement(deps, ns + 'dependency')
                ET.SubElement(dep, ns + 'groupId').text = 'net.minestom'
                ET.SubElement(dep, ns + 'artifactId').text = 'minestom'
                ET.SubElement(dep, ns + 'version').text = 'forbidden'
                tree.write(pom)
            result = subprocess.run([sys.executable, str(TOOL), '--repository', str(target)], cwd=ROOT, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('POM_DEPENDENCIES', result.stdout + result.stderr)

if __name__ == '__main__':
    unittest.main()
