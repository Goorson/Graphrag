package com.acme.graphrag.domain

import java.time.Instant
import java.util.UUID

enum class IngestJobType {
    SINGLE_FILE_PATH,
    SINGLE_FILE_UPLOAD,
    FOLDER_SCAN,
}

enum class IngestJobStatus {
    PENDING,
    PROCESSING,
    DONE,
    FAILED,
}

data class IngestJob(
    val id: UUID,
    val documentId: UUID?,
    val type: IngestJobType,
    val status: IngestJobStatus,
    val payloadJson: String,
    val attempts: Int,
    val maxAttempts: Int,
    val errorMessage: String?,
    val progressPct: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
)
