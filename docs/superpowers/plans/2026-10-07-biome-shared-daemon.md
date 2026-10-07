# Shared daemon ownership implementation plan

1. Preserve exact minimum-SDK bytecode and real process ancestry showing recursive teardown reaches the shared daemon.
2. Extract a focused real two-project regression and record baseline failure after the existing manual Restart action.
3. Change only successful long-lived proxy handoff to nonrecursive teardown and disable Node handler soft termination using the supported handler policies, so Java closes the owned transport before inherited output-pipe cleanup.
4. Prove full formatting, old proxy cleanup, surviving shared-daemon identity, final-client shutdown, and manual native launch behavior. Keep probe/failed-start cleanup regression suites passing.
5. Add explicit required CI inventory, run all required tests and guard tests, package and verify against minimum WebStorm 2025.3, and run independent review/fix cycles.
