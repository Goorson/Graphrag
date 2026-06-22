package com.acme.graphrag.service.graphrag

import com.acme.graphrag.domain.ChunkSearchResult
import com.acme.graphrag.domain.GraphContext
import com.acme.graphrag.service.SourceCitation
import org.springframework.stereotype.Component

@Component
class ContextBuilder {

    fun buildPrompt(
        graphContext: GraphContext?,
        sources: List<SourceCitation>,
        matches: List<ChunkSearchResult>,
        question: String,
    ): String {
        val graphSection = buildGraphSection(graphContext)
        val chunkSection = buildChunkSection(sources, matches)

        return """
            Jesteś asystentem odpowiadającym WYŁĄCZNIE na podstawie podanych sekcji kontekstu.
            Zasady:
            - Relacje (kto z kim pracuje, zależności) wolno wyciągać TYLKO z sekcji "Relacje (graf wiedzy)".
            - Fakty szczegółowe (daty, opisy, ryzyka) z sekcji "Fragmenty dokumentów".
            - Jeśli odpowiedzi nie ma w kontekście, napisz dokładnie: "Nie znalazłem tej informacji w dokumentach."
            - Cytuj źródła numerami [n] dla fragmentów dokumentów.
            - Nie wymyślaj faktów.

            ## Relacje (graf wiedzy)
            $graphSection

            ## Fragmenty dokumentów
            $chunkSection

            Pytanie: $question
            """.trimIndent()
    }

    private fun buildGraphSection(graphContext: GraphContext?): String {
        if (graphContext == null || graphContext.summaryLines.isEmpty()) {
            return "(brak danych z grafu)"
        }
        return graphContext.summaryLines.joinToString("\n") { "- $it" }
    }

    private fun buildChunkSection(
        sources: List<SourceCitation>,
        matches: List<ChunkSearchResult>,
    ): String =
        matches.mapIndexed { index, match ->
            val source = sources[index]
            val location = buildString {
                append(source.filename)
                source.section?.let { append(" · $it") }
                source.page?.let { append(" · str. $it") }
            }
            "[${source.index}] ($location)\n${match.content.trim()}"
        }.joinToString("\n\n").ifBlank { "(brak fragmentów)" }
}
