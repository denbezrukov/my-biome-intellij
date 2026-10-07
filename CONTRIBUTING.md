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
- IntelliJ IDEA Ultimate Edition
- git-cliff (_maintainers only_)

1. Fork the repository and clone it to your local machine.
   ```shell
   gh repo fork biomejs/biome-intellij --clone
   ```

2. Install Corepack and activate the test fixture package manager. Node.js 24 does not bundle Corepack.
   ```shell
   npm install --global corepack@0.34.0
   corepack enable
   corepack prepare pnpm@10.5.2 --activate
   ```

## Development

The plugin can be started in IDEA by running the `runIde` Gradle task. This will start a new instance of IDEA with the
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

The second-root dependency upgrade fixture keeps the upgrading root on 2.2.3 → 2.5.15 and
pins the unchanged secondary root to 2.5.14. This isolates root ownership, executable selection,
and configuration preservation from an independently reproduced Biome 2.2.3 initialization bug
that can lose an early document open. The replacement still must deliver diagnostics and format
correctly without a post-restart reopen or diagnostic retry. Other legacy and v1 coverage remains
in the required inventory; the fixture choice does not fix the upstream 2.2.3 limitation.

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

This section is for maintainers only. It describes the process for releasing a new version of the extension.

### Releasing a stable version

1. Create a new branch for the release.
   ```shell
   git fetch
   git checkout -b release/vX.Y.Z main
   ```
2. Generate the changelog.
   ```shell
   git-cliff --bump e71479100d4ed81b3e9c26881c38a0ddb7da31eb..
   ```
3. Bump the version in `gradle.properties` and to match the latest version in the changelog.
4. Commit and push your changes.
5. Create a pull request named `chore(release): prepare vX.Y.Z`.
6. Merge the pull request.
7. Run the [`Publish`](https://github.com/biomejs/biome-intellij/actions/workflows/publish.yaml) workflow manually from
   the Actions tab in GitHub (uncheck _nightly_).

### Releasing a nightly version

1. Commit your changes to the _main_ branch.
2. Generate the changelog.
   ```shell
   git-cliff e71479100d4ed81b3e9c26881c38a0ddb7da31eb..
   ```
3. Commit and push your changes.
4. Run the [`Publish`](https://github.com/biomejs/biome-intellij/actions/workflows/publish.yaml) workflow manually from
   the Actions tab in GitHub (check _nightly_).
