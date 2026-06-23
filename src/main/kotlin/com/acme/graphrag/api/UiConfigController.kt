package com.acme.graphrag.api

import com.acme.graphrag.config.SecurityProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class UiConfigController(
    private val securityProperties: SecurityProperties,
) {

    @GetMapping("/ui-config")
    fun uiConfig(): UiConfigResponse =
        UiConfigResponse(
            apiKeyRequired = !securityProperties.apiKey.isNullOrBlank(),
        )
}

data class UiConfigResponse(
    val apiKeyRequired: Boolean,
)
