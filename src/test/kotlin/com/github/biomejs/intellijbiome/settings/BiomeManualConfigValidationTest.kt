package com.github.biomejs.intellijbiome.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import org.junit.Assert.assertThrows
import java.awt.Component
import java.awt.Container
import javax.swing.JRadioButton

class BiomeManualConfigValidationTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private val settings get() = BiomeSettings.getInstance(project)

    fun testInvalidManualPathsCannotApply() {
        val valid = myFixture.tempDirFixture.createFile("valid/biome.json", "{}").parent.path
        val empty = myFixture.tempDirFixture.findOrCreateDir("empty").path
        val other = myFixture.tempDirFixture.createFile("other.json", "{}").path
        val namedDirectory = myFixture.tempDirFixture.findOrCreateDir("named/biome.json").path
        val invalidChild = myFixture.tempDirFixture.findOrCreateDir("invalid-child/biome.jsonc").parent.path
        for (path in listOf("$valid/missing", empty, other, namedDirectory, invalidChild, "bad\u0000path")) {
            settings.configurationMode = ConfigurationMode.MANUAL
            settings.configPath = valid
            panel { configurable, component ->
                configField(component).text = path
                assertThrows("Invalid Manual path must not apply: $path", ConfigurationException::class.java) {
                    configurable.apply()
                }
                assertEquals(valid, settings.configPath)
            }
        }
    }

    fun testSupportedFilesAndDirectoriesCanApply() {
        val json = myFixture.tempDirFixture.createFile("both/biome.json", "{}").path
        val jsonc = myFixture.tempDirFixture.createFile("both/biome.jsonc", "{}").path
        val jsonOnly = myFixture.tempDirFixture.createFile("json-only/biome.json", "{}").parent.path
        val jsoncOnly = myFixture.tempDirFixture.createFile("jsonc-only/biome.jsonc", "{}").parent.path
        val malformed = myFixture.tempDirFixture.createFile("malformed/biome.json", "{broken").path
        val namedDirectory = myFixture.tempDirFixture.createFile("named/biome.json/biome.jsonc", "{}").parent.path
        val spaces = myFixture.tempDirFixture.createFile("space dir/biome.jsonc", "{}").path
        for (path in listOf(json, jsonc, jsonOnly, jsoncOnly, malformed, namedDirectory, spaces)) {
            settings.configurationMode = ConfigurationMode.MANUAL
            panel { configurable, component ->
                configField(component).text = path
                configurable.apply()
                assertEquals(ConfigurationMode.MANUAL, settings.configurationMode)
            }
        }
    }

    fun testBlankManualPathCanApply() {
        for (blank in listOf("", " \t\n")) {
            settings.configurationMode = ConfigurationMode.MANUAL
            panel { configurable, component ->
                configField(component).text = blank
                configurable.apply()
                assertEquals(ConfigurationMode.MANUAL, settings.configurationMode)
            }
        }
    }

    fun testHiddenInvalidInputDoesNotBlockAutomaticMode() = applyHiddenInput(ConfigurationMode.AUTOMATIC)

    fun testHiddenInvalidInputDoesNotBlockDisabledMode() = applyHiddenInput(ConfigurationMode.DISABLED)

    fun testNewManualSelectionIsValidatedBeforeApplyingMode() {
        settings.configurationMode = ConfigurationMode.AUTOMATIC
        settings.configPath = "missing-config"
        panel { configurable, component ->
            radios(component)[2].isSelected = true
            assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertEquals(ConfigurationMode.AUTOMATIC, settings.configurationMode)
            assertEquals("missing-config", settings.configPath)
        }
    }

    private fun applyHiddenInput(mode: ConfigurationMode) {
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.configPath = "bad\u0000path"
        panel { configurable, component ->
            radios(component)[if (mode == ConfigurationMode.AUTOMATIC) 1 else 0].isSelected = true
            assertEquals(ConfigurationMode.MANUAL, settings.configurationMode)
            configurable.apply()
            assertEquals(mode, settings.configurationMode)
        }
    }

    private fun panel(test: (BiomeConfigurable, Component) -> Unit) {
        val configurable = BiomeConfigurable(project)
        try {
            val component = configurable.createComponent()!!
            configurable.reset()
            test(configurable, component)
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun configField(component: Component): TextFieldWithBrowseButton =
        descendants(component).filterIsInstance<TextFieldWithBrowseButton>().toList().let {
            assertEquals(2, it.size)
            it[1]
        }

    private fun radios(component: Component): List<JRadioButton> =
        descendants(component).filterIsInstance<JRadioButton>().toList().also { assertEquals(3, it.size) }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }
}
