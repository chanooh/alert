#!/system/bin/sh

. "${0%/*}/lib.sh"

apply_best_effort_policy
if daemon_running; then
  pid=$(cat "$PID_FILE")
  kill "$pid" 2>/dev/null || true
  echo "Restart requested. The supervisor will relaunch Root MQTT shortly."
else
  echo "Daemon is not running; the late-start supervisor will retry automatically."
fi
