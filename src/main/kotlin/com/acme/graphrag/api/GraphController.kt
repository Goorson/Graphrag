package com.acme.graphrag.api

import com.acme.graphrag.domain.GraphEntityType
import com.acme.graphrag.domain.GraphRelationship
import com.acme.graphrag.service.graph.GraphIngestService
import com.acme.graphrag.service.graph.GraphQueryService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/graph")
class GraphController(
    private val graphQueryService: GraphQueryService,
    private val graphIngestService: GraphIngestService,
) {

    @GetMapping("/entities")
    fun searchEntities(
        @RequestParam q: String,
        @RequestParam(required = false) type: GraphEntityType?,
    ): List<GraphEntityResponse> =
        graphQueryService.searchEntities(q, type).map {
            GraphEntityResponse(
                canonicalId = it.canonicalId,
                type = it.type.name,
                name = it.name,
                aliases = it.aliases,
            )
        }

    @GetMapping("/entities/{canonicalId}")
    fun getEntity(@PathVariable canonicalId: String): GraphEntityDetailResponse {
        val entity = graphQueryService.getEntity(canonicalId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encja nie istnieje")
        val documents = graphQueryService.getMentioningDocuments(canonicalId)
        return GraphEntityDetailResponse(
            canonicalId = entity.canonicalId,
            type = entity.type.name,
            name = entity.name,
            aliases = entity.aliases,
            documents = documents,
        )
    }

    @GetMapping("/entities/{canonicalId}/neighbors")
    fun neighbors(
        @PathVariable canonicalId: String,
        @RequestParam(defaultValue = "1") depth: Int,
    ): GraphNeighborsResponse {
        graphQueryService.getEntity(canonicalId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encja nie istnieje")

        val neighbors = graphQueryService.getNeighbors(canonicalId, depth)
        val entity = graphQueryService.getEntity(canonicalId)!!
        return GraphNeighborsResponse(
            entity = GraphEntityResponse(
                canonicalId = entity.canonicalId,
                type = entity.type.name,
                name = entity.name,
                aliases = entity.aliases,
            ),
            neighbors = neighbors.map {
                GraphNeighborResponse(
                    relationship = it.relationship,
                    direction = it.direction,
                    node = GraphEntityResponse(
                        canonicalId = it.node.canonicalId,
                        type = it.node.type.name,
                        name = it.node.name,
                        aliases = it.node.aliases,
                    ),
                    attributes = it.attributes,
                )
            },
            documents = graphQueryService.getMentioningDocuments(canonicalId),
        )
    }

    @GetMapping("/path")
    fun path(
        @RequestParam from: String,
        @RequestParam to: String,
    ): GraphPathResponse {
        val nodes = graphQueryService.findShortestPath(from, to)
        if (nodes.isEmpty()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono ścieżki")
        }
        return GraphPathResponse(from = from, to = to, nodes = nodes)
    }

    @PostMapping("/rebuild/{documentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun rebuild(@PathVariable documentId: UUID): GraphRebuildResponse {
        graphIngestService.rebuild(documentId)
        return GraphRebuildResponse(documentId = documentId, graphStatus = "INDEXED")
    }
}

data class GraphEntityResponse(
    val canonicalId: String,
    val type: String,
    val name: String,
    val aliases: List<String>,
)

data class GraphEntityDetailResponse(
    val canonicalId: String,
    val type: String,
    val name: String,
    val aliases: List<String>,
    val documents: List<String>,
)

data class GraphNeighborResponse(
    val relationship: String,
    val direction: String,
    val node: GraphEntityResponse,
    val attributes: Map<String, String>,
)

data class GraphNeighborsResponse(
    val entity: GraphEntityResponse,
    val neighbors: List<GraphNeighborResponse>,
    val documents: List<String>,
)

data class GraphPathResponse(
    val from: String,
    val to: String,
    val nodes: List<String>,
)

data class GraphRebuildResponse(
    val documentId: UUID,
    val graphStatus: String,
)

data class GraphContextResponse(
    val entitiesUsed: List<String>,
    val relationships: List<GraphRelationshipResponse>,
)

data class GraphRelationshipResponse(
    val from: String,
    val type: String,
    val to: String,
    val role: String? = null,
    val context: String? = null,
)

fun GraphRelationship.toResponse() = GraphRelationshipResponse(
    from = from,
    type = type,
    to = to,
    role = role,
    context = context,
)

fun com.acme.graphrag.domain.GraphContext.toResponse() = GraphContextResponse(
    entitiesUsed = entitiesUsed,
    relationships = relationships.map { it.toResponse() },
)
