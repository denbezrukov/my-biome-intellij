package com.github.biomejs.intellijbiome.lsp

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import java.nio.file.Files

/** Real SDK routing with a protocol peer controlling initialization independently of config recovery. */
class BiomeConfigDiscoveryRoutingTest : BiomeLspFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
    }

    fun testIndependentRecoveryDoesNotWaitForUnrelatedInitialization() {
        val stalled = createRoot("stalled", configured = true)
        val pending = createRoot("pending", configured = false)
        val hold = stalled.directory.toNioPath().resolve("hold-initialize")
        Files.writeString(hold, "hold")
        try {
            myFixture.configureFromExistingVirtualFile(stalled.source)
            PlatformTestUtil.waitWithEventsDispatching("The unrelated server never reached initialize", {
                Files.exists(stalled.directory.toNioPath().resolve("initialize-started"))
            }, 10)
            val original = servers().single()
            assertEquals(LspServerState.Initializing, original.state)
            myFixture.configureFromExistingVirtualFile(pending.source)
            val editor = myFixture.editor
            settleServers(listOf(original))
            assertEquals(LspServerState.Initializing, original.state)

            externalConfigs(pending.directory)
            val recovered = awaitOpened(pending)
            assertSame("Recovery must retain the already open editor", editor, myFixture.editor)
            assertTrue("The unrelated initializing server must retain its identity", servers().any { it === original })
            assertEquals("Independent recovery must finish before the unrelated initialization is released",
                LspServerState.Initializing, original.state)
            assertEquals(setOf(original, recovered), servers().toSet())
        } finally {
            Files.deleteIfExists(hold)
        }
    }

    fun testExcludedOpenFileDoesNotRestartWorkingRootOnConfigEvents() {
        val parent = createRoot("parent", configured = true)
        val firstPending = createRoot("first-pending", configured = false)
        val secondPending = createRoot("second-pending", configured = false)
        val excluded = myFixture.tempDirFixture.createFile("parent/excluded/index.js", "const excluded=1;\n")
        PsiTestUtil.addExcludedRoot(myFixture.module, excluded.parent)
        assertFalse("The regression must use a file the SDK excludes from discovery",
            ProjectFileIndex.getInstance(project).isInContent(excluded))
        myFixture.configureFromExistingVirtualFile(parent.source)
        val original = awaitOpened(parent)
        myFixture.configureFromExistingVirtualFile(excluded)
        myFixture.configureFromExistingVirtualFile(firstPending.source)
        myFixture.configureFromExistingVirtualFile(secondPending.source)
        assertTrue(FileEditorManager.getInstance(project).isFileOpen(excluded))
        settleServers(listOf(original))

        externalConfigs(excluded.parent, firstPending.directory)
        val first = awaitOpened(firstPending)
        assertSame("Creating a config for an excluded open file must preserve the working parent",
            original, servers().single { it.descriptor.roots.single() == parent.directory })
        assertEquals(setOf(original, first), servers().toSet())

        // A second eligible recovery is a completion signal for a later config edit too.
        externalConfigs(excluded.parent, secondPending.directory)
        val second = awaitOpened(secondPending)
        assertEquals("Editing the excluded config must not restart any working root",
            setOf(original, first, second), servers().toSet())
        assertEquals(LspServerState.Running, original.state)
        assertFalse("Excluded documents must never reach the parent's protocol peer",
            Files.readString(parent.directory.toNioPath().resolve("opened.txt")).contains(excluded.url))
    }

    fun testExcludingOpenFileInvalidatesQueuedRecovery() {
        val parent = createRoot("parent", configured = true)
        val child = createRoot("parent/child", configured = false)
        val dispatcher = QueuedDiscoveryDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        Disposer.register(testRootDisposable) {
            scope.cancel()
            dispatcher.drainCancelledTasks()
        }
        project.replaceService(BiomeConfigDiscoveryService::class.java,
            BiomeConfigDiscoveryService(project, scope), testRootDisposable)
        myFixture.configureFromExistingVirtualFile(parent.source)
        val original = awaitOpened(parent)
        myFixture.configureFromExistingVirtualFile(child.source)
        waitUntilFileOpenedByLspServer(project, child.source, timeout = 10)
        assertTrue(ProjectFileIndex.getInstance(project).isInContent(child.source))
        externalConfigs(child.directory)
        assertFalse(original.descriptor.isSupportedFile(child.source))
        dispatcher.runPendingReads(scope, expectEdt = true)
        // Change SDK routing eligibility after the read, without a VFS/editor change.
        val vfsCount = VirtualFileManager.getInstance().modificationCount
        val rootCount = ProjectRootManager.getInstance(project).modificationCount
        PsiTestUtil.addExcludedRoot(myFixture.module, child.directory)
        assertEquals(vfsCount, VirtualFileManager.getInstance().modificationCount)
        assertTrue(ProjectRootManager.getInstance(project).modificationCount > rootCount)
        assertTrue(FileEditorManager.getInstance(project).isFileOpen(child.source))
        assertFalse(ProjectFileIndex.getInstance(project).isInContent(child.source))
        PlatformTestUtil.waitWithEventsDispatching("Queued recovery did not recheck the excluded editor", {
            assertEquals("An exclusion after discovery must invalidate the queued restart", listOf(original), servers().toList())
            assertEquals(LspServerState.Running, original.state)
            dispatcher.runNextPendingTask()
            scope.coroutineContext.job.children.none { it.isActive }
        }, 10)
    }

    private fun createRoot(name: String, configured: Boolean): Root {
        if (configured) myFixture.tempDirFixture.createFile("$name/biome.json", "{}")
        myFixture.tempDirFixture.createFile("$name/package.json",
            """{"name":"$name","dependencies":{"@biomejs/biome":"2.5.15"}}""")
        myFixture.tempDirFixture.createFile("$name/node_modules/@biomejs/biome/package.json",
            """{"name":"@biomejs/biome","version":"2.5.15","bin":{"biome":"bin/biome"}}""")
        myFixture.tempDirFixture.createFile("$name/node_modules/@biomejs/biome/bin/biome", peer)
        val source = myFixture.tempDirFixture.createFile("$name/index.js", "const value=1;\n")
        return Root(source.parent, source)
    }

    private fun externalConfigs(vararg directories: VirtualFile) {
        directories.forEach { directory ->
            val config = directory.toNioPath().resolve("biome.json")
            Files.writeString(config, if (Files.exists(config)) Files.readString(config) + " " else "{}")
        }
        VfsUtil.markDirtyAndRefresh(false, true, true, *directories)
    }

    private fun awaitOpened(root: Root): LspServer {
        PlatformTestUtil.waitWithEventsDispatching("Config recovery did not route ${root.source.path} to its own server", {
            servers().any { it.descriptor.roots.single() == root.directory && it.state == LspServerState.Running } &&
                Files.exists(root.directory.toNioPath().resolve("opened.txt"))
        }, 10)
        val server = servers().single { it.descriptor.roots.single() == root.directory }
        assertEquals("2.5.15", server.initializeResult?.serverInfo?.version)
        assertTrue(Files.readString(root.directory.toNioPath().resolve("opened.txt")).contains(root.source.name))
        return server
    }

    private fun settleServers(expected: List<LspServer>) {
        // Let real editor callbacks discover that these open files still have no config.
        // Otherwise an in-flight initial open could observe the later config and mask recovery.
        val deadline = System.nanoTime() + 2_000_000_000L
        PlatformTestUtil.waitWithEventsDispatching("Initial editor discovery did not settle", {
            assertEquals(expected.toSet(), servers().toSet())
            System.nanoTime() >= deadline
        }, 5)
    }

    private fun servers() = LspServerManager.getInstance(project)
        .getServersForProvider(BiomeLspServerSupportProvider::class.java)

    private data class Root(val directory: VirtualFile, val source: VirtualFile)

    companion object {
        private val peer = """
            const fs = require('node:fs');
            if (process.argv.includes('--version')) console.log('Version: 2.5.15');
            else {
              let pending = Buffer.alloc(0);
              const send = message => {
                const body = JSON.stringify({jsonrpc:'2.0', ...message});
                process.stdout.write('Content-Length: ' + Buffer.byteLength(body) + '\r\n\r\n' + body);
              };
              process.stdin.on('data', data => {
                pending = Buffer.concat([pending, data]);
                while (true) {
                  const headerEnd = pending.indexOf('\r\n\r\n');
                  if (headerEnd < 0) return;
                  const size = Number(/Content-Length: (\d+)/i.exec(pending.subarray(0, headerEnd).toString())[1]);
                  if (pending.length < headerEnd + 4 + size) return;
                  const message = JSON.parse(pending.subarray(headerEnd + 4, headerEnd + 4 + size));
                  pending = pending.subarray(headerEnd + 4 + size);
                  if (message.method === 'initialize') {
                    fs.writeFileSync('initialize-started', '1');
                    const reply = () => send({id:message.id, result:{capabilities:{textDocumentSync:1}, serverInfo:{name:'Biome routing fixture', version:'2.5.15'}}});
                    if (fs.existsSync('hold-initialize')) {
                      const timer = setInterval(() => { if (!fs.existsSync('hold-initialize')) { clearInterval(timer); reply(); } }, 10);
                    } else reply();
                  } else if (message.method === 'textDocument/didOpen') {
                    fs.appendFileSync('opened.txt', message.params.textDocument.uri + '\n');
                    send({method:'textDocument/publishDiagnostics', params:{uri:message.params.textDocument.uri, diagnostics:[]}});
                  } else if (message.method === 'exit') process.exit(0);
                  else if (message.id !== undefined) send({id:message.id, result:null});
                }
              });
              process.stdin.on('end', () => process.exit(0));
            }
        """.trimIndent()
    }
}
