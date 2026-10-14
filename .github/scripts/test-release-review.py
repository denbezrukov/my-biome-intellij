#!/usr/bin/env python3
"""Check publication opt-in and the downloaded artifact consumed by each job."""
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

    def test_publication_requires_explicit_opt_in_and_successful_build(self):
        inputs = self.workflow.get('on', self.workflow.get(True))['workflow_dispatch']['inputs']
        self.assertIs(inputs.get('publish', {}).get('default'), False)
        self.assertEqual({'contents': 'read'}, self.workflow.get('permissions'))
        for name in ('publish-jetbrains', 'publish-github-release'):
            with self.subTest(job=name):
                job = self.workflow['jobs'][name]
                self.assertEqual('inputs.publish', job.get('if'))
                self.assertIn('build', job['needs'])
                self.assertNotIn('always()', job.get('if', ''))
        marketplace = self.workflow['jobs']['publish-jetbrains']['steps']
        publish = next(step for step in marketplace if step.get('name') == 'Publish Plugin')
        self.assertIn('-PreleaseArchive="build/downloaded-release/intellij-biome-${{ needs.build.outputs.version }}.zip"', publish['run'])
        github = self.workflow['jobs']['publish-github-release']['steps']
        upload = next(step for step in github if step.get('uses', '').startswith('softprops/action-gh-release@'))
        self.assertEqual('build/downloaded-release/intellij-biome-${{ needs.build.outputs.version }}.zip', upload['with']['files'])

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

    def test_actual_inspection_commands_accept_only_the_downloaded_zip(self):
        for name in ('publish-jetbrains', 'publish-github-release'):
            with self.subTest(job=name), tempfile.TemporaryDirectory() as temp:
                steps = self.workflow['jobs'][name]['steps']
                download = next(step for step in steps if step.get('uses', '').startswith('actions/download-artifact@'))
                inspection = next((step for step in steps if step.get('id') == 'inspect-archive'), None)
                self.assertIsNotNone(inspection, 'Publication must inspect its downloaded archive')
                destination = Path(temp) / download['with']['path']
                destination.mkdir(parents=True)
                archive = destination / 'intellij-biome-1.10.1.zip'
                with zipfile.ZipFile(archive, 'w') as package:
                    package.writestr('plugin/lib/test.txt', 'archive control')
                command = inspection['run'].replace('${{ needs.build.outputs.version }}', '1.10.1')
                result = subprocess.run(['bash', '-e', '-c', command], cwd=temp, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                archive.unlink()
                result = subprocess.run(['bash', '-e', '-c', command], cwd=temp, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode, 'Missing download must stop publication')


if __name__ == '__main__':
    unittest.main()
