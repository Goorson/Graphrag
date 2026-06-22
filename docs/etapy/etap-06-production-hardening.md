# Etap 6: Production hardening

| | |
|--|--|
| **Czas** | ~1,5–2 tygodnie (12–16 h) |
| **Wymaga** | [Etap 5](etap-05-agent.md) |
| **Daje** | Odporność na awarie AI, metryki, pełny Docker |
| **Następny** | [Etap 7 — opcja](etap-07-deploy.md) |

---

## Cel etapu

Utwierdzić system pod codzienną pracę:

- retry i walidacja JSON z LLM,
- timeouty, circuit breaker, rate limiting,
- health + metryki,
- graceful degradation,
- **jeden** `docker compose up` (app + postgres + redis + neo4j).

Po tym etapie projekt jest **kompletny** do nauki.

---

## Krok 1: StructuredOutputHandler

Wspólny komponent dla Etapu 3 (ekstrakcja), 4 (QueryAnalyzer), 5 (ew. JSON tools):

```kotlin
inline fun <reified T> parseWithRetry(
    raw: String,
    maxAttempts: Int = 3,
    fetch: (repairPrompt: String?) -> String,
): T {
    var lastError: Exception? = null
    repeat(maxAttempts) { attempt ->
        try {
            val cleaned = stripMarkdownFence(raw)
            return objectMapper.readValue(cleaned, T::class.java)
        } catch (e: Exception) {
            lastError = e
            raw = fetch("Popraw JSON. Błąd: ${e.message}. Zwróć tylko poprawny JSON.")
        }
    }
    throw RecoverableAiException("JSON parse failed", lastError)
}
```

Metryka: `llm.json.parse.errors` + `llm.json.parse.retries`.

---

## Krok 2: Resilience4j (LLM HTTP)

```kotlin
@Retry(name = "llm")
@CircuitBreaker(name = "llm")
fun embed(texts: List<String>): List<FloatArray>
```

`application.yml`:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      llm:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
  retry:
    instances:
      llm:
        max-attempts: 3
        wait-duration: 1s
        exponential-backoff-multiplier: 2
```

Przy `CallNotPermittedException` → HTTP 503 + body `{ "error": "AI service temporarily unavailable" }`.

---

## Krok 3: Rate limiting (Bucket4j + Redis)

```kotlin
// Klucz: ratelimit:{apiKey|ip}:{endpoint}
// ask: 30/min, ingest: 10/min
```

Nagłówki: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After` przy 429.

---

## Krok 4: Health indicators

| Indicator | `UP` gdy |
|-----------|----------|
| `db` | `SELECT 1` Postgres |
| `redis` | `PING` |
| `neo4j` | `RETURN 1` |
| `llm` (opcjonalny) | embedding słowa „ping” < 5 s |

`/actuator/health/readiness` — bez LLM w dev (żeby app wstała bez klucza).

---

## Krok 5: Metryki Micrometer

```kotlin
meterRegistry.counter("ask.requests.total", "mode", retrievalMode).increment()
meterRegistry.timer("ask.latency").record(duration)
meterRegistry.counter("ask.sources.empty").increment()  // gdy sources.isEmpty()
```

Eksport: `/actuator/prometheus`.

**Alerty ręczne (obserwuj co tydzień):**

- `ask.sources.empty` > 20% requestów → słaby RAG lub prompt
- `ingest.jobs.failed` rośnie → problem z PDF/API
- `agent.steps.count` p99 > 6 → agent się zapętla

---

## Krok 6: Graceful degradation

| Awaria | Zachowanie użytkownika |
|--------|------------------------|
| Neo4j down | Ask działa `VECTOR_ONLY`; pole `degraded: true` w JSON |
| Redis down | Ingest: synchroniczny fallback LUB 503 z komunikatem |
| LLM down | 503 + `Retry-After` |
| Pojedynczy FAILED job | Inne joby i ask bez zmian |

Implementacja: `@CircuitBreaker` na `GraphWriteService` / `GraphRetrievalService` + fallback w `GraphRagService`.

---

## Krok 7: Docker — pełny stos

`docker/Dockerfile` (multi-stage):

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
HEALTHCHECK CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`docker-compose.yml` — serwisy: `postgres`, `redis`, `neo4j`, `app` (zależności `depends_on` + healthcheck).

Zmienne z `.env` — **nie** w obrazie.

---

## Krok 8: Notatka operacyjna (opcjonalnie)

Zapisz u siebie (Notion, plik tekstowy):

1. **Uruchomienie lokalne** — `cp .env.example .env`, compose, bootRun
2. **Pełny reset indeksu** — truncate chunks/documents, re-ingest folder
3. **FAILED jobs** — `GET /api/jobs?status=FAILED`, retry, logi
4. **Agent steps** — jak czytać `/api/chat/sessions/{id}/steps`
5. **Typowe błędy LLM** — JSON, timeout, 429 OpenAI
6. **Zużycie RAM** — Neo4j heap, Postgres shared_buffers (dev)

---

## Testy chaosu (własna notatka)

| # | Akcja | Oczekiwany wynik |
|---|-------|------------------|
| 1 | `docker stop neo4j` + ask relacyjne | 200, `degraded: true`, vector-only |
| 2 | 35× ask w 1 min | 429 na części |
| 3 | Mock zły JSON ekstrakcji | retry → sukces lub czytelny błąd |
| 4 | corrupt.pdf | FAILED, inne DONE |
| 5 | LLM timeout (mock) | 503, wątek wolny |

---

## Bezpieczeństwo (minimum)

- [ ] `.env` w `.gitignore`
- [ ] Actuator: tylko `health`, `prometheus` publicznie; reszta wyłączona
- [ ] Opcjonalny header `X-API-Key` na `/api/*`
- [ ] Logi bez pełnych promptów i kluczy API

---

## Kryterium ukończenia

- [ ] `docker compose up --build` — cały system
- [ ] Health: postgres, redis, neo4j = UP
- [ ] JSON retry działa (test jednostkowy lub integracyjny)
- [ ] Rate limit 429
- [ ] Neo4j down → ask z degradacją
- [ ] Notatka operacyjna (jeśli robisz) — uruchomienie, reset indeksu, debug jobów

---

## Artefakty po etapie

```
docker/Dockerfile
docker/docker-compose.yml (pełny)
config/StructuredOutputHandler.kt
config/RateLimitFilter.kt
(własna notatka operacyjna — opcjonalnie)
```

---

## Co dalej

→ [Etap 7: Deploy](etap-07-deploy.md) (opcjonalnie)

Po Etapie 6 możesz zatrzymać się lub wdrożyć na VPS.
