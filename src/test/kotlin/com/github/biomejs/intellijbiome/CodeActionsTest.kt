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
class CodeActionsTest {

    init {
        StepsLogger.init()
    }

    @BeforeEach
    fun waitForIde(remoteRobot: RemoteRobot) {
        waitForIgnoringError(ofMinutes(3)) { remoteRobot.callJs("true") }
    }

    @Test
    @Order(1)
    fun sortImportsViaEditorAction(remoteRobot: RemoteRobot) = with(remoteRobot) {
        idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }

            step("Open file with unsorted imports") {
                openFile("src/unsorted-imports.js")
                val editor = editor("unsorted-imports.js")
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

            step("Verify unsorted imports are present") {
                val beforeText = getEditorText()
                assert(beforeText.contains("import z from \"z-module\"")) {
                    "Expected file to contain unsorted imports, got: $beforeText"
                }
            }

            step("Execute sort imports action") {
                executeAction("BiomeSortImportAction")
            }

            step("Verify imports were sorted") {
                waitFor(ofSeconds(30)) {
                    val editorText = getEditorText()
                    val aIndex = editorText.indexOf("a-module")
                    val zIndex = editorText.indexOf("z-module")
                    aIndex in 0 until zIndex
                }

                val afterText = getEditorText()
                val aIndex = afterText.indexOf("a-module")
                val zIndex = afterText.indexOf("z-module")
                assert(aIndex in 0 until zIndex) {
                    "Expected imports to be sorted (a-module before z-module), got: $afterText"
                }
            }
        }
    }

    @Test
    @Order(2)
    fun applySafeFixesViaEditorAction(remoteRobot: RemoteRobot) = with(remoteRobot) {
        idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }

            step("Open file with lint errors") {
                openFile("src/lint-errors.js")
                val editor = editor("lint-errors.js")
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

            step("Verify file contains lint errors before fix") {
                val beforeText = getEditorText()
                assert(beforeText.contains("var x") || beforeText.contains("==")) {
                    "Expected file to contain lint errors (var, ==), got: $beforeText"
                }
            }

            step("Execute apply safe fixes action") {
                executeAction("BiomeApplySafeFixesAction")
            }

            step("Verify safe fixes were applied") {
                waitFor(ofSeconds(30)) {
                    val editorText = getEditorText()
                    !editorText.contains("var x") || editorText.contains("===")
                }

                val afterText = getEditorText()
                val hasFixedVar = !afterText.contains("var x") || !afterText.contains("var y")
                val hasFixedEquals = afterText.contains("===")
                assert(hasFixedVar || hasFixedEquals) {
                    "Expected safe fixes to be applied (var→const/let, ==→===), got: $afterText"
                }
            }
        }
    }

    @Test
    @Order(3)
    fun quickFixAvailableForLintErrors(remoteRobot: RemoteRobot) = with(remoteRobot) {
        idea {
            step("Wait for smart mode") {
                waitFor(ofMinutes(5)) { isDumbMode().not() }
            }

            step("Open file with lint errors") {
                openFile("src/lint-errors.js")
                val editor = editor("lint-errors.js")
                editor.click(Point(0, 0))
            }

            step("Wait for LSP diagnostics") {
                waitFor(ofMinutes(2)) {
                    hasHighlightsInEditor()
                }
            }

            step("Trigger quick fix menu via action") {
                executeAction("ShowIntentionActions")
            }

            step("Verify quick fix panel appears") {
                waitFor(ofSeconds(10)) {
                    try {
                        quickfix()
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
            }

            step("Close quick fix panel by pressing Escape") {
                executeAction("EditorEscape")
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
