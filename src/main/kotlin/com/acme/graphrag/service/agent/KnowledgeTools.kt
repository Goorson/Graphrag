package com.acme.graphrag.service.agent

import com.acme.graphrag.domain.RetrievalMode
import com.acme.graphrag.repository.ChunkRepository
import com.acme.graphrag.service.EmbeddingService
import com.acme.graphrag.service.HybridRetrievalService
import com.acme.graphrag.service.SourceCitation
import com.acme.graphrag.service.graph.GraphQueryService
import com.acme.graphrag.service.graph.GraphRetrievalService
import com.fasterxml.jackson.databind.ObjectMapper
import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.system.measureTimeMillis

@Component
class KnowledgeTools(
    private val hybridRetrievalService: HybridRetrievalService,
    private val embeddingService: EmbeddingService,
    private val graphRetrievalService: GraphRetrievalService,
    private val graphQueryService: GraphQueryService,
    private val chunkRepository: ChunkRepository,
    private val agentStepRecorder: AgentStepRecorder,
    private val objectMapper: ObjectMapper,
) {

    @Tool("Wyszukuje fragmenty dokumentów pasujące do zapytania. Użyj gdy potrzebujesz faktów z tekstu.")
    fun searchDocuments(
        @P("pytanie lub słowa kluczowe") query: String,
    ): String {
        val input = mapOf("query" to query)
        agentStepRecorder.checkLoop("searchDocuments", objectMapper.writeValueAsString(input))

        lateinit var result: String
        val durationMs = measureTimeMillis {
            result = try {
                val embedding = embeddingService.embedOne(query)
                val matches = hybridRetrievalService.retrieve(
                    queryEmbedding = embedding,
                    question = query,
                    mode = RetrievalMode.HYBRID,
                    documentIds = null,
                ).take(3)

                if (matches.isEmpty()) {
                    "Brak wyników wyszukiwania dla: $query"
                } else {
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
                    agentStepRecorder.addSources(sources)

                    val payload = matches.map { match ->
                        mapOf(
                            "chunkId" to match.chunkId.toString(),
                            "documentId" to match.documentId.toString(),
                            "filename" to match.filename,
                            "section" to match.section,
                            "page" to match.page,
                            "excerpt" to excerpt(match.content, 300),
                        )
                    }
                    objectMapper.writeValueAsString(payload)
                }
            } catch (ex: Exception) {
                "ERROR: ${ex.message}"
            }
        }

        agentStepRecorder.record(
            toolName = "searchDocuments",
            toolInput = input,
            toolOutput = result,
            outputSummary = summarizeSearch(result),
            durationMs = durationMs,
        )
        return truncate(result)
    }

    @Tool("Zwraca osoby, projekty i relacje powiązane z encją (np. nazwa projektu lub osoby).")
    fun queryGraph(
        @P("nazwa encji, np. Project Alpha lub Jan Kowalski") entityName: String,
    ): String {
        val input = mapOf("entityName" to entityName)
        agentStepRecorder.checkLoop("queryGraph", objectMapper.writeValueAsString(input))

        lateinit var result: String
        val durationMs = measureTimeMillis {
            result = try {
                val context = graphRetrievalService.retrieve(listOf(entityName))
                if (context.summaryLines.isEmpty()) {
                    "Brak wyników w grafie dla: $entityName"
                } else {
                    val payload = mapOf(
                        "entitiesUsed" to context.entitiesUsed,
                        "relationships" to context.summaryLines,
                        "documentIds" to context.documentIds.map { it.toString() },
                    )
                    objectMapper.writeValueAsString(payload)
                }
            } catch (ex: Exception) {
                "ERROR: ${ex.message}"
            }
        }

        agentStepRecorder.record(
            toolName = "queryGraph",
            toolInput = input,
            toolOutput = result,
            outputSummary = summarizeGraph(result),
            durationMs = durationMs,
        )
        return truncate(result)
    }

    @Tool("Pobiera pełną treść fragmentu dokumentu po ID chunka.")
    fun getDocumentChunk(
        @P("UUID chunka") chunkId: String,
    ): String {
        val input = mapOf("chunkId" to chunkId)
        agentStepRecorder.checkLoop("getDocumentChunk", objectMapper.writeValueAsString(input))

        lateinit var result: String
        val durationMs = measureTimeMillis {
            result = try {
                val id = UUID.fromString(chunkId.trim())
                val chunk = chunkRepository.findById(id)
                if (chunk == null) {
                    "Brak chunka o ID: $chunkId"
                } else {
                    val payload = mapOf(
                        "chunkId" to chunk.chunkId.toString(),
                        "documentId" to chunk.documentId.toString(),
                        "filename" to chunk.filename,
                        "section" to chunk.section,
                        "page" to chunk.page,
                        "content" to truncate(chunk.content, 3500),
                    )
                    objectMapper.writeValueAsString(payload)
                }
            } catch (ex: Exception) {
                "ERROR: ${ex.message}"
            }
        }

        agentStepRecorder.record(
            toolName = "getDocumentChunk",
            toolInput = input,
            toolOutput = result,
            outputSummary = "chunk $chunkId",
            durationMs = durationMs,
        )
        return truncate(result)
    }

    @Tool("Szczegóły węzła grafu: właściwości i wszystkie relacje w 1 kroku.")
    fun getEntityDetails(
        @P("canonicalId, np. person:jan-kowalski") canonicalId: String,
    ): String {
        val input = mapOf("canonicalId" to canonicalId)
        agentStepRecorder.checkLoop("getEntityDetails", objectMapper.writeValueAsString(input))

        lateinit var result: String
        val durationMs = measureTimeMillis {
            result = try {
                val entity = graphQueryService.getEntity(canonicalId)
                if (entity == null) {
                    "Brak encji: $canonicalId"
                } else {
                val neighbors = graphQueryService.getNeighbors(canonicalId)
                val documents = graphQueryService.getMentioningDocuments(canonicalId)
                val payload = mapOf(
                    "entity" to mapOf(
                        "canonicalId" to entity.canonicalId,
                        "type" to entity.type.name,
                        "name" to entity.name,
                        "aliases" to entity.aliases,
                    ),
                    "neighbors" to neighbors.take(15).map {
                        mapOf(
                            "relationship" to it.relationship,
                            "direction" to it.direction,
                            "node" to it.node.name,
                            "attributes" to it.attributes,
                        )
                    },
                    "documents" to documents,
                )
                objectMapper.writeValueAsString(payload)
                }
            } catch (ex: Exception) {
                "ERROR: ${ex.message}"
            }
        }

        agentStepRecorder.record(
            toolName = "getEntityDetails",
            toolInput = input,
            toolOutput = result,
            outputSummary = "entity $canonicalId",
            durationMs = durationMs,
        )
        return truncate(result)
    }

    private fun excerpt(content: String, maxLength: Int = 240): String {
        val singleLine = content.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength) + "…"
    }

    private fun truncate(text: String, maxLength: Int = 4000): String =
        if (text.length <= maxLength) text else text.take(maxLength) + "…"

    private fun summarizeSearch(result: String): String =
        if (result.startsWith("[")) "${result.count { it == '{' }} wyników" else result.take(80)

    private fun summarizeGraph(result: String): String =
        if (result.contains("relationships")) "graf z relacjami" else result.take(80)
}
