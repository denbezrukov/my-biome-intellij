package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.BiomeBundle
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.services.BiomeServerService.Feature
import com.github.biomejs.intellijbiome.services.BiomeServerService.Outcome
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.EnumSet

internal fun runBiomeManualAction(
    project: Project,
    document: Document,
    file: VirtualFile,
    feature: Feature,
    messagePrefix: String,
) {
    val group = NotificationGroupManager.getInstance().getNotificationGroup("Biome")
    runWithModalProgressBlocking(project, BiomeBundle.message("biome.run.biome.check.with.features", feature.toString())) {
        var result = Outcome.Unavailable
        val operation = runBiomeSaveOperation {
            result = BiomeServerService.getInstance(project).executeFeatures(document, EnumSet.of(feature))
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
