package com.acme.graphrag.config

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class Neo4jAvailability(
    private val meterRegistry: MeterRegistry,
) {
    @Volatile
    private var lastFailureAt: Instant? = null

    val degraded: Boolean
        get() {
            val failedAt = lastFailureAt ?: return false
            return Duration.between(failedAt, Instant.now()) < DEGRADED_WINDOW
        }

    fun markDegraded() {
        lastFailureAt = Instant.now()
        meterRegistry.counter("neo4j.degraded.total").increment()
    }

    fun markHealthy() {
        lastFailureAt = null
    }

    companion object {
        private val DEGRADED_WINDOW = Duration.ofMinutes(2)
    }
}
