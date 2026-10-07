#!/usr/bin/env python3
"""Create a new draft from verified bytes; never update/reuse a release or tag."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


class ApiError(RuntimeError):
    def __init__(self, status, method, path):
        self.status = status
        super().__init__(f'GitHub API {method} {path} failed with HTTP {status}')


class GitHubClient:
    def __init__(self, token):
        self.token = token

    def request(self, method, path, data=None, upload=False):
        base = 'https://uploads.github.com' if upload else 'https://api.github.com'
        body = data if upload else (json.dumps(data).encode() if data is not None else None)
        request = urllib.request.Request(base + path, data=body, method=method, headers={
            'Authorization': 'Bearer ' + self.token,
            'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28',
            'Content-Type': 'application/zip' if upload else 'application/json',
            'User-Agent': 'verified-biome-draft-publisher',
        })
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                if response.status != (201 if method == 'POST' else 200):
                    raise ApiError(response.status, method, path)
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise ApiError(error.code, method, path) from None
        # No retries of writes: an ambiguous timeout must be investigated, not replayed.


def require_new_version(request, root, tag):
    # Listing authenticated releases covers drafts as well as published releases.
    for page in range(1, 101):
        releases = request('GET', f'{root}/releases?per_page=100&page={page}')
        if not isinstance(releases, list) or any(not isinstance(release, dict) or not isinstance(release.get('tag_name'), str) for release in releases):
            raise ValueError('Invalid release lookup response')
        if any(release['tag_name'] == tag for release in releases):
            raise ValueError('Refusing existing release, whether draft or published')
        if len(releases) < 100:
            break
    else:
        raise ValueError('Release lookup pagination exceeded safety limit')
    try:
        request('GET', f'{root}/git/ref/tags/{tag}')
    except ApiError as error:
        if error.status != 404:
            raise
    else:
        # Reject lightweight/annotated tags even when matching: no tag reuse or moves.
        raise ValueError('Refusing existing tag')


def require_commit_tag(tag_response, sha):
    target = tag_response.get('object') if isinstance(tag_response, dict) else None
    if not isinstance(target, dict) or target.get('type') != 'commit' or target.get('sha') != sha:
        raise ValueError('Reserved version tag does not identify workflow commit')


def require_draft(release, tag, expected_id=None):
    if not isinstance(release, dict) or release.get('draft') is not True or release.get('tag_name') != tag:
        raise ValueError('New release is not the expected draft')
    release_id = release.get('id')
    if type(release_id) is not int or release_id <= 0 or (expected_id is not None and release_id != expected_id):
        raise ValueError('Invalid new draft release identity')
    return release_id


def create_draft(request, repository, sha, version, archive, sha256, nightly):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Invalid repository')
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Invalid workflow commit SHA')
    spec = importlib.util.spec_from_file_location('release_artifact', Path(__file__).with_name('release-artifact.py'))
    validator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(validator)
    validator.validate_archive(archive, version, sha256)
    payload = archive.read_bytes()
    if hashlib.sha256(payload).hexdigest() != sha256:
        raise ValueError('Archive changed after validation')
    root = '/repos/' + repository
    tag = 'v' + version
    require_new_version(request, root, tag)
    # Atomic create-ref is the reservation: a raced existing tag returns 422.
    # Never delete/move the tag on error; reruns then fail safely until inspected.
    reserved = request('POST', root + '/git/refs', {'ref': 'refs/tags/' + tag, 'sha': sha})
    require_commit_tag(reserved, sha)
    require_commit_tag(request('GET', f'{root}/git/ref/tags/{tag}'), sha)
    # The existing tag already pins sha. Supplying the unused target_commitish
    # can require Workflows write permission, which GITHUB_TOKEN cannot receive.
    release = request('POST', root + '/releases', {
        'tag_name': tag, 'name': tag,
        'draft': True, 'prerelease': nightly, 'generate_release_notes': True,
    })
    release_id = require_draft(release, tag)
    # Never use PATCH or the existing-release updater. A creation conflict fails.
    require_draft(request('GET', f'{root}/releases/{release_id}'), tag, release_id)
    require_commit_tag(request('GET', f'{root}/git/ref/tags/{tag}'), sha)
    asset = request('POST', f'{root}/releases/{release_id}/assets?name={urllib.parse.quote(archive.name, safe="")}', payload, upload=True)
    if not isinstance(asset, dict) or asset.get('name') != archive.name or asset.get('size') != len(payload) or asset.get('state') != 'uploaded' or asset.get('digest') != 'sha256:' + sha256:
        raise ValueError('Uploaded asset response does not match verified ZIP')
    require_draft(request('GET', f'{root}/releases/{release_id}'), tag, release_id)
    require_commit_tag(request('GET', f'{root}/git/ref/tags/{tag}'), sha)
    return release


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--sha', required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--nightly', choices=('true', 'false'), required=True)
    args = parser.parse_args()
    token = os.environ.get('GITHUB_TOKEN')
    if not token:
        parser.error('GITHUB_TOKEN is required for explicitly authorized draft creation')
    try:
        release = create_draft(GitHubClient(token).request, args.repository, args.sha, args.version, args.archive, args.sha256, args.nightly == 'true')
        print(f'Created new draft release id={release["id"]} from verified archive')
    except (ValueError, OSError, ApiError, urllib.error.URLError) as error:
        print(f'Draft creation failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
