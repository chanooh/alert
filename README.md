# Alert

Private Android alert terminal for high-priority personal events.

## Goals

- Native Kotlin + Jetpack Compose + Material 3 UI.
- Four alert levels: `info`, `warning`, `urgent`, `critical`.
- `critical` behaves like an alarm: full-screen when permitted, screen wake, alarm audio, repeating vibration, and explicit acknowledgement.
- Server endpoint, device ID, and credentials are configured at runtime inside the app. No real server address, API key, token, or personal event data is committed.
- Multiple transports are planned: self-hosted transport first, with Xiaomi Push / FCM as optional redundancy.
- Root / KernelSU is an optional reliability layer, not a requirement for the base app.

## Security / privacy

This repository is designed to remain safe to publish:

- Never commit real endpoints or credentials.
- Never commit `google-services.json`, signing keys, private certificates, production logs, or device identifiers.
- Keep secrets in local app storage / Android Keystore and server-side secret storage.
- Use `.example` files for configuration samples.

## Development

Initial work happens on `feature/initial-alert-app`.
