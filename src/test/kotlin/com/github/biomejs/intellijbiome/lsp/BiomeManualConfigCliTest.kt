package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.lang.javascript.modules.TestNpmPackage
import com.intellij.lang.javascript.modules.TestNpmPackageInstaller
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import java.nio.file.Files
import java.nio.file.Path
import org.junit.runners.model.MultipleFailureException

class BiomeManualConfigCliTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    @TestNpmPackage("@biomejs/biome@1.9.4")
    private class Version1

    @TestNpmPackage("@biomejs/biome@2.2.3")
    private class Version2

    fun testVersion1SelectionContract() = checkSelection(Version1::class.java, "1.9.4")

    fun testVersion2SelectionContract() = checkSelection(Version2::class.java, "2.2.3")

    private fun checkSelection(packageClass: Class<*>, version: String) {
        myFixture.testDataPath = "src/test/testData/lsp/highlighting"
        val installSubdir = "version-$version"
        myFixture.tempDirFixture.findOrCreateDir(installSubdir)
        val root = myFixture.tempDirFixture.getFile(".")!!
        TestNpmPackageInstaller(myFixture).installForTest(packageClass, root, installSubdir)
        val executable = myFixture.installedBiome(version, installSubdir)
        val source = myFixture.copyFileToProject("$MANUAL_CONFIG_FIXTURE/index.js", "space dir/index.js")
        val json = myFixture.copyFileToProject("$MANUAL_CONFIG_FIXTURE/biome.json", "space dir/biome.json")
        val jsonc = myFixture.copyFileToProject("$MANUAL_CONFIG_FIXTURE/biome.jsonc", "space dir/biome.jsonc")
        val settings = BiomeSettings.getInstance(project)
        val failures = mutableListOf<Throwable>()
        for ((selection, expected) in listOf(jsonc.path to "single", json.path to "double", json.parent.path to "double")) {
            settings.configPath = selection
            try {
                val actual = runBiomeCommand(listOf(executable.toString(), "format", "--config-path", settings.configPath,
                    "--stdin-file-path", source.path), Path.of(root.path), Files.readString(source.toNioPath()))
                assertEquals("Biome $version with selected path $selection (stored ${settings.configPath})",
                    Files.readString(Path.of(myFixture.testDataPath, MANUAL_CONFIG_FIXTURE, "index.$expected.expected.js")), actual)
            } catch (failure: AssertionError) {
                // Retain JSON/directory controls even when the JSONC regression fails on the baseline setter.
                failures.add(failure)
            }
        }
        MultipleFailureException.assertEmpty(failures)
    }
}
