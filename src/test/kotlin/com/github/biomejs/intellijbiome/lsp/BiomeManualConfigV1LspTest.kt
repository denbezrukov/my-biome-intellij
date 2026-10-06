package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.lang.javascript.modules.TestNpmPackage
import java.nio.file.Files
import java.nio.file.Path

@TestNpmPackage("@biomejs/biome@1.9.4")
class BiomeManualConfigV1LspTest : BiomeLspFixtureTestCase() {
    private lateinit var argumentsLog: Path

    override fun setUp() {
        super.setUp()
        assertTrue("The required v1 launch tests run on Linux", System.getProperty("os.name").startsWith("Linux"))
        setUpLspFixture(MANUAL_CONFIG_FIXTURE)
        for (name in listOf("biome.json", "biome.jsonc", "index.js")) {
            myFixture.copyFileToProject("$MANUAL_CONFIG_FIXTURE/$name", "space dir/$name")
        }
    }

    fun testVersion1LaunchPreservesSelectedJsonc() {
        val selected = myFixture.findFileInTempDir("space dir/biome.jsonc")!!.path
        configureRecordingLauncher(selected)
        myFixture.formatManualConfig("index.single.expected.js", "space dir/index.js")
        assertArguments(listOf("lsp-proxy", "--config-path", selected))
    }

    fun testVersion1LegacyDirectoryLaunch() {
        val selected = myFixture.findFileInTempDir("space dir")!!.path
        configureRecordingLauncher(selected)
        myFixture.formatManualConfig("index.double.expected.js", "space dir/index.js")
        assertArguments(listOf("lsp-proxy", "--config-path", selected))
    }

    fun testVersion1EmptyOverrideOmitsConfigArgument() {
        configureRecordingLauncher(" \t\n")
        myFixture.formatManualConfig("index.double.expected.js", "space dir/index.js")
        assertArguments(listOf("lsp-proxy"))
    }

    private fun configureRecordingLauncher(configPath: String) {
        val installed = myFixture.configurePinnedBiome("1.9.4", configPath)
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
