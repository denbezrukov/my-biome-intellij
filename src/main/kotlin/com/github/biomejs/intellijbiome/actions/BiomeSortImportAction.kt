package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomeIcons
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.services.BiomeServerService.Feature
import com.github.biomejs.intellijbiome.settings.BiomeConfigurable
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import kotlinx.coroutines.withTimeout

class BiomeSortImportAction : AnAction(), DumbAware {
    init {
        templatePresentation.icon = BiomeIcons.BiomeIcon
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val editor = event.getData(CommonDataKeys.EDITOR)
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        event.presentation.isEnabledAndVisible = project != null && !project.isDisposed &&
            editor != null && !editor.isDisposed && editor.document.isWritable &&
            file != null && file.isValid && !file.isDirectory &&
            BiomeSettings.getInstance(project).let { it.isEnabled() && it.fileSupported(file) }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        if (project.isDisposed) return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        if (editor.isDisposed || !editor.document.isWritable) return

        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Biome")

        val settings = BiomeSettings.getInstance(project)
        if (!settings.isEnabled()) return
        val manager = FileDocumentManager.getInstance()
        val virtualFile = manager.getFile(editor.document) ?: return
        if (!virtualFile.isValid || virtualFile.isDirectory) return

        if (!settings.fileSupported(virtualFile)) {
            notificationGroup.createNotification(title = BiomeBundle.message("biome.file.not.supported.title"),
                content = BiomeBundle.message("biome.file.not.supported.description", virtualFile.name),
                type = NotificationType.WARNING)
                .addAction(NotificationAction.createSimple(BiomeBundle.message("biome.configure.extensions.link")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, BiomeConfigurable::class.java)
                }).notify(project)
            return
        }

        runWithModalProgressBlocking(project,
            BiomeBundle.message("biome.run.biome.check.with.features", Feature.SortImports.toString())) {
            try {
                withTimeout(5_000) {
                    BiomeServerService.getInstance(project).sortImports(editor.document)
                }
                notificationGroup.createNotification(title = BiomeBundle.message("biome.apply.sort.import.success.label"),
                    content = BiomeBundle.message("biome.apply.sort.import.success.description"),
                    type = NotificationType.INFORMATION).notify(project)
            } catch (e: Exception) {
                notificationGroup.createNotification(title = BiomeBundle.message("biome.apply.sort.import.failure.label"),
                    content = BiomeBundle.message("biome.apply.sort.import.failure.description", e.message.toString()),
                    type = NotificationType.ERROR).notify(project)
            }
        }
    }
}

