# Keep shared Biome daemons alive across project restarts

Two projects using the same Biome version share a version-specific daemon. On Linux, an automatic-mode session starts a Node launcher, which starts the native `lsp-proxy`, which can start that shared daemon. WebStorm 2025.3 defaults to recursive process teardown. Restarting the project that first started the daemon therefore kills the other project's transport despite its LSP server still reporting Running.

The descriptor will disable recursive teardown through the public `OSProcessHandler.setShouldDestroyProcessRecursively(false)` API only after startup checks succeed and just before handing the proxy to the platform. For `KillableProcessHandler`, it will also disable soft termination through `setShouldKillProcessSoftly(false)`. With both policies, normal teardown uses `Process.destroy()`, which closes the owned stdin before waiting on inherited output pipes. Sending SIGINT only to the Node wrapper otherwise leaves the native proxy blocked waiting for stdin EOF. The platform still performs the LSP shutdown/exit exchange first. Version probes and failed startup handoffs retain their existing recursive termination, because the plugin still owns those processes. Biome owns daemon lifetime; it starts the daemon with `--stop-on-disconnect` and must stop it after the final client disconnects.

Acceptance requires two real projects on pinned Biome 2.2.3: full formatting before and after restarting the first project, unchanged second-server identity, old wrapper and native proxy termination, continued second-project formatting after stopping the first, and actual daemon termination after stopping the final project. Exercise the manual native executable path as well. The minimum IDE plugin verifier must accept the API without new internal API usage. No Windows/WSL execution claim is made from Linux tests.

## Version-specific acceptance clarification (PR #31 review)

The original pinned 2.2.3 full restart fixture mixed process ownership with an independently
reproduced upstream first-open registration defect. A plugin-free single-open probe loses
the document when 2.2.3 receives it while workspace initialization is pending; 2.5.15 does
not lose it. The supported SDK exposes no portable public document-ordering gate.

Preserve both original Node/native full restart cases on pinned 2.5.15, including exact
replacement diagnostics/formatting, unchanged second-server identity, all proxy cleanup,
and final daemon shutdown. Add separate pinned 2.2.3 Node/native ownership controls:
two real projects format successfully, stopping the first through the public service must
terminate only its wrapper/native proxy, the same daemon and second-server identity must
survive with exact formatting, and stopping the final client must terminate its proxies and
the daemon. Initial fixture readiness may be established before the action; no file is
reopened afterward. These controls exercise the shared teardown path that caused the
original cross-project regression without representing a successful legacy replacement
registration as proven. This split explicitly supersedes the single-version acceptance
paragraph above; support for 2.2.3 and its other existing regressions remains in place.

The after-restart formatting assertion measures responsiveness after replacement document
recovery, not uninterrupted requests during its initialization writes. A transient
ContentModified/PendingWrite response during those writes is a separate request-recovery
contract; the ownership tests must not claim that the production request layer retries it.
