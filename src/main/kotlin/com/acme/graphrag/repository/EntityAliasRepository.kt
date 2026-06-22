package com.acme.graphrag.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class EntityAliasRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun findCanonicalId(entityType: String, normalizedAlias: String): String? =
        jdbcTemplate.query(
            """
            SELECT canonical_id FROM entity_aliases
            WHERE entity_type = ? AND alias = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("canonical_id") },
            entityType,
            normalizedAlias,
        ).firstOrNull()

    fun upsertAlias(entityType: String, alias: String, canonicalId: String) {
        jdbcTemplate.update(
            """
            INSERT INTO entity_aliases (id, entity_type, alias, canonical_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (entity_type, alias) DO NOTHING
            """.trimIndent(),
            UUID.randomUUID(),
            entityType,
            alias,
            canonicalId,
        )
    }
}
