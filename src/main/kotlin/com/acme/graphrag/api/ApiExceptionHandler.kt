package com.acme.graphrag.api

import com.acme.graphrag.config.RecoverableAiException
import com.acme.graphrag.service.agent.ChatAgentException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(ex.message ?: "Nieprawidłowe żądanie"))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = ex.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(message))
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(ex: NoResourceFoundException): ResponseEntity<Void> {
        log.debug("Static resource not found: {}", ex.resourcePath)
        return ResponseEntity.notFound().build()
    }

    @ExceptionHandler(ChatAgentException::class)
    fun handleChatAgent(ex: ChatAgentException): ResponseEntity<ErrorResponse> {
        log.error("Chat API 500: {}", ex.message, ex.cause ?: ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(ex.message ?: "Błąd przetwarzania czatu"))
    }

    @ExceptionHandler(RecoverableAiException::class, CallNotPermittedException::class)
    fun handleAiUnavailable(ex: Exception): ResponseEntity<ErrorResponse> {
        val headers = HttpHeaders()
        headers.add(HttpHeaders.RETRY_AFTER, "30")
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .headers(headers)
            .body(ErrorResponse("AI service temporarily unavailable"))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("API 500: {}", ex.message, ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(ex.message ?: "Wewnętrzny błąd serwera"))
    }
}

data class ErrorResponse(
    val error: String,
)
