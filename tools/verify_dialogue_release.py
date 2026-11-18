#!/usr/bin/env python3
"""Verify a version through fresh remote consumers; local review has a distinct marker."""
import argparse
import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.parse import unquote,urlparse
from urllib.request import Request,HTTPRedirectHandler,build_opener
from check_cli_distribution import check_archive,check_distribution
from verify_public_artifacts import MODULES
from verify_consumer_origins import verify_logs

ROOT=Path(__file__).resolve().parents[1]
REGISTRY='https://maven.pkg.github.com/groundsgg/library-dialogue'

def validate_source(repository,allow_local):
    if repository==REGISTRY:return True
    uri=urlparse(repository)
    if allow_local and uri.scheme=='file' and uri.netloc in ('','localhost') and not uri.query and not uri.fragment and Path(unquote(uri.path)).is_dir():return False
    raise ValueError('UNSUPPORTED_REPOSITORY')

def success_marker(remote):return 'DIALOGUE_REMOTE_CONSUMERS_OK' if remote else 'DIALOGUE_LOCAL_CONSUMERS_OK'

class OriginScopedRedirectHandler(HTTPRedirectHandler):
    """Authenticate only the original origin; reject a TLS downgrade."""
    def redirect_request(self, request, response, code, message, headers, new_url):
        before=urlparse(request.full_url);after=urlparse(new_url)
        if before.scheme=='https' and after.scheme!='https':raise ValueError('HTTPS_REDIRECT_REQUIRED')
        if after.username or after.password:raise ValueError('REDIRECT_USERINFO_REJECTED')
        redirected=super().redirect_request(request,response,code,message,headers,new_url)
        def origin(uri):return (uri.scheme,uri.hostname,uri.port if uri.port is not None else (443 if uri.scheme=='https' else 80))
        if redirected is not None and origin(before)!=origin(after):redirected.remove_header('Authorization')
        return redirected

def download_artifacts(version,mirror):
    user=os.environ.get('GITHUB_ACTOR');token=os.environ.get('GITHUB_TOKEN')
    if not user or not token:raise ValueError('PACKAGE_READ_IDENTITY_REQUIRED')
    auth='Basic '+base64.b64encode((user+':'+token).encode()).decode()
    opener=build_opener(OriginScopedRedirectHandler())
    for module in MODULES:
        folder=mirror/'gg/grounds'/module/version;folder.mkdir(parents=True)
        for suffix in ('.pom','.jar','-sources.jar','-javadoc.jar'):
            filename=module+'-'+version+suffix;url=REGISTRY+'/gg/grounds/'+module+'/'+version+'/'+filename
            for attempt in range(3):
                try:
                    request=Request(url,headers={'Authorization':auth,'User-Agent':'dialogue-release-verifier'})
                    with opener.open(request,timeout=30) as response,(folder/filename).open('wb') as out:
                        size=0
                        while True:
                            block=response.read(65536)
                            if not block:break
                            size+=len(block)
                            if size>64*1024*1024:raise ValueError('REMOTE_ARTIFACT_LIMIT')
                            out.write(block)
                    break
                except HTTPError as error:
                    if attempt==2 or error.code not in (404,429,500,502,503,504):raise ValueError('REMOTE_ARTIFACT_DOWNLOAD_FAILED') from None
                    time.sleep(attempt+1)

def verify(repository,version,cli_zip,work_dir,allow_local=False):
    remote=validate_source(repository,allow_local)
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?',version) or (remote and version.endswith('-SNAPSHOT')):raise ValueError('RELEASE_VERSION_REQUIRED')
    work_dir=Path(work_dir).resolve()
    if work_dir.exists() and any(work_dir.iterdir()):raise ValueError('WORK_DIR_NOT_EMPTY')
    check_archive(cli_zip,version)
    work_dir.mkdir(parents=True,exist_ok=True)
    evidence=work_dir/'artifacts';evidence.mkdir()
    if remote:
        mirror=evidence/'maven';download_artifacts(version,mirror)
    else:mirror=Path(unquote(urlparse(repository).path)).resolve()
    artifact_run=subprocess.run([sys.executable,str(ROOT/'tools/verify_public_artifacts.py'),'--repository',str(mirror),'--version',version],cwd=ROOT,capture_output=True,text=True,check=True)
    (work_dir/'artifacts.log').write_text(artifact_run.stdout+artifact_run.stderr,encoding='utf-8')
    manifest=json.loads((mirror.parent/'public-artifacts.json').read_text());modules=manifest['modules']
    (work_dir/'public-artifacts.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    env=os.environ.copy();env['GRADLE_USER_HOME']=str(work_dir/'gradle-home')
    # No reusable Maven cache, MavenLocal, includeBuild or library source classes.
    consumers=work_dir/'consumers';consumers.mkdir();all_logs=[]
    shutil.copytree(ROOT/'gradle',work_dir/'gradle')
    fixtures=[('java',ROOT/'fixtures/java-consumer',{'dialogue-core','dialogue-json','dialogue-presentation'}),('kotlin',ROOT/'fixtures/kotlin-consumer',{'dialogue-core','dialogue-json','dialogue-presentation'}),('minestom',ROOT/'fixtures/minestom-consumer',{'dialogue-core','dialogue-presentation','dialogue-minestom'}),('standalone',ROOT/'examples/standalone-minestom',set())]
    for name,source,required in fixtures:
        folder=consumers/name;shutil.copytree(source,folder,ignore=shutil.ignore_patterns('build','.gradle','.kotlin'))
        command=[str(ROOT/'gradlew'),'-p',str(folder),'--no-daemon','test','run','-PdialogueRepository='+repository,'-PdialogueVersion='+version]
        if name=='standalone':command.append('--args=--smoke')
        result=subprocess.run(command,cwd=work_dir,env=env,capture_output=True,text=True,encoding='utf-8',timeout=900)
        log=result.stdout+result.stderr;(work_dir/(name+'.log')).write_text(log,encoding='utf-8')
        if result.returncode:raise ValueError('CONSUMER_FAILED_'+name.upper())
        if required:verify_logs([log],modules,required);all_logs.append(log)
        if name in ('java','kotlin'):
            test_log=(folder/'build/test-origins'/(name+'.log')).read_text(encoding='utf-8')
            verify_logs([test_log],modules,{'dialogue-core','dialogue-presentation','dialogue-testkit'});all_logs.append(test_log)
    with contextlib.redirect_stdout(io.StringIO()) as output:
        cli_log=check_distribution(cli_zip,version,ROOT/'examples/headless-dialogue/src/main/resources',work_dir=work_dir/'cli')
    (work_dir/'cli.log').write_text(output.getvalue(),encoding='utf-8')
    verify_logs([cli_log],modules,{'dialogue-core','dialogue-json','dialogue-cli'});all_logs.append(cli_log)
    checked=verify_logs(all_logs,modules,set(MODULES))
    receipt={'version':version,'repository':repository,'remote':remote,'modules':{m:modules[m]['sha256'] for m in sorted(checked)},'cliSha256':hashlib.sha256(Path(cli_zip).read_bytes()).hexdigest()}
    (work_dir/'verification.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
    print(output.getvalue(),end='');print(success_marker(remote)+' version='+version)
    return receipt

def main():
    p=argparse.ArgumentParser();p.add_argument('--repository',required=True);p.add_argument('--version',required=True);p.add_argument('--cli-zip',required=True,type=Path);p.add_argument('--work-dir',required=True,type=Path);p.add_argument('--allow-local-review',action='store_true');a=p.parse_args()
    try:verify(a.repository,a.version,a.cli_zip,a.work_dir,a.allow_local_review)
    except (ValueError,OSError,subprocess.SubprocessError) as error:
        print('RELEASE_VERIFICATION_FAILED: '+(str(error) if isinstance(error,ValueError) else type(error).__name__),file=sys.stderr);return 1
    return 0
if __name__=='__main__':sys.exit(main())
