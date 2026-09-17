#!/system/bin/sh

. "${0%/*}/lib.sh"

apply_best_effort_policy
if [ ! -f "$MARKER" ]; then
  echo "Guardian marker is disabled. Enable self-hosted MQTT in Alert first."
  exit 1
fi

if recover_transport "manual WebUI request" manual; then
  echo "Restart request sent. Refresh status in a few seconds."
else
  code=$?
  if [ "$code" -eq 2 ]; then
    echo "Recent restart request is still in its cooldown. Refresh status shortly."
    exit 0
  fi
  exit "$code"
fi
