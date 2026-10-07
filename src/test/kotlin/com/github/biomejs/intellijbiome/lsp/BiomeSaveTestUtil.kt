package com.github.biomejs.intellijbiome.lsp

import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import junit.framework.TestCase.assertFalse

/** Uses the SDK's save jobs as the completion signal, not an empty event queue. */
internal fun saveAndAwaitCompletion(fixture: CodeInsightTestFixture, documents: List<Document>) {
    val fileManager = FileDocumentManager.getInstance()
    fileManager.saveAllDocuments()
    val actions = ActionsOnSaveManager.getInstance(fixture.project)
    PlatformTestUtil.waitWithEventsDispatching("Save actions did not finish", { !actions.hasPendingActions() }, 30)
    documents.forEach { assertFalse("Document must reach disk after save actions", fileManager.isDocumentUnsaved(it)) }
}
