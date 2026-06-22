package com.acme.graphrag.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
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

        val clientKey = request.remoteAddr ?: "unknown"
        val endpoint = endpointGroup(request)
        val redisKey = "ratelimit:$clientKey:$endpoint"

        val count = redis.opsForValue().increment(redisKey) ?: 1L
        if (count == 1L) {
            redis.expire(redisKey, Duration.ofMinutes(1))
        }

        val remaining = (limit - count).coerceAtLeast(0)
        response.setHeader("X-RateLimit-Limit", limit.toString())
        response.setHeader("X-RateLimit-Remaining", remaining.toString())

        if (count > limit) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.setHeader("Retry-After", "60")
            response.contentType = "application/json"
            response.writer.write("""{"error":"Rate limit exceeded"}""")
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun endpointGroup(request: HttpServletRequest): String =
        when {
            request.requestURI.contains("/api/ask") -> "ask"
            request.requestURI.contains("/api/chat") -> "chat"
            request.requestURI.contains("/api/documents") -> "ingest"
            request.requestURI.contains("/api/jobs") -> "ingest"
            else -> "api"
        }

    private fun limitFor(request: HttpServletRequest): Int =
        when (endpointGroup(request)) {
            "ask" -> rateLimitProperties.askPerMinute
            "chat" -> rateLimitProperties.chatPerMinute
            "ingest" -> rateLimitProperties.ingestPerMinute
            else -> rateLimitProperties.askPerMinute
        }
}
