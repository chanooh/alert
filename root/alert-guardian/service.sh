#!/system/bin/sh

. "${0%/*}/lib.sh"

# KernelSU service.sh runs during late_start. Wait until Android is fully usable.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 5
done

apply_best_effort_policy
log "guardian started; default interval=${DEFAULT_INTERVAL_SECONDS}s, stale=${STALE_HEARTBEAT_SECONDS}s"

while true; do
  if pm path "$PKG" >/dev/null 2>&1 && [ -f "$MARKER" ]; then
    # Avoid dumpsys here: a root service dump every minute prevents deep idle.
    age=$(heartbeat_age_seconds || true)
    if [ -z "$age" ]; then
      recover_transport "MQTT heartbeat unavailable"
    elif [ "$age" -gt "$STALE_HEARTBEAT_SECONDS" ]; then
      recover_transport "MQTT heartbeat stale (${age}s)"
    fi
  fi
  sleep "$(configured_interval)"
done
