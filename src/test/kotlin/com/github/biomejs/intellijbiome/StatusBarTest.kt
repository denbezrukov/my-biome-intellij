package com.github.biomejs.intellijbiome

import com.github.biomejs.intellijbiome.pages.*
import com.github.biomejs.intellijbiome.utils.RemoteRobotExtension
import com.github.biomejs.intellijbiome.utils.StepsLogger
import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.waitFor
import com.intellij.remoterobot.utils.waitForIgnoringError
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.Point
import java.io.File
import java.time.Duration.ofMinutes
import java.time.Duration.ofSeconds

@ExtendWith(RemoteRobotExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StatusBarTest {

    init {
        StepsLogger.init()
    }

    @BeforeEach
    fun waitForIde(remoteRobot: RemoteRobot) {
        waitForIgnoringError(ofMinutes(3)) { remoteRobot.callJs("true") }
    }

    @Test
    @Order(1)
    fun biomeVersionShownInStatusBar(remoteRobot: RemoteRobot) = with(remoteRobot) {
        idea {
            step("Wait for smart mode and open file") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
                openFile("src/index.js")
                val editor = editor("index.js")
                editor.click(Point(0, 0))
            }

            step("Verify Biome version appears in status bar") {
                statusBar {
                    waitFor(ofMinutes(2)) {
                        try {
                            val biomeWidget = byContainsText("Biome")
                            biomeWidget.callJs<String>("component.getText();").startsWith("Biome")
                        } catch (e: Exception) {
                            false
                        }
                    }

                    val biomeWidget = byContainsText("Biome")
                    val version = biomeWidget.callJs<String>("component.getText();")
                    assert(version.startsWith("Biome")) {
                        "Expected status bar text to start with 'Biome', got: $version"
                    }
                    assert(version.matches(Regex("Biome \\d+\\.\\d+\\.\\d+"))) {
                        "Expected status bar to show 'Biome X.Y.Z' format, got: $version"
                    }
                }
            }
        }
    }

    @Test
    @Order(2)
    fun statusBarVersionPersistsAcrossFiles(remoteRobot: RemoteRobot) = with(remoteRobot) {
        idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }

            var firstVersion = ""

            step("Open first file and get version") {
                openFile("src/index.js")
                editor("index.js").click(Point(0, 0))

                statusBar {
                    waitFor(ofMinutes(2)) {
                        try {
                            val biomeWidget = byContainsText("Biome")
                            biomeWidget.callJs<String>("component.getText();").startsWith("Biome")
                        } catch (e: Exception) {
                            false
                        }
                    }
                    firstVersion = byContainsText("Biome").callJs<String>("component.getText();")
                }
            }

            step("Open second file and verify same version") {
                openFile("src/valid.js")
                editor("valid.js").click(Point(0, 0))

                statusBar {
                    waitFor(ofSeconds(30)) {
                        try {
                            val biomeWidget = byContainsText("Biome")
                            biomeWidget.callJs<String>("component.getText();").startsWith("Biome")
                        } catch (e: Exception) {
                            false
                        }
                    }
                    val secondVersion = byContainsText("Biome").callJs<String>("component.getText();")
                    assert(firstVersion == secondVersion) {
                        "Expected same Biome version across files. First: $firstVersion, Second: $secondVersion"
                    }
                }
            }
        }
    }

    @Test
    @Order(3)
    fun statusBarShowsBiomeForSupportedFiles(remoteRobot: RemoteRobot) = with(remoteRobot) {
        idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }

            step("Open JavaScript file and check status bar") {
                openFile("src/index.js")
                editor("index.js").click(Point(0, 0))

                statusBar {
                    waitFor(ofMinutes(2)) {
                        try {
                            val biomeWidget = byContainsText("Biome")
                            biomeWidget.callJs<String>("component.getText();").contains("Biome")
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
            }
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
