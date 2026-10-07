package com.github.biomejs.intellijbiome.launcher

import com.github.biomejs.intellijbiome.fixtures.StartupProbeProcess
import com.github.biomejs.intellijbiome.lsp.BiomeLspServerSupportProvider
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.github.biomejs.intellijbiome.settings.ConfigurationMode
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.ModuleFixture
import com.intellij.util.EnvironmentUtil
import com.intellij.util.ui.UIUtil
import org.eclipse.lsp4j.ConfigurationItem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** Exercises configured Node dispatch through SDK manager startup with a controlled wire peer. */
class BiomeLauncherLspTest : CodeInsightFixtureTestCase<ModuleFixtureBuilder<ModuleFixture>>() {
    fun testVersion1DescriptorPreservesSelectedLauncherAndConfig() = checkDescriptor("1.9.4", true)
    fun testVersion2DescriptorPreservesSelectedLauncherAndConfig() = checkDescriptor("2.2.3", false)

    private fun checkDescriptor(version: String, usesConfigArgument: Boolean) {
        assertTrue("Required launcher process tests run on Linux", System.getProperty("os.name").startsWith("Linux"))
        val original = EnvironmentUtil.getEnvironmentMap().toMap()
        val root = Path.of(myFixture.tempDirPath)
        val node = original.getValue("PATH").split(java.io.File.pathSeparator)
            .map { Path.of(it, "node") }.firstOrNull(Files::isExecutable) ?: error("Required Node executable not found")
        val configured = root.resolve("configured node with spaces")
        Files.createSymbolicLink(configured, node)
        val manager = NodeJsInterpreterManager.getInstance(project)
        manager.setInterpreterRef(NodeJsInterpreterRef.create(NodeJsLocalInterpreter(configured.toString())), testRootDisposable)
        val log = root.resolve("selected launcher invocations.jsonl")
        val launcher = root.resolve("selected node launcher")
        val fixtureClasses = root.resolve("fixture classes with spaces")
        val fixtureName = StartupProbeProcess::class.java.name.replace('.', '/') + ".class"
        val fixtureClass = fixtureClasses.resolve(fixtureName)
        Files.createDirectories(fixtureClass.parent)
        StartupProbeProcess::class.java.classLoader.getResourceAsStream(fixtureName)!!.use {
            Files.copy(it, fixtureClass, StandardCopyOption.REPLACE_EXISTING)
        }
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        Files.writeString(launcher, """
            |#!/usr/bin/env node
            |const fs = require('fs');
            |const args = process.argv.slice(2);
            |fs.appendFileSync(${json(log)}, JSON.stringify({args, path: process.env.PATH, cwd: process.cwd()}) + '\n');
            |if (args[0] === '--version') console.log('Version: $version');
            |else if (args[0] === 'lsp-proxy') process.exit(require('child_process').spawnSync(${json(java)}, ['-cp', ${json(fixtureClasses)}, '${StartupProbeProcess::class.java.name}', 'lsp'], {stdio: 'inherit'}).status ?? 12);
            |else process.exit(12);
            |
        """.trimMargin())
        assertTrue(launcher.toFile().setExecutable(true))
        val config = myFixture.addFileToProject("selected config with spaces/biome.json", "{}").virtualFile.parent.path
        val file = myFixture.addFileToProject("index.js", "let value = 1;").virtualFile
        val settings = BiomeSettings.getInstance(project)
        settings.configurationMode = ConfigurationMode.MANUAL
        settings.executablePath = launcher.toString()
        settings.configPath = config
        var selectedDescriptor: LspServerDescriptor? = null
        BiomeLspServerSupportProvider().fileOpened(project, file, object : LspServerSupportProvider.LspServerStarter {
            override fun ensureServerStarted(descriptor: LspServerDescriptor) { selectedDescriptor = descriptor }
        })
        val selected = selectedDescriptor ?: error("Registered provider did not select the launcher")
        assertFalse("Descriptor selection must not execute a subprocess", Files.exists(log))

        // A descriptor is a snapshot: later settings must not substitute a launcher/interpreter/config.
        settings.executablePath = root.resolve("different missing executable").toString()
        settings.configPath = ""
        manager.setInterpreterRef(NodeJsInterpreterRef.create(NodeJsLocalInterpreter(root.resolve("different missing node").toString())))
        val path = Files.createDirectories(root.resolve("path without node"))
        setTestEnvironment(original + ("PATH" to path.toString()))
        try {
            startThroughManager(selected)
            val records = Files.readAllLines(log)
            val targetPath = "$root:$path"
            val expectedArgs = if (usesConfigArgument) "[\"lsp-proxy\",\"--config-path\",${json(Path.of(config))}]" else "[\"lsp-proxy\"]"
            assertEquals(listOf(
                "{\"args\":[\"--version\"],\"path\":\"$targetPath\",\"cwd\":${json(root)}}",
                "{\"args\":$expectedArgs,\"path\":\"$targetPath\",\"cwd\":${json(root)}}"
            ), records)
            val configuration = selected.getWorkspaceConfiguration(ConfigurationItem().apply { section = "biome" })
            assertNotNull(configuration)
            assertEquals(config, (configuration as com.github.biomejs.intellijbiome.lsp.BiomeLspWorkspaceSettings).configurationPath)
        } finally {
            setTestEnvironment(original)
        }
    }

    private fun startThroughManager(descriptor: LspServerDescriptor) {
        val manager = LspServerManager.getInstance(project)
        manager.ensureServerStarted(BiomeLspServerSupportProvider::class.java, descriptor)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
                val server = manager.getServersForProvider(BiomeLspServerSupportProvider::class.java)
                    .find { it.descriptor === descriptor }
                if (server?.state == LspServerState.Running) return
                if (server?.state == LspServerState.ShutdownUnexpectedly) fail("Configured Node server startup failed")
                Thread.sleep(10)
            }
            fail("Configured Node server did not initialize through the SDK manager")
        } finally {
            manager.stopServers(BiomeLspServerSupportProvider::class.java)
        }
    }

    private fun json(path: Path): String = "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
