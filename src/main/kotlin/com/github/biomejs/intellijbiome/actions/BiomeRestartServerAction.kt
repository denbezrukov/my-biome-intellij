package com.github.biomejs.intellijbiome.actions

import com.github.biomejs.intellijbiome.BiomeIcons
import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

class BiomeRestartServerAction : AnAction(), DumbAware {
    init {
        templatePresentation.icon = BiomeIcons.BiomeIcon
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        BiomeServerService.getInstance(project).restartBiomeServer()
        BiomeServerService.getInstance(project).notifyRestart()
    }
}
