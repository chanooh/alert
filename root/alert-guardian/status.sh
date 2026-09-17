#!/system/bin/sh

. "${0%/*}/lib.sh"

echo "Alert Guardian root MQTT transport"

if ! pm path "$PKG" >/dev/null 2>&1; then
  echo "App: not installed"
  exit 1
fi

echo "App: installed"
if daemon_running; then
  echo "Root MQTT daemon: running"
else
  echo "Root MQTT daemon: not running"
fi
if [ -f "$TRANSPORT_CONFIG" ]; then
  echo "App Root configuration: present"
else
  echo "App Root configuration: not saved"
fi
if [ -f "$TRANSPORT_STATUS" ]; then
  echo "Transport status:"
  tr '{,}' '\n' < "$TRANSPORT_STATUS" | sed 's/^[[:space:]]*//; s/^"//; s/"$//'
else
  echo "Transport status: waiting for daemon"
fi
count=$(find "$INBOX" -maxdepth 1 -type f -name '*.json' 2>/dev/null | wc -l | tr -d ' ')
echo "Pending inbox files: ${count:-0}"

if dumpsys deviceidle whitelist 2>/dev/null | grep -q "$PKG"; then
  echo "Doze whitelist: present"
else
  echo "Doze whitelist: not detected"
fi

echo "Recent guardian log:"
tail -n 12 "$LOG" 2>/dev/null || true
