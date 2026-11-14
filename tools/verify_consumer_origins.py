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
        found[module]=hashlib.sha256(path.read_bytes()).hexdigest()
    for module,digest in re.findall(r'^CONSUMER_JAR (dialogue-[a-z]+) sha256=([a-f0-9]{64})$',text,re.M):
        found[module]=digest
    if not found:
        raise ValueError('NO_CONSUMER_ORIGINS')
    for module,digest in found.items():
        if module not in modules or digest!=modules[module]['sha256']:
            raise ValueError('STALE_CONSUMER_ARTIFACT: '+module)
    return set(found)


def main():
    p=argparse.ArgumentParser();p.add_argument('--log',type=Path,action='append',required=True);p.add_argument('--manifest',type=Path,default=ROOT/'.evidence/public-artifacts.json');args=p.parse_args()
    modules=json.loads(args.manifest.read_text())['modules'];checked=set()
    for log in args.log:checked.update(verify_log(log.read_text(),modules))
    print('CONSUMER_ORIGINS_OK '+','.join(sorted(checked)))


if __name__=='__main__':main()
