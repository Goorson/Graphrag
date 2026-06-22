package com.acme.graphrag.service.chunking

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownChunkerTest {

    private val chunker = MarkdownChunker()

    @Test
    fun `splits markdown by sections`() {
        val markdown = """
            # Title
            ## Zespół
            Jan Kowalski — tech lead
            ## Ryzyka
            Opóźnienie integracji.
        """.trimIndent()

        val chunks = chunker.chunk(markdown)

        assertTrue(chunks.size >= 2)
        assertTrue(chunks.any { it.section == "Zespół" && it.content.contains("Jan Kowalski") })
        assertTrue(chunks.any { it.section == "Ryzyka" })
    }
}
