# Alert Guardian (KernelSU)

Root-owned, persistent MQTT transport for Alert 0.3+.

## What it does

- Starts an auditable, statically linked Go MQTT client from KernelSU late-start.
- The daemon owns the QoS 1 persistent session outside the Android app process,
  avoiding HyperOS application-freezer behavior.
- It reads only Alert's private Root configuration after the user selects
  **KernelSU 接管**. HMAC and HTTP device tokens never leave the app.
- Every MQTT payload is atomically placed in Alert's private inbox before Root
  explicitly starts a short-lived ingress foreground service.
- Alert remains responsible for HMAC verification, history, notification,
  alarms and ACKs. The daemon never logs alert payloads or ACKs alerts itself.

## Power and recovery model

At idle, this is one native MQTT socket with a 300-second keepalive. There is
no Android MQTT foreground service, heartbeat loop or periodic `dumpsys`.
Configuration changes use filesystem notifications. If an event cannot be
drained by Android, only the non-empty inbox is retried once a minute. The shell
supervisor uses exponential backoff only if the daemon exits unexpectedly.

The module applies best-effort Doze/AppOps allowances at boot. It does not hook
SystemUI, patch Xiaomi databases, modify framework code or hold a continuous
WakeLock.

## Install and switch

Install the CI-produced ZIP in KernelSU Manager and reboot. Then open Alert,
choose **KernelSU 接管（推荐）** in the transport section, and tap
**保存并应用**. This writes private MQTT configuration for Root and stops the
App MQTT foreground service.

Choose **App MQTT 备用** and save to disable Root configuration and return to
the original Android foreground transport for troubleshooting or when the module
is not installed.

## WebUI

WebUI reports whether Alert is installed, whether the daemon is alive, whether
Root configuration exists, safe transport state, pending inbox count, Doze
whitelist detection and recent logs. It has a single Root-daemon restart button.
It never displays MQTT credentials, API tokens, HMAC keys or alert content.

Root transport cannot overcome a powered-off device, a disconnected radio, or a
manually disabled/uninstalled module. Validate lock-screen behavior on the target
HyperOS device after every meaningful OS update.
