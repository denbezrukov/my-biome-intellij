package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.*
import com.github.biomejs.intellijbiome.extensions.findNearestBiomeConfig
import com.github.biomejs.intellijbiome.extensions.terminateProbeProcess
import com.github.biomejs.intellijbiome.settings.BiomeConfigurable
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.services.BiomeDependencyRefreshService
import com.intellij.openapi.components.service
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Computable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import kotlin.io.path.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.Diagnostic
import java.util.concurrent.TimeUnit


class BiomeLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (project.isDisposed) return
        val settings = BiomeSettings.getInstance(project)
        if (!settings.isEnabled() || !settings.fileSupported(file)) return
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

        // Select the executable here; the platform probes it during pooled server startup.
        val executable = biome.binaryPath(root.path, file, false) ?: return
        project.service<BiomeDependencyRefreshService>()
        serverStarter.ensureServerStarted(BiomeLspServerDescriptor(project, root, executable, configPath, file.parent))
    }

    override fun createLspServerWidgetItem(lspServer: LspServer,
        currentFile: VirtualFile?) =
        LspServerWidgetItem(lspServer, currentFile, BiomeIcons.BiomeIcon, BiomeConfigurable::class.java)
}

internal class BiomeLspServerDescriptor(
    project: Project,
    root: VirtualFile,
    val executable: String,
    private val configPath: String?,
    val packageContext: VirtualFile,
) : LspServerDescriptor(project, "Biome", root) {
    // Preserve package discovery when the startup file is closed, renamed or deleted.
    val packageContextPath = packageContext.path
    private val executionContext = BiomeTargetRunBuilder(project)
    private val probeRun = executionContext.getBuilder(executable, root.path)
        .addParameters(listOf(ProcessCommandParameter.Value("--version"))).build()
    private val targetRun = executionContext.getBuilder(executable, root.path)
        .addParameters(listOf(ProcessCommandParameter.Value("lsp-proxy"))).build()
    private val legacyTargetRun = configPath?.takeIf { it.isNotEmpty() }?.let {
        executionContext.getBuilder(executable, root.path).addParameters(listOf(
            ProcessCommandParameter.Value("lsp-proxy"),
            ProcessCommandParameter.Value("--config-path"),
            ProcessCommandParameter.FilePath(Path(it)),
        )).build()
    }

    override fun startServerProcess(): OSProcessHandler {
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
                val version = BiomePackage(project).versionNumber(probeRun, checkStartupCancellation)
                currentCoroutineContext().ensureActive()
                ProgressManager.checkCanceled()
                if (project.isDisposed) throw ProcessCanceledException()
                checkStartupCancellation()
                // Backward compatibility for v1; `--config-path` is no longer available in v2.
                val handler = (if (version.startsWith("1.")) legacyTargetRun ?: targetRun else targetRun).startProcess()
                    .also { pendingHandler = it }
                // Synchronous target preparation can overlap a stop after the preceding guard.
                currentCoroutineContext().ensureActive()
                ProgressManager.checkCanceled()
                if (project.isDisposed) throw ProcessCanceledException()
                checkStartupCancellation()
                handler
            }
            // Until runBlocking returns successfully, cancellation can discard its result.
            // The SDK connector takes ownership only after this method returns.
            // A proxy can be the parent of a daemon shared with other IDE projects.
            // Normal shutdown must close this client's transport, not kill that daemon.
            // Keep recursive cleanup for version probes and failed startup handoffs.
            handler.setShouldDestroyProcessRecursively(false)
            // Process.destroy closes stdin first; a raw SIGINT to a Node wrapper can
            // leave its native proxy holding stdout open while waiting for stdin EOF.
            (handler as? KillableProcessHandler)?.setShouldKillProcessSoftly(false)
            pendingHandler = null
            handler
        } finally {
            pendingHandler?.let { handler ->
                terminateProbeProcess(handler)
                try {
                    handler.process.waitFor(1_000, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    override fun isSupportedFile(file: VirtualFile): Boolean {
        return BiomeSettings.getInstance(project).fileSupported(file)
            && roots.any { root -> file.toNioPath().startsWith(root.toNioPath()) }
    }

    override fun createCommandLine(): GeneralCommandLine {
        throw RuntimeException("Not expected to be called because startServerProcess() is overridden")
    }

    override fun getFilePath(file: VirtualFile): String =
        probeRun.toTargetPath(file.path)

    override fun findLocalFileByPath(path: String): VirtualFile? =
        super.findLocalFileByPath(probeRun.toLocalPath(path))

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
