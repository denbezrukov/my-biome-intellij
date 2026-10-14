#!/usr/bin/env python3
"""Validate a downloaded plugin archive against its producer checksum and identity."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

VERSION = re.compile(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?')
PLUGIN_ID = 'com.github.biomejs.intellijbiome'
VENDOR = 'biomejs'


def check_version(version):
    if not VERSION.fullmatch(version):
        raise ValueError('Invalid release version')
    return version


def descriptors(archive):
    found = []
    with zipfile.ZipFile(archive) as distribution:
        if distribution.testzip() is not None:
            raise ValueError('Invalid ZIP member checksum')
        for name in distribution.namelist():
            if name.endswith('/META-INF/plugin.xml'):
                found.append(distribution.read(name))
            elif name.endswith('.jar'):
                with zipfile.ZipFile(io.BytesIO(distribution.read(name))) as jar:
                    if jar.testzip() is not None:
                        raise ValueError('Invalid ZIP/JAR member checksum')
                    for entry in jar.namelist():
                        if entry == 'META-INF/plugin.xml':
                            found.append(jar.read(entry))
    if len(found) != 1:
        raise ValueError('Expected exactly one plugin descriptor in distribution ZIP')
    return ET.fromstring(found[0])


def validate_archive(archive, version, sha256):
    check_version(version)
    if archive.name != f'intellij-biome-{version}.zip':
        raise ValueError('Archive filename does not match release version')
    if not archive.is_file():
        raise ValueError('Missing release archive')
    if not re.fullmatch(r'[0-9a-f]{64}', sha256):
        raise ValueError('Missing or invalid producer SHA256')
    digest = hashlib.sha256()
    with archive.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != sha256:
        raise ValueError('Archive SHA256 does not match build job')
    try:
        plugin = descriptors(archive)
    except (zipfile.BadZipFile, ET.ParseError, RuntimeError, OSError) as error:
        raise ValueError(f'Invalid ZIP or plugin descriptor: {error}') from error
    if plugin.tag != 'idea-plugin':
        raise ValueError('Invalid plugin descriptor root')
    if plugin.findtext('version') != version:
        raise ValueError('Plugin descriptor version does not match release version')
    idea_version = plugin.find('idea-version')
    since = idea_version.get('since-build') if idea_version is not None else None
    if plugin.findtext('id') != PLUGIN_ID or plugin.findtext('vendor') != VENDOR or since != '253':
        raise ValueError('Plugin descriptor identity/vendor/minimum differs from supported release contract')
    return {
        'upload_input': str(archive),
        'filename': archive.name,
        'sha256': sha256,
        'version': version,
        'plugin_id': PLUGIN_ID,
        'vendor': VENDOR,
        'since_build': since,
        'until_build': idea_version.get('until-build'),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--record', type=Path, required=True)
    args = parser.parse_args()
    try:
        record = validate_archive(args.archive, args.version, args.sha256)
        args.record.parent.mkdir(parents=True, exist_ok=True)
        args.record.write_text(json.dumps(record, indent=2) + '\n')
        print(json.dumps(record, indent=2))
    except (ValueError, OSError) as error:
        print(f'Release validation failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
