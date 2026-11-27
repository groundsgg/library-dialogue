#!/usr/bin/env python3
"""Explicit development-major JVM gate; never accepts a release baseline."""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path

from check_dialogue_compatibility import (
    ROOT, KOTLIN_VALUE_CLASSES, api_signature, compare_api, compare_schemas,
    member_signatures, schemas, validate_baseline, validate_frozen_consumers,
)
from verify_public_artifacts import artifact

MODULES = ('dialogue-core', 'dialogue-json', 'dialogue-presentation',
           'dialogue-minestom', 'dialogue-testkit', 'dialogue-cli')
SOURCE_COMMIT = 'f6772e81efd070cf8ca52a770061da5a90b67bf5'
REFERENCE_SHA256 = '52c5616f41d6180de67552bcf9798556cb3ff453faee8cfa70c82d13e83462c0'
ROLE = 'development-major-candidate-not-accepted-release-baseline'
POLICY = 'jvm-conservative-v1-synthetic-accessors-only'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def emit(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def load(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('DUPLICATE_JSON_KEY: ' + key)
            result[key] = value
        return result
    try:
        return json.loads(path.read_text(), object_pairs_hook=unique)
    except (OSError, ValueError) as error:
        raise ValueError('INPUT_INVALID: ' + str(path)) from error


def keys(value, expected, label):
    if not isinstance(value, dict) or set(value) != set(expected):
        raise ValueError(label + '_FIELDS_INVALID')


def class_metadata(data):
    """Read actual flags and member identity; no Kotlin/name-only visibility guesses."""
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('CLASSFILE_INVALID')
    pos = 8

    def u2():
        nonlocal pos
        value = struct.unpack_from('>H', data, pos)[0]
        pos += 2
        return value

    count = u2()
    cp = {}
    index = 1
    while index < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            size = u2()
            cp[index] = data[pos:pos + size].decode('utf-8', errors='replace')
            pos += size
        elif tag in (3, 4): pos += 4
        elif tag in (5, 6): pos += 8; index += 1
        elif tag in (7, 8, 16, 19, 20): pos += 2
        elif tag in (9, 10, 11, 12, 17, 18): pos += 4
        elif tag == 15: pos += 3
        else: raise ValueError('CLASSFILE_CONSTANT_INVALID')
        index += 1
    flags = u2()
    pos += 4
    interface_count = u2()
    pos += 2 * interface_count
    members = {}
    for kind in ('field', 'method'):
        for _ in range(u2()):
            offset = pos
            access = u2()
            name, descriptor = cp[u2()], cp[u2()]
            members[kind + ':' + name + ':' + descriptor] = {
                'flags': access, 'offset': offset,
            }
            for _ in range(u2()):
                u2()
                size = struct.unpack_from('>I', data, pos)[0]
                pos += 4 + size
    return {'flags': flags, 'members': members}


def snapshot(jar):
    raw = api_signature(jar)
    with zipfile.ZipFile(jar) as archive:
        metadata = {name[:-6].replace('/', '.'): class_metadata(archive.read(name))
                    for name in sorted(archive.namelist())
                    if name.endswith('.class') and name != 'module-info.class'}
    api = {}
    for owner, signature in raw.items():
        if metadata[owner]['flags'] & 0x1000:
            continue
        lines = signature.splitlines(keepends=True)
        excluded = set()
        for index, line in enumerate(lines[:-1]):
            match = re.search(r'\b(access\$[\w$]+)\(', line)
            if not match or not lines[index + 1].startswith('    descriptor: '):
                continue
            descriptor = lines[index + 1].strip().removeprefix('descriptor: ')
            member = metadata[owner]['members'].get('method:' + match[1] + ':' + descriptor)
            if member and member['flags'] & 0x1000:
                excluded.update((index, index + 1))
        api[owner] = ''.join(line for index, line in enumerate(lines) if index not in excluded)
    return {'raw': raw, 'classes': metadata, 'api': api}


def normalized_header(owner, header):
    if owner in KOTLIN_VALUE_CLASSES:
        return header.replace(' extends java.lang.Record', '', 1)
    return header


def member_identity(value):
    declaration, descriptor = value
    method = re.search(r'([\w.$]+)\(', declaration)
    name = method.group(1) if method else declaration.removesuffix(';').rsplit(' ', 1)[-1]
    return name, descriptor


def differences(module, before, after):
    result = []
    for owner, original in sorted(before.items()):
        candidate = after.get(owner)
        def change(kind, old, new):
            result.append({'module': module, 'owner': owner, 'kind': kind,
                           'before': old, 'after': new})
        if candidate is None:
            change('type', original, None)
            continue
        old_header = original.splitlines()[0]
        new_header = candidate.splitlines()[0]
        if new_header not in (old_header, normalized_header(owner, old_header)):
            change('header', old_header, new_header)
        old_permitted = original.partition('PermittedSubclasses:\n')[2]
        new_permitted = candidate.partition('PermittedSubclasses:\n')[2]
        if old_permitted != new_permitted:
            change('permitted', old_permitted, new_permitted)
        final = bool(re.match(r'^(public|protected) final class ', old_header))
        old_members = member_signatures(original, final)
        new_members = member_signatures(candidate, final)
        for member in sorted(old_members - new_members):
            # Pair declaration changes only by exact JVM name AND descriptor.
            # Different overloads/replacements are never inferred from a name.
            same_identity = [value for value in new_members if member_identity(value) == member_identity(member)]
            if len(same_identity) > 1: raise ValueError('AMBIGUOUS_JVM_MEMBER')
            change('member', list(member), list(same_identity[0]) if same_identity else None)
    return result


def validate_entry(entry):
    keys(entry, ('module', 'owner', 'kind', 'before', 'after', 'rationale', 'migration'), 'CHANGE')
    if entry['module'] not in MODULES or entry['kind'] not in ('type', 'header', 'permitted', 'member'):
        raise ValueError('CHANGE_KIND_OR_MODULE_INVALID')
    for key in ('owner', 'rationale', 'migration'):
        if not isinstance(entry[key], str) or not entry[key].strip():
            raise ValueError('CHANGE_' + key.upper() + '_INVALID')
    if not re.fullmatch(r'[\w$]+(?:\.[\w$]+)+', entry['owner']):
        raise ValueError('CHANGE_OWNER_INVALID')
    if entry['kind'] == 'member':
        for value in (entry['before'], entry['after']):
            if value is None and entry['before'] is not None: continue
            if (not isinstance(value, list) or len(value) != 2
                    or not all(isinstance(v, str) and v for v in value)
                    or not value[1].startswith('descriptor: ')):
                raise ValueError('CHANGE_MEMBER_INVALID')
    else:
        if not isinstance(entry['before'], str) or (not entry['before'] and entry['kind'] != 'permitted'):
            raise ValueError('CHANGE_BEFORE_INVALID')
        if entry['kind'] == 'type':
            if entry['after'] is not None: raise ValueError('CHANGE_TYPE_AFTER_INVALID')
        elif not isinstance(entry['after'], str):
            raise ValueError('CHANGE_AFTER_INVALID')


def identity(value):
    return json.dumps({key: value[key] for key in ('module', 'owner', 'kind', 'before')}, sort_keys=True)


def check_changes(before, after, entries):
    if not isinstance(entries, list): raise ValueError('CHANGES_INVALID')
    reviewed = {}
    for entry in entries:
        validate_entry(entry)
        key = identity(entry)
        if key in reviewed: raise ValueError('DUPLICATE_CHANGE')
        reviewed[key] = entry
    used = set()
    for module, api in before.items():
        for change in differences(module, api, after[module]):
            key = identity(change)
            entry = reviewed.get(key)
            if entry is None: raise ValueError('UNLISTED_CHANGE: ' + key)
            if change['kind'] == 'member' and change['after'] is not None:
                if entry['after'] != change['after']:
                    raise ValueError('CHANGE_AFTER_MISMATCH: ' + key)
            elif change['kind'] == 'member' and entry['after'] is not None:
                owner = change['owner']
                final = bool(re.match(r'^(public|protected) final class ', api[owner].splitlines()[0]))
                target = tuple(entry['after'])
                if target not in member_signatures(after[module][owner], final):
                    raise ValueError('REPLACEMENT_TARGET_MISSING: ' + key)
            elif entry['after'] != change['after']:
                raise ValueError('CHANGE_AFTER_MISMATCH: ' + key)
            used.add(key)
    if used != set(reviewed): raise ValueError('UNUSED_CHANGE: ' + str(sorted(set(reviewed) - used)))


def validate_api(value):
    if (not isinstance(value, dict) or not all(isinstance(owner, str) and isinstance(sig, str)
            and sig.startswith(('public ', 'protected ')) and sig.endswith('\n')
            for owner, sig in value.items())):
        raise ValueError('API_INVENTORY_INVALID')


def validate_inputs(baseline):
    policy = baseline / 'major-candidate'
    reference = policy / 'reference-1.8'
    manifest = load(reference / 'manifest.json')
    if digest(reference / 'manifest.json') != REFERENCE_SHA256:
        raise ValueError('REFERENCE_MANIFEST_HASH_FAILED')
    keys(manifest, ('format', 'sourceCommit', 'sourceRelease', 'publicationVersion', 'java',
                    'origin', 'archiveSha256', 'policy', 'modules', 'files', 'legacyFiles', 'schemas'), 'REFERENCE')
    if (type(manifest['format']) is not int or manifest['format'] != 1 or manifest['sourceCommit'] != SOURCE_COMMIT
            or manifest['sourceRelease'] != '1.8.0' or manifest['publicationVersion'] != '0.1.0-SNAPSHOT'
            or manifest['java'] != 25 or manifest['policy'] != POLICY
            or set(manifest['modules']) != set(MODULES)):
        raise ValueError('REFERENCE_PROVENANCE_INVALID')
    actual_files = {p.name for p in reference.iterdir() if p.is_file()}
    if (reference.is_symlink() or actual_files != set(manifest['files']) | {'manifest.json'}
            or any(p.is_symlink() or p.is_dir() for p in reference.iterdir())):
        raise ValueError('REFERENCE_FILES_INVALID')
    for name, expected in manifest['files'].items():
        if digest(reference / name) != expected: raise ValueError('REFERENCE_HASH_FAILED: ' + name)
    for name, expected in manifest['legacyFiles'].items():
        try: actual = digest(baseline / name)
        except OSError as error: raise ValueError('LEGACY_FILE_MISSING: ' + name) from error
        if actual != expected: raise ValueError('LEGACY_HASH_FAILED: ' + name)
    validate_baseline(load(baseline / 'baseline.json'))
    # Independent pinned provenance and all six historical consumer inputs stay enforced.
    if baseline.resolve() == (ROOT / 'compatibility').resolve(): validate_frozen_consumers()
    old = load(baseline / 'baseline.json')
    compare_schemas(old['schemas'], manifest['schemas'])
    reference_api = {module: load(reference / (module + '.json'))['api'] for module in MODULES}
    floors = {module: load(baseline / 'api' / (module + '.txt')) for module in MODULES}
    for module in MODULES:
        validate_api(reference_api[module])
        compare_api(floors[module], reference_api[module])
    candidate = load(policy / 'candidate.json')
    keys(candidate, ('format', 'role', 'policy', 'modules', 'schemas'), 'CANDIDATE')
    if (type(candidate['format']) is not int or candidate['format'] != 1 or candidate['role'] != ROLE or candidate['policy'] != POLICY
            or not isinstance(candidate['modules'], dict) or set(candidate['modules']) != set(MODULES)):
        raise ValueError('CANDIDATE_INVENTORY_INVALID')
    for api in candidate['modules'].values(): validate_api(api)
    compare_schemas(manifest['schemas'], candidate['schemas'])
    breaks = load(policy / 'breaks.json')
    keys(breaks, ('format', 'targetMajor', 'reference', 'changes'), 'BREAK_MANIFEST')
    if (type(breaks['format']) is not int or breaks['format'] != 1
            or type(breaks['targetMajor']) is not int or breaks['targetMajor'] != 2
            or breaks['reference'] != {'commit': SOURCE_COMMIT, 'manifestSha256': REFERENCE_SHA256}):
        raise ValueError('BREAK_REFERENCE_INVALID')
    check_changes(reference_api, candidate['modules'], breaks['changes'])
    return manifest, reference_api, floors, candidate, breaks


def audit_floors(floors, reference, candidate, entries):
    # Each old floor is first proven a compatible subset of the pinned reference.
    # Every old-floor difference must then have the same reviewed exact event, or
    # be contained by that owner's reviewed complete type removal.
    for module in MODULES:
        for change in differences(module, floors[module], candidate[module]):
            allowed = False
            for entry in entries:
                if entry['module'] != module or entry['owner'] != change['owner']: continue
                if entry['kind'] == 'type' and entry['after'] is None:
                    allowed = entry['before'] == reference[module][change['owner']]
                elif entry['kind'] == change['kind']:
                    if entry['kind'] == 'header':
                        allowed = (normalized_header(change['owner'], entry['before']) == normalized_header(change['owner'], change['before'])
                                   and entry['after'] == change['after'])
                    else:
                        allowed = (entry['before'] == change['before']
                                   and (change['kind'] != 'member' or change['after'] is None
                                        or entry['after'] == change['after']))
                if allowed: break
            if not allowed: raise ValueError('OLD_FLOOR_UNLISTED_CHANGE: ' + identity(change))


def inventory(repository, version, evidence=None):
    modules = {}
    schema = None
    for module in MODULES:
        jar = artifact(repository / 'gg/grounds' / module / version, module, version, 'jar')
        value = snapshot(jar)
        modules[module] = value['api']
        if evidence is not None: emit(evidence / (module + '.json'), value)
        if module == 'dialogue-json': schema = schemas(jar)
    return {'format': 1, 'role': ROLE, 'policy': POLICY, 'modules': modules, 'schemas': schema}


def check(repository, version, baseline=ROOT / 'compatibility', evidence=None):
    manifest, reference, floors, candidate, breaks = validate_inputs(baseline)
    actual = inventory(repository, version, evidence)
    if candidate != actual: raise ValueError('CANDIDATE_JAR_INVENTORY_MISMATCH')
    compare_schemas(manifest['schemas'], actual['schemas'])
    check_changes(reference, actual['modules'], breaks['changes'])
    audit_floors(floors, reference, actual['modules'], breaks['changes'])
    print('MAJOR_CANDIDATE_OK targetMajor=2 role=development modules=6 reviewedBreaks=' + str(len(breaks['changes'])))
    print('MAJOR_CANDIDATE_SCHEMA_OK')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument('--check', action='store_true')
    operation.add_argument('--capture-candidate', type=Path, metavar='OUTPUT')
    parser.add_argument('--repository', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--baseline', type=Path, default=ROOT / 'compatibility')
    parser.add_argument('--evidence', type=Path)
    args = parser.parse_args()
    if args.check: check(args.repository, args.version, args.baseline, args.evidence)
    else: emit(args.capture_candidate, inventory(args.repository, args.version, args.evidence))


if __name__ == '__main__':
    main()
