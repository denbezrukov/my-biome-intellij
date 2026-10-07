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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Computable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import kotlin.io.path.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationItem
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

        val projectRoots = project.getBaseDirectories()
        if (projectRoots.none { VfsUtil.isUnder(file, setOf(it)) }) return
        // Discovery must observe a later config creation even when no root exists yet.
        if (settings.usesConfigDiscovery()) {
            project.service<BiomeConfigDiscoveryService>()
        }
        fun findRoot(candidate: VirtualFile): VirtualFile? {
            if (!settings.fileSupported(candidate)) return null
            val projectRoot = projectRoots.find { VfsUtil.isUnder(candidate, setOf(it)) } ?: return null
            return if (configPath.isNullOrEmpty()) {
                candidate.findNearestBiomeConfig(projectRoot)?.parent
            } else {
                // Explicit manual configuration owns the entire project root.
                projectRoot
            }
        }

        val root = findRoot(file) ?: return
        // Select the executable here; the platform probes it during pooled server startup.
        val executable = biome.binaryPath(root.path, file, false) ?: return
        project.service<BiomeDependencyRefreshService>()
        serverStarter.ensureServerStarted(BiomeLspServerDescriptor(project, root, executable, configPath, file.parent))
        if (!configPath.isNullOrEmpty()) return

        // Native restart skips later open files below the first collected root without
        // consulting isSupportedFile. Its callback starter holds only one descriptor.
        // Submit independent open descendants through the public, project-scoped manager;
        // it deduplicates root IDs before starting or probing a process.
        val manager = LspServerManager.getInstance(project)
        val scheduledRoots = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
            .flatMapTo(mutableSetOf(root)) { it.descriptor.roots.toList() }
        val fileIndex = ProjectFileIndex.getInstance(project)
        for (openFile in FileEditorManager.getInstance(project).openFiles) {
            ProgressManager.checkCanceled()
            if (!openFile.isInLocalFileSystem || !fileIndex.isInContent(openFile) ||
                !VfsUtilCore.isAncestor(root, openFile, true)) continue
            val nestedRoot = findRoot(openFile) ?: continue
            if (nestedRoot in scheduledRoots || !VfsUtilCore.isAncestor(root, nestedRoot, true)) continue
            val nestedExecutable = biome.binaryPath(nestedRoot.path, openFile, false) ?: continue
            scheduledRoots.add(nestedRoot)
            manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java,
                BiomeLspServerDescriptor(project, nestedRoot, nestedExecutable, configPath, openFile.parent))
        }
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
            && roots.any { root ->
                if (!file.toNioPath().startsWith(root.toNioPath())) return@any false
                if (!configPath.isNullOrEmpty()) return@any true
                // Established nested workspaces retain ownership while their config is edited.
                val nestedOwner = LspServerManager.getInstance(project)
                    .getServersForProvider(BiomeLspServerSupportProvider::class.java)
                    .any { server -> server.descriptor.roots.any { otherRoot ->
                        otherRoot != root && otherRoot.toNioPath().startsWith(root.toNioPath()) &&
                            file.toNioPath().startsWith(otherRoot.toNioPath())
                    } }
                if (nestedOwner) return@any false
                val config = file.findNearestBiomeConfig(root)
                // The IDE caches rejected paths for this server's lifetime. Keep an established
                // root's files during temporary config errors; only a distinct independent root
                // takes ownership. A non-root fallback still belongs to this running workspace.
                config == null || config.parent == root || BiomeConfig.loadFromFile(config)?.isRootConfig() != true
            }
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

    override val lspDiagnosticsSupport = BiomeDiagnosticsSupport()

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
