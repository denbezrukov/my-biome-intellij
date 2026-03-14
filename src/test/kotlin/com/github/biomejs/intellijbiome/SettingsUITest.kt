package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.pages.*
import com.github.biomejs.intellijbiome.utils.RemoteRobotExtension
import com.github.biomejs.intellijbiome.utils.StepsLogger
import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.waitFor
import com.intellij.remoterobot.utils.waitForIgnoringError
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import java.io.File
import java.time.Duration.ofMinutes
import java.time.Duration.ofSeconds

@ExtendWith(RemoteRobotExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SettingsUITest {

    init {
        StepsLogger.init()
    }

    @BeforeEach
    fun waitForIde(remoteRobot: RemoteRobot) {
        waitForIgnoringError(ofMinutes(3)) { remoteRobot.callJs("true") }
    }

    private fun openBiomeSettingsDialog(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            openSettings()
        }
    }

    private fun closeSettingsDialog(remoteRobot: RemoteRobot) {
        try {
            remoteRobot.find<ComponentFixture>(
                byXpath("//div[@text='Cancel' and @class='JButton']"),
                ofSeconds(5)
            ).click()
        } catch (e: Exception) {
            // Dialog might have already closed
        }
    }

    @Test
    @Order(1)
    fun openBiomeSettings(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }
        }

        step("Open Biome settings page") {
            openBiomeSettingsDialog(remoteRobot)
        }

        step("Verify settings dialog is visible") {
            waitFor(ofSeconds(30)) {
                try {
                    remoteRobot.find<ComponentFixture>(
                        byXpath("//div[@class='DialogPanel']"),
                        ofSeconds(5)
                    )
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }

        step("Close settings dialog") {
            closeSettingsDialog(remoteRobot)
        }
    }

    @Test
    @Order(2)
    fun settingsContainConfigurationModes(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }
        }

        step("Open settings") {
            openBiomeSettingsDialog(remoteRobot)
        }

        step("Verify configuration mode radio buttons exist") {
            waitFor(ofSeconds(30)) {
                try {
                    remoteRobot.find<ComponentFixture>(
                        byXpath("//div[@class='JRadioButton']"),
                        ofSeconds(5)
                    )
                    true
                } catch (e: Exception) {
                    false
                }
            }

            val radioButtons = remoteRobot.findAll<ComponentFixture>(
                byXpath("//div[@class='JRadioButton']")
            )

            assert(radioButtons.size >= 3) {
                "Expected at least 3 radio buttons (Disabled, Automatic, Manual), found: ${radioButtons.size}"
            }
        }

        step("Close settings dialog") {
            closeSettingsDialog(remoteRobot)
        }
    }

    @Test
    @Order(3)
    fun settingsContainOnSaveCheckboxes(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }
        }

        step("Open settings") {
            openBiomeSettingsDialog(remoteRobot)
        }

        step("Verify on-save checkboxes exist") {
            waitFor(ofSeconds(30)) {
                try {
                    remoteRobot.find<ComponentFixture>(
                        byXpath("//div[@class='JBCheckBox']"),
                        ofSeconds(5)
                    )
                    true
                } catch (e: Exception) {
                    false
                }
            }

            val checkboxes = remoteRobot.findAll<ComponentFixture>(
                byXpath("//div[@class='JBCheckBox']")
            )

            assert(checkboxes.size >= 4) {
                "Expected at least 4 checkboxes (LSP format, format on save, safe fixes, sort imports), found: ${checkboxes.size}"
            }
        }

        step("Close settings dialog") {
            closeSettingsDialog(remoteRobot)
        }
    }

    @Test
    @Order(4)
    fun settingsContainSupportedExtensionsField(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }
        }

        step("Open settings") {
            openBiomeSettingsDialog(remoteRobot)
        }

        step("Verify supported extensions field exists and contains default extensions") {
            waitFor(ofSeconds(30)) {
                try {
                    remoteRobot.find<ComponentFixture>(
                        byXpath("//div[@class='JBTextField']"),
                        ofSeconds(5)
                    )
                    true
                } catch (e: Exception) {
                    false
                }
            }

            val textFields = remoteRobot.findAll<ComponentFixture>(
                byXpath("//div[@class='JBTextField']")
            )

            val extensionsField = textFields.find {
                try {
                    val text = it.callJs<String>("component.getText();")
                    text.contains(".js") && text.contains(".ts")
                } catch (e: Exception) {
                    false
                }
            }

            assert(extensionsField != null) {
                "Expected to find a text field containing default extensions (.js, .ts)"
            }

            val extensionsText = extensionsField!!.callJs<String>("component.getText();")
            assert(extensionsText.contains(".jsx")) {
                "Expected extensions to contain .jsx, got: $extensionsText"
            }
            assert(extensionsText.contains(".json")) {
                "Expected extensions to contain .json, got: $extensionsText"
            }
            assert(extensionsText.contains(".css")) {
                "Expected extensions to contain .css, got: $extensionsText"
            }
        }

        step("Close settings dialog") {
            closeSettingsDialog(remoteRobot)
        }
    }

    @Test
    @Order(5)
    fun disableConfigurationDisablesCheckboxes(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }
        }

        step("Open settings") {
            openBiomeSettingsDialog(remoteRobot)
        }

        step("Click Disabled radio button") {
            waitFor(ofSeconds(30)) {
                try {
                    remoteRobot.find<ComponentFixture>(
                        byXpath("//div[@class='JRadioButton']"),
                        ofSeconds(5)
                    )
                    true
                } catch (e: Exception) {
                    false
                }
            }

            val radioButtons = remoteRobot.findAll<ComponentFixture>(
                byXpath("//div[@class='JRadioButton']")
            )

            val disabledRadio = radioButtons.firstOrNull()
            assert(disabledRadio != null) { "Expected to find Disabled radio button" }
            disabledRadio!!.click()
        }

        step("Verify checkboxes are disabled") {
            Thread.sleep(1000)
            val checkboxes = remoteRobot.findAll<ComponentFixture>(
                byXpath("//div[@class='JBCheckBox']")
            )

            val allDisabled = checkboxes.all {
                try {
                    !it.callJs<Boolean>("component.isEnabled();")
                } catch (e: Exception) {
                    true
                }
            }

            assert(allDisabled) {
                "Expected all checkboxes to be disabled when configuration mode is Disabled"
            }
        }

        step("Restore Automatic mode and close settings") {
            val radioButtons = remoteRobot.findAll<ComponentFixture>(
                byXpath("//div[@class='JRadioButton']")
            )
            if (radioButtons.size >= 2) {
                radioButtons[1].click()
            }

            closeSettingsDialog(remoteRobot)
        }
    }

    companion object {
        private val basicProjectPath = File("src/test/testData/basic-project")

        @JvmStatic
        @BeforeAll
        fun selectProject(remoteRobot: RemoteRobot) = with(remoteRobot) {
            welcomeFrame {
                openProjectLink.click()
                dialog("Open File or Project") {
                    directoryPath.text = basicProjectPath.absolutePath
                    button("OK").click()
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun closeProject(remoteRobot: RemoteRobot) = with(remoteRobot) {
            idea {
                menuBar.select("File", "Close Project")
            }
        }
    }
}
