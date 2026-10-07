package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.extensions.runProcessFuture
import com.github.biomejs.intellijbiome.extensions.ProcessResult
import com.github.biomejs.intellijbiome.extensions.terminateProbeProcess
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.util.NodePackage
import com.intellij.javascript.nodejs.util.NodePackageDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.execution.process.OSProcessHandler
import kotlinx.coroutines.future.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.intellij.execution.ExecutionException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.coroutineContext
import java.nio.file.Paths


private const val semverNumber = "(?:0|[1-9]\\d*)"
private const val prereleaseIdentifier = "(?:0|[1-9]\\d*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
// Keep the full CLI version, including prerelease/build identity used by serverInfo.version.
private val versionRegex = Regex(
    "(?<![0-9A-Za-z_.+-])$semverNumber\\.$semverNumber\\.$semverNumber" +
        "(?:-$prereleaseIdentifier(?:\\.$prereleaseIdentifier)*)?" +
        "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?![0-9A-Za-z_.+-])"
)

class BiomePackage(private val project: Project) {
    private val packageName = "@biomejs/biome"
    private val packageDescription = NodePackageDescriptor(packageName)

    fun getPackage(virtualFile: VirtualFile?): NodePackage? {
        if (virtualFile != null) {
            val available = packageDescription.listAvailable(
                project,
                NodeJsInterpreterManager.getInstance(project).interpreter,
                virtualFile,
                false,
                true
            )
            if (available.isNotEmpty()) {
                return available[0]
            }
        }

        var pkg = packageDescription.findUnambiguousDependencyPackage(project) ?: NodePackage.findDefaultPackage(
            project,
            packageName,
            NodeJsInterpreterManager.getInstance(project).interpreter
        )

        return pkg
    }

    fun configPath(): String? {
        val settings = BiomeSettings.getInstance(project)
        val configurationMode = settings.configurationMode
        return when (configurationMode) {
            ConfigurationMode.DISABLED -> null
            ConfigurationMode.AUTOMATIC -> null // Let Biome find the config file
            ConfigurationMode.MANUAL -> settings.configPath
        }
    }

    /** Collects the version of precisely the executable and target selected by the caller. */
    suspend fun versionNumber(targetRun: BiomeTargetRun): String = versionNumber(targetRun) {}

    suspend fun versionNumber(targetRun: BiomeTargetRun, checkStartupCancellation: () -> Unit): String {
        var handler: OSProcessHandler? = null
        var future: CompletableFuture<ProcessResult>? = null
        try {
            checkProbeCancellation(checkStartupCancellation)
            // SDK target preparation is synchronous; the deadline bounds result collection.
            val process = targetRun.startProcess()
            handler = process
            val collection = runProcessFuture(process)
            future = collection
            val result = withTimeoutOrNull(5_000) {
                while (!collection.isDone) {
                    checkProbeCancellation(checkStartupCancellation)
                    delay(25)
                }
                checkProbeCancellation(checkStartupCancellation)
                collection.await()
            } ?: throw ExecutionException("Biome version probe exceeded 5000 ms")
            val output = result.processOutput
            if (output.exitCode != 0) {
                throw ExecutionException("Biome version probe exited with code ${output.exitCode}: ${output.stderr}")
            }
            return versionRegex.find(output.stdout)?.value
                ?: throw ExecutionException("Biome executable returned an invalid version")
        } finally {
            // Cleanup must survive parent cancellation, but never wait indefinitely for a child.
            withContext(NonCancellable + Dispatchers.IO) {
                val process = handler
                if (process != null && process.process.isAlive) {
                    future?.cancel(true)
                    terminateProbeProcess(process)
                    process.process.waitFor(1_000, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private suspend fun checkProbeCancellation(checkStartupCancellation: () -> Unit) {
        coroutineContext.ensureActive()
        ProgressManager.checkCanceled()
        if (project.isDisposed) throw ProcessCanceledException()
        checkStartupCancellation()
    }

    fun binaryPath(
        configPath: String?,
        virtualFile: VirtualFile,
        showVersion: Boolean,
    ): String? {
        val settings = BiomeSettings.getInstance(project)
        val configurationMode = settings.configurationMode
        return when (configurationMode) {
            ConfigurationMode.DISABLED -> null // don't try to find the executable path if the configuration file does not exist.
            // This will prevent start LSP and formatting in case if biome is not used in the project.
            ConfigurationMode.AUTOMATIC -> if (configPath != null || showVersion) findBiomeExecutable(virtualFile) else null // if configuration mode is manual, return the executable path if it is not empty string.
            // Otherwise, try to find the executable path.
            ConfigurationMode.MANUAL -> settings.executablePath
        }
    }

    private fun findBiomeExecutable(virtualFile: VirtualFile?): String? {
        val path = getPackage(virtualFile)?.getAbsolutePackagePathToRequire(project)
        if (path != null) {
            return Paths.get(path, "bin/biome").toString()
        }

        return null
    }


    companion object {
        const val configName = "biome"
    }
}
