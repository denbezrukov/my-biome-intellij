package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.extensions.findNearestBiomeConfig
import com.github.biomejs.intellijbiome.extensions.isBiomeConfigFile
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.lsp.api.LspServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun BiomeSettings.usesConfigDiscovery(): Boolean =
    configurationMode == ConfigurationMode.AUTOMATIC ||
        (configurationMode == ConfigurationMode.MANUAL && configPath.isEmpty())

/** Retries discovery for uncovered editors without restarting existing servers. */
@Service(Service.Level.PROJECT)
class BiomeConfigDiscoveryService(private val project: Project, private val scope: CoroutineScope) {
    init {
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (project.isDisposed) return
                    val configs = events.mapNotNull { event ->
                        if (event is VFileCopyEvent) event.findCreatedFile() else event.file
                    }.filter { it.isValid && it.isBiomeConfigFile() }.distinct()
                    if (configs.isNotEmpty()) scope.launch { recoverOpenFiles(configs) }
                }
            })
    }

    private suspend fun recoverOpenFiles(configs: List<VirtualFile>) {
        if (!readAction { needsDiscovery(configs) }) return
        withContext(Dispatchers.EDT) {
            if (project.isDisposed || !BiomeSettings.getInstance(project).usesConfigDiscovery()) return@withContext
            // The platform enumerates the current editors and the provider selects the
            // current executable. No file or executable from the earlier read is reused.
            LspServerManager.getInstance(project)
                .startServersIfNeeded(BiomeLspServerSupportProvider::class.java)
        }
    }

    private fun needsDiscovery(configs: List<VirtualFile>): Boolean {
        if (project.isDisposed) return false
        val settings = BiomeSettings.getInstance(project)
        if (!settings.usesConfigDiscovery()) return false
        val roots = project.getBaseDirectories()
        val changedConfigs = configs.filter { config ->
            config.isValid && roots.any { VfsUtilCore.isAncestor(it, config, true) }
        }
        if (changedConfigs.isEmpty()) return false
        val servers = LspServerManager.getInstance(project)
            .getServersForProvider(BiomeLspServerSupportProvider::class.java)
        val index = ProjectFileIndex.getInstance(project)
        return FileEditorManager.getInstance(project).openFiles.any { file ->
            file.isValid && file.isInLocalFileSystem && index.isInContent(file) && settings.fileSupported(file) &&
                changedConfigs.any { VfsUtilCore.isAncestor(it.parent, file, true) } &&
                servers.none { server -> server.descriptor.roots.any { VfsUtilCore.isAncestor(it, file, true) } } &&
                roots.any { root -> VfsUtilCore.isAncestor(root, file, true) && file.findNearestBiomeConfig(root) != null }
        }
    }
}
