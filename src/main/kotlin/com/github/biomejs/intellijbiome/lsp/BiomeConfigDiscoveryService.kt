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
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Recovers unowned open files; established owners keep watching their own configuration. */
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
                    scope.launch { recoverOpenFiles(configs) }
                }
            })
    }

    private suspend fun recoverOpenFiles(configs: List<VirtualFile>) {
        // A repair can race public restart and its asynchronous descriptor publication.
        // Recheck changed snapshots instead of restarting a newly published replacement.
        withTimeoutOrNull(30_000) {
            while (true) {
                val request = readAction { discoveryRequest(configs) } ?: break
                val completed = withContext(Dispatchers.EDT) {
                    if (project.isDisposed || BiomeSettings.getInstance(project).configurationMode != ConfigurationMode.AUTOMATIC) {
                        return@withContext true
                    }
                    val manager = LspServerManager.getInstance(project)
                    val current = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java).toSet()
                    if (current != request.servers || current.any { it.state == LspServerState.Initializing }) {
                        return@withContext false
                    }
                    if (request.restart) {
                        // SDK discovery skips descendants of existing roots regardless of
                        // actual ownership. Restart also clears that server's rejected-path
                        // cache and closes any documents it opened during the invalid edit.
                        manager.stopAndRestartIfNeeded(BiomeLspServerSupportProvider::class.java)
                    } else {
                        manager.startServersIfNeeded(BiomeLspServerSupportProvider::class.java)
                    }
                    true
                }
                if (completed) break
                delay(100)
            }
        }
    }

    private fun discoveryRequest(configs: List<VirtualFile>): DiscoveryRequest? {
        if (project.isDisposed) return null
        val settings = BiomeSettings.getInstance(project)
        if (settings.configurationMode != ConfigurationMode.AUTOMATIC) return null
        val roots = project.getBaseDirectories()
        val changedConfigs = configs.filter { config -> config.isValid && roots.any { VfsUtilCore.isAncestor(it, config, true) } }
        if (changedConfigs.isEmpty()) return null
        val servers = LspServerManager.getInstance(project)
            .getServersForProvider(BiomeLspServerSupportProvider::class.java)
        val uncovered = FileEditorManager.getInstance(project).openFiles.filter { file ->
            file.isValid && settings.fileSupported(file) &&
                changedConfigs.any { VfsUtilCore.isAncestor(it.parent, file, true) } &&
                servers.none { it.descriptor.isSupportedFile(file) } &&
                roots.any { root -> VfsUtilCore.isAncestor(root, file, true) && file.findNearestBiomeConfig(root) != null }
        }
        if (uncovered.isEmpty()) return null
        val blockedByAncestor = uncovered.any { file ->
            servers.any { server -> server.descriptor.roots.any { VfsUtilCore.isAncestor(it, file, true) } }
        }
        return DiscoveryRequest(servers.toSet(), blockedByAncestor)
    }

    private data class DiscoveryRequest(val servers: Set<LspServer>, val restart: Boolean)
}
