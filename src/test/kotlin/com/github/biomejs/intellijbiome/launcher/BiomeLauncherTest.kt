package com.github.biomejs.intellijbiome.launcher

import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.BiomeTargetRunBuilder
import com.github.biomejs.intellijbiome.GeneralProcessCommandBuilder
import com.github.biomejs.intellijbiome.supportsManualNodeTarget
import com.github.biomejs.intellijbiome.ProcessCommandParameter
import com.github.biomejs.intellijbiome.extensions.runProcessFuture
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
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
import java.nio.file.Path
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
