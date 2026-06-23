package com.acme.graphrag.service

object SourceCitationFilter {

    private val citationPattern = Regex("""\[(\d+)]""")
    private val wordPattern = Regex("""[\p{L}\p{N}]{4,}""")

    fun filterUsedSources(
        answer: String,
        sources: List<SourceCitation>,
        chunkContents: List<String> = emptyList(),
    ): List<SourceCitation> {
        if (sources.isEmpty()) return emptyList()

        val citedIndices = citationPattern
            .findAll(answer)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toSet()

        if (citedIndices.isNotEmpty()) {
            return sources.filter { it.index in citedIndices }
        }

        val answerWords = significantWords(answer)
        if (answerWords.isEmpty()) {
            return sources.take(2)
        }

        val scored = sources.mapIndexed { index, source ->
            val text = chunkContents.getOrNull(index) ?: source.excerpt
            source to overlapCount(answerWords, significantWords(text))
        }

        val withOverlap = scored.filter { it.second > 0 }
        if (withOverlap.isEmpty()) {
            return sources.take(2)
        }

        val bestScore = withOverlap.maxOf { it.second }
        val threshold = maxOf(2, (bestScore * 0.4).toInt())

        return withOverlap
            .filter { it.second >= threshold }
            .sortedByDescending { it.second }
            .map { it.first }
            .ifEmpty { withOverlap.sortedByDescending { it.second }.take(3).map { it.first } }
    }

    private fun significantWords(text: String): Set<String> =
        wordPattern.findAll(text.lowercase()).map { it.value }.toSet()

    private fun overlapCount(answerWords: Set<String>, sourceWords: Set<String>): Int =
        answerWords.intersect(sourceWords).size
}
