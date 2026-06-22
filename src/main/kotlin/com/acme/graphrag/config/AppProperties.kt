package com.acme.graphrag.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.llm")
data class LlmProperties(
    val baseUrl: String = "http://localhost:11434",
    val chatModel: String = "llama3.2",
    val embeddingModel: String = "nomic-embed-text",
    val embeddingDimension: Int = 768,
)

@ConfigurationProperties(prefix = "app.rag")
data class RagProperties(
    val topK: Int = 7,
    val candidateK: Int = 10,
)

@ConfigurationProperties(prefix = "app.ingest")
data class IngestProperties(
    val staleJobMinutes: Long = 30,
    val queueKey: String = "ingest:queue",
)
