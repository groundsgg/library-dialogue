#!/usr/bin/env python3
"""Reproduce source-derived 1.8 evidence; output never repins the gate automatically."""
import argparse
import io
import subprocess
import tarfile
from pathlib import Path

from check_dialogue_major_candidate import (
    ROOT, MODULES, SOURCE_COMMIT, POLICY, artifact, digest, emit, snapshot, schemas, load_reference,
)


def capture(source, output):
    # Check the caller path before resolving it or performing extraction/build work.
    if source.is_symlink() or (source.exists() and (not source.is_dir() or any(source.iterdir()))):
        raise ValueError('SOURCE_DIRECTORY_NOT_EMPTY_OR_REAL: ' + str(source))
    source.mkdir(parents=True, exist_ok=True)
    source = source.resolve()
    pinned, reference, _, exclusions = load_reference(ROOT / 'compatibility')
    archive = subprocess.check_output(['git', 'archive', SOURCE_COMMIT], cwd=ROOT)
    if __import__('hashlib').sha256(archive).hexdigest() != pinned['archiveSha256']:
        raise ValueError('SOURCE_ARCHIVE_HASH_FAILED')
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        members = tar.getmembers()
        if any(not (m.isfile() or m.isdir()) for m in members):
            raise ValueError('SOURCE_ARCHIVE_ENTRY_INVALID')
        tar.extractall(source, filter='data')
    subprocess.run([str(source / 'gradlew'), '--no-parallel', '--max-workers=1',
                    'publishAllPublicationsToReviewRepository'], cwd=source, check=True)
    repository = source / '.evidence/maven'
    manifest = {
        'format': 1, 'sourceCommit': SOURCE_COMMIT, 'sourceRelease': '1.8.0',
        'publicationVersion': '0.1.0-SNAPSHOT', 'java': 25,
        'origin': 'immutable git archive; isolated local Gradle publication; not downloaded release bytes',
        'archiveSha256': __import__('hashlib').sha256(archive).hexdigest(),
        'policy': POLICY, 'modules': {}, 'files': {}, 'legacyFiles': {}, 'schemas': {},
    }
    for module in MODULES:
        jar = artifact(repository / 'gg/grounds' / module / '0.1.0-SNAPSHOT', module, '0.1.0-SNAPSHOT', 'jar')
        value = snapshot(jar, module, exclusions)
        if value != reference[module]: raise ValueError('REFERENCE_REPRODUCTION_FAILED: ' + module)
        target = output / (module + '.json')
        emit(target, value)
        manifest['files'][target.name] = digest(target)
        manifest['modules'][module] = {'jarSha256': digest(jar), 'rawTypes': len(value['raw']), 'inventoryTypes': len(value['api'])}
        if module == 'dialogue-json': manifest['schemas'] = schemas(jar)
    baseline = ROOT / 'compatibility'
    for target in sorted(baseline.rglob('*')):
        if not target.is_file() or not (target.name in ('baseline.json', 'tooling-baseline.json')
                or target.relative_to(baseline).parts[0] in ('api', 'consumers')): continue
        manifest['legacyFiles'][str(target.relative_to(baseline))] = digest(target)
    emit(output / 'manifest.json', manifest)
    print('REFERENCE_CAPTURED manifestSha256=' + digest(output / 'manifest.json'))
    print('Review source/provenance and pin this manifest explicitly; capture does not accept it.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    capture(args.source, args.output.resolve())


if __name__ == '__main__': main()
