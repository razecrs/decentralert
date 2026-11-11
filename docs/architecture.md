# Architecture

## Safety boundary

Decentralert coordinates information; it is not itself an emergency control room. Every deployment has a jurisdiction configuration that explicitly marks each adapter as `simulation`, `partner`, or `authority`. The UI displays that status. An unconfigured country falls back to the native emergency-call handoff and never reports a successful digital dispatch.

## Incident flow

1. The shared `SosCoordinator` starts a visible 30-second countdown.
2. Platform code obtains permissioned location, recent sensor context, and sequential front/rear snapshots only while the SOS screen is active.
3. Cancellation deletes pending media. `Send now` or timeout submits one idempotent incident.
4. The Rust API persists the incident before fan-out.
5. Every required configured service adapter receives the same incident ID in parallel.
6. PostGIS ranks available verified responders; only an approximate area is included in the initial offer.
7. Service acknowledgements and responder acceptance update the incident timeline and SSE stream.
8. Operators may close incidents. Only an authenticated operator can issue a public warning.

## Components

### Shared Kotlin

The `shared` module owns wire models, emergency profile types, consent boundaries, and the deterministic SOS state machine. Camera, location, notifications, evidence encryption, and native calling remain platform interfaces because their permission behavior differs on Android and iOS.

### Mobile

The Android shell demonstrates countdown and category flow. A production client must add CameraX/AVFoundation capture adapters, encrypted local staging, platform keystore integration, background-location justification, notification channels, and accessibility testing. The project is opt-in (`-PenableAndroid=true`) so web/shared builds remain usable without an Android SDK.

### Dashboard

The Kotlin/Wasm console polls the incident endpoint while the backend also exposes an SSE stream for a later authenticated live client. It presents service fan-out, responder ETA, medical essentials, and a persistent simulation warning.

### Rust API

Axum serves the HTTP API. Development mode uses an in-memory store seeded with verified responders. Setting `DATABASE_URL` enables PostgreSQL/PostGIS and runs migrations. The repository boundary permits a durable outbox/NATS implementation without changing API handlers.

## Production hardening still required

- OIDC/passkeys, responder verification workflow, device attestation, and scoped RBAC.
- KMS-backed envelope encryption and real object storage with short-lived upload grants.
- Transactional outbox, NATS JetStream, adapter-specific acknowledgement/retry policies, and multi-zone failover.
- Independent security assessment, DPIA, clinical-safety review, accessibility audit, and contracts with each authority.
- A legally approved retention schedule and audited emergency “break glass” access.
- Real AI must run in an isolated processing boundary and return advisory labels only; it cannot delay or suppress dispatch.

