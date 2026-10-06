package com.github.biomejs.intellijbiome.startup

import com.github.biomejs.intellijbiome.extensions.runProcessFuture
import com.github.biomejs.intellijbiome.fixtures.StartupProbeProcess
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class BiomeStartupProbeTest : BasePlatformTestCase() {
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

    private fun process(vararg arguments: String): OSProcessHandler {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val fixtureClasses = Path.of(StartupProbeProcess::class.java.protectionDomain.codeSource.location.toURI()).toString()
        return OSProcessHandler(GeneralCommandLine(java, "-cp", fixtureClasses, StartupProbeProcess::class.java.name, *arguments))
    }

    private fun stop(handler: OSProcessHandler) {
        if (handler.process.isAlive) handler.destroyProcess()
        if (!handler.waitFor(2_000)) {
            handler.process.destroyForcibly()
            check(handler.waitFor(2_000)) { "Test child did not terminate" }
        }
    }
}
