# API contract

The development API is intentionally small and typed by `backend/src/models.rs` and the Kotlin models in `client/shared`.

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Readiness and explicit real-dispatch status. |
| `GET` | `/v1/incidents` | Latest 200 incidents. |
| `POST` | `/v1/incidents` | Create and fan out an idempotent incident. |
| `GET` | `/v1/incidents/{id}` | Read one incident. |
| `POST` | `/v1/incidents/{id}/cancel` | Cancel an active incident. |
| `POST` | `/v1/incidents/{id}/close` | Operator-only closure. |
| `POST` | `/v1/incidents/{id}/responders/{responderId}/accept` | Accept a verified responder offer. |
| `GET` | `/v1/events` | Server-sent domain events. |
| `POST` | `/v1/public-warnings` | Operator-only simulated public warning. |

Development operator calls use `Authorization: Bearer demo-operator`. This token is deliberately unsuitable for deployment.

`idempotencyKey` is mandatory. Retrying the same create request returns the existing incident with HTTP 200 rather than dispatching it again.

An `unknown` or `multi_service` incident fans out to police, fire, medical, and rescue adapters. A specific category fans out only to that service. Each adapter acknowledgement has its own status and reference.

