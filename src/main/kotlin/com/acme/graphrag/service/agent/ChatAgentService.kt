package com.acme.graphrag.service.agent

import com.acme.graphrag.domain.RetrievalMode
import com.acme.graphrag.repository.AgentStepRepository
import com.acme.graphrag.repository.ChatSessionRepository
import com.acme.graphrag.repository.SessionMessage
import com.acme.graphrag.repository.SessionMessageRepository
import com.acme.graphrag.repository.SessionMessageRole
import com.acme.graphrag.service.graphrag.GraphRagService
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

data class ChatAgentResult(
    val answer: String,
    val sources: List<com.acme.graphrag.service.SourceCitation>,
    val steps: List<ChatStepSummary>,
    val latencyMs: Long,
)

data class ChatStepSummary(
    val stepIndex: Int,
    val tool: String,
    val input: Map<String, Any?>,
    val outputSummary: String,
    val durationMs: Long,
)

@Service
class ChatAgentService(
    private val chatSessionRepository: ChatSessionRepository,
    private val sessionMessageRepository: SessionMessageRepository,
    private val agentStepRepository: AgentStepRepository,
    private val graphRagService: GraphRagService,
    private val meterRegistry: MeterRegistry,
) {

    fun createSession(requestedId: UUID? = null): UUID {
        val id = requestedId ?: UUID.randomUUID()
        if (chatSessionRepository.exists(id)) {
            throw IllegalArgumentException("Sesja już istnieje: $id")
        }
        chatSessionRepository.insert(id)
        return id
    }

    fun sendMessage(sessionId: UUID, content: String): ChatAgentResult {
        require(content.isNotBlank()) { "Treść wiadomości nie może być pusta" }
        if (!chatSessionRepository.exists(sessionId)) {
            throw IllegalArgumentException("Sesja nie istnieje: $sessionId")
        }

        return try {
            sendMessageInternal(sessionId, content.trim())
        } catch (ex: Exception) {
            meterRegistry.counter("agent.requests.failed").increment()
            ChatAgentResult(
                answer = "Wystąpił błąd podczas przetwarzania pytania. Spróbuj ponownie lub rozpocznij nową rozmowę.",
                sources = emptyList(),
                steps = emptyList(),
                latencyMs = 0,
            )
        }
    }

    private fun sendMessageInternal(sessionId: UUID, content: String): ChatAgentResult {
        val history = loadVisibleHistory(sessionId)
        val questionWithContext = buildQuestionWithHistory(history, content)

        sessionMessageRepository.insert(
            id = UUID.randomUUID(),
            sessionId = sessionId,
            role = SessionMessageRole.USER,
            content = content,
        )

        lateinit var ragResult: com.acme.graphrag.service.AskResult
        val latencyMs = measureTimeMillis {
            meterRegistry.counter("agent.requests.total").increment()
            ragResult = graphRagService.ask(questionWithContext, RetrievalMode.GRAPH_RAG)
        }

        sessionMessageRepository.insert(
            id = UUID.randomUUID(),
            sessionId = sessionId,
            role = SessionMessageRole.ASSISTANT,
            content = ragResult.answer,
        )

        meterRegistry.timer("agent.latency").record(latencyMs, TimeUnit.MILLISECONDS)

        return ChatAgentResult(
            answer = ragResult.answer,
            sources = ragResult.sources,
            steps = emptyList(),
            latencyMs = latencyMs,
        )
    }

    fun listMessages(sessionId: UUID) = loadVisibleHistory(sessionId)

    fun listSteps(sessionId: UUID) = agentStepRepository.findBySessionId(sessionId)

    private fun loadVisibleHistory(sessionId: UUID): List<SessionMessage> =
        sessionMessageRepository.findBySessionId(sessionId)
            .filter { it.role == SessionMessageRole.USER || it.role == SessionMessageRole.ASSISTANT }
            .filter { message ->
                message.role != SessionMessageRole.ASSISTANT ||
                    !message.content.contains("toolName =")
            }

    private fun buildQuestionWithHistory(history: List<SessionMessage>, currentQuestion: String): String {
        if (history.isEmpty()) return currentQuestion

        val contextLines = history.takeLast(8).map { message ->
            val speaker = when (message.role) {
                SessionMessageRole.USER -> "Użytkownik"
                SessionMessageRole.ASSISTANT -> "Asystent"
                else -> "System"
            }
            "$speaker: ${message.content}"
        }

        return """
            Poniżej kontekst wcześniejszej rozmowy. Odpowiedz na ostatnie pytanie użytkownika.

            ${contextLines.joinToString("\n")}

            Aktualne pytanie użytkownika: $currentQuestion
            """.trimIndent()
    }
}
