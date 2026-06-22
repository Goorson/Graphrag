package com.acme.graphrag.service.agent

import dev.langchain4j.service.MemoryId
import dev.langchain4j.service.SystemMessage
import dev.langchain4j.service.UserMessage

private const val KNOWLEDGE_ASSISTANT_SYSTEM_PROMPT =
    "Jesteś asystentem wiedzy firmowej i studenckiej. Masz narzędzia do przeszukiwania dokumentów i grafu relacji. " +
    "Zasady: (1) Na pytania o fakty w tekście użyj searchDocuments. " +
    "(2) Na pytania o relacje, zależności i powiązania użyj queryGraph lub getEntityDetails, potem ewentualnie searchDocuments. " +
    "(3) Nie odpowiadaj z pamięci modelu — oprzyj się na wynikach narzędzi. " +
    "(4) Jeśli narzędzia nic nie zwróciły — powiedz to wprost. " +
    "(5) Cytuj nazwy plików z wyników searchDocuments. " +
    "(6) Maksymalnie 8 wywołań narzędzi — planuj ekonomicznie. " +
    "(7) Na pytania spoza dokumentów odpowiedz, że nie masz takich danych."

interface KnowledgeAssistant {

    @SystemMessage(KNOWLEDGE_ASSISTANT_SYSTEM_PROMPT)
    fun chat(
        @MemoryId sessionId: String,
        @UserMessage userMessage: String,
    ): String
}
