import type { SignedAlert } from "./security.js";

export type MiPushSendResult = {
  providerMessageId: string;
};

export type MiPushGateway = {
  send(alert: SignedAlert, registrationId: string, callbackParam: string): Promise<MiPushSendResult>;
};

type MiPushConfig = {
  appSecret: string;
  callbackUrl: string;
  packageName?: string;
};

const fallbackText = (value: string, limit: number): string =>
  value.length <= limit ? value : `${value.slice(0, Math.max(0, limit - 1))}…`;

const notifyType = (level: SignedAlert["level"]): number => {
  switch (level) {
    case "critical":
    case "urgent":
      return 3;
    case "warning":
      return 2;
    case "info":
      return 0;
  }
};

/** Official Mi Push v2 regid REST client. The server secret never reaches Android. */
export class HttpMiPushGateway implements MiPushGateway {
  private readonly packageName: string;

  constructor(private readonly config: MiPushConfig) {
    this.packageName = config.packageName || "dev.chanooh.alert";
  }

  async send(alert: SignedAlert, registrationId: string, callbackParam: string): Promise<MiPushSendResult> {
    const body = new URLSearchParams({
      registration_id: registrationId,
      restricted_package_name: this.packageName,
      title: fallbackText(alert.title, 50),
      description: fallbackText(alert.message, 128),
      // Notification-bar delivery is deliberate: pass-through is not reliable while
      // an app is stopped or frozen. Payload is an opaque event reference only.
      payload: JSON.stringify({ v: 1, id: alert.id }),
      pass_through: "0",
      notify_type: String(notifyType(alert.level)),
      time_to_live: String(24 * 60 * 60 * 1000),
      "extra.notify_effect": "1",
      "extra.callback": this.config.callbackUrl,
      "extra.callback.param": callbackParam,
      "extra.callback.type": "17",
      // Deterministic per event: provider retries cannot create a growing stack.
      notify_id: String(Math.abs(hashCode(alert.id))),
    });
    const response = await fetch("https://api.xmpush.xiaomi.com/v2/message/regid", {
      method: "POST",
      headers: {
        Authorization: `key=${this.config.appSecret}`,
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body,
      signal: AbortSignal.timeout(10_000),
    });
    const payload = await response.json().catch(() => null) as {
      code?: number;
      data?: { id?: string } | string;
      description?: string;
    } | null;
    const providerMessageId = typeof payload?.data === "object" ? payload.data?.id : undefined;
    if (!response.ok || payload?.code !== 0 || !providerMessageId) {
      throw new Error(`Mi Push rejected message: ${payload?.description || response.status}`);
    }
    return { providerMessageId };
  }
}

const hashCode = (value: string): number => {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) hash = ((hash << 5) - hash + value.charCodeAt(index)) | 0;
  return hash;
};
