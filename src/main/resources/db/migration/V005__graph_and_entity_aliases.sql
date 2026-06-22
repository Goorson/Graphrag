ALTER TABLE documents ADD COLUMN IF NOT EXISTS graph_status TEXT NOT NULL DEFAULT 'PENDING';

CREATE TABLE entity_aliases (
    id            UUID PRIMARY KEY,
    entity_type   TEXT NOT NULL,
    alias         TEXT NOT NULL,
    canonical_id  TEXT NOT NULL,
    UNIQUE (entity_type, alias)
);

INSERT INTO entity_aliases (id, entity_type, alias, canonical_id) VALUES
    (gen_random_uuid(), 'Person', 'jan kowalski', 'person:jan-kowalski'),
    (gen_random_uuid(), 'Person', 'j. kowalski', 'person:jan-kowalski'),
    (gen_random_uuid(), 'Person', 'anna nowak', 'person:anna-nowak'),
    (gen_random_uuid(), 'Person', 'maria zielinska', 'person:maria-zielinska'),
    (gen_random_uuid(), 'Person', 'katarzyna lewandowska', 'person:katarzyna-lewandowska'),
    (gen_random_uuid(), 'Project', 'alpha', 'project:alpha'),
    (gen_random_uuid(), 'Project', 'project alpha', 'project:alpha'),
    (gen_random_uuid(), 'Project', 'beta', 'project:beta'),
    (gen_random_uuid(), 'Project', 'project beta', 'project:beta'),
    (gen_random_uuid(), 'Project', 'payment gateway', 'project:beta'),
    (gen_random_uuid(), 'Concept', 'payment gateway', 'concept:payment-gateway'),
    (gen_random_uuid(), 'Concept', 'kyc', 'concept:kyc');
