# GraphRAG Knowledge Assistant

Plan projektu w Kotlin + Spring Boot: RAG na plikach MD/PDF, graf w Neo4j, GraphRAG, agent AI.

## Status implementacji

| Etap | Status |
|------|--------|
| 0 — Hello RAG | **Zaimplementowany** |
| 1 — Multi-doc RAG (MD + PDF, hybrid) | **Zaimplementowany** |
| 2 — Async + Redis | **Zaimplementowany** |
| 3–7 | Dokumentacja w `docs/etapy/` |

## Stack

Kotlin, Spring Boot 3, LangChain4j, **Ollama**, PostgreSQL + pgvector, Flyway.

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
docker compose -f docker/docker-compose.yml up -d postgres redis
```

### 3. Aplikacja

Wymagania: **Java 21+** (działa z JDK 26 + Gradle 9.4).

```powershell
.\gradlew.bat build
.\gradlew.bat bootRun
```

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
