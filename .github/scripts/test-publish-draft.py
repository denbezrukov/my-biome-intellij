#!/usr/bin/env python3
"""Creation-only draft publication against mocked GitHub REST semantics; no network."""
import importlib.util
import io
import json
import urllib.error
from unittest import mock
from pathlib import Path
import tempfile
import unittest

import yaml

SCRIPT = Path(__file__).with_name('publish-draft.py')
ARTIFACT = Path(__file__).with_name('release-artifact.py')
WORKFLOW = Path(__file__).parents[1] / 'workflows/publish.yaml'
SHA = 'b' * 40
TAG = 'v1.10.1'
ROOT = '/repos/owner/repo'


def load(path):
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PublishDraftTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.exists(), 'Creation-only publisher is missing; release action can update published releases')
        self.publisher = load(SCRIPT)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.archive = Path(self.temporary.name) / 'intellij-biome-1.10.1.zip'
        # Use actual nested ZIP descriptor creation from the artifact regression suite.
        artifact_tests = load(Path(__file__).with_name('test-release-artifact.py'))
        artifact_tests.write_zip(self.archive, artifact_tests.descriptor())
        import hashlib
        self.digest = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.calls = []
        self.replies = {
            ('GET', ROOT + '/releases?per_page=100&page=1'): [],
            ('GET', ROOT + '/git/ref/tags/' + TAG): [404] + [{'object': {'type': 'commit', 'sha': SHA}}] * 3,
            ('POST', ROOT + '/git/refs'): {'ref': 'refs/tags/' + TAG, 'object': {'type': 'commit', 'sha': SHA}},
            ('POST', ROOT + '/releases'): {'id': 7, 'draft': True, 'tag_name': TAG},
            ('GET', ROOT + '/releases/7'): {'id': 7, 'draft': True, 'tag_name': TAG},
            ('POST', ROOT + '/releases/7/assets?name=' + self.archive.name): {'id': 8, 'name': self.archive.name, 'size': self.archive.stat().st_size, 'state': 'uploaded', 'digest': 'sha256:' + self.digest},
        }

    def request(self, method, path, data=None, **kwargs):
        self.calls.append((method, path, data, kwargs))
        response = self.replies[(method, path)]
        if isinstance(response, list) and path.find('/git/ref/') >= 0:
            response = response.pop(0)
        if isinstance(response, int):
            raise self.publisher.ApiError(response, method, path)
        if callable(response):
            return response()
        return response

    def publish(self):
        return self.publisher.create_draft(self.request, 'owner/repo', SHA, '1.10.1', self.archive, self.digest, False)

    def assert_no_writes(self):
        self.assertFalse(any(method != 'GET' for method, *_ in self.calls), self.calls)

    def test_absent_tag_creates_only_new_draft_and_uploads_exact_validated_zip(self):
        self.assertEqual(7, self.publish()['id'])
        writes = [(method, path, data) for method, path, data, _ in self.calls if method != 'GET']
        self.assertEqual(['POST', 'POST', 'POST'], [call[0] for call in writes])
        self.assertEqual({'ref': 'refs/tags/' + TAG, 'sha': SHA}, writes[0][2])
        self.assertIs(writes[1][2]['draft'], True)
        self.assertEqual(SHA, writes[1][2]['target_commitish'])
        self.assertEqual(self.archive.read_bytes(), writes[2][2])

    def test_existing_published_and_draft_releases_fail_without_any_write(self):
        for draft in (False, True):
            with self.subTest(draft=draft):
                self.calls.clear()
                self.replies[('GET', ROOT + '/releases?per_page=100&page=1')] = [{'id': 2, 'tag_name': TAG, 'draft': draft}]
                with self.assertRaisesRegex(ValueError, 'existing release'):
                    self.publish()
                self.assert_no_writes()

    def test_all_existing_lightweight_and_annotated_tags_fail_even_when_matching(self):
        for kind in ('commit', 'tag'):
            for sha in (SHA, 'a' * 40):
                with self.subTest(kind=kind, sha=sha):
                    self.calls.clear()
                    self.replies[('GET', ROOT + '/git/ref/tags/' + TAG)] = [{'object': {'type': kind, 'sha': sha}}]
                    with self.assertRaisesRegex(ValueError, 'existing tag'):
                        self.publish()
                    self.assert_no_writes()

    def test_release_lookup_pagination_checks_later_pages(self):
        self.replies[('GET', ROOT + '/releases?per_page=100&page=1')] = [{'tag_name': 'other'}] * 100
        self.replies[('GET', ROOT + '/releases?per_page=100&page=2')] = [{'tag_name': TAG, 'draft': False}]
        with self.assertRaisesRegex(ValueError, 'existing release'):
            self.publish()
        self.assert_no_writes()

    def test_non_404_tag_lookup_failure_is_fail_closed(self):
        for status in (401, 403, 429, 500):
            with self.subTest(status=status):
                self.calls.clear()
                self.replies[('GET', ROOT + '/git/ref/tags/' + TAG)] = [status]
                with self.assertRaises(self.publisher.ApiError):
                    self.publish()
                self.assert_no_writes()

    def test_atomic_tag_collision_blocks_release_creation(self):
        self.replies[('POST', ROOT + '/git/refs')] = 422
        with self.assertRaises(self.publisher.ApiError):
            self.publish()
        self.assertFalse(any('/releases' in path and method != 'GET' for method, path, *_ in self.calls))

    def test_changed_reserved_tag_blocks_release_creation(self):
        self.replies[('GET', ROOT + '/git/ref/tags/' + TAG)] = [404, {'object': {'type': 'commit', 'sha': 'a' * 40}}]
        with self.assertRaisesRegex(ValueError, 'workflow commit'):
            self.publish()
        self.assertFalse(any('/releases' in path and method != 'GET' for method, path, *_ in self.calls))

    def test_release_creation_collision_never_updates_or_uploads_existing_release(self):
        self.replies[('POST', ROOT + '/releases')] = 422
        with self.assertRaises(self.publisher.ApiError):
            self.publish()
        self.assertFalse(any('assets' in path or method in ('PATCH', 'DELETE') for method, path, *_ in self.calls))

    def test_created_or_rechecked_release_must_still_be_our_draft(self):
        for path in ('POST', 'GET'):
            with self.subTest(path=path):
                self.setUp()
                key = (path, ROOT + ('/releases' if path == 'POST' else '/releases/7'))
                self.replies[key] = {'id': 7, 'draft': False, 'tag_name': TAG}
                with self.assertRaisesRegex(ValueError, 'draft'):
                    self.publish()
                self.assertFalse(any('assets' in route for _, route, *_ in self.calls))

    def test_asset_api_failure_or_incorrect_digest_cannot_report_success(self):
        key = ('POST', ROOT + '/releases/7/assets?name=' + self.archive.name)
        self.replies[key] = 422
        with self.assertRaises(self.publisher.ApiError):
            self.publish()
        self.setUp()
        self.replies[key] = {'name': self.archive.name, 'size': self.archive.stat().st_size, 'state': 'uploaded', 'digest': 'sha256:' + '0' * 64}
        with self.assertRaisesRegex(ValueError, 'asset'):
            self.publish()

    def test_invalid_archive_sha_and_commit_fail_before_network(self):
        for sha, digest in [('unsafe', self.digest), (SHA, '0' * 64)]:
            with self.subTest(sha=sha, digest=digest):
                with self.assertRaises(ValueError):
                    self.publisher.create_draft(self.request, 'owner/repo', sha, '1.10.1', self.archive, digest, False)
                self.assertEqual([], self.calls)

    def test_actual_client_uses_creation_endpoints_payloads_and_upload_host(self):
        class Response(io.BytesIO):
            status = 201
        client = self.publisher.GitHubClient('test-token')
        with mock.patch.object(self.publisher.urllib.request, 'urlopen', return_value=Response(b'{"id":7}')) as opened:
            self.assertEqual({'id': 7}, client.request('POST', ROOT + '/releases', {'draft': True, 'target_commitish': SHA}))
        request = opened.call_args.args[0]
        self.assertEqual('https://api.github.com' + ROOT + '/releases', request.full_url)
        self.assertEqual('POST', request.method)
        self.assertIs(json.loads(request.data)['draft'], True)
        self.assertEqual(SHA, json.loads(request.data)['target_commitish'])
        with mock.patch.object(self.publisher.urllib.request, 'urlopen', return_value=Response(b'{"id":8}')) as opened:
            client.request('POST', ROOT + '/releases/7/assets?name=' + self.archive.name, self.archive.read_bytes(), upload=True)
        request = opened.call_args.args[0]
        self.assertTrue(request.full_url.startswith('https://uploads.github.com/'))
        self.assertEqual('application/zip', request.get_header('Content-type'))
        self.assertEqual(self.archive.read_bytes(), request.data)

    def test_actual_client_http_errors_do_not_retry_writes_or_reuse_existing_release(self):
        client = self.publisher.GitHubClient('test-token')
        error = urllib.error.HTTPError('https://api.github.com' + ROOT + '/releases', 422, 'collision', {}, None)
        with mock.patch.object(self.publisher.urllib.request, 'urlopen', side_effect=error) as opened:
            with self.assertRaises(self.publisher.ApiError) as caught:
                client.request('POST', ROOT + '/releases', {'draft': True})
            self.assertEqual(422, caught.exception.status)
            self.assertEqual(1, opened.call_count)

    def test_release_lookup_api_failure_and_malformed_data_cannot_write(self):
        key = ('GET', ROOT + '/releases?per_page=100&page=1')
        for response in (403, {'message': 'unexpected'}, [{}]):
            with self.subTest(response=response):
                self.calls.clear()
                self.replies[key] = response
                with self.assertRaises((ValueError, self.publisher.ApiError)):
                    self.publish()
                self.assert_no_writes()

    def test_workflow_runs_creation_only_client_with_same_archive_output_and_serializes_versions(self):
        workflow = yaml.safe_load(WORKFLOW.read_text())
        job = workflow['jobs']['publish-github-release']
        step = next(step for step in job['steps'] if step.get('id') == 'create-draft')
        self.assertEqual('${{ steps.validate.outputs.archive }}', step['env']['RELEASE_ARCHIVE'])
        self.assertIn('publish-draft.py', step['run'])
        self.assertIs(job['concurrency']['cancel-in-progress'], False)
        self.assertFalse(any('softprops/action-gh-release' in step.get('uses', '') for step in job['steps']))


if __name__ == '__main__':
    unittest.main()
