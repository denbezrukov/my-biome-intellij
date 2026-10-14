package com.github.biomejs.intellijbiome.lsp

import java.nio.file.Path
import kotlin.io.path.isRegularFile

internal class BiomeV1ConfigPath(configPath: String, workingDirectory: Path) {
    // v1 reads CLI paths outside LSP workspace-relative configuration resolution.
    val selected: Path = workingDirectory.resolve(configPath)
    val directory: Path = if (selected.isRegularFile()) {
        selected.parent ?: selected
    } else selected

    fun forVersion(version: String): Path {
        val minor = version.substringAfter("1.").substringBefore('.').toIntOrNull()
        // Biome 1.7 introduced exact config-file selection; earlier v1 needs its directory.
        return if (version.startsWith("1.") && minor != null && minor < 7) directory else selected
    }
}
