ALTER TABLE device_registrations ADD COLUMN IF NOT EXISTS username TEXT;

UPDATE device_registrations
SET username = 'user-' || LEFT(REPLACE(id::text, '-', ''), 8)
WHERE username IS NULL;

ALTER TABLE device_registrations ALTER COLUMN username SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS device_registrations_username_idx
    ON device_registrations (username);

CREATE TABLE IF NOT EXISTS network_invites (
    id UUID PRIMARY KEY,
    from_device_id UUID NOT NULL REFERENCES device_registrations(id) ON DELETE CASCADE,
    to_device_id UUID NOT NULL REFERENCES device_registrations(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT network_invite_not_self CHECK (from_device_id <> to_device_id)
);

CREATE INDEX IF NOT EXISTS network_invites_recipient_idx
    ON network_invites (to_device_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS in_app_alerts (
    id UUID PRIMARY KEY,
    recipient_device_id UUID NOT NULL REFERENCES device_registrations(id) ON DELETE CASCADE,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS in_app_alerts_recipient_idx
    ON in_app_alerts (recipient_device_id, created_at DESC);
