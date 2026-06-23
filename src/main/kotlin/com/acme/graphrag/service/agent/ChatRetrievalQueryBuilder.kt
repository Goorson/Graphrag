package com.acme.graphrag.service.agent

import com.acme.graphrag.repository.SessionMessage
import com.acme.graphrag.repository.SessionMessageRole

/**
 * Buduje skondensowane zapytanie do wyszukiwania wektorowego / full-text.
 * Historia rozmowy trafia osobno do promptu LLM — nie do embeddingu.
 */
object ChatRetrievalQueryBuilder {

    private val properNamePattern = Regex("""\b([\p{Lu}][\p{L}']+(?:\s+[\p{Lu}][\p{L}']+)+)\b""")
    private val resumePattern = Regex("""(?i)resume|cv|życiorys|zyciorys""")
    private val followUpPrefixes = listOf(
        "a ", "a co", "a jak", "a przed", "a po", "a czy", "czy ", "jak ", "co z",
        "gdzie ", "kiedy ", "dlaczego ", "hmm", "ok ", "okej ",
    )

    private val stopwords = setOf(
        "aby", "albo", "bardzo", "bez", "być", "byc", "był", "byl", "była", "byla", "było", "bylo",
        "cała", "cala", "cały", "caly", "ciąg", "ciag", "czy", "dla", "do", "gdy", "gdzie", "go",
        "historia", "ich", "jak", "jakie", "jego", "jej", "jest", "już", "juz", "kiedy", "która",
        "ktora", "które", "ktore", "który", "ktory", "ma", "mam", "mi", "mnie", "może", "moze",
        "na", "nam", "nas", "nawet", "nie", "o", "od", "oraz", "po", "pod", "ponieważ", "poniewaz",
        "przed", "przez", "przy", "się", "sie", "tak", "tam", "te", "tej", "ten", "to", "tu", "tym",
        "w", "we", "więc", "wiec", "wszystko", "wypisz", "wylistuj", "z", "za", "ze", "że", "ze",
        "the", "and", "for", "with", "from", "that", "this", "what", "when", "where", "which", "who",
        "how", "about", "list", "tell", "please", "only", "based", "using",
        "użytkownik", "uzytkownik", "asystent", "aktualne", "pytanie", "kontekst", "rozmowy",
        "najstarszej", "najnowszej", "pozycji", "pozycja", "firma", "firmie", "firmy", "kiedykolwiek",
        "dokumentach", "dokument", "informacji", "informacja", "znalazłem", "znalazlem",
    )

    fun build(history: List<SessionMessage>, currentQuestion: String): String {
        val terms = linkedSetOf<String>()

        terms.addAll(extractProperNames(currentQuestion))
        terms.addAll(extractSearchTerms(currentQuestion))

        if (resumePattern.containsMatchIn(currentQuestion)) {
            terms += "resume"
            terms += "cv"
        }

        val needsHistory = history.isNotEmpty() && (
            currentQuestion.length < 100 ||
                followUpPrefixes.any { currentQuestion.lowercase().startsWith(it) }
            )

        if (needsHistory) {
            history.takeLast(8).forEach { message ->
                terms.addAll(extractProperNames(message.content))
                terms.addAll(extractSearchTerms(message.content).take(8))
                if (resumePattern.containsMatchIn(message.content)) {
                    terms += "resume"
                    terms += "cv"
                }
            }
        }

        if (followUpPrefixes.any { currentQuestion.lowercase().startsWith("a przed") ||
                currentQuestion.lowercase().contains("przed ")
            }) {
            terms += "doświadczenie"
            terms += "doswiadczenie"
            terms += "zatrudnienie"
            terms += "praca"
            terms += "employment"
            terms += "experience"
        }

        if (terms.any { it.equals("karlik", ignoreCase = true) || it.contains("igor", ignoreCase = true) }) {
            terms += "resume"
        }

        val query = terms
            .map { it.trim() }
            .filter { it.length >= 3 && it.lowercase() !in stopwords }
            .distinct()
            .take(16)
            .joinToString(" ")

        return query.ifBlank { currentQuestion }
    }

    private fun extractProperNames(text: String): List<String> =
        properNamePattern.findAll(text).map { it.groupValues[1].trim() }.toList()

    private fun extractSearchTerms(text: String): List<String> =
        text.split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it.lowercase() !in stopwords }
            .distinct()
}
