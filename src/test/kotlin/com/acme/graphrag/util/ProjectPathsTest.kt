package com.acme.graphrag.util

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ProjectPathsTest {

    @Test
    fun `rejects path traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPaths.resolveRelative("../outside/secret.pdf")
        }
    }

    @Test
    fun `safeFilename strips directories`() {
        val name = ProjectPaths.safeFilename("../../evil.pdf")
        assert(name == "evil.pdf")
    }

    @Test
    fun `resolveRelative stays under project root`() {
        val resolved = ProjectPaths.resolveRelative("data/documents")
        val root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        assert(resolved.startsWith(root))
    }
}
