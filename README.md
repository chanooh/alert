# Alert

Personal Android alert terminal with signed MQTT events, durable acknowledgement,
and a KernelSU-native transport designed for HyperOS devices that freeze normal
background applications.

## Alert 0.3 architecture

```text
event source -> Alert server -> MQTT broker (QoS 1)
                                 |
                 KernelSU Root MQTT daemon (persistent session)
                                 |
                 private signed-event inbox + explicit ingress start
                                 |
                Alert app: HMAC verify -> notification/alarm -> HTTP ACK
```

**KernelSU 接管** is the recommended mode on a rooted phone. Its native daemon
holds the MQTT socket outside the App process; Alert wakes only to process a
durably written event. The App validates every payload and retains the HMAC and
device API token, so the module cannot forge or acknowledge alerts.

**App MQTT 备用** retains the original Android foreground-service client. It is
the upgrade default so an existing installation keeps working before the new
KernelSU module is installed, and remains useful for troubleshooting.

There is no third-party push provider in 0.3. A powered-off device, disabled
module, forced radio disconnect, or total network outage can still prevent
immediate delivery.

## Alert levels

| Level | Phone behavior | ACK |
| --- | --- | --- |
| `info` | Low-attention notification | automatic |
| `warning` | Notification and vibration | automatic |
| `urgent` | Foreground alert service, alarm audio and repeating vibration for about 30 seconds | automatic |
| `critical` | Lock-screen/full-screen foreground alarm until user confirms | manual |

Incoming events are HMAC-SHA256 signed, time-limited and deduplicated. The
server retains pending events and retries MQTT for up to 24 hours. Automatic
ACKs are attempted immediately through the App's authenticated HTTP endpoint;
WorkManager retries failures. A Critical alert is only ACKed after the user
confirms it.

## Phone setup

Enter these installation-specific values in Alert's Settings tab:

- server URL;
- MQTT broker URL (`mqtt://` or `mqtts://`) and optional username/password;
- device ID, device API token and HMAC secret;
- alarm behavior and optional Root DND override.

The App stores the API token, HMAC key and MQTT password with Android Keystore
backed encryption. UI fields are masked. In Root mode the App writes only broker
credentials and device ID to its own private `root_transport/config.json`; the
HMAC key and API token are never written there or to `/data/adb`.

### Recommended Root installation

1. Install the signed `alert-release-apk` from a green GitHub Actions run.
2. Flash the matching `alert-guardian-kernelsu` ZIP in KernelSU Manager and reboot.
3. Open Alert, verify the existing settings, select **KernelSU 接管（推荐）**, then
   tap **保存并应用**.
4. In the Guardian WebUI, wait for `Root MQTT daemon: running` and transport
   status `subscribed` / `Root MQTT 已订阅`.
5. Lock the device, send an `urgent` test, then a `critical` test. Test Wi-Fi
   and mobile-data transitions separately.

Selecting **App MQTT 备用** and saving disables Root configuration and restores
the App foreground MQTT transport without uninstalling the module.

The App's status card and Guardian WebUI show only daemon state, timestamps and
inbox count; neither renders passwords, tokens, HMAC keys or alert content.

## Server configuration

Copy only the example file when preparing a deployment:

```bash
cd server
cp .env.example .env
```

The required server variables are `ADMIN_API_KEY`, `DEVICE_ID`,
`DEVICE_API_TOKEN`, `DEVICE_HMAC_SECRET` and `MQTT_URL`; optional MQTT username
and password enable broker authentication. Keep all real values outside Git.

Send an alert through the server API:

```bash
curl -X POST http://YOUR_SERVER:8787/api/alerts \
  -H 'Content-Type: application/json' \
  -H 'x-api-key: YOUR_ADMIN_KEY' \
  -d '{"level":"urgent","title":"Test","message":"Root MQTT delivery test"}'
```

For a short, private test deployment, `server/docker-compose.dev.yml` starts
the server and Mosquitto. It intentionally allows plaintext HTTP/MQTT. This is
only suitable for a controlled personal host and non-sensitive alerts: network
observers could read alert text and the HTTP device token. Use HTTPS, `mqtts://`,
authentication and firewall/VPN restrictions for a hardened deployment.

## Verification and artifacts

GitHub Actions builds and tests the server, Android App and Root daemon source.
The Guardian job runs Go tests, cross-compiles stripped Android binaries for
`arm64-v8a` and `armeabi-v7a`, then packages the KernelSU ZIP. A green workflow
uploads:

- `alert-release-apk` — CI-signed Release APK;
- `alert-guardian-kernelsu` — KernelSU module containing both Root daemon ABIs.

CI cannot prove a vendor's lock-screen policy. Real-device acceptance must cover
30-minute lock screen, long idle, network transitions, App freezer behavior,
Critical re-alarm after a process death, Root daemon restart, and a return to
App MQTT fallback.

## Security boundaries

- The server signs every accepted event; Root only relays raw signed JSON.
- Root transport deliberately has access to MQTT credentials because the device
  owner granted KernelSU root. It has no HMAC or device API token.
- Never commit endpoints, credentials, device IDs, alert data, `.env`, signing
  keys, runtime logs or build artifacts.
- A rooted device is inherently a trusted personal-device model. Root transport
  improves process survivability; it does not make a compromised phone safe.

## AI integration skill

[`skills/alert-notifier/SKILL.md`](skills/alert-notifier/SKILL.md) documents
how another coding agent can validate events and call `POST /api/alerts` with
runtime-provided credentials.
