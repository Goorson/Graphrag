CREATE TABLE ingest_jobs (
    id              UUID PRIMARY KEY,
    document_id     UUID REFERENCES documents(id) ON DELETE SET NULL,
    type            TEXT NOT NULL,
    status          TEXT NOT NULL,
    payload_json    JSONB NOT NULL,
    attempts        INT NOT NULL DEFAULT 0,
    max_attempts    INT NOT NULL DEFAULT 3,
    error_message   TEXT,
    progress_pct    INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ
);

CREATE INDEX idx_ingest_jobs_status ON ingest_jobs(status);
