package com.github.biomejs.intellijbiome.launcher

import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.BiomeTargetRunBuilder
import com.github.biomejs.intellijbiome.GeneralProcessCommandBuilder
import com.github.biomejs.intellijbiome.supportsManualNodeTarget
import com.github.biomejs.intellijbiome.ProcessCommandParameter
import com.github.biomejs.intellijbiome.extensions.runProcessFuture
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.interpreter.wsl.WslNodeInterpreter
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.util.EnvironmentUtil
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BiomeLauncherTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    @TestNpmPackage("@biomejs/biome@1.9.4") private class Version1
    @TestNpmPackage("@biomejs/biome@2.2.3") private class Version2

    override fun setUp() {
        super.setUp()
        assertTrue("Required launcher process tests run on Linux", System.getProperty("os.name").startsWith("Linux"))
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.MANUAL
        val configured = Path.of(myFixture.tempDirPath, "configured node with spaces")
        Files.createSymbolicLink(configured, executableOnPath("node"))
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(
            NodeJsInterpreterRef.create(NodeJsLocalInterpreter(configured.toString())), testRootDisposable
        )
    }

    fun testVersion1NpmLauncherUsesConfiguredInterpreterWithoutPathNode() = checkPackage(Version1::class.java, "1.9.4")
    fun testVersion2NpmLauncherUsesConfiguredInterpreterWithoutPathNode() = checkPackage(Version2::class.java, "2.2.3")

    private fun checkPackage(packageClass: Class<*>, version: String) {
        val root = install(packageClass, "package with spaces")
        val launcher = root.resolve("node_modules/@biomejs/biome/bin/biome")
        val native = nativeExecutable(root)
        withoutPathNode {
            val scriptRun = versionRun(launcher)
            assertEquals("Version: $version", output(scriptRun))
            assertTrue("A Node shebang launcher must use the configured interpreter", scriptRun is BiomeTargetRun.Node)
            val nativeRun = versionRun(native)
            assertTrue("The native executable must run directly", nativeRun is BiomeTargetRun.General)
            assertEquals("Version: $version", output(nativeRun))
        }
    }

    fun testNodeScriptPreservesWorkingDirectoryArgumentsAndEnvironment() {
        val root = Path.of(myFixture.tempDirPath)
        val script = root.resolve("selected launcher with spaces.js")
        val nested = Files.createDirectories(root.resolve("nested cwd with spaces"))
        Files.writeString(script, "#!/usr/bin/env node\nconsole.log(JSON.stringify([process.cwd(), process.argv.slice(2), process.env.PATH, process.env.BIOME_BINARY]));\n")
        assertTrue(script.toFile().setExecutable(true))
        withoutPathNode(mapOf("BIOME_BINARY" to "chosen native with spaces")) { path ->
            val run = BiomeTargetRunBuilder(project).getBuilder(script.toString(), nested.toString())
                .addParameters(listOf(ProcessCommandParameter.Value("argument with spaces")))
                .build()
            // The SDK prepends the interpreter's directory; its filename is intentionally not `node`.
            val targetPath = "$root:$path"
            assertFalse(Files.isExecutable(root.resolve("node")))
            assertEquals("[\"$nested\",[\"argument with spaces\"],\"$targetPath\",\"chosen native with spaces\"]", output(run))
        }
    }

    fun testShellWrapperKeepsItsEnvironmentSetup() {
        val root = install(Version2::class.java, "selected package")
        val wrapper = root.resolve("shell launcher.js")
        val native = nativeExecutable(root)
        Files.writeString(wrapper, "#!/bin/sh\nexport BIOME_WRAPPER_MARKER=kept\nprintf '%s\\n' \"\$BIOME_WRAPPER_MARKER\"\nexec ${quote(native.toString())} \"\$@\"\n")
        assertTrue(wrapper.toFile().setExecutable(true))
        withoutPathNode {
            val run = versionRun(wrapper)
            assertTrue("A .js suffix must not bypass a shell wrapper", run is BiomeTargetRun.General)
            assertEquals("kept\nVersion: 2.2.3", output(run))
        }
    }

    fun testNpmLauncherPreservesBiomeBinaryOverride() {
        val v1 = install(Version1::class.java, "version one")
        val v2 = install(Version2::class.java, "version two")
        withoutPathNode(mapOf("BIOME_BINARY" to nativeExecutable(v1).toString())) {
            assertEquals("Version: 1.9.4", output(versionRun(v2.resolve("node_modules/@biomejs/biome/bin/biome"))))
        }
    }

    fun testNativeDoesNotRequireConfiguredNodeInterpreter() {
        val root = install(Version2::class.java, "native package")
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(NodeJsInterpreterRef.create(
            NodeJsLocalInterpreter(Path.of(myFixture.tempDirPath, "missing node").toString())
        ))
        withoutPathNode { assertEquals("Version: 2.2.3", output(versionRun(nativeExecutable(root)))) }
    }

    fun testNativeWithJavaScriptSuffixStillRunsDirectly() {
        val root = install(Version2::class.java, "native package")
        val selected = root.resolve("native with javascript suffix.js")
        Files.copy(nativeExecutable(root), selected)
        assertTrue(selected.toFile().setExecutable(true))
        withoutPathNode {
            val run = versionRun(selected)
            assertTrue("Source content must decide dispatch, not the filename", run is BiomeTargetRun.General)
            assertEquals("Version: 2.2.3", output(run))
        }
    }

    fun testEnvironmentSettingShebangIsNotStripped() {
        val script = Path.of(myFixture.tempDirPath, "env wrapper.js")
        Files.writeString(script, "#!/usr/bin/env -S BIOME_BINARY=selected node\nconsole.log('wrapper');\n")
        val run = versionRun(script)
        assertTrue("Environment/option setup in a shebang must remain with its wrapper", run is BiomeTargetRun.General)
    }

    fun testManualLauncherKeepsSelectedTarget() {
        val script = Path.of(myFixture.tempDirPath, "local node launcher")
        Files.writeString(script, "#!/usr/bin/env node\nconsole.log('local');\n")
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(
            NodeJsInterpreterRef.create(WslNodeInterpreter("PR4-unavailable-distribution", "/usr/bin/node"))
        )
        assertTrue("A selected local script must not be moved to a WSL interpreter target",
            BiomeTargetRunBuilder(project).getBuilder(script.toString()) is GeneralProcessCommandBuilder)

        // Decision coverage only: these path/target pairs do not claim WSL process execution.
        val local = NodeJsLocalInterpreter("C:/Node/node.exe")
        val remote = WslNodeInterpreter("Ubuntu", "/usr/bin/node")
        assertTrue(supportsManualNodeTarget(script.toString(), local))
        assertFalse(supportsManualNodeTarget(script.toString(), remote))
        assertFalse(supportsManualNodeTarget(script.toString(), null))
        for (unc in listOf("\\\\wsl$\\Ubuntu\\home\\user\\biome", "\\\\wsl.localhost\\Ubuntu\\home\\user\\biome",
            "//WSL$/Ubuntu/home/user/biome", "//wsl.localhost/Other/home/user/biome", "\\\\server\\share\\biome")) {
            assertFalse("UNC selection must retain direct target dispatch: $unc", supportsManualNodeTarget(unc, local))
            assertFalse("Matching or foreign WSL interpreter must retain direct dispatch: $unc", supportsManualNodeTarget(unc, remote))
            assertFalse("A local-interpreter object with a UNC executable is not a verified local target",
                supportsManualNodeTarget(script.toString(), NodeJsLocalInterpreter(unc)))
        }
    }

    fun testCancellingNodeLauncherCollectionTerminatesItsProcess() {
        val root = Path.of(myFixture.tempDirPath)
        val ready = root.resolve("node is ready")
        val script = root.resolve("hanging node launcher")
        Files.writeString(script, "#!/usr/bin/env node\nrequire('fs').writeFileSync('${ready}', 'ready'); setInterval(() => {}, 1000);\n")
        assertTrue(script.toFile().setExecutable(true))
        withoutPathNode {
            val handler = versionRun(script).startProcess()
            try {
                val future = runProcessFuture(handler)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!Files.exists(ready) && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue("Configured Node child did not start", Files.exists(ready))
                future.cancel(true)
                assertTrue("Cancelling collection must terminate the Node child", handler.waitFor(2_000))
                assertFalse(handler.process.isAlive)
            } finally {
                if (handler.process.isAlive) handler.destroyProcess()
                if (!handler.waitFor(2_000)) {
                    handler.process.destroyForcibly()
                    check(handler.waitFor(2_000)) { "Node launcher did not terminate" }
                }
            }
        }
    }

    fun testNodeReaderFinishesAfterProxyExitWithInheritedPipes() = checkInheritedPipes(destroy = false)

    fun testNodeReaderFinishesAfterProxyDestroyWithInheritedPipes() = checkInheritedPipes(destroy = true)

    private fun checkInheritedPipes(destroy: Boolean) {
        val root = Path.of(myFixture.tempDirPath)
        val release = root.resolve("release owned child")
        val childPid = root.resolve("owned child pid")
        val script = root.resolve("proxy with inherited pipes.js")
        val stdout = "stdout café\r\nlast stdout"
        val stderr = "stderr café\r\nlast stderr"
        Files.writeString(script, """
            #!/usr/bin/env node
            const fs = require('fs');
            const child = require('child_process').spawn(process.execPath, ['-e', `
                const fs = require('fs');
                setInterval(() => {
                    if (fs.existsSync(process.argv[1])) process.exit(0);
                }, 10);
            `, process.argv[2]], {stdio: ['ignore', 'inherit', 'inherit']});
            fs.writeFileSync(process.argv[3], String(child.pid));
            child.unref();
            process.stdout.write('stdout café\r\nlast stdout');
            process.stderr.write('stderr café\r\nlast stderr');
            process.stdin.once('data', () => process.exit(0));
        """.trimIndent())
        val run = BiomeTargetRunBuilder(project).getBuilder(script.toString(), root.toString())
            .addParameters(listOf(release, childPid).map { ProcessCommandParameter.Value(it.toString()) }).build()
        assertTrue("The regression must exercise the native Node target handler", run is BiomeTargetRun.Node)
        val handler = run.startProcess()
        handler.setShouldDestroyProcessRecursively(false)
        (handler as KillableProcessHandler).setShouldKillProcessSoftly(false)
        val streamsReady = CountDownLatch(2)
        val stdoutSeen = StringBuilder()
        val stderrSeen = StringBuilder()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val seen = when (outputType) {
                    ProcessOutputTypes.STDOUT -> stdoutSeen
                    ProcessOutputTypes.STDERR -> stderrSeen
                    else -> return
                }
                val expected = if (outputType == ProcessOutputTypes.STDOUT) stdout else stderr
                seen.append(event.text)
                if (seen.toString() == expected) streamsReady.countDown()
            }
        })
        val result = runProcessFuture(handler)
        var child: ProcessHandle? = null
        try {
            assertTrue("Both native readers must receive exact CRLF output before stopping", streamsReady.await(5, TimeUnit.SECONDS))
            child = ProcessHandle.of(Files.readString(childPid).toLong()).orElseThrow()
            assertTrue("Owned child must remain alive holding inherited pipes", isExecuting(child))
            if (destroy) handler.destroyProcess() else handler.processInput!!.apply { write("exit\n".toByteArray()); flush() }
            assertTrue("Proxy itself must exit", handler.process.waitFor(5, TimeUnit.SECONDS))
            assertTrue("Native readers must finish while the inherited-pipe child stays alive", handler.waitFor(2_000))
            assertTrue("Stopping this client must not kill the inherited-pipe child", isExecuting(child))
            val output = result.get(1, TimeUnit.SECONDS).processOutput
            assertTrue("stdout must retain exact UTF-8 and CRLF bytes", stdout.toByteArray().contentEquals(output.stdout.toByteArray()))
            assertTrue("stderr must retain exact UTF-8 and CRLF bytes", stderr.toByteArray().contentEquals(output.stderr.toByteArray()))
        } finally {
            // Release only this fixture's owned child, including after the expected RED.
            Files.writeString(release, "release")
            // The fixture directory must outlive the child observing its release marker.
            val ownedChild = child ?: if (Files.exists(childPid))
                ProcessHandle.of(Files.readString(childPid).toLong()).orElse(null) else null
            try {
                if (ownedChild != null && !awaitChildExit(ownedChild)) {
                    ownedChild.destroyForcibly()
                    assertTrue("Owned child must stop before removing its release marker", awaitChildExit(ownedChild))
                }
            } finally {
                if (handler.process.isAlive) handler.destroyProcess()
                assertTrue("Owned proxy and reader cleanup must finish", handler.waitFor(5_000))
            }
        }
    }

    private fun awaitChildExit(child: ProcessHandle): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (isExecuting(child) && System.nanoTime() < deadline) Thread.sleep(10)
        return !isExecuting(child)
    }

    private fun isExecuting(child: ProcessHandle): Boolean {
        if (!child.isAlive) return false
        // Linux PID 1 may defer reaping an orphan after exit; a zombie holds no pipes.
        // This is process state only, never inspection of the child's file descriptors.
        val stat = try {
            Files.readString(Path.of("/proc", child.pid().toString(), "stat"))
        } catch (_: NoSuchFileException) {
            return child.isAlive
        }
        return stat.substringAfterLast(')').trimStart().first() != 'Z'
    }

    private fun install(packageClass: Class<*>, subdir: String): Path {
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        myFixture.tempDirFixture.findOrCreateDir(subdir)
        val root = myFixture.tempDirFixture.getFile(".")!!
        TestNpmPackageInstaller(myFixture).installForTest(packageClass, root, subdir)
        return Path.of(root.path, subdir)
    }

    private fun nativeExecutable(root: Path): Path = Files.list(root.resolve("node_modules/@biomejs/biome").toRealPath().parent).use { entries ->
        entries.filter { it.fileName.toString().startsWith("cli-linux-") && !it.fileName.toString().endsWith("-musl") }
            .map { it.resolve("biome") }.filter(Files::isExecutable).findFirst().orElseThrow()
    }

    private fun versionRun(executable: Path): BiomeTargetRun = BiomeTargetRunBuilder(project)
        .getBuilder(executable.toString())
        .addParameters(listOf(ProcessCommandParameter.Value("--version"))).build()

    private fun output(run: BiomeTargetRun): String {
        val handler = run.startProcess()
        try {
            val result = runProcessFuture(handler).get(10, TimeUnit.SECONDS).processOutput
            assertEquals(result.stderr, 0, handler.exitCode)
            return result.stdout.trimEnd('\r', '\n')
        } finally {
            if (handler.process.isAlive) handler.destroyProcess()
            if (!handler.waitFor(2_000)) {
                handler.process.destroyForcibly()
                check(handler.waitFor(2_000)) { "Launcher child did not terminate" }
            }
        }
    }

    private fun withoutPathNode(extra: Map<String, String> = emptyMap(), action: (Path) -> Unit) {
        val original = EnvironmentUtil.getEnvironmentMap().toMap()
        val path = Files.createDirectories(Path.of(myFixture.tempDirPath, "path without node"))
        if (!Files.exists(path.resolve("ldd"))) Files.createSymbolicLink(path.resolve("ldd"), executableOnPath("ldd"))
        assertFalse(Files.exists(path.resolve("node")))
        setTestEnvironment(original + extra + ("PATH" to path.toString()))
        try { action(path) } finally { setTestEnvironment(original) }
    }

    private fun executableOnPath(name: String): Path = EnvironmentUtil.getEnvironmentMap().getValue("PATH")
        .split(java.io.File.pathSeparator).map { Path.of(it, name) }.firstOrNull(Files::isExecutable)
        ?: error("Required $name executable not found")

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
