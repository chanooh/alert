import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

export type DeviceRegistration = {
  deviceId: string;
  miPushRegistrationId: string | null;
  miPushRegisteredAt: number | null;
};

export class DeviceStore {
  private records = new Map<string, DeviceRegistration>();
  private writeChain: Promise<void> = Promise.resolve();

  constructor(private readonly filePath: string) {}

  async init(): Promise<void> {
    await mkdir(dirname(this.filePath), { recursive: true });
    try {
      const parsed = JSON.parse(await readFile(this.filePath, "utf8")) as DeviceRegistration[];
      for (const record of parsed) this.records.set(record.deviceId, record);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
  }

  get(deviceId: string): DeviceRegistration | undefined {
    return this.records.get(deviceId);
  }

  async registerMiPush(deviceId: string, registrationId: string, registeredAt: number): Promise<void> {
    this.records.set(deviceId, { deviceId, miPushRegistrationId: registrationId, miPushRegisteredAt: registeredAt });
    await this.persist();
  }

  async clearMiPush(deviceId: string): Promise<void> {
    const existing = this.records.get(deviceId);
    if (!existing) return;
    this.records.set(deviceId, { ...existing, miPushRegistrationId: null, miPushRegisteredAt: null });
    await this.persist();
  }

  private persist(): Promise<void> {
    const snapshot = JSON.stringify([...this.records.values()], null, 2);
    const tempPath = `${this.filePath}.tmp`;
    this.writeChain = this.writeChain.then(async () => {
      await writeFile(tempPath, snapshot, { encoding: "utf8", mode: 0o600 });
      await rename(tempPath, this.filePath);
    });
    return this.writeChain;
  }
}
