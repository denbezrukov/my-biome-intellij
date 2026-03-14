package com.github.biomejs.intellijbiome.pages

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.DefaultXpath
import com.intellij.remoterobot.fixtures.FixtureName
import com.intellij.remoterobot.fixtures.JMenuBarFixture
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.waitFor
import java.time.Duration

fun RemoteRobot.idea(function: IdeaFrame.() -> Unit) {
    find<IdeaFrame>(timeout = Duration.ofSeconds(10)).apply(function)
}

@FixtureName("Idea frame")
@DefaultXpath("IdeFrameImpl type", "//div[@class='IdeFrameImpl']")
class IdeaFrame(remoteRobot: RemoteRobot,
    remoteComponent: RemoteComponent) :
    CommonContainerFixture(remoteRobot, remoteComponent) {
    val menuBar: JMenuBarFixture
        get() = step("Menu...") {
            return@step remoteRobot.find(JMenuBarFixture::class.java, JMenuBarFixture.byType())
        }

    @JvmOverloads
    fun dumbAware(timeout: Duration = Duration.ofMinutes(5),
        function: () -> Unit) {
        step("Wait for smart mode") {
            waitFor(duration = timeout, interval = Duration.ofSeconds(5)) {
                runCatching { isDumbMode().not() }.getOrDefault(false)
            }
            function()
            step("..wait for smart mode again") {
                waitFor(duration = timeout, interval = Duration.ofSeconds(5)) {
                    isDumbMode().not()
                }
            }
        }
    }

    fun isDumbMode(): Boolean {
        return callJs(
            """
            const frameHelper = com.intellij.openapi.wm.impl.ProjectFrameHelper.getFrameHelper(component)
            if (frameHelper) {
                const project = frameHelper.getProject()
                project ? com.intellij.openapi.project.DumbService.isDumb(project) : true
            } else {
                true
            }
        """, true
        )
    }

    fun openFile(path: String) {
        runJs(
            """
            importPackage(com.intellij.openapi.fileEditor)
            importPackage(com.intellij.openapi.vfs)
            importPackage(com.intellij.openapi.wm.impl)

            const path = '$path'
            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            if (frameHelper) {
                const project = frameHelper.getProject()
                const projectPath = project.getBasePath()
                const file = LocalFileSystem.getInstance().findFileByPath(projectPath + '/' + path)
                FileEditorManager.getInstance(project).openTextEditor(
                    new OpenFileDescriptor(
                        project,
                        file
                    ), true
                )
            }
        """, true
        )
    }

    fun closeAllEditors() {
        runJs(
            """
            importPackage(com.intellij.openapi.fileEditor)
            importPackage(com.intellij.openapi.wm.impl)
            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            if (frameHelper) {
                const project = frameHelper.getProject()
                FileEditorManager.getInstance(project).closeAllFiles()
            }
        """, true
        )
    }

    fun getEditorText(): String {
        return callJs(
            """
            importPackage(com.intellij.openapi.fileEditor)
            importPackage(com.intellij.openapi.wm.impl)
            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            let text = ""
            if (frameHelper) {
                const project = frameHelper.getProject()
                const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
                if (editor) {
                    text = editor.getDocument().getText()
                }
            }
            text
        """, true
        )
    }

    fun executeAction(actionId: String) {
        runJs(
            """
            importPackage(com.intellij.openapi.actionSystem)
            importPackage(com.intellij.openapi.actionSystem.ex)
            importPackage(com.intellij.openapi.wm.impl)
            const actionManager = ActionManager.getInstance()
            const action = actionManager.getAction("$actionId")
            if (action) {
                ActionUtil.invokeAction(action, component, "EditorPopup", null, null)
            }
        """, true
        )
    }

    fun getDiagnostics(): String {
        return callJs(
            """
            importPackage(com.intellij.openapi.fileEditor)
            importPackage(com.intellij.openapi.wm.impl)
            importPackage(com.intellij.codeInsight.daemon.impl)
            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            let result = ""
            if (frameHelper) {
                const project = frameHelper.getProject()
                const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
                if (editor) {
                    const document = editor.getDocument()
                    const highlights = DaemonCodeAnalyzerEx.getInstanceEx(project).getFileLevelHighlights(project, editor.getDocument())
                    result = highlights.toString()
                }
            }
            result
        """, true
        )
    }

    fun hasHighlightsInEditor(): Boolean {
        return callJs(
            """
            importPackage(com.intellij.openapi.fileEditor)
            importPackage(com.intellij.openapi.wm.impl)
            importPackage(com.intellij.openapi.editor.markup)
            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            let hasHighlights = false
            if (frameHelper) {
                const project = frameHelper.getProject()
                const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
                if (editor) {
                    const markupModel = editor.getMarkupModel()
                    const highlights = markupModel.getAllHighlighters()
                    hasHighlights = highlights.length > 0
                }
            }
            hasHighlights
        """, true
        )
    }

    fun openSettings() {
        runJs(
            """
            importPackage(com.intellij.openapi.options)
            importPackage(com.intellij.openapi.wm.impl)
            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            if (frameHelper) {
                const project = frameHelper.getProject()
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Biome Settings")
            }
        """, true
        )
    }

    fun revertFileContent(path: String) {
        runJs(
            """
            importPackage(com.intellij.openapi.fileEditor)
            importPackage(com.intellij.openapi.vfs)
            importPackage(com.intellij.openapi.wm.impl)
            importPackage(com.intellij.openapi.command)
            importPackage(com.intellij.openapi.application)
            const frameHelper = ProjectFrameHelper.getFrameHelper(component)
            if (frameHelper) {
                const project = frameHelper.getProject()
                const file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + '/$path')
                if (file) {
                    file.refresh(false, false)
                }
            }
        """, true
        )
    }
}
