# Alert Guardian (KernelSU)

Optional reliability layer for the Android Alert app.

## What it does

- Runs as a KernelSU `service.sh` late-start module.
- Checks every **60 seconds** by default; WebUI can switch to 30 seconds or 5 minutes.
- Does nothing unless the Android app previously enabled its self-hosted MQTT transport and left the private guardian marker.
- If the MQTT foreground service is missing, explicitly requests Android to start that service again.
- With Alert **0.1.7+**, also checks a private MQTT-health heartbeat. A stale
  heartbeat triggers a safe foreground-service restart even when Android still
  lists the service as running.
- Applies a small set of best-effort AOSP background/Doze app-op allowances. Unsupported app-ops are ignored.
- Keeps only the latest ~200 guardian log lines in the module directory.
- Provides a KernelSU WebUI with local-only status, recent log lines, a manual
  restart control, and fixed safe check-interval choices.

The module does **not** modify SystemUI, hook framework code, patch Xiaomi databases, or continuously acquire a wake lock.

## Why explicit start is used

Modern Android intentionally keeps a package in `FLAG_STOPPED` after a user force-stop. The guardian is outside the app process and runs as root, so it can issue an explicit component start when the user has intentionally enabled the guardian path. This is the recovery mechanism; the Android app is not expected to self-resurrect from force-stop.

## Power model

The default 60-second guardian interval is aimed at notification reliability. It
does not maintain its own network connection and normally performs only a quick
service/heartbeat check before sleeping again. Use the WebUI to select 5 minutes
if battery life is more important, or 30 seconds for the fastest recovery.

The actual realtime transport remains MQTT in the Android foreground service with a 300-second MQTT keepalive. If you do not want the root fallback, simply do not install this module.

## Install

Package the contents of this directory as a KernelSU module ZIP so that
`module.prop`, `service.sh`, `action.sh`, `webroot/`, and `skip_mount` are at
the ZIP root, then install it from KernelSU Manager and reboot.

Enable **Self-hosted MQTT transport** inside the Alert app before expecting Guardian to restart it.

Use the KernelSU module **Action** button for a one-shot status check / restart attempt.

## WebUI

Open **WebUI** on the Alert Guardian module card in KernelSU Manager. It reports:

- whether Alert is installed and has enabled the Guardian marker;
- whether Android lists the MQTT foreground service as running;
- the age of the private MQTT health heartbeat (Alert 0.1.7+);
- Doze whitelist detection and recent Guardian log entries.

The only controls are an explicit MQTT transport restart and three fixed
check intervals (30, 60, or 300 seconds). The WebUI does not expose an arbitrary
root shell, network endpoint, device token, or alert contents.

Use a current KernelSU Manager build with module WebUI support; on older Manager
versions, the existing **Action** button remains available for a one-shot status
and recovery check.
