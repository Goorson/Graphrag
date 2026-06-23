package com.acme.graphrag.service.agent

import com.acme.graphrag.domain.RetrievalMode
import com.acme.graphrag.repository.AgentStepRepository
import com.acme.graphrag.repository.ChatSessionRepository
import com.acme.graphrag.repository.SessionMessage
import com.acme.graphrag.repository.SessionMessageRepository
import com.acme.graphrag.repository.SessionMessageRole
import com.acme.graphrag.service.SourceCitation
import com.acme.graphrag.service.graphrag.GraphRagService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

data class ChatAgentResult(
    val answer: String,
    val sources: List<SourceCitation>,
    val steps: List<ChatStepSummary>,
    val latencyMs: Long,
    val degraded: Boolean = false,
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

    private val log = LoggerFactory.getLogger(javaClass)

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

        val question = content.trim()
        val historyBefore = loadVisibleHistory(sessionId)
        log.info(
            "Chat request: sessionId={} historyMessages={} question={}",
            sessionId,
            historyBefore.size,
            question.take(200),
        )

        sessionMessageRepository.insert(
            id = UUID.randomUUID(),
            sessionId = sessionId,
            role = SessionMessageRole.USER,
            content = question,
        )

        val questionWithContext = buildQuestionWithHistory(historyBefore, question)
        val retrievalQuery = ChatRetrievalQueryBuilder.build(historyBefore, question)
        log.info("Chat retrieval query: sessionId={} query={}", sessionId, retrievalQuery.take(300))

        lateinit var result: com.acme.graphrag.service.AskResult
        val latencyMs = measureTimeMillis {
            meterRegistry.counter("agent.requests.total").increment()
            try {
                result = graphRagService.ask(
                    question = questionWithContext,
                    mode = RetrievalMode.GRAPH_RAG,
                    retrievalQuery = retrievalQuery,
                )
            } catch (ex: Exception) {
                meterRegistry.counter("agent.requests.failed").increment()
                log.error(
                    "Chat failed: sessionId={} question={} type={}",
                    sessionId,
                    question.take(200),
                    ex.javaClass.simpleName,
                    ex,
                )
                val message = "Wystąpił błąd podczas przetwarzania pytania. Spróbuj ponownie lub rozpocznij nową rozmowę."
                persistAssistantMessage(sessionId, message)
                throw ChatAgentException(message, ex)
            }
        }

        persistAssistantMessage(sessionId, result.answer)
        meterRegistry.timer("agent.latency").record(latencyMs, TimeUnit.MILLISECONDS)

        log.info(
            "Chat completed: sessionId={} sources={} latencyMs={} degraded={}",
            sessionId,
            result.sources.size,
            latencyMs,
            result.degraded,
        )

        return ChatAgentResult(
            answer = result.answer,
            sources = result.sources,
            steps = emptyList(),
            latencyMs = latencyMs,
            degraded = result.degraded,
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
            .filter { message ->
                message.role != SessionMessageRole.ASSISTANT ||
                    !message.content.startsWith("Wystąpił błąd podczas przetwarzania pytania")
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

    private fun persistAssistantMessage(sessionId: UUID, content: String): UUID {
        val id = UUID.randomUUID()
        sessionMessageRepository.insert(
            id = id,
            sessionId = sessionId,
            role = SessionMessageRole.ASSISTANT,
            content = content,
        )
        return id
    }
}
