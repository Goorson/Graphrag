package com.acme.graphrag.service

import com.acme.graphrag.domain.RetrievalMode
import com.acme.graphrag.repository.QueryLogRepository
import com.fasterxml.jackson.databind.ObjectMapper
import dev.langchain4j.model.chat.ChatLanguageModel
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.system.measureTimeMillis

@Service
class RagService(
    private val embeddingService: EmbeddingService,
    private val hybridRetrievalService: HybridRetrievalService,
    private val queryLogRepository: QueryLogRepository,
    private val chunkRepository: com.acme.graphrag.repository.ChunkRepository,
    private val chatLanguageModel: ChatLanguageModel,
    private val objectMapper: ObjectMapper,
) {

    fun ask(question: String, mode: RetrievalMode = RetrievalMode.HYBRID): AskResult {
        val trimmedQuestion = question.trim()
        require(trimmedQuestion.isNotEmpty()) { "Pytanie nie może być puste" }

        if (chunkRepository.count() == 0) {
            return AskResult(
                answer = "Brak zindeksowanych dokumentów. Najpierw zaindeksuj pliki (POST /api/documents).",
                sources = emptyList(),
                retrievalMode = mode,
                latencyMs = 0,
            )
        }

        lateinit var result: AskResult
        val latencyMs = measureTimeMillis {
            val queryEmbedding = embeddingService.embedOne(trimmedQuestion)
            val matches = hybridRetrievalService.retrieve(queryEmbedding, trimmedQuestion, mode)

            if (matches.isEmpty()) {
                result = AskResult(
                    answer = "Nie znalazłem tej informacji w dokumentach.",
                    sources = emptyList(),
                    retrievalMode = mode,
                    latencyMs = 0,
                )
                return@measureTimeMillis
            }

            val sources = matches.mapIndexed { index, match ->
                SourceCitation(
                    index = index + 1,
                    documentId = match.documentId,
                    filename = match.filename,
                    section = match.section,
                    page = match.page,
                    excerpt = excerpt(match.content),
                )
            }

            val contextBlock = buildContextBlock(sources, matches)
            val prompt = buildPrompt(contextBlock, trimmedQuestion)
            val answer = chatLanguageModel.generate(prompt)

            result = AskResult(
                answer = answer.trim(),
                sources = sources,
                retrievalMode = mode,
                latencyMs = 0,
            )
        }

        val finalResult = result.copy(latencyMs = latencyMs)
        logQuery(trimmedQuestion, finalResult)
        return finalResult
    }

    private fun logQuery(question: String, result: AskResult) {
        val sourcesJson = objectMapper.writeValueAsString(
            result.sources.map {
                mapOf(
                    "filename" to it.filename,
                    "section" to it.section,
                    "page" to it.page,
                    "excerpt" to it.excerpt,
                )
            },
        )
        queryLogRepository.insert(
            id = UUID.randomUUID(),
            question = question,
            answerPreview = result.answer.take(500),
            sourcesJson = sourcesJson,
            retrievalMode = result.retrievalMode.name,
            latencyMs = result.latencyMs,
        )
    }

    private fun buildContextBlock(
        sources: List<SourceCitation>,
        matches: List<com.acme.graphrag.domain.ChunkSearchResult>,
    ): String =
        matches.mapIndexed { index, match ->
            val source = sources[index]
            val location = buildString {
                append(source.filename)
                source.section?.let { append(" · $it") }
                source.page?.let { append(" · str. $it") }
            }
            "[${source.index}] ($location)\n${match.content.trim()}"
        }.joinToString(separator = "\n\n")

    private fun buildPrompt(context: String, question: String): String =
        """
        Jesteś asystentem odpowiadającym WYŁĄCZNIE na podstawie podanego kontekstu.
        Zasady:
        - Jeśli odpowiedzi nie ma w kontekście, napisz dokładnie: "Nie znalazłem tej informacji w dokumentach."
        - Cytuj źródła numerami w nawiasach kwadratowych, np. [1].
        - Nie wymyślaj faktów.

        Kontekst:
        $context

        Pytanie: $question
        """.trimIndent()

    private fun excerpt(content: String, maxLength: Int = 240): String {
        val singleLine = content.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength) + "…"
    }
}

data class AskResult(
    val answer: String,
    val sources: List<SourceCitation>,
    val retrievalMode: RetrievalMode,
    val latencyMs: Long,
)

data class SourceCitation(
    val index: Int,
    val documentId: java.util.UUID,
    val filename: String,
    val section: String?,
    val page: Int?,
    val excerpt: String,
)
