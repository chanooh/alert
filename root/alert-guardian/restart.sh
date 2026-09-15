#!/system/bin/sh

. "${0%/*}/lib.sh"

apply_best_effort_policy
if [ ! -f "$MARKER" ]; then
  echo "Guardian marker is disabled. Enable self-hosted MQTT in Alert first."
  exit 1
fi

recover_transport "manual WebUI request"
echo "Restart request sent. Refresh status in a few seconds."
