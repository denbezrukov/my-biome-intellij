package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.BiomeSettingsState
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.openapi.components.service
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveManager
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

private const val GRIT_INPUT = "`console.log(\$x)`=>`console.info(\$x)`"
private const val GRIT_EXPECTED = "`console.log(\$x)` => `console.info(\$x)`\n"
private const val SVG_INPUT = "<svg width=\"24\" height=\"24\"><path d=\"M0 0\"/></svg>"

/** All requests pass through the plugin's real descriptor and the installed Biome process. */
abstract class BiomeLanguageLspTestBase(private val version: String) : BiomeLspFixtureTestCase() {
    private lateinit var transcript: Path

    override fun setUp() {
        super.setUp()
        myFixture.testDataPath = "src/test/testData/lsp/languages"
        myFixture.addFileToProject("biome.json", "{}")
        val root = myFixture.tempDirFixture.getFile(".") ?: error("Missing fixture directory")
        TestNpmPackageInstaller(myFixture).installForTest(javaClass, root)
        val executable = Path.of(root.path, "node_modules/.bin/biome")
        assertTrue("Pinned executable missing: $executable", Files.isRegularFile(executable))
        val output = CapturingProcessHandler(GeneralCommandLine(executable.toString(), "--version")
            .withWorkDirectory(root.path)).runProcess(10_000)
        assertEquals(output.stderr, 0, output.exitCode)
        assertEquals("Version: $version", output.stdout.trim())

        transcript = Path.of(root.path, "lsp-transcript.jsonl")
        val recorder = Files.readString(Path.of(myFixture.testDataPath, "record-lsp.cjs"))
            .replace("__BIOME_EXECUTABLE__", Gson().toJson(executable.toString()))
            .replace("__LSP_TRANSCRIPT__", Gson().toJson(transcript.toString()))
        val wrapper = myFixture.addFileToProject("record-biome.cjs", recorder).virtualFile
        assertTrue("Cannot make recording launcher executable", wrapper.toNioPath().toFile().setExecutable(true))
        BiomeSettings.getInstance(project).apply {
            loadState(BiomeSettingsState())
            configurationMode = ConfigurationMode.MANUAL
            executablePath = wrapper.path
            enableLspFormat = true
        }
    }

    protected fun startAndInitializeServerBeforeLanguageDocument() {
        openThroughPlugin("startup-control.js", "const a={b:1}", "javascript")
        assertFormattingRegistrationHasNoDocumentSelector()
        FileEditorManager.getInstance(project).closeFile(myFixture.file.virtualFile)
    }

    protected fun assertDisabledPluginDoesNotStartOrFormatGrit() {
        BiomeSettings.getInstance(project).apply {
            formatOnSave = true
            configurationMode = ConfigurationMode.DISABLED
        }
        val file = myFixture.addFileToProject("disabled.grit", GRIT_INPUT).virtualFile
        myFixture.configureFromExistingVirtualFile(file)
        val document = myFixture.editor.document
        val edited = "// saved without Biome\n$GRIT_INPUT"
        WriteCommandAction.runWriteCommandAction(project) { document.setText(edited) }
        FileDocumentManager.getInstance().saveAllDocuments()
        ActionsOnSaveManager.getInstance(project).waitForTasks()
        assertEquals(edited, document.text)
        assertEquals(edited, Files.readString(file.toNioPath()))
        assertTrue(LspServerManager.getInstance(project)
            .getServersForProvider(BiomeLspServerSupportProvider::class.java).isEmpty())
        assertFalse("Disabled plugin must not launch the recording executable", Files.exists(transcript))
    }

    protected fun formatThroughPlugin(name: String, input: String, languageId: String, expected: String) {
        val file = openThroughPlugin(name, input, languageId)
        runWithModalProgressBlocking(project, "Test Biome language formatting") {
            withTimeout(30_000) { project.service<BiomeServerService>().format(myFixture.editor.document) }
        }
        assertEquals(expected, myFixture.editor.document.text)
        val request = messages("client").lastOrNull {
            it.get("method")?.asString == "textDocument/formatting" &&
                it.getAsJsonObject("params").getAsJsonObject("textDocument").get("uri").asString
                    .endsWith("/${file.name}")
        } ?: error("Formatting request did not reach Biome")
        val response = messages("server").lastOrNull {
            !it.has("method") && it.get("id") == request.get("id")
        } ?: error("Biome did not answer the formatting request")
        assertFalse("Biome returned a formatting error", response.has("error"))
        if (input == expected) {
            val edits = response.get("result")
            assertTrue("Unsupported formatting must return null or no edits",
                edits != null && (edits.isJsonNull || (edits.isJsonArray && edits.asJsonArray.isEmpty)))
        }
    }

    protected fun assertIdeFormattingPreservedForExcludedFile(name: String, input: String) {
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.DISABLED
        val file = myFixture.addFileToProject(name, input).virtualFile
        myFixture.configureFromExistingVirtualFile(file)
        myFixture.performEditorAction("ReformatCode")
        val nativeOutput = myFixture.editor.document.text
        assertFalse("Native IDE formatter must actually change this control", nativeOutput == input)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText(input) }
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
        settings.configurationMode = ConfigurationMode.MANUAL
        FileEditorManager.getInstance(project).closeFile(file)
        // An excluded file must not be responsible for starting Biome. Keep a supported
        // document open so native fallback is tested while Biome formatting is registered.
        openThroughPlugin("native-format-control.js", "const control = 1;", "javascript")
        assertFormattingRegistrationHasNoDocumentSelector()
        myFixture.configureFromExistingVirtualFile(file)
        assertTrue("The supported control must keep Biome active during native formatting",
            LspServerManager.getInstance(project)
                .getServersForProvider(BiomeLspServerSupportProvider::class.java)
                .any { it.state == com.intellij.platform.lsp.api.LspServerState.Running }
        )
        myFixture.performEditorAction("ReformatCode")
        assertEquals("Enabling Biome must preserve the complete native IDE output", nativeOutput,
            myFixture.editor.document.text)
        assertFalse("Excluded file must not be opened by Biome", messages("client").any {
            it.get("method")?.asString == "textDocument/didOpen" &&
                it.getAsJsonObject("params").getAsJsonObject("textDocument").get("uri").asString
                    .endsWith("/$name")
        })
    }

    protected fun formatThroughIde(name: String, input: String, languageId: String, expected: String) {
        openThroughPlugin(name, input, languageId)
        assertFormattingRegistrationHasNoDocumentSelector()
        myFixture.performEditorAction("ReformatCode")
        PlatformTestUtil.waitWithEventsDispatching("IDE formatting did not produce expected document", {
            myFixture.editor.document.text == expected
        }, 30)
        assertEquals(expected, myFixture.editor.document.text)
        assertTrue("IDE Reformat must reach Biome", messages("client").any {
            it.get("method")?.asString == "textDocument/formatting"
        })
    }

    protected fun saveThroughIde(name: String, input: String, languageId: String, expected: String) {
        val file = openThroughPlugin(name, input, languageId)
        // didOpen is sent before Biome finishes configuring the workspace. Await the
        // advertised formatter before editing/saving, as the Reformat case does.
        assertFormattingRegistrationHasNoDocumentSelector()
        BiomeSettings.getInstance(project).formatOnSave = true
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, " ") }
        // saveAllDocuments drives the real ActionsOnSave listener; saving one document is
        // ignored unless the IDE's SaveDocument action is marked as running.
        FileDocumentManager.getInstance().saveAllDocuments()
        PlatformTestUtil.waitWithEventsDispatching("Save did not persist Biome's complete result", {
            document.text == expected && Files.readString(file.toNioPath()) == expected &&
                !FileDocumentManager.getInstance().isDocumentUnsaved(document)
        }, 30)
        assertEquals(expected, document.text)
        assertEquals(expected, Files.readString(file.toNioPath()))
        assertTrue("Save must issue a real Biome formatting request", messages("client").any {
            it.get("method")?.asString == "textDocument/formatting"
        })
    }

    protected fun openThroughPlugin(name: String, input: String, languageId: String): VirtualFile {
        val file = myFixture.addFileToProject(name, input).virtualFile
        myFixture.configureFromExistingVirtualFile(file)
        waitUntilFileOpenedByLspServer(project, file,
            "com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor", timeout = 30)
        PlatformTestUtil.waitWithEventsDispatching("Missing didOpen for $name", {
            didOpen(name) != null
        }, 30)
        val document = didOpen(name)!!
        assertEquals("Wrong didOpen language identity for $name", languageId, document.get("languageId").asString)
        assertEquals(input, document.get("text").asString)
        return file
    }

    protected fun assertFormattingRegistrationHasNoDocumentSelector() {
        PlatformTestUtil.waitWithEventsDispatching("Biome did not register formatting", {
            formattingRegistrations().isNotEmpty()
        }, 30)
        assertTrue("A global registration cannot establish SVG support", formattingRegistrations().all {
            val options = it.getAsJsonObject("registerOptions")
            options == null || !options.has("documentSelector") || options.get("documentSelector").isJsonNull
        })
    }

    private fun formattingRegistrations(): List<JsonObject> = messages("server")
        .filter { it.get("method")?.asString == "client/registerCapability" }
        .flatMap { it.getAsJsonObject("params").getAsJsonArray("registrations").map { item -> item.asJsonObject } }
        .filter { it.get("method").asString == "textDocument/formatting" }

    private fun didOpen(name: String): JsonObject? = messages("client")
        .filter { it.get("method")?.asString == "textDocument/didOpen" }
        .map { it.getAsJsonObject("params").getAsJsonObject("textDocument") }
        .firstOrNull { it.get("uri").asString.endsWith("/$name") }

    private fun messages(direction: String): List<JsonObject> {
        if (!Files.exists(transcript)) return emptyList()
        val text = Files.readString(transcript)
        // The relay may currently be appending a record; parse only complete lines.
        return text.take(text.lastIndexOf('\n') + 1).lineSequence().filter { it.isNotBlank() }
            .map { JsonParser.parseString(it).asJsonObject }
            .filter { it.get("direction").asString == direction }
            .map { it.getAsJsonObject("message") }.toList()
    }
}

@TestNpmPackage("@biomejs/biome@2.5.15")
class BiomeLanguageLspTest : BiomeLanguageLspTestBase("2.5.15") {
    fun testDefaultGritUsesGritIdentityAndFormats() {
        formatThroughIde("example.grit", GRIT_INPUT, "grit", GRIT_EXPECTED)
    }

    fun testDisabledPluginSavesGritWithoutStartingOrFormatting() {
        assertDisabledPluginDoesNotStartOrFormatGrit()
    }

    fun testDefaultSvgPreservesNativeIdeFormatting() {
        assertIdeFormattingPreservedForExcludedFile("icon.svg", "<svg><path d=\"M0 0\"/></svg>")
    }

    fun testArbitraryXmlPreservesNativeIdeFormatting() {
        assertIdeFormattingPreservedForExcludedFile("data.xml", "<root><child value=\"1\"/></root>")
    }

    fun testGritFormatsAndPersistsThroughActualSave() {
        saveThroughIde("saved.grit", GRIT_INPUT, "grit", GRIT_EXPECTED)
    }

    fun testExplicitSvgUsesSdkIdentityAndReturnsNoEditsWithHtmlEnabled() {
        myFixture.addFileToProject("biome.json", "{\"html\":{\"formatter\":{\"enabled\":true}}}")
        BiomeSettings.getInstance(project).supportedExtensions = mutableListOf(".svg")
        formatThroughPlugin("icon.svg", SVG_INPUT, "svg", SVG_INPUT)
        assertFormattingRegistrationHasNoDocumentSelector()
    }

    fun testExplicitSvgReturnsNoEditsWithHtmlDisabled() {
        myFixture.addFileToProject("biome.json", "{\"html\":{\"formatter\":{\"enabled\":false}}}")
        BiomeSettings.getInstance(project).supportedExtensions = mutableListOf(".svg")
        formatThroughPlugin("icon.svg", SVG_INPUT, "svg", SVG_INPUT)
        assertFormattingRegistrationHasNoDocumentSelector()
    }
}

@TestNpmPackage("@biomejs/biome@2.2.3")
class OlderBiomeLanguageLspTest : BiomeLanguageLspTestBase("2.2.3") {
    fun testDefaultGritFormatsAfterServerInitialization() {
        startAndInitializeServerBeforeLanguageDocument()
        formatThroughIde("example.grit", GRIT_INPUT, "grit", GRIT_EXPECTED)
    }

    fun testExplicitSvgReturnsNoEditsOnOlderBiome() {
        myFixture.addFileToProject("biome.json", "{\"html\":{\"formatter\":{\"enabled\":true}}}")
        startAndInitializeServerBeforeLanguageDocument()
        BiomeSettings.getInstance(project).supportedExtensions = mutableListOf(".svg")
        formatThroughPlugin("icon.svg", SVG_INPUT, "svg", SVG_INPUT)
        assertFormattingRegistrationHasNoDocumentSelector()
    }
}

@TestNpmPackage("@biomejs/biome@1.9.4")
class V1BiomeLanguageLspTest : BiomeLanguageLspTestBase("1.9.4") {
    fun testJavascriptStillFormatsWithV1() {
        formatThroughPlugin("control.js", "const a={b:1}", "javascript", "const a = { b: 1 };\n")
    }

    fun testUnsupportedGritRemainsUnchangedWithV1() {
        formatThroughPlugin("example.grit", GRIT_INPUT, "grit", GRIT_INPUT)
    }
}
