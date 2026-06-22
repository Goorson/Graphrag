# Etap 1: Multi-doc RAG

| | |
|--|--|
| **Czas** | ~1,5–2 tygodnie (12–18 h) |
| **Wymaga** | [Etap 0](etap-00-hello-rag.md) |
| **Daje** | Wiele MD/PDF, hybrid search, logi zapytań |
| **Następny** | [Etap 2](etap-02-async-redis.md) |

---

## Cel etapu

Rozszerzyć RAG na **cały korpus** `data/documents/`:

- rekursywny ingest wielu `.md` i `.pdf`,
- chunking z metadanymi (`section`, `page`),
- **hybrid search** (wektor + full-text),
- logowanie zapytań,
- CRUD na dokumentach.

### Poza zakresem

- Async / Redis (Etap 2) — ingest może być synchroniczny, ale **wolny** przy wielu plikach
- Neo4j, agent

---

## Przepływ ingestu

```
POST /api/documents/ingest-folder
        |
        v
rekursywnie: *.md, *.pdf w data/documents/
        |
        +-- .md  → MarkdownChunker (jak Etap 0)
        |
        +-- .pdf → PdfTextExtractor (PDFBox) → chunk per strona lub akapit
        |
        v
dla każdego pliku: upsert documents (path + content_hash)
        → usuń stare chunki tego document_id
        → nowe chunki + embeddingi
```

---

## Krok 1: PDF — Apache PDFBox

Gradle:

```kotlin
implementation("org.apache.pdfbox:pdfbox:3.0.3")
```

`PdfTextExtractor`:

```kotlin
// Dla każdej strony: strip text → jeden chunk lub podział jeśli strona > limit
// metadata: page = numer strony (1-based)
```

**Skany bez warstwy tekstu** — zwróć `status: SKIPPED_OCR_REQUIRED` w metadanych dokumentu; nie wywalaj całego batcha.

---

## Krok 2: Migracja — full-text + query_logs

`V002__fulltext_and_logs.sql`:

```sql
ALTER TABLE documents ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'INDEXED';

ALTER TABLE chunks ADD COLUMN IF NOT EXISTS page INT;

ALTER TABLE chunks ADD COLUMN IF NOT EXISTS content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED;

CREATE INDEX idx_chunks_content_tsv ON chunks USING gin(content_tsv);

CREATE TABLE query_logs (
    id              UUID PRIMARY KEY,
    question        TEXT NOT NULL,
    answer_preview  TEXT,
    sources_json    JSONB,
    retrieval_mode  TEXT NOT NULL DEFAULT 'HYBRID',
    latency_ms      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Konfiguracja `simple` — bez odmiany polskiej; na start wystarczy. Później: słownik polski lub `pg_trgm`.

---

## Krok 3: Upsert i re-ingest

Przed ponownym indeksem tego samego pliku:

1. Oblicz `SHA-256` treści pliku → `content_hash`.
2. Jeśli `path` istnieje i hash się **nie zmienił** — pomiń (opcjonalnie).
3. Jeśli hash inny — `DELETE FROM chunks WHERE document_id = ?`, potem nowe chunki.

Zapobiega duplikatom przy wielokrotnym `ingest-folder`.

---

## Krok 4: Hybrid search

### Wektor (top K₁ = 10)

```sql
SELECT id, content, document_id, section, page,
       (embedding <=> :queryVec) AS score
FROM chunks
ORDER BY embedding <=> :queryVec
LIMIT 10;
```

### Full-text (top K₂ = 10)

```sql
SELECT id, content, document_id, section, page,
       ts_rank(content_tsv, plainto_tsquery('simple', :q)) AS score
FROM chunks
WHERE content_tsv @@ plainto_tsquery('simple', :q)
ORDER BY score DESC
LIMIT 10;
```

### RRF — Reciprocal Rank Fusion

Dla każdego chunka `id` z obu list:

```
RRF(id) = Σ 1 / (k + rank_i)
```

`k` = 60 (stała standardowa), `rank` = pozycja na liście (1-based).

Posortuj po `RRF` malejąco, weź **top 7** do promptu.

**Alternatywa prostsza:** weź unię top-5 vector + top-5 keyword, deduplikuj po `id`.

---

## Krok 5: Limit kontekstu w prompcie

- Szacuj ~4 znaki = 1 token.
- Max kontekst dla `gpt-4o-mini`: ~6000 znaków fragmentów (zostaw miejsce na pytanie).
- Jeśli chunki za długie — przytnij `excerpt` w `sources`, pełny tekst tylko w prompcie.

---

## API (pełna lista)

| Metoda | Ścieżka | Opis |
|--------|---------|------|
| `POST` | `/api/documents` | upload multipart |
| `POST` | `/api/documents/ingest-folder` | skan `data/documents/` |
| `GET` | `/api/documents` | lista + status |
| `GET` | `/api/documents/{id}` | szczegóły |
| `DELETE` | `/api/documents/{id}` | usuń dokument + chunki |
| `POST` | `/api/ask` | RAG (hybrid domyślnie) |
| `GET` | `/api/query-logs?limit=20` | ostatnie zapytania (dev) |

### Przykład ingest folderu

```bash
curl -X POST http://localhost:8080/api/documents/ingest-folder
```

### Odpowiedź ask (rozszerzona)

```json
{
  "answer": "...",
  "sources": [
    {
      "documentId": "...",
      "filename": "people/team-roster.md",
      "section": "Inżynieria",
      "page": null,
      "excerpt": "Jan Kowalski — Tech Lead — Project Alpha"
    }
  ],
  "retrievalMode": "HYBRID",
  "latencyMs": 1100
}
```

---

## Korpus testowy

Uzupełnij `data/documents/` — w repo są już 3 pliki MD. Dodaj min.:

- `projects/project-beta/overview.md`
- `runbooks/incident-response.md`
- `runbooks/deployment.md`
- 2–3 PDF (eksport MD→PDF lub docs open source)

**Ważne:** powtarzaj te same osoby i projekty — przyda się w Etapie 3.

---

## Testy (własna notatka)

Przykładowe pytania:

| # | Pytanie | Oczekiwany plik |
|---|---------|-----------------|
| 1 | Kto to J. Kowalski? | `people/team-roster.md` |
| 2 | Jakie ryzyka ma Alpha? | `projects/project-alpha/overview.md` |
| 3 | Od czego zależy Alpha? | `projects/project-alpha/overview.md` |
| 4 | Ile osób w inżynierii? | `people/team-roster.md` |
| 5 | Co robić przy incydencie? | `runbooks/incident-response.md` |

Dodaj pytania łączące **dwa** dokumenty.

### Ćwiczenie porównawcze (3 pytania)

Uruchom ask z `?mode=vector` i domyślnym hybrid — zanotuj, gdzie keyword pomógł (np. nazwisko „Wiśniewski”).

---

## Typowe problemy

| Problem | Rozwiązanie |
|---------|-------------|
| PDF pusty | `PDFTextStripper` zwraca blank → status `SKIPPED` |
| Polskie znaki w tsvector | Upewnij się UTF-8; rozważ `unaccent` później |
| Ten sam chunk 2× w wynikach | Deduplikacja po `chunk.id` przed promptem |
| ingest-folder 2 min | Normalne — Etap 2 przeniesie to do workera |
| Odpowiedź ze złego projektu | Zwiększ wagę keyword dla nazw własnych |

---

## Kryterium ukończenia

- [ ] **20+** dokumentów w indeksie (MD + min. 2 PDF)
- [ ] Hybrid search włączony domyślnie
- [ ] Każda odpowiedź ma `sources[]` z `filename` + `excerpt`
- [ ] `query_logs` zapisuje historię
- [ ] Notatka: 3 pytania gdzie hybrid > sam vector

---

## Artefakty po etapie

```
V002__fulltext_and_logs.sql
service/chunking/PdfTextExtractor.kt
service/HybridRetrievalService.kt
(własna notatka z pytaniami testowymi)
```

---

## Co dalej

→ [Etap 2: Async pipeline + Redis](etap-02-async-redis.md)
