package com.acme.graphrag.service.graph

import com.acme.graphrag.domain.Document
import com.acme.graphrag.domain.ExtractedEntity
import com.acme.graphrag.domain.ExtractedRelationship
import com.acme.graphrag.domain.ExtractionResult
import com.acme.graphrag.domain.GraphEntityType
import com.acme.graphrag.domain.ResolvedEntity
import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class GraphWriteService(
    private val driver: Driver,
    private val entityDeduplicationService: EntityDeduplicationService,
) {

    fun rebuildDocumentGraph(document: Document, extraction: ExtractionResult) {
        val resolvedEntities = extraction.entities.map { entityDeduplicationService.resolveCanonical(it.type, it.name) }
        val nameToCanonical = resolvedEntities.associateBy { entityDeduplicationService.normalize(it.displayName) }

        driver.session().executeWrite { tx ->
            tx.run(
                """
                MERGE (d:Document {id: ${'$'}id})
                SET d.path = ${'$'}path,
                    d.filename = ${'$'}filename
                """.trimIndent(),
                Values.parameters(
                    "id", document.id.toString(),
                    "path", document.path,
                    "filename", document.filename,
                ),
            )

            tx.run(
                """
                MATCH (d:Document {id: ${'$'}docId})-[r:MENTIONS|DEFINES]->()
                DELETE r
                """.trimIndent(),
                Values.parameters("docId", document.id.toString()),
            )

            resolvedEntities.forEach { resolved ->
                mergeEntityNode(tx, resolved, extraction.entities)
                tx.run(
                    """
                    MATCH (d:Document {id: ${'$'}docId})
                    MATCH (e {canonicalId: ${'$'}canonicalId})
                    MERGE (d)-[:MENTIONS]->(e)
                    """.trimIndent(),
                    Values.parameters(
                        "docId", document.id.toString(),
                        "canonicalId", resolved.canonicalId,
                    ),
                )
                if (resolved.type == GraphEntityType.Concept || resolved.type == GraphEntityType.Topic) {
                    tx.run(
                        """
                        MATCH (d:Document {id: ${'$'}docId})
                        MATCH (e {canonicalId: ${'$'}canonicalId})
                        MERGE (d)-[:DEFINES]->(e)
                        """.trimIndent(),
                        Values.parameters(
                            "docId", document.id.toString(),
                            "canonicalId", resolved.canonicalId,
                        ),
                    )
                }
            }

            extraction.relationships.forEach { relationship ->
                val from = resolveByName(relationship.from, nameToCanonical, extraction.entities) ?: return@forEach
                val to = resolveByName(relationship.to, nameToCanonical, extraction.entities) ?: return@forEach
                mergeRelationship(tx, from, to, relationship, document.id)
            }
        }
    }

    fun deleteDocumentNode(documentId: UUID) {
        driver.session().executeWrite { tx ->
            tx.run(
                """
                MATCH (d:Document {id: ${'$'}docId})
                DETACH DELETE d
                """.trimIndent(),
                Values.parameters("docId", documentId.toString()),
            )
        }
    }

    private fun mergeEntityNode(
        tx: org.neo4j.driver.TransactionContext,
        resolved: ResolvedEntity,
        entities: List<ExtractedEntity>,
    ) {
        val attributes = entities
            .firstOrNull { entityDeduplicationService.normalize(it.name) == resolved.alias }
            ?.attributes
            ?: emptyMap()

        when (resolved.type) {
            GraphEntityType.Person -> tx.run(
                """
                MERGE (p:Person {canonicalId: ${'$'}canonicalId})
                SET p.canonicalName = ${'$'}displayName,
                    p.aliases = CASE
                        WHEN p.aliases IS NULL THEN [${'$'}alias]
                        WHEN ${'$'}alias IN p.aliases THEN p.aliases
                        ELSE p.aliases + ${'$'}alias
                    END
                """.trimIndent(),
                Values.parameters(
                    "canonicalId", resolved.canonicalId,
                    "displayName", resolved.displayName,
                    "alias", resolved.alias,
                ),
            )
            GraphEntityType.Project -> tx.run(
                """
                MERGE (p:Project {canonicalId: ${'$'}canonicalId})
                SET p.name = ${'$'}displayName,
                    p.status = coalesce(${'$'}status, p.status)
                """.trimIndent(),
                Values.parameters(
                    "canonicalId", resolved.canonicalId,
                    "displayName", resolved.displayName,
                    "status", attributes["status"],
                ),
            )
            GraphEntityType.Concept -> tx.run(
                """
                MERGE (c:Concept {canonicalId: ${'$'}canonicalId})
                SET c.name = ${'$'}displayName,
                    c.description = coalesce(${'$'}description, c.description)
                """.trimIndent(),
                Values.parameters(
                    "canonicalId", resolved.canonicalId,
                    "displayName", resolved.displayName,
                    "description", attributes["description"],
                ),
            )
            GraphEntityType.Topic -> tx.run(
                """
                MERGE (t:Topic {canonicalId: ${'$'}canonicalId})
                SET t.name = ${'$'}displayName,
                    t.description = coalesce(${'$'}description, t.description)
                """.trimIndent(),
                Values.parameters(
                    "canonicalId", resolved.canonicalId,
                    "displayName", resolved.displayName,
                    "description", attributes["description"],
                ),
            )
        }
    }

    private fun mergeRelationship(
        tx: org.neo4j.driver.TransactionContext,
        from: ResolvedEntity,
        to: ResolvedEntity,
        relationship: ExtractedRelationship,
        documentId: UUID,
    ) {
        val relType = relationship.type.uppercase()
        if (relType !in ALLOWED_RELATIONSHIPS) return

        mergeEntityNode(tx, from, listOf(ExtractedEntity(from.type.name, from.displayName)))
        mergeEntityNode(tx, to, listOf(ExtractedEntity(to.type.name, to.displayName)))

        val role = relationship.attributes["role"]
        val context = relationship.attributes["context"] ?: relationship.attributes["description"]

        tx.run(
            """
            MATCH (a {canonicalId: ${'$'}fromId})
            MATCH (b {canonicalId: ${'$'}toId})
            MERGE (a)-[r:$relType]->(b)
            SET r.role = coalesce(${'$'}role, r.role),
                r.context = coalesce(${'$'}context, r.context),
                r.sourceDocumentId = ${'$'}docId
            """.trimIndent(),
            Values.parameters(
                "fromId", from.canonicalId,
                "toId", to.canonicalId,
                "role", role,
                "context", context,
                "docId", documentId.toString(),
            ),
        )
    }

    private fun resolveByName(
        rawName: String,
        resolvedByName: Map<String, ResolvedEntity>,
        entities: List<ExtractedEntity>,
    ): ResolvedEntity? {
        val normalized = entityDeduplicationService.normalize(rawName)
        resolvedByName[normalized]?.let { return it }
        val entity = entities.firstOrNull {
            entityDeduplicationService.normalize(it.name) == normalized
        } ?: return null
        return entityDeduplicationService.resolveCanonical(entity.type, entity.name)
    }

    companion object {
        private val ALLOWED_RELATIONSHIPS = setOf(
            "WORKS_ON",
            "DEPENDS_ON",
            "ESCALATES",
            "RELATES_TO",
            "COMPARES_WITH",
            "PART_OF",
        )
    }
}
