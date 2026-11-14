import json,pathlib,subprocess,sys,tempfile,unittest
SCRIPT=pathlib.Path(__file__).with_name('check_client_profiles.py')
class ClientProfileTest(unittest.TestCase):
 def run_profile(self,protocol,major):
  with tempfile.TemporaryDirectory() as folder:
   path=pathlib.Path(folder)/'version.json';path.write_text(json.dumps({'id':'26.3','protocol_version':protocol,'pack_version':{'resource_major':major,'resource_minor':1}}))
   return subprocess.run([sys.executable,str(SCRIPT),'--client',str(path),'--profile','grounds-26.3'],capture_output=True,text=True)
 def test_accepts_matching_explicit_metadata(self):
  result=self.run_profile(777,97);self.assertEqual(0,result.returncode,result.stderr);self.assertIn('CLIENT_PROFILE_OK',result.stdout)
 def test_rejects_wrong_protocol_even_with_matching_pack(self):
  result=self.run_profile(776,97);self.assertNotEqual(0,result.returncode);self.assertIn('CLIENT_PROFILE_MISMATCH',result.stderr)
 def test_rejects_wrong_pack_even_with_matching_protocol(self):
  self.assertNotEqual(0,self.run_profile(777,88).returncode)
