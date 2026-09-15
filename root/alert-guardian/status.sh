#!/system/bin/sh

. "${0%/*}/lib.sh"

recover=${1:-}
echo "Alert Guardian ${DEFAULT_INTERVAL_SECONDS}s watchdog"

if ! pm path "$PKG" >/dev/null 2>&1; then
  echo "App: not installed"
  exit 1
fi

echo "App: installed"
echo "Guardian marker: $([ -f "$MARKER" ] && echo enabled || echo disabled)"
echo "Check interval: $(configured_interval)s"

if transport_running; then
  echo "MQTT foreground service: running"
else
  echo "MQTT foreground service: missing"
fi

age=$(heartbeat_age_seconds || true)
if [ -n "$age" ]; then
  echo "Last healthy MQTT heartbeat: ${age}s ago"
else
  echo "Last healthy MQTT heartbeat: unavailable (install Alert 0.1.7+)"
fi

if dumpsys deviceidle whitelist 2>/dev/null | grep -q "$PKG"; then
  echo "Doze whitelist: present"
else
  echo "Doze whitelist: not detected"
fi

if [ "$recover" = "--recover" ] && [ -f "$MARKER" ]; then
  if ! transport_running; then
    recover_transport "manual action; service missing"
  elif [ -n "$age" ] && [ "$age" -gt "$STALE_HEARTBEAT_SECONDS" ]; then
    recover_transport "manual action; heartbeat stale (${age}s)"
  else
    echo "Recovery: not needed"
  fi
fi

echo "Recent guardian log:"
tail -n 12 "$LOG" 2>/dev/null || true
