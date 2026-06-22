package com.acme.graphrag.config

import com.acme.graphrag.service.EmbeddingService
import org.neo4j.driver.Driver
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class PostgresHealthIndicator(
    private val jdbcTemplate: JdbcTemplate,
) : HealthIndicator {

    override fun health(): Health =
        runCatching {
            jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
            Health.up().build()
        }.getOrElse { Health.down().withException(it).build() }
}

@Component
class RedisHealthIndicator(
    private val redis: StringRedisTemplate,
) : HealthIndicator {

    override fun health(): Health =
        runCatching {
            val key = "health:ping"
            redis.opsForValue().set(key, "1")
            val value = redis.opsForValue().get(key)
            redis.delete(key)
            if (value == "1") Health.up().build() else Health.down().withDetail("ping", value ?: "null").build()
        }.getOrElse { Health.down().withException(it).build() }
}

@Component
class Neo4jHealthIndicator(
    private val driver: Driver,
) : HealthIndicator {

    override fun health(): Health =
        runCatching {
            driver.verifyConnectivity()
            driver.session().executeRead { tx ->
                tx.run("RETURN 1 AS ok").single().get("ok").asInt()
            }
            Health.up().build()
        }.getOrElse { Health.down().withException(it).build() }
}

@Component
@ConditionalOnProperty(prefix = "app.health", name = ["llm-enabled"], havingValue = "true")
class LlmHealthIndicator(
    private val embeddingService: EmbeddingService,
) : HealthIndicator {

    override fun health(): Health =
        runCatching {
            val started = System.nanoTime()
            embeddingService.embedOne("ping")
            val elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis()
            if (elapsedMs > 5_000) {
                Health.down().withDetail("latencyMs", elapsedMs).build()
            } else {
                Health.up().withDetail("latencyMs", elapsedMs).build()
            }
        }.getOrElse { Health.down().withException(it).build() }
}
