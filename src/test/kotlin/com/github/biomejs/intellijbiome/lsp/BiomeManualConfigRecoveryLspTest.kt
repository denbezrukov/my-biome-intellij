package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerManagerListener
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Recovery uses real config events and the original editor, never a provider call or reopen. */
@TestNpmPackage("@biomejs/biome@2.5.14")
class BiomeManualConfigRecoveryLspTest : BiomeLspFixtureTestCase() {
    private lateinit var root: VirtualFile
    private lateinit var source: VirtualFile
    private lateinit var selectedExecutable: String
    private lateinit var events: RuntimeGateEvents
    private val config = """{"javascript":{"formatter":{"quoteStyle":"single"}}}"""

    @TestNpmPackage("@biomejs/biome@2.5.15")
    private class ProjectDependency

    override fun setUp() {
        super.setUp()
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
        events = RuntimeGateEvents(project, testRootDisposable)
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        root = myFixture.tempDirFixture.findOrCreateDir(".")
        TestNpmPackageInstaller(myFixture).installForTest(ProjectDependency::class.java, root)
        myFixture.tempDirFixture.findOrCreateDir("selected-tool")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root, "selected-tool")
        source = myFixture.tempDirFixture.createFile("index.js", "const message=\"hello\";\n")
        val automatic = BiomePackage(project).binaryPath(root.path, source, false)
        selectedExecutable = Files.walk(root.toNioPath().resolve("selected-tool/node_modules/.pnpm")).use { paths ->
            paths.filter { it.fileName.toString() == "biome" && it.parent.fileName.toString() == "cli-linux-x64" }
                .findFirst().orElseThrow().toString()
        }
        assertNotNull("A distinct project dependency is the executable-selection control", automatic)
        assertFalse("Manual recovery must not silently select the project dependency", automatic == selectedExecutable)
        manualSettings(project)
    }

    fun testMissingConfigCreationRecoversSameEditorWithSelectedExecutable() {
        recover(malformed = false)
    }

    fun testMalformedConfigRepairRecoversSameEditorWithSelectedExecutable() {
        recover(malformed = true)
    }

    fun testExplicitOverrideKeepsSelectedConfigAfterUnrelatedConfigCreation() {
        val selected = myFixture.tempDirFixture.createFile("selected/biome.jsonc", config)
        BiomeSettings.getInstance(project).configPath = selected.path
        val selectedOverride = BiomeSettings.getInstance(project).configPath
        openSource()
        assertFormatting()
        val original = servers().single()
        externalConfig(config.replace("single", "double"))
        settle()
        assertEquals(listOf(original), servers().toList())
        assertEquals(selectedOverride, BiomeSettings.getInstance(project).configPath)
        assertFormatting()
    }

    fun testExplicitOverrideAddedAfterOpenPreventsDiscoveryRecovery() {
        openSource()
        assertNoServers()
        BiomeSettings.getInstance(project).configPath = root.path + "/selected/biome.jsonc"
        externalConfig(config)
        assertNoServers()
    }

    fun testDisabledModePreventsPendingManualRecovery() {
        openSource()
        assertNoServers()
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.DISABLED
        externalConfig(config)
        assertNoServers()
    }

    fun testUnrelatedConfigDoesNotRecoverManualEditor() {
        openSource()
        assertNoServers()
        myFixture.tempDirFixture.createFile("unrelated/biome.json", config)
        assertNoServers()
    }

    fun testManualRecoveryPreservesAnotherProjectServerAndFormatting() {
        openSource()
        assertNoServers()
        com.intellij.ide.bookmarks.BookmarkManager.getInstance(project)
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val builder = factory.createFixtureBuilder("${name}-other-project")
        val otherFixture = factory.createCodeInsightFixture(builder.fixture)
        builder.addModule(EmptyModuleFixtureBuilder::class.java).addSourceContentRoot(otherFixture.tempDirPath)
        otherFixture.setUp()
        try {
            val otherProject = otherFixture.project
            configureLocalNodeForRuntimeTests(otherProject, otherFixture.testRootDisposable)
            otherFixture.testDataPath = myFixture.testDataPath
            val otherRoot = otherFixture.tempDirFixture.findOrCreateDir(".")
            TestNpmPackageInstaller(otherFixture).installForTest(ProjectDependency::class.java, otherRoot)
            val otherEvents = RuntimeGateEvents(otherProject, otherFixture.testRootDisposable)
            otherFixture.tempDirFixture.createFile("biome.json", "{}")
            val other = otherFixture.tempDirFixture.createFile("other.js", "const other=1;\n")
            otherFixture.configureFromExistingVirtualFile(other)
            waitUntilFileOpenedByLspServer(otherProject, other, timeout = 20)
            otherEvents.awaitDiagnostics(other, "2.5.15")
            val original = servers(otherProject).single()
            externalConfig(config)
            assertFormatting()
            assertEquals(listOf(original), servers(otherProject).toList())
            formatDocument(otherProject, otherFixture.editor.document)
            assertEquals("const other = 1;\n", otherFixture.editor.document.text)
        } finally {
            com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            project.service<BiomeServerService>().stopBiomeServer()
            otherFixture.project.service<BiomeServerService>().stopBiomeServer()
            otherFixture.tearDown()
        }
    }

    fun testExplicitOverrideInvalidatesQueuedRecovery() = checkQueuedChange(QueuedChange.OVERRIDE)
    fun testDisabledModeInvalidatesQueuedRecovery() = checkQueuedChange(QueuedChange.DISABLED)
    fun testClosingEditorInvalidatesQueuedRecovery() = checkQueuedChange(QueuedChange.CLOSE)
    fun testCancellationPreventsQueuedRecovery() = checkQueuedChange(QueuedChange.CANCEL)
    fun testExecutableChangeUsesFreshSelectionForQueuedRecovery() = checkQueuedChange(QueuedChange.EXECUTABLE)

    private enum class QueuedChange { OVERRIDE, DISABLED, CLOSE, CANCEL, EXECUTABLE }

    private fun checkQueuedChange(change: QueuedChange) {
        val dispatcher = QueuedDiscoveryDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        com.intellij.openapi.util.Disposer.register(testRootDisposable) {
            scope.cancel()
            dispatcher.drainCancelledTasks()
        }
        project.replaceService(BiomeConfigDiscoveryService::class.java,
            BiomeConfigDiscoveryService(project, scope), testRootDisposable)
        openSource()
        val editor = myFixture.editor
        val document = editor.document
        assertNoServers()
        val replacement = Files.walk(root.toNioPath().resolve("node_modules/.pnpm")).use { paths ->
            paths.filter { it.fileName.toString() == "biome" && it.parent.fileName.toString() == "cli-linux-x64" }
                .findFirst().orElseThrow().toString()
        }
        externalConfig(config)
        dispatcher.runPendingReads(scope, expectEdt = true)
        val settings = BiomeSettings.getInstance(project)
        when (change) {
            QueuedChange.OVERRIDE -> settings.configPath = root.path + "/explicit/biome.json"
            QueuedChange.DISABLED -> settings.configurationMode = ConfigurationMode.DISABLED
            QueuedChange.CLOSE -> FileEditorManager.getInstance(project).closeFile(source)
            QueuedChange.CANCEL -> scope.cancel()
            QueuedChange.EXECUTABLE -> {
                selectedExecutable = replacement
                settings.executablePath = replacement
            }
        }
        // Let the queued action observe the user's current settings and open editors.
        settle()
        if (change != QueuedChange.EXECUTABLE) {
            assertTrue("The queued action must respect the changed recovery eligibility", servers().isEmpty())
        }
        PlatformTestUtil.waitWithEventsDispatching("Queued discovery did not finish", {
            dispatcher.runNextPendingTask()
            scope.coroutineContext.job.children.none { it.isActive }
        }, 10)
        if (change == QueuedChange.EXECUTABLE) assertFormatting("2.5.15")
        else assertNoServers()
        if (change != QueuedChange.CLOSE) {
            assertSame("Queued recovery must retain the editor", editor, myFixture.editor)
            assertSame("Queued recovery must retain the document", document, myFixture.editor.document)
        }
    }

    fun testDisposalCancelsPendingManualConfigRecovery() {
        // Initialize primary editor services before secondary fixture leak tracking begins.
        openSource()
        assertNoServers()
        FileEditorManager.getInstance(project).closeFile(source)
        com.intellij.ide.bookmarks.BookmarkManager.getInstance(project)
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val builder = factory.createFixtureBuilder("${name}-disposing-project")
        val otherFixture = factory.createCodeInsightFixture(builder.fixture)
        builder.addModule(EmptyModuleFixtureBuilder::class.java).addSourceContentRoot(otherFixture.tempDirPath)
        otherFixture.setUp()
        val otherProject = otherFixture.project
        val lateStarts = AtomicInteger()
        try {
            configureLocalNodeForRuntimeTests(otherProject, otherFixture.testRootDisposable)
            manualSettings(otherProject)
            val otherRoot = otherFixture.tempDirFixture.findOrCreateDir(".")
            val other = otherFixture.tempDirFixture.createFile("other.js", "const other=1;\n")
            LspServerManager.getInstance(otherProject).addLspServerManagerListener(object : LspServerManagerListener {
                override fun serverStateChanged(lspServer: LspServer) {
                    if (otherProject.isDisposed && lspServer.state == LspServerState.Running) lateStarts.incrementAndGet()
                }
            }, testRootDisposable, true)
            otherFixture.configureFromExistingVirtualFile(other)
            PlatformTestUtil.waitWithEventsDispatching("Manual discovery subscription was not installed", {
                otherProject.getServiceIfCreated(BiomeConfigDiscoveryService::class.java) != null
            }, 10)
            Files.writeString(otherRoot.toNioPath().resolve("biome.json"), config)
            VfsUtil.markDirtyAndRefresh(false, true, true, otherRoot)
        } finally {
            otherFixture.tearDown()
        }
        assertTrue(otherProject.isDisposed)
        settle()
        assertEquals("Disposed projects must not start a recovered server", 0, lateStarts.get())
    }

    private fun recover(malformed: Boolean) {
        if (malformed) externalConfig("{broken")
        openSource()
        val editor = myFixture.editor
        val editors = FileEditorManager.getInstance(project).getEditors(source).toList()
        assertNoServers()
        externalConfig(config)
        assertFormatting()
        assertSame("Recovery must keep the existing editor", editor, myFixture.editor)
        assertEquals(editors, FileEditorManager.getInstance(project).getEditors(source).toList())
        assertEquals(1, servers().size)
        assertEquals(selectedExecutable, BiomeSettings.getInstance(project).executablePath)
        assertEquals("", BiomeSettings.getInstance(project).configPath)
    }

    private fun manualSettings(target: Project) {
        BiomeSettings.getInstance(target).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = selectedExecutable
            configPath = ""
        }
    }

    private fun openSource() { myFixture.configureFromExistingVirtualFile(source) }

    private fun externalConfig(text: String) {
        Files.writeString(root.toNioPath().resolve("biome.json"), text)
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
    }

    private fun settle() {
        val deadline = System.nanoTime() + 2_000_000_000L
        PlatformTestUtil.waitWithEventsDispatching("Config events did not settle", { System.nanoTime() >= deadline }, 5)
    }

    private fun assertNoServers() {
        settle()
        assertTrue("No eligible config should mean no server", servers().isEmpty())
    }

    private fun servers(target: Project = project) = LspServerManager.getInstance(target)
        .getServersForProvider(BiomeLspServerSupportProvider::class.java)

    private fun assertFormatting(version: String = "2.5.14") {
        waitUntilFileOpenedByLspServer(project, source, timeout = 20)
        events.awaitDiagnostics(source, version)
        val owner = servers().single { it.descriptor.isSupportedFile(source) }
        assertEquals(version, owner.initializeResult?.serverInfo?.version)
        formatDocument(project, myFixture.editor.document)
        assertEquals("const message = 'hello';\n", myFixture.editor.document.text)
    }

    private fun formatDocument(target: Project, document: com.intellij.openapi.editor.Document) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val operation = scope.async { withTimeout(15_000) { target.service<BiomeServerService>().format(document) } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Formatting timed out", { operation.isCompleted }, 20)
            runBlocking { operation.await() }
        } finally { scope.cancel() }
    }
}
