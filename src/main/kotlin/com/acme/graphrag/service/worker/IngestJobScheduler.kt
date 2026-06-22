package com.acme.graphrag.service.worker

import com.acme.graphrag.config.IngestProperties
import com.acme.graphrag.repository.IngestJobRepository
import com.acme.graphrag.service.IngestWorker
import com.acme.graphrag.service.job.IngestJobService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class IngestJobScheduler(
    private val ingestWorker: IngestWorker,
    private val ingestJobService: IngestJobService,
    private val ingestJobRepository: IngestJobRepository,
    private val ingestProperties: IngestProperties,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        log.info("Odzyskiwanie jobów PENDING/PROCESSING po restarcie...")
        ingestJobService.recoverPendingJobs()
    }

    @Scheduled(fixedDelay = 1000)
    fun pollQueue() {
        ingestWorker.processNext()
    }

    @Scheduled(fixedDelay = 60_000)
    fun failStaleJobs() {
        val threshold = Instant.now().minus(ingestProperties.staleJobMinutes, ChronoUnit.MINUTES)
        val count = ingestJobRepository.failStaleProcessing(threshold)
        if (count > 0) {
            log.warn("Oznaczono {} jobów jako FAILED (timeout)", count)
        }
    }
}
