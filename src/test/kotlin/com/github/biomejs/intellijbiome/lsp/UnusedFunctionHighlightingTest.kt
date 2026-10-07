package com.github.biomejs.intellijbiome.lsp

import com.intellij.lang.javascript.modules.TestNpmPackage

@TestNpmPackage("@biomejs/biome@2.2.3")
class UnusedFunctionHighlightingTest : BiomeLspFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        setUpLspFixture("unused-function")
        myFixture.configureBiomeForLspTests()
    }

    fun testUnusedFunctionDiagnosticsProduceSnapshotDiagnostics() {
        myFixture.checkBiomeHighlightingSnapshot("index.js", "unused-function/index.expected.js")
    }

    fun testDiagnosticQuickFixRenamesUnusedParameter() {
        myFixture.checkBiomeHighlightingSnapshot("index.js", "unused-function/index.expected.js")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("param"))
        val intentions = myFixture.availableIntentions
        val fix = intentions.singleOrNull { it.text == "If this is intentional, prepend param with an underscore." }
        assertNotNull("Expected Biome quick fix among ${intentions.map { it.text }}", fix)
        myFixture.launchAction(fix!!)
        assertEquals("function broken(_param) {}\n", myFixture.editor.document.text)
    }
}
