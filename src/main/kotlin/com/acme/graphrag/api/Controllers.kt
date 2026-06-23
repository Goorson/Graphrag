package com.acme.graphrag.api

import com.acme.graphrag.domain.Document
import com.acme.graphrag.domain.IngestJob
import com.acme.graphrag.domain.IngestJobStatus
import com.acme.graphrag.domain.RetrievalMode
import com.acme.graphrag.repository.DocumentRepository
import com.acme.graphrag.repository.QueryLogRepository
import com.acme.graphrag.service.AskResult
import com.acme.graphrag.service.graphrag.GraphRagService
import com.acme.graphrag.service.SourceCitation
import com.acme.graphrag.service.job.IngestJobService
import com.acme.graphrag.service.DocumentDeleteService
import com.acme.graphrag.service.job.JobCreated
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api")
class DocumentController(
    private val ingestJobService: IngestJobService,
    private val documentRepository: DocumentRepository,
    private val documentDeleteService: DocumentDeleteService,
) {

    @GetMapping("/documents")
    fun listDocuments(): List<DocumentResponse> =
        documentRepository.listAll().map { it.toResponse() }

    @GetMapping("/documents/{id}")
    fun getDocument(@PathVariable id: UUID): DocumentResponse =
        documentRepository.findById(id)?.toResponse()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dokument nie istnieje")

    @DeleteMapping("/documents/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDocument(@PathVariable id: UUID) {
        if (!documentDeleteService.delete(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dokument nie istnieje")
        }
    }

    @PostMapping("/documents/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@RequestPart("file") file: MultipartFile): ResponseEntity<JobResponse> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(ingestJobService.enqueueUpload(file).toResponse())

    @PostMapping("/documents", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun ingestFromPath(@Valid @RequestBody request: IngestPathRequest): ResponseEntity<JobResponse> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(ingestJobService.enqueuePath(request.path).toResponse())

    @PostMapping("/documents/ingest-folder")
    fun ingestFolder(@RequestBody(required = false) request: IngestFolderRequest?): ResponseEntity<JobResponse> {
        val folder = request?.folder ?: "data/documents"
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ingestJobService.enqueueFolder(folder).toResponse())
    }
}

@RestController
@RequestMapping("/api/jobs")
class JobController(
    private val ingestJobService: IngestJobService,
) {

    @GetMapping("/{id}")
    fun getJob(@PathVariable id: UUID): JobDetailResponse =
        ingestJobService.getJob(id).toDetailResponse()

    @GetMapping
    fun listJobs(@RequestParam(required = false) status: IngestJobStatus?): List<JobDetailResponse> =
        ingestJobService.listByStatus(status).map { it.toDetailResponse() }

    @PostMapping("/{id}/retry")
    fun retry(@PathVariable id: UUID): ResponseEntity<JobResponse> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(ingestJobService.retry(id).toResponse())
}

@RestController
@RequestMapping("/api")
class AskController(
    private val graphRagService: GraphRagService,
) {

    @PostMapping("/ask")
    fun ask(
        @Valid @RequestBody request: AskRequest,
        @RequestParam(required = false, defaultValue = "graph_rag") mode: String,
    ): AskResponse {
        val retrievalMode = parseMode(mode)
        val result = graphRagService.ask(request.question, retrievalMode)
        return result.toResponse()
    }

    private fun parseMode(mode: String): RetrievalMode =
        when (mode.lowercase()) {
            "graph_rag", "graphrag" -> RetrievalMode.GRAPH_RAG
            "vector" -> RetrievalMode.VECTOR
            "hybrid" -> RetrievalMode.HYBRID
            "graph" -> RetrievalMode.GRAPH
            else -> throw IllegalArgumentException("mode musi być: graph_rag, hybrid, vector lub graph")
        }
}

@RestController
@RequestMapping("/api/query-logs")
class QueryLogController(
    private val queryLogRepository: QueryLogRepository,
) {

    @GetMapping
    fun recent(@RequestParam(defaultValue = "20") limit: Int): List<QueryLogResponse> =
        queryLogRepository.listRecent(limit.coerceIn(1, 100)).map {
            QueryLogResponse(
                id = it.id,
                question = it.question,
                answerPreview = it.answerPreview,
                retrievalMode = it.retrievalMode,
                latencyMs = it.latencyMs,
                createdAt = it.createdAt,
            )
        }
}

data class IngestPathRequest(
    @field:NotBlank val path: String,
)

data class IngestFolderRequest(
    val folder: String? = null,
)

data class AskRequest(
    @field:NotBlank val question: String,
)

data class DocumentResponse(
    val id: UUID,
    val filename: String,
    val path: String,
    val mimeType: String,
    val status: String,
    val graphStatus: String,
    val contentHash: String?,
    val ingestedAt: Instant,
)

data class JobResponse(
    val jobId: UUID,
    val status: IngestJobStatus,
    val links: JobLinks,
)

data class JobLinks(
    val status: String,
)

data class JobDetailResponse(
    val jobId: UUID,
    val status: IngestJobStatus,
    val type: String,
    val documentId: UUID?,
    val attempts: Int,
    val maxAttempts: Int,
    val progressPct: Int,
    val errorMessage: String?,
    val payloadJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
)

data class AskResponse(
    val answer: String,
    val sources: List<SourceResponse>,
    val graphContext: GraphContextResponse?,
    val retrievalMode: String,
    val latencyMs: Long,
    val degraded: Boolean = false,
)

data class SourceResponse(
    val index: Int,
    val documentId: UUID,
    val filename: String,
    val section: String?,
    val page: Int?,
    val excerpt: String,
)

data class QueryLogResponse(
    val id: UUID,
    val question: String,
    val answerPreview: String?,
    val retrievalMode: String,
    val latencyMs: Long,
    val createdAt: Instant,
)

private fun Document.toResponse() = DocumentResponse(
    id = id,
    filename = filename,
    path = path,
    mimeType = mimeType,
    status = status.name,
    graphStatus = graphStatus.name,
    contentHash = contentHash,
    ingestedAt = ingestedAt,
)

private fun JobCreated.toResponse() = JobResponse(
    jobId = jobId,
    status = status,
    links = JobLinks(status = statusUrl),
)

private fun IngestJob.toDetailResponse() = JobDetailResponse(
    jobId = id,
    status = status,
    type = type.name,
    documentId = documentId,
    attempts = attempts,
    maxAttempts = maxAttempts,
    progressPct = progressPct,
    errorMessage = errorMessage,
    payloadJson = payloadJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
    startedAt = startedAt,
    finishedAt = finishedAt,
)

private fun AskResult.toResponse() = AskResponse(
    answer = answer,
    sources = sources.map { it.toResponse() },
    graphContext = graphContext?.toResponse(),
    retrievalMode = retrievalMode.name,
    latencyMs = latencyMs,
    degraded = degraded,
)

private fun SourceCitation.toResponse() = SourceResponse(
    index = index,
    documentId = documentId,
    filename = filename,
    section = section,
    page = page,
    excerpt = excerpt,
)
