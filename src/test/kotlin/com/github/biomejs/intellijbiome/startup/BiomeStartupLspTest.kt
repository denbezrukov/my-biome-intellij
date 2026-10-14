package com.github.biomejs.intellijbiome.startup

import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.lsp.BiomeLspWorkspaceSettings
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
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.testFramework.PlatformTestUtil
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
import org.eclipse.lsp4j.ConfigurationItem

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





    fun testAutomaticVersionStillUsesPackageMetadataWithoutProbing() = runBlocking {
        val node = System.getenv("PATH").split(java.io.File.pathSeparator).map { Path.of(it, "node") }
            .firstOrNull { Files.isExecutable(it) } ?: error("Node is required")
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(
            NodeJsInterpreterRef.create(NodeJsLocalInterpreter(node.toString())), testRootDisposable)
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.AUTOMATIC
        myFixture.addFileToProject("package.json", """{"name":"fixture","dependencies":{"@biomejs/biome":"99.0.0"}}""")
        myFixture.addFileToProject("node_modules/@biomejs/biome/package.json", """{"name":"@biomejs/biome","version":"99.0.0","bin":{"biome":"bin/biome"}}""")
        myFixture.addFileToProject("node_modules/@biomejs/biome/bin/biome", "throw new Error('Automatic version lookup must not execute');")
        assertEquals("99.0.0", BiomePackage(project).versionNumber())
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        assertEquals(1, descriptors.size)
        assertFalse(Files.exists(invocationLog))
    }

    fun testSupportedFileRequestsServerStart() {
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        assertEquals("The same fixture must be eligible when its extension is supported", 1, descriptors.size)
        assertFalse("The read-action callback must not run a version subprocess", Files.exists(invocationLog))
    }





    fun testManualV1AndV2ConfigurationTransportIsPreserved() {
        val config = Path.of(myFixture.tempDirPath, "biome.json").toString()
        BiomeSettings.getInstance(project).configPath = config
        val configuredPath = BiomeSettings.getInstance(project).configPath
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        for (version in listOf("1.6.4", "1.7.0", "1.9.4", "2.2.3")) {
            descriptors.clear()
            Files.deleteIfExists(invocationLog)
            Files.writeString(executable, "#!/bin/sh\nprintf '%s\\n' \"\$*\" >> ${quote(invocationLog.toString())}\nif [ \"\$1\" = '--version' ]; then printf 'Version: $version\\n'; else $peerCommand; fi\n")
            BiomeLspServerSupportProvider().fileOpened(project, file, starter)
            start(descriptors.single())
            val arguments = Files.readAllLines(invocationLog).last()
            val expectedPath = if (version == "1.6.4") Path.of(configuredPath).parent.toString() else configuredPath
            assertEquals(if (version.startsWith("1.")) "lsp-proxy --config-path $expectedPath" else "lsp-proxy", arguments)
        }
    }

    fun testManualV1AndV2CommandsUseSelectedSecondContentRoot() {
        val secondPath = Files.createTempDirectory("biome second content root ")
        Disposer.register(testRootDisposable) { FileUtil.delete(secondPath.toFile()) }
        Files.writeString(secondPath.resolve("biome.json"), "{}")
        Files.writeString(secondPath.resolve("index.js"), "let value = 1;")
        val secondRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(secondPath)!!
        secondRoot.refresh(false, true)
        ModuleRootModificationUtil.addContentRoot(myFixture.module, secondRoot.path)
        assertTrue("The fixture must retain both content roots", ModuleRootManager.getInstance(myFixture.module).contentRoots.size >= 2)
        val file = secondRoot.findChild("index.js")!!
        BiomeSettings.getInstance(project).configPath = "biome.json"
        val configuredPath = BiomeSettings.getInstance(project).configPath
        assertTrue("The selected content root must differ from the default working directory", secondRoot.path != project.basePath)

        for (version in listOf("1.6.4", "1.7.0", "1.9.4", "2.2.3")) {
            descriptors.clear()
            Files.deleteIfExists(invocationLog)
            Files.writeString(executable, "#!/bin/sh\nprintf '%s\\t%s\\n' \"\$PWD\" \"\$*\" >> ${quote(invocationLog.toString())}\nif [ \"\$1\" = '--version' ]; then printf 'Version: $version\\n'; else $peerCommand; fi\n")
            BiomeLspServerSupportProvider().fileOpened(project, file, starter)
            val descriptor = descriptors.single()
            assertEquals(listOf(secondRoot), descriptor.roots.toList())
            val workspaceSettings = descriptor.getWorkspaceConfiguration(ConfigurationItem().apply {
                section = "biome"
            }) as BiomeLspWorkspaceSettings
            assertEquals("Workspace configuration must retain the selected relative path",
                configuredPath, workspaceSettings.configurationPath)
            start(descriptor)
            val expectedPath = if (version == "1.6.4") secondRoot.path else secondPath.resolve(configuredPath).toString()
            val arguments = if (version.startsWith("1.")) "lsp-proxy --config-path $expectedPath" else "lsp-proxy"
            assertEquals("Both commands must run in the selected content root for Biome $version",
                listOf("${secondRoot.path}\t--version", "${secondRoot.path}\t$arguments"),
                Files.readAllLines(invocationLog))
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
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                Thread.sleep(10)
            }
            assertTrue("SDK manager did not begin the version probe", Files.exists(pid))
            val child = ProcessHandle.of(Files.readString(pid).toLong())
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
            val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (child.map { it.isAlive }.orElse(false) && System.nanoTime() < cleanupDeadline) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
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
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
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
                // Startup can wait behind platform write-intent work. The IDE dispatcher
                // releases the test's write-intent lock while draining pending events.
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                val server = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
                    .find { it.descriptor === descriptor }
                if (server?.state == LspServerState.Running) return
                if (server?.state == LspServerState.ShutdownUnexpectedly) fail("SDK server startup failed")
                Thread.sleep(10)
            }
            val servers = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
                .map { "${it.state} (requested descriptor: ${it.descriptor === descriptor})" }
            val commands = if (Files.exists(invocationLog)) Files.readAllLines(invocationLog) else emptyList()
            fail("SDK server did not initialize; servers=$servers; commands=$commands")
        } finally {
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
