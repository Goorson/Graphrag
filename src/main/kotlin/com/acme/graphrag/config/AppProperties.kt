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

@ConfigurationProperties(prefix = "app.neo4j")
data class Neo4jProperties(
    val uri: String = "bolt://localhost:7687",
    val username: String = "neo4j",
    val password: String = "changeme",
)

@ConfigurationProperties(prefix = "app.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,
    val askPerMinute: Int = 30,
    val ingestPerMinute: Int = 10,
    val chatPerMinute: Int = 30,
)

@ConfigurationProperties(prefix = "app.security")
data class SecurityProperties(
    val apiKey: String? = null,
)
