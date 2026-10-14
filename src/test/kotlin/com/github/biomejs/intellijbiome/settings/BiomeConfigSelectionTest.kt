package com.github.biomejs.intellijbiome.settings

import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.util.xmlb.XmlSerializer
import java.awt.Component
import java.awt.Container

class BiomeConfigSelectionTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    fun testSelectedFilesRoundTrip() {
        for (name in listOf("biome.json", "biome.jsonc")) {
            val selected = myFixture.tempDirFixture.createFile(name, "{}").path
            val settings = BiomeSettings()
            settings.configPath = selected
            assertEquals(selected, settings.configPath)
            assertEquals(selected, reload(settings).configPath)
        }
    }

    fun testLegacyDirectoryRoundTrip() {
        val directory = myFixture.tempDirFixture.createFile("biome.json", "{}").parent.path
        val settings = BiomeSettings.getInstance(project)
        settings.loadState(BiomeSettingsState().apply {
            configPath = directory
            configurationMode = ConfigurationMode.MANUAL
        })
        assertEquals(directory, reload(settings).configPath)
        assertPanelRoundTrip(directory)
    }

    fun testNonblankPathsKeepTheirCharacters() {
        val path = myFixture.tempDirFixture.createFile("space dir/biome.jsonc", "{}").path
        val settings = BiomeSettings()
        for (selected in listOf(path, "  $path  ", "$path-missing")) {
            settings.configPath = selected
            assertEquals(selected, settings.configPath)
            assertEquals(selected, reload(settings).configPath)
        }
    }

    fun testSelectedConfigSurvivesApplyAndReopen() {
        myFixture.tempDirFixture.createFile("biome.json", "{}")
        val selected = myFixture.tempDirFixture.createFile("biome.jsonc", "{}").path
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.MANUAL
        val configurable = BiomeConfigurable(project)
        try {
            val panel = configurable.createComponent()!!
            configurable.reset()
            configField(panel).text = selected
            assertTrue(configurable.isModified)
            configurable.apply()
            assertEquals(selected, settings.configPath)
        } finally {
            configurable.disposeUIResources()
        }
        assertPanelRoundTrip(selected)
    }

    private fun reload(settings: BiomeSettings): BiomeSettings = BiomeSettings().apply {
        loadState(XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), BiomeSettingsState::class.java))
    }

    private fun assertPanelRoundTrip(expected: String) {
        val settings = BiomeSettings.getInstance(project)
        repeat(2) {
            val configurable = BiomeConfigurable(project)
            try {
                val panel = configurable.createComponent()!!
                configurable.reset()
                assertEquals(expected, configField(panel).text)
                configurable.apply()
                assertEquals(expected, settings.configPath)
                settings.loadState(reload(settings).state)
            } finally {
                configurable.disposeUIResources()
            }
        }
    }

    private fun configField(panel: Component): TextFieldWithBrowseButton =
        descendants(panel).filterIsInstance<TextFieldWithBrowseButton>().toList().let {
            assertEquals("The manual panel must expose executable and config fields", 2, it.size)
            it[1]
        }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }
}
