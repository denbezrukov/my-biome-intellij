package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.settings.BiomeConfigurable
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.BiomeSettingsState
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.util.ui.UIUtil
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

class BiomeLanguageRoutingTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BiomeSettings.getInstance(project).loadState(BiomeSettingsState())
    }

    fun testBaselineSdkUsesGritSuffixForLspIdentity() {
        val file = myFixture.addFileToProject("pattern.grit", "`foo` => `bar`").virtualFile
        assertEquals("grit", LspServerDescriptor.getLanguageId(file))
    }

    fun testBaselineSdkUsesSvgSuffixForLspIdentity() {
        val file = myFixture.addFileToProject("icon.svg", "<svg/>").virtualFile
        assertEquals("svg", LspServerDescriptor.getLanguageId(file))
    }

    fun testDisabledPluginDisablesFormatting() {
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.DISABLED
        settings.enableLspFormat = true
        settings.formatOnSave = true
        assertFalse(settings.enableLspFormat)
        assertFalse(settings.formatOnSave)
    }

    fun testResetToDefaultsLinkAppliesGritWithoutClaimingSvg() {
        val settings = BiomeSettings.getInstance(project)
        settings.supportedExtensions = mutableListOf(".custom")
        val configurable = BiomeConfigurable(project)
        try {
            val panel = configurable.createComponent() ?: error("Missing settings component")
            configurable.reset()
            val resetLink = UIUtil.findComponentsOfType(panel, JEditorPane::class.java)
                .single { it.text.contains("href=\"reset\"") }
            val event = HyperlinkEvent(resetLink, HyperlinkEvent.EventType.ACTIVATED, null, "reset")
            resetLink.hyperlinkListeners.forEach { it.hyperlinkUpdate(event) }
            // The link edits the form, preserving persisted state until Apply.
            assertEquals(listOf(".custom"), settings.supportedExtensions)
            configurable.apply()
            assertTrue(settings.fileSupported(myFixture.addFileToProject("reset.grit", "").virtualFile))
            assertFalse(settings.fileSupported(myFixture.addFileToProject("reset.svg", "").virtualFile))
            assertFalse(settings.supportedExtensions.contains(".custom"))
            val reopened = BiomeSettings().apply {
                loadState(XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), BiomeSettingsState::class.java))
            }
            assertTrue(reopened.supportedExtensions.contains(".grit"))
            assertFalse(reopened.supportedExtensions.contains(".svg"))
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testGritIsSupportedByDefault() {
        val file = myFixture.addFileToProject("pattern.grit", "`foo` => `bar`").virtualFile
        assertTrue(BiomeSettings.getInstance(project).fileSupported(file))
    }

    fun testArbitraryXmlIsNotSupportedByDefault() {
        val file = myFixture.addFileToProject("data.xml", "<root/>").virtualFile
        assertFalse(BiomeSettings.getInstance(project).fileSupported(file))
    }

    fun testSvgIsNotClaimedUntilOlderServerFallbackIsVerified() {
        val file = myFixture.addFileToProject("icon.svg", "<svg/>").virtualFile
        assertFalse(BiomeSettings.getInstance(project).fileSupported(file))
    }

    fun testPersistedCustomExtensionsAreNotReplacedByNewDefaults() {
        val settings = BiomeSettings.getInstance(project)
        settings.supportedExtensions = mutableListOf(".js", ".custom")
        val serialized = XmlSerializer.serialize(settings.state)
        val reopened = BiomeSettings().apply {
            loadState(XmlSerializer.deserialize(serialized, BiomeSettingsState::class.java))
        }
        assertEquals(listOf(".js", ".custom"), reopened.supportedExtensions)
        assertFalse(reopened.fileSupported(myFixture.addFileToProject("pattern.grit", "").virtualFile))
        assertFalse(reopened.fileSupported(myFixture.addFileToProject("icon.svg", "").virtualFile))
        assertTrue(reopened.fileSupported(myFixture.addFileToProject("example.custom", "").virtualFile))
    }

    fun testExplicitSvgExtensionRemainsSupported() {
        val settings = BiomeSettings.getInstance(project)
        settings.supportedExtensions = mutableListOf(".svg")
        assertTrue(settings.fileSupported(myFixture.addFileToProject("icon.svg", "<svg/>").virtualFile))
        assertFalse(settings.fileSupported(myFixture.addFileToProject("data.xml", "<root/>").virtualFile))
    }
}
