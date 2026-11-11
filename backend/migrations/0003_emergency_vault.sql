CREATE TABLE IF NOT EXISTS device_registrations (
    id UUID PRIMARY KEY,
    token_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS emergency_vaults (
    device_id UUID PRIMARY KEY REFERENCES device_registrations(id) ON DELETE CASCADE,
    payload JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS sos_sessions (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES device_registrations(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS sos_sessions_pending_idx ON sos_sessions (status, updated_at);
