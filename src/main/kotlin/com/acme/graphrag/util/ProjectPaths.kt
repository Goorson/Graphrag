package com.acme.graphrag.util

import java.nio.file.Path
import kotlin.io.path.name

object ProjectPaths {

    private val projectRoot: Path
        get() = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

    fun resolveRelative(relativePath: String): Path {
        val normalized = relativePath.replace('\\', '/').trim()
        require(normalized.isNotBlank()) { "Ścieżka nie może być pusta" }
        require(!normalized.contains("..")) { "Ścieżka poza katalogiem projektu" }

        val resolved = projectRoot.resolve(normalized).normalize()
        require(resolved.startsWith(projectRoot)) { "Ścieżka poza katalogiem projektu" }
        return resolved
    }

    fun safeFilename(originalName: String): String {
        val name = Path.of(originalName).fileName.name
        require(name.isNotBlank()) { "Nieprawidłowa nazwa pliku" }
        require(!name.contains("..")) { "Nieprawidłowa nazwa pliku" }
        return name
    }
}
