#!/system/bin/sh

. "${0%/*}/lib.sh"

case "$1" in
  300|900)
    umask 077
    mkdir -p "$CONFIG_DIR"
    printf '%s\n' "$1" > "$INTERVAL_FILE"
    log "check interval changed to ${1}s"
    echo "Check interval set to ${1}s"
    ;;
  *)
    echo "Unsupported interval"
    exit 1
    ;;
esac
