CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS incidents (
    id UUID PRIMARY KEY,
    status TEXT NOT NULL,
    category TEXT NOT NULL,
    position geography(Point, 4326) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS incidents_position_gix ON incidents USING GIST (position);
CREATE INDEX IF NOT EXISTS incidents_status_idx ON incidents (status, created_at DESC);

CREATE TABLE IF NOT EXISTS responders (
    id UUID PRIMARY KEY,
    display_name TEXT NOT NULL,
    verification TEXT NOT NULL,
    capabilities TEXT[] NOT NULL DEFAULT '{}',
    available BOOLEAN NOT NULL DEFAULT FALSE,
    position geography(Point, 4326),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS responders_position_gix ON responders USING GIST (position);

CREATE TABLE IF NOT EXISTS public_warnings (
    id UUID PRIMARY KEY,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_events (
    sequence BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    incident_id UUID,
    event_type TEXT NOT NULL,
    actor TEXT NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS audit_incident_idx ON audit_events (incident_id, occurred_at);

