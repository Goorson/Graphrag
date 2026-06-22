package com.acme.graphrag.service.graph

import com.acme.graphrag.domain.ExtractedEntity
import com.acme.graphrag.domain.ExtractedRelationship
import com.acme.graphrag.domain.ExtractionResult
import org.springframework.stereotype.Component

@Component
class ExtractionMergeService(
    private val entityDeduplicationService: EntityDeduplicationService,
) {

    fun merge(parts: List<ExtractionResult>): ExtractionResult {
        if (parts.isEmpty()) return ExtractionResult()
        if (parts.size == 1) return parts.first()

        val entitiesByKey = linkedMapOf<String, ExtractedEntity>()
        val relationships = mutableListOf<ExtractedRelationship>()

        for (part in parts) {
            for (entity in part.entities) {
                val key = entityKey(entity)
                entitiesByKey.putIfAbsent(key, entity)
            }
            relationships += part.relationships
        }

        val dedupedRelationships = relationships.distinctBy { rel ->
            listOf(
                entityDeduplicationService.normalize(rel.from),
                rel.type.uppercase(),
                entityDeduplicationService.normalize(rel.to),
            )
        }

        return ExtractionResult(
            entities = entitiesByKey.values.toList(),
            relationships = dedupedRelationships,
        )
    }

    private fun entityKey(entity: ExtractedEntity): String =
        "${entity.type.lowercase()}::${entityDeduplicationService.normalize(entity.name)}"
}
