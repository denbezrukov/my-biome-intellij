package com.github.biomejs.intellijbiome.lsp

import com.github.biomejs.intellijbiome.services.BiomeServerService
import com.github.biomejs.intellijbiome.settings.BiomeSettings
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import kotlinx.coroutines.*
import org.junit.Assert.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal const val MANUAL_CONFIG_FIXTURE = "manual-config-selection"
private const val DESCRIPTOR = "com.github.biomejs.intellijbiome.lsp.BiomeLspServerDescriptor"

internal fun CodeInsightTestFixture.installedBiome(version: String, subdir: String = ""): Path {
    val root = Path.of(tempDirFixture.getFile(".")!!.path).resolve(subdir)
    val executable = root.resolve("node_modules/.bin/biome")
    assertTrue("Pinned Biome executable missing: $executable", Files.isRegularFile(executable))
    val output = runBiomeCommand(listOf(executable.toString(), "--version"), root)
    assertEquals("Version: $version", output.trim())
    return executable
}

internal fun CodeInsightTestFixture.configurePinnedBiome(version: String, configPath: String): Path {
    // Check installation before the existing helper's optional override/assumption logic.
    val installed = installedBiome(version)
    configureBiomeForLspTests(configPath)
    assertEquals("A global override must not replace the pinned fixture executable",
        installed.toString(), BiomeSettings.getInstance(project).executablePath)
    return installed
}

internal fun runBiomeCommand(arguments: List<String>, directory: Path, input: String = ""): String {
    val stdout = Files.createTempFile("biome-contract-", ".stdout")
    val stderr = Files.createTempFile("biome-contract-", ".stderr")
    var process: Process? = null
    try {
        process = ProcessBuilder(arguments).directory(directory.toFile())
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(input) }
        assertTrue("Biome timed out after 30 seconds: $arguments", process.waitFor(30, TimeUnit.SECONDS))
        assertEquals("Biome failed: ${Files.readString(stderr)}", 0, process.exitValue())
        return Files.readString(stdout)
    } finally {
        process?.let {
            if (it.isAlive) {
                it.descendants().use { children -> children.forEach { child -> child.destroyForcibly() } }
                it.destroyForcibly()
                check(it.waitFor(5, TimeUnit.SECONDS)) { "Biome did not terminate after forced cleanup" }
            }
        }
        Files.deleteIfExists(stdout)
        Files.deleteIfExists(stderr)
    }
}

internal fun CodeInsightTestFixture.formatManualConfig(expectedName: String, sourceName: String = "index.js") {
    val source = findFileInTempDir(sourceName) ?: error("Missing fixture: $sourceName")
    configureFromExistingVirtualFile(source)
    waitUntilFileOpenedByLspServer(project, source, DESCRIPTOR, timeout = 30)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    val formatting = scope.async {
        withTimeout(30_000) { project.service<BiomeServerService>().format(editor.document) }
    }
    try {
        PlatformTestUtil.waitWithEventsDispatching("Biome formatting did not finish in 30 seconds",
            { formatting.isCompleted }, 30)
        runBlocking { formatting.await() }
        assertEquals(Files.readString(Path.of(testDataPath, MANUAL_CONFIG_FIXTURE, expectedName)), editor.document.text)
    } finally {
        scope.cancel()
    }
}
