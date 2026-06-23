package com.acme.graphrag.service

import com.acme.graphrag.domain.ChunkSearchResult
import com.acme.graphrag.repository.ChunkRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DocumentChunkExpander(
    private val chunkRepository: ChunkRepository,
) {

    private val resumeFilenamePattern = Regex("""(?i)resume|cv|życiorys|zyciorys|curriculum""")
    private val employmentQueryPattern = Regex(
        """(?i)zatrudnien|employment|pracowa|praca|karier|historia|doświadczen|doswiadczen|experience|work history""",
    )

    fun expand(
        hits: List<ChunkSearchResult>,
        searchQuery: String,
        maxChunks: Int = 14,
    ): List<ChunkSearchResult> {
        if (hits.isEmpty()) return hits

        val expand = employmentQueryPattern.containsMatchIn(searchQuery) ||
            hits.any { resumeFilenamePattern.containsMatchIn(it.filename) }
        if (!expand) return hits

        val documentIds = hits
            .map { it.documentId }
            .distinct()
            .filter { docId ->
                val filename = hits.first { it.documentId == docId }.filename
                employmentQueryPattern.containsMatchIn(searchQuery) ||
                    resumeFilenamePattern.containsMatchIn(filename)
            }

        if (documentIds.isEmpty()) return hits

        val merged = linkedMapOf<UUID, ChunkSearchResult>()
        hits.forEach { merged[it.chunkId] = it }

        documentIds.forEach { documentId ->
            chunkRepository.findAllByDocumentId(documentId).forEach { chunk ->
                merged[chunk.chunkId] = chunk
            }
        }

        return merged.values
            .sortedWith(
                compareByDescending<ChunkSearchResult> { hit ->
                    if (hit.chunkId in hits.map { it.chunkId }.toSet()) hit.score else 0.0
                }.thenBy { it.filename }
                    .thenBy { it.page ?: Int.MAX_VALUE }
                    .thenBy { it.chunkId },
            )
            .take(maxChunks)
    }
}
