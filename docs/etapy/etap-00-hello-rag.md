# Etap 0: Hello RAG

| | |
|--|--|
| **Czas** | ~1 tydzień (8–10 h) |
| **Wymaga** | Kotlin, Spring Boot, REST; [decyzje techniczne](../decyzje.md) |
| **Daje** | Jeden dokument MD → pytanie → odpowiedź ze źródłem |
| **Następny** | [Etap 1](etap-01-multi-doc-rag.md) |

---

## Cel etapu

Zbudować **najmniejszy działający RAG**:

1. Wczytanie jednego pliku `.md`.
2. Chunking → embeddingi → zapis w Postgres (**pgvector**).
3. Endpoint `/api/ask` — similarity search + odpowiedź LLM z cytatem.

### Poza zakresem tego etapu

- Wiele dokumentów, PDF, hybrid search (Etap 1)
- Redis, kolejki (Etap 2)
- Neo4j, agent (Etapy 3–5)

---

## Przepływ

```
POST /api/documents     →  read MD → chunk → embed → INSERT chunks
POST /api/ask           →  embed pytanie → top-K chunks → prompt → LLM → JSON + sources
```

---

## Krok 1: Szkielet projektu

### Spring Initializr (lub ręcznie)

- **Project:** Gradle - Kotlin
- **Spring Boot:** 3.3+
- **Dependencies:** Spring Web, Spring Data JPA (opcjonalnie), Validation, Flyway, PostgreSQL Driver, Spring Boot Actuator (opcjonalnie na później)

### Zależności Gradle (dopisz)

**LangChain4j:**

```kotlin
implementation("dev.langchain4j:langchain4j-open-ai:0.36.2")
implementation("dev.langchain4j:langchain4j:0.36.2")
```

**Spring AI (alternatywa):**

```kotlin
implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter")
```

Wersje sprawdź w dokumentacji — używaj jednej linii produktowej.

### Struktura pakietów

```
src/main/kotlin/com/acme/graphrag/
├── GraphRagApplication.kt
├── config/
├── domain/
├── repository/
├── service/
│   ├── chunking/MarkdownChunker.kt
│   ├── IngestService.kt
│   ├── EmbeddingService.kt
│   └── RagService.kt
└── api/
    ├── DocumentController.kt
    └── AskController.kt
```

---

## Krok 2: Docker — Postgres + pgvector

Plik `docker/docker-compose.yml`:

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: graphrag
      POSTGRES_USER: graphrag
      POSTGRES_PASSWORD: changeme
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U graphrag -d graphrag"]
      interval: 5s
      timeout: 5s
      retries: 5

volumes:
  pgdata:
```

Uruchomienie: `docker compose -f docker/docker-compose.yml up -d`

---

## Krok 3: Migracja Flyway

`src/main/resources/db/migration/V001__init.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE documents (
    id          UUID PRIMARY KEY,
    filename    TEXT NOT NULL,
    path        TEXT NOT NULL UNIQUE,
    mime_type   TEXT NOT NULL,
    content_hash TEXT,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chunks (
    id           UUID PRIMARY KEY,
    document_id  UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index  INT NOT NULL,
    section      TEXT,
    content      TEXT NOT NULL,
    embedding    vector(1536),  -- text-embedding-3-small; dla innego modelu zmień wymiar
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chunks_document ON chunks(document_id);
CREATE INDEX idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);
```

**Uwaga:** `1536` = OpenAI `text-embedding-3-small`. Ollama `nomic-embed-text` = **768** — dostosuj kolumnę i model razem.

---

## Krok 4: Chunking Markdown

Algorytm `MarkdownChunker`:

1. Podziel tekst po liniach zaczynających się od `##` lub `###`.
2. Każda sekcja = jeden chunk (jeśli < ~2000 znaków).
3. Jeśli sekcja za długa — podziel na okna **~500 tokenów** (~2000 znaków) z **overlap 100 znaków**.
4. Zapisz `section` = treść nagłówka (np. `"Zespół"`).

Na Etapie 0 **nie** potrzebujesz biblioteki tokenizera — przybliżenie po znakach wystarczy.

---

## Krok 5: Embeddingi i zapis

`EmbeddingService`:

- wejście: `List<String>` tekstów chunków,
- wyjście: `List<FloatArray>`,
- batch po 10–20 tekstów (limit API).

Zapis wektorów — **JdbcTemplate**, nie JPA:

```sql
INSERT INTO chunks (id, document_id, chunk_index, section, content, embedding)
VALUES (?, ?, ?, ?, ?, ?::vector)
```

Konwersja `FloatArray` → string `"[0.1,0.2,...]"` dla Postgres.

---

## Krok 6: RAG — zapytanie

`RagService.ask(question: String)`:

```sql
SELECT c.id, c.content, c.section, d.path AS filename,
       (c.embedding <=> ?::vector) AS distance
FROM chunks c
JOIN documents d ON d.id = c.document_id
ORDER BY c.embedding <=> ?::vector
LIMIT 5;
```

Operator `<=>` = cosine distance (pgvector).

### Prompt systemowy (szablon)

```
Jesteś asystentem odpowiadającym WYŁĄCZNIE na podstawie podanego kontekstu.
Zasady:
- Jeśli odpowiedzi nie ma w kontekście, napisz: "Nie znalazłem tej informacji w dokumentach."
- Na końcu wymień numer źródła w nawiasach, np. [1].
- Nie wymyślaj faktów.

Kontekst:
[1] (projects/project-alpha/overview.md · Zespół)
Jan Kowalski — tech lead
...
```

---

## API

### `POST /api/documents`

**Opcja A — multipart:**

```bash
curl -X POST http://localhost:8080/api/documents \
  -F "file=@data/documents/company/overview.md"
```

**Opcja B — ingest ze ścieżki (dev):**

```json
{ "path": "data/documents/company/overview.md" }
```

**Odpowiedź 201:**

```json
{
  "documentId": "550e8400-e29b-41d4-a716-446655440000",
  "chunksCreated": 4,
  "filename": "company/overview.md"
}
```

### `POST /api/ask`

```bash
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "Kto jest tech leadem Project Alpha?"}'
```

**Odpowiedź 200:**

```json
{
  "answer": "Tech leadem Project Alpha jest Jan Kowalski [1].",
  "sources": [
    {
      "index": 1,
      "documentId": "...",
      "filename": "projects/project-alpha/overview.md",
      "section": "Zespół",
      "excerpt": "Jan Kowalski — tech lead"
    }
  ],
  "latencyMs": 890
}
```

---

## Konfiguracja

Skopiuj `.env.example` → `.env`. W `application.yml`:

```yaml
spring:
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USER}
    password: ${DATABASE_PASSWORD}
  flyway:
    enabled: true

app:
  llm:
    api-key: ${OPENAI_API_KEY}
    chat-model: ${CHAT_MODEL:gpt-4o-mini}
    embedding-model: ${EMBEDDING_MODEL:text-embedding-3-small}
```

---

## Testy

Przygotuj **5 pytań testowych** z oczekiwaną odpowiedzią — zapisz we własnej notatce.

1. Kto jest tech leadem Project Alpha?
2. Od czego zależy Project Alpha?
3. Jakie są ryzyka projektu?
4. Kto eskaluje ryzyka biznesowe?
5. Kiedy planowane jest GA? *(jeśli jest w dokumencie)*

### Checklista

- [ ] `docker compose up` + `./gradlew bootRun`
- [ ] Ingest `overview.md` z Alpha
- [ ] W DB: `SELECT count(*) FROM chunks` > 0
- [ ] 4/5 pytań PASS ze źródłem
- [ ] Pytanie „Jaka jest pogoda?” → brak halucynacji

---

## Typowe problemy

| Problem | Przyczyna | Rozwiązanie |
|---------|-----------|-------------|
| `dimension mismatch` | Zły wymiar `vector(N)` | Dopasuj N do modelu embedding |
| Pusty kontekst | Brak chunków / złe similarity | Sprawdź `SELECT count(*) FROM chunks` |
| Halucynacje | Słaby prompt | Wzmocnij „tylko z kontekstu” |
| `extension vector does not exist` | Brak `CREATE EXTENSION` | Migracja V001 |
| Wolny HNSW przy małej bazie | Niepotrzebny indeks | Przy <1000 chunków możesz użyć brute force bez HNSW |

---

## Kryterium ukończenia

- [ ] `POST /api/documents` dla jednego `.md`
- [ ] `POST /api/ask` z `sources[]`
- [ ] **4/5** pytań PASS w rejestrze testów
- [ ] Start: Compose + bootRun bez ręcznych kroków w DB

---

## Artefakty po etapie

```
docker/docker-compose.yml
src/main/.../ (aplikacja Spring)
src/main/resources/db/migration/V001__init.sql
(własna notatka z pytaniami testowymi)
```

---

## Co dalej

→ [Etap 1: Multi-doc RAG](etap-01-multi-doc-rag.md)
