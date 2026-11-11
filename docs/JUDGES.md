# Judge guide

Thank you for reviewing Decentralert. The complete prototype can be explored in about two minutes.

> **Safety notice:** This is a simulation. It does not contact 112 or any live emergency service.

## 1. Install the Android prototype

1. Open the [latest GitHub Release](https://github.com/razecrs/decentralert/releases/latest) on an Android 8.0 or newer device.
2. Download `Decentralert-judge-preview-v0.1.0.apk`.
3. Allow installation from your browser or file manager if Android asks.
4. Install and open **Decentralert**.

The APK uses Android's standard debug signature. It is installable for evaluation but is not production-signed.

## 2. Try the SOS safety window

1. Press the large **SOS** button.
2. Observe the visible 30-second confirmation.
3. Use **Cancel** to safely return to the start screen, or use **Send now** to demonstrate immediate confirmation.

The current Android shell demonstrates the interaction and safety state machine. It does not submit a real emergency or contact the deployed console.

## 3. Open the operator demo

1. Visit [decentralert.razecrs.com](https://decentralert.razecrs.com).
2. Select **Create demo incident**.
3. Observe the incident queue, simulated police/fire/medical/rescue acknowledgements, nearby responder ranking, and consented medical essentials.

## What is real in this prototype

- Android Compose SOS interface running on a physical device
- Deterministic Kotlin countdown and cancellation logic
- Rust API with idempotent incident creation
- PostgreSQL and PostGIS persistence
- Parallel simulated service fan-out
- ETA-ranked responder offers
- Live browser operator console

## What remains simulated

- Emergency-service integrations
- 112 and NL-Alert connectivity
- Production identity and responder verification
- Encrypted camera evidence upload
- Authority-approved dispatch workflows

## Links

- [Source code](https://github.com/razecrs/decentralert)
- [Latest release](https://github.com/razecrs/decentralert/releases/latest)
- [Architecture](architecture.md)
- [Threat model](threat-model.md)
