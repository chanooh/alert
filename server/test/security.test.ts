import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import test from "node:test";
import {
  canonicalAcknowledgement,
  canonicalAlert,
  secureEqual,
  signAlert,
  verifyAcknowledgement,
} from "../src/security.js";

test("canonical alert and HMAC signature are stable", () => {
  const unsigned = {
    id: "evt-test-1",
    deviceId: "device-test",
    level: "critical" as const,
    createdAt: 1_700_000_000_000,
    title: "Critical test",
    message: "Line one\nLine two",
  };
  const expectedCanonical = [
    "evt-test-1",
    "device-test",
    "critical",
    "1700000000000",
    "Critical test",
    "Line one\nLine two",
  ].join("\n");

  assert.equal(canonicalAlert(unsigned), expectedCanonical);

  const expectedSignature = createHmac("sha256", "test-secret")
    .update(expectedCanonical, "utf8")
    .digest("hex");
  assert.equal(signAlert(unsigned, "test-secret").signature, expectedSignature);
});

test("secureEqual accepts exact token and rejects malformed tokens", () => {
  assert.equal(secureEqual("token-123", "token-123"), true);
  assert.equal(secureEqual("token-12x", "token-123"), false);
  assert.equal(secureEqual("short", "token-123"), false);
  assert.equal(secureEqual(undefined, "token-123"), false);
});

test("MQTT acknowledgement signatures are stable and reject tampering", () => {
  const acknowledgement = {
    id: "evt-ack-1",
    deviceId: "device-test",
    acknowledgedAt: 1_700_000_000_000,
  };
  const canonical = ["evt-ack-1", "device-test", "1700000000000"].join("\n");
  const signature = createHmac("sha256", "test-secret").update(canonical, "utf8").digest("hex");

  assert.equal(canonicalAcknowledgement(acknowledgement), canonical);
  assert.equal(verifyAcknowledgement({ ...acknowledgement, signature }, "test-secret"), true);
  assert.equal(
    verifyAcknowledgement({ ...acknowledgement, deviceId: "other-device", signature }, "test-secret"),
    false,
  );
});
