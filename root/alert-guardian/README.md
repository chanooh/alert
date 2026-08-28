# Alert Guardian (KernelSU)

Optional reliability layer for the Android Alert app.

## What it does

- Runs as a KernelSU `service.sh` late-start module.
- Checks every **300 seconds** by default.
- Does nothing unless the Android app previously enabled its self-hosted MQTT transport and left the private guardian marker.
- If the MQTT foreground service is missing, explicitly requests Android to start that service again.
- Applies a small set of best-effort AOSP background/Doze app-op allowances. Unsupported app-ops are ignored.
- Keeps only the latest ~200 guardian log lines in the module directory.

The module does **not** modify SystemUI, hook framework code, patch Xiaomi databases, or continuously acquire a wake lock.

## Why explicit start is used

Modern Android intentionally keeps a package in `FLAG_STOPPED` after a user force-stop. The guardian is outside the app process and runs as root, so it can issue an explicit component start when the user has intentionally enabled the guardian path. This is the recovery mechanism; the Android app is not expected to self-resurrect from force-stop.

## Power model

The default 5-minute guardian interval is meant to be a balanced fallback. It does not maintain its own network connection and normally performs only a quick service-state check before sleeping again.

The actual realtime transport remains MQTT in the Android foreground service with a 300-second MQTT keepalive. If you do not want the root fallback, simply do not install this module.

## Install

Package the contents of this directory as a KernelSU module ZIP so that `module.prop`, `service.sh`, `action.sh`, and `skip_mount` are at the ZIP root, then install it from KernelSU Manager and reboot.

Enable **Self-hosted MQTT transport** inside the Alert app before expecting Guardian to restart it.

Use the KernelSU module **Action** button for a one-shot status check / restart attempt.
