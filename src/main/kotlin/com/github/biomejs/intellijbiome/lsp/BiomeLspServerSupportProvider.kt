package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.*
import com.github.biomejs.intellijbiome.extensions.findNearestBiomeConfig
import com.github.biomejs.intellijbiome.settings.BiomeConfigurable
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import kotlin.io.path.Path
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

        // Finds the Biome executable and check the version using CLI.
        val executable = biome.binaryPath(root.path, file, false) ?: return
        val version = runBlocking { biome.versionNumber() }

        serverStarter.ensureServerStarted(BiomeLspServerDescriptor(project, root, executable, version, configPath))
        if (!configPath.isNullOrEmpty()) return

        // Public Restart skips later open files below the first collected root.
        // Its callback holds one descriptor, so submit independent descendants to the manager.
        val manager = LspServerManager.getInstance(project)
        val scheduledRoots = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
            .flatMapTo(mutableSetOf(root)) { it.descriptor.roots.toList() }
        val index = ProjectFileIndex.getInstance(project)
        val rootSettings = BiomeSettings.getInstance(project)
        for (openFile in FileEditorManager.getInstance(project).openFiles) {
            ProgressManager.checkCanceled()
            if (!openFile.isInLocalFileSystem || !index.isInContent(openFile) ||
                !VfsUtilCore.isAncestor(root, openFile, true)) continue
            if (!rootSettings.fileSupported(openFile)) continue
            val nestedRoot = openFile.findNearestBiomeConfig(projectRootDir)?.parent ?: continue
            if (nestedRoot in scheduledRoots || !VfsUtilCore.isAncestor(root, nestedRoot, true)) continue
            val nestedExecutable = biome.binaryPath(nestedRoot.path, openFile, false) ?: continue
            scheduledRoots.add(nestedRoot)
            manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java,
                BiomeLspServerDescriptor(project, nestedRoot, nestedExecutable, version, configPath))
        }
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
    private val targetRun: BiomeTargetRun = run {
        var builder = BiomeTargetRunBuilder(project)
            .getBuilder(executable)
            .setWorkingDirectory(root.path)
            .addParameters(listOf(ProcessCommandParameter.Value("lsp-proxy")))

        // Backward compatibility for v1; `--config-path` is no longer available in v2
        if (version != null && version.startsWith("1.") && !configPath.isNullOrEmpty()) {
            builder = builder.addParameters(listOf(
                ProcessCommandParameter.Value("--config-path"),
                ProcessCommandParameter.FilePath(Path(configPath))
            ))
        }

        builder.build()
    }

    override fun isSupportedFile(file: VirtualFile): Boolean {
        return BiomeSettings.getInstance(project).fileSupported(file)
            && roots.any { root ->
                if (!file.toNioPath().startsWith(root.toNioPath())) return@any false
                if (!configPath.isNullOrEmpty()) return@any true
                val nestedOwner = LspServerManager.getInstance(project)
                    .getServersForProvider(BiomeLspServerSupportProvider::class.java)
                    .any { server -> server.descriptor.roots.any { otherRoot ->
                        otherRoot != root && otherRoot.toNioPath().startsWith(root.toNioPath()) &&
                            file.toNioPath().startsWith(otherRoot.toNioPath())
                    } }
                if (nestedOwner) return@any false
                val config = file.findNearestBiomeConfig(root)
                // Rejections are cached by the IDE. Preserve an established root while its
                // config is temporarily invalid, unless another independent root owns the file.
                config == null || config.parent == root || BiomeConfig.loadFromFile(config)?.isRootConfig() != true
            }
    }

    override fun createCommandLine(): GeneralCommandLine {
        throw RuntimeException("Not expected to be called because startServerProcess() is overridden")
    }

    override fun startServerProcess(): OSProcessHandler =
        targetRun.startProcess()

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
