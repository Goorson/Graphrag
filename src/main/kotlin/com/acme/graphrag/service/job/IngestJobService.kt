package com.acme.graphrag.service.job

import com.acme.graphrag.domain.IngestJob
import com.acme.graphrag.domain.IngestJobStatus
import com.acme.graphrag.domain.IngestJobType
import com.acme.graphrag.repository.IngestJobRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.acme.graphrag.util.ProjectPaths
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

@Service
class IngestJobService(
    private val ingestJobRepository: IngestJobRepository,
    private val ingestQueueService: IngestQueueService,
    private val objectMapper: ObjectMapper,
) {

    fun enqueuePath(relativePath: String): JobCreated {
        val normalized = relativePath.replace('\\', '/')
        ProjectPaths.resolveRelative(normalized)
        require(ingestJobRepository.countActiveForPath(normalized) == 0) {
            "Trwa już indeksowanie dla ścieżki: $normalized"
        }

        val payload = PathIngestPayload(path = normalized)
        return createAndEnqueue(
            type = IngestJobType.SINGLE_FILE_PATH,
            payload = payload,
        )
    }

    fun enqueueUpload(file: MultipartFile): JobCreated {
        val originalName = file.originalFilename ?: "upload.md"
        val safeName = ProjectPaths.safeFilename(originalName)
        require(
            safeName.endsWith(".md", ignoreCase = true) ||
                safeName.endsWith(".pdf", ignoreCase = true),
        ) {
            "Obsługiwane rozszerzenia: .md, .pdf"
        }

        val uploadsDir = Path.of(System.getProperty("user.dir"), "uploads")
        Files.createDirectories(uploadsDir)
        val target = uploadsDir.resolve(safeName).normalize()
        require(target.startsWith(uploadsDir.toAbsolutePath().normalize())) {
            "Nieprawidłowa ścieżka pliku"
        }
        file.inputStream.use { input ->
            Files.newOutputStream(target).use { output -> input.copyTo(output) }
        }

        val relativePath = "uploads/$safeName"
        require(ingestJobRepository.countActiveForPath(relativePath) == 0) {
            "Trwa już indeksowanie dla pliku: $relativePath"
        }

        return createAndEnqueue(
            type = IngestJobType.SINGLE_FILE_UPLOAD,
            payload = PathIngestPayload(path = relativePath),
        )
    }

    fun enqueueFolder(folder: String = "data/documents"): JobCreated {
        val normalized = folder.replace('\\', '/')
        ProjectPaths.resolveRelative(normalized)
        val payload = FolderIngestPayload(folder = normalized)
        return createAndEnqueue(
            type = IngestJobType.FOLDER_SCAN,
            payload = payload,
        )
    }

    fun retry(jobId: UUID): JobCreated {
        val job = ingestJobRepository.findById(jobId)
            ?: throw IllegalArgumentException("Job nie istnieje: $jobId")
        require(job.status == IngestJobStatus.FAILED) {
            "Retry możliwy tylko dla statusu FAILED (aktualny: ${job.status})"
        }

        ingestJobRepository.updatePendingForRetry(jobId, resetAttempts = true)
        ingestQueueService.enqueue(jobId)

        return JobCreated(
            jobId = jobId,
            status = IngestJobStatus.PENDING,
            statusUrl = statusUrl(jobId),
        )
    }

    fun getJob(jobId: UUID): IngestJob =
        ingestJobRepository.findById(jobId)
            ?: throw IllegalArgumentException("Job nie istnieje: $jobId")

    fun listByStatus(status: IngestJobStatus?): List<IngestJob> =
        if (status == null) {
            IngestJobStatus.entries.flatMap { ingestJobRepository.findByStatus(it) }
        } else {
            ingestJobRepository.findByStatus(status)
        }

    fun recoverPendingJobs() {
        ingestJobRepository.resetProcessingToPending()
        ingestJobRepository.findByStatus(IngestJobStatus.PENDING).forEach { job ->
            ingestQueueService.enqueue(job.id)
        }
    }

    private fun createAndEnqueue(type: IngestJobType, payload: Any): JobCreated {
        val jobId = UUID.randomUUID()
        val now = Instant.now()
        val payloadJson = objectMapper.writeValueAsString(payload)

        val job = IngestJob(
            id = jobId,
            documentId = null,
            type = type,
            status = IngestJobStatus.PENDING,
            payloadJson = payloadJson,
            attempts = 0,
            maxAttempts = 3,
            errorMessage = null,
            progressPct = 0,
            createdAt = now,
            updatedAt = now,
            startedAt = null,
            finishedAt = null,
        )
        ingestJobRepository.insert(job)
        ingestQueueService.enqueue(jobId)

        return JobCreated(
            jobId = jobId,
            status = IngestJobStatus.PENDING,
            statusUrl = statusUrl(jobId),
        )
    }

    private fun statusUrl(jobId: UUID) = "/api/jobs/$jobId"
}

data class JobCreated(
    val jobId: UUID,
    val status: IngestJobStatus,
    val statusUrl: String,
)
