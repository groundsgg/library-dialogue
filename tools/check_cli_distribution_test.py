import importlib.util
from pathlib import Path
import tempfile
import hashlib
import os
import subprocess
import unittest
import zipfile

class DistributionTests(unittest.TestCase):
 def tool(self):
  path=Path(__file__).with_name('check_cli_distribution.py');self.assertTrue(path.exists(),'distribution checker missing')
  spec=importlib.util.spec_from_file_location('dist',path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
 def test_missingLauncherOrJarFails(self):
  tool=self.tool()
  with tempfile.TemporaryDirectory() as tmp:
   path=Path(tmp)/'broken.zip'
   with zipfile.ZipFile(path,'w') as z:z.writestr('dialogue-cli-x/bin/dialogue','')
   with self.assertRaises(ValueError):tool.check_archive(path,'x')
 def test_snapshotVersionCannotPassReleaseCheck(self):
  tool=self.tool()
  with self.assertRaises(ValueError):tool.check_version('0.1.0-SNAPSHOT\n','0.1.0')
 def test_unsafeArchiveCannotEscape(self):
  tool=self.tool()
  with tempfile.TemporaryDirectory() as tmp:
   path=Path(tmp)/'broken.zip'
   with zipfile.ZipFile(path,'w') as z:z.writestr('../escape','')
   with self.assertRaises(ValueError):tool.check_archive(path,'x')
 def test_distributionDoesNotNeedCheckoutAndHandlesSpacesAndUnicode(self):
  tool=self.tool();root=Path(__file__).resolve().parents[1];path=root/'dialogue-cli/build/distributions/dialogue-cli-0.1.0-SNAPSHOT.zip'
  self.assertTrue(path.exists(),'build distZip first');tool.check_distribution(path,'0.1.0-SNAPSHOT',root/'examples/headless-dialogue/src/main/resources')

 def test_sameInputsProduceSameLinuxZip(self):
  if os.name=='nt':self.skipTest('Linux reproducibility gate')
  root=Path(__file__).resolve().parents[1];zip_path=root/'dialogue-cli/build/distributions/dialogue-cli-0.1.0-SNAPSHOT.zip'
  hashes=[]
  for _ in range(2):
   result=subprocess.run([str(root/'gradlew'),'clean',':dialogue-cli:distZip'],cwd=root,capture_output=True,text=True)
   self.assertEqual(0,result.returncode,result.stdout+result.stderr);hashes.append(hashlib.sha256(zip_path.read_bytes()).hexdigest())
  self.assertEqual(hashes[0],hashes[1])
  with self.assertRaises(ValueError):self.tool().check_distribution(zip_path,'0.1.0',root/'examples/headless-dialogue/src/main/resources')

 def test_windowsBatchLauncherHandlesSpacesAndUnicode(self):
  if os.name!='nt':self.skipTest('native Windows command processor gate')
  tool=self.tool()
  with tempfile.TemporaryDirectory(prefix='batch space ü ') as temporary:
   folder=Path(temporary);script=folder/'probe.bat';script.write_bytes(b'@echo off\r\necho PROBE_OK %*\r\n')
   result=tool.invoke(script,['--version'],folder)
   self.assertEqual(0,result.returncode,result.stderr);self.assertEqual('PROBE_OK --version',result.stdout.strip())
