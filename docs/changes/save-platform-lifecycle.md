# Platform save lifecycle

Biome now updates each document through the platform's suspending save action. The IDE owns the final save and cancels the pending work when the user types or closes the project. The per-document request budget and stale-response checks remain in place.

The native line-separator converter saves the document before conversion, which conflicts with this lifecycle. Text edits instead carry separator metadata into the platform's final save. Separator-only edits convert bytes directly because the final save skips equal normalized text. Both paths compare VFS and filesystem timestamps. If they differ, separator undo and redo report a conflict and leave the external bytes untouched.

A successful formatting response is applied to the normalized request text to reconstruct the complete server output. Separator detection uses that output even when the server returns an empty edit list or only whitespace edits. A null response leaves text and separators unchanged. Normalizing the complete output once also handles a CRLF pair split across an edit boundary; character diffs preserve granular document changes.

`BiomeSavePlatformLifecycleTest` covers the actual save hook, IDE formatter ordering, typing and disposal cancellation, LF and CRLF bytes, separator-only edits, combined text and separator undo, and external writes. It includes a normal document-save conflict control, real Biome 2.5.15 CRLF-to-LF responses with no newline-bearing edits, and undo/redo for those conversions. A synthetic split-CRLF case checks edit-boundary handling; current Biome probes emit complete CRLF chunks. The inherited save request tests retain batch failure isolation and cancellation checks.

Run the focused checks with:

```sh
./gradlew cleanTest test --tests '*BiomeSavePlatformLifecycleTest' --tests '*BiomeSaveActionsTest' --tests '*BiomeSaveOperationTest' --tests '*BiomeLegacySaveActionTest' --tests '*UnusedFunctionHighlightingTest' buildPlugin --no-build-cache
python3 .github/scripts/check-required-tests.py build/test-results/test
```
