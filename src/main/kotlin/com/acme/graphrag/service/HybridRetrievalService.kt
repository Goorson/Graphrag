package com.acme.graphrag.service

import com.acme.graphrag.config.RagProperties
import com.acme.graphrag.domain.ChunkSearchResult
import com.acme.graphrag.domain.RetrievalMode
import com.acme.graphrag.repository.ChunkRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class HybridRetrievalService(
    private val chunkRepository: ChunkRepository,
    private val ragProperties: RagProperties,
    private val documentChunkExpander: DocumentChunkExpander,
) {

    fun retrieve(
        queryEmbedding: FloatArray,
        question: String,
        mode: RetrievalMode,
        documentIds: List<UUID>? = null,
    ): List<ChunkSearchResult> =
        when (mode) {
            RetrievalMode.VECTOR -> chunkRepository.searchSimilar(queryEmbedding, ragProperties.topK, documentIds)
            RetrievalMode.HYBRID -> hybridSearch(queryEmbedding, question, documentIds)
            RetrievalMode.GRAPH_RAG -> hybridSearch(queryEmbedding, question, documentIds)
            RetrievalMode.GRAPH -> emptyList()
        }

    private fun hybridSearch(
        queryEmbedding: FloatArray,
        question: String,
        documentIds: List<UUID>? = null,
    ): List<ChunkSearchResult> {
        val vectorHits = chunkRepository.searchSimilar(queryEmbedding, ragProperties.candidateK, documentIds)
        val keywordHits = chunkRepository.searchKeyword(question, ragProperties.candidateK, documentIds)

        val scores = mutableMapOf<UUID, Double>()
        val byId = mutableMapOf<UUID, ChunkSearchResult>()

        vectorHits.forEachIndexed { rank, hit ->
            val id = hit.chunkId
            scores[id] = (scores[id] ?: 0.0) + rrf(rank + 1)
            byId.putIfAbsent(id, hit)
        }
        keywordHits.forEachIndexed { rank, hit ->
            val id = hit.chunkId
            scores[id] = (scores[id] ?: 0.0) + rrf(rank + 1)
            byId.putIfAbsent(id, hit)
        }

        return documentChunkExpander.expand(
            hits = scores.entries
                .sortedByDescending { it.value }
                .take(ragProperties.topK)
                .map { (id, score) -> byId.getValue(id).copy(score = score) },
            searchQuery = question,
        )
    }

    private fun rrf(rank: Int, k: Int = RRF_K): Double = 1.0 / (k + rank)

    companion object {
        private const val RRF_K = 60
    }
}
