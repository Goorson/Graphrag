# GraphRAG Knowledge Assistant

Plan projektu w Kotlin + Spring Boot: RAG na plikach MD/PDF, graf w Neo4j, GraphRAG, agent AI.

## Status implementacji

| Etap | Status |
|------|--------|
| 0 — Hello RAG | **Zaimplementowany** |
| 1 — Multi-doc RAG (MD + PDF, hybrid) | **Zaimplementowany** |
| 2 — Async + Redis | **Zaimplementowany** |
| 3 — Neo4j (graf wiedzy) | **Zaimplementowany** |
| 4 — GraphRAG | **Zaimplementowany** |
| 5 — Agent (chat + tools) | **Zaimplementowany** |
| 6 — Production hardening | **Zaimplementowany** |
| 7 — Deploy (opcjonalny) | Dokumentacja w `docs/etapy/` |

## Stack

Kotlin, Spring Boot 3, LangChain4j, **Ollama**, PostgreSQL + pgvector, **Neo4j** (Person/Project/Concept/**Topic**), Redis, Flyway.

## Uruchomienie (Etap 0)

### 1. Ollama + modele

Jeśli masz [Ollama](https://ollama.com) na Windowsie:

```bash
ollama pull llama3.2
ollama pull nomic-embed-text
```

Albo przez Docker (serwis `ollama` w compose):

```bash
docker compose -f docker/docker-compose.yml up -d ollama
docker exec -it graphrag-knowledge-assistant-ollama-1 ollama pull llama3.2
docker exec -it graphrag-knowledge-assistant-ollama-1 ollama pull nomic-embed-text
```

(Nazwa kontenera może się różnić — sprawdź `docker ps`.)

### 2. Postgres + Redis

```powershell
docker compose -f docker/docker-compose.yml up -d postgres redis neo4j
```

### 3. Aplikacja

Wymagania: **Java 21+** (działa z JDK 26 + Gradle 9.4).

```powershell
.\gradlew.bat build
.\gradlew.bat bootRun
```

### Interfejs webowy

Po starcie aplikacji otwórz w przeglądarce:

**http://localhost:8080**

Interfejs oferuje:
- czat z agentem (pamięć sesji, follow-up),
- wrzucanie plików PDF i MD (drag-and-drop lub przycisk),
- listę dokumentów ze statusem indeksacji i grafu.

Pliki statyczne: `src/main/resources/static/`.

### 4. Indeks dokumentu (async — zwraca 202 + jobId)

Indeksowanie działa **w tle**. Skrypt sam czeka na DONE:

```powershell
.\scripts\ingest.ps1 -Path "data/documents/projects/project-alpha/overview.md"
```

Cały folder `data/documents`:

```powershell
.\scripts\ingest-folder.ps1
```

Status joba ręcznie:

```powershell
Invoke-RestMethod http://localhost:8080/api/jobs/<jobId>
```

Retry po FAILED:

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/jobs/<jobId>/retry
```

### 5. Pytanie

```powershell
.\scripts\ask.ps1 -Question "Kto jest tech leadem Project Alpha?"
```

Domyślnie `/api/ask` używa **GraphRAG** (graf + fragmenty dokumentów).

### 6. Agent konwersacyjny (Etap 5)

Sesja z pamięcią i narzędziami (`searchDocuments`, `queryGraph`, `getDocumentChunk`, `getEntityDetails`):

```powershell
.\scripts\chat.ps1 -Question "Kto prowadzi Alpha i jakie są główne ryzyka?"
```

API:

```powershell
$s = Invoke-RestMethod -Method Post http://localhost:8080/api/chat/sessions -ContentType application/json -Body "{}"
Invoke-RestMethod -Method Post "http://localhost:8080/api/chat/sessions/$($s.id)/messages" -ContentType application/json -Body '{"content":"Kto eskaluje Project Alpha?"}'
Invoke-RestMethod "http://localhost:8080/api/chat/sessions/$($s.id)/steps"
```

### 7. Pełny Docker (Etap 6)

```powershell
cp .env.example .env
docker compose -f docker/docker-compose.yml up -d --build
```

Health: `http://localhost:8080/actuator/health/readiness`  
Metryki: `http://localhost:8080/actuator/prometheus`

Gdy Neo4j jest niedostępny, `/api/ask` działa w trybie wektorowym z `"degraded": true`.

| Tryb | Opis |
|------|------|
| `?mode=graph_rag` | domyślny — analiza intencji + graf + hybrid search |
| `?mode=hybrid` | tylko vector + full-text (bez grafu) |
| `?mode=vector` | tylko podobieństwo wektorowe |
| `?mode=graph` | tylko struktura grafu (debug) |

Graf (Neo4j Browser: http://localhost:7474):

```powershell
Invoke-RestMethod "http://localhost:8080/api/graph/entities?q=alpha&type=Project"
Invoke-RestMethod "http://localhost:8080/api/graph/entities?q=edge&type=Concept"
Invoke-RestMethod "http://localhost:8080/api/graph/entities/project:alpha/neighbors"
```

### Graf pod materiały studyjne

Graf obsługuje **dwa światy** naraz:

| Typ | Przykład | Relacje |
|-----|----------|---------|
| Firmowy | Person, Project | `WORKS_ON`, `DEPENDS_ON`, `ESCALATES` |
| Studyjny | Concept, Topic | `RELATES_TO`, `COMPARES_WITH`, `PART_OF`, `DEFINES` |

Po wrzuceniu PDF-ów wykładów przebuduj graf (ekstrakcja LLM per batch chunków):

```powershell
.\scripts\rebuild-graph-all.ps1
# lub pojedynczy dokument:
Invoke-RestMethod -Method Post "http://localhost:8080/api/graph/rebuild/<documentId>"
```

PDF (po indeksie):

```powershell
.\scripts\ingest.ps1 -Path "data/documents/ORKUZ_Cheat_Sheet.pdf"
```

Tylko vector search (debug): `POST /api/ask?mode=vector`

Lista dokumentów: `GET http://localhost:8080/api/documents`

## Konfiguracja Ollama

| Zmienna | Domyślnie | Opis |
|---------|-----------|------|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Adres API Ollama |
| `OLLAMA_CHAT_MODEL` | `llama3.2` | Model do odpowiedzi |
| `OLLAMA_EMBEDDING_MODEL` | `nomic-embed-text` | Model embeddingów (768 wymiarów) |

Wzór: `.env.example`

**Uwaga:** Po przejściu z OpenAI uruchom aplikację ponownie — migracja `V002` czyści stare chunki i zmienia wymiar wektora na 768.

## Etapy (dokumentacja)

| Etap | Plik |
|------|------|
| 0 | [Hello RAG](docs/etapy/etap-00-hello-rag.md) |
| 1 | [Multi-doc RAG](docs/etapy/etap-01-multi-doc-rag.md) |
| … | [pełny plan](docs/etapy/README.md) |
