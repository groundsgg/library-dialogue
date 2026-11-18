import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
TOOL=ROOT/'tools/verify_dialogue_release.py'

class ReleaseVerifierTests(unittest.TestCase):
 def tool(self):
  self.assertTrue(TOOL.exists(),'release verifier missing')
  spec=importlib.util.spec_from_file_location('releasecheck',TOOL);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
 def test_artifactFromWrongRepositoryRejected(self):
  m=self.tool()
  for url in ['https://example.com/maven','http://maven.pkg.github.com/groundsgg/library-dialogue','https://user:token@maven.pkg.github.com/groundsgg/library-dialogue']:
   with self.assertRaises(ValueError):m.validate_source(url,False)
 def test_localReviewNeverClaimsRemoteSuccess(self):
  m=self.tool();self.assertFalse(m.validate_source((ROOT/'.evidence/maven').as_uri(),True))
  with self.assertRaises(ValueError):m.validate_source((ROOT/'.evidence/maven').as_uri(),False)
  self.assertEqual('DIALOGUE_LOCAL_CONSUMERS_OK',m.success_marker(False));self.assertEqual('DIALOGUE_REMOTE_CONSUMERS_OK',m.success_marker(True))
 def test_wrongTagVersionOrMissingAssetRejectsBeforeConsumers(self):
  m=self.tool()
  with tempfile.TemporaryDirectory() as tmp:
   for version,asset in [('0.1.0',ROOT/'dialogue-cli/build/distributions/dialogue-cli-0.1.0-SNAPSHOT.zip'),('0.1.0-SNAPSHOT',Path(tmp)/'missing.zip')]:
    with self.subTest(version=version),self.assertRaises((ValueError,FileNotFoundError)):m.verify((ROOT/'.evidence/maven').as_uri(),version,asset,Path(tmp)/version,True)
 def test_staleCachedVersionCannotSatisfyFreshRun(self):
  m=self.tool()
  with tempfile.TemporaryDirectory() as tmp:
   work=Path(tmp);(work/'cached.jar').write_bytes(b'stale')
   with self.assertRaisesRegex(ValueError,'WORK_DIR_NOT_EMPTY'):m.verify((ROOT/'.evidence/maven').as_uri(),'0.1.0-SNAPSHOT',ROOT/'dialogue-cli/build/distributions/dialogue-cli-0.1.0-SNAPSHOT.zip',work,True)
 def test_missingModuleRejectsBeforeConsumers(self):
  m=self.tool()
  with tempfile.TemporaryDirectory() as tmp:
   import shutil
   repo=Path(tmp)/'maven';shutil.copytree(ROOT/'.evidence/maven',repo);shutil.rmtree(repo/'gg/grounds/dialogue-testkit')
   with self.assertRaises(subprocess.CalledProcessError):m.verify(repo.as_uri(),'0.1.0-SNAPSHOT',ROOT/'dialogue-cli/build/distributions/dialogue-cli-0.1.0-SNAPSHOT.zip',Path(tmp)/'work',True)

 def test_actualLocalRunEmitsOnlyLocalMarker(self):
  with tempfile.TemporaryDirectory(dir=ROOT/'.evidence',prefix='local-release-test-') as temporary:
   result=subprocess.run([sys.executable,str(TOOL),'--repository',(ROOT/'.evidence/maven').as_uri(),'--version','0.1.0-SNAPSHOT','--cli-zip',str(ROOT/'dialogue-cli/build/distributions/dialogue-cli-0.1.0-SNAPSHOT.zip'),'--work-dir',str(Path(temporary)/'fresh'),'--allow-local-review'],cwd=ROOT,capture_output=True,text=True)
   self.assertEqual(0,result.returncode,result.stdout+result.stderr)
   self.assertIn('DIALOGUE_LOCAL_CONSUMERS_OK',result.stdout)
   self.assertIn('CLI_DISTRIBUTION_OK',result.stdout)
   self.assertNotIn('DIALOGUE_REMOTE_CONSUMERS_OK',result.stdout)

class RedirectCredentialRegressions(unittest.TestCase):
 def test_crossOriginRedirectCannotReceivePackageCredential(self):
  import http.server,threading,os,base64
  from unittest.mock import patch
  import verify_dialogue_release as verifier
  received=[];source_auth=[];mode=['cross']
  class Sink(http.server.BaseHTTPRequestHandler):
   def do_GET(self):
    received.append(self.headers.get('Authorization'));self.send_response(200);self.end_headers();self.wfile.write(b'ok')
   def log_message(self,*args):pass
  target=http.server.ThreadingHTTPServer(('127.0.0.1',0),Sink)
  class Source(http.server.BaseHTTPRequestHandler):
   def do_GET(self):
    if self.path=='/same':
     received.append(self.headers.get('Authorization'));self.send_response(200);self.end_headers();self.wfile.write(b'ok');return
    source_auth.append(self.headers.get('Authorization'));self.send_response(302);self.send_header('Location',('http://127.0.0.1:'+str(target.server_port)+'/target') if mode[0]=='cross' else '/same');self.end_headers()
   def log_message(self,*args):pass
  source=http.server.ThreadingHTTPServer(('127.0.0.1',0),Source)
  threads=[threading.Thread(target=server.serve_forever,daemon=True) for server in (source,target)]
  for thread in threads:thread.start()
  try:
   with tempfile.TemporaryDirectory() as temporary,patch.object(verifier,'REGISTRY','http://127.0.0.1:'+str(source.server_port)),patch.dict(os.environ,{'GITHUB_ACTOR':'dummy-user','GITHUB_TOKEN':'DUMMY-ONLY'}):
    verifier.download_artifacts('1.0.0',Path(temporary)/'cross')
    self.assertEqual([None]*24,received)
    received.clear();mode[0]='relative';verifier.download_artifacts('1.0.0',Path(temporary)/'same')
    expected='Basic '+base64.b64encode(b'dummy-user:DUMMY-ONLY').decode()
    self.assertEqual([expected]*24,received);self.assertEqual([expected]*48,source_auth)
  finally:
   for server in (source,target):server.shutdown();server.server_close()
   for thread in threads:thread.join(timeout=5)
 def test_httpsRedirectCannotDowngrade(self):
  import verify_dialogue_release as verifier
  from urllib.request import Request
  with self.assertRaisesRegex(ValueError,'HTTPS_REDIRECT_REQUIRED'):verifier.OriginScopedRedirectHandler().redirect_request(Request('https://registry.invalid/artifact'),None,302,'Found',{},'http://registry.invalid/artifact')
