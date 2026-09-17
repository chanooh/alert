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
RECOVERY_FILE="$CONFIG_DIR/last_recovery_epoch"
DEFAULT_INTERVAL_SECONDS=300
STALE_HEARTBEAT_SECONDS=600
RECOVERY_COOLDOWN_SECONDS=900
MANUAL_COOLDOWN_SECONDS=60

log() {
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG"
  tail -n 200 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
}

configured_interval() {
  value=$(cat "$INTERVAL_FILE" 2>/dev/null)
  case "$value" in
    300|900) printf '%s\n' "$value" ;;
    *) printf '%s\n' "$DEFAULT_INTERVAL_SECONDS" ;;
  esac
}

last_recovery_age_seconds() {
  [ -f "$RECOVERY_FILE" ] || return 1
  now=$(date +%s 2>/dev/null) || return 1
  then=$(cat "$RECOVERY_FILE" 2>/dev/null) || return 1
  case "$then" in *[!0-9]*|'') return 1 ;; esac
  printf '%s\n' $((now - then))
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
  manual=$2
  cooldown=$RECOVERY_COOLDOWN_SECONDS
  [ "$manual" = "manual" ] && cooldown=$MANUAL_COOLDOWN_SECONDS
  age=$(last_recovery_age_seconds || true)
  if [ -n "$age" ] && [ "$age" -lt "$cooldown" ]; then
    log "transport recovery skipped: cooldown (${age}s < ${cooldown}s); $reason"
    return 2
  fi
  if start_transport; then
    umask 077
    mkdir -p "$CONFIG_DIR"
    date +%s > "$RECOVERY_FILE"
    log "transport restart requested: $reason"
    return 0
  fi
  log "transport restart request failed: $reason"
  return 1
}
