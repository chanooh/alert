import { randomUUID } from "node:crypto";
import express from "express";
import { DeviceStore } from "./devices.js";
import { HttpMiPushGateway, type MiPushGateway } from "./mipush.js";
import { AlertMqttPublisher } from "./mqtt.js";
import {
  secureEqual,
  signAlert,
  type SignedAcknowledgement,
  type SignedAlert,
  verifyAcknowledgement,
} from "./security.js";
import { AlertStore } from "./store.js";

const required = (name: string): string => {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
};

const port = Number(process.env.PORT || 8787);
const adminApiKey = required("ADMIN_API_KEY");
const defaultDeviceId = required("DEVICE_ID");
const deviceApiToken = required("DEVICE_API_TOKEN");
const hmacSecret = required("DEVICE_HMAC_SECRET");
const mqttUrl = required("MQTT_URL");
const miPushAppSecret = process.env.MIPUSH_APP_SECRET?.trim();
const miPushCallbackUrl = process.env.MIPUSH_CALLBACK_URL?.trim();
const miPushCallbackToken = process.env.MIPUSH_CALLBACK_TOKEN?.trim();

const store = new AlertStore(new URL("../data/alerts.json", import.meta.url).pathname);
await store.init();
const devices = new DeviceStore(new URL("../data/devices.json", import.meta.url).pathname);
await devices.init();
const miPush: MiPushGateway | null = miPushAppSecret && miPushCallbackUrl && miPushCallbackToken
  ? new HttpMiPushGateway({ appSecret: miPushAppSecret, callbackUrl: miPushCallbackUrl })
  : null;

const publisher = new AlertMqttPublisher(
  mqttUrl,
  process.env.MQTT_USERNAME,
  process.env.MQTT_PASSWORD,
);
await publisher.connect();
await publisher.subscribeAcknowledgements(async (topic, payload) => {
  let acknowledgement: SignedAcknowledgement;
  try {
    acknowledgement = JSON.parse(payload.toString("utf8")) as SignedAcknowledgement;
  } catch {
    return;
  }

  if (topic !== `alert/${acknowledgement.deviceId}/ack`) return;
  if (!verifyAcknowledgement(acknowledgement, hmacSecret)) return;

  const record = store.get(acknowledgement.id);
  if (!record || record.deviceId !== acknowledgement.deviceId) return;
  await store.acknowledge(record.id, Date.now());
});

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "32kb" }));

const levels = new Set(["info", "warning", "urgent", "critical"] as const);

app.get("/health", (_req, res) => {
  res.json({
    ok: true,
    mqtt: publisher.isConnected(),
    pendingAlerts: store.list().filter((item) => item.status === "pending").length,
    miPushConfigured: miPush !== null,
  });
});

app.post("/api/alerts", async (req, res) => {
  if (!secureEqual(req.header("x-api-key"), adminApiKey)) {
    res.status(401).json({ error: "unauthorized" });
    return;
  }

  const level = String(req.body?.level || "").toLowerCase();
  const title = String(req.body?.title || "").trim();
  const message = String(req.body?.message || "").trim();
  const deviceId = String(req.body?.deviceId || defaultDeviceId).trim();

  if (!levels.has(level as SignedAlert["level"]) || !title || !message || !deviceId) {
    res.status(400).json({ error: "level, title, message and deviceId are required" });
    return;
  }
  if (title.length > 160 || message.length > 4000 || deviceId.length > 128) {
    res.status(400).json({ error: "payload too large" });
    return;
  }

  const unsigned = {
    id: randomUUID(),
    deviceId,
    level: level as SignedAlert["level"],
    title,
    message,
    createdAt: Date.now(),
  };
  const alert = signAlert(unsigned, hmacSecret);
  await store.add(alert);

  try {
    await publisher.publish(alert);
    await store.markSent(alert.id, Date.now());
    res.status(202).json({ id: alert.id, status: "pending" });
  } catch (error) {
    console.error("initial MQTT publish failed", error);
    res.status(202).json({ id: alert.id, status: "queued" });
  }
});

app.post("/api/alerts/:id/ack", async (req, res) => {
  const authorization = req.header("authorization") || "";
  const token = authorization.startsWith("Bearer ") ? authorization.slice(7) : undefined;
  if (!secureEqual(token, deviceApiToken)) {
    res.status(401).json({ error: "unauthorized" });
    return;
  }

  const record = store.get(req.params.id);
  if (!record) {
    res.status(404).json({ error: "alert not found" });
    return;
  }
  if (String(req.body?.deviceId || "") !== record.deviceId) {
    res.status(403).json({ error: "device mismatch" });
    return;
  }

  const acknowledged = await store.acknowledge(record.id, Date.now());
  res.json({ id: acknowledged!.id, status: acknowledged!.status, ackedAt: acknowledged!.ackedAt });
});

/**
 * The Mi Push client reports its current RegID after every successful SDK
 * registration. The existing device bearer token prevents arbitrary devices
 * from replacing the fallback destination.
 */
app.post("/api/device/mipush-registration", async (req, res) => {
  if (!secureEqual(bearer(req), deviceApiToken)) {
    res.status(401).json({ error: "unauthorized" });
    return;
  }
  const deviceId = String(req.body?.deviceId || "").trim();
  const registrationId = String(req.body?.registrationId || "").trim();
  if (deviceId !== defaultDeviceId || !/^[A-Za-z0-9._:-]{8,512}$/.test(registrationId)) {
    res.status(400).json({ error: "invalid deviceId or registrationId" });
    return;
  }
  await devices.registerMiPush(deviceId, registrationId, Date.now());
  res.status(204).end();
});

/** Fetching by ID lets a compact vendor payload remain below the 4KB Mi Push limit. */
app.get("/api/device/alerts/:id", (req, res) => {
  if (!secureEqual(bearer(req), deviceApiToken)) {
    res.status(401).json({ error: "unauthorized" });
    return;
  }
  const record = store.get(req.params.id);
  if (!record || req.header("x-device-id") !== record.deviceId) {
    res.status(404).json({ error: "not found" });
    return;
  }
  res.json({
    id: record.id,
    deviceId: record.deviceId,
    level: record.level,
    title: record.title,
    message: record.message,
    createdAt: record.createdAt,
    signature: record.signature,
  });
});

/** Mi Push has no caller authentication for delivery callbacks; a high-entropy URL token scopes it. */
app.post("/api/mipush/receipts/:token", express.urlencoded({ extended: false }), async (req, res) => {
  if (!miPushCallbackToken || !secureEqual(req.params.token, miPushCallbackToken)) {
    res.status(404).end();
    return;
  }
  const encoded = typeof req.body?.data === "string" ? req.body.data : "";
  let receipts: Record<string, { param?: string; type?: number; targets?: string; timestamp?: number }>;
  try {
    receipts = JSON.parse(encoded) as typeof receipts;
  } catch {
    res.status(400).end();
    return;
  }
  for (const receipt of Object.values(receipts)) {
    const eventId = receipt.param;
    if (!eventId || !store.get(eventId)) continue;
    if (receipt.type === 1) await store.markMiPushDeliveredByProvider(eventId, receipt.timestamp || Date.now());
    if (receipt.type === 16) {
      const record = store.get(eventId)!;
      await devices.clearMiPush(record.deviceId);
      await store.markMiPushUnavailable(eventId);
    }
  }
  res.status(204).end();
});

setInterval(async () => {
  const now = Date.now();
  for (const record of store.pendingForRetry(now)) {
    try {
      await publisher.publish(record);
      await store.markSent(record.id, Date.now());
    } catch (error) {
      console.error(`retry publish failed for ${record.id}`, error);
    }
  }
}, 10_000).unref();

setInterval(async () => {
  const now = Date.now();
  for (const record of store.pendingForMiPush(now)) {
    const registrationId = devices.get(record.deviceId)?.miPushRegistrationId;
    if (!miPush || !registrationId) {
      await store.markMiPushUnavailable(record.id);
      continue;
    }
    await store.markMiPushSending(record.id, now);
    try {
      const result = await miPush.send(record, registrationId, record.id);
      await store.markMiPushSent(record.id, result.providerMessageId);
    } catch (error) {
      console.error(`Mi Push fallback failed for ${record.id}`, error);
      await store.markMiPushFailed(record.id, error instanceof Error ? error.message : "unknown provider error");
    }
  }
}, 1_000).unref();

function bearer(req: express.Request): string | undefined {
  const authorization = req.header("authorization") || "";
  return authorization.startsWith("Bearer ") ? authorization.slice(7) : undefined;
}

app.listen(port, "0.0.0.0", () => {
  console.log(`alert server listening on :${port}`);
});
