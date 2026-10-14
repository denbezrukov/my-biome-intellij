# Platform save lifecycle

Biome now updates each document through the platform's suspending save action. The IDE owns the final save and cancels the pending work when the user types or closes the project. The per-document request budget and stale-response checks remain in place.

The native line-separator converter saves the document before conversion, which conflicts with this lifecycle. Text edits instead carry separator metadata into the platform's final save. Separator-only edits convert bytes directly because the final save skips equal normalized text. Both paths share a disk timestamp check, and separator undo and redo report an external-file conflict instead of overwriting newer disk bytes.

`BiomeSavePlatformLifecycleTest` covers the actual save hook, IDE formatter ordering, typing and disposal cancellation, LF and CRLF bytes, separator-only edits, combined text and separator undo, and external writes. It includes a normal document-save conflict control. The inherited save request tests retain batch failure isolation and cancellation checks.

Run the focused checks with:

```sh
./gradlew cleanTest test --tests '*BiomeSavePlatformLifecycleTest' --tests '*BiomeSaveActionsTest' --tests '*BiomeSaveOperationTest' --tests '*BiomeLegacySaveActionTest' --tests '*UnusedFunctionHighlightingTest' buildPlugin --no-build-cache
python3 .github/scripts/check-required-tests.py build/test-results/test
```
