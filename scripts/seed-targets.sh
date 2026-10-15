#!/bin/zsh
# Usage: scripts/seed-targets.sh [--at <lat>,<lon>] <bearing>:<hue>[:<meters>] ...
#        scripts/seed-targets.sh [--at <lat>,<lon>] many
#        scripts/seed-targets.sh none
# Replaces the app's waypoints on the attached emulator with targets at the given true bearings (degrees) and
# distances (meters, 400 if left out) from a position, each with the given number stored as its color (<hue>: its place, 0 to 360, on the ring the colors come from; the column is named hue), then starts the app.
# The position is the one the tests feed (43.7022,-72.2896) unless --at names another; run
# `scripts/geo-feed.sh <lon> <lat> &` with the same position so the bearings come out as asked.
#   many   thirty targets 12 degrees apart from 5, with the color numbers the app gives the first thirty waypoints of a
#          new install (since 2026-10-14; before, the golden-angle places from 0); the
#          thirtieth is at 77, the bearing of the seventh.
#   none   no waypoints at all.
# The rows go into the app's own database file with the app stopped, and the location permission is granted.
# Where the image has no sqlite3 of its own, this machine's sqlite3 changes a copy of the file, which is put back.
# It refuses to run on anything but an emulator.
# Rows are named Waypoint 1, 2, ... in the order given and the last one is the newest, so it has the focus unless
# the focus stored in the app's settings names another row that exists. The settings are not touched, so the
# number the app gives its next waypoint does not follow these names.
source "$(dirname "$0")/emulator-only.sh" || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
PKG=com.infinite8looper.compass
at=43.7022,-72.2896
if [ "$1" = "--at" ]; then at=$2; shift 2; fi
if [ $# -eq 0 ]; then
  echo "usage: scripts/seed-targets.sh [--at <lat>,<lon>] <bearing>:<hue>[:<meters>] ... | many | none" >&2
  exit 1
fi
sql=$(python3 - "$at" "$@" <<'PY'
import math, sys
lat0, lon0 = (math.radians(float(v)) for v in sys.argv[1].split(","))
specs = sys.argv[2:]
if specs == ["none"]:
    specs = []
elif specs == ["many"]:
    # The places the app gives the first thirty waypoints of a new install (nextWaypointPlace; WaypointPlacesTest
    # checks this list).
    places = "108.32 298.36 180.91 43.40 245.83 75.86 148.45 265.90 318.42 128.39 160.85 31.01 95.93 285.96 63.47 51.07 136.06 306.02 83.53 273.56 326.09 115.99 168.52 253.50 38.68 241.10 293.63 261.17 313.69 281.23".split()
    specs = [f"{5 + i * 12}:{places[i]}" for i in range(29)] + [f"77:{places[29]}"]
rows = ["DELETE FROM waypoints;"]
for i, spec in enumerate(specs, 1):
    parts = spec.split(":")
    if len(parts) not in (2, 3):
        sys.exit(f"not <bearing>:<hue>[:<meters>]: {spec}")
    bearing, hue = math.radians(float(parts[0])), float(parts[1])
    d = (float(parts[2]) if len(parts) == 3 else 400.0) / 6371008.8   # the mean radius the app uses
    lat = math.asin(math.sin(lat0) * math.cos(d) + math.cos(lat0) * math.sin(d) * math.cos(bearing))
    lon = lon0 + math.atan2(math.sin(bearing) * math.sin(d) * math.cos(lat0), math.cos(d) - math.sin(lat0) * math.sin(lat))
    rows.append(
        "INSERT INTO waypoints(name,lat,lon,accuracyM,createdAt,hue,isTarget) VALUES("
        f"'Waypoint {i}',{math.degrees(lat):.7f},{math.degrees(lon):.7f},5.0,{1760000000000 + i},{hue},1);"
    )
print(" ".join(rows))
PY
) || exit 1
# This deletes every waypoint on the attached device, so it runs on an emulator only.
serial=$($ADB get-serialno 2>/dev/null | tr -d '\r')
qemu="$($ADB shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')$($ADB shell getprop ro.boot.qemu 2>/dev/null | tr -d '\r')"
if [[ "$serial" != emulator-* ]] || [[ "$qemu" != *1* ]]; then
  echo "refusing: the attached device (${serial:-none, or more than one}) is not an emulator, and this would delete its waypoints" >&2
  exit 1
fi
$ADB shell am force-stop $PKG
sleep 1
if $ADB shell "command -v sqlite3" 2>/dev/null | grep -q sqlite3; then
  # One transaction, stopped at the first error: a statement that fails leaves the device's rows as they were.
  out=$($ADB shell "run-as $PKG sqlite3 -bail databases/waypoints.db \"BEGIN; $sql SELECT count(*) FROM waypoints; COMMIT;\"; echo status=\$?" 2>&1 | tr -d '\r')
  if [[ "$out" != *$'\n'status=0 && "$out" != status=0 ]] || [[ "$(echo "$out" | sed '$d' | tail -1)" != <-> ]]; then
    echo "the device's sqlite3 could not change the waypoints; they are as they were: $(echo "$out" | head -2 | tr '\n' ' ')" >&2
    exit 1
  fi
  count=$(echo "$out" | sed '$d' | tail -1)
else
  # An image without sqlite3 (the signed Wear OS 6 and 7 images): the database file, with its write-ahead log if
  # it has one, is copied to this machine, changed here, and copied back whole.
  work=$(mktemp -d) || exit 1
  trap 'rm -rf "$work"; $ADB shell rm -f /data/local/tmp/seed-waypoints.db' EXIT
  for f in waypoints.db waypoints.db-wal waypoints.db-shm; do
    if [ -n "$($ADB shell "run-as $PKG ls databases/$f" 2>/dev/null)" ]; then
      $ADB exec-out run-as $PKG cat databases/$f > "$work/$f"
    fi
  done
  if [ -s "$work/waypoints.db" ]; then
    # The device's database is replaced only by a copy this machine's sqlite3 changed without an error and can
    # count the rows of. Anything else stops here and leaves the device's files as they were.
    # The status tested is sqlite3's own: its output goes to a file, not through a pipe.
    if ! sqlite3 -bail "$work/waypoints.db" "PRAGMA wal_checkpoint(TRUNCATE); $sql PRAGMA journal_mode=DELETE; SELECT count(*) FROM waypoints;" > "$work/out" 2>&1 || [[ "$(tail -1 "$work/out")" != <-> ]]; then
      echo "this machine's sqlite3 could not change the copy; the device's database is untouched: $(grep -m1 -i error "$work/out" || tail -1 "$work/out")" >&2
      exit 1
    fi
    count=$(tail -1 "$work/out")
    $ADB push "$work/waypoints.db" /data/local/tmp/seed-waypoints.db > /dev/null 2>&1 &&
      $ADB shell "run-as $PKG sh -c 'cp /data/local/tmp/seed-waypoints.db databases/waypoints.db && rm -f databases/waypoints.db-wal databases/waypoints.db-shm'" || count="could not copy the database back"
  fi
fi
if [[ "$count" != <-> ]]; then
  echo "could not write the waypoints (has the app been started once, so its database exists?): $count" >&2
  exit 1
fi
$ADB shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
$ADB shell am start -n $PKG/.MainActivity > /dev/null
echo "$count targets around $at; app started"
