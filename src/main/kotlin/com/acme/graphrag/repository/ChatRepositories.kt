package com.acme.graphrag.repository

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

enum class SessionMessageRole {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM,
}

data class ChatSession(
    val id: UUID,
    val createdAt: Instant,
)

data class SessionMessage(
    val id: UUID,
    val sessionId: UUID,
    val role: SessionMessageRole,
    val content: String,
    val createdAt: Instant,
)

data class AgentStep(
    val id: UUID,
    val sessionId: UUID,
    val messageId: UUID?,
    val stepIndex: Int,
    val toolName: String?,
    val toolInput: String?,
    val toolOutput: String?,
    val durationMs: Long?,
    val createdAt: Instant,
)

@Repository
class ChatSessionRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun insert(id: UUID, createdAt: Instant = Instant.now()) {
        jdbcTemplate.update(
            "INSERT INTO chat_sessions (id, created_at) VALUES (?, ?)",
            id,
            Timestamp.from(createdAt),
        )
    }

    fun exists(id: UUID): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chat_sessions WHERE id = ?",
            Int::class.java,
            id,
        )!! > 0

    fun findById(id: UUID): ChatSession? =
        jdbcTemplate.query(
            "SELECT id, created_at FROM chat_sessions WHERE id = ?",
            sessionRowMapper,
            id,
        ).firstOrNull()

    private val sessionRowMapper = RowMapper { rs, _ ->
        ChatSession(
            id = rs.getObject("id", UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }
}

@Repository
class SessionMessageRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun insert(
        id: UUID,
        sessionId: UUID,
        role: SessionMessageRole,
        content: String,
        createdAt: Instant = Instant.now(),
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO session_messages (id, session_id, role, content, created_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            sessionId,
            role.name.lowercase(),
            content,
            Timestamp.from(createdAt),
        )
    }

    fun countBySessionId(sessionId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM session_messages WHERE session_id = ?",
            Int::class.java,
            sessionId,
        ) ?: 0

    fun findBySessionId(sessionId: UUID): List<SessionMessage> =
        jdbcTemplate.query(
            """
            SELECT id, session_id, role, content, created_at
            FROM session_messages
            WHERE session_id = ?
            ORDER BY created_at ASC
            """.trimIndent(),
            messageRowMapper,
            sessionId,
        )

    fun updateLastAssistantContent(sessionId: UUID, content: String): UUID? {
        val messages = findBySessionId(sessionId).filter { it.role == SessionMessageRole.ASSISTANT }
        val last = messages.lastOrNull() ?: return null
        jdbcTemplate.update(
            "UPDATE session_messages SET content = ? WHERE id = ?",
            content,
            last.id,
        )
        return last.id
    }

    private val messageRowMapper = RowMapper { rs, _ ->
        SessionMessage(
            id = rs.getObject("id", UUID::class.java),
            sessionId = rs.getObject("session_id", UUID::class.java),
            role = SessionMessageRole.valueOf(rs.getString("role").uppercase()),
            content = rs.getString("content"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }
}

@Repository
class AgentStepRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {

    fun insert(
        id: UUID,
        sessionId: UUID,
        messageId: UUID?,
        stepIndex: Int,
        toolName: String?,
        toolInput: Map<String, Any?>?,
        toolOutput: String?,
        durationMs: Long?,
        createdAt: Instant = Instant.now(),
    ) {
        val inputJson = toolInput?.let { objectMapper.writeValueAsString(it) }
        jdbcTemplate.update(
            """
            INSERT INTO agent_steps
                (id, session_id, message_id, step_index, tool_name, tool_input, tool_output, duration_ms, created_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            """.trimIndent(),
            id,
            sessionId,
            messageId,
            stepIndex,
            toolName,
            inputJson,
            toolOutput?.take(8000),
            durationMs,
            Timestamp.from(createdAt),
        )
    }

    fun findBySessionId(sessionId: UUID): List<AgentStep> =
        jdbcTemplate.query(
            """
            SELECT id, session_id, message_id, step_index, tool_name, tool_input::text, tool_output, duration_ms, created_at
            FROM agent_steps
            WHERE session_id = ?
            ORDER BY step_index ASC
            """.trimIndent(),
            stepRowMapper,
            sessionId,
        )

    private val stepRowMapper = RowMapper { rs, _ ->
        AgentStep(
            id = rs.getObject("id", UUID::class.java),
            sessionId = rs.getObject("session_id", UUID::class.java),
            messageId = rs.getObject("message_id")?.let { (it as UUID) },
            stepIndex = rs.getInt("step_index"),
            toolName = rs.getString("tool_name"),
            toolInput = rs.getString("tool_input"),
            toolOutput = rs.getString("tool_output"),
            durationMs = rs.getObject("duration_ms")?.let { (it as Number).toLong() },
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }
}
