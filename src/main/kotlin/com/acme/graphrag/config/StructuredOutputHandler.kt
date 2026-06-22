package com.acme.graphrag.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class StructuredOutputHandler(
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {

    fun stripMarkdownFence(raw: String): String {
        val trimmed = raw.trim()
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val match = fence.find(trimmed)
        return match?.groupValues?.get(1)?.trim() ?: trimmed
    }

    fun <T> parseWithRetry(
        initialRaw: String,
        type: Class<T>,
        maxAttempts: Int = 3,
        fetch: (repairPrompt: String?) -> String,
    ): T {
        var raw = initialRaw
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                val cleaned = stripMarkdownFence(raw)
                return objectMapper.readValue(cleaned, type)
            } catch (ex: Exception) {
                lastError = ex
                meterRegistry.counter("llm.json.parse.errors").increment()
                if (attempt < maxAttempts - 1) {
                    meterRegistry.counter("llm.json.parse.retries").increment()
                    raw = fetch("Popraw JSON. Błąd: ${ex.message}. Zwróć tylko poprawny JSON.")
                }
            }
        }
        throw IllegalArgumentException("Nie udało się sparsować JSON: ${lastError?.message}", lastError)
    }
}
