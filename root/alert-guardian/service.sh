#!/system/bin/sh

. "${0%/*}/lib.sh"

# KernelSU service.sh runs during late_start. Wait until Android is fully usable.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 5
done

apply_best_effort_policy
log "guardian started; root MQTT supervisor enabled"

backoff=5
while true; do
  if ! pm path "$PKG" >/dev/null 2>&1; then
    sleep 60
    continue
  fi
  if start_transport; then
    pid=$(cat "$PID_FILE" 2>/dev/null)
    wait "$pid" 2>/dev/null
    code=$?
    rm -f "$PID_FILE"
    log "root MQTT daemon exited (${code}); retrying in ${backoff}s"
  else
    log "root MQTT daemon unavailable for this ABI; retrying in ${backoff}s"
  fi
  sleep "$backoff"
  if [ "$backoff" -lt 300 ]; then
    backoff=$((backoff * 2))
    [ "$backoff" -gt 300 ] && backoff=300
  fi
done
