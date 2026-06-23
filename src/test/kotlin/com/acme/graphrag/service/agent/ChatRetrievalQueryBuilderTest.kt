package com.acme.graphrag.service.agent

import com.acme.graphrag.repository.SessionMessage
import com.acme.graphrag.repository.SessionMessageRole
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ChatRetrievalQueryBuilderTest {

    private fun message(role: SessionMessageRole, content: String) = SessionMessage(
        id = UUID.randomUUID(),
        sessionId = UUID.randomUUID(),
        role = role,
        content = content,
        createdAt = Instant.now(),
    )

    @Test
    fun `employment history question keeps person name and cv hints`() {
        val query = ChatRetrievalQueryBuilder.build(
            history = emptyList(),
            currentQuestion = "Wypisz całą historię zatrudnienia Igora Karlik z CV, od najstarszej do najnowszej pozycji",
        )

        assertTrue(query.contains("Igor Karlik", ignoreCase = true))
        assertTrue(query.contains("karlik", ignoreCase = true))
        assertTrue(query.contains("resume", ignoreCase = true) || query.contains("cv", ignoreCase = true))
        assertTrue(!query.contains("Poniżej kontekst", ignoreCase = true))
    }

    @Test
    fun `follow up before employer enriches from history`() {
        val history = listOf(
            message(SessionMessageRole.USER, "gdzie pracował Igor Karlik?"),
            message(SessionMessageRole.ASSISTANT, "Igor Karlik pracował w Infor."),
        )

        val query = ChatRetrievalQueryBuilder.build(history, "a przed infor?")

        assertTrue(query.contains("Igor Karlik", ignoreCase = true))
        assertTrue(query.contains("infor", ignoreCase = true))
        assertTrue(
            query.contains("zatrudnienie", ignoreCase = true) ||
                query.contains("doświadczenie", ignoreCase = true) ||
                query.contains("doswiadczenie", ignoreCase = true),
        )
    }
}
