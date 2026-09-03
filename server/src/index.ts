import { randomUUID } from "node:crypto";
import express from "express";
import { AlertMqttPublisher } from "./mqtt.js";
import { secureEqual, signAlert, type SignedAlert } from "./security.js";
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

const store = new AlertStore(new URL("../data/alerts.json", import.meta.url).pathname);
await store.init();

const publisher = new AlertMqttPublisher(
  mqttUrl,
  process.env.MQTT_USERNAME,
  process.env.MQTT_PASSWORD,
);
await publisher.connect();

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "32kb" }));

const levels = new Set(["info", "warning", "urgent", "critical"] as const);

app.get("/health", (_req, res) => {
  res.json({
    ok: true,
    mqtt: publisher.isConnected(),
    pendingAlerts: store.list().filter((item) => item.status === "pending").length,
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

app.listen(port, "0.0.0.0", () => {
  console.log(`alert server listening on :${port}`);
});
