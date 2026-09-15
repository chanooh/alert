# HyperOS 3 + KernelSU device acceptance

This checklist is for **real-device acceptance only**. GitHub Actions can prove source compilation, JVM/Node tests, APK packaging, and KernelSU ZIP packaging; it cannot prove HyperOS notification policy, lock-screen behavior, real speaker/vibrator output, root shell behavior, or OEM background-process handling.

Do not put a real server URL, broker URL, API key, bearer token, HMAC secret, MQTT password, device ID, phone number, or personal event payload into this document, screenshots, issues, PR comments, or logs committed to the repository.

## Test target

Record device-specific results outside this repository (for example in a private review note). At minimum note:

- HyperOS major/minor build used for the test.
- Android version/API level shown by the device.
- KernelSU version and whether root was granted to Alert.
- Alert APK head commit / GitHub Actions run used.
- Guardian artifact/run used, if installed.
- Pass/fail and observed behavior for each scenario below.

Do **not** replace these with invented values in Git.

## 1. Obtain CI artifacts

Use artifacts from the GitHub Actions run for the exact commit under review. Do not build locally for acceptance.

Expected artifacts:

- `alert-debug-apk`
- `alert-guardian-kernelsu`

Before testing, confirm the workflow run reports success for all three jobs:

- Server: tests, typecheck, build.
- Android: JVM unit tests and `assembleDebug`.
- Guardian: KernelSU package.

The APK/ZIP from an older green run is not evidence for a newer commit.

## 2. Install the Android app

1. Download/extract `alert-debug-apk` from the exact green Actions run.
2. Install the debug APK on the HyperOS 3 test device using the normal package installer/ADB method chosen by the tester.
3. Launch **Alert** once.
4. Confirm the Material 3 control center opens without crashing.
5. Confirm no installation-specific endpoint/credential is pre-populated by the repository build.

Expected result: the app installs and launches, and real deployment values must be entered at runtime.

## 3. Grant reliability permissions

From the Alert control center and HyperOS settings, grant/verify the permissions that actually exist on the tested build:

1. **Notifications** — allow Alert notifications.
2. **Full-screen alert access** — on Android versions that expose the special full-screen-intent setting, allow it for Alert.
3. **Do Not Disturb policy access** — grant notification-policy/DND access when testing DND behavior.
4. Allow Alert to run its foreground service. If HyperOS exposes an app-specific background/autostart/battery page, record the chosen setting outside Git.
5. If HyperOS offers battery optimization controls, remove restrictions that would obviously prevent the intended always-on private terminal behavior, then record what was changed.

Expected result: the app status screen no longer reports missing notification/full-screen/DND capabilities that were explicitly granted.

### What CI cannot verify here

Only a real HyperOS device can verify:

- The exact HyperOS permission/settings screens.
- Whether full-screen intent is actually permitted at runtime.
- Whether HyperOS adds another vendor-specific battery/autostart restriction.
- Whether a foreground service remains alive under the tested power mode.

## 4. Configure the private runtime values

Enter the deployment-specific values **only inside the app**:

- Server base URL.
- MQTT broker URL (`mqtt://` or preferably `mqtts://` for an Internet-facing broker).
- MQTT username/password if the broker requires them.
- Device ID.
- Device API token.
- Device HMAC secret.
- Desired critical alarm volume.
- Restore-alarm-volume-after-ACK preference.
- Root DND override only if the root-specific test section below is intentionally being executed.

Tap **Save & apply** and enable **Self-hosted MQTT transport**.

Acceptance observations:

- The foreground transport notification should appear.
- The transport should eventually report an armed/connected state when the broker is reachable.
- Secrets/identifiers should remain masked in the UI rather than being shown as plain persisted values.
- An invalid MQTT URI should fail safely and require configuration correction rather than creating repeated parallel connections.

Do not capture real credentials in a public screenshot or log.

## 5. Baseline critical-alert test (screen on, DND off)

1. Leave DND off.
2. Keep the screen on and Alert configured/armed.
3. Send one correctly signed `critical` event to this test device using the private server/event source.
4. Observe the device.

Expected result:

- Critical alarm audio starts on the alarm stream.
- Repeating vibration starts.
- Critical UI/notification is presented.
- The alert remains active until the user acknowledges it.
- Tapping **Acknowledge & stop** immediately stops local alarm/vibration.
- ACK is queued durably; temporary network loss at the moment of the tap must not require keeping the critical UI open.
- The server eventually records the event as acknowledged once connectivity is available.

Only a real device can verify the physical sound, vibration, UI, and end-to-end ACK.

## 6. Screen-off / lock-screen critical test

1. Lock the device and turn the display off.
2. Leave DND off.
3. Send a new correctly signed `critical` event.

Expected result to verify on the device:

- The device receives the MQTT event while locked.
- The critical service acquires its wake path.
- The display turns on / critical activity is allowed over the lock screen when HyperOS full-screen policy permits it.
- Alarm audio and vibration are perceptible.
- The user can reach the acknowledgement action and stop the alert.

A CI pass does **not** prove any of these lock-screen behaviors.

If HyperOS shows only a heads-up/lock-screen notification instead of the full-screen activity, record the actual behavior and re-check the OS full-screen-intent permission before calling the scenario failed.

## 7. Silent/ringer-mode test

1. Put the phone into the normal silent/ringer-muted state without enabling DND.
2. Lock the screen.
3. Send a new `critical` event.

Expected result to verify:

- Critical uses alarm-stream audio rather than relying on the normal notification/ring stream.
- Vibration repeats.
- The critical path remains acknowledgeable.

HyperOS may apply vendor sound policy differently from AOSP, so this result must be recorded from the physical device.

## 8. DND tests without Root DND override

First disable **Root DND override** in Alert.

Test the DND modes that HyperOS exposes, such as priority/selected-interruptions, alarms-only, and total silence.

For each mode:

1. Enable the DND mode.
2. Lock the screen.
3. Send a fresh `critical` event.
4. Record whether full-screen UI, alarm audio, and vibration were delivered.
5. ACK the event.

Important: this section measures native Android/HyperOS behavior. `NotificationChannel.setBypassDnd(true)` and notification-policy access are **not** treated by this project as a universal guarantee that every DND configuration will permit audio/vibration.

Failure to sound in a restrictive DND mode is therefore not automatically a regression in the Root override path; continue with the rooted test below if that feature is part of the deployment requirement.

## 9. Install KernelSU Guardian (optional reliability layer)

Use only the `alert-guardian-kernelsu` artifact produced by the exact green CI run. Do not install an AAR/ZIP/module obtained from an unreviewed third party.

1. Confirm KernelSU is already working on the test device.
2. Install the `alert-guardian-kernelsu` ZIP from KernelSU Manager.
3. Reboot as required by KernelSU.
4. Launch Alert after boot and confirm **Self-hosted MQTT transport** is enabled/configured.
5. Confirm the transport foreground notification is present/connected.
6. In KernelSU Manager, the Guardian module Action button may be used for a one-shot status/restart attempt.

Guardian behavior to verify:

- It waits for Android boot completion.
- It only acts if the app's private `guardian_mqtt_enabled` marker exists.
- It checks at a default interval of 300 seconds.
- If the MQTT foreground service is absent, it issues an explicit rooted start request for `MqttTransportService`.
- It does not carry MQTT traffic itself and does not continuously hold a wake lock.

## 10. Swipe-away / task-removal test

This is intentionally separate from Android **Force stop**.

1. Ensure Alert MQTT is enabled and connected.
2. Open Android Recents and swipe the Alert app task away.
3. Do not press **Force stop** yet.
4. Observe whether the MQTT foreground-service notification remains present.
5. With the display off, send a new `critical` event.

Expected result:

- Removing the UI task should not by itself disable the intended MQTT foreground transport.
- The new critical alert should still be delivered and be acknowledgeable.

Record the actual HyperOS result. OEM task-removal behavior cannot be reproduced by JVM tests or GitHub-hosted Android builds.

## 11. Process/Force-stop recovery test with Guardian

This is the destructive reliability test and should only be run when KernelSU Guardian is installed and the tester accepts the possible recovery delay.

1. Confirm MQTT is enabled/connected and the Guardian marker has been created by the app.
2. Force-stop/kill the Alert package using the chosen HyperOS/root test method.
3. Confirm the Alert foreground transport disappears.
4. Wait for Guardian's next check. The default loop is 300 seconds, so recovery is **not instantaneous**.
5. Confirm Guardian requests the MQTT foreground service to start again and that the transport reconnects.
6. Send a new `critical` event after recovery and verify delivery/ACK.

### Unacknowledged-critical re-arm test

To verify the specific retry/re-arm protection:

1. Send a fresh `critical` event and let it start ringing.
2. **Do not ACK it.**
3. Kill the app/process in the controlled test.
4. Allow Guardian to restore the transport.
5. Leave the corresponding server event pending so the server retries it.
6. Verify that receiving the duplicate event ID while it remains in the active-critical store re-arms the critical alarm instead of being discarded only by deduplication.
7. ACK the re-armed alert and confirm the server eventually marks it acknowledged.

This sequence can only be proven on a real device against a real private server/broker. Do not claim it passed based on CI alone.

## 12. Root DND override acceptance

### Preconditions

- KernelSU/root is working.
- Alert has been granted root access for the test.
- The tester understands that this feature temporarily changes a global system DND filter.
- No critical personal situation depends on the phone's current DND state during this experiment.

### Test

1. In Alert, enable **Root DND override** and save/apply.
2. Enable one HyperOS DND mode.
3. Record the current DND mode privately before sending the test event.
4. Lock the screen.
5. Send a new correctly signed `critical` event.
6. Verify that the critical path attempts to temporarily disable the active DND filter using root shell access.
7. Verify actual alarm audio/vibration/full-screen behavior on the physical device.
8. Tap **Acknowledge & stop**.
9. Confirm the previous **interruption filter level** is restored.
10. Repeat for other HyperOS DND modes only if needed by the deployment.

### Risk and recovery boundary

Root DND override is a best-effort private-device feature, not a transactional system setting API.

- It restores the previous Android interruption-filter level (`priority`, `alarms`, or `none`) when normal critical-service cleanup runs.
- It does **not** take a full snapshot of HyperOS/AOSP Zen rules, schedules, contacts, exceptions, or vendor-specific DND policy.
- The restore mode is currently held in the critical alarm-service process. If the service/process/device crashes, is killed, or loses power after DND was disabled but before cleanup, the previous DND filter may **not** be restored automatically.
- Root shell command semantics may differ on future HyperOS releases.
- After every Root DND test, manually inspect the system DND setting. If restoration did not occur, restore the intended mode manually before continuing normal phone use.

Do not enable this option on a daily-use device unless that failure mode is acceptable.

## 13. ACK and duplicate-event checks

Run at least these end-to-end checks against the private server:

1. Send a critical event and ACK it once. Confirm one logical ACK is sufficient for the server to stop retrying it.
2. Re-publish an already processed non-critical event ID and verify durable deduplication prevents duplicate user-visible handling.
3. For an unacknowledged active critical event, verify the retry/re-arm behavior described above.
4. Temporarily remove network connectivity immediately before ACKing a critical event; ACK locally; restore connectivity; verify WorkManager eventually uploads the ACK.

Never paste the real signed event body into a public issue/PR.

## 14. Reboot recovery

1. Leave MQTT enabled in Alert.
2. Reboot the phone normally.
3. After boot completion, verify the app/Guardian recovery behavior appropriate to the configured setup.
4. Confirm the MQTT foreground transport reconnects.
5. With the device locked, send a fresh critical event and verify delivery/ACK.

The Android app has a boot receiver, and Guardian also starts during KernelSU late-start; actual HyperOS scheduling/order must be verified on-device.

## 15. Acceptance result

A release candidate should not be called **HyperOS 3 + KernelSU verified** until the required scenarios above have real recorded results.

CI-only status may be stated as:

> Server tests/typecheck/build, Android JVM tests/assembleDebug, and Guardian packaging passed in GitHub Actions. Real-device HyperOS 3 behavior remains pending device acceptance.

After real testing, report pass/fail separately for at least:

- Runtime configuration + MQTT connection.
- Screen-on critical.
- Screen-off/lock-screen critical.
- Silent-mode critical.
- DND without root override.
- Root DND override and post-ACK DND restoration.
- Swipe-away behavior.
- Guardian recovery after process/force-stop.
- Unacknowledged critical re-arm after recovery.
- Offline ACK then WorkManager delivery.
- Reboot recovery.

A build artifact existing in GitHub Actions is not a substitute for these device results.
