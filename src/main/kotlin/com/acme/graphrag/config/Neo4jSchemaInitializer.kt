package com.acme.graphrag.config

import jakarta.annotation.PostConstruct
import org.neo4j.driver.Driver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class Neo4jSchemaInitializer(
    private val driver: Driver,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun initConstraints() {
        try {
            driver.session().use { session ->
                session.run(
                    """
                    CREATE CONSTRAINT person_id IF NOT EXISTS
                    FOR (p:Person) REQUIRE p.canonicalId IS UNIQUE
                    """.trimIndent(),
                )
                session.run(
                    """
                    CREATE CONSTRAINT project_id IF NOT EXISTS
                    FOR (p:Project) REQUIRE p.canonicalId IS UNIQUE
                    """.trimIndent(),
                )
                session.run(
                    """
                    CREATE CONSTRAINT concept_id IF NOT EXISTS
                    FOR (c:Concept) REQUIRE c.canonicalId IS UNIQUE
                    """.trimIndent(),
                )
                session.run(
                    """
                    CREATE CONSTRAINT topic_id IF NOT EXISTS
                    FOR (t:Topic) REQUIRE t.canonicalId IS UNIQUE
                    """.trimIndent(),
                )
                session.run(
                    """
                    CREATE CONSTRAINT document_id IF NOT EXISTS
                    FOR (d:Document) REQUIRE d.id IS UNIQUE
                    """.trimIndent(),
                )
                session.run("CREATE INDEX person_name IF NOT EXISTS FOR (p:Person) ON (p.canonicalName)")
                session.run("CREATE INDEX project_name IF NOT EXISTS FOR (p:Project) ON (p.name)")
                session.run("CREATE INDEX concept_name IF NOT EXISTS FOR (c:Concept) ON (c.name)")
                session.run("CREATE INDEX topic_name IF NOT EXISTS FOR (t:Topic) ON (t.name)")
            }
            log.info("Neo4j constraints and indexes initialized")
        } catch (ex: Exception) {
            log.warn("Neo4j niedostępny przy starcie — graf będzie niedostępny do czasu połączenia: {}", ex.message)
        }
    }
}
