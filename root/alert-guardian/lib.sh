#!/system/bin/sh

MODDIR=${0%/*}
PKG="dev.chanooh.alert"
APP_FILES="/data/user/0/$PKG/files"
TRANSPORT_DIR="$APP_FILES/root_transport"
TRANSPORT_CONFIG="$TRANSPORT_DIR/config.json"
TRANSPORT_STATUS="$TRANSPORT_DIR/status.json"
INBOX="$TRANSPORT_DIR/inbox"
LOG="$MODDIR/guardian.log"
PID_FILE="$MODDIR/daemon.pid"

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

daemon_binary() {
  abi=$(getprop ro.product.cpu.abi 2>/dev/null)
  case "$abi" in
    arm64-v8a|aarch64) printf '%s\n' "$MODDIR/bin/arm64-v8a/alert-root-mqtt" ;;
    armeabi-v7a|armeabi) printf '%s\n' "$MODDIR/bin/armeabi-v7a/alert-root-mqtt" ;;
    *) return 1 ;;
  esac
}

daemon_running() {
  [ -f "$PID_FILE" ] || return 1
  pid=$(cat "$PID_FILE" 2>/dev/null)
  case "$pid" in *[!0-9]*|'') return 1 ;; esac
  kill -0 "$pid" 2>/dev/null
}

  binary=$(daemon_binary) || return 1
  [ -x "$binary" ] || return 1
  "$binary" "$APP_FILES" >> "$LOG" 2>&1 &
  pid=$!
  printf '%s\n' "$pid" > "$PID_FILE"
  log "root MQTT daemon started"
}
