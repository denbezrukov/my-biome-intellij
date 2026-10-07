package com.github.biomejs.intellijbiome.settings

import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.util.xmlb.XmlSerializer
import java.awt.Component
import java.awt.Container

class BiomeManualConfigSettingsTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    fun testSelectedFilesRoundTrip() {
        val selections = listOf("biome.json", "biome.jsonc").map {
            myFixture.tempDirFixture.createFile(it, "{}").path
        }
        for (selected in selections) {
            val settings = BiomeSettings()
            settings.configPath = selected
            assertEquals(selected, settings.configPath)
            assertEquals(selected, reload(settings).configPath)
        }
    }

    fun testLegacyDirectoryRoundTrip() {
        myFixture.tempDirFixture.createFile("biome.json", "{}")
        val directory = myFixture.tempDirFixture.getFile(".")!!.path
        val settings = BiomeSettings.getInstance(project)
        settings.loadState(BiomeSettingsState().apply {
            configPath = directory
            configurationMode = ConfigurationMode.MANUAL
        })
        assertEquals(directory, reload(settings).configPath)
        assertPanelRoundTrip(directory)
    }

    fun testConfigPathValidation() {
        val configurable = BiomeConfigurable(project)
        val field = TextFieldWithBrowseButton()
        val both = myFixture.tempDirFixture.findOrCreateDir("both")
        val json = myFixture.tempDirFixture.createFile("both/biome.json", "{}").path
        val jsonc = myFixture.tempDirFixture.createFile("both/biome.jsonc", "{}").path
        val jsonOnly = myFixture.tempDirFixture.createFile("json-only/biome.json", "{}").parent.path
        val jsoncOnly = myFixture.tempDirFixture.createFile("jsonc-only/biome.jsonc", "{}").parent.path
        val malformed = myFixture.tempDirFixture.createFile("malformed/biome.json", "{malformed").path
        for (path in listOf("", " \t\n", json, jsonc, both.path, jsonOnly, jsoncOnly, malformed)) {
            field.text = path
            assertNull("Expected accepted path: $path", configurable.validateConfigPath(field))
        }
        val emptyDirectory = myFixture.tempDirFixture.findOrCreateDir("empty").path
        val notConfig = myFixture.tempDirFixture.createFile("other.json", "{}").path
        val configDirectory = myFixture.tempDirFixture.findOrCreateDir("masquerading/biome.json").path
        val invalidChild = myFixture.tempDirFixture.findOrCreateDir("bad-child/biome.jsonc").parent.path
        for (path in listOf("${both.path}/missing", emptyDirectory, notConfig, configDirectory, invalidChild, "bad\u0000path")) {
            field.text = path
            val error = configurable.validateConfigPath(field)
            assertNotNull("Expected rejected path: $path", error)
            assertSame(field, error!!.component)
        }
        // A config-named directory is valid only as a directory with a standard regular child.
        myFixture.tempDirFixture.createFile("masquerading/biome.json/biome.jsonc", "{}")
        field.text = configDirectory
        assertNull(configurable.validateConfigPath(field))
    }

    fun testBlankOverrideRoundTrip() {
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.MANUAL
        for (blank in listOf("", " \t\n")) {
            settings.configPath = blank
            assertEquals("", settings.configPath)
            assertEquals("", reload(settings).configPath)
            assertPanelRoundTrip("")
        }
    }

    fun testPathWithSpaces() {
        val path = myFixture.tempDirFixture.createFile("space dir/biome.jsonc", "{}").path
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.configPath = path
        assertEquals(path, settings.configPath)
        assertEquals(path, reload(settings).configPath)
        val field = TextFieldWithBrowseButton().apply { text = path }
        assertNull(BiomeConfigurable(project).validateConfigPath(field))
        assertPanelRoundTrip(path)
        val padded = "  $path  "
        settings.configPath = padded
        assertEquals(padded, settings.configPath)
        assertEquals(padded, reload(settings).configPath)
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
            val field = configField(panel)
            field.text = selected
            assertNull(configurable.validateConfigPath(field))
            assertTrue(configurable.isModified)
            configurable.apply()
            assertEquals(selected, settings.configPath)
        } finally {
            configurable.disposeUIResources()
        }
        assertPanelRoundTrip(selected)
    }

    fun testInvalidManualInputCannotApply() {
        val valid = myFixture.tempDirFixture.createFile("biome.json", "{}").path
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.configPath = valid
        val configurable = BiomeConfigurable(project)
        try {
            val panel = configurable.createComponent()!!
            configurable.reset()
            configField(panel).text = "$valid-missing"
            org.junit.Assert.assertThrows(com.intellij.openapi.options.ConfigurationException::class.java) {
                configurable.apply()
            }
            assertEquals("Invalid Apply must not overwrite the valid stored value", valid, settings.configPath)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testHiddenManualInputDoesNotBlockModeChange() {
        val settings = BiomeSettings.getInstance(project)
        for (mode in listOf(ConfigurationMode.AUTOMATIC, ConfigurationMode.DISABLED)) {
            settings.configurationMode = ConfigurationMode.MANUAL
            settings.configPath = "invalid\u0000path"
            val configurable = BiomeConfigurable(project)
            try {
                val panel = configurable.createComponent()!!
                configurable.reset()
                val modes = descendants(panel).filterIsInstance<javax.swing.JRadioButton>().toList()
                assertEquals("Disabled, Automatic and Manual controls must be present", 3, modes.size)
                modes[if (mode == ConfigurationMode.AUTOMATIC) 1 else 0].isSelected = true
                assertEquals("The UI selection must be checked before settings are applied", ConfigurationMode.MANUAL,
                    settings.configurationMode)
                configurable.apply()
                assertEquals(mode, settings.configurationMode)
            } finally {
                configurable.disposeUIResources()
            }
        }
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
                assertNull(configurable.validateConfigPath(configField(panel)))
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
