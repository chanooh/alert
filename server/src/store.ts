import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import type { SignedAlert } from "./security.js";

export type AlertRecord = SignedAlert & {
  status: "pending" | "acked";
  attempts: number;
  lastSentAt: number | null;
  ackedAt: number | null;
};

export class AlertStore {
  private records = new Map<string, AlertRecord>();
  private writeChain: Promise<void> = Promise.resolve();

  constructor(private readonly filePath: string) {}

  async init(): Promise<void> {
    await mkdir(dirname(this.filePath), { recursive: true });
    try {
      const raw = await readFile(this.filePath, "utf8");
      const parsed = JSON.parse(raw) as AlertRecord[];
      for (const record of parsed) this.records.set(record.id, record);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
  }

  list(): AlertRecord[] {
    return [...this.records.values()].sort((a, b) => b.createdAt - a.createdAt);
  }

  get(id: string): AlertRecord | undefined {
    return this.records.get(id);
  }

  async add(alert: SignedAlert): Promise<AlertRecord> {
    const record: AlertRecord = {
      ...alert,
      status: "pending",
      attempts: 0,
      lastSentAt: null,
      ackedAt: null,
    };
    this.records.set(record.id, record);
    await this.persist();
    return record;
  }

  async markSent(id: string, sentAt: number): Promise<void> {
    const record = this.records.get(id);
    if (!record || record.status === "acked") return;
    record.attempts += 1;
    record.lastSentAt = sentAt;
    await this.persist();
  }

  async acknowledge(id: string, ackedAt: number): Promise<AlertRecord | null> {
    const record = this.records.get(id);
    if (!record) return null;
    record.status = "acked";
    record.ackedAt = ackedAt;
    await this.persist();
    return record;
  }

  pendingForRetry(now: number): AlertRecord[] {
    const retryAfter = [0, 30_000, 60_000, 120_000, 300_000];
    return [...this.records.values()].filter((record) => {
      if (record.status !== "pending") return false;
      if (now - record.createdAt > 15 * 60_000) return false;
      const delay = retryAfter[Math.min(record.attempts, retryAfter.length - 1)];
      return record.lastSentAt === null || now - record.lastSentAt >= delay;
    });
  }

  private persist(): Promise<void> {
    const snapshot = JSON.stringify(this.list(), null, 2);
    const tempPath = `${this.filePath}.tmp`;
    this.writeChain = this.writeChain.then(async () => {
      await writeFile(tempPath, snapshot, { encoding: "utf8", mode: 0o600 });
      await rename(tempPath, this.filePath);
    });
    return this.writeChain;
  }
}
