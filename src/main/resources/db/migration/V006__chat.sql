CREATE TABLE chat_sessions (
    id          UUID PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE session_messages (
    id          UUID PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role        TEXT NOT NULL,
    content     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_session_messages_session_id ON session_messages(session_id, created_at);

CREATE TABLE agent_steps (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    message_id      UUID REFERENCES session_messages(id) ON DELETE SET NULL,
    step_index      INT NOT NULL,
    tool_name       TEXT,
    tool_input      JSONB,
    tool_output     TEXT,
    duration_ms     BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_steps_session_id ON agent_steps(session_id, step_index);
