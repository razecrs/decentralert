# API contract

The development API is intentionally small and typed by `backend/src/models.rs` and the Kotlin models in `client/shared`.

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Readiness and explicit real-dispatch status. |
| `POST` | `/v1/devices/register` | Create a prototype device ID and bearer token. |
| `GET` | `/v1/vault` | Read the authenticated device's Emergency Vault. |
| `PUT` | `/v1/vault` | Validate and replace the authenticated device's Emergency Vault. |
| `POST` | `/v1/sos/sessions` | Persist an authenticated SOS pre-alert and vault snapshot. |
| `GET` | `/v1/sos/sessions/{id}` | Read an authenticated device's SOS session. |
| `POST` | `/v1/sos/sessions/{id}/heartbeat` | Refresh liveness and optional location or battery context. |
| `POST` | `/v1/sos/sessions/{id}/confirm` | Confirm a session and create one private simulated incident. |
| `POST` | `/v1/sos/sessions/{id}/cancel` | Cancel a session and remove its sensitive snapshot. |
| `GET` | `/v1/incidents` | Latest 200 incidents. |
| `POST` | `/v1/incidents` | Create and fan out an idempotent incident. |
| `GET` | `/v1/incidents/{id}` | Read one incident. |
| `POST` | `/v1/incidents/{id}/cancel` | Cancel an active incident. |
| `POST` | `/v1/incidents/{id}/close` | Operator-only closure. |
| `POST` | `/v1/incidents/{id}/responders/{responderId}/accept` | Accept a verified responder offer. |
| `GET` | `/v1/events` | Server-sent domain events. |
| `POST` | `/v1/public-warnings` | Operator-only simulated public warning. |

Development operator calls use `Authorization: Bearer demo-operator`. This token is deliberately unsuitable for deployment.

Vault and SOS session routes require the `X-Device-Id` header and an `Authorization: Bearer <device token>` header returned by device registration. The server stores only a SHA-256 digest of that token, and Android encrypts its local copy with an app-specific Android Keystore key. This prototype still requires token rotation, attestation, rate limits, and end-to-end encrypted server vault fields before it can handle real personal data.

`idempotencyKey` is mandatory. Retrying the same create request returns the existing incident with HTTP 200 rather than dispatching it again.

An `unknown` or `multi_service` incident fans out to police, fire, medical, and rescue adapters. A specific category fans out only to that service. Each adapter acknowledgement has its own status and reference.

Device-originated incidents are marked private. Unauthenticated incident list and detail responses retain operational status and Circle-notification counts but redact the profile, evidence, and sensor context.

