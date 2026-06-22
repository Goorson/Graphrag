package com.acme.graphrag.config

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class Neo4jAvailability(
    private val meterRegistry: MeterRegistry,
) {
    @Volatile
    private var degradedFlag: Boolean = false

    val degraded: Boolean
        get() = degradedFlag

    fun markDegraded() {
        degradedFlag = true
        meterRegistry.counter("neo4j.degraded.total").increment()
    }

    fun markHealthy() {
        degradedFlag = false
    }
}
