# Biome - IntelliJ Plugin

Minimum IDE build: **253 (2025.3)**. Development and compatibility gates use WebStorm; see [supported IDEs](#supported-ides) for the product and runtime limits.

[Biome](https://biomejs.dev/) is a powerful tool designed to enhance your development experience.
The plugin provides the following capabilities in [compatible JetBrains IDEs](#supported-ides).

- 💡 See lints while you type
- 👨‍💻 Apply code fixes
- 🚧 Reformat your code
- 💾 Apply code fixes on save

## Installation

This repository is the [denbezrukov/my-biome-intellij fork](https://github.com/denbezrukov/my-biome-intellij). Its release candidates are distributed as GitHub ZIP artifacts. The [upstream Marketplace listing](https://plugins.jetbrains.com/plugin/22761-biome) is maintained separately; it does not imply availability of this fork's changes.

1. Open a successful [Publish dry run](https://github.com/denbezrukov/my-biome-intellij/actions/workflows/publish.yaml) for the commit you want to review. The compatibility gate's artifact contains `intellij-biome-VERSION.zip`; the `release-record-VERSION` artifact records its SHA256, descriptor and intended upload path. A dry run creates no release or tag. Published fork assets, when available, appear on the [GitHub releases page](https://github.com/denbezrukov/my-biome-intellij/releases).
2. Download the plugin ZIP. For the stable candidate, both the filename and embedded version are `1.11.0`; nightly versions include `-nightly.COMMIT_SHA7`.
3. Open **Settings/Preferences > Plugins**, use the gear menu, and choose **Install Plugin from Disk…**. Select the ZIP and restart the IDE if prompted.

The fork preserves upstream plugin ID `com.github.biomejs.intellijbiome` and vendor `biomejs`, with the upstream license and attribution. It replaces the installed Biome plugin with that same ID; these are not two independently installable plugins. This fork has no Marketplace upload job. See [CONTRIBUTING](CONTRIBUTING.md#maintainers) for the nonpublishing release procedure.

## Getting Started
### Biome Resolution

The plugin tries to use Biome from your project’s local dependencies (`node_modules/.bin/biome`). We recommend adding Biome as a project dependency to ensure that NPM scripts and the extension use the same Biome version.

You can also explicitly specify the `biome` binary the extension should use by configuring the `Biome executable` in **Settings/Preferences > Languages & Frameworks > Biome**.

In Automatic mode, a successful Biome dependency upgrade refreshes the language servers in that IntelliJ project. Each open Biome root resolves its dependency and configuration separately; other IntelliJ projects are unaffected. The plugin waits for the selected executables to work before restarting, so an incomplete or failed install keeps the existing servers running. Manual mode continues to use the existing Restart action.

When several package installations share one Biome config root, restarting can select a different installation according to the files currently open. Opening another file by itself does not count as a dependency upgrade.

### Biome Config resolution
In Automatic mode, the plugin searches upward from each supported file for `biome.json` or `biome.jsonc` and follows Biome's root configuration rules. Each independent config root gets its own language server and working directory, including nested roots. Switching between files does not restart a single shared server. A file without a covering configuration remains uncovered; creating or repairing a relevant configuration can start its server without reopening the editor. This recovery also applies in Manual mode when the configuration override is blank. Running Biome handles ordinary configuration edits.

Manual mode accepts an existing `biome.json`, `biome.jsonc`, or a directory containing either. Selecting a specific file preserves that exact choice, including JSONC alongside JSON; leaving the field blank uses automatic discovery. Manual executable and configuration paths are separate settings. Local npm launchers use the project's selected Node.js interpreter. Native executables run directly. Windows/WSL target selection is preserved, but actual Windows/WSL execution and remote configuration mapping have not been validated by the Linux release gate.

Dynamic creation/removal of an independent nested root under an already running parent remains outside the config-recovery scope. Use the native language-service restart after changing workspace topology.

### Plugin settings

#### `Biome executable`

In Manual mode, this setting selects the native Biome executable or npm launcher used by the plugin.

#### Enabling Code Formatting

To enable formatting, open Biome settings and enable **LSP-based Code Formatting**. This feature allows you to format code using the designated hotkey (<kbd>⌥⇧⌘+L</kbd> or <kbd>Ctrl+Alt+L</kbd>).

If you want to format code on save, navigate to **Actions on Save** settings and enable **Reformat Code**, specifying the desired file types.


#### Biome actions on save

Enable Biome's save actions in **Actions on Save**. For each supported document, enabled actions run in this order: safe fixes, organize imports, then formatting. They share a five-second total budget and run with cancellable background progress.

A timeout or ordinary failure stops Biome work for that file while other files continue. Valid edits completed before the failure remain and are saved; there is no rollback. Timeouts produce a file-specific warning in the IDE log. Other failures also show a notification naming the affected file. Typing cancels pending work for that document, and canceled or stale responses do not overwrite newer text. User cancellation and project closure stop the corresponding work without an error notification.

The IDE controls ordering relative to its built-in formatter. WebStorm 2025.3 runs **Reformat Code** before Biome's save actions. This five-second budget applies only to Biome save actions; manual fixes, import sorting and formatting keep their existing behavior.


### Supported IDEs

The plugin requires build **253 (2025.3)** through **262.* (2026.2)** within the packaged descriptor's supported range, the Ultimate platform module, and JavaScript support. WebStorm is the tested product. IntelliJ IDEA Ultimate and other paid JetBrains products with those modules may satisfy the descriptor, but this release does not claim a tested product matrix. AppCode and Community/free-product support are not claimed.

The release gate targets **WS-253.28294.332** and **WS-262.10968.77**, runs the complete required regression inventory on both, and verifies the same ZIP against both targets. WebStorm **263 / 2026.3 EAP** is outside the admitted range: direct diagnostic-renderer and verifier investigations do not establish its full runtime gate. Desktop acceptance remains unrun; Plugin Verifier results do not replace an interactive walkthrough. Linux is the actual runtime test platform. Windows/WSL runtime acceptance remains held.

Default extensions include JavaScript/TypeScript, JSON/JSONC, CSS, GraphQL, Grit, and supported embedded-language files. Availability depends on the installed Biome version: older Biome versions are not promised newer language support. Grit is included by default, with text-file fallback where the IDE has no Grit language registration; the plugin does not add a Grit parser or syntax highlighter. SVG is excluded by default because the tested Biome route returned no formatting edits and suppressed the IDE formatter. Native SVG/XML formatting remains available. Custom extension preferences remain configurable.

Disabled mode suppresses integration while retaining saved formatting/save preferences for re-enabling. Manual fix/import actions require an enabled, supported active editor and a usable server. Changed, unchanged, unavailable and stale results have distinct feedback; cancellation does not report a failure.

## Troubleshooting

Check the native **Language Services** widget for Biome's version, current root, state and errors. Use its restart control after changing a manual executable or diagnosing a stalled server. A requested restart is asynchronous; confirm the replacement server is running and produces diagnostics or exact formatting. Project restart and dependency refresh must preserve another project's shared Biome daemon.

Use **Help > Show Log in Files** (the name varies by OS) to locate the IDE log directory. Inspect `idea.log` for startup/save exceptions and file-specific timeout messages, and `language-services/Biome*` for server traffic and output logs. Do not guess a fixed home-directory path: custom IDE sandbox/log settings change it. Include the IDE build, plugin version, complete Biome version, affected file/config root, selected executable and Node interpreter, and relevant log excerpts in a [fork issue](https://github.com/denbezrukov/my-biome-intellij/issues).

## Usage

Biome CLI comes with many commands and options, so you can use only what you need.

Apply code fixes by either hovering over the relevant section and selecting the suggested fix, pressing <kbd title="Option">⌥</kbd>+<kbd  title="Enter">⏎</kbd>(Option+Enter) or <kbd title="Alt">Alt</kbd>+<kbd title="Enter">Enter</kbd>.

To reformat your code, use the keyboard shortcut <kbd>⌥⇧</kbd>+<kbd title="Cmd">⌘</kbd>+<kbd  title="L">L</kbd> or <kbd title="Ctrl">Ctrl</kbd>+<kbd title="Alt">Alt</kbd>+<kbd  title="L">L</kbd>. Alternatively, you can configure your IDE to format [code automatically on save](https://www.jetbrains.com/help/webstorm/reformat-and-rearrange-code.html#reformat-on-save)  for a seamless coding experience.
