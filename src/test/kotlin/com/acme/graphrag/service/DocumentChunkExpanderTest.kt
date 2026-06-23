package com.acme.graphrag.service

import com.acme.graphrag.domain.ChunkSearchResult
import com.acme.graphrag.repository.ChunkRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class DocumentChunkExpanderTest {

    private val chunkRepository: ChunkRepository = mock()
    private val expander = DocumentChunkExpander(chunkRepository)

    @Test
    fun `expands all chunks from resume document when employment is asked`() {
        val docId = UUID.randomUUID()
        val hit = chunk(
            chunkId = UUID.randomUUID(),
            documentId = docId,
            filename = "uploads/Resume_Igor_Karlik.pdf",
            page = 1,
            content = "header and contact",
            score = 0.9,
        )
        val otherChunk = chunk(
            chunkId = UUID.randomUUID(),
            documentId = docId,
            filename = "uploads/Resume_Igor_Karlik.pdf",
            page = 2,
            content = "Infor 2025 Accenture 2023",
            score = 1.0,
        )
        `when`(chunkRepository.findAllByDocumentId(docId)).thenReturn(listOf(hit, otherChunk))

        val expanded = expander.expand(listOf(hit), "Igor Karlik historia zatrudnienia")

        assertEquals(2, expanded.size)
        assertTrue(expanded.any { it.page == 2 })
    }

    private fun chunk(
        chunkId: UUID,
        documentId: UUID,
        filename: String,
        page: Int,
        content: String,
        score: Double,
    ) = ChunkSearchResult(
        chunkId = chunkId,
        documentId = documentId,
        content = content,
        section = "Strona $page",
        page = page,
        filename = filename,
        score = score,
    )
}
