import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { signAlert } from "../src/security.js";
import { AlertStore } from "../src/store.js";

test("store persists pending state, retry metadata, and ACK", async () => {
  const dir = await mkdtemp(join(tmpdir(), "alert-store-"));
  const file = join(dir, "alerts.json");
  try {
    const createdAt = 1_700_000_000_000;
    const alert = signAlert(
      {
        id: "evt-store-1",
        deviceId: "device-test",
        level: "critical",
        title: "Test",
        message: "Persist me",
        createdAt,
      },
      "test-secret",
    );

    const store = new AlertStore(file);
    await store.init();
    await store.add(alert);

    assert.equal(store.get(alert.id)?.status, "pending");
    assert.deepEqual(store.pendingForRetry(createdAt), [store.get(alert.id)]);

    await store.markSent(alert.id, createdAt + 1_000);
    assert.equal(store.get(alert.id)?.attempts, 1);
    assert.equal(store.pendingForRetry(createdAt + 20_000).length, 0);
    assert.equal(store.pendingForRetry(createdAt + 31_000).length, 1);

    await store.acknowledge(alert.id, createdAt + 32_000);
    assert.equal(store.get(alert.id)?.status, "acked");
    assert.equal(store.pendingForRetry(createdAt + 60_000).length, 0);

    const reloaded = new AlertStore(file);
    await reloaded.init();
    assert.equal(reloaded.get(alert.id)?.status, "acked");
    assert.equal(reloaded.get(alert.id)?.attempts, 1);

    const persisted = JSON.parse(await readFile(file, "utf8"));
    assert.equal(persisted[0].id, alert.id);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
