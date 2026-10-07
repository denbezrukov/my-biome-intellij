package com.github.biomejs.intellijbiome.lsp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind

class BiomeDiagnosticsTest : BasePlatformTestCase() {
    private val renderer by lazy { myFixture.addFileToProject("diagnostic.js", "").virtualFile.let {
        BiomeLspServerDescriptor(project, it.parent, "/bin/true", null, it).lspDiagnosticsSupport
    } }

    private fun support() = renderer

    fun testAbsentCodeRendersMessageAndTooltip() {
        val diagnostic = Diagnostic().apply { setMessage("sample diagnostic") }
        assertRendering(diagnostic, "Biome: sample diagnostic")
    }

    fun testStringCodeRendersMessageAndTooltip() {
        val diagnostic = Diagnostic().apply {
            setMessage("sample diagnostic")
            setCode("lint/style/example")
        }
        assertRendering(diagnostic, "Biome: sample diagnostic (lint/style/example)")
    }

    fun testIntegerAndZeroCodesRenderMessageAndTooltip() {
        for (code in listOf(123, 0)) {
            val diagnostic = Diagnostic().apply { setMessage("sample diagnostic"); setCode(code) }
            assertRendering(diagnostic, "Biome: sample diagnostic ($code)")
        }
    }

    fun testMultilineAndHtmlSensitiveTextIsEscapedOnlyInTooltip() {
        val diagnostic = Diagnostic().apply {
            setMessage("Use <value> & \"quoted\"\nnext line")
            setCode("rule/<tag>&")
        }
        val renderer = support()
        assertEquals("Biome: Use <value> & \"quoted\"\nnext line (rule/<tag>&)", renderer.getMessage(diagnostic))
        val tooltip = renderer.getTooltip(diagnostic)
        assertTrue(tooltip, tooltip.contains("&lt;value&gt; &amp; &quot;quoted&quot;"))
        assertTrue(tooltip, tooltip.contains("<br>next line"))
        assertTrue(tooltip, tooltip.contains("rule/&lt;tag&gt;&amp;"))
        assertFalse(tooltip, tooltip.contains("<value>"))
    }

    /** Each SDK's real wire representations, including MarkupContent when the SDK accepts it. */
    fun testRuntimeMessageRepresentationsKeepTheirText() {
        assertRendering(Diagnostic().apply { setMessage("runtime string"); setCode(0) }, "Biome: runtime string (0)")
        val markupSetter = Diagnostic::class.java.methods.find {
            it.name == "setMessage" && it.parameterTypes.contentEquals(arrayOf(MarkupContent::class.java))
        }
        if (markupSetter != null) {
            for (kind in listOf(MarkupKind.PLAINTEXT, MarkupKind.MARKDOWN)) {
                val diagnostic = Diagnostic().apply { setCode("markup") }
                markupSetter.invoke(diagnostic, MarkupContent(kind, "**important** <value> & detail\nnext line"))
                val renderer = support()
                assertEquals("Biome: **important** <value> & detail\nnext line (markup)", renderer.getMessage(diagnostic))
                val tooltip = renderer.getTooltip(diagnostic)
                assertTrue(tooltip, tooltip.contains("**important** &lt;value&gt; &amp; detail<br>next line"))
            }
        }
    }

    private fun assertRendering(diagnostic: Diagnostic, expected: String) {
        val renderer = support()
        assertEquals(expected, renderer.getMessage(diagnostic))
        assertTrue(renderer.getTooltip(diagnostic), renderer.getTooltip(diagnostic).contains(expected))
    }
}
