package com.github.biomejs.intellijbiome.lsp

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** These controls fail if a newer-runtime lane silently falls back to the compile SDK. */
class BiomeIdeRuntimeTest : BasePlatformTestCase() {
    override fun setUp() {
        val expected = System.getProperty("biome.test.expected.build", "")
        val kotlinVersion = KotlinVersion.CURRENT.toString()
        println("BIOME_KOTLIN_RUNTIME_VERSION=$kotlinVersion")
        when {
            expected.startsWith("WS-262.") -> assertEquals("2.4.0", kotlinVersion)
        }
        super.setUp()
    }
    fun testPinnedIdeRuntimeIsActuallyLoaded() {
        val expected = System.getProperty("biome.test.expected.build")
        assertNotNull("The runtime lane must declare its exact IDE build", expected)
        assertEquals(expected, ApplicationInfo.getInstance().build.asString())
        println("BIOME_RUNTIME_IDENTITY=${ApplicationInfo.getInstance().build.asString()}; java=${System.getProperty("java.runtime.version")}")
    }

    fun testMinimumCompiledPluginIsLoadedInRuntime() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("com.github.biomejs.intellijbiome"))
            ?: error("Biome plugin was not loaded into the test IDE")
        assertTrue("Biome must be enabled in the target IDE", plugin.isEnabled)
        assertNotNull("Biome must have a live plugin classloader (a disabled dependency graph is insufficient)", plugin.pluginClassLoader)
        assertEquals("253", plugin.sinceBuild)
        val runtime = System.getProperty("biome.test.expected.build", "")
        if (runtime.startsWith("WS-262.")) {
            for (id in listOf("JavaScript", "NodeJS", "com.intellij.modules.json", "JavaScriptDebugger")) {
                val dependency = PluginManagerCore.getPlugin(PluginId.getId(id))
                    ?: error("Required target runtime plugin $id is absent")
                assertTrue("Required target runtime plugin $id must be enabled", dependency.isEnabled)
                assertNotNull("Required target runtime plugin $id needs a live classloader", dependency.pluginClassLoader)
            }
        }
        val expected = System.getProperty("biome.test.compile.build")
        assertEquals("The published plugin must use the minimum SDK", "WS-253.28294.332", expected)
        assertNotNull(BiomeLspServerDescriptor::class.java.protectionDomain.codeSource)
    }
}
