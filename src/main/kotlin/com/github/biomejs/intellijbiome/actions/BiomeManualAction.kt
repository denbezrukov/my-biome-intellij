package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.BiomeIcons
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.services.BiomeServerService.Feature
import com.github.biomejs.intellijbiome.services.BiomeServerService.Outcome
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.EnumSet

abstract class BiomeManualAction protected constructor(
    private val feature: Feature,
    private val messagePrefix: String,
) : AnAction(), DumbAware {
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
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!file.isValid || file.isDirectory) return
        val settings = BiomeSettings.getInstance(project)
        if (!settings.isEnabled()) return
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Biome")
        if (!settings.fileSupported(file)) {
            group.createNotification(
                BiomeBundle.message("biome.file.not.supported.title"),
                BiomeBundle.message("biome.file.not.supported.description", file.name),
                NotificationType.WARNING,
            ).addAction(NotificationAction.createSimple(BiomeBundle.message("biome.configure.extensions.link")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, BiomeConfigurable::class.java)
            }).notify(project)
            return
        }

        runWithModalProgressBlocking(project, BiomeBundle.message("biome.run.biome.check.with.features", feature.toString())) {
            var result = Outcome.Unavailable
            val operation = runBiomeSaveOperation {
                result = BiomeServerService.getInstance(project).executeFeatures(editor.document, EnumSet.of(feature))
            }
            currentCoroutineContext().ensureActive()
            val (title, content, type) = when (operation) {
                BiomeSaveOutcome.Completed -> when (result) {
                    Outcome.Changed -> Triple(BiomeBundle.message("$messagePrefix.success.label"),
                        BiomeBundle.message("$messagePrefix.success.description"), NotificationType.INFORMATION)
                    Outcome.PartiallyChanged -> Triple(BiomeBundle.message("biome.manual.partial.title"),
                        BiomeBundle.message("biome.manual.partial.description", file.name), NotificationType.WARNING)
                    Outcome.NotApplied -> Triple(BiomeBundle.message("biome.manual.not.applied.title"),
                        BiomeBundle.message("biome.manual.not.applied.description", file.name), NotificationType.WARNING)
                    Outcome.Unchanged -> Triple(BiomeBundle.message("biome.manual.unchanged.title"),
                        BiomeBundle.message("biome.manual.unchanged.description", file.name), NotificationType.INFORMATION)
                    Outcome.Unavailable -> Triple(BiomeBundle.message("biome.manual.unavailable.title"),
                        BiomeBundle.message("biome.manual.unavailable.description", file.name), NotificationType.WARNING)
                    Outcome.Stale -> Triple(BiomeBundle.message("biome.manual.stale.title"),
                        BiomeBundle.message("biome.manual.stale.description", file.name), NotificationType.WARNING)
                }
                BiomeSaveOutcome.TimedOut -> Triple(BiomeBundle.message("biome.manual.timeout.title"),
                    BiomeBundle.message("biome.manual.timeout.description", file.name), NotificationType.WARNING)
                is BiomeSaveOutcome.Failed -> Triple(BiomeBundle.message("$messagePrefix.failure.label"),
                    BiomeBundle.message("$messagePrefix.failure.description", operation.cause.message ?: operation.cause.toString()),
                    NotificationType.ERROR)
            }
            group.createNotification(title, content, type).notify(project)
        }
    }
}
