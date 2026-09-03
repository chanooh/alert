---
name: alert-notifier
description: Integrate application event monitoring with the Alert server and Android alert app, mapping validated events to info, warning, urgent, or critical notifications. Use when adding or reviewing code that must notify the owner about runtime events; do not use for unrelated messaging.
---

# Alert Notifier

Use this skill when another AI needs to add event monitoring to an application and deliver matching notifications to this repository's Alert Android app. The integration must call the Alert HTTP API; it must not publish unsigned MQTT messages directly.

## Runtime contract

The server endpoint is:

```text
POST ${ALERT_BASE_URL}/api/alerts
Content-Type: application/json
x-api-key: ${ALERT_ADMIN_API_KEY}
```

JSON body:

```json
{
  "level": "info|warning|urgent|critical",
  "title": "短标题",
  "message": "给人的可操作说明",
  "deviceId": "可选；省略时使用服务器默认设备"
}
```

The server validates and HMAC-signs the event, publishes it to the configured
MQTT broker, and returns HTTP `202` with `{ "id": "...", "status": "pending" }`
or `{ "id": "...", "status": "queued" }`. Both are accepted delivery results:
`pending` means the server is tracking the event, while `queued` means the first
MQTT publish failed and the server will retry. The phone performs its own device
and signature checks before displaying the alert.

Never put a real API key, HMAC secret, device token, server address, or event
payload in source control, generated examples, logs, screenshots, or prompts.
Read the endpoint and key from deployment configuration such as
`ALERT_BASE_URL` and `ALERT_ADMIN_API_KEY`. If either is missing, report the
configuration error clearly; do not invent a value or silently switch to an
unsigned transport.

## Event-to-alert policy

Add the notifier at the point where the application has already validated the
event. Keep the main event path usable if notification delivery fails unless the
user explicitly requires fail-closed behavior.

| Level | Use for | Android behavior |
| --- | --- | --- |
| `info` | Routine state changes, successful completion, low urgency | Low-attention notification; automatic ACK |
| `warning` | Actionable degradation or a condition likely to become a problem | Notification with vibration; automatic ACK |
| `urgent` | Time-sensitive action needed soon | Short alarm-stream sound/vibration; automatic ACK |
| `critical` | Immediate human intervention or safety-impacting failure | Full-screen alarm path; remains pending until manual ACK |

Choose the lowest level that still gives the user enough time to react. Do not
use `critical` merely to make a message more visible. The app's persistent
silent mode can suppress Urgent/Critical sound and vibration, so the message
must always contain the essential context in text.

## Monitoring and de-duplication

1. Identify the real event source (webhook, queue, file watcher, process
   supervisor, scheduled check, or domain callback) before editing code.
2. Normalize and validate the event, then derive a stable fingerprint from its
   source and provider event ID. If no provider ID exists, hash the normalized
   fields that define one occurrence.
3. Suppress the same fingerprint during a bounded deduplication window. Store
   only the minimum metadata needed for that window; do not store secrets.
4. Build a concise title and message. Include what happened, which component
   was affected, the observed value or timestamp when useful, and the next
   action. Redact credentials, cookies, bearer tokens, private keys, and full
   request headers.
5. Send one HTTP request through the Alert API. Treat only a `202` response as
   accepted. Handle `401/403` as configuration/authentication errors and
   `400/413` as payload/schema errors; do not retry those automatically.
6. For network errors, `408`, `429`, or `5xx`, use a small bounded retry with
   backoff only when the request outcome is known to be safe to repeat. If a
   timeout leaves acceptance uncertain, mark it as uncertain instead of blindly
   creating duplicate alerts; surface the failure through the host application's
   normal diagnostics.
7. Log only the redacted event fingerprint, HTTP status, and returned alert ID.
   Never log the API key or the complete event body.

The Alert API generates the event UUID server-side and does not provide an
idempotency-key field. Therefore, caller-side fingerprinting is required to
avoid duplicate notifications when a source retries the same event.

## Minimal TypeScript shape

Adapt this shape to the host project's HTTP client and lifecycle. Keep the
function behind the project's existing configuration and logging abstractions.

```ts
type AlertLevel = "info" | "warning" | "urgent" | "critical";

type AlertInput = {
  level: AlertLevel;
  title: string;
  message: string;
  deviceId?: string;
};

const alertBaseUrl = process.env.ALERT_BASE_URL;
const alertAdminApiKey = process.env.ALERT_ADMIN_API_KEY;

export async function sendAlert(input: AlertInput): Promise<{ id: string; status: string }> {
  if (!alertBaseUrl || !alertAdminApiKey) {
    throw new Error("Alert notifier is not configured: set ALERT_BASE_URL and ALERT_ADMIN_API_KEY");
  }

  const response = await fetch(new URL("/api/alerts", alertBaseUrl), {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": alertAdminApiKey,
    },
    body: JSON.stringify(input),
    signal: AbortSignal.timeout(10_000),
  });

  if (response.status !== 202) {
    throw new Error(`Alert API rejected event with HTTP ${response.status}`);
  }
  return (await response.json()) as { id: string; status: string };
}
```

The host integration should call `sendAlert` from its validated event handler,
after the fingerprint check, and should attach a bounded retry/dedup layer when
the event source is at-least-once. Do not block a critical business operation
forever waiting for the phone notification.

## Implementation checklist

- [ ] Monitoring source and lifecycle are identified; shutdown removes timers,
      subscriptions, and file descriptors.
- [ ] `info`/`warning`/`urgent`/`critical` mapping is explicit and justified.
- [ ] The API URL and admin key come only from runtime configuration.
- [ ] Stable event fingerprinting prevents duplicate sends.
- [ ] Titles and messages are bounded, useful, and secret-free.
- [ ] Only HTTP `202` is treated as accepted; failures are bounded and visible.
- [ ] Tests mock the HTTP client and cover schema, severity mapping, duplicate
      suppression, `202`, auth/payload errors, and redaction.
- [ ] Logs and error messages do not contain credentials or full sensitive
      payloads.
- [ ] The implementation sends through `/api/alerts`, never directly to MQTT.
