#!/system/bin/sh

. "${0%/*}/lib.sh"

echo "Alert Guardian"
echo "Package: $PKG"

exec "$MODDIR/status.sh" --recover
