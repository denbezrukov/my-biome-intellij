# Save request safety

Each supported file gets one five-second budget for safe fixes, import sorting, and formatting, in that order. A timeout or ordinary request failure stops work on that file while the remaining files continue. Edits already completed remain available to the IDE's normal save.

The service checks cancellation and the document's modification stamp inside the write command. A response cannot overwrite typing that happened while the request was pending. Cancellation and platform control-flow exceptions leave the operation without an error notification.

Only immediate code-action edits without a command are applied. Resolving an action through the native intention API can issue a synchronous request, and command completion is not tracked here. Those actions are skipped so the write section does not start another network operation.

This change retains the existing modal save hook and native line-separator converter. The platform's suspending save lifecycle is a separate change with its own separator persistence and undo tests.

Run the focused checks with:

```sh
./gradlew cleanTest test --tests '*BiomeSaveOperationTest' --tests '*BiomeLegacySaveActionTest' --tests '*BiomeSaveActionsTest' --tests '*UnusedFunctionHighlightingTest' buildPlugin --no-build-cache
python3 .github/scripts/check-required-tests.py build/test-results/test
```
