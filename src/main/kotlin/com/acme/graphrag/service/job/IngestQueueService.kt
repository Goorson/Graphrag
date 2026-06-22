package com.acme.graphrag.service.job

import com.acme.graphrag.config.IngestProperties
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class IngestQueueService(
    private val redis: StringRedisTemplate,
    private val ingestProperties: IngestProperties,
) {

    fun enqueue(jobId: UUID) {
        redis.opsForList().leftPush(ingestProperties.queueKey, jobId.toString())
    }

    fun poll(): UUID? {
        val value = redis.opsForList().rightPop(ingestProperties.queueKey) ?: return null
        return UUID.fromString(value)
    }
}
