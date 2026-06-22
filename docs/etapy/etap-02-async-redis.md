# Etap 2: Async pipeline + Redis

| | |
|--|--|
| **Czas** | ~1,5–2 tygodnie (12–16 h) |
| **Wymaga** | [Etap 1](etap-01-multi-doc-rag.md) |
| **Daje** | Indeksowanie w tle, joby, retry, Redis |
| **Następny** | [Etap 3](etap-03-neo4j-graf.md) |

---

## Cel etapu

Przenieść ingest (chunking + embeddingi) **poza wątek HTTP**:

- `202 Accepted` + `jobId` zamiast długiego oczekiwania,
- worker konsumuje kolejkę Redis,
- statusy jobów i retry,
- opcjonalny cache embeddingów w Redis.

`POST /api/ask` pozostaje **synchroniczne**.

---

## Maszyna stanów joba

```
        create
PENDING ──────► PROCESSING ──────► DONE
                    │
                    │ error (attempts < max)
                    ▼
              (retry delay)
                    │
                    └──► PENDING
                    │
                    │ error (attempts >= max)
                    ▼
                 FAILED ──────► (ręczny retry) ──► PENDING
```

**Zasady:**

- `PROCESSING` dłużej niż **30 min** → watchdog ustawia `FAILED` (stale job).
- Przy starcie aplikacji: joby `PROCESSING` → `PENDING` (recovery).

---

## Przepływ

```
POST /api/documents  →  zapis pliku na dysk / metadane
                     →  INSERT ingest_jobs (PENDING)
                     →  LPUSH ingest:queue {jobId}
                     ←  202 { jobId, status: "PENDING" }

IngestWorker (@Scheduled co 1s lub BRPOP)
                     →  RPOP / BRPOP ingest:queue
                     →  UPDATE status PROCESSING
                     →  IngestService (logika z Etapu 1)
                     →  DONE | FAILED
```

---

## Krok 1: Redis w Compose

Rozszerz `docker/docker-compose.yml`:

```yaml
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      retries: 5
```

`application.yml`:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
```

---

## Krok 2: Migracja jobów

`V003__ingest_jobs.sql`:

```sql
CREATE TABLE ingest_jobs (
    id              UUID PRIMARY KEY,
    document_id     UUID REFERENCES documents(id) ON DELETE SET NULL,
    type            TEXT NOT NULL,  -- SINGLE_FILE | FOLDER_SCAN
    status          TEXT NOT NULL,  -- PENDING | PROCESSING | DONE | FAILED
    payload_json    JSONB,          -- ścieżka, lista plików, opcje
    attempts        INT NOT NULL DEFAULT 0,
    max_attempts    INT NOT NULL DEFAULT 3,
    error_message   TEXT,
    progress_pct    INT DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ
);

CREATE INDEX idx_ingest_jobs_status ON ingest_jobs(status);
```

---

## Krok 3: Kolejka Redis List

Stałe:

```kotlin
const val INGEST_QUEUE = "ingest:queue"
```

Enqueue:

```kotlin
redis.opsForList().leftPush(INGEST_QUEUE, jobId.toString())
```

Worker (pętla):

```kotlin
val jobId = redis.opsForList().rightPop(INGEST_QUEUE, Duration.ofSeconds(5))
```

**Idempotencja:** przed `LPUSH` sprawdź, czy ten sam `document_id` nie ma już joba w `PENDING`/`PROCESSING`.

---

## Krok 4: Worker w Spring

```kotlin
@Component
class IngestWorker(
    private val jobRepository: IngestJobRepository,
    private val ingestService: IngestService,
    private val redis: StringRedisTemplate,
) {
    @Scheduled(fixedDelay = 1000)
    fun poll() {
        val jobId = redis.opsForList().rightPop(INGEST_QUEUE) ?: return
        processJob(UUID.fromString(jobId))
    }
}
```

Włącz `@EnableScheduling` w aplikacji.

`processJob`:

1. `attempts++`, `status = PROCESSING`, `started_at = now()`
2. try { `ingestService.ingest(...)`; `DONE` }
3. catch { jeśli `attempts < max` → `PENDING` + `LPUSH`; else `FAILED` + `error_message` }

Dla `FOLDER_SCAN`: aktualizuj `progress_pct` co N plików.

---

## Krok 5: API

| Metoda | Ścieżka | Kod | Body |
|--------|---------|-----|------|
| `POST` | `/api/documents` | **202** | multipart file |
| `POST` | `/api/documents/ingest-folder` | **202** | — |
| `GET` | `/api/jobs/{id}` | 200 | status, progress, error |
| `GET` | `/api/jobs?status=FAILED` | 200 | lista |
| `POST` | `/api/jobs/{id}/retry` | 202 | tylko dla FAILED |

**Przykład 202:**

```json
{
  "jobId": "...",
  "status": "PENDING",
  "links": { "status": "/api/jobs/..." }
}
```

---

## Krok 6: Cache embeddingów (opcjonalnie)

```
Klucz: emb:v1:{sha256(normalized_chunk_text)}
Wartość: JSON tablicy floatów
TTL: 7 dni
```

Przed wywołaniem API embedding — `GET`; po — `SET`. Oszczędność przy re-ingestcie bez zmian treści.

---

## Test chaosu (mini)

1. Wrzuć **uszkodzony** plik `corrupt.pdf` (losowe bajty).
2. Upload 5 poprawnych + 1 zepsuty równolegle.
3. Sprawdź: 5× DONE, 1× FAILED, ask działa na poprawnych.

---

## Observability

Loguj (structured):

```json
{
  "event": "ingest_job_completed",
  "jobId": "...",
  "documentId": "...",
  "durationMs": 4200,
  "chunks": 12,
  "attempt": 1
}
```

---

## Typowe problemy

| Problem | Rozwiązanie |
|---------|-------------|
| Job znika po restarcie | Job w Postgres jest źródłem prawdy; kolejka można odbudować z `PENDING` |
| Podwójne przetwarzanie | Distributed lock `SETNX lock:job:{id}` na czas PROCESSING |
| Worker nie działa | `@EnableScheduling`; profil nie wyłącza schedulera |
| Redis OOM | TTL na cache; maxmemory-policy `allkeys-lru` |

---

## Kryterium ukończenia

- [ ] Upload 10 plików → wszystkie **202** w < 1 s
- [ ] Job przechodzi PENDING → PROCESSING → DONE
- [ ] Zepsuty plik → FAILED po 3 próbach
- [ ] `POST /api/jobs/{id}/retry` działa
- [ ] Restart app — brak wiecznych PROCESSING
- [ ] Ask działa podczas trwającego ingestu

---

## Artefakty po etapie

```
V003__ingest_jobs.sql
service/worker/IngestWorker.kt
service/IngestJobService.kt
api/JobController.kt
docker-compose.yml (+ redis)
```

---

## Co dalej

→ [Etap 3: Neo4j + graf wiedzy](etap-03-neo4j-graf.md)
