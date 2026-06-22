package com.acme.graphrag.service

import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import org.springframework.stereotype.Service

@Service
class EmbeddingService(
    private val embeddingModel: EmbeddingModel,
) {

    fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        return texts.chunked(BATCH_SIZE).flatMap { batch ->
            val segments = batch.map { TextSegment.from(it) }
            embeddingModel.embedAll(segments).content().map(Embedding::vector)
        }
    }

    fun embedOne(text: String): FloatArray = embed(listOf(text)).first()

    companion object {
        private const val BATCH_SIZE = 16
    }
}
