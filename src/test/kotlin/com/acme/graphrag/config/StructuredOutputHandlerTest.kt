package com.acme.graphrag.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StructuredOutputHandlerTest {

    private val objectMapper = jacksonObjectMapper()
    private val handler = StructuredOutputHandler(objectMapper, SimpleMeterRegistry())

    @Test
    fun `parseWithRetry succeeds after repair`() {
        var attempt = 0
        val result = handler.parseWithRetry(
            initialRaw = "not json",
            type = Sample::class.java,
            maxAttempts = 3,
        ) { _ ->
            attempt++
            """{"name":"alpha"}"""
        }

        assertEquals("alpha", result.name)
        assertEquals(1, attempt)
    }

    @Test
    fun `parseWithRetry strips markdown fence`() {
        val result = handler.parseWithRetry(
            initialRaw = """```json
                {"name":"beta"}
            ```""",
            type = Sample::class.java,
            maxAttempts = 1,
        ) { throw IllegalStateException("should not repair") }

        assertEquals("beta", result.name)
    }

    @Test
    fun `parseWithRetry throws after max attempts`() {
        assertThrows(IllegalArgumentException::class.java) {
            handler.parseWithRetry(
                initialRaw = "broken",
                type = Sample::class.java,
                maxAttempts = 2,
            ) { "still broken" }
        }
    }

    data class Sample(val name: String)
}
