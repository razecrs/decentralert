# Judge guide

Thank you for reviewing Decentralert. The complete prototype can be explored in about two minutes.

> **Safety notice:** This is a simulation. It does not contact 112 or any live emergency service.

## 1. Install the Android prototype

1. Open the [latest GitHub Release](https://github.com/razecrs/decentralert/releases/latest) on an Android 8.0 or newer device.
2. Download the APK attached to the latest release.
3. Allow installation from your browser or file manager if Android asks.
4. Install and open **Decentralert**.

The APK uses Android's standard debug signature. It is installable for evaluation but is not production-signed.

## 2. Create a test Emergency Vault

1. Open **Profile** and enter fictional test data. Do not use real health information in this prototype.
2. Open **People**, add a fictional trusted contact, and mark it confirmed for the demo.
3. Select **Save and sync** on each screen.

The authenticated server copy lets the prototype preserve the context needed for an SOS handoff. Calls, SMS, push notifications, and contact invitations remain simulated.

## 3. Try the SOS safety window

1. Return to **SOS**, select an emergency type, and press the large **SOS** button.
2. Grant location permission and observe the visible 30-second confirmation.
3. Use **Cancel** to safely remove the pending handoff, or use **Send now** to confirm it immediately.

The Android prototype creates an authenticated pre-alert on the deployed simulation API and sends heartbeats during the confirmation window. A confirmed session appears in the operator console with its private health fields redacted from the public incident endpoint.

## 4. Open the operator demo

1. Visit [decentralert.razecrs.com](https://decentralert.razecrs.com).
2. Select **Create demo incident**.
3. Observe the incident queue, simulated police/fire/medical/rescue acknowledgements, nearby responder ranking, and consented medical essentials.

## What is real in this prototype

- Android Compose SOS interface running on a physical device
- Authenticated device registration and Emergency Vault synchronization
- Durable SOS pre-alerts, client heartbeats, and optional offline escalation
- Emergency Circle notification attempt records
- Deterministic Kotlin countdown and cancellation logic
- Rust API with idempotent incident creation
- PostgreSQL and PostGIS persistence
- Parallel simulated service fan-out
- ETA-ranked responder offers
- Live browser operator console

## What remains simulated

- Emergency-service integrations
- Calls, SMS, push notifications, and contact invitations
- 112 and NL-Alert connectivity
- Production identity and responder verification
- End-to-end encrypted server vault fields and production device attestation
- Encrypted camera evidence upload
- Authority-approved dispatch workflows

## Links

- [Source code](https://github.com/razecrs/decentralert)
- [Latest release](https://github.com/razecrs/decentralert/releases/latest)
- [Architecture](architecture.md)
- [Threat model](threat-model.md)
