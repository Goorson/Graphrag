package com.acme.graphrag.repository

import com.acme.graphrag.domain.ChunkSearchResult
import com.acme.graphrag.domain.Document
import com.acme.graphrag.domain.DocumentStatus
import com.acme.graphrag.util.VectorUtils
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
class DocumentRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun insert(document: Document) {
        jdbcTemplate.update(
            """
            INSERT INTO documents (id, filename, path, mime_type, content_hash, status, ingested_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            document.id,
            document.filename,
            document.path,
            document.mimeType,
            document.contentHash,
            document.status.name,
            Timestamp.from(document.ingestedAt),
        )
    }

    fun deleteByPath(path: String) {
        jdbcTemplate.update("DELETE FROM documents WHERE path = ?", path)
    }

    fun deleteById(id: UUID): Boolean =
        jdbcTemplate.update("DELETE FROM documents WHERE id = ?", id) > 0

    fun findByPath(path: String): Document? =
        jdbcTemplate.query(
            """
            SELECT id, filename, path, mime_type, content_hash, status, ingested_at
            FROM documents WHERE path = ?
            """.trimIndent(),
            documentRowMapper,
            path,
        ).firstOrNull()

    fun findById(id: UUID): Document? =
        jdbcTemplate.query(
            """
            SELECT id, filename, path, mime_type, content_hash, status, ingested_at
            FROM documents WHERE id = ?
            """.trimIndent(),
            documentRowMapper,
            id,
        ).firstOrNull()

    fun listAll(): List<Document> =
        jdbcTemplate.query(
            """
            SELECT id, filename, path, mime_type, content_hash, status, ingested_at
            FROM documents ORDER BY ingested_at DESC
            """.trimIndent(),
            documentRowMapper,
        )

    private val documentRowMapper = RowMapper { rs, _ ->
        Document(
            id = rs.getObject("id", UUID::class.java),
            filename = rs.getString("filename"),
            path = rs.getString("path"),
            mimeType = rs.getString("mime_type"),
            contentHash = rs.getString("content_hash"),
            status = DocumentStatus.valueOf(rs.getString("status")),
            ingestedAt = rs.getTimestamp("ingested_at").toInstant(),
        )
    }
}

@Repository
class ChunkRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun deleteByDocumentId(documentId: UUID) {
        jdbcTemplate.update("DELETE FROM chunks WHERE document_id = ?", documentId)
    }

    fun insert(
        id: UUID,
        documentId: UUID,
        chunkIndex: Int,
        section: String?,
        page: Int?,
        content: String,
        embedding: FloatArray?,
    ) {
        val vectorLiteral = embedding?.let { VectorUtils.toPgVectorLiteral(it) }
        jdbcTemplate.update(
            """
            INSERT INTO chunks (id, document_id, chunk_index, section, page, content, embedding)
            VALUES (?, ?, ?, ?, ?, ?, ?::vector)
            """.trimIndent(),
            id,
            documentId,
            chunkIndex,
            section,
            page,
            content,
            vectorLiteral,
        )
    }

    fun searchSimilar(queryEmbedding: FloatArray, limit: Int): List<ChunkSearchResult> {
        val vectorLiteral = VectorUtils.toPgVectorLiteral(queryEmbedding)
        return jdbcTemplate.query(
            """
            SELECT c.id, c.document_id, c.content, c.section, c.page, d.path AS filename,
                   (c.embedding <=> ?::vector) AS distance
            FROM chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE c.embedding IS NOT NULL
            ORDER BY c.embedding <=> ?::vector
            LIMIT ?
            """.trimIndent(),
            chunkRowMapper { rs -> 1.0 / (1.0 + rs.getDouble("distance")) },
            vectorLiteral,
            vectorLiteral,
            limit,
        )
    }

    fun searchKeyword(question: String, limit: Int): List<ChunkSearchResult> {
        val tsQuery = toTsQuery(question) ?: return emptyList()
        return jdbcTemplate.query(
            """
            SELECT c.id, c.document_id, c.content, c.section, c.page, d.path AS filename,
                   ts_rank(c.content_tsv, to_tsquery('simple', ?)) AS score
            FROM chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE c.content_tsv @@ to_tsquery('simple', ?)
            ORDER BY score DESC
            LIMIT ?
            """.trimIndent(),
            chunkRowMapper { rs -> rs.getDouble("score") },
            tsQuery,
            tsQuery,
            limit,
        )
    }

    fun count(): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunks", Int::class.java) ?: 0

    private fun chunkRowMapper(scoreExtractor: (java.sql.ResultSet) -> Double) = RowMapper { rs, _ ->
        ChunkSearchResult(
            chunkId = rs.getObject("id", UUID::class.java),
            documentId = rs.getObject("document_id", UUID::class.java),
            content = rs.getString("content"),
            section = rs.getString("section"),
            page = rs.getObject("page")?.let { (it as Number).toInt() },
            filename = rs.getString("filename"),
            score = scoreExtractor(rs),
        )
    }

    private fun toTsQuery(question: String): String? {
        val terms = question.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .distinct()
            .take(8)
        if (terms.isEmpty()) return null
        return terms.joinToString(" & ") { "$it:*" }
    }
}
