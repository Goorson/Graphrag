package com.acme.graphrag.config

import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(Neo4jProperties::class)
class Neo4jConfig(
    private val neo4jProperties: Neo4jProperties,
) {

    @Bean(destroyMethod = "close")
    fun neo4jDriver(): Driver =
        GraphDatabase.driver(
            neo4jProperties.uri,
            AuthTokens.basic(neo4jProperties.username, neo4jProperties.password),
        )
}
