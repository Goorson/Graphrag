ALTER TABLE documents ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'INDEXED';

ALTER TABLE chunks ADD COLUMN IF NOT EXISTS page INT;

ALTER TABLE chunks ADD COLUMN IF NOT EXISTS content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED;

CREATE INDEX IF NOT EXISTS idx_chunks_content_tsv ON chunks USING gin(content_tsv);

CREATE TABLE query_logs (
    id              UUID PRIMARY KEY,
    question        TEXT NOT NULL,
    answer_preview  TEXT,
    sources_json    JSONB,
    retrieval_mode  TEXT NOT NULL DEFAULT 'HYBRID',
    latency_ms      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_query_logs_created_at ON query_logs(created_at DESC);
