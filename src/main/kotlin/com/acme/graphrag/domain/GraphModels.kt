package com.acme.graphrag.domain

import java.util.UUID

data class ExtractionResult(
    val entities: List<ExtractedEntity> = emptyList(),
    val relationships: List<ExtractedRelationship> = emptyList(),
)

data class ExtractedEntity(
    val type: String,
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class ExtractedRelationship(
    val from: String,
    val to: String,
    val type: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class ResolvedEntity(
    val type: GraphEntityType,
    val canonicalId: String,
    val displayName: String,
    val alias: String,
)

data class GraphEntitySummary(
    val canonicalId: String,
    val type: GraphEntityType,
    val name: String,
    val aliases: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
)

data class GraphNeighbor(
    val relationship: String,
    val direction: String,
    val node: GraphEntitySummary,
    val attributes: Map<String, String> = emptyMap(),
)

data class GraphRelationship(
    val from: String,
    val type: String,
    val to: String,
    val role: String? = null,
    val context: String? = null,
)

data class GraphContext(
    val entitiesUsed: List<String>,
    val relationships: List<GraphRelationship>,
    val documentIds: List<UUID>,
    val summaryLines: List<String>,
)

data class QueryAnalysis(
    val type: QueryType,
    val entities: List<String>,
    val intent: String? = null,
)
