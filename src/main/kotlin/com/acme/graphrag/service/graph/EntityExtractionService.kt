package com.acme.graphrag.service.graph

import com.acme.graphrag.config.StructuredOutputHandler
import com.acme.graphrag.domain.ExtractionResult
import dev.langchain4j.model.chat.ChatLanguageModel
import org.springframework.stereotype.Service

@Service
class EntityExtractionService(
    private val chatLanguageModel: ChatLanguageModel,
    private val structuredOutputHandler: StructuredOutputHandler,
) {

    fun extract(documentText: String, documentKind: String = "dokument"): ExtractionResult {
        val prompt = buildPrompt(documentText, documentKind)
        return structuredOutputHandler.parseWithRetry(
            initialRaw = chatLanguageModel.generate(prompt),
            type = ExtractionResult::class.java,
            maxAttempts = 3,
        ) { repairPrompt ->
            chatLanguageModel.generate("$prompt\n\n$repairPrompt")
        }
    }

    private fun buildPrompt(documentText: String, documentKind: String): String =
        """
        Przeanalizuj poniższy $documentKind (materiał edukacyjny lub firmowy) i zwróć TYLKO JSON (bez markdown):
        {
          "entities": [
            { "type": "Person|Project|Concept|Topic", "name": "...", "attributes": {"description": "..."} }
          ],
          "relationships": [
            { "from": "nazwa encji", "to": "nazwa encji", "type": "WORKS_ON|DEPENDS_ON|ESCALATES|RELATES_TO|COMPARES_WITH|PART_OF", "attributes": {} }
          ]
        }

        Typy encji:
        - Person — osoby (wykładowca, autor, członek zespołu)
        - Project — projekty firmowe
        - Concept — pojęcie, definicja, technologia (np. edge computing, fog computing)
        - Topic — szerszy temat/moduł/przedmiot (np. Sieci komputerowe, Chmura obliczeniowa)

        Typy relacji:
        - WORKS_ON, DEPENDS_ON, ESCALATES — relacje organizacyjne (projekty, zespół)
        - RELATES_TO — ogólne powiązanie między pojęciami/tematami
        - COMPARES_WITH — porównanie (np. edge computing vs fog computing)
        - PART_OF — pojęcie należy do szerszego tematu

        Zasady:
        - Wyciągaj WSZYSTKIE istotne pojęcia techniczne i tematy z materiału studiów.
        - Dla wykładów/PDF: priorytet Concept i Topic oraz RELATES_TO, COMPARES_WITH, PART_OF.
        - Używaj dokładnych nazw z tekstu (nie skracaj bez powodu).
        - Nie wymyślaj encji spoza dokumentu.
        - COMPARES_WITH używaj gdy tekst porównuje dwa pojęcia.
        - PART_OF gdy pojęcie jest podtematem większego modułu.
        - W attributes.description krótkie wyjaśnienie (max 1 zdanie), jeśli jest w tekście.

        Dokument:
        ---
        $documentText
        ---
        """.trimIndent()
}
