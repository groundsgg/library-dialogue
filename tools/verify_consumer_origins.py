#!/usr/bin/env python3
"""Check actually loaded Maven JARs against this checkout's fresh publication manifest."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import unquote, urlparse

ROOT=Path(__file__).resolve().parents[1]


def verify_log(text, modules):
    found={}
    for class_name, location in re.findall(r'^MAVEN_ORIGIN (\S+) (\S+)$',text,re.M):
        family=class_name.removeprefix('gg.grounds.dialogue.').split('.')[0]
        module='dialogue-'+family
        uri=urlparse(location)
        if uri.scheme!='file' or uri.netloc not in ('','localhost') or module not in modules:
            raise ValueError('Invalid consumer origin')
        path=Path(unquote(uri.path))
        if path.suffix!='.jar' or not path.is_file():
            raise ValueError('Expected a real Maven JAR')
        digest=hashlib.sha256(path.read_bytes()).hexdigest()
        if digest!=modules[module]['sha256']:raise ValueError('STALE_CONSUMER_ARTIFACT: '+module)
        found[module]=digest
    for module,digest in re.findall(r'^CONSUMER_JAR (dialogue-[a-z]+) sha256=([a-f0-9]{64})$',text,re.M):
        if module not in modules or digest!=modules[module]['sha256']:raise ValueError('STALE_CONSUMER_ARTIFACT: '+module)
        found[module]=digest
    if not found:
        raise ValueError('NO_CONSUMER_ORIGINS')
    for module,digest in found.items():
        if module not in modules or digest!=modules[module]['sha256']:
            raise ValueError('STALE_CONSUMER_ARTIFACT: '+module)
    return set(found)


def verify_logs(logs, modules, required):
    required=set(required)
    if not required or not required.issubset(modules):raise ValueError('INVALID_REQUIRED_MODULES')
    checked=set()
    for log in logs:checked.update(verify_log(log,modules))
    missing=required-checked
    if missing:raise ValueError('MISSING_CONSUMER_MODULES: '+','.join(sorted(missing)))
    return checked


def main():
    p=argparse.ArgumentParser();p.add_argument('--log',type=Path,action='append',required=True);p.add_argument('--manifest',type=Path,default=ROOT/'.evidence/public-artifacts.json');p.add_argument('--require',action='append',required=True);args=p.parse_args()
    modules=json.loads(args.manifest.read_text())['modules']
    checked=verify_logs([log.read_text() for log in args.log],modules,args.require)
    print('CONSUMER_ORIGINS_OK '+','.join(sorted(checked)))


if __name__=='__main__':main()
