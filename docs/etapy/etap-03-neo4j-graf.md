# Etap 3: Neo4j — knowledge graph

| | |
|--|--|
| **Czas** | ~2–2,5 tygodnia (16–22 h) |
| **Wymaga** | [Etap 2](etap-02-async-redis.md) |
| **Daje** | Graf encji, deduplikacja, API eksploracji |
| **Następny** | [Etap 4](etap-04-graphrag.md) |

---

## Cel etapu

Po zindeksowaniu dokumentu zbudować **graf wiedzy** w Neo4j:

- ekstrakcja encji i relacji (LLM → JSON),
- deduplikacja aliasów,
- `MERGE` węzłów i krawędzi,
- REST API do przeglądania grafu.

`POST /api/ask` na razie **bez zmian** (nadal hybrid RAG) — GraphRAG w Etapie 4.

---

## Pipeline (krok w workerze)

```
ingest DONE (chunki w Postgres)
        |
        v
GraphIngestStep
        |
        +-- EntityExtractionService (LLM, 1× na dokument lub na sekcje)
        +-- EntityDeduplicationService
        +-- GraphWriteService (Neo4j MERGE)
        |
        v
documents.graph_status = INDEXED | FAILED
```

---

## Krok 1: Neo4j w Docker

```yaml
  neo4j:
    image: neo4j:5-community
    environment:
      NEO4J_AUTH: neo4j/changeme
      NEO4J_PLUGINS: '["apoc"]'
    ports:
      - "7474:7474"   # Browser — tylko dev
      - "7687:7687"   # Bolt
    volumes:
      - neo4jdata:/data
```

Gradle:

```kotlin
implementation("org.neo4j.driver:neo4j-java-driver:5.26.0")
```

---

## Krok 2: Model grafu

### Węzły

| Label | Właściwości | Id stabilny |
|-------|-------------|-------------|
| `Document` | `id`, `path`, `filename` | UUID z Postgres |
| `Person` | `canonicalId`, `canonicalName`, `aliases` | `person:jan-kowalski` |
| `Project` | `canonicalId`, `name`, `status` | `project:alpha` |
| `Concept` | `canonicalId`, `name` | `concept:payment-gateway` |

### Relacje

| Typ | Od → Do | Atrybuty |
|-----|---------|----------|
| `MENTIONS` | Document → Person/Project/Concept | — |
| `WORKS_ON` | Person → Project | `role`, `sourceDocumentId` |
| `DEPENDS_ON` | Project → Project | — |
| `ESCALATES` | Person → Concept/Project | `context` (np. ryzyka) |

### Indeksy (Cypher, jednorazowo)

```cypher
CREATE CONSTRAINT person_id IF NOT EXISTS FOR (p:Person) REQUIRE p.canonicalId IS UNIQUE;
CREATE CONSTRAINT project_id IF NOT EXISTS FOR (p:Project) REQUIRE p.canonicalId IS UNIQUE;
CREATE INDEX person_name IF NOT EXISTS FOR (p:Person) ON (p.canonicalName);
```

---

## Krok 3: Ekstrakcja encji — prompt

```
Przeanalizuj poniższy dokument i zwróć TYLKO JSON (bez markdown):
{
  "entities": [
    { "type": "Person|Project|Concept", "name": "...", "attributes": {} }
  ],
  "relationships": [
    { "from": "nazwa encji", "to": "nazwa encji", "type": "WORKS_ON|DEPENDS_ON|...", "attributes": {} }
  ]
}

Zasady:
- Używaj dokładnych nazw z tekstu.
- Nie wymyślaj encji spoza dokumentu.
- Typy relacji tylko z listy: MENTIONS, WORKS_ON, DEPENDS_ON, ESCALATES.

Dokument:
---
{document_text}
---
```

**Strategia wywołań:**

1. Na start: **cały dokument** (do ~8k tokenów).
2. Jeśli za długi: ekstrakcja **per sekcja** (nagłówki z chunkera).

Walidacja: Jackson → data class; przy błędzie → retry (2×) z dopiskiem „popraw JSON”.

---

## Krok 4: Deduplikacja

### Tabela aliasów (Postgres)

`V004__entity_aliases.sql`:

```sql
CREATE TABLE entity_aliases (
    id            UUID PRIMARY KEY,
    entity_type   TEXT NOT NULL,
    alias         TEXT NOT NULL,
    canonical_id  TEXT NOT NULL,
    UNIQUE(entity_type, alias)
);

INSERT INTO entity_aliases (id, entity_type, alias, canonical_id) VALUES
  (gen_random_uuid(), 'Person', 'jan kowalski', 'person:jan-kowalski'),
  (gen_random_uuid(), 'Person', 'j. kowalski', 'person:jan-kowalski'),
  (gen_random_uuid(), 'Project', 'alpha', 'project:alpha'),
  (gen_random_uuid(), 'Project', 'project alpha', 'project:alpha');
```

### Algorytm `resolveCanonical(type, rawName)`

1. `normalize(s) = lowercase(trim(removeExtraSpaces(s)))`
2. Szukaj w `entity_aliases` po `(type, normalize(rawName))`
3. Jeśli brak — `generateId(type, rawName)` np. slug z nazwiska
4. Opcjonalnie fuzzy: Levenshtein < 2 w obrębie tego samego `type`

### MERGE w Neo4j

```cypher
MERGE (p:Person {canonicalId: $canonicalId})
SET p.canonicalName = $displayName,
    p.aliases = apoc.coll.toSet(coalesce(p.aliases, []) + $alias)

MERGE (proj:Project {canonicalId: $projectId})
MERGE (p)-[r:WORKS_ON]->(proj)
SET r.role = $role, r.sourceDocumentId = $docId
```

---

## Krok 5: API grafu

| Metoda | Ścieżka | Opis |
|--------|---------|------|
| `GET` | `/api/graph/entities?q=jan&type=Person` | wyszukiwanie |
| `GET` | `/api/graph/entities/{canonicalId}` | szczegóły węzła |
| `GET` | `/api/graph/entities/{canonicalId}/neighbors?depth=1` | sąsiedzi |
| `GET` | `/api/graph/path?from=&to=` | najkrótsza ścieżka (opcjonalnie) |
| `POST` | `/api/graph/rebuild/{documentId}` | wymuszenie przebudowy |

**Przykład neighbors dla `person:jan-kowalski`:**

```json
{
  "entity": { "type": "Person", "canonicalName": "Jan Kowalski" },
  "neighbors": [
    { "relationship": "WORKS_ON", "node": { "type": "Project", "name": "Project Alpha" }, "role": "tech lead" }
  ],
  "documents": ["projects/project-alpha/overview.md", "people/team-roster.md"]
}
```

---

## Testy (własna notatka)

| Pytanie (ręcznie przez API) | Oczekiwany wynik w grafie |
|-----------------------------|---------------------------|
| Kto pracuje nad Alpha? | `WORKS_ON` od Jan, Anna, Maria |
| Od czego zależy Alpha? | `DEPENDS_ON` → Payment Gateway / Beta |
| Kto eskaluje ryzyka Alpha? | `ESCALATES` lub `WORKS_ON` + dokument |
| Ile węzłów Person dla Kowalskiego? | **1** (deduplikacja) |
| Które dokumenty wspominają Annę? | `MENTIONS` z 2+ Document |

W Neo4j Browser:

```cypher
MATCH (p:Person {canonicalId: 'person:jan-kowalski'})-[r]->(n)
RETURN p, r, n
```

---

## Typowe problemy

| Problem | Rozwiązanie |
|---------|-------------|
| Setki encji z jednego pliku | Ogranicz typy; ekstrahuj z sekcji „Zespół”, „Ryzyka” |
| JSON z markdown ``` | Strip fence przed parse; retry |
| Duplikaty Project | Aliasy „Alpha” / „Project Alpha” |
| Graf nieaktualny po edycji MD | Re-ingest → `rebuild` usuwa stare `MENTIONS` z tego `documentId` |

### Czyszczenie relacji dokumentu przed rebuild

```cypher
MATCH (d:Document {id: $docId})-[r:MENTIONS]->()
DELETE r
```

Potem ponowne `MENTIONS` i relacje pochodne z nowej ekstrakcji.

---

## Kryterium ukończenia

- [ ] Graf buduje się po każdym ingest job DONE
- [ ] Jan Kowalski + J. Kowalski = **1** węzeł Person
- [ ] API neighbors zwraca sensowne `WORKS_ON` z `role`
- [ ] 5 pytań relacyjnych — graf (bez LLM) daje poprawną strukturę
- [ ] `graph_status: FAILED` nie blokuje RAG w Postgres

---

## Artefakty po etapie

```
V004__entity_aliases.sql
service/graph/EntityExtractionService.kt
service/graph/EntityDeduplicationService.kt
service/graph/GraphWriteService.kt
api/GraphController.kt
docker-compose.yml (+ neo4j)
```

---

## Co dalej

→ [Etap 4: GraphRAG](etap-04-graphrag.md)
