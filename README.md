# Alert

Private Android alert terminal for high-priority personal events. The app is native Kotlin + Jetpack Compose + Material 3, with a self-hosted MQTT transport, signed events, durable ACK handling, and an optional KernelSU reliability layer.

## Current status

- Active implementation branch: `feature/initial-alert-app`.
- PR #1 (`Initial native Android alert app`) is merged into `main`.
- Follow-up reliability, transport, testing, and device-acceptance work is reviewed from `feature/initial-alert-app` in PR #2.
- GitHub Actions is the build/test source of truth. Reviewers do not need a local Android or Node build to verify the branch.
- Xiaomi Push and FCM are **not** implemented yet; they remain future redundant transports and require their official credentials/dependencies.

## End-to-end flow

```text
Your event source
    |
    | POST /api/alerts + admin API key
    v
Alert server
    |-- UUID event ID
    |-- HMAC-SHA256 signature
    |-- persistent pending/ACK state
    |-- retry while pending
    v
Self-hosted MQTT broker (QoS 1)
    |
    v
Android MQTT foreground service
    |-- verify device ID
    |-- reject stale events
    |-- verify HMAC signature
    |-- durable event-ID deduplication
    v
Alert dispatcher
    |-- info     -> notification -> automatic durable ACK
    |-- warning  -> notification + vibration -> automatic durable ACK
    |-- urgent   -> dedicated foreground alert service -> automatic durable ACK
    `-- critical -> full-screen alarm path -> explicit user ACK -> durable ACK upload
```

ACK uploads use WorkManager, so a temporary loss of network does not require the user to keep the alert screen open. The server keeps pending state and retries unacknowledged events. Active critical IDs are persisted so a server retry can re-arm an unacknowledged critical alert after the Android process has died and the transport is restored.

## Alert levels

| Level | Local behavior | ACK behavior |
| --- | --- | --- |
| `info` | Normal low-attention notification | Automatic |
| `warning` | Notification with vibration | Automatic |
| `urgent` | Dedicated `UrgentAlertService` foreground service, alarm-stream audio, raised alarm volume, repeating vibration, and automatic stop after about 30 seconds; no full-screen activity | Automatic |
| `critical` | Dedicated foreground alarm service, alarm-stream audio, configurable alarm volume, repeating vibration, wake lock, lock-screen visibility, screen-on request, and full-screen intent when Android permits it; continues until acknowledged | Manual, then durable WorkManager upload |

Full-screen presentation is subject to Android/HyperOS full-screen-intent policy and must be verified on the target device. A successful APK build does not prove OEM lock-screen behavior.

## Android configuration

Installation-specific values are entered in the Material 3 control center on the phone:

- Server base URL
- MQTT broker URL (`mqtt://` or `mqtts://`)
- MQTT username/password (optional)
- Device ID
- Device API token
- Device HMAC secret
- Critical alarm volume
- Restore-alarm-volume-after-ACK preference
- Optional Root DND override

Sensitive values such as the device API token, HMAC secret, and MQTT password are encrypted with an Android Keystore-backed AES-GCM key. UI fields containing identifiers/secrets are masked/redacted rather than rendered as plain persisted values.

The MQTT transport runs as a foreground service, uses QoS 1, and reconnects automatically. When MQTT is enabled, the app also records a private marker used by the optional KernelSU Guardian.

## Root / KernelSU reliability

`root/alert-guardian` is optional. It does not carry alert traffic itself. Its late-start `service.sh` checks every 300 seconds and, only when the app previously enabled MQTT, requests a restart of the MQTT foreground service if that service is missing. It also applies a small set of best-effort background/Doze allowances.

### Root DND override

The app also has an explicit **Root DND override** option for rooted private devices. When enabled for a critical alert and Android reports an active DND filter (`priority`, `alarms only`, or `total silence`), the app uses a root `cmd notification set_dnd` command to temporarily disable DND and attempts to restore the previous interruption-filter level when the critical alert is stopped/acknowledged.

This path is intentionally opt-in and has important limits:

- Normal notification-channel DND bypass is not treated as a hard guarantee for every Android/HyperOS DND policy.
- Root override restores the previous **interruption filter level**, not an exact snapshot of every OEM/automatic Zen rule.
- The restore mode currently lives in the alarm-service process. If that process/device is killed or crashes while DND is temporarily disabled, automatic restoration is not guaranteed.
- Root shell behavior can vary by HyperOS/Android build and therefore requires real-device acceptance testing.

See [`docs/hyperos-3-device-acceptance.md`](docs/hyperos-3-device-acceptance.md) before enabling this path on a daily-use phone.

## Server configuration

Copy only the example file when preparing a deployment:

```bash
cd server
cp .env.example .env
```

`server/.env.example` contains placeholders only. Real admin keys, device IDs, device API tokens, HMAC secrets, MQTT credentials, and Internet-facing endpoints must remain outside Git.

For local-only development, `docker-compose.dev.yml` includes an anonymous Mosquitto configuration. It is **development only** and must never be exposed directly to the public Internet.

Example local request:

```bash
curl -X POST http://127.0.0.1:8787/api/alerts \
  -H 'Content-Type: application/json' \
  -H 'x-api-key: YOUR_LOCAL_ADMIN_KEY' \
  -d '{"level":"critical","title":"Test","message":"Critical path test"}'
```

### Temporary development deployment (test only)

For a short, isolated test on a disposable host, the development Compose stack can
build and run both the alert server and Mosquitto without a local Node or Android
build:

```bash
git clone --branch feature/initial-alert-app https://github.com/chanooh/alert.git alert
cd alert/server
cp .env.example .env
# Fill .env with newly generated test-only values; never commit this file.
docker compose -f docker-compose.dev.yml up -d --build
curl http://YOUR_SERVER_IP:8787/health
```

Configure the app with `http://YOUR_SERVER_IP:8787` and
`mqtt://YOUR_SERVER_IP:1883`, using the same test device ID, API token, and HMAC
secret as the server `.env`. Stop the stack as soon as testing is complete:

```bash
docker compose -f docker-compose.dev.yml down
```

This stack intentionally uses anonymous, plaintext MQTT and HTTP. It is suitable
only for a brief, access-controlled lab test; do not expose it to an untrusted
network or use real credentials, personal events, or a daily-use deployment.
Production use requires authenticated MQTT over TLS (`mqtts://`), HTTPS, firewall
or VPN restrictions, rotated secrets, and a separately reviewed deployment
configuration.

The Debug APK includes a test-only cleartext-network manifest overlay so this
temporary HTTP endpoint can be exercised on a lab device. Do not carry that
setting into a production/release build.

## Automated verification and CI artifacts

`.github/workflows/ci.yml` runs on `main` and `feature/**` pushes and on pull requests. A branch is not considered build-verified until the workflow for that exact head commit is green.

The CI jobs verify:

- **Server:** dependency install, TypeScript typecheck, automated Node tests (HMAC/canonical signing, constant-time credential comparison, persistent alert store, retry, and ACK behavior), then production TypeScript build.
- **Android:** JVM unit tests for HMAC acceptance/rejection, device mismatch, bad signatures, and stale-event rejection, followed by `:app:assembleDebug` against Android API 36.
- **Guardian:** packages the KernelSU module ZIP from the repository sources.

Successful runs upload two review artifacts:

- `alert-release-apk` — Release APK signed with the repository's private CI keystore.
- `alert-guardian-kernelsu` — installable KernelSU Guardian ZIP.

### Release signing

The Android build reads `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` from the CI environment. The
keystore itself is supplied through the encrypted `ANDROID_KEYSTORE_BASE64`
repository secret and is never committed to Git. Keep a secure backup of the
keystore and its passwords: Android will reject future updates if the signing
key is lost or replaced.

The exact run URL, head SHA, artifact digests, and success state should be taken from the latest GitHub Actions run for the branch/PR rather than copied from an older run.

## Real-device acceptance

CI proves compilation and automated logic tests; it cannot prove HyperOS background policy, notification permission UI, lock-screen/full-screen behavior, actual speaker/vibrator behavior, KernelSU root commands, or Guardian recovery after task/process termination.

Use the dedicated checklist:

- [`docs/hyperos-3-device-acceptance.md`](docs/hyperos-3-device-acceptance.md)

Do not mark the project as HyperOS 3 device-verified until those steps have actually been executed on a device and the results recorded externally.

## Security / privacy

This repository is intended to remain safe to publish:

- Never commit real server/broker endpoints, API keys, bearer tokens, HMAC/signing secrets, MQTT passwords, device identifiers, or personal alert/event data.
- Never commit `.env`, `google-services.json`, signing keys, private certificates, production logs, or server runtime alert data.
- Do not add opaque or untrusted AAR/JAR binaries. Dependencies should come from declared, reviewable upstream packages or official vendor integrations.
- Device API tokens, HMAC secrets, and MQTT passwords live in Android Keystore-backed encrypted storage.
- Server secrets come only from environment variables.
- Incoming MQTT events are HMAC-signed; MQTT publish access alone is not enough to forge a valid accepted alert.
- Use TLS (`https://` and `mqtts://`) for Internet-facing deployments.

## Development / review

PR #1 established the initial app and has already been merged. The current follow-up history remains on `feature/initial-alert-app` and is proposed to `main` through PR #2. Do not merge PR #2 solely because CI is green: HyperOS 3 + KernelSU runtime behavior still requires the real-device acceptance checklist above.
