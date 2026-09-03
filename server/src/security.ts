import { createHmac, timingSafeEqual } from "node:crypto";

export type SignedAlert = {
  id: string;
  deviceId: string;
  level: "info" | "warning" | "urgent" | "critical";
  title: string;
  message: string;
  createdAt: number;
  signature: string;
};

export function canonicalAlert(alert: Omit<SignedAlert, "signature">): string {
  return [
    alert.id,
    alert.deviceId,
    alert.level,
    String(alert.createdAt),
    alert.title,
    alert.message,
  ].join("\n");
}

export function signAlert(
  alert: Omit<SignedAlert, "signature">,
  secret: string,
): SignedAlert {
  const signature = createHmac("sha256", secret)
    .update(canonicalAlert(alert), "utf8")
    .digest("hex");
  return { ...alert, signature };
}

export function secureEqual(actual: string | undefined, expected: string): boolean {
  if (!actual) return false;
  const a = Buffer.from(actual);
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}
