package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.*
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Computable
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import com.github.biomejs.intellijbiome.extensions.findNearestBiomeConfig
import com.github.biomejs.intellijbiome.settings.BiomeConfigurable
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.Diagnostic


class BiomeLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        val biome = BiomePackage(project)
        val configPath = biome.configPath()

        // Finds the root directory of a Biome workspace. It's typically the parent directory of `biome.json`.
        // If no `biome.json` file found, nothing to do.
        val projectRootDir = project
            .getBaseDirectories()
            .find { VfsUtil.isUnder(file, setOf(it)) } ?: return

        val root = if (configPath.isNullOrEmpty()) {
            file.findNearestBiomeConfig(projectRootDir)?.parent ?: return
        } else {
            // When using manual configuration, the root directory will be the project root.
            projectRootDir
        }

        // Select the executable before SDK startup.
        val executable = biome.binaryPath(root.path, file, false) ?: return
        // Manual probes run during cancellable SDK startup. Automatic mode keeps package metadata.
        val version = if (BiomeSettings.getInstance(project).configurationMode == ConfigurationMode.MANUAL) {
            null
        } else {
            runBlocking { biome.versionNumber() }
        }

        serverStarter.ensureServerStarted(BiomeLspServerDescriptor(project, root, executable, version, configPath))
    }

    override fun createLspServerWidgetItem(lspServer: LspServer,
        currentFile: VirtualFile?) =
        LspServerWidgetItem(lspServer, currentFile, BiomeIcons.BiomeIcon, BiomeConfigurable::class.java)
}

private class BiomeLspServerDescriptor(
    project: Project,
    root: VirtualFile,
    executable: String,
    version: String?,
    private val configPath: String?,
) : LspServerDescriptor(project, "Biome", root) {
    private val probeRun = if (BiomeSettings.getInstance(project).configurationMode == ConfigurationMode.MANUAL) {
        BiomeTargetRunBuilder(project).getBuilder(executable)
            .setWorkingDirectory(root.path)
            .addParameters(listOf(ProcessCommandParameter.Value("--version"))).build()
    } else null
    private val legacyConfigPath = configPath?.takeIf { it.isNotEmpty() }
        ?.let { BiomeV1ConfigPath(it, root.toNioPath()) }
    private val legacyTargetRuns = if (probeRun != null && legacyConfigPath != null) {
        // Prepare target paths before SDK startup; choose the supported form after the probe.
        listOf(legacyConfigPath.selected, legacyConfigPath.directory).distinct().associateWith { path ->
            BiomeTargetRunBuilder(project).getBuilder(executable).setWorkingDirectory(root.path).addParameters(listOf(
                ProcessCommandParameter.Value("lsp-proxy"),
                ProcessCommandParameter.Value("--config-path"),
                ProcessCommandParameter.FilePath(path),
            )).build()
        }
    } else emptyMap()

    private val targetRun: BiomeTargetRun = run {
        var builder = BiomeTargetRunBuilder(project)
            .getBuilder(executable)
            .setWorkingDirectory(root.path)
            .addParameters(listOf(ProcessCommandParameter.Value("lsp-proxy")))

        // Backward compatibility for v1; `--config-path` is no longer available in v2.
        if (version != null && version.startsWith("1.") && !configPath.isNullOrEmpty()) {
            val path = BiomeV1ConfigPath(configPath, root.toNioPath()).forVersion(version)
            builder = builder.addParameters(listOf(
                ProcessCommandParameter.Value("--config-path"),
                ProcessCommandParameter.FilePath(path)
            ))
        }

        builder.build()
    }

    override fun isSupportedFile(file: VirtualFile): Boolean {
        return BiomeSettings.getInstance(project).fileSupported(file)
            && roots.any { root -> file.toNioPath().startsWith(root.toNioPath()) }
    }

    // IntelliJ falls back to the file extension as the language ID, which Biome doesn't
    // recognize for these extensions. Use the language IDs that Biome expects instead.
    override fun getLanguageId(file: VirtualFile): String =
        when (file.extension?.lowercase()) {
            "htm" -> "html"
            "svg" -> "xml"
            "gs" -> "javascript"
            "md" -> "markdown"
            "yml" -> "yaml"
            else -> super.getLanguageId(file)
        }

    override fun createCommandLine(): GeneralCommandLine {
        throw RuntimeException("Not expected to be called because startServerProcess() is overridden")
    }

    override fun startServerProcess(): OSProcessHandler {
        val probe = probeRun ?: return targetRun.startProcess().preserveSharedDaemon()
        if (project.isDisposed) throw ProcessCanceledException()
        val manager = LspServerManager.getInstance(project)
        // The SDK queues process startup before adding the server in its EDT write action.
        // A short read action waits for that publication and cannot mistake it for a stale start.
        val server = ApplicationManager.getApplication().runReadAction(Computable {
            manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
                .find { it.descriptor === this }
        }) ?: throw ProcessCanceledException()
        // SDK stop removes this instance from its copy-on-write collection before shutdown.
        // Poll its supported manager API rather than retaining an internal lifecycle listener.
        val checkStartupCancellation = {
            if (manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).none { it === server }) {
                throw CancellationException("Biome server startup was stopped")
            }
        }
        var pendingHandler: OSProcessHandler? = null
        return try {
            val handler = runBlocking {
                val version = BiomePackage(project).versionNumber(probe, checkStartupCancellation)
                currentCoroutineContext().ensureActive()
                ProgressManager.checkCanceled()
                if (project.isDisposed) throw ProcessCanceledException()
                checkStartupCancellation()
                // Backward compatibility for v1; `--config-path` is no longer available in v2.
                val selectedRun = if (version.startsWith("1.")) {
                    legacyConfigPath?.let { legacyTargetRuns[it.forVersion(version)] } ?: targetRun
                } else targetRun
                val handler = selectedRun.startProcess().also { pendingHandler = it }.preserveSharedDaemon()
                // Synchronous target preparation can overlap a stop after the preceding guard.
                currentCoroutineContext().ensureActive()
                ProgressManager.checkCanceled()
                if (project.isDisposed) throw ProcessCanceledException()
                checkStartupCancellation()
                handler
            }
            // Until runBlocking returns successfully, cancellation can discard its result.
            // The SDK connector takes ownership only after this method returns.
            pendingHandler = null
            handler
        } finally {
            pendingHandler?.let { handler ->
                if (!handler.isStartNotified) handler.startNotify()
                // Probe killProcess() terminates descendants even when recursive destruction
                // is disabled. Normal destruction closes this proxy's input instead.
                handler.destroyProcess()
                if (handler.process.isAlive) handler.process.destroyForcibly()
                try {
                    handler.process.waitFor(1_000, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun OSProcessHandler.preserveSharedDaemon() = apply {
        // A daemon can serve another client before this proxy reaches SDK handoff.
        setShouldDestroyProcessRecursively(false)
        (this as? KillableProcessHandler)?.setShouldKillProcessSoftly(false)
    }

    override fun getFilePath(file: VirtualFile): String =
        targetRun.toTargetPath(file.path)

    override fun findLocalFileByPath(path: String): VirtualFile? =
        super.findLocalFileByPath(targetRun.toLocalPath(path))

    override val lspGoToDefinitionSupport = false
    override val lspCompletionSupport = null

    override val lspFormattingSupport = object : LspFormattingSupport() {
        override fun shouldFormatThisFileExclusivelyByServer(
            file: VirtualFile,
            ideCanFormatThisFileItself: Boolean,
            serverExplicitlyWantsToFormatThisFile: Boolean,
        ): Boolean {
            val settings = BiomeSettings.getInstance(project)
            return settings.enableLspFormat
        }
    }

    override val lspDiagnosticsSupport = object : LspDiagnosticsSupport() {
        override fun getMessage(diagnostic: Diagnostic) =
            "Biome: ${diagnostic.message} (${diagnostic.code.left})"

        override fun getTooltip(diagnostic: Diagnostic) =
            getMessage(diagnostic)
    }

    override val clientCapabilities: ClientCapabilities
        get() = super.clientCapabilities.apply {
            workspace.configuration = true
        }

    override fun getWorkspaceConfiguration(item: ConfigurationItem): BiomeLspWorkspaceSettings? {
        if (item.section != "biome") {
            return null
        }

        return BiomeLspWorkspaceSettings().apply {
            configurationPath = configPath?.takeIf { it.isNotBlank() }
        }
    }
}
