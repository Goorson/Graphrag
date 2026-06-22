package com.acme.graphrag.service

import com.acme.graphrag.config.LlmGateway
import org.springframework.stereotype.Service

@Service
class EmbeddingService(
    private val llmGateway: LlmGateway,
) {

    fun embed(texts: List<String>): List<FloatArray> = llmGateway.embedTexts(texts)

    fun embedOne(text: String): FloatArray = embed(listOf(text)).first()
}
