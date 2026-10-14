#!/usr/bin/env python3
"""Exercise ZIP descriptors and release artifact transport without publishing."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

import yaml


SCRIPT = Path(__file__).with_name('release-artifact.py')


def descriptor(version='1.10.1', plugin_id='com.github.biomejs.intellijbiome', vendor='biomejs', since='253'):
    return f'<idea-plugin><id>{plugin_id}</id><name>Biome</name><vendor>{vendor}</vendor><version>{version}</version><idea-version since-build="{since}"/></idea-plugin>'


def write_zip(path, xml):
    jar = io.BytesIO()
    with zipfile.ZipFile(jar, 'w') as archive:
        archive.writestr('META-INF/plugin.xml', xml)
    with zipfile.ZipFile(path, 'w') as archive:
        archive.writestr('intellij-biome/lib/intellij-biome.jar', jar.getvalue())


class ReleaseArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / 'intellij-biome-1.10.1.zip'
        write_zip(self.archive, descriptor())
        self.sha = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.record = self.root / 'release-record.json'

    def run_script(self, *arguments, expected_error=None):
        environment = {key: value for key, value in os.environ.items() if key != 'GITHUB_OUTPUT'}
        result = subprocess.run([sys.executable, str(SCRIPT), *map(str, arguments)], env=environment, capture_output=True, text=True, timeout=10)
        if expected_error is None:
            self.assertEqual(0, result.returncode, result.stderr)
        else:
            self.assertNotEqual(0, result.returncode)
            self.assertIn(expected_error, result.stderr)
        return result

    def validate(self, expected_error=None, **overrides):
        values = {'archive': self.archive, 'version': '1.10.1', 'sha256': self.sha, 'record': self.record}
        values.update(overrides)
        arguments = ['validate']
        for key, value in values.items():
            arguments += ['--' + key, value]
        return self.run_script(*arguments, expected_error=expected_error)

    def test_stable_dry_run_records_exact_upload_archive_and_descriptor(self):
        self.validate()
        record = json.loads(self.record.read_text())
        self.assertEqual(str(self.archive), record['upload_input'])
        self.assertEqual(self.sha, record['sha256'])
        self.assertEqual('1.10.1', record['version'])
        self.assertEqual('com.github.biomejs.intellijbiome', record['plugin_id'])
        self.assertEqual('biomejs', record['vendor'])
        self.assertEqual('253', record['since_build'])


    def test_nightly_archive_uses_the_same_descriptor_contract(self):
        version = '1.10.1-nightly.abcdef0'
        archive = self.root / f'intellij-biome-{version}.zip'
        write_zip(archive, descriptor(version=version))
        self.validate(archive=archive, version=version, sha256=hashlib.sha256(archive.read_bytes()).hexdigest())
        self.assertEqual(version, json.loads(self.record.read_text())['version'])

    def test_missing_archive_is_rejected(self):
        self.archive.unlink()
        self.validate(expected_error='archive')
        self.assertFalse(self.record.exists())

    def test_missing_or_incorrect_sha_is_rejected(self):
        for digest in ('', '0' * 64, 'not-a-digest'):
            with self.subTest(digest=digest):
                self.validate(sha256=digest, expected_error='SHA256')
                self.assertFalse(self.record.exists())

    def test_corrupted_zip_with_matching_digest_is_rejected(self):
        self.archive.write_bytes(b'not a zip')
        self.validate(sha256=hashlib.sha256(self.archive.read_bytes()).hexdigest(), expected_error='ZIP')

    def test_wrong_filename_and_descriptor_version_are_rejected(self):
        self.validate(version='1.10.2', expected_error='filename')
        write_zip(self.archive, descriptor(version='1.10.2'))
        self.validate(sha256=hashlib.sha256(self.archive.read_bytes()).hexdigest(), expected_error='version')

    def test_descriptor_identity_and_minimum_are_preserved(self):
        for attribute, value in [('plugin_id', 'other.plugin'), ('vendor', 'fork'), ('since', '252')]:
            with self.subTest(attribute=attribute):
                write_zip(self.archive, descriptor(**{attribute: value}))
                self.validate(sha256=hashlib.sha256(self.archive.read_bytes()).hexdigest(), expected_error='descriptor')

    def test_missing_or_duplicate_descriptor_is_rejected(self):
        with zipfile.ZipFile(self.archive, 'w') as archive:
            archive.writestr('intellij-biome/lib/empty.jar', b'not a jar')
        self.validate(sha256=hashlib.sha256(self.archive.read_bytes()).hexdigest(), expected_error='ZIP')
        write_zip(self.archive, descriptor())
        with zipfile.ZipFile(self.archive, 'a') as archive:
            archive.writestr('intellij-biome/META-INF/plugin.xml', descriptor())
        self.validate(sha256=hashlib.sha256(self.archive.read_bytes()).hexdigest(), expected_error='exactly one')


class ArtifactWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[2]
        self.workflow = yaml.safe_load((self.root / '.github/workflows/publish.yaml').read_text())

    def test_producer_command_records_the_built_archive_checksum(self):
        build = yaml.safe_load((self.root / '.github/workflows/_build.yaml').read_text())
        step = next(step for step in build['jobs']['build-plugin']['steps'] if step.get('id') == 'archive')
        self.assertEqual('bash', step.get('shell'), 'The checksum pipeline must use the Actions pipefail shell')
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / 'intellij-biome-1.10.1.zip'
            write_zip(archive, descriptor())
            output = Path(temp) / 'outputs'
            env = os.environ | {'PLUGIN_ARCHIVE': str(archive), 'GITHUB_OUTPUT': str(output)}
            result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', step['run']], env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('sha256=' + hashlib.sha256(archive.read_bytes()).hexdigest(), output.read_text().strip())
            archive.unlink()
            output.unlink()
            result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', step['run']], env=env, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(output.exists())

    def test_actual_download_validation_commands_reject_different_bytes(self):
        transport_path = self.root / '.github/workflows/release-artifact-validation.yaml'
        self.assertTrue(transport_path.exists(), 'A separate nonpublishing workflow must exercise artifact transport')
        transport = yaml.safe_load(transport_path.read_text())
        jobs = {name: self.workflow['jobs'][name] for name in ('validate-download', 'publish-jetbrains', 'publish-github-release')}
        jobs['transport'] = transport['jobs']['validate-download']
        for name, job in jobs.items():
            with self.subTest(job=name), tempfile.TemporaryDirectory() as temp:
                steps = job['steps']
                download = next(step for step in steps if step.get('uses', '').startswith('actions/download-artifact@'))
                validation = next(step for step in steps if step.get('id') == 'validate-archive')
                destination = Path(temp) / download['with']['path']
                destination.mkdir(parents=True)
                archive = destination / 'intellij-biome-1.10.1.zip'
                write_zip(archive, descriptor())
                scripts = Path(temp) / '.github/scripts'
                scripts.mkdir(parents=True)
                (scripts / SCRIPT.name).symlink_to(SCRIPT.resolve())
                record = Path(temp) / 'build/release-record.json'
                env = os.environ | {'RELEASE_VERSION': '1.10.1', 'RELEASE_SHA256': hashlib.sha256(archive.read_bytes()).hexdigest()}
                env.pop('GITHUB_OUTPUT', None)
                result = subprocess.run(['bash', '-e', '-c', validation['run']], cwd=temp, env=env, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(str(destination.relative_to(temp) / archive.name), json.loads(record.read_text())['upload_input'])
                record.unlink()
                env['RELEASE_SHA256'] = '0' * 64
                result = subprocess.run(['bash', '-e', '-c', validation['run']], cwd=temp, env=env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('SHA256', result.stderr)
                self.assertFalse(record.exists())

    def test_transport_workflow_uses_production_build_without_publication(self):
        path = self.root / '.github/workflows/release-artifact-validation.yaml'
        self.assertTrue(path.exists(), 'Artifact transport needs a publication-free workflow')
        workflow = yaml.safe_load(path.read_text())
        events = workflow.get('on', workflow.get(True))
        inputs = events['workflow_dispatch']['inputs']
        self.assertEqual('depot-ubuntu-24.04-arm-16', inputs['runner']['default'])
        self.assertIn('ubuntu-24.04', inputs['runner']['options'])
        self.assertEqual({'contents': 'read'}, workflow['permissions'])
        self.assertEqual({'build', 'validate-download'}, set(workflow['jobs']))
        build = workflow['jobs']['build']
        self.assertEqual('./.github/workflows/_build.yaml', build['uses'])
        self.assertIs(build['with']['artifact'], True)
        self.assertEqual('${{ inputs.runner }}', build['with']['runner'])
        validation = workflow['jobs']['validate-download']
        self.assertEqual(['build'], validation['needs'])
        download = next(step for step in validation['steps'] if step.get('uses', '').startswith('actions/download-artifact@'))
        self.assertEqual('Biome-${{ needs.build.outputs.version }}.zip', download['with']['name'])
        step = next(step for step in validation['steps'] if step.get('id') == 'validate-archive')
        self.assertEqual('${{ needs.build.outputs.version }}', step['env']['RELEASE_VERSION'])
        self.assertEqual('${{ needs.build.outputs.sha256 }}', step['env']['RELEASE_SHA256'])
        self.assertNotIn('secrets.', path.read_text())
        self.assertNotIn('publishPlugin', path.read_text())
        self.assertNotIn('action-gh-release', path.read_text())

    def test_reusable_build_keeps_depot_default_and_fails_for_missing_upload(self):
        build = yaml.safe_load((self.root / '.github/workflows/_build.yaml').read_text())
        events = build.get('on', build.get(True))
        runner = events['workflow_call']['inputs'].get('runner', {})
        self.assertEqual('depot-ubuntu-24.04-arm-16', runner.get('default'))
        producer = build['jobs']['build-plugin']
        self.assertEqual('${{ inputs.runner }}', producer['runs-on'])
        upload = next(step for step in producer['steps'] if step.get('uses', '').startswith('actions/upload-artifact@'))
        self.assertEqual('error', upload['with'].get('if-no-files-found'))
        self.assertEqual('${{ steps.archive.outputs.sha256 }}', producer['outputs']['sha256'])
        self.assertEqual('${{ jobs.build-plugin.outputs.sha256 }}', events['workflow_call']['outputs']['sha256']['value'])

    def test_publication_waits_for_successful_validation(self):
        self.assertEqual(['build'], self.workflow['jobs']['validate-download']['needs'])
        self.assertNotIn('if', self.workflow['jobs']['validate-download'])
        for name in ('publish-jetbrains', 'publish-github-release'):
            job = self.workflow['jobs'][name]
            self.assertIn('validate-download', job['needs'])
            self.assertNotIn('if', job)


if __name__ == '__main__':
    unittest.main()
