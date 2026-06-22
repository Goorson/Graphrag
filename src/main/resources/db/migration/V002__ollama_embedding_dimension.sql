-- Ollama nomic-embed-text ma 768 wymiarów (nie 1536 jak OpenAI).
-- Jeśli indeksowałeś dokumenty przez OpenAI, chunki trzeba przebudować.

TRUNCATE chunks;

DROP INDEX IF EXISTS idx_chunks_embedding;
ALTER TABLE chunks ALTER COLUMN embedding TYPE vector(768);
CREATE INDEX idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);
