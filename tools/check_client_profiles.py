"""Read installed client metadata; no client code or account data is copied."""
import argparse, json, pathlib, sys, zipfile
p=argparse.ArgumentParser();p.add_argument('--client',required=True,type=pathlib.Path);p.add_argument('--profile',required=True,choices=['public-26.2','grounds-26.3']);args=p.parse_args()
expected={'public-26.2':('26.2',776,88,0),'grounds-26.3':('26.3',777,97,1)}[args.profile]
try:
    if args.client.suffix=='.jar':
        with zipfile.ZipFile(args.client) as z: data=json.loads(z.read('version.json'))
    else:data=json.loads(args.client.read_text(encoding='utf-8'))
    pack=data['pack_version'];actual=(data['id'],data['protocol_version'],pack['resource_major'],pack['resource_minor'])
    if actual!=expected:raise ValueError(f'Expected {expected}, found {actual}')
    print(json.dumps({'profile':args.profile,'client':actual[0],'protocol':actual[1],'pack':[actual[2],actual[3]],'status':'CLIENT_PROFILE_OK'}))
except (OSError,ValueError,KeyError,zipfile.BadZipFile) as error:
    print(f'CLIENT_PROFILE_MISMATCH: {error}',file=sys.stderr);sys.exit(1)
