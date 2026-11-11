-- Hackathon-only verified responder fixtures. A production deployment replaces these
-- with an identity-checked onboarding workflow and never seeds real people.
INSERT INTO responders (id, display_name, verification, capabilities, available, position, updated_at)
VALUES
    (
        '7f0ec4ca-ece3-4d63-9e9d-96204f3dfd01',
        'Noor · First aid',
        'demo_verified',
        ARRAY['first_aid', 'aed'],
        TRUE,
        ST_SetSRID(ST_MakePoint(4.8936, 52.3729), 4326)::geography,
        NOW()
    ),
    (
        '62d6a45a-641f-4be8-9815-c6f012e26902',
        'Sam · Community responder',
        'demo_verified',
        ARRAY['first_aid'],
        TRUE,
        ST_SetSRID(ST_MakePoint(4.8952, 52.3702), 4326)::geography,
        NOW()
    )
ON CONFLICT (id) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    verification = EXCLUDED.verification,
    capabilities = EXCLUDED.capabilities,
    available = EXCLUDED.available,
    position = EXCLUDED.position,
    updated_at = EXCLUDED.updated_at;

