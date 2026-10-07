package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.extensions.findNearestBiomeConfig
import com.github.biomejs.intellijbiome.extensions.isBiomeConfigFile
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.lsp.api.LspServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Retries initial discovery; running servers retain ownership of configuration watching. */
@Service(Service.Level.PROJECT)
class BiomeConfigDiscoveryService(private val project: Project, private val scope: CoroutineScope) {
    init {
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val configs = events.mapNotNull { event ->
                        if (event is VFileCopyEvent) event.findCreatedFile() else event.file
                    }
                        .filter { it.isValid && it.isBiomeConfigFile() }.distinct()
                    if (configs.isEmpty() || project.isDisposed) return
                    scope.launch {
                        val shouldDiscover = readAction { needsDiscovery(configs) }
                        if (shouldDiscover && !project.isDisposed) {
                            LspServerManager.getInstance(project)
                                .startServersIfNeeded(BiomeLspServerSupportProvider::class.java)
                        }
                    }
                }
            })
    }

    private fun needsDiscovery(configs: List<VirtualFile>): Boolean {
        if (project.isDisposed) return false
        val settings = BiomeSettings.getInstance(project)
        if (settings.configurationMode != ConfigurationMode.AUTOMATIC) return false
        val roots = project.getBaseDirectories()
        val changedConfigs = configs.filter { config -> roots.any { VfsUtilCore.isAncestor(it, config, true) } }
        if (changedConfigs.isEmpty()) return false
        val servers = LspServerManager.getInstance(project)
            .getServersForProvider(BiomeLspServerSupportProvider::class.java)
        return FileEditorManager.getInstance(project).openFiles.any { file ->
            file.isValid && settings.fileSupported(file) &&
                changedConfigs.any { VfsUtilCore.isAncestor(it.parent, file, true) } &&
                // startServersIfNeeded itself skips existing descriptor roots. Dynamic nested-root
                // reparenting therefore remains separate from recovery of an uncovered open file.
                servers.none { server -> server.descriptor.roots.any { VfsUtilCore.isAncestor(it, file, true) } } &&
                roots.any { root -> VfsUtilCore.isAncestor(root, file, true) && file.findNearestBiomeConfig(root) != null }
        }
    }
}
