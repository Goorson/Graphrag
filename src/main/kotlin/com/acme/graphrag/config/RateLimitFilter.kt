package com.acme.graphrag.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class ApiKeyFilter(
    private val securityProperties: SecurityProperties,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requiredKey = securityProperties.apiKey
        if (requiredKey.isNullOrBlank() || !request.requestURI.startsWith("/api/")) {
            filterChain.doFilter(request, response)
            return
        }

        if (request.requestURI == "/api/ui-config") {
            filterChain.doFilter(request, response)
            return
        }

        val provided = request.getHeader("X-API-Key")
        if (provided != requiredKey) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = "application/json"
            response.writer.write("""{"error":"Unauthorized"}""")
            return
        }
        filterChain.doFilter(request, response)
    }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class RateLimitFilter(
    private val redis: StringRedisTemplate,
    private val rateLimitProperties: RateLimitProperties,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(RateLimitFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!rateLimitProperties.enabled || !request.requestURI.startsWith("/api/")) {
            filterChain.doFilter(request, response)
            return
        }

        val limit = limitFor(request)
        if (limit <= 0) {
            filterChain.doFilter(request, response)
            return
        }

        val clientKey = rateLimitClientKey(request)
        val endpoint = endpointGroup(request)
        val redisKey = "ratelimit:$clientKey:$endpoint"

        try {
            val count = redis.opsForValue().increment(redisKey) ?: 1L
            if (count == 1L) {
                redis.expire(redisKey, Duration.ofMinutes(1))
            }

            val remaining = (limit - count).coerceAtLeast(0)
            response.setHeader("X-RateLimit-Limit", limit.toString())
            response.setHeader("X-RateLimit-Remaining", remaining.toString())

            if (count > limit) {
                log.warn(
                    "Rate limit exceeded: client={} endpoint={} count={} limit={} method={} uri={}",
                    clientKey,
                    endpoint,
                    count,
                    limit,
                    request.method,
                    request.requestURI,
                )
                response.status = HttpStatus.TOO_MANY_REQUESTS.value()
                response.setHeader("Retry-After", "60")
                response.contentType = "application/json"
                response.writer.write("""{"error":"Rate limit exceeded"}""")
                return
            }
        } catch (ex: Exception) {
            log.warn("Rate limit niedostępny (Redis) — pomijam limit: {}", ex.message)
        }

        filterChain.doFilter(request, response)
    }

    private fun rateLimitClientKey(request: HttpServletRequest): String {
        val apiKey = request.getHeader("X-API-Key")
        if (!apiKey.isNullOrBlank()) {
            return "key:${apiKey.hashCode()}"
        }
        return "ip:${request.remoteAddr ?: "unknown"}"
    }

    private fun endpointGroup(request: HttpServletRequest): String {
        val uri = request.requestURI
        return when {
            uri.contains("/api/ask") -> "ask"
            uri.contains("/api/chat") -> "chat"
            uri.contains("/api/jobs") -> "jobs"
            uri.contains("/api/documents") && request.method == "GET" -> "read"
            uri.contains("/api/documents") -> "ingest"
            else -> "api"
        }
    }

    private fun limitFor(request: HttpServletRequest): Int =
        when (endpointGroup(request)) {
            "ask" -> rateLimitProperties.askPerMinute
            "chat" -> rateLimitProperties.chatPerMinute
            "ingest" -> rateLimitProperties.ingestPerMinute
            "jobs" -> rateLimitProperties.jobsPerMinute
            "read" -> rateLimitProperties.askPerMinute
            else -> rateLimitProperties.askPerMinute
        }
}
