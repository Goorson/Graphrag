package com.acme.graphrag.service.agent

class ChatAgentException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
