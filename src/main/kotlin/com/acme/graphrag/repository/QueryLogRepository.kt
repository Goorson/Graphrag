package com.acme.graphrag.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class QueryLogRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun insert(
        id: UUID,
        question: String,
        answerPreview: String?,
        sourcesJson: String?,
        retrievalMode: String,
        latencyMs: Long,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO query_logs (id, question, answer_preview, sources_json, retrieval_mode, latency_ms)
            VALUES (?, ?, ?, ?::jsonb, ?, ?)
            """.trimIndent(),
            id,
            question,
            answerPreview,
            sourcesJson,
            retrievalMode,
            latencyMs,
        )
    }

    fun listRecent(limit: Int): List<QueryLogEntry> =
        jdbcTemplate.query(
            """
            SELECT id, question, answer_preview, retrieval_mode, latency_ms, created_at
            FROM query_logs
            ORDER BY created_at DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                QueryLogEntry(
                    id = rs.getObject("id", UUID::class.java),
                    question = rs.getString("question"),
                    answerPreview = rs.getString("answer_preview"),
                    retrievalMode = rs.getString("retrieval_mode"),
                    latencyMs = rs.getLong("latency_ms"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            },
            limit,
        )
}

data class QueryLogEntry(
    val id: UUID,
    val question: String,
    val answerPreview: String?,
    val retrievalMode: String,
    val latencyMs: Long,
    val createdAt: java.time.Instant,
)
