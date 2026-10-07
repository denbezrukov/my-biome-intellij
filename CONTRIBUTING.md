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
./gradlew cleanTest test --no-build-cache --tests '*BiomeCheckOnSaveActionTest' --tests '*BiomeSaveOperationTest' --tests '*BiomeLauncherLspTest' --tests '*BiomeLauncherTest' --tests '*BiomeConfigRecoveryLspTest' --tests '*BiomeDependencyRefreshLifecycleTest' --tests '*BiomeDependencyUpgradeLspTest' --tests '*BiomeLanguageLspTest' --tests '*BiomeLanguageRoutingTest' --tests '*BiomeManualConfigCliTest' --tests '*BiomeManualConfigLspTest' --tests '*BiomeManualConfigV1LspTest' --tests '*BiomeNestedRootsLspTest' --tests '*BiomeSaveActionsTest' --tests '*BiomeSharedDaemonLspTest' --tests '*OlderBiomeLanguageLspTest' --tests '*UnusedFunctionHighlightingTest' --tests '*V1BiomeLanguageLspTest' --tests '*BiomeManualConfigSettingsTest' --tests '*BiomeStartupLspTest' --tests '*BiomeStartupProbeTest'
python3 .github/scripts/check-required-tests.py build/test-results/test
```

These suites exercise settings persistence, both Biome CLI versions, and real plugin LSP sessions. The v1 launch tests
require Linux. The fixture installer uses the committed pnpm lockfiles with `--frozen-lockfile`. `cleanTest` removes old
results; `--no-build-cache` prevents Gradle from restoring cached test results. The report guard requires all 163 named
tests across 21 classes to execute without failures or skips. The CI
job runs the guard and uploads reports even when Gradle fails. Packaging remains a separate `./gradlew buildPlugin` job.

The second-root dependency upgrade fixture keeps the upgrading root on 2.2.3 → 2.5.15 and
pins the unchanged secondary root to 2.5.14. This isolates root ownership, executable selection,
and configuration preservation from an independently reproduced Biome 2.2.3 initialization bug
that can lose an early document open. The replacement still must deliver diagnostics and format
correctly without a post-restart reopen or diagnostic retry. Other legacy and v1 coverage remains
in the required inventory; the fixture choice does not fix the upstream 2.2.3 limitation.

To check the report guard itself, run `python3 .github/scripts/test-check-required-tests.py`.

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
