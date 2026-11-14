#!/usr/bin/env python3
"""Verify the actual Maven publications, not project class directories."""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

MODULES = ('dialogue-core', 'dialogue-json', 'dialogue-presentation')
EXPECTED = {
    'dialogue-core': set(),
    'dialogue-json': {'gg.grounds:dialogue-core', 'com.google.code.gson:gson'},
    'dialogue-presentation': {'gg.grounds:dialogue-core'},
}
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def require(condition, code, message):
    if not condition:
        raise ValueError(f'{code}: {message}')


def artifact(directory, module, version, extension, classifier=''):
    value = version
    metadata = directory / 'maven-metadata.xml'
    if version.endswith('-SNAPSHOT') and metadata.exists():
        candidates = [n for n in ET.parse(metadata).getroot().findall('./versioning/snapshotVersions/snapshotVersion')
                      if n.findtext('extension') == extension and n.findtext('classifier', '') == classifier]
        require(candidates, 'METADATA_MISSING', f'{module} {extension} {classifier}')
        value = max(candidates, key=lambda n: n.findtext('updated', '')).findtext('value')
    suffix = '-' + classifier if classifier else ''
    path = directory / f'{module}-{value}{suffix}.{extension}'
    require(path.is_file(), 'ARTIFACT_MISSING', str(path))
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repository', type=pathlib.Path, default=pathlib.Path('.evidence/maven'))
    parser.add_argument('--version', default='0.1.0-SNAPSHOT')
    args = parser.parse_args()
    java_home = os.environ.get('JAVA_HOME')
    javap = str(pathlib.Path(java_home) / 'bin/javap') if java_home else shutil.which('javap')
    require(javap, 'JDK_REQUIRED', 'Set JAVA_HOME to JDK 25')
    publications = {}
    for module in MODULES:
        directory = args.repository / 'gg/grounds' / module / args.version
        pom = artifact(directory, module, args.version, 'pom')
        root = ET.parse(pom).getroot()
        dependencies = {f'{d.findtext("m:groupId", namespaces=NS)}:{d.findtext("m:artifactId", namespaces=NS)}'
                        for d in root.findall('m:dependencies/m:dependency', NS)}
        require(dependencies == EXPECTED[module], 'POM_DEPENDENCIES', f'{module}: {dependencies}')
        require(root.findtext('m:version', namespaces=NS) == args.version, 'POM_VERSION', module)
        require(root.findtext('m:licenses/m:license/m:name', namespaces=NS) == 'MIT License', 'LICENSE_METADATA', module)
        jar = artifact(directory, module, args.version, 'jar')
        artifact(directory, module, args.version, 'jar', 'sources')
        artifact(directory, module, args.version, 'jar', 'javadoc')
        with zipfile.ZipFile(jar) as archive:
            require('META-INF/LICENSE' in archive.namelist() and 'META-INF/NOTICE' in archive.namelist(), 'LICENSE_FILES', module)
            classes = []
            for name in archive.namelist():
                if not name.endswith('.class'):
                    continue
                data = archive.read(name)
                require(struct.unpack('>H', data[6:8])[0] == 69, 'BYTECODE_TARGET', name)
                if module != 'dialogue-json':
                    for forbidden in (b'com/google/gson', b'net/minestom', b'kotlin/', b'gg/grounds/scene'):
                        require(forbidden not in data, 'FORBIDDEN_CLASS_REFERENCE', f'{module}: {name} {forbidden!r}')
                classes.append(name[:-6].replace('/', '.'))
            if module == 'dialogue-presentation':
                require('default-theme/FONT-LICENSE.txt' in archive.namelist(), 'FONT_LICENSE', module)
                require('default-theme/metrics.properties' in archive.namelist(), 'FONT_METRICS', module)
        publications[module] = {'jar': str(jar.resolve()), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'classes': classes}
    classpath = os.pathsep.join(item['jar'] for item in publications.values())
    for module, item in publications.items():
        result = subprocess.run([javap, '-public', '-classpath', classpath, *item['classes']], capture_output=True, text=True)
        require(result.returncode == 0, 'API_INSPECTION', result.stderr)
        for forbidden in ('com.google.gson', 'net.minestom', 'kotlin.', 'gg.grounds.scene'):
            require(forbidden not in result.stdout, 'EXPOSED_PUBLIC_TYPE', f'{module}: {forbidden}')
        print(f'PUBLIC_ARTIFACT {module} {args.version} sha256={item["sha256"]}')
    output = args.repository.parent / 'public-artifacts.json'
    output.write_text(json.dumps({'version': args.version, 'modules': publications}, indent=2) + '\n')
    print('PUBLIC_ARTIFACTS_OK')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
