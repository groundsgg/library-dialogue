#!/usr/bin/env python3
"""Launch the extracted distribution in a source-free work directory with JDK25."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]

def check_version(actual,requested):
    if actual.strip()!=requested:raise ValueError('CLI_VERSION_MISMATCH')

def check_archive(path,version):
    base='dialogue-cli-'+version
    with zipfile.ZipFile(path) as z:
        infos=z.infolist();names=[i.filename for i in infos]
        if len(names)>512 or len(set(names))!=len(names) or sum(i.file_size for i in infos)>128*1024*1024:raise ValueError('CLI_ARCHIVE_LIMIT')
        for i in infos:
            parts=PurePosixPath(i.filename).parts
            if not parts or parts[0]!=base or '..' in parts or '\\' in i.filename or ':' in i.filename or (i.external_attr>>16)&0o170000==0o120000:raise ValueError('CLI_ARCHIVE_PATH')
        required={base+'/bin/dialogue',base+'/bin/dialogue.bat',base+'/LICENSE',base+'/NOTICE',base+'/licenses/Apache-2.0.txt',base+'/licenses/DEPENDENCIES.txt'}
        required.update(base+'/lib/'+m+'-'+version+'.jar' for m in ('dialogue-core','dialogue-json','dialogue-cli'))
        if not required.issubset(names):raise ValueError('CLI_DISTRIBUTION_INCOMPLETE')
    return base

def invoke(launcher,args,cwd):
    command=(subprocess.list2cmdline([os.environ.get('COMSPEC','cmd.exe')])+' /d /s /c "'+subprocess.list2cmdline([str(launcher),*args])+'"' if os.name=='nt' else [str(launcher),*args])
    return subprocess.run(command,cwd=cwd,capture_output=True,text=True,encoding='utf-8',timeout=60)

def check_distribution(path,version,author_root,java=None,work_dir=None):
    if work_dir is None:
        with tempfile.TemporaryDirectory(prefix='dialogue CLI ü ') as temp:
            return check_distribution(path,version,author_root,java,Path(temp))
    base=check_archive(path,version);work_dir=Path(work_dir).resolve();work_dir.mkdir(parents=True,exist_ok=True)
    if any(work_dir.iterdir()):raise ValueError('CLI_WORK_DIR_NOT_EMPTY')
    with zipfile.ZipFile(path) as z:z.extractall(work_dir)
    home=work_dir/base;launcher=home/'bin'/('dialogue.bat' if os.name=='nt' else 'dialogue')
    if os.name!='nt':launcher.chmod(0o755)
    project=work_dir/'author project Grüße';project.mkdir()
    for name in ('dialogues','texts','context'):shutil.copytree(Path(author_root)/name,project/name)
    version_result=invoke(launcher,['--version'],work_dir)
    if version_result.returncode:raise ValueError('CLI_VERSION_LAUNCH_FAILED: '+version_result.stderr.strip())
    check_version(version_result.stdout,version)
    good=invoke(launcher,['validate','--root',str(project),'--format','json'],work_dir)
    report=json.loads(good.stdout)
    if good.returncode or report['status']!='valid' or report['toolVersion']!=version or report['validatorVersion']!=version:raise ValueError('CLI_VALIDATION_FAILED')
    (project/'dialogues/broken.json').write_text('{',encoding='utf-8')
    bad=invoke(launcher,['validate','--root',str(project),'--format','json'],work_dir)
    if bad.returncode!=1 or json.loads(bad.stdout)['status']!='invalid':raise ValueError('CLI_INVALID_INPUT_ACCEPTED')
    java=java or str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else (java or shutil.which('java'))
    if not java:raise ValueError('JDK25_REQUIRED')
    javac=str(Path(java).with_name('javac.exe' if os.name=='nt' else 'javac'))
    probe=work_dir/'probe';probe.mkdir();shutil.copy2(ROOT/'fixtures/cli-consumer/OriginProbe.java',probe)
    subprocess.run([javac,'-d',str(probe),str(probe/'OriginProbe.java')],check=True,capture_output=True)
    cp=os.pathsep.join([str(probe),str(home/'lib'/'*')])
    result=subprocess.run([java,'-cp',cp,'OriginProbe'],cwd=work_dir,check=True,capture_output=True,text=True,encoding='utf-8',timeout=60)
    print(result.stdout,end='');print('CLI_DISTRIBUTION_OK version='+version+' sha256='+hashlib.sha256(Path(path).read_bytes()).hexdigest())
    return result.stdout

def main():
    p=argparse.ArgumentParser();p.add_argument('--zip',type=Path,required=True);p.add_argument('--version',required=True);p.add_argument('--author-root',type=Path,required=True);p.add_argument('--java');p.add_argument('--work-dir',type=Path);a=p.parse_args()
    check_distribution(a.zip,a.version,a.author_root,a.java,a.work_dir)
if __name__=='__main__':main()
