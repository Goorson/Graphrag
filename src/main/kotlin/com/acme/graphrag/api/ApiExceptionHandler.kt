package com.acme.graphrag.api

import com.acme.graphrag.config.RecoverableAiException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(ex.message ?: "Nieprawidłowe żądanie"))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = ex.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(message))
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
    fun handleGeneric(ex: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(ex.message ?: "Wewnętrzny błąd serwera"))
}

data class ErrorResponse(
    val error: String,
)
