# Biome dependency refresh implementation plan

> **For agentic workers:** Use superpowers:executing-plans. Complete tests, implementation and independent review before PR publication.

**Goal:** Adopt successful dependency upgrades automatically using the supported project-scoped Biome restart.

**Architecture:** A project service debounces relevant VFS changes and verifies current binaries using PR2's bounded probes. It uses only public `LspServerManager.stopAndRestartIfNeeded`; all servers in that project stop and roots needed by open files restart with their own dependencies/configuration. Other projects remain unchanged.

**Tech stack:** Kotlin, IntelliJ 253 LSP/VFS/project coroutine scope, pinned pnpm fixtures.

**Spec:** `docs/superpowers/specs/2026-10-07-biome-dependency-refresh.md`

## Global constraints

- Preserve minimum WebStorm 2025.3 / build 253, compiler JDK 21 and production dependencies.
- Automatic mode only; no duplicate UI, internal SDK restart, process kill or global daemon operation.
- Old servers survive a failed replacement. All probes run outside read actions and are cancelled on disposal.
- Project-wide restart is an explicit supported-API limitation; other projects remain untouched.

## Review focus

- Bursts and rename/move events must use both old and new paths and coalesce into one adoption.
- A partial/failed package install must not sacrifice the still-running server.
- A monorepo lock above a project must trigger a current-package check without restarting an unrelated project.
- Another root in the affected project must retain its own executable/config after the documented restart.
- Disposal and disabled/manual settings must prevent pending restart work. Superseding VFS events immediately invalidate older restart work; probe cancellation follows the existing bounded lifecycle.

### Task 1: Prove and fix actual package replacement

Files: `lsp/BiomeLspServerSupportProvider.kt`, new `services/BiomeDependencyRefreshService.kt`; `lsp/BiomeDependencyUpgradeLspTest.kt`, `lsp/RuntimeGateTestSupport.kt`, pinned 2.5.15 package lock fixture.

- [x] Run the existing actual upgrade/restart regressions. Automatic case stays Running 2.2.3 after a successful 2.5.15 install; manual restart control adopts 2.5.15 and formats the same file.
- [x] Implement project service initialization from an eligible provider callback. Use a conflated event flow with 500 ms debounce; verify all active roots before one public project restart when any actual version differs.
- [x] Run both actual upgrade cases and verify complete output, replacement identity and 2.5.15 server version.

### Task 2: Exercise lifecycle, install and scope controls

- [x] Add actual VFS/managed-server tests for failed installs, event bursts, parent locks and rename/delete/recreate.
- [x] Add two-root and separate-project tests. The affected project's second root may restart but must preserve its distinct binary/config/version/output; the other project's exact server identity and complete before/after formatting output must stay unchanged (passing output depends on the separately reviewed daemon ownership prerequisite).
- [x] Add disposal and disabled/manual mode controls; no pending restart occurs.
- [x] Run the new tests plus startup probe/LSP regressions and old highlighting; inspect XML for failures/errors/skips. Reviewed-source run: 39/40 pass, with the pre-existing highlighting diagnostics timeout on this branch before the shared helper fix; zero errors/skips. Final stacked verification remains required.
- [x] Resolve every independent review finding, rerun affected checks, and re-review (six review regressions pass; round 2 has no remaining refresh findings).
- [ ] Hand off the focused commit and final integration gates to root; complete stacked checks before PR publication.

### Review regression acceptance

- [x] Reproduce active-root reopen, closed failed-root, interpreter change, superseding-event, unchanged prerelease and changed prerelease defects with six managed SDK regressions.
- [x] Implement immediate event generations, final active-selection/interpreter checks, idle-root filtering and full-version prerequisite.
- [x] Confirm revised failed-initialization fixture RED/GREEN and all six cleanly passing.
- [ ] Pass unchanged-project full formatting after applying the independently reviewed daemon ownership prerequisite; retain existing-Restart baseline control.
- [x] Complete independent re-review with no remaining refresh findings (round 2); daemon prerequisite and final stacked verification remain publication gates.
