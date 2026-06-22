package com.acme.graphrag.domain

import java.time.Instant
import java.util.UUID

enum class DocumentStatus {
    INDEXED,
    SKIPPED_OCR_REQUIRED,
}

enum class GraphStatus {
    PENDING,
    INDEXED,
    FAILED,
    SKIPPED,
}

enum class RetrievalMode {
    GRAPH_RAG,
    HYBRID,
    VECTOR,
    GRAPH,
}

enum class QueryType {
    FACTUAL,
    RELATIONAL,
    HYBRID,
}

enum class GraphEntityType {
    Person,
    Project,
    Concept,
    Topic,
}

data class Document(
    val id: UUID,
    val filename: String,
    val path: String,
    val mimeType: String,
    val contentHash: String?,
    val status: DocumentStatus,
    val graphStatus: GraphStatus = GraphStatus.PENDING,
    val ingestedAt: Instant,
)

data class Chunk(
    val id: UUID,
    val documentId: UUID,
    val chunkIndex: Int,
    val section: String?,
    val page: Int?,
    val content: String,
)

data class ChunkSearchResult(
    val chunkId: UUID,
    val documentId: UUID,
    val content: String,
    val section: String?,
    val page: Int?,
    val filename: String,
    val score: Double,
)

data class TextChunk(
    val chunkIndex: Int,
    val section: String?,
    val page: Int? = null,
    val content: String,
)
