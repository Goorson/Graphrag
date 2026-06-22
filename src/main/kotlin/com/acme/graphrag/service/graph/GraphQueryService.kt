package com.acme.graphrag.service.graph

import com.acme.graphrag.domain.GraphEntitySummary
import com.acme.graphrag.domain.GraphEntityType
import com.acme.graphrag.domain.GraphNeighbor
import org.neo4j.driver.Driver
import org.neo4j.driver.Record
import org.neo4j.driver.Values
import org.neo4j.driver.types.Node
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class GraphQueryService(
    private val driver: Driver,
) {

    fun searchEntities(query: String, type: GraphEntityType?): List<GraphEntitySummary> {
        val label = type?.name
        val matchClause = if (label != null) "MATCH (n:$label)" else "MATCH (n)"
        val cypher = """
            $matchClause
            WHERE n.canonicalId IS NOT NULL AND (
                toLower(coalesce(n.canonicalName, n.name, '')) CONTAINS toLower(${'$'}q)
                OR toLower(${'$'}q) CONTAINS toLower(coalesce(n.canonicalName, n.name, ''))
                OR any(a IN coalesce(n.aliases, []) WHERE toLower(a) CONTAINS toLower(${'$'}q))
            )
            RETURN n LIMIT 20
            """.trimIndent()

        return driver.session().executeRead { tx ->
            tx.run(cypher, Values.parameters("q", query))
                .list { record -> toSummary(record.get("n").asNode()) }
        }
    }

    fun getEntity(canonicalId: String): GraphEntitySummary? =
        driver.session().executeRead { tx ->
            val result = tx.run(
                """
                MATCH (n {canonicalId: ${ '$' }id})
                RETURN n
                LIMIT 1
                """.trimIndent(),
                Values.parameters("id", canonicalId),
            )
            if (!result.hasNext()) return@executeRead null
            toSummary(result.single().get("n").asNode())
        }

    fun getNeighbors(canonicalId: String, depth: Int = 1): List<GraphNeighbor> {
        depth.coerceIn(1, 2)
        return driver.session().executeRead { tx ->
            tx.run(
                """
                MATCH (start {canonicalId: ${ '$' }id})-[r]-(neighbor)
                WHERE start <> neighbor
                RETURN type(r) AS relType, neighbor, r,
                       CASE WHEN start = startNode(r) THEN 'OUT' ELSE 'IN' END AS direction
                LIMIT 50
                """.trimIndent(),
                Values.parameters("id", canonicalId),
            ).list { record ->
                val node = record.get("neighbor").asNode()
                val rel = record.get("r").asRelationship()
                GraphNeighbor(
                    relationship = record.get("relType").asString(),
                    direction = record.get("direction").asString(),
                    node = toSummary(node),
                    attributes = rel.asMap().mapValues { it.value?.toString() ?: "" },
                )
            }
        }
    }

    fun getMentioningDocuments(canonicalId: String): List<String> =
        driver.session().executeRead { tx ->
            tx.run(
                """
                MATCH (d:Document)-[:MENTIONS]->(n {canonicalId: ${ '$' }id})
                RETURN d.path AS path
                """.trimIndent(),
                Values.parameters("id", canonicalId),
            ).list { it.get("path").asString() }
        }

    fun findShortestPath(fromCanonicalId: String, toCanonicalId: String): List<String> =
        driver.session().executeRead { tx ->
            val result = tx.run(
                """
                MATCH (a {canonicalId: ${ '$' }from}), (b {canonicalId: ${ '$' }to}),
                      p = shortestPath((a)-[*..4]-(b))
                RETURN [n IN nodes(p) | coalesce(n.canonicalName, n.name, n.filename)] AS names
                """.trimIndent(),
                Values.parameters("from", fromCanonicalId, "to", toCanonicalId),
            )
            if (!result.hasNext()) return@executeRead emptyList()
            result.single().get("names").asList { it.toString() }
        }

    fun retrieveForEntityHints(entityHints: List<String>): GraphRetrievalData {
        if (entityHints.isEmpty()) return GraphRetrievalData.empty()

        for (hint in entityHints) {
            val data = retrieveForSingleHint(hint)
            if (data.relationships.isNotEmpty() || data.entitiesUsed.isNotEmpty()) {
                return data
            }
        }
        return GraphRetrievalData.empty()
    }

    private fun retrieveForSingleHint(hint: String): GraphRetrievalData {
        if (hint.isBlank()) return GraphRetrievalData.empty()

        return driver.session().executeRead { tx ->
            val projectResult = tx.run(
                """
                MATCH (proj:Project)
                WHERE toLower(proj.name) CONTAINS toLower(${ '$' }hint)
                   OR toLower(${ '$' }hint) CONTAINS toLower(proj.name)
                   OR toLower(proj.canonicalId) CONTAINS toLower(${ '$' }hint)
                OPTIONAL MATCH (person:Person)-[w:WORKS_ON]->(proj)
                OPTIONAL MATCH (proj)-[d:DEPENDS_ON]->(dep:Project)
                OPTIONAL MATCH (escalator:Person)-[e:ESCALATES]->(proj)
                OPTIONAL MATCH (doc:Document)-[:MENTIONS]->(proj)
                RETURN proj,
                       collect(DISTINCT person) AS people,
                       collect(DISTINCT dep) AS dependencies,
                       collect(DISTINCT escalator) AS escalators,
                       collect(DISTINCT doc.id) AS documentIds,
                       collect(DISTINCT person) AS workPeople,
                       collect(DISTINCT w) AS workRels,
                       collect(DISTINCT dep) AS depNodes,
                       collect(DISTINCT d) AS depRels,
                       collect(DISTINCT escalator) AS escPeople,
                       collect(DISTINCT e) AS escRels
                LIMIT 1
                """.trimIndent(),
                Values.parameters("hint", hint),
            )

            if (projectResult.hasNext()) {
                val record = projectResult.single()
                if (!record.get("proj").isNull) {
                    return@executeRead buildProjectRetrieval(record)
                }
            }

            val knowledge = searchKnowledgeContext(tx, hint)
            if (knowledge.entitiesUsed.isNotEmpty() || knowledge.relationships.isNotEmpty()) {
                return@executeRead knowledge
            }

            searchPersonContext(tx, hint)
        }
    }

    private fun searchKnowledgeContext(
        tx: org.neo4j.driver.TransactionContext,
        hint: String,
    ): GraphRetrievalData {
        val result = tx.run(
            """
            MATCH (k)
            WHERE (k:Concept OR k:Topic)
              AND (
                toLower(coalesce(k.name, '')) CONTAINS toLower(${ '$' }hint)
                OR toLower(${ '$' }hint) CONTAINS toLower(coalesce(k.name, ''))
              )
            OPTIONAL MATCH (k)-[r:RELATES_TO|COMPARES_WITH|PART_OF]-(other)
            WHERE other:Concept OR other:Topic OR other:Project
            OPTIONAL MATCH (doc:Document)-[:DEFINES|MENTIONS]->(k)
            RETURN k,
                   collect(DISTINCT other) AS relatedNodes,
                   collect(DISTINCT r) AS rels,
                   collect(DISTINCT doc.id) AS documentIds
            LIMIT 1
            """.trimIndent(),
            Values.parameters("hint", hint),
        )
        if (!result.hasNext()) return GraphRetrievalData.empty()

        val record = result.single()
        if (record.get("k").isNull) return GraphRetrievalData.empty()

        val knowledgeNode = record.get("k").asNode()
        val name = knowledgeNode.stringOrNull("name") ?: "unknown"
        val documentIds = record.uuidList("documentIds")
        val relatedNodes = record.nodes("relatedNodes")
        val rels = record.relationships("rels")

        val relationships = mutableListOf<GraphRelationLine>()
        relatedNodes.zip(rels).forEach { (other, rel) ->
            val otherName = other.stringOrNull("name")
                ?: other.stringOrNull("canonicalName")
                ?: "unknown"
            relationships += GraphRelationLine(
                from = name,
                type = rel.type(),
                to = otherName,
                context = rel.get("context")?.asString(),
            )
        }

        return GraphRetrievalData(
            entitiesUsed = listOf(name),
            relationships = relationships,
            documentIds = documentIds,
        )
    }

    private fun buildProjectRetrieval(record: Record): GraphRetrievalData {
        val projectNode = record.get("proj").asNode()
        val projectName = projectNode.stringOrNull("name") ?: "unknown"

        val documentIds = record.uuidList("documentIds")

        val relationships = mutableListOf<GraphRelationLine>()

        val workPeople = record.nodes("workPeople")
        val workRels = record.relationships("workRels")
        workPeople.zip(workRels).forEach { (person, rel) ->
            relationships += GraphRelationLine(
                from = person.stringOrNull("canonicalName") ?: person.stringOrNull("name") ?: "unknown",
                type = rel.type(),
                to = projectName,
                role = rel.get("role")?.asString(),
            )
        }

        val depNodes = record.nodes("depNodes")
        val depRels = record.relationships("depRels")
        depNodes.zip(depRels).forEach { (dep, _) ->
            relationships += GraphRelationLine(
                from = projectName,
                type = "DEPENDS_ON",
                to = dep.stringOrNull("name") ?: "unknown",
            )
        }

        val escPeople = record.nodes("escPeople")
        val escRels = record.relationships("escRels")
        escPeople.zip(escRels).forEach { (person, rel) ->
            relationships += GraphRelationLine(
                from = person.stringOrNull("canonicalName") ?: person.stringOrNull("name") ?: "unknown",
                type = rel.type(),
                to = projectName,
                context = rel.get("context")?.asString(),
            )
        }

        return GraphRetrievalData(
            entitiesUsed = listOf(projectName),
            relationships = relationships,
            documentIds = documentIds,
        )
    }

    private fun searchPersonContext(tx: org.neo4j.driver.TransactionContext, hint: String): GraphRetrievalData {
        val result = tx.run(
            """
            MATCH (person:Person)
            WHERE toLower(person.canonicalName) CONTAINS toLower(${ '$' }hint)
               OR any(a IN coalesce(person.aliases, []) WHERE toLower(a) CONTAINS toLower(${ '$' }hint))
            OPTIONAL MATCH (person)-[w:WORKS_ON]->(proj:Project)
            OPTIONAL MATCH (doc:Document)-[:MENTIONS]->(person)
            RETURN person,
                   collect(DISTINCT proj) AS projects,
                   collect(DISTINCT doc.id) AS documentIds,
                   collect(DISTINCT w) AS workRels
            LIMIT 1
            """.trimIndent(),
            Values.parameters("hint", hint),
        )
        if (!result.hasNext()) return GraphRetrievalData.empty()

        val record = result.single()
        val person = record.get("person").asNode()
        val personName = person.stringOrNull("canonicalName") ?: person.stringOrNull("name") ?: "unknown"
        val projects = record.nodes("projects")
        val workRels = record.relationships("workRels")
        val documentIds = record.uuidList("documentIds")

        val relationships = projects.zip(workRels).map { (proj, rel) ->
            GraphRelationLine(
                from = personName,
                type = rel.type(),
                to = proj.stringOrNull("name") ?: "unknown",
                role = rel.get("role")?.asString(),
            )
        }

        return GraphRetrievalData(
            entitiesUsed = listOf(personName),
            relationships = relationships,
            documentIds = documentIds,
        )
    }

    private fun toSummary(node: Node): GraphEntitySummary {
        val labels = node.labels().toList()
        val type = when {
            labels.contains("Person") -> GraphEntityType.Person
            labels.contains("Project") -> GraphEntityType.Project
            labels.contains("Topic") -> GraphEntityType.Topic
            labels.contains("Concept") -> GraphEntityType.Concept
            else -> GraphEntityType.Concept
        }
        return GraphEntitySummary(
            canonicalId = node.stringOrNull("canonicalId") ?: node.stringOrNull("id") ?: "",
            type = type,
            name = node.stringOrNull("canonicalName")
                ?: node.stringOrNull("name")
                ?: node.stringOrNull("filename")
                ?: "unknown",
            aliases = node.stringList("aliases"),
        )
    }

    private fun Node.stringOrNull(key: String): String? {
        val value = get(key) ?: return null
        return if (value.isNull) null else value.asString()
    }

    private fun Node.stringList(key: String): List<String> {
        val value = get(key) ?: return emptyList()
        if (value.isNull) return emptyList()
        return value.asList { it.asString() }
    }

    private fun Record.nodes(key: String): List<Node> {
        val value = get(key)
        if (value.isNull) return emptyList()
        return value.asList { item -> if (item.isNull) null else item.asNode() }.filterNotNull()
    }

    private fun Record.relationships(key: String): List<org.neo4j.driver.types.Relationship> {
        val value = get(key)
        if (value.isNull) return emptyList()
        return value.asList { item -> if (item.isNull) null else item.asRelationship() }.filterNotNull()
    }

    private fun Record.uuidList(key: String): List<UUID> {
        val value = get(key)
        if (value.isNull) return emptyList()
        return value.asList { item ->
            if (item.isNull) null else runCatching { UUID.fromString(item.asString()) }.getOrNull()
        }.filterNotNull()
    }
}

data class GraphRelationLine(
    val from: String,
    val type: String,
    val to: String,
    val role: String? = null,
    val context: String? = null,
)

data class GraphRetrievalData(
    val entitiesUsed: List<String>,
    val relationships: List<GraphRelationLine>,
    val documentIds: List<UUID>,
) {
    companion object {
        fun empty() = GraphRetrievalData(emptyList(), emptyList(), emptyList())
    }
}
