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
class FormattingTest {

    init {
        StepsLogger.init()
    }

    @BeforeEach
    fun waitForIde(remoteRobot: RemoteRobot) {
        waitForIgnoringError(ofMinutes(3)) { remoteRobot.callJs("true") }
    }

    @Test
    @Order(1)
    fun openFileAndVerifyContent(remoteRobot: RemoteRobot) = with(remoteRobot) {
        idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }

            step("Open format.js and verify its content") {
                openFile("src/format.js")
                val editor = editor("format.js")
                editor.click(Point(0, 0))

                val editorText = getEditorText()
                assert(editorText.contains("const   x=1")) {
                    "Expected editor to contain unformatted code, got: $editorText"
                }
            }
        }
    }

    @Test
    @Order(2)
    fun formatFileViaAction(remoteRobot: RemoteRobot) = with(remoteRobot) {
        idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }

            step("Open format.js file") {
                openFile("src/format.js")
                val editor = editor("format.js")
                editor.click(Point(0, 0))
            }

            step("Wait for LSP server to be ready") {
                waitFor(ofMinutes(2)) {
                    try {
                        statusBar {
                            val biomeWidget = byContainsText("Biome")
                            biomeWidget.callJs<String>("component.getText();").contains("Biome")
                        }
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
            }

            step("Execute reformat action") {
                executeAction("ReformatCode")
            }

            step("Verify file was formatted") {
                waitFor(ofSeconds(30)) {
                    val editorText = getEditorText()
                    !editorText.contains("const   x=1")
                }

                val editorText = getEditorText()
                assert(!editorText.contains("const   x=1")) {
                    "Expected formatting to fix irregular spacing, got: $editorText"
                }
            }
        }
    }

    @Test
    @Order(3)
    fun openValidFileAndVerifyNoChanges(remoteRobot: RemoteRobot) = with(remoteRobot) {
        idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }

            step("Open valid.js file") {
                openFile("src/valid.js")
                val editor = editor("valid.js")
                editor.click(Point(0, 0))
            }

            step("Get content before formatting") {
                val beforeFormat = getEditorText()

                step("Execute reformat action") {
                    executeAction("ReformatCode")
                }

                step("Verify content is unchanged (already well-formatted)") {
                    waitFor(ofSeconds(10)) {
                        val afterFormat = getEditorText()
                        beforeFormat.trim() == afterFormat.trim()
                    }
                    val afterFormat = getEditorText()
                    assert(beforeFormat.trim() == afterFormat.trim()) {
                        "Expected well-formatted file to remain unchanged.\nBefore: $beforeFormat\nAfter: $afterFormat"
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
