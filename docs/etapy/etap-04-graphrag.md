# Etap 4: GraphRAG

| | |
|--|--|
| **Czas** | ~1,5–2 tygodnie (12–18 h) |
| **Wymaga** | [Etap 3](etap-03-neo4j-graf.md) |
| **Daje** | Połączenie retrievalu wektorowego i grafu w jednym pipeline |
| **Następny** | [Etap 5](etap-05-agent.md) |

---

## Cel etapu

Zastąpić „goły” hybrid RAG pipeline **GraphRAG**:

1. Analiza intencji pytania (fakt / relacja / mieszane).
2. Retrieval z Neo4j (struktura + lista dokumentów).
3. Hybrid vector search — często **zawężony** do dokumentów z grafu.
4. Synteza z cytatami i `graphContext` w odpowiedzi.

---

## Architektura

```
AskRequest
    → QueryAnalyzer (LLM lub reguły)
    → równolegle lub sekwencyjnie:
         GraphRetrievalService
         HybridRetrievalService (opcjonalnie z filtrem documentIds)
    → ContextBuilder (budżet tokenów)
    → LlmAnswerService
    → AskResponse (+ graphContext, retrievalMode)
```

---

## Krok 1: QueryAnalyzer

### Reguły (szybki start)

```kotlin
fun analyze(question: String): QueryType {
    val relational = listOf("kto", "z kim", "współprac", "powiązan", "zespoł", "relacj")
    val q = question.lowercase()
    return when {
        relational.any { q.contains(it) } -> QueryType.RELATIONAL
        else -> QueryType.FACTUAL
    }
}
```

### LLM (lepsza jakość)

Prompt zwraca JSON:

```json
{
  "type": "FACTUAL|RELATIONAL|HYBRID",
  "entities": ["Project Alpha"],
  "intent": "find_people_on_project"
}
```

Użyj `StructuredOutputHandler` (retry JSON — przygotowanie pod Etap 6).

| Typ | Przykład | Strategia retrieval |
|-----|----------|---------------------|
| `FACTUAL` | „Jaki jest cel Alpha?” | vector/hybrid bez grafu |
| `RELATIONAL` | „Kto pracuje nad Alpha?” | graf → potem chunki z powiązanych docs |
| `HYBRID` | „Ryzyka Alpha i kto eskaluje?” | graf + vector równolegle |

---

## Krok 2: GraphRetrievalService

Wejście: lista encji z analizy (np. `["Project Alpha"]`).

```cypher
MATCH (proj:Project)
WHERE toLower(proj.name) CONTAINS toLower($entityHint)
OPTIONAL MATCH (p:Person)-[w:WORKS_ON]->(proj)
OPTIONAL MATCH (d:Document)-[:MENTIONS]->(p)
RETURN proj, collect(DISTINCT p) AS people, collect(DISTINCT d.id) AS documentIds
```

Wyjście `GraphContext`:

```kotlin
data class GraphContext(
    val entitiesUsed: List<String>,
    val relationships: List<RelationshipDto>,
    val documentIds: List<UUID>,  // filtr dla vector search
    val summaryLines: List<String> // gotowe linie do promptu
)
```

Jeśli **0 wyników** z grafu → `retrievalMode = VECTOR_ONLY` (fallback).

---

## Krok 3: Vector search z filtrem

```sql
SELECT ... FROM chunks c
WHERE (:filterIds IS NULL OR c.document_id = ANY(:filterIds))
ORDER BY ... hybrid / vector ...
LIMIT 7;
```

`:filterIds = null` gdy pełny fallback.

---

## Krok 4: ContextBuilder

Szablon promptu:

```
## Relacje (graf wiedzy)
- Jan Kowalski —WORKS_ON→ Project Alpha (tech lead)
- Anna Nowak —WORKS_ON→ Project Alpha (backend)

## Fragmenty dokumentów
[1] (people/team-roster.md · Inżynieria)
...
[2] (projects/project-alpha/overview.md · Ryzyka)
...

Pytanie: {question}

Odpowiedz na podstawie OBU sekcji. Relacje tylko z sekcji grafu. Cytuj [n].
```

**Budżet tokenów:**

| Sekcja | Max znaków (orient.) |
|--------|----------------------|
| Graf | 1500 |
| Chunki | 5000 |
| Pytanie + instrukcje | 1000 |

---

## Krok 5: API

| Metoda | Ścieżka | Opis |
|--------|---------|------|
| `POST` | `/api/ask` | GraphRAG domyślnie |
| `POST` | `/api/ask?mode=vector` | tylko hybrid/vector |
| `POST` | `/api/ask?mode=graph` | tylko struktura (debug) |

**Odpowiedź:**

```json
{
  "answer": "Nad Project Alpha pracują m.in. Jan Kowalski (tech lead) i Anna Nowak [1][2].",
  "sources": [ ... ],
  "graphContext": {
    "entitiesUsed": ["Project Alpha"],
    "relationships": [
      { "from": "Jan Kowalski", "type": "WORKS_ON", "to": "Project Alpha", "role": "tech lead" }
    ]
  },
  "retrievalMode": "GRAPH_RAG",
  "latencyMs": 2100
}
```

Zapisuj `retrieval_mode` w `query_logs`.

---

## Ćwiczenie obowiązkowe

We własnej notatce — **10 pytań**:

- 5 faktograficznych (np. cel projektu, data GA),
- 5 relacyjnych (kto, z kim, zależności).

Dla każdego: oceń vector (1–5) i GraphRAG (1–5).

**Sukces:** GraphRAG ≥ o 1 punkt lepszy na min. **4/5** relacyjnych; faktograficzne nie gorsze niż -1 punkt.

---

## Optymalizacje (opcjonalne)

- **Równoległość:** `CompletableFuture` / coroutines — graf i vector jednocześnie dla `HYBRID`.
- **Cache:** `graph:context:{entityId}` w Redis, TTL 10 min.
- **Multi-hop:** `depth=2` tylko dla `intent=explore_network` — ogranicz do 20 węzłów.

---

## Typowe problemy

| Problem | Rozwiązanie |
|---------|-------------|
| Graf nie pasuje do pytania | Fuzzy match nazw projektów; aliasy z Etapu 3 |
| Za krótka odpowiedź relacyjna | Zawsze doładuj min. 3 chunki z `documentIds` |
| Halucynowane relacje | „Relacje tylko z sekcji grafu” |
| Wolno (>5 s) | Cache; mniejszy model do QueryAnalyzer |

---

## Kryterium ukończenia

- [ ] `/api/ask` domyślnie GraphRAG
- [ ] `graphContext` przy pytaniach relacyjnych
- [ ] Fallback vector gdy graf pusty
- [ ] Tabela 10 pytań wypełniona
- [ ] 4/5 relacyjnych lepszych niż sam vector

---

## Artefakty po etapie

```
service/graphrag/QueryAnalyzer.kt
service/graphrag/GraphRagService.kt
service/graphrag/ContextBuilder.kt
(własna notatka: porównanie vector vs GraphRAG)
```

---

## Co dalej

→ [Etap 5: Agent z narzędziami](etap-05-agent.md)
