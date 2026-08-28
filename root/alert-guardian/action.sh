#!/system/bin/sh

PKG="dev.chanooh.alert"
COMPONENT="dev.chanooh.alert/.transport.MqttTransportService"
ACTION="dev.chanooh.alert.action.START_MQTT"
MARKER="/data/user/0/dev.chanooh.alert/files/guardian_mqtt_enabled"

echo "Alert Guardian"
echo "Package: $PKG"

if ! pm path "$PKG" >/dev/null 2>&1; then
  echo "App: not installed"
  exit 1
fi

echo "App: installed"

if [ -f "$MARKER" ]; then
  echo "Guardian marker: enabled"
else
  echo "Guardian marker: disabled"
  echo "Enable Self-hosted MQTT transport inside the Alert app first."
  exit 0
fi

if dumpsys activity services "$PKG" 2>/dev/null | grep -q "MqttTransportService"; then
  echo "MQTT service: running"
else
  echo "MQTT service: not running; requesting explicit restart"
  am start-foreground-service --user 0 -a "$ACTION" -n "$COMPONENT"
fi
