package com.acme.graphrag.service.graphrag

import com.acme.graphrag.domain.QueryAnalysis
import com.acme.graphrag.domain.QueryType
import com.acme.graphrag.service.graph.GraphQueryService
import org.springframework.stereotype.Service

@Service
class QueryAnalyzer(
    private val graphQueryService: GraphQueryService,
) {

    private val relationalKeywords = listOf(
        "kto", "z kim", "współprac", "powiązan", "zespoł", "relacj", "zależy", "eskaluj",
    )

    private val comparisonKeywords = listOf(
        "czym różni", "czym rozni", "różni się", "rozni sie", "różnica między", "roznica miedzy",
        "różnica", "roznica", " vs ", "versus", "porównaj", "porownaj", "w przeciwieństwie",
    )

    private val hybridKeywords = listOf("ryzyk", "i kto", "oraz kto", "a także")

    fun analyze(question: String): QueryAnalysis {
        val lowered = question.lowercase()
        val isComparison = comparisonKeywords.any { lowered.contains(it) }
        val isRelational = relationalKeywords.any { lowered.contains(it) }

        val type = when {
            isComparison -> QueryType.HYBRID
            hybridKeywords.any { lowered.contains(it) } && isRelational -> QueryType.HYBRID
            isRelational -> QueryType.RELATIONAL
            else -> QueryType.FACTUAL
        }

        val entities = extractEntityHints(question)
        val intent = when {
            isComparison -> "compare_concepts"
            type == QueryType.RELATIONAL -> "find_relationships"
            type == QueryType.HYBRID -> "find_relationships_and_facts"
            else -> "find_facts"
        }

        return QueryAnalysis(type = type, entities = entities, intent = intent)
    }

    private fun extractEntityHints(question: String): List<String> {
        val hints = mutableListOf<String>()

        Regex(
            "czym (?:się |sie )?różni(?:ą|a)?\\s+(.+?)\\s+od\\s+(.+)",
            RegexOption.IGNORE_CASE,
        ).find(question)?.let { match ->
            hints += match.groupValues[1].trim()
            hints += match.groupValues[2].trim().trimEnd('?', '.', '!')
        }

        Regex("(.+?)\\s+(?:vs\\.?|versus)\\s+(.+)", RegexOption.IGNORE_CASE).find(question)?.let { match ->
            hints += match.groupValues[1].trim()
            hints += match.groupValues[2].trim().trimEnd('?', '.', '!')
        }

        Regex("project\\s+[a-z0-9-]+", RegexOption.IGNORE_CASE).findAll(question).forEach {
            hints += it.value
            val shortName = it.value.replace(Regex("^project\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (shortName.isNotEmpty()) hints += shortName
        }
        Regex("project\\s+alpha|project\\s+beta|payment\\s+gateway", RegexOption.IGNORE_CASE)
            .findAll(question).forEach { hints += it.value }

        Regex("""["“](.+?)["”]""").findAll(question).forEach { hints += it.groupValues[1].trim() }

        if (hints.isEmpty()) {
            val terms = question.split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 4 }
                .take(4)
            hints += terms
        }

        if (hints.size < 2) {
            graphQueryService.searchEntities(question.take(60), null)
                .take(3)
                .forEach { hints += it.name }
        }

        return hints
            .map { it.trim().trimEnd('?', '.', '!') }
            .filter { it.length >= 2 }
            .distinct()
            .take(5)
    }
}
