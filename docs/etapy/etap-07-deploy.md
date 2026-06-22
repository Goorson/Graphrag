# Etap 7: Deploy (opcjonalny)

| | |
|--|--|
| **Czas** | ~1 tydzień (6–10 h) |
| **Wymaga** | [Etap 6](etap-06-production-hardening.md) |
| **Daje** | System dostępny poza localhost |

---

## Cel etapu

Doświadczenie z wdrożeniem: obraz Docker, sekrety, HTTPS, limity zasobów — **bez** skupiania się na portfolio.

---

## Wybór hostingu

| Opcja | Kiedy wybrać |
|-------|--------------|
| **VPS** (Hetzner CX22, DO Droplet 4GB) | Chcesz pełny Compose jak lokalnie |
| **Cloudflare Tunnel / ngrok** | Szybki test na 1–3 dni, zero kosztu |
| **Railway / Render** | OK dla samej app; Neo4j często osobno = drożej |

**Rekomendacja nauki:** VPS + Compose **lub** tunnel do lokalnego Compose.

---

## Wymagania RAM (minimalne)

| Serwis | RAM |
|--------|-----|
| Spring Boot app | 512 MB – 1 GB |
| Postgres + pgvector | 512 MB |
| Redis | 128 MB |
| Neo4j | 512 MB heap (`NEO4J_server_memory_heap_max__size=512m`) |
| **Razem** | **≥ 4 GB** na VPS |

Na 2 GB VPS — wyłącz Neo4j Browser port, zmniejsz heap, nie trzymaj dużego korpusu.

---

## Compose produkcyjny

`docker/docker-compose.prod.yml`:

- **Brak** mapowania portów Postgres/Redis/Neo4j na hosta (tylko sieć `internal`).
- `app` wystawione na `127.0.0.1:8080` — przed nim Caddy/nginx z HTTPS.
- Wolumeny nazwane dla danych.
- `restart: unless-stopped`.

```yaml
services:
  app:
    build: ..
    env_file: .env
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
      neo4j: { condition: service_healthy }
    networks: [internal, web]

  caddy:
    image: caddy:2-alpine
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
    networks: [web]
```

`Caddyfile`:

```
twoja-domena.pl {
    reverse_proxy app:8080
}
```

---

## Przepływ deploy (VPS)

```
1. Lokalnie: test + docker compose -f docker-compose.prod.yml build
2. Push obrazu do GHCR  LUB  git clone na VPS + build
3. Na VPS: .env z produkcyjnymi hasłami (openssl rand -hex 32)
4. docker compose -f docker-compose.prod.yml up -d
5. curl https://twoja-domena.pl/actuator/health/readiness
6. Jednorazowo: ingest korpusu (SSH + curl ingest-folder lub skrypt)
7. UptimeRobot ping co 5 min
```

---

## Sekrety

| Zmienna | Gdzie |
|---------|-------|
| `OPENAI_API_KEY` | tylko `.env` na serwerze |
| `POSTGRES_PASSWORD` | `.env`, inne niż dev |
| `NEO4J_AUTH` | `.env` |
| opcjonalny `API_KEY` | `.env` + weryfikacja w filtrze |

**Nigdy** w git, Dockerfile ARG z wartościami, ani logach.

---

## Tryb demo (jeśli publiczne API)

- Wyłącz `ingest-folder` w profilu `production`.
- Upload max **5 MB** / plik.
- Rate limit: **10 ask/min** na IP.
- Opcjonalnie statyczny `X-API-Key` — bez klucza 401.
- Korpus zindeksowany z góry — użytkownik tylko pyta.

---

## Backup

```bash
# Postgres
docker exec postgres pg_dump -U graphrag graphrag > backup_$(date +%F).sql

# Neo4j (community)
docker exec neo4j neo4j-admin database dump neo4j --to-path=/backups
```

Skrypt `scripts/backup.sh` + cron cotygodniowy (opcjonalnie).

---

## Checklist przed go-live

- [ ] `.env` nie w git; hasła ≠ `changeme`
- [ ] Porty baz zamknięte z internetu (`ss -tlnp`)
- [ ] HTTPS działa
- [ ] Rate limit włączony
- [ ] Billing limit w OpenAI
- [ ] Test ask z telefonu (LTE, nie WiFi domowe)
- [ ] Monitoring zewnętrzny na `/actuator/health`

---

## Koszty (orientacyjnie / mies.)

| Pozycja | Koszt |
|---------|-------|
| VPS 4 GB | 5–15 EUR |
| OpenAI (nauka, limit) | 5–20 USD |
| Domena | opcjonalnie ~10 EUR/rok |

---

## Kryterium ukończenia

- [ ] HTTPS + health z zewnątrz
- [ ] `docker-compose.prod.yml` udokumentowany u siebie (krótka notatka jak uruchomić)
- [ ] Sekrety tylko na serwerze
- [ ] Jedno udane pytanie z internetu z poprawną odpowiedzią

---

## Po całym projekcie

```
MD/PDF → async ingest → Postgres + Neo4j
              ↓
        GraphRAG + Agent
              ↓
        metryki, retry, rate limit
              ↓
        (opcjonalnie) VPS / tunnel
```

Kierunek rozwoju po deploy: OCR PDF, lepsza deduplikacja, testy integracyjne, reranking.

→ [Plan etapów](README.md)
