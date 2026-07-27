package com.github.biomejs.intellijbiome.listeners

import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class BiomeConfigWatcher : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val hasRelevantChange = events.any { event ->
            val name = event.file?.name ?: event.path.substringAfterLast('/')
            name == "biome.json" || name == "biome.jsonc"
        }
        if (!hasRelevantChange) return

        ProjectManager.getInstance().openProjects.forEach { project ->
            if (!project.isDisposed) {
                BiomeServerService.getInstance(project).restartBiomeServer()
            }
        }
    }
}
