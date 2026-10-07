package com.github.biomejs.intellijbiome.startup

import com.github.biomejs.intellijbiome.extensions.runProcessFuture
import com.github.biomejs.intellijbiome.BiomePackage
import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.execution.ExecutionException
import com.github.biomejs.intellijbiome.fixtures.StartupProbeProcess
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.Computable
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

class BiomeStartupProbeTest : BasePlatformTestCase() {
    private var fixtureDirectory: Path? = null

    override fun tearDown() {
        try {
            fixtureDirectory?.let { FileUtil.delete(it.toFile()) }
            fixtureDirectory = null
        } finally {
            super.tearDown()
        }
    }

    fun testVersionOutputIdentifiesV1AndV2() = runBlocking {
        for (version in listOf(
            "1.9.4", "2.2.3", "1.9.4-rc.2", "2.2.3-nightly.20261007+abc123",
            "2.2.3-nightly.20261008+abc123", "2.2.3+001",
        )) {
            assertEquals(version, BiomePackage(project).versionNumber(run("exit", "Version: $version", "0")))
        }
    }

    fun testInvalidVersionAndNonzeroExitRemainFailures() = runBlocking {
        for ((output, exit) in listOf(
            "not a version" to "0", "Version: 2.2.3" to "23",
            "Version: 2.2.3-nightly..1" to "0", "Version: 2.2.3+" to "0",
        )) {
            try {
                BiomePackage(project).versionNumber(run("exit", output, exit))
                fail("Invalid or unsuccessful version probes must fail")
            } catch (_: ExecutionException) {
            }
        }
    }

    fun testMissingExecutableRemainsAFailure() = runBlocking {
        try {
            BiomePackage(project).versionNumber(BiomeTargetRun.General(GeneralCommandLine("/missing-biome-probe")))
            fail("A missing selected executable must fail")
        } catch (_: ExecutionException) {
        }
    }

    fun testVersionDeadlineTerminatesTheProcess() = runBlocking {
        val started = Files.createTempFile("biome-version-timeout", ".pid")
        Files.delete(started)
        try {
            try {
                withTimeout(7_000) { BiomePackage(project).versionNumber(run("hang", started.toString())) }
                fail("A stalled version executable must fail")
            } catch (failure: ExecutionException) {
                assertTrue(failure.message.orEmpty().contains("5000"))
            }
            assertTrue("The timeout fixture never started", Files.exists(started))
            assertFalse("Timed-out executable survived cleanup", ProcessHandle.of(Files.readString(started).toLong()).map { it.isAlive }.orElse(false))
        } finally {
            if (Files.exists(started)) ProcessHandle.of(Files.readString(started).toLong()).ifPresent { it.destroyForcibly() }
            Files.deleteIfExists(started)
        }
    }

    fun testVersionCoroutineCancellationIsPreservedAndTerminatesChild() = runBlocking {
        val started = Files.createTempFile("biome-version-cancel", ".pid")
        Files.delete(started)
        try {
            val collection = async { BiomePackage(project).versionNumber(run("hang", started.toString())) }
            withTimeout(5_000) { while (!Files.exists(started)) delay(10) }
            collection.cancel()
            try {
                collection.await()
                fail("Parent cancellation must propagate")
            } catch (_: CancellationException) {
            }
            collection.join()
            assertFalse("Cancelled executable survived cleanup", ProcessHandle.of(Files.readString(started).toLong()).map { it.isAlive }.orElse(false))
        } finally {
            if (Files.exists(started)) ProcessHandle.of(Files.readString(started).toLong()).ifPresent { it.destroyForcibly() }
            Files.deleteIfExists(started)
        }
    }

    fun testPlatformCancellationIsPreservedAndTerminatesChild() {
        val started = Files.createTempFile("biome-version-platform-cancel", ".pid")
        Files.delete(started)
        val indicator = EmptyProgressIndicator()
        try {
            try {
                ProgressManager.getInstance().runProcess(Computable {
                    runBlocking {
                        val cancellation = launch {
                            withTimeout(5_000) { while (!Files.exists(started)) delay(10) }
                            indicator.cancel()
                        }
                        try {
                            BiomePackage(project).versionNumber(run("hang", started.toString()))
                        } finally {
                            cancellation.cancel()
                        }
                    }
                }, indicator)
                fail("Platform cancellation must propagate")
            } catch (_: ProcessCanceledException) {
            }
            assertTrue(Files.exists(started))
            assertFalse("Platform-cancelled executable survived cleanup", ProcessHandle.of(Files.readString(started).toLong()).map { it.isAlive }.orElse(false))
        } finally {
            if (Files.exists(started)) ProcessHandle.of(Files.readString(started).toLong()).ifPresent { it.destroyForcibly() }
            Files.deleteIfExists(started)
        }
    }

    @Suppress("DEPRECATION") // The baseline SDK still exposes creation without opening the project.
    fun testProjectDisposalTerminatesTheChild() = runBlocking {
        val directory = Files.createTempDirectory("biome-disposed-project")
        val probeProject = ProjectManager.getInstance().createProject("biome-startup-probe", directory.toString())!!
        val started = directory.resolve("probe.pid")
        try {
            val disposal = launch {
                withTimeout(5_000) { while (!Files.exists(started)) delay(10) }
                ApplicationManager.getApplication().runWriteAction { Disposer.dispose(probeProject) }
            }
            try {
                BiomePackage(probeProject).versionNumber(run("hang", started.toString()))
                fail("Disposed projects must cancel their version probe")
            } catch (_: ProcessCanceledException) {
            } finally {
                disposal.cancel()
            }
            assertTrue("A real project must be disposed", probeProject.isDisposed)
            val file = myFixture.addFileToProject("after-disposal.js", "let value = 1;").virtualFile
            BiomeLspServerSupportProvider().fileOpened(probeProject, file, object : LspServerSupportProvider.LspServerStarter {
                override fun ensureServerStarted(descriptor: LspServerDescriptor) {
                    fail("A disposed project must not request a later server start")
                }
            })
            assertTrue(Files.exists(started))
            assertFalse("Disposed project's executable survived cleanup", ProcessHandle.of(Files.readString(started).toLong()).map { it.isAlive }.orElse(false))
        } finally {
            if (!probeProject.isDisposed) ApplicationManager.getApplication().runWriteAction { Disposer.dispose(probeProject) }
            if (Files.exists(started)) ProcessHandle.of(Files.readString(started).toLong()).ifPresent { it.destroyForcibly() }
            FileUtil.delete(directory.toFile())
        }
    }
    fun testNonzeroExitIsPreserved() = runBlocking {
        val handler = process("exit", "Version: 2.2.3", "23")
        try {
            val result = withTimeout(5_000) { runProcessFuture(handler).await() }
            assertEquals("A failed executable must not be accepted as a version probe", 23, result.processOutput.exitCode)
        } finally {
            stop(handler)
        }
    }

    fun testCancellingVersionCollectionTerminatesTheProcess() {
        val started = Files.createTempFile("biome-probe", ".pid")
        Files.delete(started)
        val handler = process("hang", started.toString())
        try {
            val future = runProcessFuture(handler)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!Files.exists(started) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("The controlled child did not start", Files.exists(started))
            future.cancel(true)
            assertTrue("Cancelling collection must terminate its real subprocess", handler.waitFor(1_000))
            assertFalse("The cancelled subprocess is still alive", handler.process.isAlive)
        } finally {
            stop(handler)
            Files.deleteIfExists(started)
        }
    }

    fun testCancellingCollectionTerminatesWrapperDescendants() = assertDescendantCleanup(false)

    fun testCancellingNodeStyleCollectionTerminatesInterruptIgnoringDescendants() = assertDescendantCleanup(true)

    private fun assertDescendantCleanup(nodeStyle: Boolean) {
        val directory = Files.createTempDirectory("biome-wrapper-descendant")
        val started = directory.resolve("wrapper.pid")
        val descendant = directory.resolve("child.pid")
        val arguments = arrayOf(if (nodeStyle) "hang-child-ignore-interrupt" else "hang-child", started.toString(), descendant.toString())
        val handler = if (nodeStyle) KillableProcessHandler(command(*arguments)) else process(*arguments)
        try {
            val collection = runProcessFuture(handler)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!Files.exists(started) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("The child-spawning wrapper did not start", Files.exists(started))
            collection.cancel(true)
            assertTrue(handler.waitFor(1_000))
            val childPid = Files.readString(descendant).toLong()
            val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (isExecuting(childPid) && System.nanoTime() < cleanupDeadline) Thread.sleep(10)
            assertFalse("Version wrapper cancellation orphaned its running native child ($childPid)", isExecuting(childPid))
        } finally {
            stop(handler)
            if (Files.exists(descendant)) ProcessHandle.of(Files.readString(descendant).toLong()).ifPresent { it.destroyForcibly() }
            FileUtil.delete(directory.toFile())
        }
    }

    private fun isExecuting(pid: Long): Boolean {
        if (!ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) return false
        // Linux PID 1 may retain a killed orphan as a zombie. It has exited and cannot execute;
        // ProcessHandle.isAlive alone does not distinguish that state from an orphan still running.
        val stat = Path.of("/proc", pid.toString(), "stat")
        if (Files.exists(stat)) {
            val state = Files.readString(stat).substringAfterLast(')').trim().firstOrNull()
            return state != 'Z' && state != 'X'
        }
        return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    fun testCoroutineCancellationTerminatesTheProcess() = runBlocking {
        val started = Files.createTempFile("biome-probe-coroutine", ".pid")
        Files.delete(started)
        val handler = process("hang", started.toString())
        try {
            val collection = launch { runProcessFuture(handler).await() }
            withTimeout(5_000) {
                while (!Files.exists(started)) yield()
            }
            collection.cancel()
            collection.join()
            assertTrue("Coroutine cancellation must terminate its child", handler.waitFor(1_000))
            assertFalse(handler.process.isAlive)
        } finally {
            stop(handler)
            Files.deleteIfExists(started)
        }
    }

    private fun process(vararg arguments: String): OSProcessHandler = OSProcessHandler(command(*arguments))

    private fun run(vararg arguments: String): BiomeTargetRun = BiomeTargetRun.General(command(*arguments))

    private fun command(vararg arguments: String): GeneralCommandLine {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val fixtureClasses = fixtureDirectory ?: Files.createTempDirectory("biome-probe-classes").also { fixtureDirectory = it }
        val fixtureName = StartupProbeProcess::class.java.name.replace('.', '/') + ".class"
        val fixtureClass = fixtureClasses.resolve(fixtureName)
        Files.createDirectories(fixtureClass.parent)
        StartupProbeProcess::class.java.classLoader.getResourceAsStream(fixtureName)!!.use {
            Files.copy(it, fixtureClass, StandardCopyOption.REPLACE_EXISTING)
        }
        return GeneralCommandLine(java, "-cp", fixtureClasses.toString(), StartupProbeProcess::class.java.name, *arguments)
    }

    private fun stop(handler: OSProcessHandler) {
        if (handler.process.isAlive) handler.destroyProcess()
        if (!handler.waitFor(2_000)) {
            handler.process.destroyForcibly()
            check(handler.waitFor(2_000)) { "Test child did not terminate" }
        }
    }
}
