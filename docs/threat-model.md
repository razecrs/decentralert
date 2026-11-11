# Threat model

## Protected assets

- Precise live location and home addresses.
- Emergency health profile and contacts.
- Camera evidence and sensor context.
- Responder identity, skills, location, and availability.
- Authority credentials and dispatch/public-warning capabilities.

## Principal threats and controls

- **Stalking through responder offers:** disclose only approximate area until a verified responder accepts; audit exact-location access.
- **False or repeated incidents:** idempotency, per-device/user rate limits, operator review, device reputation, and an appeal process.
- **Unauthorized public warning:** operator authentication, least-privilege role, two-person confirmation in production, immutable audit event, and authority adapter allow-list.
- **Health-data disclosure:** field-level authorization, data minimization, encryption, purpose-bound access, and short retention.
- **Evidence tampering:** hash evidence on the device, retain capture/consent metadata, sign server receipts, and never treat images or AI labels as sole truth.
- **AI misclassification:** advisory-only labels with confidence and model version; always show original evidence; no face recognition or emotion inference; never block dispatch.
- **Adapter replay or duplication:** shared incident ID, adapter idempotency token, signed requests, acknowledgement state, and dead-letter review.
- **Backend outage:** native 112 handoff remains visible; durable client queue must never claim delivery until an authority acknowledgement exists.

This document is an engineering starting point, not a completed DPIA or security certification.

