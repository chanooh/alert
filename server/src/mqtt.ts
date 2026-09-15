import mqtt, { type MqttClient } from "mqtt";
import type { SignedAlert } from "./security.js";

type AcknowledgementHandler = (topic: string, payload: Buffer) => void | Promise<void>;

export class AlertMqttPublisher {
  private client: MqttClient | null = null;
  private acknowledgementHandler: AcknowledgementHandler | null = null;

  constructor(
    private readonly url: string,
    private readonly username?: string,
    private readonly password?: string,
  ) {}

  async connect(): Promise<void> {
    if (this.client?.connected) return;
    this.client = mqtt.connect(this.url, {
      username: this.username || undefined,
      password: this.password || undefined,
      clean: true,
      reconnectPeriod: 5_000,
      connectTimeout: 10_000,
    });
    this.client.on("message", (topic, payload) => {
      void this.acknowledgementHandler?.(topic, payload);
    });

    await new Promise<void>((resolve, reject) => {
      const client = this.client!;
      const onConnect = () => {
        cleanup();
        resolve();
      };
      const onError = (error: Error) => {
        cleanup();
        reject(error);
      };
      const cleanup = () => {
        client.off("connect", onConnect);
        client.off("error", onError);
      };
      client.once("connect", onConnect);
      client.once("error", onError);
    });
  }

  async subscribeAcknowledgements(handler: AcknowledgementHandler): Promise<void> {
    if (!this.client) await this.connect();
    this.acknowledgementHandler = handler;
    await new Promise<void>((resolve, reject) => {
      this.client!.subscribe("alert/+/ack", { qos: 1 }, (error) => {
        if (error) reject(error);
        else resolve();
      });
    });
  }

  isConnected(): boolean {
    return this.client?.connected === true;
  }

  async publish(alert: SignedAlert): Promise<void> {
    if (!this.client) await this.connect();
    const topic = `alert/${alert.deviceId}/events`;
    await new Promise<void>((resolve, reject) => {
      this.client!.publish(
        topic,
        JSON.stringify(alert),
        { qos: 1, retain: false },
        (error) => (error ? reject(error) : resolve()),
      );
    });
  }
}
