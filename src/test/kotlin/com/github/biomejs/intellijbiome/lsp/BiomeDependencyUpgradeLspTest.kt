package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManagerListener
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@TestNpmPackage("@biomejs/biome@2.2.3")
class BiomeDependencyUpgradeLspTest : BiomeLspFixtureTestCase() {
    private lateinit var root: VirtualFile
    private lateinit var source: VirtualFile

    private lateinit var events: RuntimeGateEvents

    override fun setUp() {
        super.setUp()
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
        events = RuntimeGateEvents(project, testRootDisposable)
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        root = myFixture.tempDirFixture.findOrCreateDir(".")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root)
        myFixture.tempDirFixture.createFile("biome.json", """{"javascript":{"formatter":{"quoteStyle":"single"}}}""")
        source = myFixture.tempDirFixture.createFile("index.js", "const message=\"hello\";\n")
    }

    fun testRealInstallAdoptsVersionBWithoutRestart() { upgrade(restart = false) }
    fun testExistingRestartAdoptsInstalledVersionB() { upgrade(restart = true) }

    private fun establishVersionA(): LspServer {
        assertExecutableVersion("2.2.3")
        myFixture.configureFromExistingVirtualFile(source)
        waitUntilFileOpenedByLspServer(project, source, timeout = 20)
        // Establish the version-A precondition before the upgrade begins. No reopening is
        // allowed after installation in the automatic-refresh case below.
        try {
            events.awaitDiagnostics(source, timeout = 5)
        } catch (_: AssertionError) {
            println("Pre-upgrade readiness control: reopening the version-A file once")
            FileEditorManager.getInstance(project).closeFile(source)
            myFixture.configureFromExistingVirtualFile(source)
            waitUntilFileOpenedByLspServer(project, source, timeout = 20)
            events.awaitDiagnostics(source)
        }
        val original = servers().single { it.descriptor.roots.single() == root }
        assertEquals("2.2.3", original.initializeResult?.serverInfo?.version)
        formatAndAssert()
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("const message=\"hello\";\n") }
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
        return original
    }

    private fun upgrade(restart: Boolean) {
        val original = establishVersionA()
        installVersionB()
        if (restart) project.service<BiomeServerService>().restartBiomeServer()
        assertAdoptedVersionB(original)
    }

    private fun installVersionB() {
        installLockedVersion("_biomejs_biome_2_5_15", root)
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        assertExecutableVersion("2.5.15")
    }

    private fun assertAdoptedVersionB(original: LspServer, format: Boolean = true) {
        try {
            PlatformTestUtil.waitWithEventsDispatching("Installed 2.5.15 did not replace the 2.2.3 server", {
                servers().any { it.initializeResult?.serverInfo?.version == "2.5.15" && it.descriptor.roots.single() == root }
            }, 20)
        } finally {
            println("After upgrade: " + servers().joinToString {
                "state=${it.state} version=${it.initializeResult?.serverInfo?.version} original=${it === original}"
            })
        }
        val current = servers().single { it.descriptor.roots.single() == root }
        assertNotSame("Upgrade must replace the old server", original, current)
        assertEquals("2.5.15", current.initializeResult?.serverInfo?.version)
        waitUntilFileOpenedByLspServer(project, source, timeout = 15)
        events.awaitDiagnostics(source, "2.5.15")
        if (format) formatAndAssert()
    }

    fun testDependencyEventBurstAdoptsVersionBOnce() {
        val original = establishVersionA()
        val started = CopyOnWriteArraySet<LspServer>().apply { add(original) }
        LspServerManager.getInstance(project).addLspServerManagerListener(object : LspServerManagerListener {
            override fun serverStateChanged(lspServer: LspServer) {
                if (lspServer.state == LspServerState.Running && lspServer.providerClass == BiomeLspServerSupportProvider::class.java) started.add(lspServer)
            }
        }, testRootDisposable, true)
        installVersionB()
        repeat(5) {
            Files.writeString(root.toNioPath().resolve("package.json"), Files.readString(root.toNioPath().resolve("package.json")) + " ")
            VfsUtil.markDirtyAndRefresh(false, true, true, root)
        }
        assertAdoptedVersionB(original)
        val current = servers().single()
        settleUnchanged(current)
        assertEquals("One original and one replacement server despite the event burst", setOf(original, current), started.toSet())
    }

    fun testFailedReplacementKeepsWorkingServerThenRecovers() {
        val original = establishVersionA()
        val packagePath = root.toNioPath().resolve("node_modules/@biomejs/biome")
        val backup = packagePath.resolveSibling("biome-before-failed-install")
        Files.move(packagePath, backup)
        Files.createDirectories(packagePath.resolve("bin"))
        Files.writeString(packagePath.resolve("package.json"), """{"name":"@biomejs/biome","version":"2.5.15","bin":{"biome":"bin/biome"}}""")
        Files.writeString(packagePath.resolve("bin/biome"), "process.exit(42);\n")
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        try {
            settleUnchanged(original)
            formatAndAssert()
        } finally {
            Files.delete(packagePath.resolve("bin/biome"))
            Files.delete(packagePath.resolve("bin"))
            Files.delete(packagePath.resolve("package.json"))
            Files.delete(packagePath)
            Files.move(backup, packagePath)
            VfsUtil.markDirtyAndRefresh(false, true, true, root)
        }
        resetSource()
        installVersionB()
        assertAdoptedVersionB(original)
    }

    fun testDependencyRenameAndRecreationPreservesOldServerUntilValid() {
        val original = establishVersionA()
        val packageFile = root.findFileByRelativePath("node_modules/@biomejs/biome")!!
        com.intellij.openapi.application.WriteAction.run<RuntimeException> { packageFile.rename(this, "biome-temporarily-removed") }
        settleUnchanged(original)
        com.intellij.openapi.application.WriteAction.run<RuntimeException> { packageFile.rename(this, "biome") }
        installVersionB()
        assertAdoptedVersionB(original)
    }

    fun testParentMonorepoLockAdoptsReplacementWithoutPackageRefresh() {
        val parentLock = root.toNioPath().parent.resolve("pnpm-lock.yaml")
        Files.writeString(parentLock, "# initial parent lock\n")
        val lock = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(parentLock)!!
        val original = establishVersionA()
        installVersionBWithoutVfsRefresh()
        assertSame(original, servers().single())
        Files.writeString(parentLock, "# parent monorepo install completed\n")
        VfsUtil.markDirtyAndRefresh(false, false, false, lock)
        assertAdoptedVersionB(original)
    }

    fun testDisabledModeDoesNotRefreshAfterDependencyUpgrade() {
        val original = establishVersionA()
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.DISABLED
        installVersionBWithoutVfsRefresh()
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        settleUnchanged(original)
    }

    fun testManualModeDoesNotRefreshAfterDependencyUpgrade() {
        val original = establishVersionA()
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.MANUAL
        installVersionBWithoutVfsRefresh()
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        settleUnchanged(original)
    }

    fun testProjectRestartRetainsSecondRootsOwnBinaryAndConfiguration() { checkSecondRoot(closeOther = false) }
    fun testClosedRootDoesNotBlockRemainingRootsUpgrade() { checkSecondRoot(closeOther = true) }

    private fun checkSecondRoot(closeOther: Boolean) {
        com.intellij.openapi.application.WriteAction.run<RuntimeException> { root.findChild("biome.json")!!.delete(this) }
        root = myFixture.tempDirFixture.findOrCreateDir("upgrading")
        installLockedVersion("_biomejs_biome_2_2_3", root)
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        myFixture.tempDirFixture.createFile("upgrading/biome.json", """{"javascript":{"formatter":{"quoteStyle":"single"}}}""")
        source = myFixture.tempDirFixture.createFile("upgrading/index.js", "const message=\"hello\";\n")
        val otherRoot = myFixture.tempDirFixture.findOrCreateDir("other")
        // Keep this root distinct from both upgrade versions; 2.2.3 can lose an
        // initial didOpen during workspace setup, independently of root ownership.
        installLockedVersion("_biomejs_biome_2_5_14", otherRoot)
        VfsUtil.markDirtyAndRefresh(false, true, true, otherRoot)
        myFixture.tempDirFixture.createFile("other/biome.json", """{"javascript":{"formatter":{"quoteStyle":"double"}}}""")
        val otherFile = myFixture.tempDirFixture.createFile("other/other.js", "const other='other';\n")
        myFixture.configureFromExistingVirtualFile(otherFile)
        waitUntilFileOpenedByLspServer(project, otherFile, timeout = 20)
        val oldOther = servers().single()
        awaitInitialReadiness(otherFile, project, myFixture, events)
        formatAndAssert("const other = \"other\";\n")
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("const other='other';\n") }
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
        val otherEditor = myFixture.editor
        val original = establishVersionA()
        if (closeOther) FileEditorManager.getInstance(project).closeFile(otherFile)
        installVersionB()
        assertAdoptedVersionB(original)
        if (closeOther) {
            assertEquals(listOf(root), servers().map { it.descriptor.roots.single() })
            return
        }
        val newOther = servers().single { it.descriptor.roots.single() == otherRoot }
        assertNotSame("The public API intentionally restarts every Biome root in this project", oldOther, newOther)
        assertEquals("2.5.14", newOther.initializeResult?.serverInfo?.version)
        assertTrue("Second root must retain its own dependency", (newOther.descriptor as BiomeLspServerDescriptor).executable.startsWith(otherRoot.path + "/node_modules/"))
        assertFalse("The secondary editor must stay open across restart", otherEditor.isDisposed)
        assertTrue(FileEditorManager.getInstance(project).isFileOpen(otherFile))
        val beforeSelection = otherEditor.document.modificationStamp
        // Reconfiguring the fixture rewrites the file and makes in-flight diagnostics stale.
        myFixture.openFileInEditor(otherFile)
        assertSame("Selecting the secondary file must retain its editor", otherEditor, myFixture.editor)
        assertSame("Selecting the secondary file must retain its document", otherEditor.document, myFixture.editor.document)
        assertEquals("Selecting the secondary editor must not rewrite its document", beforeSelection, myFixture.editor.document.modificationStamp)
        waitUntilFileOpenedByLspServer(project, otherFile, timeout = 15)
        events.awaitDiagnostics(otherFile, newOther)
        formatAndAssert("const other = \"other\";\n")
    }

    fun testDependencyUpgradePreservesAnotherProjectsServer() { checkOtherProject(manualRestartOnly = false) }
    fun testExistingRestartKeepsAnotherProjectResponsiveWithoutAnInstall() { checkOtherProject(manualRestartOnly = true) }

    private fun checkOtherProject(manualRestartOnly: Boolean) {
        val original = establishVersionA()
        com.intellij.ide.bookmarks.BookmarkManager.getInstance(project)
        // The secondary fixture snapshots global editor listeners. Register the
        // still-live primary project's lazy listener before that snapshot.
        com.intellij.refactoring.suggested.SuggestedRefactoringProvider.getInstance(project)
        // Native JS annotation also owns package.json pointers in this project.
        // Complete that work before the secondary fixture captures its leak baseline.
        myFixture.doHighlighting()
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
            TestNpmPackageInstaller(otherFixture).installForTest(javaClass, otherRoot)
            otherFixture.tempDirFixture.createFile("biome.json", "{}")
            val other = otherFixture.tempDirFixture.createFile("other.js", "const other=1;\n")
            val otherEvents = RuntimeGateEvents(otherProject, otherFixture.testRootDisposable)
            otherFixture.configureFromExistingVirtualFile(other)
            waitUntilFileOpenedByLspServer(otherProject, other, timeout = 20)
            awaitInitialReadiness(other, otherProject, otherFixture, otherEvents)
            val otherManager = LspServerManager.getInstance(otherProject)
            val otherServer = otherManager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
            assertEquals("2.2.3", otherServer.initializeResult?.serverInfo?.version)
            println("Other project formatting precondition before installation")
            assertFormattingResponse(otherServer, other, "const other = 1;\n")
            fun processes(label: String) {
                ProcessHandle.allProcesses().use { all -> all.filter { process ->
                    val command = process.info().commandLine().orElse("")
                    command.contains(root.path) || command.contains(otherRoot.path)
                }.forEach { process -> println("$label pid=${process.pid()} parent=${process.parent().map { it.pid() }.orElse(-1)} command=${process.info().commandLine().orElse("")}") } }
            }
            processes("Before primary restart")
            if (manualRestartOnly) {
                project.service<BiomeServerService>().restartBiomeServer()
                PlatformTestUtil.waitWithEventsDispatching("Existing restart did not replace the primary server", {
                    servers().singleOrNull()?.let { it !== original && it.state == LspServerState.Running } == true
                }, 20)
            } else {
                installVersionB()
                assertAdoptedVersionB(original, format = false)
            }
            processes("After primary restart")
            println("Checking other project after primary restart (manual without install=$manualRestartOnly)")
            assertEquals(listOf(otherServer), otherManager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList())
            assertEquals(LspServerState.Running, otherServer.state)
            assertEquals("2.2.3", otherServer.initializeResult?.serverInfo?.version)
            assertFormattingResponse(otherServer, other, "const other = 1;\n")
        } finally {
            com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            project.service<BiomeServerService>().stopBiomeServer()
            otherFixture.project.service<BiomeServerService>().stopBiomeServer()
            otherFixture.tearDown()
        }
    }

    fun testProjectDisposalCancelsPendingDependencyRefresh() {
        com.intellij.ide.bookmarks.BookmarkManager.getInstance(project)
        com.intellij.refactoring.suggested.SuggestedRefactoringProvider.getInstance(project)
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val builder = factory.createFixtureBuilder("${name}-disposing-project")
        val otherFixture = factory.createCodeInsightFixture(builder.fixture)
        builder.addModule(EmptyModuleFixtureBuilder::class.java).addSourceContentRoot(otherFixture.tempDirPath)
        otherFixture.setUp()
        val otherProject = otherFixture.project
        val replacements = AtomicInteger()
        try {
            configureLocalNodeForRuntimeTests(otherProject, otherFixture.testRootDisposable)
            otherFixture.testDataPath = myFixture.testDataPath
            val otherRoot = otherFixture.tempDirFixture.findOrCreateDir(".")
            TestNpmPackageInstaller(otherFixture).installForTest(javaClass, otherRoot)
            otherFixture.tempDirFixture.createFile("biome.json", "{}")
            val other = otherFixture.tempDirFixture.createFile("other.js", "const other=1;\n")
            otherFixture.configureFromExistingVirtualFile(other)
            waitUntilFileOpenedByLspServer(otherProject, other, timeout = 20)
            LspServerManager.getInstance(otherProject).addLspServerManagerListener(object : LspServerManagerListener {
                override fun serverStateChanged(lspServer: LspServer) {
                    if (lspServer.providerClass == BiomeLspServerSupportProvider::class.java && lspServer.state == LspServerState.Running) replacements.incrementAndGet()
                }
            }, testRootDisposable, false)
            installVersionBWithoutVfsRefresh(otherRoot)
            VfsUtil.markDirtyAndRefresh(false, true, true, otherRoot)
        } finally {
            otherFixture.tearDown()
        }
        assertTrue(otherProject.isDisposed)
        val deadline = System.nanoTime() + 1_500_000_000L
        PlatformTestUtil.waitWithEventsDispatching("Disposal callbacks did not settle", { System.nanoTime() >= deadline }, 5)
        assertEquals("Disposal must cancel the pending debounced replacement", 0, replacements.get())
    }

    fun testTemporarilyMissingInterpreterDoesNotLoseRefreshSubscription() {
        val original = establishVersionA()
        val manager = com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager.getInstance(project)
        val selected = manager.interpreterRef
        try {
            manager.setInterpreterRef(com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef.create("unavailable-interpreter"))
            assertNull(manager.interpreter)
            installVersionBWithoutVfsRefresh()
            VfsUtil.markDirtyAndRefresh(false, true, true, root)
            settleUnchanged(original)
        } finally {
            manager.setInterpreterRef(selected)
        }
        val manifest = root.toNioPath().resolve("package.json")
        Files.writeString(manifest, Files.readString(manifest) + " ")
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        assertAdoptedVersionB(original)
    }

    fun testUpgradeWhileAnotherRootInitializesIsRetriedWhenItRuns() {
        com.intellij.openapi.application.WriteAction.run<RuntimeException> { root.findChild("biome.json")!!.delete(this) }
        root = myFixture.tempDirFixture.findOrCreateDir("upgrading")
        installLockedVersion("_biomejs_biome_2_2_3", root)
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        myFixture.tempDirFixture.createFile("upgrading/biome.json", """{"javascript":{"formatter":{"quoteStyle":"single"}}}""")
        source = myFixture.tempDirFixture.createFile("upgrading/index.js", "const message=\"hello\";\n")
        val original = establishVersionA()
        val otherRoot = myFixture.tempDirFixture.findOrCreateDir("slow")
        installLockedVersion("_biomejs_biome_2_2_3", otherRoot)
        val launcher = otherRoot.toNioPath().resolve("node_modules/@biomejs/biome/bin/biome")
        Files.move(launcher, launcher.resolveSibling("biome-before-delay"))
        Files.writeString(launcher, """
            const run = () => {
              const child = require('node:child_process').spawn(process.execPath,
                [__dirname + '/biome-before-delay', ...process.argv.slice(2)], {stdio: 'inherit'});
              child.on('exit', code => process.exit(code ?? 1));
            };
            if (process.argv.includes('lsp-proxy')) setTimeout(run, 8000); else run();
        """.trimIndent())
        VfsUtil.markDirtyAndRefresh(false, true, true, otherRoot)
        myFixture.tempDirFixture.createFile("slow/biome.json", "{}")
        val otherFile = myFixture.tempDirFixture.createFile("slow/other.js", "const other=1;\n")
        myFixture.configureFromExistingVirtualFile(otherFile)
        PlatformTestUtil.waitWithEventsDispatching("Second root never started initializing", {
            servers().any { it.descriptor.roots.single() == otherRoot && it.state == LspServerState.Initializing }
        }, 10)
        // Select the already-open original editor; this does not send another didOpen.
        myFixture.configureFromExistingVirtualFile(source)
        installVersionB()
        assertTrue("Replacement event must happen during the second root's initialization",
            servers().any { it.descriptor.roots.single() == otherRoot && it.state == LspServerState.Initializing })
        assertAdoptedVersionB(original)
    }

    private fun awaitInitialReadiness(file: VirtualFile, fileProject: com.intellij.openapi.project.Project,
        fixture: com.intellij.testFramework.fixtures.CodeInsightTestFixture, runtimeEvents: RuntimeGateEvents) {
        try {
            runtimeEvents.awaitDiagnostics(file, timeout = 5)
        } catch (_: AssertionError) {
            FileEditorManager.getInstance(fileProject).closeFile(file)
            fixture.configureFromExistingVirtualFile(file)
            waitUntilFileOpenedByLspServer(fileProject, file, timeout = 20)
            runtimeEvents.awaitDiagnostics(file)
        }
    }

    private fun assertFormattingResponse(server: LspServer, file: VirtualFile, expected: String) {
        val params = org.eclipse.lsp4j.DocumentFormattingParams(server.getDocumentIdentifier(file), org.eclipse.lsp4j.FormattingOptions(2, true))
        val input = VfsUtil.loadText(file)
        val document = com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(input)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val operation = scope.async { withTimeout(15_000) {
            server.sendRequest { it.textDocumentService.formatting(params) }
        } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Other project formatting timed out", { operation.isCompleted }, 20)
            val edits = runBlocking { operation.await() } ?: error("Other project returned no formatting edits")
            val output = StringBuilder(input)
            edits.map { edit ->
                Triple(document.getLineStartOffset(edit.range.start.line) + edit.range.start.character,
                    document.getLineStartOffset(edit.range.end.line) + edit.range.end.character, edit.newText)
            }.sortedByDescending { it.first }.forEach { (start, end, text) -> output.replace(start, end, text) }
            assertEquals(expected, output.toString())
        } finally { scope.cancel() }
    }

    private fun installVersionBWithoutVfsRefresh(installRoot: VirtualFile = root) {
        installLockedVersion("_biomejs_biome_2_5_15", installRoot)
        if (installRoot == root) assertExecutableVersion("2.5.15", installRoot.toNioPath().resolve("node_modules/@biomejs/biome/bin/biome").toString())
    }

    private fun installLockedVersion(fixtureName: String, installRoot: VirtualFile) {
        val fixture = Path.of(myFixture.testDataPath, "_package-locks-store", fixtureName)
        Files.copy(fixture.resolve("package.json"), installRoot.toNioPath().resolve("package.json"), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(fixture.resolve("pnpm-lock.yaml"), installRoot.toNioPath().resolve("pnpm-lock.yaml"), StandardCopyOption.REPLACE_EXISTING)
        val output = Files.createTempFile("biome-real-upgrade-", ".log")
        try {
            val installer = ProcessBuilder("corepack", "pnpm", "install", "--frozen-lockfile").directory(installRoot.toNioPath().toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                assertTrue("pnpm installation timed out", installer.waitFor(30, TimeUnit.SECONDS))
                assertEquals(Files.readString(output), 0, installer.exitValue())
            } finally { if (installer.isAlive) installer.destroyForcibly() }
        } finally { Files.deleteIfExists(output) }
    }

    private fun settleUnchanged(original: LspServer) {
        val deadline = System.nanoTime() + 2_000_000_000L
        PlatformTestUtil.waitWithEventsDispatching("Dependency events did not settle", {
            assertEquals(listOf(original), servers().toList())
            assertEquals(LspServerState.Running, original.state)
            System.nanoTime() >= deadline
        }, 5)
    }

    private fun resetSource() {
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("const message=\"hello\";\n") }
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
    }

    private fun formatAndAssert(expected: String = "const message = 'hello';\n") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val operation = scope.async { withTimeout(15_000) { project.service<BiomeServerService>().format(myFixture.editor.document) } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Formatting timed out", { operation.isCompleted }, 20)
            runBlocking { operation.await() }
            assertEquals(expected, myFixture.editor.document.text)
        } finally { scope.cancel() }
    }

    private fun assertExecutableVersion(expected: String, executable: String = BiomePackage(project).binaryPath(root.path, source, false) ?: error("Automatic discovery failed")) {
        val output = Files.createTempFile("biome-upgrade-version-", ".txt")
        try {
            val process = ProcessBuilder("node", executable, "--version").directory(root.toNioPath().toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                assertTrue("Version probe timed out", process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(Files.readString(output), 0, process.exitValue())
                println("Selected executable $executable resolves ${root.toNioPath().resolve("node_modules/@biomejs/biome").toRealPath()} actual=${Files.readString(output).trim()} expected=$expected")
                assertEquals("Version: $expected", Files.readString(output).trim())
            } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
        } finally { Files.deleteIfExists(output) }
    }

    private fun servers() = LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)

}
