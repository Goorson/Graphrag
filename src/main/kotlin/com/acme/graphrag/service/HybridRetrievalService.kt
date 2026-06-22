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
) {

    fun retrieve(queryEmbedding: FloatArray, question: String, mode: RetrievalMode): List<ChunkSearchResult> =
        when (mode) {
            RetrievalMode.VECTOR -> chunkRepository.searchSimilar(queryEmbedding, ragProperties.topK)
            RetrievalMode.HYBRID -> hybridSearch(queryEmbedding, question)
        }

    private fun hybridSearch(queryEmbedding: FloatArray, question: String): List<ChunkSearchResult> {
        val vectorHits = chunkRepository.searchSimilar(queryEmbedding, ragProperties.candidateK)
        val keywordHits = chunkRepository.searchKeyword(question, ragProperties.candidateK)

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

        return scores.entries
            .sortedByDescending { it.value }
            .take(ragProperties.topK)
            .map { (id, score) -> byId.getValue(id).copy(score = score) }
    }

    private fun rrf(rank: Int, k: Int = RRF_K): Double = 1.0 / (k + rank)

    companion object {
        private const val RRF_K = 60
    }
}
