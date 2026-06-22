package com.acme.graphrag.service.agent

import com.acme.graphrag.repository.SessionMessageRepository
import com.acme.graphrag.repository.SessionMessageRole
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.store.memory.chat.ChatMemoryStore
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class PersistentChatMemoryStore(
    private val sessionMessageRepository: SessionMessageRepository,
) : ChatMemoryStore {

    override fun getMessages(memoryId: Any): List<ChatMessage> {
        val sessionId = UUID.fromString(memoryId.toString())
        return sessionMessageRepository.findBySessionId(sessionId)
            .filter { it.role == SessionMessageRole.USER || it.role == SessionMessageRole.ASSISTANT }
            .filter { message ->
                message.role != SessionMessageRole.ASSISTANT ||
                    !message.content.contains("toolName =")
            }
            .mapNotNull { toChatMessage(it.role, it.content) }
    }

    override fun updateMessages(memoryId: Any, messages: List<ChatMessage>) {
        val sessionId = UUID.fromString(memoryId.toString())
        val existingCount = sessionMessageRepository.countBySessionId(sessionId)
        val newMessages = messages.drop(existingCount)
        newMessages.forEach { message ->
            if (shouldSkipMessage(message)) return@forEach
            val role = toRole(message)
            val content = messageText(message)
            if (content.isNotBlank()) {
                sessionMessageRepository.insert(
                    id = UUID.randomUUID(),
                    sessionId = sessionId,
                    role = role,
                    content = content,
                )
            }
        }
    }

    override fun deleteMessages(memoryId: Any) {
        // Brak endpointu czyszczenia sesji — no-op
    }

    private fun shouldSkipMessage(message: ChatMessage): Boolean {
        when (message) {
            is ToolExecutionResultMessage -> return true
            is AiMessage -> {
                val text = message.text()?.trim().orEmpty()
                if (message.hasToolExecutionRequests() && text.isEmpty()) return true
                if (text.contains("toolName =")) return true
            }
        }
        return false
    }

    private fun toChatMessage(role: SessionMessageRole, content: String): ChatMessage? =
        when (role) {
            SessionMessageRole.USER -> UserMessage.from(content)
            SessionMessageRole.ASSISTANT -> AiMessage.from(content)
            SessionMessageRole.SYSTEM -> SystemMessage.from(content)
            SessionMessageRole.TOOL -> null
        }

    private fun toRole(message: ChatMessage): SessionMessageRole =
        when (message) {
            is UserMessage -> SessionMessageRole.USER
            is AiMessage -> SessionMessageRole.ASSISTANT
            is SystemMessage -> SessionMessageRole.SYSTEM
            else -> SessionMessageRole.TOOL
        }

    private fun messageText(message: ChatMessage): String =
        when (message) {
            is UserMessage -> message.singleText()
            is AiMessage -> message.text() ?: ""
            is SystemMessage -> message.text()
            is ToolExecutionResultMessage -> message.text()
            else -> ""
        }
}
