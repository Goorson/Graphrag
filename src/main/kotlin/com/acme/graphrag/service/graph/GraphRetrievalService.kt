package com.acme.graphrag.service.graph

import com.acme.graphrag.config.Neo4jAvailability
import com.acme.graphrag.domain.GraphContext
import com.acme.graphrag.domain.GraphRelationship
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.springframework.stereotype.Service

@Service
class GraphRetrievalService(
    private val graphQueryService: GraphQueryService,
    private val neo4jAvailability: Neo4jAvailability,
) {

    @CircuitBreaker(name = "neo4j", fallbackMethod = "retrieveFallback")
    fun retrieve(entityHints: List<String>): GraphContext {
        neo4jAvailability.markHealthy()
        return retrieveInternal(entityHints)
    }

    @Suppress("unused")
    fun retrieveFallback(entityHints: List<String>, throwable: Throwable): GraphContext {
        neo4jAvailability.markDegraded()
        return GraphContext(
            entitiesUsed = emptyList(),
            relationships = emptyList(),
            documentIds = emptyList(),
            summaryLines = emptyList(),
        )
    }

    private fun retrieveInternal(entityHints: List<String>): GraphContext {
        val data = graphQueryService.retrieveForEntityHints(entityHints)
        if (data.entitiesUsed.isEmpty() && data.relationships.isEmpty()) {
            return GraphContext(
                entitiesUsed = emptyList(),
                relationships = emptyList(),
                documentIds = emptyList(),
                summaryLines = emptyList(),
            )
        }

        val relationships = data.relationships.map { line ->
            GraphRelationship(
                from = line.from,
                type = line.type,
                to = line.to,
                role = line.role,
                context = line.context,
            )
        }

        val summaryLines = buildList {
            addAll(
                relationships.map { rel ->
                    buildString {
                        append(rel.from)
                        append(" —")
                        append(rel.type)
                        append("→ ")
                        append(rel.to)
                        rel.role?.let { append(" ($it)") }
                        rel.context?.let { append(" [$it]") }
                    }
                },
            )
            if (isEmpty()) {
                addAll(data.entitiesUsed.map { "Pojęcie/temat w grafie: $it" })
            }
        }

        return GraphContext(
            entitiesUsed = data.entitiesUsed,
            relationships = relationships,
            documentIds = data.documentIds,
            summaryLines = summaryLines,
        )
    }
}
