package com.acme.graphrag.service

import com.acme.graphrag.domain.IngestJob
import com.acme.graphrag.repository.IngestJobRepository
import com.acme.graphrag.service.job.FolderIngestPayload
import com.acme.graphrag.service.job.IngestQueueService
import com.acme.graphrag.service.job.PathIngestPayload
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

@Component
class IngestWorker(
    private val ingestQueueService: IngestQueueService,
    private val ingestJobRepository: IngestJobRepository,
    private val ingestService: IngestService,
    private val objectMapper: ObjectMapper,
    private val redis: org.springframework.data.redis.core.StringRedisTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun processNext(): Boolean {
        val jobId = ingestQueueService.poll() ?: return false
        processJob(jobId)
        return true
    }

    fun processJob(jobId: UUID) {
        val lockKey = "lock:ingest:$jobId"
        val locked = redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL_MINUTES, TimeUnit.MINUTES) == true
        if (!locked) {
            log.warn("Job {} już przetwarzany — pomijam", jobId)
            return
        }

        try {
            val job = ingestJobRepository.findById(jobId) ?: return
            if (job.status != com.acme.graphrag.domain.IngestJobStatus.PENDING &&
                job.status != com.acme.graphrag.domain.IngestJobStatus.PROCESSING
            ) {
                return
            }

            val attempt = job.attempts + 1
            ingestJobRepository.updateProcessing(jobId, attempt, Instant.now())

            val durationMs = measureTimeMillis {
                execute(job)
            }

            log.info(
                "ingest_job_completed jobId={} type={} attempt={} durationMs={}",
                jobId,
                job.type,
                attempt,
                durationMs,
            )
        } catch (ex: Exception) {
            handleFailure(jobId, ex)
        } finally {
            redis.delete(lockKey)
        }
    }

    private fun execute(job: IngestJob) {
        when (job.type) {
            com.acme.graphrag.domain.IngestJobType.SINGLE_FILE_PATH,
            com.acme.graphrag.domain.IngestJobType.SINGLE_FILE_UPLOAD,
            -> {
                val payload = objectMapper.readValue(job.payloadJson, PathIngestPayload::class.java)
                val result = ingestService.ingestFromPath(payload.path)
                ingestJobRepository.updateDone(job.id, result.documentId, 100)
            }
            com.acme.graphrag.domain.IngestJobType.FOLDER_SCAN -> {
                val payload = objectMapper.readValue(job.payloadJson, FolderIngestPayload::class.java)
                val results = ingestService.ingestFolder(payload.folder) { done, total ->
                    val pct = if (total == 0) 100 else (done * 100 / total)
                    ingestJobRepository.updateProgress(job.id, pct)
                }
                val lastDocId = results.lastOrNull()?.documentId
                ingestJobRepository.updateDone(job.id, lastDocId, 100)
            }
        }
    }

    private fun handleFailure(jobId: UUID, ex: Exception) {
        val job = ingestJobRepository.findById(jobId) ?: return
        log.warn("ingest_job_failed jobId={} attempt={} error={}", jobId, job.attempts, ex.message)

        if (job.attempts < job.maxAttempts) {
            ingestJobRepository.updatePendingForRetry(jobId)
            ingestQueueService.enqueue(jobId)
        } else {
            ingestJobRepository.updateFailed(jobId, ex.message ?: ex.javaClass.simpleName)
        }
    }

    companion object {
        private const val LOCK_TTL_MINUTES = 35L
    }
}
