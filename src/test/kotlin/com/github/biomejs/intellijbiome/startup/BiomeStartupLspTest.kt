package com.github.biomejs.intellijbiome.startup

import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.github.biomejs.intellijbiome.fixtures.StartupProbeProcess
import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.BiomeTargetRunBuilder
import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.intellij.execution.configurations.GeneralCommandLine
import com.github.biomejs.intellijbiome.ProcessCommandParameter
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import kotlinx.coroutines.runBlocking
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.StandardCopyOption
import com.intellij.platform.lsp.api.LspServerState

class BiomeStartupLspTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private lateinit var invocationLog: Path
    private lateinit var executable: Path
    private lateinit var peerCommand: String
    private val descriptors = mutableListOf<LspServerDescriptor>()
    private val starter = object : LspServerSupportProvider.LspServerStarter {
        override fun ensureServerStarted(descriptor: LspServerDescriptor) {
            descriptors += descriptor
        }
    }

    override fun setUp() {
        super.setUp()
        descriptors.clear()
        invocationLog = Path.of(myFixture.tempDirPath, "invocations.log")
        executable = Path.of(myFixture.tempDirPath, "biome-probe")
        val fixtureClasses = Path.of(myFixture.tempDirPath, "fixture-classes")
        val fixtureName = StartupProbeProcess::class.java.name.replace('.', '/') + ".class"
        val fixtureClass = fixtureClasses.resolve(fixtureName)
        Files.createDirectories(fixtureClass.parent)
        StartupProbeProcess::class.java.classLoader.getResourceAsStream(fixtureName)!!.use {
            Files.copy(it, fixtureClass, StandardCopyOption.REPLACE_EXISTING)
        }
        peerCommand = "exec ${quote(Path.of(System.getProperty("java.home"), "bin", "java").toString())} -cp ${quote(fixtureClasses.toString())} ${StartupProbeProcess::class.java.name} lsp"
        Files.writeString(executable, "#!/bin/sh\nprintf '%s\\n' \"\$*\" >> ${quote(invocationLog.toString())}\nif [ \"\$1\" = '--version' ]; then printf 'Version: 2.2.3\\n'; else $peerCommand; fi\n")
        check(executable.toFile().setExecutable(true)) { "Cannot make the startup fixture executable" }
        myFixture.addFileToProject("biome.json", "{}")
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = executable.toString()
            configPath = ""
        }
    }

    fun testUnsupportedFileDoesNotProbeOrRequestServerStart() {
        val file = myFixture.addFileToProject("notes.unsupported", "plain text").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        assertFalse("Unsupported files must not invoke the executable", Files.exists(invocationLog))
        assertEmpty(descriptors)
    }

    fun testDisabledPluginDoesNotProbeOrRequestServerStart() {
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.DISABLED
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        assertFalse("Disabled projects must not invoke the executable", Files.exists(invocationLog))
        assertEmpty(descriptors)
    }

    fun testSupportedFileRequestsServerStart() {
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        assertEquals("The same fixture must be eligible when its extension is supported", 1, descriptors.size)
        assertFalse("The read-action callback must not run a version subprocess", Files.exists(invocationLog))
    }

    fun testTwoNestedRootsKeepSelectedExecutableAndWorkingDirectory() {
        val roots = listOf("first" to "1.9.4", "second" to "2.2.3")
        for ((root, version) in roots) {
            myFixture.addFileToProject("$root/biome.json", "{}")
            myFixture.addFileToProject("$root/.probe-version", "Version: $version\n")
            val wrapper = Path.of(myFixture.tempDirPath, root, "biome-wrapper")
            Files.writeString(wrapper, "#!/bin/sh\nprintf '%s|%s|%s\\n' '$root' \"\$PWD\" \"\$*\" >> ${quote(invocationLog.toString())}\nif [ \"\$1\" = '--version' ]; then cat .probe-version; else $peerCommand; fi\n")
            check(wrapper.toFile().setExecutable(true))
            BiomeSettings.getInstance(project).executablePath = wrapper.toString()
            val file = myFixture.addFileToProject("$root/index.js", "let value = 1;").virtualFile
            BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        }
        assertEquals(2, descriptors.size)
        assertFalse("Descriptor selection must not perform I/O", Files.exists(invocationLog))
        // Settings now select the second root; starting the first descriptor must retain its snapshot.
        descriptors.forEach { start(it) }
        val lines = Files.readAllLines(invocationLog)
        assertEquals(4, lines.size)
        for ((root, _) in roots) {
            val cwd = Path.of(myFixture.tempDirPath, root).toString()
            assertContainsElements(lines, "$root|$cwd|--version", "$root|$cwd|lsp-proxy")
        }
    }

    fun testAutomaticRootsProbeTheirSelectedDependencyInsteadOfPackageMetadata() = runBlocking {
        val node = System.getenv("PATH").split(java.io.File.pathSeparator).map { Path.of(it, "node") }
            .firstOrNull { Files.isExecutable(it) } ?: error("Node executable is required for the automatic startup fixture")
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(
            NodeJsInterpreterRef.create(NodeJsLocalInterpreter(node.toString())), testRootDisposable,
        )
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.AUTOMATIC
        for ((root, version) in listOf("first" to "1.9.4", "second" to "2.2.3")) {
            myFixture.addFileToProject("$root/biome.json", "{}")
            myFixture.addFileToProject("$root/package.json", """{"name":"$root","dependencies":{"@biomejs/biome":"99.0.0"}}""")
            myFixture.addFileToProject("$root/.probe-version", "Version: $version\n")
            myFixture.addFileToProject("$root/node_modules/@biomejs/biome/package.json", """{"name":"@biomejs/biome","version":"99.0.0","bin":{"biome":"bin/biome"}}""")
            myFixture.addFileToProject("$root/node_modules/@biomejs/biome/bin/biome", """
                const fs = require('fs');
                fs.appendFileSync(${jsString(invocationLog.toString())}, process.argv[1] + '|' + process.cwd() + '\n');
                process.stdout.write(fs.readFileSync('.probe-version', 'utf8'));
            """.trimIndent())
            val file = myFixture.addFileToProject("$root/index.js", "let value = 1;").virtualFile
            val rootPath = Path.of(myFixture.tempDirPath, root)
            val biome = BiomePackage(project)
            val selected = biome.binaryPath(rootPath.toString(), file, false)
                ?: error("Automatic discovery did not select the $root dependency")
            assertEquals(rootPath.resolve("node_modules/@biomejs/biome/bin/biome").toString(), selected)
            val probe = BiomeTargetRunBuilder(project).getBuilder(selected, rootPath.toString())
                .addParameters(listOf(ProcessCommandParameter.Value("--version"))).build()
            assertEquals("Selected executable output must win over stale package metadata", version, biome.versionNumber(probe))
        }
        val lines = Files.readAllLines(invocationLog)
        assertEquals(2, lines.size)
        for (root in listOf("first", "second")) {
            val rootPath = Path.of(myFixture.tempDirPath, root)
            assertContainsElements(lines, "${rootPath.resolve("node_modules/@biomejs/biome/bin/biome")}|$rootPath")
        }
    }

    fun testManualV1AndV2ConfigurationTransportIsPreserved() {
        val config = Path.of(myFixture.tempDirPath, "biome.json").toString()
        BiomeSettings.getInstance(project).configPath = config
        val configuredPath = BiomeSettings.getInstance(project).configPath
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        for (version in listOf("1.9.4", "2.2.3")) {
            descriptors.clear()
            Files.deleteIfExists(invocationLog)
            Files.writeString(executable, "#!/bin/sh\nprintf '%s\\n' \"\$*\" >> ${quote(invocationLog.toString())}\nif [ \"\$1\" = '--version' ]; then printf 'Version: $version\\n'; else $peerCommand; fi\n")
            BiomeLspServerSupportProvider().fileOpened(project, file, starter)
            start(descriptors.single())
            val arguments = Files.readAllLines(invocationLog).last()
            assertEquals(if (version.startsWith("1.")) "lsp-proxy --config-path $configuredPath" else "lsp-proxy", arguments)
        }
    }

    fun testStaleDescriptorNeverStartsAProbeOrServer() {
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        val startup = AppExecutorUtil.getAppExecutorService().submit(Callable {
            descriptors.single().startServerProcess()
        })
        try {
            startup.get(3, TimeUnit.SECONDS)
            fail("A descriptor without a live managed server must not start")
        } catch (failure: java.util.concurrent.ExecutionException) {
            assertTrue(failure.cause is ProcessCanceledException)
        }
        assertFalse("A stale descriptor invoked its executable", Files.exists(invocationLog))
    }

    fun testStoppingInitializingServerCancelsProbeAndPreventsLaunch() {
        val pid = Path.of(myFixture.tempDirPath, "managed-probe.pid")
        Files.writeString(executable, "#!/bin/sh\nprintf '%s\\n' \"\$*\" >> ${quote(invocationLog.toString())}\nprintf '%s' \"\$\$\" > ${quote(pid.toString())}\nexec sleep 60\n")
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        val manager = LspServerManager.getInstance(project)
        manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptors.single())
        try {
            val startedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!Files.exists(pid) && System.nanoTime() < startedDeadline) {
                UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(10)
            }
            assertTrue("SDK manager did not begin the version probe", Files.exists(pid))
            val child = ProcessHandle.of(Files.readString(pid).toLong())
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
            val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (child.map { it.isAlive }.orElse(false) && System.nanoTime() < cleanupDeadline) {
                UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(10)
            }
            assertFalse("SDK stop left the initializing version probe alive", child.map { it.isAlive }.orElse(false))
            assertEquals(listOf("--version"), Files.readAllLines(invocationLog))
        } finally {
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
            if (Files.exists(pid)) ProcessHandle.of(Files.readString(pid).toLong()).ifPresent { it.destroyForcibly() }
        }
    }

    fun testStopDuringFinalProcessCreationDoesNotLoseProcessOwnership() {
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        val descriptor = descriptors.single()
        val created = CountDownLatch(1)
        val release = CountDownLatch(1)
        val child = AtomicReference<Process>()
        val command = object : GeneralCommandLine("/bin/sleep", "60") {
            override fun createProcess(): Process {
                val process = super.createProcess()
                child.set(process)
                created.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Final process creation was not released" }
                return process
            }
        }
        // Exercise the real descriptor with a controlled command factory; no production test hook.
        descriptor.javaClass.getDeclaredField("targetRun").apply { isAccessible = true }
            .set(descriptor, BiomeTargetRun.General(command))
        val manager = LspServerManager.getInstance(project)
        manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptor)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (created.count != 0L && System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(10)
            }
            assertEquals("The final server process factory was not entered", 0L, created.count)
            val server = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).single()
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
            release.countDown()
            assertTrue("Cancellation lost the newly created server process before SDK handoff", child.get().waitFor(2, TimeUnit.SECONDS))
            assertFalse(child.get().isAlive)
            assertEquals(LspServerState.ShutdownNormally, server.state)
        } finally {
            release.countDown()
            child.get()?.let { it.destroyForcibly(); it.waitFor(1, TimeUnit.SECONDS) }
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
        }
    }

    private fun start(descriptor: LspServerDescriptor) {
        val manager = LspServerManager.getInstance(project)
        manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptor)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
                val server = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
                    .find { it.descriptor === descriptor }
                if (server?.state == LspServerState.Running) return
                if (server?.state == LspServerState.ShutdownUnexpectedly) fail("SDK server startup failed")
                Thread.sleep(10)
            }
            fail("SDK server did not initialize")
        } finally {
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun jsString(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
