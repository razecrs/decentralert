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
- **Lost or compromised phone:** store device tokens in the platform keystore, support remote token revocation, require local authentication for vault edits, and never expose vault contents through public incident reads.
- **Emergency Circle abuse:** require verified invitations and explicit acceptance, rate-limit notification attempts, expose clear revocation, and record auditable consent. The prototype only simulates these steps.
- **Phone failure during SOS:** persist the pre-alert before the countdown, send authenticated heartbeats, and require explicit opt-in before server-side heartbeat-loss escalation.
- **Evidence tampering:** hash evidence on the device, retain capture/consent metadata, sign server receipts, and never treat images or AI labels as sole truth.
- **AI misclassification:** advisory-only labels with confidence and model version; always show original evidence; no face recognition or emotion inference; never block dispatch.
- **Adapter replay or duplication:** shared incident ID, adapter idempotency token, signed requests, acknowledgement state, and dead-letter review.
- **Backend outage:** native 112 handoff remains visible; durable client queue must never claim delivery until an authority acknowledgement exists.

This document is an engineering starting point, not a completed DPIA or security certification.

The Android prototype encrypts its local vault and bearer token with an app-specific Android Keystore key, but server vault records remain ordinary JSON. Judges should enter fictional test data only. Real health or contact data requires hardware-backed key policy, end-to-end field encryption, key rotation, deletion workflows, and an approved retention policy.

