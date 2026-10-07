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


    fun testUnchangedNestedRootVersionsKeepBothServers() {
        val outer = createRoot("outer", "2.2.3")
        var inner = createRoot("outer/inner", "2.5.15")
        val originalInner = open(inner)
        val originalOuter = open(outer)
        inner = firstOpenFile(inner)
        assertEquals(setOf(originalInner, originalOuter), servers().toSet())
        outer.write("probe-scripts.txt", "")
        inner.write("probe-scripts.txt", "")

        dependencyEvent(outer)
        awaitProbe(outer)
        awaitProbe(inner)
        settleUnchanged(listOf(originalInner, originalOuter))
        assertProbeExecutable(outer, outer)
        assertProbeExecutable(inner, inner)
    }

    fun testClosedParentDoesNotBlockNestedRootUpgrade() {
        val outer = createRoot("outer")
        val inner = createRoot("outer/inner")
        val originalInner = open(inner)
        open(outer)
        FileEditorManager.getInstance(project).closeFile(outer.source)
        outer.write("broken", "1")
        outer.write("probe-scripts.txt", "")
        inner.write("probe-scripts.txt", "")
        inner.write("version.txt", "2.5.15")

        dependencyEvent(inner)
        awaitVersion(inner, "2.5.15", originalInner)
        assertEquals(listOf(inner.directory), servers().map { it.descriptor.roots.single() })
        assertEquals("An idle parent must not be probed for its child's file", "",
            Files.readString(outer.directory.toNioPath().resolve("probe-scripts.txt")))
        assertProbeExecutable(inner, inner)
    }

    fun testMalformedOpenRootWithNonRootFallbackStillBlocksUnsafeRestart() {
        val outer = createRoot("outer")
        myFixture.tempDirFixture.createFile("outer/nested/biome.json", """{"root":false}""")
        val nestedFile = myFixture.tempDirFixture.createFile("outer/nested/index.js", "const value=1;\n")
        val outerWithNestedFile = Root(outer.directory, nestedFile)
        val sibling = createRoot("sibling")
        val originalOuter = open(outerWithNestedFile)
        val originalSibling = open(sibling)
        WriteAction.run<RuntimeException> { outer.directory.findChild("biome.json")!!.setBinaryContent("{".toByteArray()) }
        outer.write("broken", "1")
        sibling.write("version.txt", "2.5.15")

        dependencyEvent(sibling)
        settleUnchanged(listOf(originalOuter, originalSibling))
    }

    fun testNestedPackageWithinOneConfigRootKeepsFileSpecificSelection() {
        val outer = createRoot("outer", "2.2.3")
        val nested = createRoot("outer/nested", "2.5.15")
        WriteAction.run<RuntimeException> { nested.directory.findChild("biome.json")!!.setBinaryContent("""{"root":false}""".toByteArray()) }
        myFixture.configureFromExistingVirtualFile(nested.source)
        waitUntilFileOpenedByLspServer(project, nested.source, timeout = 15)
        val original = servers().single()
        assertEquals(outer.directory, original.descriptor.roots.single())
        assertEquals("2.5.15", original.initializeResult?.serverInfo?.version)
        assertEquals(executable(nested), (original.descriptor as BiomeLspServerDescriptor).executable)
        outer.write("probe-scripts.txt", "")
        nested.write("version.txt", "2.6.0")

        dependencyEvent(outer)
        val replacement = awaitVersion(Root(outer.directory, nested.source), "2.6.0", original)
        assertEquals(executable(nested), (replacement.descriptor as BiomeLspServerDescriptor).executable)
        assertProbeExecutable(outer, nested)
    }


    fun testTwoPackagesInOneRootDoNotChangeStartupSelection() = checkSameRootStartupSelection(closeOrigin = false)

    fun testClosedStartupFileRetainsSameRootPackageSelection() = checkSameRootStartupSelection(closeOrigin = true)

    fun testDeletedStartupFileRetainsPackageDiscoveryContext() = checkSameRootStartupSelection(closeOrigin = true, deleteOrigin = true)

    private fun checkSameRootStartupSelection(closeOrigin: Boolean, deleteOrigin: Boolean = false) {
        val outer = createRoot("outer", "2.2.3")
        var nested = createRoot("outer/nested", "2.5.15")
        WriteAction.run<RuntimeException> { nested.directory.findChild("biome.json")!!.setBinaryContent("""{"root":false}""".toByteArray()) }
        val original = open(outer)
        myFixture.configureFromExistingVirtualFile(nested.source)
        waitUntilFileOpenedByLspServer(project, nested.source, timeout = 15)
        if (closeOrigin) {
            val editors = FileEditorManager.getInstance(project)
            editors.closeFile(outer.source)
            // Only the nested file participates in a restart after the startup file closes.
            // Do not depend on the two-file HashMap order before closing that file.
            assertEquals(listOf(nested.source), editors.openFiles.toList())
        } else {
            nested = firstOpenFile(nested)
        }
        if (deleteOrigin) {
            WriteAction.run<RuntimeException> { outer.source.delete(this) }
            assertFalse(outer.source.isValid)
        }
        assertSame(original, servers().single())
        assertEquals(outer.directory, original.descriptor.roots.single())
        assertEquals(executable(outer), (original.descriptor as BiomeLspServerDescriptor).executable)
        val packages = com.github.biomejs.intellijbiome.BiomePackage(project)
        assertEquals(executable(nested), packages.binaryPath(outer.directory.path, nested.source, false))
        assertEquals("Directory discovery must preserve the startup file's own node_modules selection",
            executable(nested), packages.binaryPath(outer.directory.path, nested.source.parent, false))
        assertEquals(executable(outer), packages.binaryPath(outer.directory.path, outer.directory, false))
        outer.write("probe-scripts.txt", "")

        dependencyEvent(outer)
        awaitProbe(outer)
        settleUnchanged(listOf(original))
        assertProbeExecutable(outer, outer)
        if (deleteOrigin) {
            outer.write("probe-scripts.txt", "")
            outer.write("version.txt", "2.6.0")
            dependencyEvent(outer)
            // The supported restart reconstructs this root from its remaining nested file.
            val replacement = awaitVersion(Root(outer.directory, nested.source), "2.5.15", original)
            assertEquals(executable(nested), (replacement.descriptor as BiomeLspServerDescriptor).executable)
            assertEquals(setOf(executable(outer), executable(nested)),
                Files.readAllLines(outer.directory.toNioPath().resolve("probe-scripts.txt")).toSet())
        }
    }


    fun testBrokenProspectivePackagePreservesServerDuringOriginalUpgrade() {
        val outer = createRoot("outer", "2.2.3")
        var nested = createRoot("outer/nested", "2.5.15")
        WriteAction.run<RuntimeException> { nested.directory.findChild("biome.json")!!.setBinaryContent("""{"root":false}""".toByteArray()) }
        val original = open(outer)
        myFixture.configureFromExistingVirtualFile(nested.source)
        waitUntilFileOpenedByLspServer(project, nested.source, timeout = 15)
        nested = firstOpenFile(nested)
        assertSame(original, servers().single())
        outer.write("probe-scripts.txt", "")
        outer.write("version.txt", "2.6.0")
        // This package would be selected first when the SDK rebuilds from open files.
        // Fail this actual script, independently of the shared outer working directory.
        Files.writeString(nested.directory.toNioPath().resolve("node_modules/@biomejs/biome/bin/biome"), """
            require('node:fs').appendFileSync('probe-scripts.txt', __filename + '\n');
            process.exit(42);
        """.trimIndent())

        dependencyEvent(outer)
        awaitProbe(outer)
        settleUnchanged(listOf(original))
        assertTrue("The prospective executable must be checked before any restart",
            Files.readAllLines(outer.directory.toNioPath().resolve("probe-scripts.txt")).contains(executable(nested)))
    }

    private fun firstOpenFile(root: Root): Root {
        val editors = FileEditorManager.getInstance(project)
        val originalServers = servers().toSet()
        val other = editors.openFiles.single { it != root.source }
        assertEquals(setOf(root.source, other), editors.openFiles.toSet())
        // TestEditorManagerImpl returns a default HashMap's key order. With at most
        // three open files its table has 16 buckets, and VirtualFile hashes are VFS IDs.
        // A batch spanning two bucket cycles covers every bucket even across hash-spread
        // boundaries. Create it in one write action, before editor events allocate other IDs.
        val bucketCount = 16
        fun bucket(file: VirtualFile): Int = (file.hashCode() xor (file.hashCode() ushr 16)) and (bucketCount - 1)
        val candidates = WriteAction.compute<List<VirtualFile>, RuntimeException> {
            (1..bucketCount * 2).map { index ->
                root.directory.createChildData(this, "ordered-$index.js").apply {
                    setBinaryContent("const value=1;\n".toByteArray())
                }
            }
        }
        assertEquals("Real candidate files must cover the SDK editor map's buckets",
            (0 until bucketCount).toSet(), candidates.map(::bucket).toSet())
        val source = candidates.first { bucket(it) == bucket(other) }
        myFixture.configureFromExistingVirtualFile(source)
        waitUntilFileOpenedByLspServer(project, source, timeout = 15)
        editors.closeFile(root.source)
        // Equal-bucket insertion order is deterministic, including when the outer file
        // occupies bucket zero. Reinsert it through the public API without replacing a server.
        editors.closeFile(other)
        editors.openFile(other, false)
        waitUntilFileOpenedByLspServer(project, other, timeout = 15)
        assertEquals("The actual SDK snapshot must contain exactly nested then outer",
            listOf(source, other), editors.openFiles.toList())
        assertEquals("Ordering the editors must preserve the established servers", originalServers, servers().toSet())
        originalServers.forEach { assertEquals(LspServerState.Running, it.state) }
        return Root(root.directory, source)
    }

    private fun executable(root: Root) = root.directory.toNioPath().resolve("node_modules/@biomejs/biome/bin/biome").toString()

    private fun awaitProbe(root: Root) {
        PlatformTestUtil.waitWithEventsDispatching("Dependency probe did not run for ${root.directory.path}", {
            Files.readString(root.directory.toNioPath().resolve("probe-scripts.txt")).isNotBlank()
        }, 5)
    }

    private fun assertProbeExecutable(workingRoot: Root, packageRoot: Root) {
        assertEquals("Every version probe must execute the selected root's package",
            setOf(executable(packageRoot)), Files.readAllLines(workingRoot.directory.toNioPath().resolve("probe-scripts.txt")).toSet())
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
            const fixtureRoot = require('node:path').resolve(__dirname, '../../../..');
            const version = fs.readFileSync(require('node:path').join(fixtureRoot, 'version.txt'), 'utf8').trim();
            if (process.argv.includes('--version')) {
              fs.appendFileSync('probe-scripts.txt', __filename + '\n');
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
