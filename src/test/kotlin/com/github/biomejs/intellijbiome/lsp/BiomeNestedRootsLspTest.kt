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

    private fun verifyPublicRestart(parentFirst: Boolean) {
        exercise(parentFirst)
        val manager = LspServerManager.getInstance(project)
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
