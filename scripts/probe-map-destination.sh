#!/usr/bin/env bash
# Probe whether the head unit's map exposes the destination coordinates over ADB.
#
# The car's AMap only broadcasts city/district names (KEY_TYPE 10002), so DiPlay can hand the
# iPhone a name to search for, not a point. This script collects everything an adb shell can read
# about the running navigation, so a source for latitude/longitude can be found (or ruled out).
#
# Run it from the PC WHILE the car is actively guiding to a destination:
#   scripts/probe-map-destination.sh [adb-serial]
#
# The output file contains the real destination (names, coordinates, possibly the address book of
# the map app). Treat it as private data.
set -uo pipefail

ADB=${ADB:-adb}
SERIAL=${1:-}
ADB_ARGS=()
[[ -n "$SERIAL" ]] && ADB_ARGS=(-s "$SERIAL")
LOGCAT_SECONDS=${LOGCAT_SECONDS:-45}
OUT=${OUT:-"map-destination-probe-$(date +%Y%m%d-%H%M%S).txt"}

# Head-unit map and the stock cluster adapters that receive its guidance.
PACKAGES=${PACKAGES:-"com.byd.launchermap com.byd.automap com.autonavi.amapauto com.byd.amapservice com.example.amapservice com.byd.naviauto"}
# Decimal degrees with enough precision to be a real coordinate, and the usual AMap key names.
COORD='[0-9]{2,3}\.[0-9]{4,}'
KEYS='lat|lon|lng|latitude|longitude|poiname|dname|dest|endPoint|终点|目的地|navi|route'

sh_run() { "$ADB" "${ADB_ARGS[@]}" shell "$1" 2>&1; }

section() {
  printf '\n\n===== %s =====\n' "$1" >> "$OUT"
  shift
  "$@" >> "$OUT" 2>&1
}

if ! "$ADB" "${ADB_ARGS[@]}" get-state >/dev/null 2>&1; then
  echo "No device. Connect first, e.g.: adb connect 172.31.46.1:5555" >&2
  exit 1
fi

{
  echo "DiPlay map-destination ADB probe $(date '+%F %T')"
  echo "device: $("$ADB" "${ADB_ARGS[@]}" shell getprop ro.product.model 2>&1 | tr -d '\r')"
  echo "build:  $("$ADB" "${ADB_ARGS[@]}" shell getprop ro.build.fingerprint 2>&1 | tr -d '\r')"
  echo "shell user: $(sh_run 'id -u' | tr -d '\r')"
} > "$OUT"

echo "Writing to $OUT"
echo "Make sure the car is guiding to a destination right now."

section "installed map packages" bash -c "
  for p in $PACKAGES; do
    path=\$($ADB ${ADB_ARGS[*]} shell pm path \"\$p\" 2>&1 | tr -d '\r')
    [ -n \"\$path\" ] && echo \"\$p -> \$path\"
  done"

# Registered receivers tell which app listens to the AMap protocol; the history shows which
# actions actually fired (extras are not printed by dumpsys, so logcat below matters more).
section "broadcast receivers and history (AUTONAVI / navi)" bash -c "
  $ADB ${ADB_ARGS[*]} shell dumpsys activity broadcasts 2>&1 | grep -iE 'AUTONAVI|navi|amap' | head -200"

section "exported content providers of the map packages" bash -c "
  for p in $PACKAGES; do
    echo \"--- \$p\"
    $ADB ${ADB_ARGS[*]} shell dumpsys package \"\$p\" 2>&1 | grep -iE 'Provider|authority' | head -40
  done"

section "app state dumps (may print the current route)" bash -c "
  for p in $PACKAGES; do
    echo \"--- services of \$p\"
    $ADB ${ADB_ARGS[*]} shell dumpsys activity services \"\$p\" 2>&1 | head -120
  done"

section "binder services that look navigation related" bash -c "
  $ADB ${ADB_ARGS[*]} shell service list 2>&1 | grep -iE 'navi|map|amap|auto|cluster|meter' | head -40"

section "map files readable by the shell user" bash -c "
  for d in /sdcard/amap /sdcard/autonavi /sdcard/Android/data/com.byd.launchermap /sdcard/amapauto9 /sdcard/byd; do
    echo \"--- \$d\"
    $ADB ${ADB_ARGS[*]} shell ls -la \"\$d\" 2>&1 | head -30
  done"

section "current logcat buffer: coordinate-looking values" bash -c "
  $ADB ${ADB_ARGS[*]} logcat -d -v time 2>&1 | grep -nEi '($KEYS)' | grep -E '$COORD' | tail -120"

printf '\n\n===== live logcat for %ss =====\n' "$LOGCAT_SECONDS" >> "$OUT"
echo
echo "Now, on the car: cancel the route and start navigation to the SAME destination again."
echo "Capturing logcat for ${LOGCAT_SECONDS}s..."
"$ADB" "${ADB_ARGS[@]}" logcat -c >/dev/null 2>&1
timeout "$LOGCAT_SECONDS" "$ADB" "${ADB_ARGS[@]}" logcat -v time >> "$OUT" 2>&1

{
  printf '\n\n===== summary: lines in the live capture that carry a coordinate =====\n'
  grep -aEi "($KEYS)" "$OUT" | grep -aE "$COORD" | tail -80
} >> "$OUT"

echo
echo "Done: $OUT"
echo "It contains the real destination; send it only to someone you trust."
