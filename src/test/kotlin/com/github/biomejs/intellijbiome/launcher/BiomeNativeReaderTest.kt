package com.github.biomejs.intellijbiome.launcher

import com.github.biomejs.intellijbiome.BiomeTargetRun
import com.github.biomejs.intellijbiome.BiomeTargetRunBuilder
import com.github.biomejs.intellijbiome.GeneralProcessCommandBuilder
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
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BiomeNativeReaderTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private lateinit var interpreter: NodeJsLocalInterpreter

    override fun setUp() {
        super.setUp()
        assertTrue("Inherited-pipe fixtures require Linux procfs", Files.isDirectory(Path.of("/proc/self")))
        val node = EnvironmentUtil.getEnvironmentMap().getValue("PATH").split(java.io.File.pathSeparator)
            .map { Path.of(it, "node") }.firstOrNull(Files::isExecutable) ?: error("Node is required")
        interpreter = NodeJsLocalInterpreter(node.toString())
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
        val run = com.github.biomejs.intellijbiome.NodeProcessCommandBuilder(project, interpreter)
            .setExecutable(script.toString()).setWorkingDirectory(root.toString()).setCharset(Charsets.UTF_8)
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

}
