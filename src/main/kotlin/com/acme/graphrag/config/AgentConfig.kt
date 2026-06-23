package com.acme.graphrag.config

import com.acme.graphrag.service.agent.KnowledgeAssistant
import com.acme.graphrag.service.agent.KNOWLEDGE_ASSISTANT_SYSTEM_PROMPT
import com.acme.graphrag.service.agent.KnowledgeTools
import com.acme.graphrag.service.agent.PersistentChatMemoryStore
import dev.langchain4j.memory.chat.ChatMemoryProvider
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.service.AiServices
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "app.agent")
data class AgentProperties(
    val maxSteps: Int = 8,
    val memoryWindow: Int = 10,
    val timeoutSeconds: Long = 120,
)

@Configuration
@EnableConfigurationProperties(AgentProperties::class)
class AgentConfig(
    private val agentProperties: AgentProperties,
) {

    @Bean
    fun chatMemoryProvider(chatMemoryStore: PersistentChatMemoryStore): ChatMemoryProvider =
        ChatMemoryProvider { memoryId ->
            MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(agentProperties.memoryWindow)
                .chatMemoryStore(chatMemoryStore)
                .build()
        }

    @Bean
    fun knowledgeAssistant(
        chatLanguageModel: ChatLanguageModel,
        knowledgeTools: KnowledgeTools,
        chatMemoryProvider: ChatMemoryProvider,
    ): KnowledgeAssistant =
        AiServices.builder(KnowledgeAssistant::class.java)
            .chatLanguageModel(chatLanguageModel)
            .tools(knowledgeTools)
            .chatMemoryProvider(chatMemoryProvider)
            .systemMessageProvider { KNOWLEDGE_ASSISTANT_SYSTEM_PROMPT }
            .build()
}
