#!/usr/bin/env python3
"""Check publication behavior and the downloaded artifact consumed by each job."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[2]


class ReleaseReviewTest(unittest.TestCase):
    def setUp(self):
        self.workflow = yaml.safe_load((ROOT / '.github/workflows/publish.yaml').read_text())

    def test_publication_preserves_upstream_dispatch_behavior_and_successful_build(self):
        inputs = self.workflow.get('on', self.workflow.get(True))['workflow_dispatch']['inputs']
        self.assertNotIn('publish', inputs)
        self.assertEqual({'contents': 'read'}, self.workflow.get('permissions'))
        for name in ('publish-jetbrains', 'publish-github-release'):
            with self.subTest(job=name):
                job = self.workflow['jobs'][name]
                self.assertNotIn('if', job)
                self.assertIn('build', job['needs'])
                self.assertNotIn('always()', job.get('if', ''))
        marketplace = self.workflow['jobs']['publish-jetbrains']['steps']
        publish = next(step for step in marketplace if step.get('name') == 'Publish Plugin')
        self.assertIn('-PreleaseArchive="build/downloaded-release/intellij-biome-${{ needs.build.outputs.version }}.zip"', publish['run'])
        github = self.workflow['jobs']['publish-github-release']['steps']
        upload = next(step for step in github if step.get('uses', '').startswith('softprops/action-gh-release@'))
        self.assertEqual('biome.zip', upload['with']['files'])
        self.assertEqual('depot-ubuntu-24.04-arm-16', self.workflow['jobs']['publish-jetbrains']['runs-on'])
        self.assertEqual('depot-ubuntu-24.04', self.workflow['jobs']['publish-github-release']['runs-on'])

    def test_github_asset_rename_preserves_downloaded_bytes(self):
        steps = self.workflow['jobs']['publish-github-release']['steps']
        rename = next((step for step in steps if step.get('name') == 'Rename artifact'), None)
        self.assertIsNotNone(rename, 'GitHub releases must keep the biome.zip asset name')
        for version in ('1.10.1', '1.10.1-nightly.abcdef0'):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as temp:
                archive = Path(temp) / f'build/downloaded-release/intellij-biome-{version}.zip'
                archive.parent.mkdir(parents=True)
                with zipfile.ZipFile(archive, 'w') as package:
                    package.writestr('plugin/lib/test.txt', version)
                downloaded_bytes = archive.read_bytes()
                command = rename['run'].replace('${{ needs.build.outputs.version }}', version)
                result = subprocess.run(['bash', '-e', '-c', command], cwd=temp, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(downloaded_bytes, (Path(temp) / 'biome.zip').read_bytes())
                result = subprocess.run(['bash', '-e', '-c', command], cwd=temp, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode, 'A missing download must stop asset preparation')

    def test_marketplace_command_uses_the_build_version_for_its_download(self):
        job = self.workflow['jobs']['publish-jetbrains']
        publish = next(step for step in job['steps'] if step.get('name') == 'Publish Plugin')
        version = '1.10.1-nightly.abcdef0'
        command = publish['run'].replace('${{ needs.build.outputs.version }}', version)
        with tempfile.TemporaryDirectory() as temp:
            runner = Path(temp) / 'gradlew'
            runner.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > invocation.txt\n')
            runner.chmod(0o755)
            result = subprocess.run(['bash', '-e', '-c', command], cwd=temp, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            arguments = (Path(temp) / 'invocation.txt').read_text().splitlines()
            self.assertEqual('publishPlugin', arguments[0])
            self.assertIn('-PreleaseArchive=build/downloaded-release/intellij-biome-' + version + '.zip', arguments)
            self.assertIn('-PpluginVersion=' + version, arguments)

if __name__ == '__main__':
    unittest.main()
