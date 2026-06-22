package com.acme.graphrag.service.graph

import com.acme.graphrag.domain.GraphContext
import com.acme.graphrag.domain.GraphRelationship
import org.springframework.stereotype.Service

@Service
class GraphRetrievalService(
    private val graphQueryService: GraphQueryService,
) {

    fun retrieve(entityHints: List<String>): GraphContext {
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
