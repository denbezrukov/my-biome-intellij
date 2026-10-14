package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.lang.javascript.modules.TestNpmPackage
import java.nio.file.Files
import java.nio.file.Path

@TestNpmPackage("@biomejs/biome@1.6.4")
class BiomeManualConfigV164LspTest : BiomeManualConfigLegacyLspTest("1.6.4", false)

@TestNpmPackage("@biomejs/biome@1.7.0")
class BiomeManualConfigV170LspTest : BiomeManualConfigLegacyLspTest("1.7.0", true)

@TestNpmPackage("@biomejs/biome@1.9.4")
class BiomeManualConfigV1LspTest : BiomeManualConfigLegacyLspTest("1.9.4", true)

abstract class BiomeManualConfigLegacyLspTest(
    private val version: String,
    private val supportsFilePath: Boolean,
) : BiomeLspFixtureTestCase() {
    private lateinit var argumentsLog: Path

    override fun setUp() {
        super.setUp()
        assertTrue("The required v1 launch tests run on Linux", System.getProperty("os.name").startsWith("Linux"))
        setUpLspFixture(MANUAL_CONFIG_FIXTURE)
        for (name in listOf("biome.json", "biome.jsonc", "index.js")) {
            myFixture.copyFileToProject("$MANUAL_CONFIG_FIXTURE/$name", "space dir/$name")
        }
    }

    fun testSelectedFileUsesVersionSupportedPath() {
        val selected = myFixture.findFileInTempDir("space dir/biome.jsonc")!!.path
        configureRecordingLauncher(selected)
        myFixture.formatManualConfig(
            if (supportsFilePath) "index.single.expected.js" else "index.double.expected.js", "space dir/index.js")
        assertArguments(listOf("lsp-proxy", "--config-path",
            if (supportsFilePath) selected else Path.of(selected).parent.toString()))
        assertEquals("Transport must not replace the stored selection", selected,
            BiomeSettings.getInstance(project).configPath)
    }

    fun testRelativeSelectedFileUsesVersionSupportedPath() {
        val selected = "biome.jsonc"
        configureRecordingLauncher(selected)
        myFixture.formatManualConfig(
            if (supportsFilePath) "index.single.expected.js" else "index.double.expected.js")
        val root = Path.of(myFixture.tempDirFixture.getFile(".")!!.path)
        assertArguments(listOf("lsp-proxy", "--config-path",
            (if (supportsFilePath) root.resolve(selected) else root).toString()))
        assertEquals(selected, BiomeSettings.getInstance(project).configPath)
    }

    fun testDirectoryPathIsPreserved() {
        val selected = myFixture.findFileInTempDir("space dir")!!.path
        configureRecordingLauncher(selected)
        myFixture.formatManualConfig("index.double.expected.js", "space dir/index.js")
        assertArguments(listOf("lsp-proxy", "--config-path", selected))
    }

    private fun configureRecordingLauncher(configPath: String) {
        val installed = myFixture.configurePinnedBiome(version, configPath)
        val root = Path.of(myFixture.tempDirFixture.getFile(".")!!.path)
        argumentsLog = root.resolve("recorded arguments.bin")
        val launcher = root.resolve("recording launcher")
        Files.writeString(launcher, """
            |#!/bin/sh
            |if [ "${'$'}1" = "lsp-proxy" ]; then
            |  printf '%s\000' "${'$'}@" > ${shellQuote(argumentsLog.toString())}
            |fi
            |exec ${shellQuote(installed.toString())} "${'$'}@"
            |
        """.trimMargin())
        assertTrue("Could not make recording launcher executable", launcher.toFile().setExecutable(true))
        BiomeSettings.getInstance(project).executablePath = launcher.toString()
    }

    private fun assertArguments(expected: List<String>) {
        assertTrue("The registered provider did not launch the recording wrapper", Files.isRegularFile(argumentsLog))
        val bytes = Files.readAllBytes(argumentsLog)
        assertTrue("Argument log must end in NUL", bytes.isNotEmpty() && bytes.last() == 0.toByte())
        assertEquals(expected, String(bytes, Charsets.UTF_8).dropLast(1).split('\u0000'))
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
