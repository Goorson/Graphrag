package com.acme.graphrag.service.graphrag

import com.acme.graphrag.domain.GraphContext
import com.acme.graphrag.domain.GraphRelationship
import com.acme.graphrag.domain.QueryType
import com.acme.graphrag.domain.RetrievalMode
import com.acme.graphrag.config.LlmGateway
import com.acme.graphrag.config.Neo4jAvailability
import com.acme.graphrag.service.AskResult
import com.acme.graphrag.service.EmbeddingService
import com.acme.graphrag.service.HybridRetrievalService
import com.acme.graphrag.service.RagService
import com.acme.graphrag.service.SourceCitation
import com.acme.graphrag.service.graph.GraphRetrievalService
import com.fasterxml.jackson.databind.ObjectMapper
import com.acme.graphrag.repository.QueryLogRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.system.measureTimeMillis

@Service
class GraphRagService(
    private val queryAnalyzer: QueryAnalyzer,
    private val graphRetrievalService: GraphRetrievalService,
    private val hybridRetrievalService: HybridRetrievalService,
    private val embeddingService: EmbeddingService,
    private val contextBuilder: ContextBuilder,
    private val llmGateway: LlmGateway,
    private val queryLogRepository: QueryLogRepository,
    private val ragService: RagService,
    private val objectMapper: ObjectMapper,
    private val neo4jAvailability: Neo4jAvailability,
    private val meterRegistry: MeterRegistry,
) {

    fun ask(question: String, mode: RetrievalMode): AskResult {
        return when (mode) {
            RetrievalMode.VECTOR, RetrievalMode.HYBRID -> ragService.ask(question, mode)
            RetrievalMode.GRAPH -> graphOnly(question)
            RetrievalMode.GRAPH_RAG -> graphRag(question)
        }
    }

    private fun graphRag(question: String): AskResult {
        val trimmedQuestion = question.trim()
        meterRegistry.counter("ask.requests.total", "mode", RetrievalMode.GRAPH_RAG.name).increment()
        val analysis = queryAnalyzer.analyze(trimmedQuestion)

        lateinit var result: AskResult
        val latencyMs = measureTimeMillis {
            result = when (analysis.type) {
                QueryType.FACTUAL -> factualGraphRag(trimmedQuestion, analysis)
                QueryType.RELATIONAL -> relationalGraphRag(trimmedQuestion, analysis)
                QueryType.HYBRID -> hybridGraphRag(trimmedQuestion, analysis)
            }
        }

        val finalResult = withDegradedIfNeeded(result.copy(latencyMs = latencyMs))
        meterRegistry.timer("ask.latency", "mode", RetrievalMode.GRAPH_RAG.name).record(latencyMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (finalResult.sources.isEmpty() && finalResult.answer.contains("Nie znalazłem")) {
            meterRegistry.counter("ask.sources.empty", "mode", RetrievalMode.GRAPH_RAG.name).increment()
        }
        logQuery(trimmedQuestion, finalResult)
        return finalResult
    }

    private fun withDegradedIfNeeded(result: AskResult): AskResult =
        if (neo4jAvailability.degraded) result.copy(degraded = true) else result

    private fun factualGraphRag(question: String, analysis: com.acme.graphrag.domain.QueryAnalysis): AskResult {
        val ragResult = ragService.ask(question, RetrievalMode.HYBRID, persistLog = false)
        return ragResult.copy(
            retrievalMode = RetrievalMode.GRAPH_RAG,
            graphContext = null,
            degraded = false,
        )
    }

    private fun relationalGraphRag(question: String, analysis: com.acme.graphrag.domain.QueryAnalysis): AskResult {
        val graphContext = graphRetrievalService.retrieve(analysis.entities)
        if (!hasGraphHit(graphContext)) {
            val fallback = ragService.ask(question, RetrievalMode.HYBRID, persistLog = false)
            return fallback.copy(
                retrievalMode = RetrievalMode.GRAPH_RAG,
                degraded = true,
            )
        }

        return answerWithGraphAndChunks(question, graphContext, RetrievalMode.GRAPH_RAG)
    }

    private fun hybridGraphRag(question: String, analysis: com.acme.graphrag.domain.QueryAnalysis): AskResult {
        val graphContext = graphRetrievalService.retrieve(analysis.entities)
        if (!hasGraphHit(graphContext)) {
            val fallback = ragService.ask(question, RetrievalMode.HYBRID, persistLog = false)
            return fallback.copy(
                retrievalMode = RetrievalMode.GRAPH_RAG,
                degraded = true,
            )
        }
        return answerWithGraphAndChunks(question, graphContext, RetrievalMode.GRAPH_RAG)
    }

    private fun graphOnly(question: String): AskResult {
        val analysis = queryAnalyzer.analyze(question)
        val graphContext = graphRetrievalService.retrieve(analysis.entities)

        if (graphContext.summaryLines.isEmpty() && graphContext.documentIds.isEmpty()) {
            return AskResult(
                answer = "Nie znalazłem tej informacji w grafie wiedzy.",
                sources = emptyList(),
                retrievalMode = RetrievalMode.GRAPH,
                latencyMs = 0,
                graphContext = graphContext,
            )
        }

        val prompt = """
            Na podstawie WYŁĄCZNIE poniższych relacji z grafu wiedzy odpowiedz na pytanie.
            Jeśli brak danych, napisz: "Nie znalazłem tej informacji w grafie wiedzy."

            Relacje:
            ${graphContext.summaryLines.joinToString("\n") { "- $it" }}

            Pytanie: $question
            """.trimIndent()

        val answer = llmGateway.generate(prompt).trim()
        val result = AskResult(
            answer = answer,
            sources = emptyList(),
            retrievalMode = RetrievalMode.GRAPH,
            latencyMs = 0,
            graphContext = graphContext,
        )
        logQuery(question, result)
        return result
    }

    private fun answerWithGraphAndChunks(
        question: String,
        graphContext: GraphContext,
        mode: RetrievalMode,
    ): AskResult {
        val queryEmbedding = embeddingService.embedOne(question)
        val documentFilter = graphContext.documentIds.takeIf { it.isNotEmpty() }
        var matches = hybridRetrievalService.retrieve(
            queryEmbedding = queryEmbedding,
            question = question,
            mode = RetrievalMode.HYBRID,
            documentIds = documentFilter,
        )

        if (matches.isEmpty()) {
            matches = hybridRetrievalService.retrieve(
                queryEmbedding = queryEmbedding,
                question = question,
                mode = RetrievalMode.HYBRID,
                documentIds = null,
            )
        }

        if (matches.isEmpty()) {
            if (graphContext.summaryLines.isNotEmpty()) {
                return answerFromGraphContext(question, graphContext, mode)
            }
            return AskResult(
                answer = "Nie znalazłem tej informacji w dokumentach.",
                sources = emptyList(),
                retrievalMode = mode,
                latencyMs = 0,
                graphContext = graphContext,
            )
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

        val prompt = contextBuilder.buildPrompt(graphContext, sources, matches, question)
        val answer = llmGateway.generate(prompt).trim()

        return withDegradedIfNeeded(
            AskResult(
                answer = answer,
                sources = sources,
                retrievalMode = mode,
                latencyMs = 0,
                graphContext = graphContext,
            ),
        )
    }

    private fun hasGraphHit(graphContext: GraphContext): Boolean =
        graphContext.summaryLines.isNotEmpty() || graphContext.documentIds.isNotEmpty()

    private fun answerFromGraphContext(
        question: String,
        graphContext: GraphContext,
        mode: RetrievalMode,
    ): AskResult {
        val prompt = """
            Na podstawie WYŁĄCZNIE poniższych relacji z grafu wiedzy odpowiedz na pytanie.
            Jeśli brak danych, napisz: "Nie znalazłem tej informacji w dokumentach."

            Relacje:
            ${graphContext.summaryLines.joinToString("\n") { "- $it" }}

            Pytanie: $question
            """.trimIndent()

        val answer = llmGateway.generate(prompt).trim()
        return AskResult(
            answer = answer,
            sources = emptyList(),
            retrievalMode = mode,
            latencyMs = 0,
            graphContext = graphContext,
        )
    }

    private fun logQuery(question: String, result: AskResult) {
        val sourcesJson = objectMapper.writeValueAsString(
            mapOf(
                "sources" to result.sources.map {
                    mapOf(
                        "filename" to it.filename,
                        "section" to it.section,
                        "page" to it.page,
                        "excerpt" to it.excerpt,
                    )
                },
                "graphContext" to result.graphContext?.let {
                    mapOf(
                        "entitiesUsed" to it.entitiesUsed,
                        "relationships" to it.relationships,
                    )
                },
            ),
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

    private fun excerpt(content: String, maxLength: Int = 240): String {
        val singleLine = content.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength) + "…"
    }
}
