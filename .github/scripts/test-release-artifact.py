#!/usr/bin/env python3
"""Exercise real ZIP descriptors and release job authorization without publishing."""
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
WORKFLOW = Path(__file__).parents[1] / 'workflows' / 'publish.yaml'


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

    def test_nightly_version_and_filename_use_same_identifier(self):
        properties = self.root / 'gradle.properties'
        properties.write_text('pluginVersion = 1.10.1\n')
        output = self.run_script('version', '--properties', properties, '--nightly', 'true', '--sha', 'abcdef0123456789abcdef0123456789abcdef01').stdout.strip()
        self.assertEqual('1.10.1-nightly.abcdef0', output)
        nightly = self.root / ('intellij-biome-' + output + '.zip')
        write_zip(nightly, descriptor(version=output))
        self.validate(archive=nightly, version=output, sha256=hashlib.sha256(nightly.read_bytes()).hexdigest())
        self.assertEqual(str(nightly), json.loads(self.record.read_text())['upload_input'])

    def test_stable_version_does_not_modify_properties(self):
        properties = self.root / 'gradle.properties'
        original = 'pluginVersion=1.10.1\n'
        properties.write_text(original)
        output = self.run_script('version', '--properties', properties, '--nightly', 'false', '--sha', 'a' * 40).stdout.strip()
        self.assertEqual('1.10.1', output)
        self.assertEqual(original, properties.read_text())

    def test_workflow_validation_command_selects_the_actual_downloaded_zip(self):
        workflow = yaml.safe_load(WORKFLOW.read_text())
        steps = workflow['jobs']['validate-release']['steps']
        validation = next(step for step in steps if step.get('id') == 'validate')
        download = next(step for step in steps if step.get('uses', '').startswith('actions/download-artifact@'))
        downloaded = self.root / download['with']['path']
        downloaded.mkdir(parents=True)
        selected = downloaded / self.archive.name
        selected.write_bytes(self.archive.read_bytes())
        scripts = self.root / '.github' / 'scripts'
        scripts.mkdir(parents=True)
        (scripts / SCRIPT.name).symlink_to(SCRIPT.resolve())
        output = self.root / 'outputs'
        environment = os.environ | {'RELEASE_VERSION': '1.10.1', 'RELEASE_SHA256': self.sha, 'GITHUB_OUTPUT': str(output)}
        result = subprocess.run(['bash', '-e', '-c', validation['run']], cwd=self.root, env=environment, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        upload_input = dict(line.split('=', 1) for line in output.read_text().splitlines())['archive']
        self.assertEqual(selected.read_bytes(), (self.root / upload_input).read_bytes())
        self.assertEqual(str(downloaded.relative_to(self.root) / self.archive.name), json.loads((self.root / 'build/release-record.json').read_text())['upload_input'])
        # Removing the gate digest must stop the very same workflow command.
        environment['RELEASE_SHA256'] = ''
        output.unlink()
        result = subprocess.run(['bash', '-e', '-c', validation['run']], cwd=self.root, env=environment, capture_output=True, text=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.exists())

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

    def test_unsafe_version_and_nightly_sha_are_rejected(self):
        properties = self.root / 'gradle.properties'
        for version in ('../../escape', '1.10.1\npluginVersion=2.0.0', '1.2.3; echo unsafe'):
            with self.subTest(version=version):
                properties.write_text('pluginVersion=' + version)
                self.run_script('version', '--properties', properties, '--nightly', 'false', '--sha', 'a' * 40, expected_error='version')
        properties.write_text('pluginVersion=1.10.1')
        self.run_script('version', '--properties', properties, '--nightly', 'true', '--sha', 'unsafe', expected_error='SHA')


class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.workflow = yaml.safe_load(WORKFLOW.read_text())

    def runnable(self, name, publish=False, results=None):
        """Evaluate this workflow's documented needs/if contract with job verdicts."""
        job = self.workflow['jobs'][name]
        results = results or {}
        needs = job.get('needs', [])
        if isinstance(needs, str):
            needs = [needs]
        # Actions applies success() implicitly when the condition has no status function.
        if any(results.get(need) != 'success' for need in needs):
            return False
        expression = job.get('if', 'true').removeprefix('${{').removesuffix('}}').strip()
        if expression == 'true':
            return True
        if expression == 'inputs.publish':
            return publish
        self.fail('Unsupported release authorization condition: ' + expression)

    def publication_job(self):
        candidates = [name for name, job in self.workflow['jobs'].items() if job.get('permissions', {}).get('contents') == 'write']
        self.assertEqual(1, len(candidates), 'Exactly one explicitly authorized GitHub publisher is expected')
        return candidates[0]

    def test_default_dry_run_cannot_publish(self):
        defaults = self.workflow.get('on', self.workflow.get(True))['workflow_dispatch']['inputs']
        successes = {name: 'success' for name in self.workflow['jobs']}
        self.assertFalse(self.runnable(self.publication_job(), results=successes))
        self.assertIs(defaults.get('publish', {}).get('default', True), False)
        self.assertFalse(any('PUBLISH_TOKEN' in step.get('env', {}) for job in self.workflow['jobs'].values() for step in job.get('steps', [])))

    def test_missing_failed_skipped_or_cancelled_gate_blocks_download_and_publication(self):
        gate = self.workflow['jobs']['compatibility']
        self.assertEqual('./.github/workflows/_compatibility.yaml', gate['uses'])
        self.assertIs(gate['with']['artifact'], True)
        for verdict in (None, 'failure', 'skipped', 'cancelled'):
            with self.subTest(verdict=verdict):
                results = {'compatibility': verdict, 'prepare-version': 'success', 'validate-release': 'success'}
                self.assertFalse(self.runnable('validate-release', publish=True, results=results))
                # A validation job cannot succeed if its required gate didn't run successfully.
                results['validate-release'] = 'skipped'
                self.assertFalse(self.runnable(self.publication_job(), publish=True, results=results))

    def test_validation_failure_blocks_even_explicit_publication(self):
        for verdict in (None, 'failure', 'skipped', 'cancelled'):
            with self.subTest(verdict=verdict):
                self.assertFalse(self.runnable(self.publication_job(), publish=True, results={'compatibility': 'success', 'validate-release': verdict}))

    def test_explicit_publication_consumes_validated_archive_without_build(self):
        publisher = self.workflow['jobs'][self.publication_job()]
        self.assertTrue(self.runnable(self.publication_job(), publish=True, results={'compatibility': 'success', 'validate-release': 'success'}))
        uploads = [step for step in publisher['steps'] if step.get('uses', '').startswith('softprops/action-gh-release@')]
        self.assertEqual(1, len(uploads))
        self.assertEqual('${{ steps.validate.outputs.archive }}', uploads[0]['with']['files'])
        self.assertIs(uploads[0]['with']['fail_on_unmatched_files'], True)
        self.assertIs(uploads[0]['with']['draft'], True)
        self.assertFalse(any('gradlew' in step.get('run', '') for step in publisher['steps']))


if __name__ == '__main__':
    unittest.main()
