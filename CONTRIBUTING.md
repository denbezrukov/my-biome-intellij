# Contributing

Thank you for your interest in contributing to this project!

Please review the following guidelines before making your contribution.

> [!IMPORTANT]
> If you plan on making a significant contribution, we recommend that you first create a discussion describing your
> proposed contribution to the project. This allows the project maintainers to provide early feedback that can help guide
> your contribution.

## Project setup

Ensure that you have the following tools installed on your machine:

- Java development kit (JDK) 21
- Node.js 24, Corepack, and pnpm 10.5.2 (for the pinned Biome test packages)
- WebStorm 2025.3 or a compatible IDE with the required Ultimate and JavaScript modules
- git-cliff (_maintainers only_)

1. Fork the repository and clone it to your local machine.
   ```shell
   gh repo fork denbezrukov/my-biome-intellij --clone
   ```

2. Install Corepack and activate the test fixture package manager. Node.js 24 does not bundle Corepack.
   ```shell
   npm install --global corepack@0.34.0
   corepack enable
   corepack prepare pnpm@10.5.2 --activate
   ```

## Development

The plugin can be started in WebStorm by running the `runIde` Gradle task. This will start a new instance of WebStorm with the
plugin loaded.

```shell
./gradlew runIde
```

### Running tests

Run the required plugin regression suites from the repository root on Linux:

```shell
python3 .github/scripts/run-required-tests.py --task test
python3 .github/scripts/check-required-tests.py build/test-results/test
```

These suites exercise settings persistence, both Biome CLI versions, and real plugin LSP sessions. The v1 launch tests
require Linux. The fixture installer uses the committed pnpm lockfiles with `--frozen-lockfile`. `cleanTest` removes old
results; `--no-build-cache` prevents Gradle from restoring cached test results. The report guard requires all 173 named
tests across 23 classes to execute without failures or skips. `run-required-tests.py` selects the classes from that
same inventory, so adding a required class cannot leave it unselected in CI. The CI gate runs the guard and uploads
reports even when Gradle fails.

The launcher inventory includes `testNodeReaderFinishesAfterProxyExitWithInheritedPipes` and
`testNodeReaderFinishesAfterProxyDestroyWithInheritedPipes`. Both require the native Node handler to finish while
an owned child retains the inherited pipes, preserve that child, and retain exact CRLF output.

To check the report guard itself, run `python3 .github/scripts/test-check-required-tests.py`.

Diagnostic coverage includes absent, string, integer and zero codes; multiline and HTML-sensitive
messages/tooltips; real Biome highlighting and an applied parameter quick fix.
`BiomeDiagnosticsTest.testRuntimeMessageRepresentationsKeepTheirText` exercises the running SDK's
string representation in supported SDK fixtures. Direct native 263 probes separately exercised plaintext/markdown
`MarkupContent`; its full fixture smoke remains unvalidated and outside the supported range. Markup is preserved as
readable, escaped text; rich Markdown rendering is not promised. Run this suite on the minimum and current supported
SDKs when changing diagnostic APIs.

The supported IDE workflow compiles production and test sources against WebStorm **2025.3 / WS-253.28294.332**
with JDK 21 and Kotlin 2.2.20. It executes the complete named inventory on both that runtime and pinned current stable
**2026.2.3 / WS-262.10968.77**. The stable pin comes from JetBrains' official release metadata dated September 17,
2026. The custom runtime uses its matching IDE libraries, bundled JBR, and explicitly pinned platform test framework
262.10968.67; it does not change `platformVersion`. Production compilation remains 253.

```shell
python3 .github/scripts/run-required-tests.py --task testCurrentIde
python3 .github/scripts/check-required-tests.py build/test-results/testCurrentIde
```

`BiomeIdeRuntimeTest.testPinnedIdeRuntimeIsActuallyLoaded` requires the exact runtime build and prints its JBR
version into JUnit XML. `testMinimumCompiledPluginIsLoadedInRuntime` checks that the plugin loads with its minimum
descriptor and declared compilation SDK. These controls accompany the existing real startup, diagnostics, action,
save-to-disk, undo/redo, stale/cancellation, nested-root, and dependency-upgrade regressions; a zero-test or skipped
run cannot satisfy the gate. The save-order control recognizes the actual platform formatter API: 253 completes its legacy formatter before Biome; 262 runs the document-updating formatter after Biome according to the declared extension order. Both paths assert final disk contents, feature ordering, undo/redo groups, and no repeated Biome calls. Linux fixtures do not claim interactive desktop or Windows/WSL acceptance.

The descriptor supports builds **253 through 262.***. The investigated **263.6259.34 / WebStorm 2026.3 EAP**
is outside the admitted range: binary verification and direct diagnostic probes succeeded on an earlier archive,
but its fixture smoke could not execute because the EAP test launcher failed before startup. This does not establish
supported editor behavior. Extending the range requires a working runtime gate and verification of the newly packaged
archive; EAP is not silently treated as a passing lane. Future targets are never selected with `recommended()` or `latest`.
The current fixture includes the SDK's split JSON, Node.js, test-runner, structure-view, JCEF, SSH, bookmarks, images,
and library-provider plugins, and uses its Kotlin stdlib without changing production dependencies. Runtime controls reject
missing or disabled required plugins.

Disk controls await the native file-specific VFS completion barrier on 262, where a document can be marked saved before
its physical write finishes. The 253 writer is synchronous. Separator document-group controls scope transparent caret
movement to the fixture and deliberately move the caret in the CRLF redo case; physical bytes and external-write guards
remain required.

Build and verify the same minimum-compiled archive:

```shell
version=$(sed -n 's/^pluginVersion=//p' gradle.properties)
./gradlew buildPlugin verifyPlugin
python3 .github/scripts/check-compatibility.py --archive "build/distributions/intellij-biome-$version.zip" --version "$version" --reports build/reports/pluginVerifier --target WS-253.28294.332 --target WS-262.10968.77 --output build/compatibility-provenance.json
python3 .github/scripts/test-check-compatibility.py
```

Use a fresh verifier reports directory; CI removes it before verification. Both Gradle failure levels and the verdict
guard reject compatibility problems, including the standalone verifier's zero-exit incompatibility result. The guard
checks ZIP structure, plugin identity/version/minimum, exact target verdicts, and records the archive SHA-256.
`requireMinimumCompileSdk` rejects newer production SDK overrides in compatibility/release tasks.

To reuse already verified SDKs, supply `-PminimumIdePath=/path/to/253`, `-PcurrentIdePath=/path/to/262`. Local paths are overrides for runtime/verifier targets, never the production SDK.
Cloud runs can set `BIOME_GRADLE_RUNNER` to their process-slot helper before invoking `run-required-tests.py`.

`.github/workflows/_compatibility.yaml` is reusable by integration and release workflows. Its `artifact: false`
input runs every gate and uploads evidence. Passing `true` additionally uploads the exact verified
`intellij-biome-<version>.zip` after all gates pass, including in a caller's nonpublishing dry run. An optional `version`
override accepts the version prepared by the caller. Outputs are `version`, `artifact-name`, and `sha256`; this workflow
never publishes to a registry or creates a release.

The release pipeline has a separate Python regression inventory (it does not change the JVM inventory above):

```shell
python3 -m pip install PyYAML==6.0.3
python3 .github/scripts/test-release-artifact.py
python3 .github/scripts/test-publish-draft.py
```

Its 15 tests cover stable/nightly version selection, the actual workflow validation command, nested JAR descriptor
identity and minimum build, filename/version/SHA256 agreement, damaged or missing archives and descriptors, unsafe
versions, dry-run authorization, and missing/failed/skipped/cancelled gate outcomes. The `Publish` workflow runs this
inventory before selecting a version or invoking the compatibility gate. The separate 15-test draft-publication
inventory mocks the GitHub API, including existing published/draft releases, lightweight/annotated tags, pagination,
API failures, tag/release collisions, and exact ZIP upload bytes. These tests make no network requests or release writes.

The legacy Remote Robot UI tests are separate from this required gate. To run those alongside the full test suite:

```shell
./gradlew runIdeForUiTests &
./gradlew test
```

## Making changes

1. **Create a branch.** Before making any changes, create a branch to work on.
   ```shell
   git checkout -b my-branch-name
   ```
2. **Make your changes.** Make your changes to the codebase and commit them. The format of your commit messages is not
   important at this stage because they will be squashed later, but please ensure that your commit messages are
   descriptive.

3. **Create a pull request.** Once you are done making your changes, push your branch to your fork and create a pull
   request. Please ensure that the title of your pull request follows the conventional commits specification.

## Maintainers

The `Publish` workflow defaults to a nonpublishing dry run. It computes the stable or nightly version without changing
`gradle.properties`, then calls the shared compatibility workflow to run the required tests and Plugin Verifier. Only
that successful gate can upload the exact built `intellij-biome-VERSION.zip` artifact. The release job downloads it to
`build/verified-release/`, checks its SHA256 against the gate output, and inspects its embedded descriptor. The
`release-record-VERSION` artifact records the version, plugin ID/vendor, IDE build range, SHA256, and intended upload
path. Download the ZIP from the verified build artifact for local installation or review.

For a stable dry run, prepare the changelog and `pluginVersion` on the release branch, then manually run `Publish` on
that commit with `nightly` unchecked and `publish` unchecked. For a nightly dry run, check `nightly`; the filename and
embedded version both use `BASE_VERSION-nightly.COMMIT_SHA7`.

Creating a GitHub draft release requires explicitly checking the `publish` input. The publisher depends on both the
compatibility gate and successful dry-run validation. It downloads the same artifact, validates it again, and uploads
that exact ZIP without rebuilding or renaming it. The creation-only publisher refuses every existing release and
version tag, including matching lightweight or annotated tags. It atomically creates a new tag at the workflow commit,
then creates a new draft; it never updates a release or moves a tag. Concurrent workflow publishers for the same
version are serialized. A collision or API failure stops publication; a tag or incomplete draft already created during
that attempt is retained for manual inspection, and a rerun of the same version is refused. Running this workflow with
its defaults creates no GitHub release or tag.

GitHub's release and asset APIs are separate requests, so maintainers must leave the newly created draft unpublished
until its ZIP upload completes. The publisher rechecks the draft and tag before and after upload; workflow concurrency
covers this workflow's publishers, and cannot serialize independent manual or external release operations.

This fork has no Marketplace upload job. The plugin retains its upstream ID `com.github.biomejs.intellijbiome` and
vendor `biomejs`; those identifiers do not authorize a fork to publish an update to the upstream Marketplace listing.
Any future Marketplace automation must explicitly authorize its repository and release channel and consume the
verified archive through the supported Gradle `PublishPluginTask.archiveFile` property. The removed
`-PdistributionFile` argument was ignored by the Gradle plugin and did not select the downloaded artifact.
