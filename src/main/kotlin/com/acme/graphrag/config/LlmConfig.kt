package com.acme.graphrag.config

import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.ollama.OllamaEmbeddingModel
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
@EnableConfigurationProperties(LlmProperties::class, RagProperties::class, IngestProperties::class, Neo4jProperties::class)
class LlmConfig(
    private val llmProperties: LlmProperties,
) {

    @Bean
    fun embeddingModel(): EmbeddingModel =
        OllamaEmbeddingModel.builder()
            .baseUrl(llmProperties.baseUrl)
            .modelName(llmProperties.embeddingModel)
            .timeout(Duration.ofMinutes(2))
            .build()

    @Bean
    fun chatLanguageModel(): ChatLanguageModel =
        OllamaChatModel.builder()
            .baseUrl(llmProperties.baseUrl)
            .modelName(llmProperties.chatModel)
            .temperature(0.2)
            .timeout(Duration.ofMinutes(3))
            .build()
}
