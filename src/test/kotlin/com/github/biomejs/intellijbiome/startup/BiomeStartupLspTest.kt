package com.github.biomejs.intellijbiome.startup

import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import java.nio.file.Files
import java.nio.file.Path

class BiomeStartupLspTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    private lateinit var invocationLog: Path
    private lateinit var executable: Path
    private val descriptors = mutableListOf<LspServerDescriptor>()
    private val starter = object : LspServerSupportProvider.LspServerStarter {
        override fun ensureServerStarted(descriptor: LspServerDescriptor) {
            descriptors += descriptor
        }
    }

    override fun setUp() {
        super.setUp()
        descriptors.clear()
        invocationLog = Path.of(myFixture.tempDirPath, "invocations.log")
        executable = Path.of(myFixture.tempDirPath, "biome-probe")
        Files.writeString(executable, "#!/bin/sh\nprintf '%s\\n' \"\$*\" >> ${quote(invocationLog.toString())}\nprintf 'Version: 2.2.3\\n'\n")
        check(executable.toFile().setExecutable(true)) { "Cannot make the startup fixture executable" }
        myFixture.addFileToProject("biome.json", "{}")
        BiomeSettings.getInstance(project).apply {
            configurationMode = ConfigurationMode.MANUAL
            executablePath = executable.toString()
            configPath = ""
        }
    }

    fun testUnsupportedFileDoesNotProbeOrRequestServerStart() {
        val file = myFixture.addFileToProject("notes.unsupported", "plain text").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        assertFalse("Unsupported files must not invoke the executable", Files.exists(invocationLog))
        assertEmpty(descriptors)
    }

    fun testDisabledPluginDoesNotProbeOrRequestServerStart() {
        BiomeSettings.getInstance(project).configurationMode = ConfigurationMode.DISABLED
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        assertFalse("Disabled projects must not invoke the executable", Files.exists(invocationLog))
        assertEmpty(descriptors)
    }

    fun testSupportedFileRequestsServerStart() {
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        BiomeLspServerSupportProvider().fileOpened(project, file, starter)
        assertEquals("The same fixture must be eligible when its extension is supported", 1, descriptors.size)
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
