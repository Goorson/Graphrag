# Etap 5: Agent z narzędziami

| | |
|--|--|
| **Czas** | ~2–3 tygodnie (18–28 h) |
| **Wymaga** | [Etap 4](etap-04-graphrag.md) |
| **Daje** | Agent z tool calling, pamięcią sesji, logiem kroków |
| **Następny** | [Etap 6](etap-06-production-hardening.md) |

---

## Cel etapu

Dodać **agenta konwersacyjnego**, który sam wybiera narzędzia zamiast sztywnego pipeline GraphRAG:

- multi-step reasoning,
- pamięć w obrębie sesji,
- pełny audyt kroków (debugging „misbehaving agents”).

`POST /api/ask` może zostać jako „prosty tryb” bez pamięci.

---

## Architektura

```
POST /api/chat/sessions/{id}/messages
        → ChatAgentService
        → pętla (maxSteps):
              LLM + tools
              → tool call? execute → wynik do LLM
              → final answer
        → zapis session_messages + agent_steps
        → ChatResponse
```

---

## Krok 1: LangChain4j — definicja agenta

```kotlin
interface KnowledgeAssistant {
    fun chat(sessionId: String, userMessage: String): String
}

@Bean
fun assistant(
    chatModel: ChatLanguageModel,
    tools: KnowledgeTools,
    chatMemoryProvider: ChatMemoryProvider,
): KnowledgeAssistant = AiServices.builder(KnowledgeAssistant::class.java)
    .chatLanguageModel(chatModel)
    .tools(tools)
    .chatMemoryProvider(chatMemoryProvider)
    .build()
```

`ChatMemoryProvider` → `MessageWindowChatMemory` z oknem **10** wiadomości, `id = sessionId`.

---

## Krok 2: Narzędzia (`@Tool`)

```kotlin
@Component
class KnowledgeTools(
    private val hybridSearch: HybridRetrievalService,
    private val graphQuery: GraphRetrievalService,
    private val chunkRepo: ChunkRepository,
) {
    @Tool("Wyszukuje fragmenty dokumentów pasujące do zapytania. Użyj gdy potrzebujesz faktów z tekstu.")
    fun searchDocuments(
        @P("pytanie lub słowa kluczowe") query: String,
        @P("opcjonalna lista ID dokumentów do zawężenia; puste = cała baza") documentIds: List<String>? = null,
    ): String { ... }

    @Tool("Zwraca osoby, projekty i relacje powiązane z encją (np. nazwa projektu lub osoby).")
    fun queryGraph(
        @P("nazwa encji, np. Project Alpha lub Jan Kowalski") entityName: String,
    ): String { ... }

    @Tool("Pobiera pełną treść fragmentu dokumentu po ID chunka.")
    fun getDocumentChunk(
        @P("UUID chunka") chunkId: String,
    ): String { ... }

    @Tool("Szczegóły węzła grafu: właściwości i wszystkie relacje w 1 kroku.")
    fun getEntityDetails(
        @P("canonicalId, np. person:jan-kowalski") canonicalId: String,
    ): String { ... }
}
```

**Bezpieczeństwo:** `queryGraph` **nie** przyjmuje surowego Cypher od modelu — tylko `entityName` / `canonicalId`.

Zwracaj z tools **krótki JSON lub tekst** (< 4000 znaków), nie surowe tabele.

---

## Krok 3: System prompt agenta

```
Jesteś asystentem wiedzy firmowej. Masz narzędzia do przeszukiwania dokumentów i grafu relacji.

Zasady:
1. Na pytania o fakty w tekście — użyj searchDocuments.
2. Na pytania kto z kim pracuje, zależności projektów — użyj queryGraph, potem ewentualnie searchDocuments.
3. Nie odpowiadaj z pamięci modelu — zawsze oprzyj się na wynikach narzędzi.
4. Jeśli narzędzia nic nie zwróciły — powiedz to wprost.
5. Cytuj nazwy plików z wyników searchDocuments.
6. Maksymalnie {maxSteps} wywołań narzędzi — planuj ekonomicznie.
```

---

## Krok 4: Persystencja sesji

`V005__chat.sql`:

```sql
CREATE TABLE chat_sessions (
    id          UUID PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE session_messages (
    id          UUID PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role        TEXT NOT NULL,  -- user | assistant | tool
    content     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_steps (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    message_id      UUID REFERENCES session_messages(id),
    step_index      INT NOT NULL,
    tool_name       TEXT,
    tool_input      JSONB,
    tool_output     TEXT,
    duration_ms     BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Redis (opcjonalnie):** cache ostatnich wiadomości dla szybkiego odczytu; Postgres = source of truth.

Implementuj `ChatMemoryStore` zapisujący do `session_messages`.

---

## Krok 5: Orchestracja i limity

| Limit | Wartość |
|-------|---------|
| `maxSteps` | 8 |
| timeout całej rozmowy | 120 s |
| powtórzenie tego samego tool+input | max 2 → przerwij |

### Wykrywanie pętli

```kotlin
val signature = "${toolName}:${hash(input)}"
if (signatures.count(signature) >= 2) {
    return "Przerwano: powtarzające się wywołanie narzędzia."
}
```

---

## API

| Metoda | Ścieżka | Opis |
|--------|---------|------|
| `POST` | `/api/chat/sessions` | `{ "id": "uuid" }` nowa sesja |
| `POST` | `/api/chat/sessions/{id}/messages` | `{ "content": "..." }` |
| `GET` | `/api/chat/sessions/{id}/messages` | historia |
| `GET` | `/api/chat/sessions/{id}/steps` | kroki agenta |

**Przykład:**

```bash
curl -X POST http://localhost:8080/api/chat/sessions \
  -H "Content-Type: application/json" -d '{}'

curl -X POST http://localhost:8080/api/chat/sessions/{id}/messages \
  -H "Content-Type: application/json" \
  -d '{"content": "Kto prowadzi Alpha i jakie są główne ryzyka?"}'
```

**Odpowiedź:**

```json
{
  "answer": "...",
  "sources": [ ... ],
  "steps": [
    {
      "stepIndex": 0,
      "tool": "queryGraph",
      "input": { "entityName": "Project Alpha" },
      "outputSummary": "3 osoby WORKS_ON, 2 documentIds",
      "durationMs": 45
    },
    {
      "stepIndex": 1,
      "tool": "searchDocuments",
      "input": { "query": "ryzyka Project Alpha", "documentIds": ["..."] },
      "durationMs": 320
    }
  ],
  "latencyMs": 5400
}
```

---

## Scenariusze debugowania (przećwicz)

| Scenariusz | Oczekiwane zachowanie |
|------------|----------------------|
| Zły tool | Popraw opis `@Tool`; dodaj przykład w system prompt |
| Tool exception | Zwróć LLM: `ERROR: ...`; agent próbuje inaczej |
| Brak wyników | „Nie znalazłem w dokumentach” |
| Follow-up | „A jakie ma ryzyka?” w tej samej sesji — rozumie Alpha z kontekstu |
| Pętla search | Loop detection → komunikat |

---

## Testy (własna notatka)

| # | Typ | Pytanie | Oczekiwane tools |
|---|-----|---------|------------------|
| 1 | proste | Kto jest tech leadem Alpha? | queryGraph lub searchDocuments |
| 2 | złożone | Kto na Alpha i jakie ryzyka? | queryGraph + searchDocuments |
| 3 | follow-up | (w tej samej sesji) Kto eskaluje? | searchDocuments |
| 4 | negatywne | Jaka pogoda w Warszawie? | brak halucynacji |
| 5 | graf | Od czego zależy Alpha? | queryGraph |

Razem **8 pytań** — min. **6 PASS**.

---

## Typowe problemy

| Problem | Rozwiązanie |
|---------|-------------|
| Agent nie woła tools | Mocniejszy model; temperature 0.0–0.2 |
| Za długi output tool | Skracaj w serwisie; top-3 chunki |
| Spring AI vs LC4j tools | Trzymaj się jednego frameworka |
| Wolno | Równoległe tools tylko jeśli niezależne |

---

## Kryterium ukończenia

- [ ] Sesje + pamięć follow-up
- [ ] Min. 4 narzędzia
- [ ] `agent_steps` + GET `/steps`
- [ ] Złożone pytanie: ≥ 2 tool calls
- [ ] 6/8 pytań PASS

---

## Artefakty po etapie

```
V005__chat.sql
service/agent/KnowledgeTools.kt
service/agent/ChatAgentService.kt
api/ChatController.kt
(własna notatka z pytaniami testowymi)
```

---

## Co dalej

→ [Etap 6: Production hardening](etap-06-production-hardening.md)
