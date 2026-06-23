package com.acme.graphrag.api

import com.acme.graphrag.repository.AgentStep
import com.acme.graphrag.repository.ChatSessionRepository
import com.acme.graphrag.repository.SessionMessage
import com.acme.graphrag.service.agent.ChatAgentService
import com.acme.graphrag.service.agent.ChatAgentResult
import com.acme.graphrag.service.agent.ChatStepSummary
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val chatAgentService: ChatAgentService,
    private val chatSessionRepository: ChatSessionRepository,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    fun createSession(@RequestBody(required = false) request: CreateSessionRequest?): CreateSessionResponse {
        val id = chatAgentService.createSession(request?.id)
        return CreateSessionResponse(id = id)
    }

    @PostMapping("/sessions/{id}/messages")
    fun sendMessage(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ChatMessageRequest,
    ): ChatResponse =
        try {
            chatAgentService.sendMessage(id, request.content).toResponse()
        } catch (ex: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, ex.message)
        }

    @GetMapping("/sessions/{id}/messages")
    fun listMessages(@PathVariable id: UUID): List<SessionMessageResponse> {
        if (!sessionExists(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Sesja nie istnieje")
        }
        return chatAgentService.listMessages(id).map { it.toResponse() }
    }

    @GetMapping("/sessions/{id}/steps")
    fun listSteps(@PathVariable id: UUID): List<AgentStepResponse> {
        if (!sessionExists(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Sesja nie istnieje")
        }
        return chatAgentService.listSteps(id).map { it.toResponse(objectMapper) }
    }

    private fun sessionExists(id: UUID): Boolean = chatSessionRepository.exists(id)
}

data class CreateSessionRequest(
    val id: UUID? = null,
)

data class CreateSessionResponse(
    val id: UUID,
)

data class ChatMessageRequest(
    @field:NotBlank @field:Size(max = 4000) val content: String,
)

data class ChatResponse(
    val answer: String,
    val sources: List<SourceResponse>,
    val steps: List<ChatStepResponse>,
    val latencyMs: Long,
    val degraded: Boolean = false,
)

data class ChatStepResponse(
    val stepIndex: Int,
    val tool: String,
    val input: Map<String, Any?>,
    val outputSummary: String,
    val durationMs: Long,
)

data class SessionMessageResponse(
    val id: UUID,
    val role: String,
    val content: String,
    val createdAt: Instant,
)

data class AgentStepResponse(
    val id: UUID,
    val stepIndex: Int,
    val tool: String?,
    val input: Map<String, Any?>?,
    val outputSummary: String?,
    val durationMs: Long?,
    val createdAt: Instant,
)

private fun ChatAgentResult.toResponse() = ChatResponse(
    answer = answer,
    sources = sources.map { source ->
        SourceResponse(
            index = source.index,
            documentId = source.documentId,
            filename = source.filename,
            section = source.section,
            page = source.page,
            excerpt = source.excerpt,
        )
    },
    steps = steps.map { it.toResponse() },
    latencyMs = latencyMs,
    degraded = degraded,
)

private fun ChatStepSummary.toResponse() = ChatStepResponse(
    stepIndex = stepIndex,
    tool = tool,
    input = input,
    outputSummary = outputSummary,
    durationMs = durationMs,
)

private fun SessionMessage.toResponse() = SessionMessageResponse(
    id = id,
    role = role.name.lowercase(),
    content = content,
    createdAt = createdAt,
)

private fun AgentStep.toResponse(objectMapper: ObjectMapper) = AgentStepResponse(
    id = id,
    stepIndex = stepIndex,
    tool = toolName,
    input = toolInput?.let {
        runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull()
    },
    outputSummary = toolOutput?.take(200),
    durationMs = durationMs,
    createdAt = createdAt,
)
