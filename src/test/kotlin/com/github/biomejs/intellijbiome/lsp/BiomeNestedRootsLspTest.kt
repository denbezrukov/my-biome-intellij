package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerManagerListener
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.nio.file.Files

@TestNpmPackage("@biomejs/biome@2.5.14")
class BiomeNestedRootsLspTest : BiomeLspFixtureTestCase() {
    private lateinit var parentFile: VirtualFile
    private lateinit var childFile: VirtualFile
    private val opened = CopyOnWriteArrayList<Pair<LspServer, VirtualFile>>()
    private val observedServers = CopyOnWriteArrayList<LspServer>()

    private lateinit var events: RuntimeGateEvents

    override fun setUp() {
        super.setUp()
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
        events = RuntimeGateEvents(project, testRootDisposable)
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        val root = myFixture.tempDirFixture.findOrCreateDir(".")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root)
        myFixture.tempDirFixture.findOrCreateDir("nested")
        TestNpmPackageInstaller(myFixture).installForTest(CurrentBiome::class.java, root, "nested")
        myFixture.tempDirFixture.createFile("biome.json", """{"javascript":{"formatter":{"quoteStyle":"double"}}}""")
        myFixture.tempDirFixture.createFile("nested/biome.json", """{"root":true,"javascript":{"formatter":{"quoteStyle":"single"}}}""")
        parentFile = myFixture.tempDirFixture.createFile("parent.js", "const message='parent';\n")
        childFile = myFixture.tempDirFixture.createFile("nested/child.js", "const message=\"child\";\n")
        assertFalse("Each root must resolve its own executable", BiomePackage(project).binaryPath(root.path, parentFile, false) == BiomePackage(project).binaryPath(root.path, childFile, false))
        LspServerManager.getInstance(project).addLspServerManagerListener(object : LspServerManagerListener {
            override fun serverStateChanged(lspServer: LspServer) {
                if (lspServer.providerClass == BiomeLspServerSupportProvider::class.java && lspServer !in observedServers) {
                    observedServers.add(lspServer)
                }
            }

            override fun fileOpened(lspServer: LspServer, file: VirtualFile) {
                if (lspServer.providerClass == BiomeLspServerSupportProvider::class.java) opened.add(lspServer to file)
            }
        }, testRootDisposable, true)
    }

    fun testRepairAfterMalformedChildRestartRestoresIndependentWorkspace() = checkMalformedRestartRepair()
    fun testChildFirstRepairAfterMalformedRestartRestoresIndependentWorkspace() = checkMalformedRestartRepair(parentFirst = false)
    fun testRepairWithoutRestartControlRestoresIndependentWorkspace() = checkMalformedRestartRepair(restart = false)
    fun testRepairAfterDependencyRefreshRestoresIndependentWorkspace() = checkMalformedRestartRepair(dependencyRefresh = true)
    fun testDisabledModeDoesNotRecoverNestedConfig() = checkMalformedRestartRepair(mode = ConfigurationMode.DISABLED)
    fun testManualModeDoesNotRecoverNestedConfig() = checkMalformedRestartRepair(mode = ConfigurationMode.MANUAL)

    fun testNestedRepairPreservesAnotherProjectServer() {
        exercise(parentFirst = true)
        FileDocumentManager.getInstance().saveAllDocuments()
        com.intellij.ide.bookmarks.BookmarkManager.getInstance(project)
        val factory = com.intellij.testFramework.fixtures.IdeaTestFixtureFactory.getFixtureFactory()
        val builder = factory.createFixtureBuilder("${name}-other-project")
        val otherFixture = factory.createCodeInsightFixture(builder.fixture)
        builder.addModule(com.intellij.testFramework.builders.EmptyModuleFixtureBuilder::class.java)
            .addSourceContentRoot(otherFixture.tempDirPath)
        otherFixture.setUp()
        try {
            val otherProject = otherFixture.project
            configureLocalNodeForRuntimeTests(otherProject, otherFixture.testRootDisposable)
            otherFixture.testDataPath = myFixture.testDataPath
            val otherRoot = otherFixture.tempDirFixture.findOrCreateDir(".")
            TestNpmPackageInstaller(otherFixture).installForTest(javaClass, otherRoot)
            otherFixture.tempDirFixture.createFile("biome.json", "{}")
            val other = otherFixture.tempDirFixture.createFile("other.js", "const other=1;\n")
            otherFixture.configureFromExistingVirtualFile(other)
            waitUntilFileOpenedByLspServer(otherProject, other, timeout = 20)
            val otherManager = LspServerManager.getInstance(otherProject)
            val original = otherManager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
            checkMalformedRestartRepair(established = true)
            assertEquals(listOf(original), otherManager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList())
            val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
            try {
                val operation = operationScope.async {
                    withTimeout(15_000) { otherProject.service<BiomeServerService>().format(otherFixture.editor.document) }
                }
                PlatformTestUtil.waitWithEventsDispatching("Other project became unresponsive", { operation.isCompleted }, 20)
                runBlocking { operation.await() }
                assertEquals("const other = 1;\n", otherFixture.editor.document.text)
            } finally { operationScope.cancel() }
        } finally {
            com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
            project.service<BiomeServerService>().stopBiomeServer()
            otherFixture.project.service<BiomeServerService>().stopBiomeServer()
            otherFixture.tearDown()
        }
    }

    fun testNewIndependentChildConfigRecoversUnownedEditor() {
        val config = childFile.parent.toNioPath().resolve("biome.json")
        val valid = Files.readString(config)
        Files.delete(config)
        VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        myFixture.configureFromExistingVirtualFile(parentFile)
        waitUntilFileOpenedByLspServer(project, parentFile, timeout = 20)
        awaitFirstServerReadiness(parentFile)
        myFixture.configureFromExistingVirtualFile(childFile)
        waitUntilFileOpenedByLspServer(project, childFile, timeout = 20)
        val editorManager = FileEditorManager.getInstance(project)
        val editors = editorManager.getEditors(childFile).toList()
        val manager = LspServerManager.getInstance(project)
        val original = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
        Files.writeString(config, valid)
        VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        PlatformTestUtil.waitWithEventsDispatching("Independent config must recover the now-unowned child editor", {
            manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).any {
                it !== original && it.descriptor.roots.single() == childFile.parent &&
                    it.initializeResult?.serverInfo?.version == "2.5.15"
            }
        }, 20)
        events.awaitDiagnostics(childFile, "2.5.15")
        formatExistingDocumentAndAssert(childFile, "const message = 'child';\n")
        assertEquals(editors, editorManager.getEditors(childFile).toList())
        val current = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList()
        assertEquals(2, current.size)
        assertEquals(setOf(childFile.parent), opened.filter { it.first in current && it.second == childFile }
            .flatMap { it.first.descriptor.roots.toList() }.toSet())
    }

    private fun checkMalformedRestartRepair(
        restart: Boolean = true,
        parentFirst: Boolean = true,
        dependencyRefresh: Boolean = false,
        mode: ConfigurationMode = ConfigurationMode.AUTOMATIC,
        established: Boolean = false,
    ) {
        if (!established) exercise(parentFirst)
        orderOpenFiles(parentFirst)
        val manager = LspServerManager.getInstance(project)
        val editorManager = FileEditorManager.getInstance(project)
        val editors = editorManager.getEditors(childFile).toList()
        val original = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList()
        val config = childFile.parent.toNioPath().resolve("biome.json")
        val valid = Files.readString(config)
        Files.writeString(config, "{broken")
        VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        if (restart) {
            if (dependencyRefresh) installParentUpgrade()
            else project.service<BiomeServerService>().restartBiomeServer()
            PlatformTestUtil.waitWithEventsDispatching("Restart did not complete while child config malformed", {
                val current = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
                current.size == 1 && current.none { it in original } &&
                    current.single().initializeResult?.serverInfo?.version != null &&
                    (!dependencyRefresh || current.single().initializeResult?.serverInfo?.version == "2.5.15")
            }, 20)
        }
        val beforeRepair = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList()
        val freshDiagnostics = CopyOnWriteArrayList<Pair<LspServer, VirtualFile>>()
        manager.addLspServerManagerListener(object : LspServerManagerListener {
            override fun diagnosticsReceived(lspServer: LspServer, file: VirtualFile) {
                freshDiagnostics.add(lspServer to file)
            }
        }, testRootDisposable, false)
        BiomeSettings.getInstance(project).configurationMode = mode
        // Separate VFS events must not schedule duplicate replacements, including before startup completes.
        repeat(5) {
            Files.writeString(config, valid + " ".repeat(it))
            VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        }
        if (mode != ConfigurationMode.AUTOMATIC) {
            settleServers(beforeRepair)
            assertEquals(editors, editorManager.getEditors(childFile).toList())
            return
        }
        PlatformTestUtil.waitWithEventsDispatching("Repaired child config did not restore independent child ownership without reopening", {
            val current = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
            current.size == 2 && current.all { it.initializeResult?.serverInfo?.version != null } &&
                current.any { it.descriptor.roots.toList() == listOf(childFile.parent) &&
                it.initializeResult?.serverInfo?.version == "2.5.15" && it.descriptor.isSupportedFile(childFile) }
        }, 20)
        val repaired = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList()
        assertEquals("Repair may not reopen editor", editors, editorManager.getEditors(childFile).toList())
        assertEquals(if (dependencyRefresh) setOf("2.5.15") else setOf("2.5.14", "2.5.15"),
            repaired.map { it.initializeResult?.serverInfo?.version }.toSet())
        if (!restart) assertEquals("An established owner must retain its identity", original, repaired)
        else {
            for (file in listOf(parentFile, childFile)) {
                PlatformTestUtil.waitWithEventsDispatching("Recovered workspace did not open and diagnose ${file.path}", {
                    repaired.any { server -> opened.any { it.first === server && it.second == file } &&
                        freshDiagnostics.any { it.first === server && it.second == file } }
                }, 20)
            }
            assertEquals("Only the child server may open the repaired child", setOf(childFile.parent),
                opened.filter { it.first in repaired && it.second == childFile }.flatMap { it.first.descriptor.roots.toList() }.toSet())
        }
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(childFile)!!.setText("const message=\"child\";\n")
            FileDocumentManager.getInstance().getDocument(parentFile)!!.setText("const message='parent';\n")
        }
        formatExistingDocumentAndAssert(childFile, "const message = 'child';\n")
        formatExistingDocumentAndAssert(parentFile, "const message = \"parent\";\n")
        repeat(3) {
            Files.writeString(config, valid + " ".repeat(it + 6))
            VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        }
        settleServers(repaired)
        assertEquals(editors, editorManager.getEditors(childFile).toList())
        assertEquals("Repair bursts must not create extra server instances",
            (original + beforeRepair + repaired).toSet(), observedServers.toSet())
    }

    private fun settleServers(expected: List<LspServer>) {
        val deadline = System.nanoTime() + 2_000_000_000L
        PlatformTestUtil.waitWithEventsDispatching("Config events did not settle", {
            assertEquals(expected, LspServerManager.getInstance(project)
                .getServersForProvider(BiomeLspServerSupportProvider::class.java).toList())
            System.nanoTime() >= deadline
        }, 5)
    }

    private fun installParentUpgrade() {
        val root = parentFile.parent.toNioPath()
        val fixture = java.nio.file.Path.of(myFixture.testDataPath, "_package-locks-store", "_biomejs_biome_2_5_15")
        for (name in listOf("package.json", "pnpm-lock.yaml")) {
            Files.copy(fixture.resolve(name), root.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val output = Files.createTempFile("biome-nested-upgrade-", ".log")
        try {
            val installer = ProcessBuilder("corepack", "pnpm", "install", "--frozen-lockfile")
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                assertTrue("pnpm installation timed out", installer.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(Files.readString(output), 0, installer.exitValue())
            } finally { if (installer.isAlive) installer.destroyForcibly() }
        } finally { Files.deleteIfExists(output) }
        VfsUtil.markDirtyAndRefresh(false, true, true, parentFile.parent)
    }

    fun testParentThenChildServiceFormattingUsesChildServer() { exercise(parentFirst = true) }
    fun testChildThenParentServiceFormattingUsesChildServer() { exercise(parentFirst = false) }
    fun testParentThenChildIdeFormattingUsesChildServer() { exercise(parentFirst = true, throughIde = true) }
    fun testChildThenParentIdeFormattingUsesChildServer() { exercise(parentFirst = false, throughIde = true) }

    fun testParentFirstPublicRestartRestoresBothWorkspaces() {
        verifyPublicRestart(parentFirst = true)
    }

    fun testChildFirstPublicRestartRestoresBothWorkspaces() {
        verifyPublicRestart(parentFirst = false)
    }

    private fun orderOpenFiles(parentFirst: Boolean) {
        val editorManager = FileEditorManager.getInstance(project)
        // The SDK's test editor manager returns HashMap key order, not tab order.
        // Choose real fixture files until its public openFiles array has the desired order;
        // all setup reopens finish before capturing identities or measuring Restart.
        FileDocumentManager.getInstance().saveAllDocuments()
        var ordered = false
        for (attempt in 0 until 32) {
            editorManager.closeFile(parentFile)
            editorManager.closeFile(childFile)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (attempt > 0) childFile = myFixture.tempDirFixture.createFile(
                "nested/restart-child-$attempt.js", "const message=\"child\";\n")
            for (file in if (parentFirst) listOf(parentFile, childFile) else listOf(childFile, parentFile)) {
                editorManager.openFile(file, true)
                waitUntilFileOpenedByLspServer(project, file, timeout = 20)
            }
            val order = editorManager.openFiles.toList()
            ordered = (order.indexOf(parentFile) < order.indexOf(childFile)) == parentFirst
            if (ordered) {
                println("Restart open-file order: " + order.map { it.path })
                break
            }
        }
        assertTrue("SDK test editor manager must expose the requested restart order", ordered)
    }

    private fun verifyPublicRestart(parentFirst: Boolean) {
        exercise(parentFirst)
        val manager = LspServerManager.getInstance(project)
        val editorManager = FileEditorManager.getInstance(project)
        orderOpenFiles(parentFirst)
        events.awaitDiagnostics(childFile)
        val original = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList()
        val childEditors = editorManager.getEditors(childFile).toList()
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(parentFile)!!.setText("const message='parent';\n")
            FileDocumentManager.getInstance().getDocument(childFile)!!.setText("const message=\"child\";\n")
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        val freshDiagnostics = CopyOnWriteArrayList<Pair<LspServer, VirtualFile>>()
        manager.addLspServerManagerListener(object : LspServerManagerListener {
            override fun diagnosticsReceived(lspServer: LspServer, file: VirtualFile) {
                if (lspServer.providerClass == BiomeLspServerSupportProvider::class.java) {
                    freshDiagnostics.add(lspServer to file)
                }
            }
        }, testRootDisposable, false)
        project.service<BiomeServerService>().restartBiomeServer()
        PlatformTestUtil.waitWithEventsDispatching("Public Restart did not restore both independent workspaces", {
            val current = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
            current.size == 2 && current.none { it in original } &&
                current.all { it.initializeResult?.serverInfo?.version != null }
        }, 20)
        val restarted = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toList()
        assertEquals(setOf("2.5.14", "2.5.15"), restarted.map { it.initializeResult?.serverInfo?.version }.toSet())
        assertEquals("Restart must retain the child's editor instances", childEditors, editorManager.getEditors(childFile).toList())
        for (file in listOf(parentFile, childFile)) {
            PlatformTestUtil.waitWithEventsDispatching("Replacement server did not open and diagnose ${file.path}", {
                restarted.any { server -> opened.any { it.first === server && it.second == file } &&
                    freshDiagnostics.any { it.first === server && it.second == file } }
            }, 20)
        }
        assertEquals(setOf(childFile.parent), opened.filter { it.first in restarted && it.second == childFile }
            .flatMap { it.first.descriptor.roots.toList() }.toSet())
        formatExistingDocumentAndAssert(childFile, "const message = 'child';\n")
        formatExistingDocumentAndAssert(parentFile, "const message = \"parent\";\n")
        assertEquals(childEditors, editorManager.getEditors(childFile).toList())
        assertEquals("Only the two replacement servers may be started", (original + restarted).toSet(), observedServers.toSet())
    }

    fun testMalformedExistingRootDoesNotPermanentlyRejectNewFile() {
        verifyEstablishedRootOwnership(removeConfig = false, nonRootChild = false)
    }

    fun testMissingExistingRootRetainsNonRootChildOwnership() {
        verifyEstablishedRootOwnership(removeConfig = true, nonRootChild = true)
    }

    private fun verifyEstablishedRootOwnership(removeConfig: Boolean, nonRootChild: Boolean) {
        myFixture.configureFromExistingVirtualFile(parentFile)
        waitUntilFileOpenedByLspServer(project, parentFile, timeout = 20)
        awaitFirstServerReadiness(parentFile)
        val original = LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
        val rootConfig = parentFile.parent.toNioPath().resolve("biome.json")
        val validConfig = Files.readString(rootConfig)
        if (removeConfig) Files.delete(rootConfig) else Files.writeString(rootConfig, "{broken")
        VfsUtil.markDirtyAndRefresh(false, true, true, parentFile.parent)
        val latePath = if (nonRootChild) {
            myFixture.tempDirFixture.createFile("non-root/biome.json", """{"root":false,"extends":"//"}""")
            "non-root/late.js"
        } else "late.js"
        val lateFile = myFixture.tempDirFixture.createFile(latePath, "const message='late';\n")
        myFixture.configureFromExistingVirtualFile(lateFile)
        waitUntilFileOpenedByLspServer(project, lateFile, timeout = 15)
        Files.writeString(rootConfig, validConfig)
        VfsUtil.markDirtyAndRefresh(false, true, true, parentFile.parent)
        FileEditorManager.getInstance(project).closeFile(lateFile)
        myFixture.configureFromExistingVirtualFile(lateFile)
        waitUntilFileOpenedByLspServer(project, lateFile, timeout = 15)
        events.awaitDiagnostics(lateFile)
        formatAndAssert(lateFile, "const message = \"late\";\n")
        assertSame(original, LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java).single())
    }

    fun testMalformedEstablishedChildKeepsExclusiveOwnership() {
        exercise(parentFirst = true)
        val config = childFile.parent.toNioPath().resolve("biome.json")
        val validConfig = Files.readString(config)
        Files.writeString(config, "{broken")
        VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        val lateFile = myFixture.tempDirFixture.createFile("nested/late.js", "const message=\"late\";\n")
        myFixture.configureFromExistingVirtualFile(lateFile)
        waitUntilFileOpenedByLspServer(project, lateFile, timeout = 15)
        val owners = LspServerManager.getInstance(project)
            .getServersForProvider(BiomeLspServerSupportProvider::class.java)
            .filter { it.descriptor.isSupportedFile(lateFile) }
        assertEquals(setOf(childFile.parent), owners.flatMap { it.descriptor.roots.toList() }.toSet())
        assertEquals("Temporary child config errors must not transfer ownership to its parent",
            setOf(childFile.parent), opened.filter { it.second == lateFile }.flatMap { it.first.descriptor.roots.toList() }.toSet())
        Files.writeString(config, validConfig)
        VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        FileEditorManager.getInstance(project).closeFile(lateFile)
        myFixture.configureFromExistingVirtualFile(lateFile)
        waitUntilFileOpenedByLspServer(project, lateFile, timeout = 15)
        events.awaitDiagnostics(lateFile)
        formatAndAssert(lateFile, "const message = 'late';\n")
    }

    fun testExplicitManualConfigRetainsProjectWideOwnership() {
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            configPath = parentFile.parent.path
            executablePath = parentFile.parent.toNioPath().resolve("node_modules/.bin/biome").toString()
        }
        myFixture.configureFromExistingVirtualFile(childFile)
        waitUntilFileOpenedByLspServer(project, childFile, timeout = 20)
        val servers = LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
        assertEquals(1, servers.size)
        assertEquals(listOf(parentFile.parent), servers.single().descriptor.roots.toList())
        assertTrue(servers.single().descriptor.isSupportedFile(parentFile))
        assertTrue(servers.single().descriptor.isSupportedFile(childFile))
        verifySingleWorkspaceRestart("const message='child';\n", "const message = \"child\";\n")
    }

    fun testNestedNonRootConfigRemainsInParentWorkspace() {
        Files.writeString(childFile.parent.toNioPath().resolve("biome.json"),
            """{"root":false,"extends":"//","javascript":{"formatter":{"quoteStyle":"single"}}}""")
        VfsUtil.markDirtyAndRefresh(false, true, true, childFile.parent)
        myFixture.configureFromExistingVirtualFile(childFile)
        waitUntilFileOpenedByLspServer(project, childFile, timeout = 20)
        events.awaitDiagnostics(childFile)
        formatAndAssert(childFile, "const message = 'child';\n")
        val servers = LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
        assertEquals(1, servers.size)
        assertEquals(listOf(parentFile.parent), servers.single().descriptor.roots.toList())
        assertEquals(setOf(parentFile.parent), opened.filter { it.second == childFile }.flatMap { it.first.descriptor.roots.toList() }.toSet())
        verifySingleWorkspaceRestart("const message=\"child\";\n", "const message = 'child';\n")
    }

    private fun verifySingleWorkspaceRestart(input: String, expected: String) {
        val manager = LspServerManager.getInstance(project)
        val original = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
        val editorManager = FileEditorManager.getInstance(project)
        val editors = editorManager.getEditors(childFile).toList()
        val document = FileDocumentManager.getInstance().getDocument(childFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(input) }
        FileDocumentManager.getInstance().saveDocument(document)
        val diagnosed = CopyOnWriteArrayList<LspServer>()
        manager.addLspServerManagerListener(object : LspServerManagerListener {
            override fun diagnosticsReceived(lspServer: LspServer, file: VirtualFile) {
                if (file == childFile && lspServer !== original) diagnosed.add(lspServer)
            }
        }, testRootDisposable, false)
        project.service<BiomeServerService>().restartBiomeServer()
        PlatformTestUtil.waitWithEventsDispatching("Single workspace restart did not restore the existing editor", {
            val current = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
            current.size == 1 && current.single() !== original && current.single() in diagnosed
        }, 20)
        val restarted = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
        assertEquals(listOf(parentFile.parent), restarted.descriptor.roots.toList())
        assertEquals(editors, editorManager.getEditors(childFile).toList())
        formatExistingDocumentAndAssert(childFile, expected)
        assertEquals(editors, editorManager.getEditors(childFile).toList())
        assertEquals(setOf(original, restarted), observedServers.toSet())
    }

    private fun exercise(parentFirst: Boolean, throughIde: Boolean = false) {
        val order = if (parentFirst) listOf(parentFile, childFile) else listOf(childFile, parentFile)
        order.forEachIndexed { index, file ->
            myFixture.configureFromExistingVirtualFile(file)
            waitUntilFileOpenedByLspServer(project, file, timeout = 20)
            if (index == 0) {
                // Stabilize only the first server, before opening the other root. Never reopen
                // the second file to force discovery or change its measured ownership.
                awaitFirstServerReadiness(file)
            } else {
                events.awaitDiagnostics(file)
            }
        }
        val servers = LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)
        println("Nested ownership: " + servers.joinToString { "${it.descriptor.roots.toList()} version=${it.initializeResult?.serverInfo?.version}" })
        println("Documents opened: " + opened.joinToString { "${it.second.path} -> ${it.first.descriptor.roots.toList()}" })
        formatAndAssert(childFile, "const message = 'child';\n", throughIde)
        formatAndAssert(parentFile, "const message = \"parent\";\n", throughIde)
        assertEquals("Both independent roots must have a server", 2, servers.size)
        assertEquals(setOf("2.5.14", "2.5.15"), servers.map { it.initializeResult?.serverInfo?.version }.toSet())
        assertEquals("Nested document must belong to its own root", setOf(childFile.parent),
            opened.filter { it.second == childFile }.flatMap { it.first.descriptor.roots.toList() }.toSet())
    }

    private fun awaitFirstServerReadiness(file: VirtualFile) {
        try {
            events.awaitDiagnostics(file, timeout = 5)
        } catch (_: AssertionError) {
            println("Initial readiness control: reopening first file ${file.path}")
            FileEditorManager.getInstance(project).closeFile(file)
            myFixture.configureFromExistingVirtualFile(file)
            waitUntilFileOpenedByLspServer(project, file, timeout = 20)
            events.awaitDiagnostics(file)
        }
    }

    private fun formatAndAssert(file: VirtualFile, expected: String, throughIde: Boolean = false) {
        myFixture.configureFromExistingVirtualFile(file)
        if (throughIde) {
            BiomeSettings.getInstance(project).enableLspFormat = true
            myFixture.performEditorAction("ReformatCode")
            PlatformTestUtil.waitWithEventsDispatching("IDE formatting did not produce expected complete output", { myFixture.editor.document.text == expected }, 20)
            assertEquals(expected, myFixture.editor.document.text)
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val operation = scope.async { withTimeout(15_000) { project.service<BiomeServerService>().format(myFixture.editor.document) } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Formatting timed out", { operation.isCompleted }, 20)
            runBlocking { operation.await() }
            assertEquals(expected, myFixture.editor.document.text)
        } finally { scope.cancel() }
    }

    private fun formatExistingDocumentAndAssert(file: VirtualFile, expected: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val operation = scope.async { withTimeout(15_000) { project.service<BiomeServerService>().format(document) } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Existing editor formatting timed out", { operation.isCompleted }, 20)
            runBlocking { operation.await() }
            assertEquals(expected, document.text)
        } finally { scope.cancel() }
    }

    @TestNpmPackage("@biomejs/biome@2.5.15")
    private class CurrentBiome
}
