package com.github.biomejs.intellijbiome.lsp

import com.intellij.lang.javascript.modules.TestNpmPackage

@TestNpmPackage("@biomejs/biome@2.2.3")
class BiomeManualConfigLspTest : BiomeLspFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        setUpLspFixture(MANUAL_CONFIG_FIXTURE)
    }

    fun testSelectedJsoncUsesSingleQuotes() {
        myFixture.configurePinnedBiome("2.2.3", myFixture.findFileInTempDir("biome.jsonc")!!.path)
        myFixture.formatManualConfig("index.single.expected.js")
    }

    fun testSelectedJsonUsesDoubleQuotes() {
        myFixture.configurePinnedBiome("2.2.3", myFixture.findFileInTempDir("biome.json")!!.path)
        myFixture.formatManualConfig("index.double.expected.js")
    }

    fun testLegacyDirectoryUsesDoubleQuotes() {
        myFixture.configurePinnedBiome("2.2.3", myFixture.tempDirFixture.getFile(".")!!.path)
        myFixture.formatManualConfig("index.double.expected.js")
    }
}
