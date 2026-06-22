package com.acme.graphrag.service.agent

import com.acme.graphrag.config.LlmGateway
import org.springframework.stereotype.Service

@Service
class AgentAnswerSynthesizer(
    private val llmGateway: LlmGateway,
) {

    fun finalize(
        userQuestion: String,
        rawAnswer: String,
        steps: List<RecordedAgentStep>,
    ): String {
        val trimmedRaw = rawAnswer.trim()
        if (steps.isEmpty() && !looksLikeRawToolOutput(trimmedRaw)) {
            return trimmedRaw
        }

        val toolContext = when {
            steps.isNotEmpty() -> steps.joinToString("\n\n") { step ->
                "Narzędzie ${step.toolName}:\n${step.toolOutput.take(4000)}"
            }
            else -> trimmedRaw
        }

        val prompt = """
            Jesteś asystentem wiedzy. Na podstawie WYŁĄCZNIE poniższych wyników narzędzi odpowiedz po polsku na pytanie użytkownika.

            Zasady:
            - Pisz naturalnie i zwięźle, jak w czacie.
            - NIE wklejaj surowego JSON, nazw narzędzi (searchDocuments, queryGraph itd.) ani technicznych dumpów.
            - Jeśli brak danych, napisz: "Nie znalazłem tej informacji w dokumentach."
            - Cytuj nazwy plików, jeśli wynikają z wyników narzędzi.

            Pytanie: $userQuestion

            Wyniki narzędzi:
            $toolContext
            """.trimIndent()

        return llmGateway.generate(prompt).trim()
    }

    private fun looksLikeRawToolOutput(text: String): Boolean =
        text.contains("toolName =") ||
            text.contains("ToolExecutionResult") ||
            (text.startsWith("[") && text.contains("\"chunkId\"")) ||
            (text.startsWith("{") && text.contains("\"canonicalId\""))
}
