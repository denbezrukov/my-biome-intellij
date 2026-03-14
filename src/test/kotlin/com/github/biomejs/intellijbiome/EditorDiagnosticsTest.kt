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
class EditorDiagnosticsTest {

    init {
        StepsLogger.init()
    }

    @BeforeEach
    fun waitForIde(remoteRobot: RemoteRobot) {
        waitForIgnoringError(ofMinutes(3)) { remoteRobot.callJs("true") }
    }

    @Test
    @Order(1)
    fun lspServerStartsWhenFileOpened(remoteRobot: RemoteRobot) = with(remoteRobot) {
        idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }

            step("Open JavaScript file to trigger LSP startup") {
                openFile("src/lint-errors.js")
                val editor = editor("lint-errors.js")
                editor.click(Point(0, 0))
            }

            step("Verify Biome appears in status bar (LSP server running)") {
                statusBar {
                    waitFor(ofMinutes(2)) {
                        try {
                            val biomeWidget = byContainsText("Biome")
                            biomeWidget.callJs<String>("component.getText();").contains("Biome")
                        } catch (e: Exception) {
                            false
                        }
                    }
                    val biomeWidget = byContainsText("Biome")
                    val version = biomeWidget.callJs<String>("component.getText();")
                    assert(version.startsWith("Biome")) {
                        "Expected status bar to show Biome version, got: $version"
                    }
                }
            }
        }
    }

    @Test
    @Order(2)
    fun editorShowsHighlightsForLintErrors(remoteRobot: RemoteRobot) = with(remoteRobot) {
        idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }

            step("Open file with lint errors") {
                openFile("src/lint-errors.js")
                val editor = editor("lint-errors.js")
                editor.click(Point(0, 0))
            }

            step("Wait for LSP diagnostics to appear") {
                waitFor(ofMinutes(2)) {
                    hasHighlightsInEditor()
                }

                assert(hasHighlightsInEditor()) {
                    "Expected editor to show highlights for lint errors (var usage, ==, debugger)"
                }
            }
        }
    }

    @Test
    @Order(3)
    fun validFileShowsNoLintErrors(remoteRobot: RemoteRobot) = with(remoteRobot) {
        idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }

            step("Open a valid file") {
                openFile("src/valid.js")
                val editor = editor("valid.js")
                editor.click(Point(0, 0))
            }

            step("Wait for LSP to process the file") {
                waitFor(ofSeconds(30)) {
                    val text = getEditorText()
                    text.contains("Hello, world!")
                }
            }

            step("Verify editor text is present") {
                val text = getEditorText()
                assert(text.contains("Hello, world!")) {
                    "Expected valid.js content, got: $text"
                }
            }
        }
    }

    @Test
    @Order(4)
    fun openFileAndReadEditorContent(remoteRobot: RemoteRobot) = with(remoteRobot) {
        idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }

            step("Open index.js") {
                openFile("src/index.js")
                val editor = editor("index.js")
                editor.click(Point(0, 0))
            }

            step("Verify editor content matches file content") {
                val text = getEditorText()
                assert(text.contains("var a = 1")) {
                    "Expected index.js content 'var a = 1', got: $text"
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
