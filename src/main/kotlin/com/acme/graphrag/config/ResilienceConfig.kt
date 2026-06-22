package com.acme.graphrag.config

import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.embedding.EmbeddingModel
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service

class RecoverableAiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Configuration
@EnableConfigurationProperties(RateLimitProperties::class, SecurityProperties::class)
class ResilienceConfig

@Service
class LlmGateway(
    private val embeddingModel: EmbeddingModel,
    private val chatLanguageModel: ChatLanguageModel,
) {

    @Retry(name = "llm")
    @CircuitBreaker(name = "llm", fallbackMethod = "embedFallback")
    fun embedTexts(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return texts.chunked(BATCH_SIZE).flatMap { batch ->
            val segments = batch.map { TextSegment.from(it) }
            embeddingModel.embedAll(segments).content().map { it.vector() }
        }
    }

    @Suppress("unused")
    fun embedFallback(texts: List<String>, throwable: Throwable): List<FloatArray> {
        throw RecoverableAiException("AI service temporarily unavailable", throwable)
    }

    @Retry(name = "llm")
    @CircuitBreaker(name = "llm", fallbackMethod = "generateFallback")
    fun generate(prompt: String): String = chatLanguageModel.generate(prompt)

    @Suppress("unused")
    fun generateFallback(prompt: String, throwable: Throwable): String {
        throw RecoverableAiException("AI service temporarily unavailable", throwable)
    }

    companion object {
        private const val BATCH_SIZE = 16
    }
}
