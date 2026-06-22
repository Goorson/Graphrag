package com.acme.graphrag.repository

import com.acme.graphrag.domain.ChunkSearchResult
import com.acme.graphrag.domain.Document
import com.acme.graphrag.domain.DocumentStatus
import com.acme.graphrag.domain.GraphStatus
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
            INSERT INTO documents (id, filename, path, mime_type, content_hash, status, graph_status, ingested_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            document.id,
            document.filename,
            document.path,
            document.mimeType,
            document.contentHash,
            document.status.name,
            document.graphStatus.name,
            Timestamp.from(document.ingestedAt),
        )
    }

    fun updateGraphStatus(id: UUID, graphStatus: GraphStatus) {
        jdbcTemplate.update(
            "UPDATE documents SET graph_status = ? WHERE id = ?",
            graphStatus.name,
            id,
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
            SELECT id, filename, path, mime_type, content_hash, status, graph_status, ingested_at
            FROM documents WHERE path = ?
            """.trimIndent(),
            documentRowMapper,
            path,
        ).firstOrNull()

    fun findById(id: UUID): Document? =
        jdbcTemplate.query(
            """
            SELECT id, filename, path, mime_type, content_hash, status, graph_status, ingested_at
            FROM documents WHERE id = ?
            """.trimIndent(),
            documentRowMapper,
            id,
        ).firstOrNull()

    fun listAll(): List<Document> =
        jdbcTemplate.query(
            """
            SELECT id, filename, path, mime_type, content_hash, status, graph_status, ingested_at
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
            graphStatus = GraphStatus.valueOf(rs.getString("graph_status")),
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

    fun getConcatenatedContent(documentId: UUID, maxChars: Int = 12_000): String {
        val parts = getOrderedChunkTexts(documentId)
        val joined = parts.joinToString("\n\n")
        return if (joined.length <= maxChars) joined else joined.take(maxChars)
    }

    fun getChunkBatches(documentId: UUID, maxCharsPerBatch: Int = 6_000): List<String> {
        val parts = getOrderedChunkTexts(documentId)
        if (parts.isEmpty()) return emptyList()

        val batches = mutableListOf<String>()
        val current = StringBuilder()

        for (part in parts) {
            val separator = if (current.isEmpty()) "" else "\n\n"
            if (current.isNotEmpty() && current.length + separator.length + part.length > maxCharsPerBatch) {
                batches += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(part)
        }
        if (current.isNotEmpty()) {
            batches += current.toString()
        }
        return batches
    }

    private fun getOrderedChunkTexts(documentId: UUID): List<String> =
        jdbcTemplate.query(
            """
            SELECT content FROM chunks
            WHERE document_id = ?
            ORDER BY chunk_index
            """.trimIndent(),
            { rs, _ -> rs.getString("content") },
            documentId,
        )

    fun searchSimilar(
        queryEmbedding: FloatArray,
        limit: Int,
        documentIds: List<UUID>? = null,
    ): List<ChunkSearchResult> {
        val vectorLiteral = VectorUtils.toPgVectorLiteral(queryEmbedding)
        val filterClause = documentFilterClause(documentIds)
        val sql = """
            SELECT c.id, c.document_id, c.content, c.section, c.page, d.path AS filename,
                   (c.embedding <=> ?::vector) AS distance
            FROM chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE c.embedding IS NOT NULL
            $filterClause
            ORDER BY c.embedding <=> ?::vector
            LIMIT ?
            """.trimIndent()

        return if (documentIds.isNullOrEmpty()) {
            jdbcTemplate.query(
                sql,
                chunkRowMapper { rs -> 1.0 / (1.0 + rs.getDouble("distance")) },
                vectorLiteral,
                vectorLiteral,
                limit,
            )
        } else {
            jdbcTemplate.query(
                sql,
                chunkRowMapper { rs -> 1.0 / (1.0 + rs.getDouble("distance")) },
                vectorLiteral,
                toUuidArray(documentIds),
                vectorLiteral,
                limit,
            )
        }
    }

    fun searchKeyword(
        question: String,
        limit: Int,
        documentIds: List<UUID>? = null,
    ): List<ChunkSearchResult> {
        val tsQuery = toTsQuery(question) ?: return emptyList()
        val filterClause = documentFilterClause(documentIds)
        val sql = """
            SELECT c.id, c.document_id, c.content, c.section, c.page, d.path AS filename,
                   ts_rank(c.content_tsv, to_tsquery('simple', ?)) AS score
            FROM chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE c.content_tsv @@ to_tsquery('simple', ?)
            $filterClause
            ORDER BY score DESC
            LIMIT ?
            """.trimIndent()

        return if (documentIds.isNullOrEmpty()) {
            jdbcTemplate.query(
                sql,
                chunkRowMapper { rs -> rs.getDouble("score") },
                tsQuery,
                tsQuery,
                limit,
            )
        } else {
            jdbcTemplate.query(
                sql,
                chunkRowMapper { rs -> rs.getDouble("score") },
                tsQuery,
                tsQuery,
                toUuidArray(documentIds),
                limit,
            )
        }
    }

    fun count(): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunks", Int::class.java) ?: 0

    fun findById(chunkId: UUID): ChunkSearchResult? =
        jdbcTemplate.query(
            """
            SELECT c.id, c.document_id, c.content, c.section, c.page, d.path AS filename,
                   1.0 AS score
            FROM chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE c.id = ?
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                ChunkSearchResult(
                    chunkId = rs.getObject("id", UUID::class.java),
                    documentId = rs.getObject("document_id", UUID::class.java),
                    content = rs.getString("content"),
                    section = rs.getString("section"),
                    page = rs.getObject("page")?.let { (it as Number).toInt() },
                    filename = rs.getString("filename"),
                    score = rs.getDouble("score"),
                )
            },
            chunkId,
        ).firstOrNull()

    private fun documentFilterClause(documentIds: List<UUID>?): String =
        if (documentIds.isNullOrEmpty()) "" else "AND c.document_id = ANY(?::uuid[])"

    private fun toUuidArray(ids: List<UUID>): java.sql.Array =
        jdbcTemplate.dataSource!!.connection.use { connection ->
            connection.createArrayOf("uuid", ids.toTypedArray())
        }

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
