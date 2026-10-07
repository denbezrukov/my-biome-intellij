package com.github.biomejs.intellijbiome.lsp

import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files

/** Managed SDK servers with a small protocol peer, isolating install/probe timing from Biome's daemon. */
class BiomeDependencyRefreshLifecycleTest : BiomeLspFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        configureLocalNodeForRuntimeTests(project, testRootDisposable)
    }

    fun testReopenedIdleRootIsVerifiedBeforeProjectRestart() {
        val first = createRoot("first")
        val second = createRoot("second")
        val originalFirst = open(first)
        val originalSecond = open(second)
        FileEditorManager.getInstance(project).closeFile(second.source)
        second.write("broken", "1")
        holdUpgradeProbe(first)
        myFixture.configureFromExistingVirtualFile(second.source)
        first.delete("hold")
        settleUnchanged(listOf(originalFirst, originalSecond))
    }

    fun testClosedFailedRootDoesNotBlockHealthyRootUpgrade() {
        val first = createRoot("first")
        val second = createRoot("second")
        val original = open(first)
        second.write("fail-start", "1")
        myFixture.configureFromExistingVirtualFile(second.source)
        PlatformTestUtil.waitWithEventsDispatching("Sibling did not fail initialization", {
            servers().any { it.descriptor.roots.single() == second.directory && it.state == LspServerState.ShutdownUnexpectedly }
        }, 10)
        FileEditorManager.getInstance(project).closeFile(second.source)
        first.write("version.txt", "2.5.15")
        dependencyEvent(first)
        awaitVersion(first, "2.5.15", original)
    }

    fun testInterpreterChangeDuringProbePreservesWorkingServer() {
        val root = createRoot("first")
        val original = open(root)
        val manager = NodeJsInterpreterManager.getInstance(project)
        val selected = manager.interpreterRef
        try {
            holdUpgradeProbe(root)
            manager.setInterpreterRef(NodeJsInterpreterRef.create("unavailable-interpreter"))
            root.delete("hold")
            settleUnchanged(listOf(original))
        } finally { manager.setInterpreterRef(selected) }
    }

    fun testSupersedingInstallEventInvalidatesSuccessfulOlderProbeImmediately() {
        val root = createRoot("first")
        val original = open(root)
        holdUpgradeProbe(root)
        // The held probe already captured a successful B result; the latest install is now invalid.
        root.write("broken", "1")
        dependencyEvent(root)
        root.delete("hold")
        settleUnchanged(listOf(original))
    }

    fun testUnchangedPrereleaseDoesNotRestart() {
        val root = createRoot("first", "2.6.0-nightly.20261007+build.7")
        val original = open(root)
        dependencyEvent(root)
        settleUnchanged(listOf(original))
    }

    fun testChangedPrereleaseIsAdoptedOnce() {
        val root = createRoot("first", "2.6.0-nightly.20261006")
        val original = open(root)
        root.write("version.txt", "2.6.0-nightly.20261007")
        dependencyEvent(root)
        val replacement = awaitVersion(root, "2.6.0-nightly.20261007", original)
        dependencyEvent(root)
        settleUnchanged(listOf(replacement))
    }

    private fun createRoot(name: String, version: String = "2.2.3"): Root {
        myFixture.tempDirFixture.createFile("$name/biome.json", "{}")
        myFixture.tempDirFixture.createFile("$name/package.json", """{"name":"$name","dependencies":{"@biomejs/biome":"2.2.3"}}""")
        myFixture.tempDirFixture.createFile("$name/node_modules/@biomejs/biome/package.json", """{"name":"@biomejs/biome","version":"2.2.3","bin":{"biome":"bin/biome"}}""")
        myFixture.tempDirFixture.createFile("$name/node_modules/@biomejs/biome/bin/biome", peer)
        myFixture.tempDirFixture.createFile("$name/version.txt", version)
        val source = myFixture.tempDirFixture.createFile("$name/index.js", "const value=1;\n")
        return Root(source.parent, source)
    }

    private fun open(root: Root): LspServer {
        myFixture.configureFromExistingVirtualFile(root.source)
        waitUntilFileOpenedByLspServer(project, root.source, timeout = 15)
        return servers().single { it.descriptor.roots.single() == root.directory }.also {
            assertEquals(Files.readString(root.directory.toNioPath().resolve("version.txt")), it.initializeResult?.serverInfo?.version)
        }
    }

    private fun holdUpgradeProbe(root: Root) {
        root.write("version.txt", "2.5.15")
        root.write("hold", "1")
        dependencyEvent(root)
        PlatformTestUtil.waitWithEventsDispatching("Upgrade probe never reached its hold", {
            Files.exists(root.directory.toNioPath().resolve("probe-started"))
        }, 4)
    }

    private fun dependencyEvent(root: Root) {
        val manifest = root.directory.findChild("package.json")!!
        WriteAction.run<RuntimeException> { manifest.setBinaryContent(manifest.contentsToByteArray() + byteArrayOf(32)) }
    }

    private fun awaitVersion(root: Root, version: String, original: LspServer): LspServer {
        PlatformTestUtil.waitWithEventsDispatching("Open root did not adopt $version", {
            servers().any { it !== original && it.descriptor.roots.single() == root.directory && it.state == LspServerState.Running && it.initializeResult?.serverInfo?.version == version }
        }, 15)
        return servers().single { it.descriptor.roots.single() == root.directory }
    }

    private fun settleUnchanged(original: List<LspServer>) {
        val deadline = System.nanoTime() + 2_000_000_000L
        PlatformTestUtil.waitWithEventsDispatching("Dependency events did not settle", {
            assertEquals(original.toSet(), servers().toSet())
            original.forEach { assertEquals(LspServerState.Running, it.state) }
            System.nanoTime() >= deadline
        }, 5)
    }

    private fun servers() = LspServerManager.getInstance(project).getServersForProvider(BiomeLspServerSupportProvider::class.java)

    private data class Root(val directory: VirtualFile, val source: VirtualFile) {
        fun write(name: String, value: String) { Files.writeString(directory.toNioPath().resolve(name), value) }
        fun delete(name: String) { Files.delete(directory.toNioPath().resolve(name)) }
    }

    companion object {
        private val peer = """
            const fs = require('node:fs');
            const version = fs.readFileSync('version.txt', 'utf8').trim();
            if (process.argv.includes('--version')) {
              if (fs.existsSync('broken')) process.exit(42);
              const answer = 'Version: ' + version + '\n';
              if (fs.existsSync('hold')) {
                fs.writeFileSync('probe-started', '1');
                const timer = setInterval(() => {
                  if (!fs.existsSync('hold')) { clearInterval(timer); process.stdout.write(answer); }
                }, 10);
              } else process.stdout.write(answer);
            } else {
              if (fs.existsSync('broken')) process.exit(42);
              let pending = Buffer.alloc(0);
              const reply = (id, result, error) => {
                const body = JSON.stringify({jsonrpc:'2.0', id, result, error});
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
                  if (message.method === 'initialize' && fs.existsSync('fail-start')) reply(message.id, undefined, {code:-32603, message:'Fixture rejects initialization'});
                  else if (message.method === 'initialize') reply(message.id, {capabilities:{textDocumentSync:1}, serverInfo:{name:'Biome fixture', version}});
                  else if (message.method === 'exit') process.exit(0);
                  else if (message.id !== undefined) reply(message.id, null);
                }
              });
            }
        """.trimIndent()
    }
}
