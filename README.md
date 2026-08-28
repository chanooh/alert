# Alert

Private Android alert terminal for high-priority personal events.

## Goals

- Native Kotlin + Jetpack Compose + Material 3 UI.
- Four alert levels: `info`, `warning`, `urgent`, `critical`.
- `critical` behaves like an alarm: full-screen when permitted, screen wake, alarm audio, repeating vibration, and explicit acknowledgement.
- Server endpoint, MQTT broker, device ID, and credentials are configured at runtime inside the app. No real server address, API key, token, or personal event data is committed.
- Self-hosted MQTT is the first transport. Xiaomi Push and FCM are intended as independent redundant transports later.
- Root / KernelSU is an optional reliability layer, not a requirement for the base app.

## Current end-to-end flow

```text
Your event source
    |
    | POST /api/alerts + admin API key
    v
Alert server
    |-- UUID event ID
    |-- HMAC-SHA256 signature
    |-- persistent pending/ACK state
    |-- retry until acknowledged
    v
MQTT broker (QoS 1)
    |
    v
Android transport service
    |-- verify device ID
    |-- verify event age
    |-- verify HMAC signature
    |-- durable event-ID deduplication
    v
Alert dispatcher
    |-- info/warning/urgent -> notification + automatic ACK
    `-- critical -> full-screen alarm -> manual ACK -> durable ACK upload
```

Critical ACK is delivered using WorkManager, so a temporary loss of network does not require the user to keep the alert screen open.

## Android configuration

All installation-specific values are entered in the Material 3 control center on the phone:

- Server base URL
- MQTT broker URL (`mqtt://` or `mqtts://`)
- MQTT username/password (optional)
- Device ID
- Device API token
- Device HMAC secret
- Critical alarm volume
- Restore-volume-after-ACK preference

Sensitive values are encrypted using an Android Keystore-backed AES-GCM key. MQTT transport can be enabled or disabled from the app. If enabled, the app attempts to restore the transport after device boot.

## Server quick start

```bash
cd server
cp .env.example .env
# Fill .env locally. Never commit it.
npm install
npm run typecheck
npm run dev
```

For local-only testing, `docker-compose.dev.yml` starts an anonymous Mosquitto broker and the alert server. The included anonymous broker configuration is **development only** and must never be exposed to the public Internet.

Example event request:

```bash
curl -X POST http://127.0.0.1:8787/api/alerts \
  -H 'Content-Type: application/json' \
  -H 'x-api-key: YOUR_LOCAL_ADMIN_KEY' \
  -d '{"level":"critical","title":"Test","message":"Critical path test"}'
```

## Security / privacy

This repository is designed to remain safe to publish:

- Never commit real endpoints or credentials.
- Never commit `.env`, `google-services.json`, signing keys, private certificates, production logs, device identifiers, or server alert data.
- Device API tokens, HMAC secrets, and MQTT passwords live in Android Keystore-backed encrypted storage.
- Server secrets come only from environment variables.
- Incoming MQTT events are HMAC-signed; possession of broker publish access alone is not enough to forge a valid alert.
- Use TLS (`https://` and `mqtts://`) for any Internet-facing deployment.

## Development

Active work happens on `feature/initial-alert-app` and is reviewed through pull request #1.
