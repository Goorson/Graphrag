package com.acme.graphrag.repository

import com.acme.graphrag.domain.IngestJob
import com.acme.graphrag.domain.IngestJobStatus
import com.acme.graphrag.domain.IngestJobType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class IngestJobRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun insert(job: IngestJob) {
        jdbcTemplate.update(
            """
            INSERT INTO ingest_jobs (
                id, document_id, type, status, payload_json, attempts, max_attempts,
                error_message, progress_pct, created_at, updated_at, started_at, finished_at
            ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            job.id,
            job.documentId,
            job.type.name,
            job.status.name,
            job.payloadJson,
            job.attempts,
            job.maxAttempts,
            job.errorMessage,
            job.progressPct,
            Timestamp.from(job.createdAt),
            Timestamp.from(job.updatedAt),
            job.startedAt?.let { Timestamp.from(it) },
            job.finishedAt?.let { Timestamp.from(it) },
        )
    }

    fun findById(id: UUID): IngestJob? =
        jdbcTemplate.query(
            """
            SELECT id, document_id, type, status, payload_json::text, attempts, max_attempts,
                   error_message, progress_pct, created_at, updated_at, started_at, finished_at
            FROM ingest_jobs WHERE id = ?
            """.trimIndent(),
            rowMapper,
            id,
        ).firstOrNull()

    fun findByStatus(status: IngestJobStatus): List<IngestJob> =
        jdbcTemplate.query(
            """
            SELECT id, document_id, type, status, payload_json::text, attempts, max_attempts,
                   error_message, progress_pct, created_at, updated_at, started_at, finished_at
            FROM ingest_jobs WHERE status = ?
            ORDER BY created_at DESC
            """.trimIndent(),
            rowMapper,
            status.name,
        )

    fun countActiveForPath(path: String): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM ingest_jobs
            WHERE status IN ('PENDING', 'PROCESSING')
              AND payload_json->>'path' = ?
            """.trimIndent(),
            Int::class.java,
            path,
        ) ?: 0

    fun updateProcessing(id: UUID, attempts: Int, startedAt: Instant) {
        jdbcTemplate.update(
            """
            UPDATE ingest_jobs
            SET status = 'PROCESSING', attempts = ?, started_at = ?, updated_at = now(), error_message = NULL
            WHERE id = ?
            """.trimIndent(),
            attempts,
            Timestamp.from(startedAt),
            id,
        )
    }

    fun updateDone(id: UUID, documentId: UUID?, progressPct: Int) {
        jdbcTemplate.update(
            """
            UPDATE ingest_jobs
            SET status = 'DONE', document_id = ?, progress_pct = ?, finished_at = now(), updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            documentId,
            progressPct,
            id,
        )
    }

    fun updateFailed(id: UUID, errorMessage: String) {
        jdbcTemplate.update(
            """
            UPDATE ingest_jobs
            SET status = 'FAILED', error_message = ?, finished_at = now(), updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            errorMessage.take(2000),
            id,
        )
    }

    fun updatePendingForRetry(id: UUID, resetAttempts: Boolean = false) {
        if (resetAttempts) {
            jdbcTemplate.update(
                """
                UPDATE ingest_jobs
                SET status = 'PENDING', attempts = 0, updated_at = now(), finished_at = NULL, error_message = NULL
                WHERE id = ?
                """.trimIndent(),
                id,
            )
        } else {
            jdbcTemplate.update(
                """
                UPDATE ingest_jobs SET status = 'PENDING', updated_at = now(), finished_at = NULL
                WHERE id = ?
                """.trimIndent(),
                id,
            )
        }
    }

    fun updateProgress(id: UUID, progressPct: Int) {
        jdbcTemplate.update(
            """
            UPDATE ingest_jobs SET progress_pct = ?, updated_at = now() WHERE id = ?
            """.trimIndent(),
            progressPct,
            id,
        )
    }

    fun resetProcessingToPending() {
        jdbcTemplate.update(
            """
            UPDATE ingest_jobs
            SET status = 'PENDING', updated_at = now()
            WHERE status = 'PROCESSING'
            """.trimIndent(),
        )
    }

    fun failStaleProcessing(olderThan: Instant): Int =
        jdbcTemplate.update(
            """
            UPDATE ingest_jobs
            SET status = 'FAILED',
                error_message = 'Job timeout (stale PROCESSING)',
                finished_at = now(),
                updated_at = now()
            WHERE status = 'PROCESSING' AND started_at < ?
            """.trimIndent(),
            Timestamp.from(olderThan),
        )

    private val rowMapper = RowMapper { rs, _ ->
        IngestJob(
            id = rs.getObject("id", UUID::class.java),
            documentId = rs.getObject("document_id", UUID::class.java),
            type = IngestJobType.valueOf(rs.getString("type")),
            status = IngestJobStatus.valueOf(rs.getString("status")),
            payloadJson = rs.getString("payload_json"),
            attempts = rs.getInt("attempts"),
            maxAttempts = rs.getInt("max_attempts"),
            errorMessage = rs.getString("error_message"),
            progressPct = rs.getInt("progress_pct"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            startedAt = rs.getTimestamp("started_at")?.toInstant(),
            finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
        )
    }
}
