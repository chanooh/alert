#!/system/bin/sh

MODDIR=${0%/*}
PKG="dev.chanooh.alert"
COMPONENT="dev.chanooh.alert/.transport.MqttTransportService"
ACTION="dev.chanooh.alert.action.START_MQTT"
MARKER="/data/user/0/dev.chanooh.alert/files/guardian_mqtt_enabled"
LOG="$MODDIR/guardian.log"
INTERVAL_SECONDS=300

log() {
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG"
  tail -n 200 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
}

apply_best_effort_policy() {
  dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true
  cmd appops set "$PKG" RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
  cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
  cmd appops set "$PKG" START_FOREGROUND allow >/dev/null 2>&1 || true
  cmd appops set "$PKG" WAKE_LOCK allow >/dev/null 2>&1 || true
}

transport_running() {
  dumpsys activity services "$PKG" 2>/dev/null | grep -q "MqttTransportService"
}

start_transport() {
  am start-foreground-service \
    --user 0 \
    -a "$ACTION" \
    -n "$COMPONENT" >/dev/null 2>&1
}

# KernelSU service.sh runs during late_start. Wait until Android is fully usable.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 5
done

apply_best_effort_policy
log "guardian started; interval=${INTERVAL_SECONDS}s"

while true; do
  if pm path "$PKG" >/dev/null 2>&1 && [ -f "$MARKER" ]; then
    if ! transport_running; then
      if start_transport; then
        log "transport restart requested"
      else
        log "transport restart request failed"
      fi
    fi
  fi
  sleep "$INTERVAL_SECONDS"
done
