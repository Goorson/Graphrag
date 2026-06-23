package com.acme.graphrag.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SourceCitationFilterTest {

    private val docId = UUID.randomUUID()

    private fun source(index: Int, excerpt: String) = SourceCitation(
        index = index,
        documentId = docId,
        filename = "file-$index.md",
        section = null,
        page = null,
        excerpt = excerpt,
    )

    @Test
    fun `keeps only cited sources when answer contains bracket citations`() {
        val sources = listOf(
            source(1, "Project Alpha overview"),
            source(2, "Team members"),
            source(3, "Resume Kotlin JUnit Angular"),
        )
        val answer = "Doświadczenie z Kotlin i JUnit [3]."

        val filtered = SourceCitationFilter.filterUsedSources(answer, sources)

        assertEquals(listOf(3), filtered.map { it.index })
    }

    @Test
    fun `uses content overlap when answer has no citations`() {
        val sources = listOf(
            source(1, "Project Alpha goals and Payment Gateway"),
            source(2, "Milestones for 2026 onboarding"),
            source(3, "Kotlin JUnit Cucumber Angular TypeScript resume"),
        )
        val answer = """
            Igor ma doświadczenie z Kotlin, JUnit, Cucumber oraz Angular i TypeScript.
            """.trimIndent()

        val filtered = SourceCitationFilter.filterUsedSources(
            answer = answer,
            sources = sources,
            chunkContents = sources.map { it.excerpt },
        )

        assertEquals(1, filtered.size)
        assertEquals(3, filtered.first().index)
    }

    @Test
    fun `falls back to top retrieval sources when nothing matches`() {
        val sources = listOf(
            source(1, "alpha beta gamma"),
            source(2, "delta epsilon zeta"),
            source(3, "eta theta iota"),
        )
        val answer = "Krótka odpowiedź bez wspólnych słów."

        val filtered = SourceCitationFilter.filterUsedSources(answer, sources)

        assertEquals(2, filtered.size)
        assertTrue(filtered.map { it.index }.containsAll(listOf(1, 2)))
    }
}
