package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.services.BiomeLspRequests
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.services.DefaultBiomeLspRequests
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.openapi.command.WriteCommandAction
import com.github.biomejs.intellijbiome.actions.BiomeCheckOnSaveAction
import com.github.biomejs.intellijbiome.actions.BiomeSaveOutcome
import com.github.biomejs.intellijbiome.actions.runBiomeSaveOperation
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.*
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicReference
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.lsp.api.LspServer
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.awaitCancellation
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.intellij.testFramework.LoggedErrorProcessor

@TestNpmPackage("@biomejs/biome@2.2.3")
class BiomeSaveActionsTest : BiomeLspFixtureTestCase() {
    private var verifyAfterDisposal: (() -> Unit)? = null

    override fun tearDown() {
        val verify = verifyAfterDisposal
        verifyAfterDisposal = null
        var failure: Throwable? = null
        try {
            super.tearDown()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            verify?.invoke()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    override fun setUp() {
        super.setUp()
        setUpLspFixture("save-actions")
        val executableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "biome.cmd" else "biome"
        val executable = Path.of(myFixture.tempDirPath, "node_modules", ".bin", executableName)
        check(Files.isRegularFile(executable)) { "Locked Biome 2.2.3 fixture executable is required" }
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = executable.toString()
        }
    }

    fun testSaveAllContinuesAfterFileFailure() = checkBatchIsolation { error("controlled request failure") }

    fun testSaveAllContinuesAfterFileTimeout() = checkBatchIsolation { awaitCancellation() }

    fun testTypingDiscardsStaleResponseAndRemainingFeatures() {
        val document = openDocuments().first()
        var formattingRequested = false
        val service = BiomeServerService(project, object : BiomeLspRequests by DefaultBiomeLspRequests {
            override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>> {
                withContext(Dispatchers.EDT) {
                    WriteCommandAction.runWriteCommandAction(project) { document.setText("// newer typing\n") }
                }
                return listOf(Either.forRight(CodeAction("controlled fix").apply {
                    edit = WorkspaceEdit(mapOf(params.textDocument.uri to listOf(
                        TextEdit(Range(Position(0, 0), Position(0, 0)), "// stale response\n"))))
                }))
            }
            override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                formattingRequested = true
                return emptyList()
            }
        })
        runService {
            service.executeFeatures(document, EnumSet.of(BiomeServerService.Feature.ApplySafeFixes, BiomeServerService.Feature.Format))
        }
        assertEquals("// newer typing\n", document.text)
        assertFalse("Stale work must stop later stages", formattingRequested)
    }

    fun testCancellationBeforeWriteDoesNotMutate() {
        val document = openDocuments().first()
        val original = document.text
        val cancellation = CancellationException("cancel before write")
        val service = BiomeServerService(project, object : BiomeLspRequests by DefaultBiomeLspRequests {
            override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                currentCoroutineContext().cancel(cancellation)
                return listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "// canceled\n"))
            }
        })
        var thrown: Throwable? = null
        runService {
            try {
                service.format(document)
            } catch (failure: Throwable) {
                thrown = failure
            }
        }
        assertSame(cancellation, thrown)
        assertEquals(original, document.text)
    }

    fun testCancellationWhileWriteIsQueuedDoesNotMutate() {
        val document = openDocuments().last()
        val file = FileDocumentManager.getInstance().getFile(document)!!
        val original = document.text
        val originalSeparator = file.detectedLineSeparator
        val originalBytes = diskText(document)
        val requestReady = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val writeQueued = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val result = AtomicReference<Result<Unit>>()
        val notifications = CopyOnWriteArrayList<com.intellij.notification.Notification>()
        val warnings = CopyOnWriteArrayList<String>()
        project.messageBus.connect(testRootDisposable).subscribe(com.intellij.notification.Notifications.TOPIC,
            object : com.intellij.notification.Notifications {
                override fun notify(notification: com.intellij.notification.Notification) {
                    if (notification.groupId == "Biome") notifications += notification
                }
            })
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    requestReady.complete(Unit)
                    releaseResponse.await()
                    // This marker cannot run on the single worker until the current
                    // coroutine yields after dispatching its write to the held EDT.
                    executor.execute { writeQueued.countDown() }
                    return listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "// canceled\r\n"))
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        try {
            LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
                override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                    if (category.contains("BiomeCheckOnSaveAction")) warnings += message
                    return false
                }
            }).use {
                scope.launch { result.set(runCatching { BiomeCheckOnSaveAction().updateDocument(project, document) }) }
                PlatformTestUtil.waitWithEventsDispatching("Formatting request did not start", { requestReady.isCompleted }, 10)
                releaseResponse.complete(Unit)
                // Do not dispatch EDT events until the write has queued and its
                // parent is canceled. A request-boundary check alone cannot pass.
                assertTrue("Write was not queued", writeQueued.await(10, TimeUnit.SECONDS))
                assertNull("The write must still be waiting for EDT", result.get())
                val cancellation = CancellationException("cancel queued write")
                scope.cancel(cancellation)
                PlatformTestUtil.waitWithEventsDispatching("Canceled write did not finish", { result.get() != null }, 10)
                val failure = result.get().exceptionOrNull()
                assertTrue(failure === cancellation || failure?.cause === cancellation)
                assertEquals(original, document.text)
                assertEquals(originalSeparator, file.detectedLineSeparator)
                assertEquals(originalBytes, diskText(document))
                assertTrue(warnings.isEmpty())
                assertTrue(notifications.isEmpty())
            }
        } finally {
            releaseResponse.complete(Unit)
            scope.cancel()
            dispatcher.close()
        }
    }

    fun testPlatformCancellationPropagates() {
        val document = openDocuments().first()
        val original = document.text
        val cancellation = ProcessCanceledException()
        val service = BiomeServerService(project, object : BiomeLspRequests by DefaultBiomeLspRequests {
            override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> = throw cancellation
        })
        var thrown: Throwable? = null
        var outcome: BiomeSaveOutcome? = null
        runService {
            try {
                outcome = runBiomeSaveOperation { service.format(document) }
            } catch (failure: Throwable) {
                thrown = failure
            }
        }
        assertSame(cancellation, thrown)
        assertNull(outcome)
        assertEquals(original, document.text)
    }

    private fun runService(operation: suspend () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val result = AtomicReference<Result<Unit>>()
        try {
            scope.launch { result.set(runCatching { operation() }) }
            PlatformTestUtil.waitWithEventsDispatching("Service operation did not finish", { result.get() != null }, 15)
            result.get().getOrThrow()
        } finally {
            scope.cancel()
        }
    }

    fun testEnabledFeaturesRunInOrderAndPersist() {
        val document = openDocuments().last()
        val calls = CopyOnWriteArrayList<String>()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>>? {
                    assertEquals(org.eclipse.lsp4j.CodeActionTriggerKind.Automatic, params.context.triggerKind)
                    assertEquals(Position(0, 0), params.range.start)
                    calls += params.context.only.single()
                    return DefaultBiomeLspRequests.codeActions(server, params)
                }
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit>? {
                    calls += "format"
                    return DefaultBiomeLspRequests.formatting(server, params)
                }
            }), testRootDisposable)
        val settings = BiomeSettings.getInstance(project)
        val input = "let value=1;console.log(value);\n"
        val subsets = listOf(
            Triple(true, false, false), Triple(false, true, false), Triple(false, false, true), Triple(true, true, true),
        )
        for ((fixes, imports, format) in subsets) {
            calls.clear()
            settings.applySafeFixesOnSave = fixes
            settings.sortImportOnSave = imports
            settings.formatOnSave = format
            WriteCommandAction.runWriteCommandAction(project) { document.setText(input) }
            saveAndAwaitCompletion(myFixture, listOf(document))
            val expectedCalls = buildList {
                if (fixes) add("source.fixAll.biome")
                if (imports) add("source.organizeImports.biome")
                if (format) add("format")
            }
            assertEquals(expectedCalls, calls.toList())
            val keyword = if (fixes) "const" else "let"
            // Biome 2.2.3's fix-all response includes formatting; preserve that server edit.
            val expected = if (fixes || format) "$keyword value = 1;\nconsole.log(value);\n" else "$keyword value=1;console.log(value);\n"
            assertEquals(expected, document.text)
            assertEquals(expected, diskText(document))
        }
    }

    fun testOrganizeImportsPersistsRealServerEdits() {
        val document = openDocuments().last()
        BiomeSettings.getInstance(project).sortImportOnSave = true
        val input = "import { z } from \"./z\";\nimport { a } from \"./a\";\nconsole.log(a,z);\n"
        WriteCommandAction.runWriteCommandAction(project) { document.setText(input) }
        saveAndAwaitCompletion(myFixture, listOf(document))
        val expected = "import { a } from \"./a\";\nimport { z } from \"./z\";\n\nconsole.log(a,z);\n"
        assertEquals(expected, document.text)
        assertEquals(expected, diskText(document))
    }

    fun testSavePreservesLfBytes() = checkLineSeparators("lf", "\n")

    fun testSavePreservesCrLfBytes() = checkLineSeparators("crlf", "\r\n")

    fun testCrLfOnlyEditPersists() = checkSeparatorOnlyEdit("\n", "\r\n")

    fun testLfOnlyEditPersists() = checkSeparatorOnlyEdit("\r\n", "\n")

    fun testSeparatorPersistsWhenEarlierEditsReturnToSavedText() = checkSeparatorOnlyEdit("\n", "\r\n", true)

    private fun checkSeparatorOnlyEdit(originalSeparator: String, requestedSeparator: String, earlierEdit: Boolean = false) {
        val document = openDocuments().last()
        val file = FileDocumentManager.getInstance().getFile(document)!!
        val formatted = "const value = 1;\nconsole.log(value);\n"
        var requests = 0
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>> =
                    listOf(Either.forRight(CodeAction("temporary fix").apply {
                        edit = WorkspaceEdit(mapOf(params.textDocument.uri to listOf(
                            TextEdit(Range(Position(0, 0), Position(0, 0)), "// fixed\n"))))
                    }))

                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    requests++
                    val endLine = if (earlierEdit) 3 else 2
                    return listOf(TextEdit(Range(Position(0, 0), Position(endLine, 0)), formatted.replace("\n", requestedSeparator)))
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).apply {
            applySafeFixesOnSave = earlierEdit
            formatOnSave = true
        }
        WriteCommandAction.runWriteCommandAction(project) {
            file.detectedLineSeparator = originalSeparator
            document.setText(formatted)
        }
        saveAndAwaitCompletion(myFixture, listOf(document))
        assertEquals("Separator conversion must not trigger a recursive save action", 1, requests)
        assertEquals(formatted, document.text)
        assertEquals(formatted.replace("\n", requestedSeparator), diskText(document))
        assertEquals(requestedSeparator, file.detectedLineSeparator)
        val editor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
        assertTrue("Separator-only formatting must be undoable", undo.isUndoAvailable(editor))
        undo.undo(editor)
        assertEquals("Undo must preserve normalized editor text", formatted, document.text)
        assertEquals(formatted.replace("\n", originalSeparator), diskText(document))
        assertTrue(undo.isRedoAvailable(editor))
        undo.redo(editor)
        assertEquals(formatted, document.text)
        assertEquals(formatted.replace("\n", requestedSeparator), diskText(document))
        assertEquals("Undo/redo must not restart Biome", 1, requests)
    }

    private fun checkLineSeparators(option: String, separator: String) {
        val config = myFixture.findFileInTempDir("biome.json") ?: error("Missing config")
        WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.openapi.vfs.VfsUtil.saveText(config, """{"formatter":{"lineEnding":"$option"}}""")
        }
        val document = openDocuments().last()
        BiomeSettings.getInstance(project).formatOnSave = true
        WriteCommandAction.runWriteCommandAction(project) { document.setText("const value={a:1};\nconsole.log(value);\n") }
        saveAndAwaitCompletion(myFixture, listOf(document))
        val expected = "const value = { a: 1 };\nconsole.log(value);\n"
        assertEquals(expected, document.text)
        assertEquals(expected.replace("\n", separator), diskText(document))
    }

    fun testPartialSuccessPersistsAfterTimeout() {
        val documents = openDocuments()
        val fixed = CopyOnWriteArrayList<String>()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>> {
                    fixed += params.textDocument.uri
                    return listOf(Either.forRight(CodeAction("controlled fix").apply {
                        edit = WorkspaceEdit(mapOf(params.textDocument.uri to listOf(
                            TextEdit(Range(Position(0, 0), Position(0, 0)), "// safe fix\n"))))
                    }))
                }
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    if (params.textDocument.uri == fixed.first()) awaitCancellation()
                    return listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "// formatted\n"))
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).apply {
            applySafeFixesOnSave = true
            formatOnSave = true
        }
        dirty(documents)
        saveAndAwaitCompletion(myFixture, documents)
        assertEquals(2, fixed.size)
        assertEquals(1, documents.count { it.text.startsWith("// safe fix\n") })
        assertEquals(1, documents.count { it.text.startsWith("// formatted\n// safe fix\n") })
        documents.forEach { assertEquals(it.text, diskText(it)) }
    }

    fun testMissingServerIsNoOp() {
        // A backing file that was never opened by the LSP has no available server.
        val file = myFixture.findFileInTempDir("first.js") ?: error("Missing file")
        val document = FileDocumentManager.getInstance().getDocument(file) ?: error("Missing document")
        val original = document.text
        runService { BiomeServerService.getInstance(project).executeFeatures(document, EnumSet.allOf(BiomeServerService.Feature::class.java)) }
        assertEquals(original, document.text)
    }

    fun testIdeFormatOnSaveOrderingAndUndo() {
        val document = openDocuments().first()
        val file = FileDocumentManager.getInstance().getFile(document)!!
        myFixture.configureFromExistingVirtualFile(file)
        val formatOptions = com.intellij.codeInsight.actions.onSave.FormatOnSaveOptions.getInstance(project)
        val originalEnabled = formatOptions.isRunOnSaveEnabled
        val input = "let value={a:1};"
        val beforeBiome = AtomicReference<String>()
        val requests = CopyOnWriteArrayList<String>()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams): List<Either<Command, CodeAction>> {
                    if (beforeBiome.get() == null) {
                        beforeBiome.set(com.intellij.openapi.application.readAction { document.text })
                    }
                    val stage = if (params.context.only.single() == "source.fixAll.biome") "fix" else "imports"
                    requests += stage
                    return listOf(Either.forRight(CodeAction(stage).apply {
                        edit = WorkspaceEdit(mapOf(params.textDocument.uri to listOf(
                            TextEdit(Range(Position(0, 0), Position(0, 0)), "// $stage\n"))))
                    }))
                }
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    requests += "format"
                    return listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "// format\n"))
                }
            }), testRootDisposable)
        try {
            formatOptions.isRunOnSaveEnabled = true
            BiomeSettings.getInstance(project).apply {
                applySafeFixesOnSave = true
                sortImportOnSave = true
                formatOnSave = true
                enableLspFormat = false
            }
            WriteCommandAction.runWriteCommandAction(project) { document.setText(input) }
            saveAndAwaitCompletion(myFixture, listOf(document))
            assertNotNull(beforeBiome.get())
            assertFalse("2025.3 must finish its legacy IDE formatter before Biome starts", input == beforeBiome.get())
            assertEquals(listOf("fix", "imports", "format"), requests.toList())
            val saved = "// format\n// imports\n// fix\n" + beforeBiome.get()
            assertEquals(saved, document.text)
            assertEquals(saved, diskText(document))
            val editor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
            val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
            assertTrue(undo.isUndoAvailable(editor))
            undo.undo(editor)
            assertEquals("All Biome feature edits must be one undo group", beforeBiome.get(), document.text)
            assertTrue(undo.isRedoAvailable(editor))
            undo.redo(editor)
            assertEquals(saved, document.text)
            assertEquals("Undo/redo must not restart Biome", 3, requests.size)
        } finally {
            formatOptions.isRunOnSaveEnabled = originalEnabled
        }
    }

    fun testTypingDuringSaveCancelsOnlyThatDocument() {
        val documents = openDocuments()
        val requested = AtomicReference<String>()
        val release = CompletableDeferred<Unit>()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    if (requested.compareAndSet(null, params.textDocument.uri)) {
                        // Deliberately deliver a response even after cancellation, as a raced
                        // transport completion might do. The real service must reject it.
                        withContext(NonCancellable) { release.await() }
                    }
                    return listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "// formatted\n"))
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        dirty(documents)
        try {
            FileDocumentManager.getInstance().saveAllDocuments()
            PlatformTestUtil.waitWithEventsDispatching("Save request did not start", { requested.get() != null }, 10)
            val typed = documents.single {
                requested.get().endsWith("/" + FileDocumentManager.getInstance().getFile(it)!!.name)
            }
            WriteCommandAction.runWriteCommandAction(project) { typed.setText("// newer typing\n") }
            release.complete(Unit)
            val manager = com.intellij.ide.actionsOnSave.impl.ActionsOnSaveManager.getInstance(project)
            PlatformTestUtil.waitWithEventsDispatching("Save actions did not finish", { !manager.hasPendingActions() }, 15)
            assertEquals("// newer typing\n", typed.text)
            assertTrue("Typing after save must remain unsaved", FileDocumentManager.getInstance().isDocumentUnsaved(typed))
            val other = documents.single { it !== typed }
            assertTrue(other.text.startsWith("// formatted\n"))
            assertEquals(other.text, diskText(other))
        } finally {
            release.complete(Unit)
        }
    }

    fun testProjectDisposalCancelsPendingSave() {
        val document = openDocuments().last()
        val started = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val cancellation = AtomicReference<CancellationException>()
        val notifications = CopyOnWriteArrayList<com.intellij.notification.Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(com.intellij.notification.Notifications.TOPIC,
            object : com.intellij.notification.Notifications {
                override fun notify(notification: com.intellij.notification.Notification) {
                    if (notification.groupId == "Biome") notifications += notification
                }
            })
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (failure: CancellationException) {
                        cancellation.set(failure)
                        throw failure
                    } finally {
                        canceled.complete(Unit)
                    }
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        dirty(listOf(document))
        val original = document.text
        FileDocumentManager.getInstance().saveAllDocuments()
        PlatformTestUtil.waitWithEventsDispatching("Pending save did not start", { started.isCompleted }, 10)
        val disposedProject = project
        // Let the fixture close its editors before disposing the real project. Its
        // teardown cannot access PSI services after an explicit forceCloseProject.
        verifyAfterDisposal = {
            assertTrue(disposedProject.isDisposed)
            assertTrue("Project disposal must cancel the pending save request", canceled.isCompleted)
            assertNotNull(cancellation.get())
            assertFalse("Project disposal must cancel before the local deadline", cancellation.get() is TimeoutCancellationException)
            assertEquals(original, document.text)
            assertTrue(notifications.isEmpty())
        }
    }

    private fun checkBatchIsolation(firstRequest: suspend () -> Unit) {
        val documents = openDocuments()
        val requests = CopyOnWriteArrayList<String>()
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun formatting(server: LspServer, params: DocumentFormattingParams): List<TextEdit> {
                    requests += params.textDocument.uri
                    if (requests.size == 1) firstRequest()
                    return listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "// saved by Biome\n"))
                }
            }), testRootDisposable)
        BiomeSettings.getInstance(project).formatOnSave = true
        dirty(documents)
        saveAndAwaitCompletion(myFixture, documents)
        assertEquals("One local failure must leave the other document eligible", 2, requests.size)
        val saved = documents.filter { it.text.startsWith("// saved by Biome\n") }
        assertEquals(1, saved.size)
        assertEquals(saved.single().text, diskText(saved.single()))
    }

    private fun openDocuments(): List<Document> = listOf("first.js", "second.js").map { path ->
        val file = myFixture.findFileInTempDir(path) ?: error("Missing fixture $path")
        myFixture.configureFromExistingVirtualFile(file)
        waitUntilFileOpenedByLspServer(project, file, "com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor")
        FileDocumentManager.getInstance().getDocument(file) ?: error("Missing document $path")
    }

    private fun dirty(documents: List<Document>) {
        WriteCommandAction.runWriteCommandAction(project) {
            documents.forEach { it.insertString(it.textLength, "// changed\n") }
        }
    }

    private fun diskText(document: Document): String {
        val file = FileDocumentManager.getInstance().getFile(document) ?: error("Document has no backing file")
        return Files.readString(Path.of(file.path))
    }
}
