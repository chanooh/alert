#!/system/bin/sh

MODDIR=${0%/*}
PKG="dev.chanooh.alert"
COMPONENT="dev.chanooh.alert/.transport.MqttTransportService"
ACTION="dev.chanooh.alert.action.START_MQTT"
MARKER="/data/user/0/dev.chanooh.alert/files/guardian_mqtt_enabled"
HEARTBEAT="/data/user/0/dev.chanooh.alert/files/guardian_mqtt_heartbeat"
LOG="$MODDIR/guardian.log"
CONFIG_DIR="$MODDIR/config"
INTERVAL_FILE="$CONFIG_DIR/interval_seconds"
DEFAULT_INTERVAL_SECONDS=60
STALE_HEARTBEAT_SECONDS=180

log() {
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG"
  tail -n 200 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
}

configured_interval() {
  value=$(cat "$INTERVAL_FILE" 2>/dev/null)
  case "$value" in
    30|60|300) printf '%s\n' "$value" ;;
    *) printf '%s\n' "$DEFAULT_INTERVAL_SECONDS" ;;
  esac
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

heartbeat_age_seconds() {
  [ -f "$HEARTBEAT" ] || return 1
  now=$(date +%s 2>/dev/null) || return 1
  modified=$(stat -c %Y "$HEARTBEAT" 2>/dev/null || stat -f %m "$HEARTBEAT" 2>/dev/null) || return 1
  printf '%s\n' $((now - modified))
}

start_transport() {
  am start-foreground-service \
    --user 0 \
    -a "$ACTION" \
    -n "$COMPONENT" >/dev/null 2>&1
}

recover_transport() {
  reason=$1
  if start_transport; then
    log "transport restart requested: $reason"
    return 0
  fi
  log "transport restart request failed: $reason"
  return 1
}
