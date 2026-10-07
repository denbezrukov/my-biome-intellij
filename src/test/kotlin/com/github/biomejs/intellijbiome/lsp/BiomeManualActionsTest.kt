package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.actions.BiomeApplySafeFixesAction
import com.github.biomejs.intellijbiome.actions.BiomeSortImportAction
import com.github.biomejs.intellijbiome.services.BiomeLspRequests
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.services.DefaultBiomeLspRequests
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

@TestNpmPackage("@biomejs/biome@2.2.3")
class BiomeManualActionsTest : BiomeLspFixtureTestCase() {
    private val notifications = CopyOnWriteArrayList<Notification>()
    private var requestDisposable: Disposable? = null
    private val actions: List<AnAction> get() = listOf(BiomeApplySafeFixesAction(), BiomeSortImportAction())

    override fun setUp() {
        super.setUp()
        setUpLspFixture("save-actions")
        val executable = Path.of(myFixture.tempDirPath, "node_modules/.bin/biome")
        check(Files.isRegularFile(executable))
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = executable.toString()
        }
        notifications.clear()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "Biome") notifications += notification
            }
        })
    }

    override fun tearDown() {
        try {
            requestDisposable?.let(Disposer::dispose)
            requestDisposable = null
        } finally {
            super.tearDown()
        }
    }

    fun testBothActionsReportMissingServer() {
        openFile()
        BiomeServerService.getInstance(project).stopBiomeServer()
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.WARNING, "Biome unavailable", "language server is not ready")
        }
    }

    fun testBothActionsReportInitializingServerWithoutRequesting() {
        val wrapper = Path.of(myFixture.tempDirPath, "initializing-biome")
        val pid = Path.of(myFixture.tempDirPath, "initializing.pid")
        Files.writeString(wrapper, "#!/bin/sh\nif [ \"\$1\" = '--version' ]; then printf 'Version: 2.2.3\\n'; else printf '%s' \"\$\$\" > '${pid}'; exec sleep 60; fi\n")
        check(wrapper.toFile().setExecutable(true))
        BiomeSettings.getInstance(project).executablePath = wrapper.toString()
        val file = myFixture.findFileInTempDir("first.js")!!
        myFixture.configureFromExistingVirtualFile(file)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Server must be initializing", {
                Files.exists(pid) &&
                    LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
                        .any { it.state == LspServerState.Initializing }
            }, 10)
            var requested = false
            replaceRequests { _, _ -> requested = true; error("Initializing server must not be requested") }
            for (action in actions) {
                val before = myFixture.editor.document.text
                invoke(action)
                assertFalse(requested)
                assertEquals(before, myFixture.editor.document.text)
                assertNotification(NotificationType.WARNING, "Biome unavailable", "language server is not ready")
            }
        } finally {
            BiomeServerService.getInstance(project).stopBiomeServer()
            // This intentionally silent peer cannot answer the SDK's graceful shutdown.
            // Terminate only the process created by this fixture before project teardown.
            if (Files.exists(pid)) {
                ProcessHandle.of(Files.readString(pid).toLong()).ifPresent { child ->
                    child.destroyForcibly()
                    PlatformTestUtil.waitWithEventsDispatching("Initializing fixture must terminate", { !child.isAlive }, 10)
                }
            }
        }
    }

    fun testBothActionsReportNoChanges() {
        openFile()
        replaceRequests { _, _ -> emptyList() }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.INFORMATION, "No changes needed", "first.js")
        }
    }

    fun testBothActionsReportIdenticalTextEditAsUnchanged() {
        openFile()
        replaceRequests { _, params -> response(params, "") }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.INFORMATION, "No changes needed", "first.js")
        }
    }

    fun testBothActionsApplyEditsAndUndo() {
        openFile()
        replaceRequests { _, params -> response(params, "// manual edit\n") }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals("// manual edit\n$before", myFixture.editor.document.text)
            val prefix = if (action is BiomeApplySafeFixesAction) "biome.apply.safe.fixes" else "biome.apply.sort.import"
            assertNotification(NotificationType.INFORMATION, BiomeBundle.message("$prefix.success.label"), BiomeBundle.message("$prefix.success.description"))
            val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
            UndoManager.getInstance(project).undo(editor)
            assertEquals(before, myFixture.editor.document.text)
        }
    }

    fun testBothActionsApplyRealServerEditsAndUndo() {
        openFile()
        val cases = listOf(
            Triple(BiomeApplySafeFixesAction(), "let value=1;console.log(value);\n", "const value = 1;\nconsole.log(value);\n"),
            Triple(BiomeSortImportAction(), "import { z } from \"./z\";\nimport { a } from \"./a\";\nconsole.log(a,z);\n",
                "import { a } from \"./a\";\nimport { z } from \"./z\";\n\nconsole.log(a,z);\n"),
        )
        for ((action, input, expected) in cases) {
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText(input) }
            invoke(action)
            assertEquals(expected, myFixture.editor.document.text)
            val prefix = if (action is BiomeApplySafeFixesAction) "biome.apply.safe.fixes" else "biome.apply.sort.import"
            assertNotification(NotificationType.INFORMATION, BiomeBundle.message("$prefix.success.label"), BiomeBundle.message("$prefix.success.description"))
            UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
            assertEquals(input, myFixture.editor.document.text)
        }
    }

    fun testBothActionsReportStaleResponseAfterTyping() {
        openFile()
        replaceRequests { _, params ->
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("// new typing\n") }
            }
            response(params, "// stale edit\n")
        }
        for (action in actions) {
            // Changing text each time guarantees a distinct modification stamp.
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("// before typing\n") }
            invoke(action)
            assertEquals("// new typing\n", myFixture.editor.document.text)
            assertNotification(NotificationType.WARNING, "Biome result discarded", "changed")
        }
    }

    fun testBothActionsReportServerFailure() {
        openFile()
        replaceRequests { _, _ -> error("controlled request failure") }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.ERROR, if (action is BiomeApplySafeFixesAction) "Failed to apply safe fixes" else "Failed to sort imports", "controlled request failure")
        }
    }

    fun testBothActionsReportOwnTimeout() {
        openFile()
        replaceRequests { _, _ -> awaitCancellation() }
        for (action in actions) {
            val before = myFixture.editor.document.text
            invoke(action)
            assertEquals(before, myFixture.editor.document.text)
            assertNotification(NotificationType.WARNING, "Biome action timed out", "five seconds")
        }
    }

    fun testBothActionsPreservePlatformCancellation() {
        openFile()
        replaceRequests { _, _ -> throw ProcessCanceledException() }
        for (action in actions) {
            val before = myFixture.editor.document.text
            val failure = runCatching { invoke(action) }.exceptionOrNull()
            assertTrue("Platform cancellation must leave the action: $failure", failure is ProcessCanceledException || failure is CancellationException)
            assertEquals(before, myFixture.editor.document.text)
            assertTrue("Cancellation must not notify", notifications.isEmpty())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun testBothActionsPreserveParentCancellation() {
        openFile()
        replaceRequests { _, _ ->
            checkNotNull(currentCoroutineContext()[Job]?.parent).cancel(CancellationException("controlled parent cancellation"))
            awaitCancellation()
        }
        for (action in actions) {
            val before = myFixture.editor.document.text
            val failure = runCatching { invoke(action) }.exceptionOrNull()
            assertTrue("Parent cancellation must leave the action: $failure", failure is ProcessCanceledException || failure is CancellationException)
            assertEquals(before, myFixture.editor.document.text)
            assertTrue("Cancellation must not notify", notifications.isEmpty())
        }
    }

    fun testBothActionsPreserveRequestTimeoutCancellation() {
        openFile()
        replaceRequests { _, _ -> withTimeout(20) { awaitCancellation() } }
        for (action in actions) {
            val before = myFixture.editor.document.text
            val failure = runCatching { invoke(action) }.exceptionOrNull()
            assertTrue("Request timeout must remain cancellation: $failure", failure is ProcessCanceledException || failure is CancellationException)
            assertEquals(before, myFixture.editor.document.text)
            assertTrue(notifications.isEmpty())
        }
    }

    fun testBothActionsHideIneligibleContextsAndUseBgtUpdates() {
        openFile()
        for (action in actions) {
            assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
            val eligible = event(action)
            update(action, eligible)
            assertTrue(eligible.presentation.isEnabledAndVisible)
            val before = myFixture.editor.document.text
            val noEditor = event(action, false)
            update(action, noEditor)
            assertFalse(noEditor.presentation.isEnabledAndVisible)
            invoke(action, noEditor)
            assertEquals(before, myFixture.editor.document.text)
            assertTrue(notifications.isEmpty())
            BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.DISABLED
            val disabled = event(action)
            update(action, disabled)
            assertFalse(disabled.presentation.isEnabledAndVisible)
            invoke(action, disabled)
            assertEquals(before, myFixture.editor.document.text)
            assertTrue(notifications.isEmpty())
            BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.MANUAL
        }
        val unsupported = myFixture.addFileToProject("notes.unsupported", "plain text").virtualFile
        myFixture.configureFromExistingVirtualFile(unsupported)
        for (action in actions) {
            val event = event(action)
            update(action, event)
            assertFalse(event.presentation.isEnabledAndVisible)
            invoke(action, event)
            assertEquals("plain text", myFixture.editor.document.text)
            assertNotification(NotificationType.WARNING, "File not supported", "notes.unsupported")
        }
    }

    private fun openFile() {
        val file = myFixture.findFileInTempDir("first.js")!!
        myFixture.configureFromExistingVirtualFile(file)
        waitUntilFileOpenedByLspServer(project, file, "com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor")
        notifications.clear()
    }

    private fun replaceRequests(request: suspend (LspServer, CodeActionParams) -> List<Either<Command, CodeAction>>?) {
        val disposable = requestDisposable ?: Disposer.newDisposable().also {
            requestDisposable = it
            Disposer.register(testRootDisposable, it)
        }
        project.replaceService(BiomeServerService::class.java, BiomeServerService(project,
            object : BiomeLspRequests by DefaultBiomeLspRequests {
                override suspend fun codeActions(server: LspServer, params: CodeActionParams) = request(server, params)
            }), disposable)
    }

    private fun response(params: CodeActionParams, text: String): List<Either<Command, CodeAction>> =
        listOf(Either.forRight(CodeAction("controlled manual edit").apply {
            edit = WorkspaceEdit(mapOf(params.textDocument.uri to listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), text))))
        }))

    private fun event(action: AnAction, editor: Boolean = true): AnActionEvent {
        val context = DataContext { key ->
            when (key) {
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.EDITOR.name -> if (editor) myFixture.editor else null
                CommonDataKeys.VIRTUAL_FILE.name -> myFixture.file.virtualFile
                else -> null
            }
        }
        return AnActionEvent.createFromAnAction(action, null, if (editor) ActionPlaces.EDITOR_POPUP else ActionPlaces.PROJECT_VIEW_POPUP, context)
    }

    private fun update(action: AnAction, event: AnActionEvent) {
        val future = ApplicationManager.getApplication().executeOnPooledThread {
            ReadAction.run<RuntimeException> { action.update(event) }
        }
        PlatformTestUtil.waitWithEventsDispatching("BGT update did not finish", { future.isDone }, 10)
        future.get()
    }

    private fun invoke(action: AnAction, event: AnActionEvent = event(action)) {
        notifications.clear()
        action.actionPerformed(event)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun assertNotification(type: NotificationType, title: String, content: String) {
        assertEquals("One manual action notification", 1, notifications.size)
        val notification = notifications.single()
        assertEquals(type, notification.type)
        assertEquals(title, notification.title)
        assertTrue("Expected '$content' in '${notification.content}'", notification.content.contains(content))
    }
}
