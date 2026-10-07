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
./gradlew cleanTest test --no-build-cache --tests '*BiomeCheckOnSaveActionTest' --tests '*BiomeSaveOperationTest' --tests '*BiomeLauncherLspTest' --tests '*BiomeLauncherTest' --tests '*BiomeConfigTest' --tests '*BiomeConfigRecoveryLspTest' --tests '*BiomeDependencyRefreshLifecycleTest' --tests '*BiomeDependencyUpgradeLspTest' --tests '*BiomeLanguageLspTest' --tests '*BiomeLanguageRoutingTest' --tests '*BiomeManualConfigCliTest' --tests '*BiomeManualConfigLspTest' --tests '*BiomeManualConfigV1LspTest' --tests '*BiomeNestedRootsLspTest' --tests '*BiomeSaveActionsTest' --tests '*BiomeSharedDaemonLspTest' --tests '*OlderBiomeLanguageLspTest' --tests '*UnusedFunctionHighlightingTest' --tests '*V1BiomeLanguageLspTest' --tests '*BiomeManualConfigSettingsTest' --tests '*BiomeStartupLspTest' --tests '*BiomeStartupProbeTest' --tests '*BiomeDisabledPreferencesTest' --tests '*BiomeDiagnosticsTest' --tests '*BiomeManualConfigRecoveryLspTest' --tests '*BiomeManualActionsTest'
python3 .github/scripts/check-required-tests.py build/test-results/test
```

These suites exercise settings persistence, both Biome CLI versions, and real plugin LSP sessions.
`BiomeManualActionsTest` invokes both manual actions and checks notification outcomes, actual edits and undo,
unchanged edits, disabled/unavailable/command-only results, mixed applied/skipped results, missing/initializing servers,
stale responses, failure, timeout, cancellation, and presentation availability. The v1 launch tests
require Linux. The fixture installer uses the committed pnpm lockfiles with `--frozen-lockfile`. `cleanTest` removes old
results; `--no-build-cache` prevents Gradle from restoring cached test results. The report guard requires all 227 named
tests across 26 classes to execute without failures or skips. The CI
job runs the guard and uploads reports even when Gradle fails. Packaging remains a separate `./gradlew buildPlugin` job.

The required `BiomeConfigTest` inventory exercises the real loader:

- `testValidJsonClosesStreamOnce`, `testValidJsoncClosesStreamOnce`, `testRootAndExtendsSemanticsArePreserved`
- `testMalformedInputReturnsNullAndClosesStreamOnce`, `testIoFailureOpeningReturnsNull`, `testIoFailureReadingReturnsNullAndClosesStreamOnce`, `testIoFailureClosingReturnsNullAndClosesStreamOnce`
- `testCoroutineCancellationIdentityIsPreserved`, `testPlatformCancellationIdentityIsPreserved`, `testPlatformControlFlowIdentityIsPreserved`
- `testFatalFailureIdentityIsPreserved`, `testUnexpectedRuntimeFailureIdentityIsPreserved`
- `testClosingCancellationAfterExpectedReadFailureIsPreserved`, `testClosingFatalFailureAfterExpectedReadFailureIsPreserved`

Disabled preference regressions: `BiomeDisabledPreferencesTest.testDisableApplyReopenEnablePreservesPreferences`, `testInitiallyDisabledApplyPreservesPreferences`, `testDisabledSerializationPreservesPreferences`, `testCancelDoesNotChangePreferencesOrMode`, `testActionsOnSaveResetAndApplyPreserveDisabledPreferences`, and `BiomeCheckOnSaveActionTest.testDisabledPreferencesExecuteNoSaveWork`. These cover settings Apply/reopen, XML persistence, Cancel, Actions on Save reset/toggling, and execution suppression.

The nested recovery gate includes these named `BiomeNestedRootsLspTest` regressions:

- `testChildFirstRepairAfterMalformedRestartRestoresIndependentWorkspace`
- `testClosedEditorInvalidatesQueuedNestedRecovery`
- `testMalformedConfigInvalidatesQueuedNestedRecovery`
- `testDisabledModeDoesNotRecoverNestedConfig`
- `testManualModeDoesNotRecoverNestedConfig`
- `testNestedRepairPreservesAnotherProjectServer`
- `testNewIndependentChildConfigRecoversUnownedEditor`
- `testRepairAfterDependencyRefreshRestoresIndependentWorkspace`
- `testRepairAfterMalformedChildRestartRestoresIndependentWorkspace`
- `testRepairWithoutRestartControlRestoresIndependentWorkspace`

They check actual SDK discovery order, retained editor identity, exclusive child ownership, real dependency refresh,
config-event bursts, and project/mode isolation. Config removal and reparenting remain separate lifecycle work.

The manual executable recovery gate includes these named `BiomeManualConfigRecoveryLspTest` regressions:

- `testMissingConfigCreationRecoversSameEditorWithSelectedExecutable`
- `testMalformedConfigRepairRecoversSameEditorWithSelectedExecutable`
- `testWhitespaceOverrideUsesSameEditorDiscovery`
- `testExplicitOverrideKeepsSelectedConfigAfterUnrelatedConfigCreation`
- `testExplicitOverrideAddedAfterOpenPreventsDiscoveryRecovery`
- `testDisabledModePreventsPendingManualRecovery`
- `testUnrelatedConfigDoesNotRecoverManualEditor`
- `testManualRecoveryPreservesAnotherProjectServerAndFormatting`
- `testDisposalCancelsPendingManualConfigRecovery`
- `testExplicitOverrideInvalidatesQueuedManualRecovery`
- `testExecutableChangeInvalidatesQueuedManualRecoveryBeforeRetry`

They retain the open editor, selected executable and real server version, and verify formatting with a different
project dependency installed. Nonblank overrides retain exact configuration selection; the existing nested Manual
negative control uses an explicit override. Mode changes, project disposal and unrelated config/project isolation
remain covered. Controlled-dispatcher races also require changes to Manual config/executable selections to discard
stale discovery requests before an EDT restart, then adopt the current executable on a fresh read.

To check the report guard itself, run `python3 .github/scripts/test-check-required-tests.py`.

Diagnostic coverage includes absent, string, integer and zero codes; multiline and HTML-sensitive
messages/tooltips; real Biome highlighting and an applied parameter quick fix.
`BiomeDiagnosticsTest.testRuntimeMessageRepresentationsKeepTheirText` exercises the running SDK's
string representation and both plaintext/markdown `MarkupContent` where the SDK accepts it
(the tested 263 SDK). Markup is preserved as readable, escaped text; rich Markdown rendering is not promised.
Run this suite on the minimum, current stable and newest supported SDK when changing diagnostic APIs.

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
that exact ZIP without rebuilding or renaming it. Both stable and nightly drafts use a version-specific tag targeting
the workflow commit. Running this workflow with its defaults creates no GitHub release or tag.

This fork has no Marketplace upload job. The plugin retains its upstream ID `com.github.biomejs.intellijbiome` and
vendor `biomejs`; those identifiers do not authorize a fork to publish an update to the upstream Marketplace listing.
Any future Marketplace automation must explicitly authorize its repository and release channel and consume the
verified archive through the supported Gradle `PublishPluginTask.archiveFile` property. The removed
`-PdistributionFile` argument was ignored by the Gradle plugin and did not select the downloaded artifact.
