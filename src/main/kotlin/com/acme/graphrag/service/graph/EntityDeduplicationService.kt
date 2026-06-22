package com.acme.graphrag.service.graph

import com.acme.graphrag.domain.GraphEntityType
import com.acme.graphrag.domain.ResolvedEntity
import com.acme.graphrag.repository.EntityAliasRepository
import org.springframework.stereotype.Service
import java.text.Normalizer

@Service
class EntityDeduplicationService(
    private val entityAliasRepository: EntityAliasRepository,
) {

    fun resolveCanonical(rawType: String, rawName: String): ResolvedEntity {
        val type = parseType(rawType)
        val normalizedAlias = normalize(rawName)
        val canonicalId = entityAliasRepository.findCanonicalId(type.name, normalizedAlias)
            ?: generateCanonicalId(type, rawName)

        entityAliasRepository.upsertAlias(type.name, normalizedAlias, canonicalId)

        return ResolvedEntity(
            type = type,
            canonicalId = canonicalId,
            displayName = rawName.trim(),
            alias = normalizedAlias,
        )
    }

    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun parseType(rawType: String): GraphEntityType =
        when (rawType.trim().lowercase()) {
            "person" -> GraphEntityType.Person
            "project" -> GraphEntityType.Project
            "concept" -> GraphEntityType.Concept
            "topic" -> GraphEntityType.Topic
            "technology", "term", "subject" -> GraphEntityType.Concept
            "module", "theme" -> GraphEntityType.Topic
            else -> GraphEntityType.Concept
        }

    private fun generateCanonicalId(type: GraphEntityType, rawName: String): String {
        val slug = normalize(rawName)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        val prefix = when (type) {
            GraphEntityType.Person -> "person"
            GraphEntityType.Project -> "project"
            GraphEntityType.Concept -> "concept"
            GraphEntityType.Topic -> "topic"
        }
        return "$prefix:$slug"
    }
}
