# Changelog

All notable changes to this project will be documented in this file.

## 1.11.0 — fork release candidate

This version prepares the fork's combined changes for GitHub artifact review. It does not announce an upstream Marketplace release.

### Features and fixes

- Preserve the exact manually selected JSON/JSONC configuration file, including sibling configurations ([#8](https://github.com/denbezrukov/my-biome-intellij/pull/8)).
- Enable Grit by default while preserving native SVG/XML formatting; keep older Biome language support bounded by the installed version ([#9](https://github.com/denbezrukov/my-biome-intellij/pull/9)).
- Run cancellable save actions with a five-second budget per document; preserve newer edits, completed valid changes, actual disk output and undo/redo across LF/CRLF ([#10](https://github.com/denbezrukov/my-biome-intellij/pull/10)).
- Bound startup/version probes, preserve cancellation and process cleanup, and retain complete prerelease/build version identity ([#11](https://github.com/denbezrukov/my-biome-intellij/pull/11)).
- Honor the selected local Node.js interpreter for manual npm launchers while retaining native/foreign-target dispatch ([#12](https://github.com/denbezrukov/my-biome-intellij/pull/12)).
- Recover the existing editor after relevant configuration creation or repair ([#13](https://github.com/denbezrukov/my-biome-intellij/pull/13)).
- Keep nested configuration roots independent, including when rebuilding servers during restart ([#14](https://github.com/denbezrukov/my-biome-intellij/pull/14)).
- Preserve a Biome daemon shared with another open project during restart ([#15](https://github.com/denbezrukov/my-biome-intellij/pull/15)).
- Refresh Automatic-mode servers after a verified installed dependency upgrade, with each root retaining its own package/config selection ([#16](https://github.com/denbezrukov/my-biome-intellij/pull/16)).

### Audit follow-ups

- Filter queued nested-root recovery against current project/editor ownership, and retain package discovery context when the startup file disappears.
- Render missing, string and numeric diagnostic codes safely; preserve escaped multiline tooltips and readable newer-SDK markup messages without the old 263 binary-incompatible getter call.
- Distinguish changed, unchanged, unavailable and stale manual-action results; preserve cancellation and give own-timeout feedback. Hide editor-only actions in unsupported or disabled contexts.
- Retain stored formatting/save preferences through Disabled mode, Settings Apply/reopen and the Actions on Save page, while keeping execution disabled.
- Recover missing or repaired configuration in Manual mode with a blank override without reopening the editor; retain the selected executable and keep explicit overrides isolated.
- Close config streams exactly once and propagate cancellation/control flow through config parsing, including stream-close failures.
- Add explicit WebStorm compatibility lanes and a bounded descriptor range; gate fork ZIP distribution on required reports and Plugin Verifier results. Stable/nightly dry runs record the exact artifact's descriptor, version, SHA256 and intended upload path. Default workflow inputs publish nothing; an explicit publication input creates a GitHub draft from the same verified ZIP.

### Scope and distribution

- Minimum IDE build is 253 (2025.3); JDK 21 and Kotlin 2.2.20 remain pinned for development.
- Fork installation uses a verified GitHub ZIP. Upstream plugin ID/vendor attribution is preserved; the upstream Marketplace listing is separate.
- Linux is the actual runtime validation platform. Windows/WSL runtime and remote configuration mapping remain held. Default SVG routing, watcher controls, dynamic nested-root topology changes, and additional IDE product coverage remain outside this release's scope.

## 1.10.1

### Bug Fixes

- Resolve the `extends` option in the config correctly ([#214](https://github.com/biomejs/biome/pull/214))

## 1.10.0

### Bug Fixes

- Revert a change to use `DocumentUtil.executeInBulk` ([#208](https://github.com/biomejs/biome-intellij/pull/208))
- Fallback to child config when no root config was found in the project ([#210](https://github.com/biomejs/biome-intellij/pull/210))

### Features

- Improve diagnostics message ([#209](https://github.com/biomejs/biome-intellij/pull/209))

## 1.9.0

### Bug Fixes

- Support project structure with multiple roots ([#197](https://github.com/biomejs/biome-intellij/pull/197))

### Features

- Support the new-style monorepo ([#196](https://github.com/biomejs/biome-intellij/pull/196))

## 1.8.1

### Bug Fixes

- Resolved compatibility problems for older versions of IntelliJ IDEs ([#193](https://github.com/biomejs/biome-intellij/pull/193))

## 1.8.0

### Bug Fixes

- Fixed that applying fixes on save can break the code in the editor ([#190](https://github.com/biomejs/biome-intellij/pull/190))

### Features

- Removed a file listener that watches changes to `biome.json(c)`, it is now handled by Biome itself ([#183](https://github.com/biomejs/biome-intellij/pull/183))
- Improved performance on formatting ([#184](https://github.com/biomejs/biome-intellij/pull/184))

## 1.7.2

### Bug Fixes

- Fixed a regression where the plugin couldn't find biome.json at the project root ([#166](https://github.com/biomejs/biome-intellij/pull/166))

## 1.7.1

### Bug Fixes

- Avoid duplicated LSP instances in monorepo ([#159](https://github.com/biomejs/biome-intellij/pull/159))

## 1.7.0

### Bug Fixes

- Support textDocument/formatting response with granular text edits ([#148](https://github.com/biomejs/biome-intellij/pull/148))
- Run text edits in a reversed order ([#151](https://github.com/biomejs/biome-intellij/pull/151))
- Avoid conflicting code actions on save ([#149](https://github.com/biomejs/biome-intellij/pull/149))

### Features

- Monorepo support ([#138](https://github.com/biomejs/biome-intellij/pull/138))
- Support workspace/configuration request for providing configuration path ([#144](https://github.com/biomejs/biome-intellij/pull/144))

## 1.6.0

### Bug Fixes

- Improve BiomePackage and LSP startup logic ([#129](https://github.com/biomejs/biome-intellij/pull/129))
- Support running Biome on WSL Node.js interpreter ([#131](https://github.com/biomejs/biome-intellij/pull/131))
- Convert WSL path to local path and vice versa ([#132](https://github.com/biomejs/biome-intellij/pull/132))
- Workaround for IDEA-347138 ([#135](https://github.com/biomejs/biome-intellij/pull/135))

### Features

- Support running custom Biome executable on WSL 2 ([#134](https://github.com/biomejs/biome-intellij/pull/134))
- Refactor and replace BiomeRunner with BiomeServerService ([#124](https://github.com/biomejs/biome-intellij/pull/124))

## 1.5.5

### Bug Fixes

- Keep empty end line ([#120](https://github.com/biomejs/biome-intellij/pull/120))

### Features

- Add support for Biome config icons and improve save actions ([#119](https://github.com/biomejs/biome-intellij/pull/119))


## 1.5.4

### Bug Fixes

- Revert "format on save" ([#117](https://github.com/biomejs/biome-intellij/pull/117))

## 1.5.3

### Bug Fixes

- Infinite indexing / intellisense blocked ([#102](https://github.com/biomejs/biome-intellij/pull/102))
- Resolve plugin crash when using nightly biome releases ([#112](https://github.com/biomejs/biome-intellij/pull/112))

## 1.0.0

### Bug Fixes

- Fix plugin for IntelliJ 2024.1 ([#45](https://github.com/biomejs/biome-intellij/pull/45))
- Added missing double quotes ([#377](https://github.com/biomejs/biome-intellij/pull/377))
- Use node interpreter to run commands ([#416](https://github.com/biomejs/biome-intellij/pull/416))
- Binary resolution on windows ([#556](https://github.com/biomejs/biome-intellij/pull/556))
- Binary resolution execution sequence ([#601](https://github.com/biomejs/biome-intellij/pull/601))
- Remove build range ([#1093](https://github.com/biomejs/biome-intellij/pull/1093))
- Auto-save race condition ([#26](https://github.com/biomejs/biome-intellij/pull/26))

### Documentation

- Add contribution guide ([#2](https://github.com/biomejs/biome-intellij/pull/2))

### Features

- IntelliJ Platform LSP ([#185](https://github.com/biomejs/biome-intellij/pull/185))
- Manual config path specifying ([#660](https://github.com/biomejs/biome-intellij/pull/660))
- Add onSave actions
- Improved command line execution mode (node & binary)
- Use biome check ([#28](https://github.com/biomejs/biome-intellij/pull/28))
- Validate biome.json path config ([#32](https://github.com/biomejs/biome-intellij/pull/32))

