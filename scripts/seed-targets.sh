#!/bin/zsh
# Usage: scripts/seed-targets.sh [--at <lat>,<lon>] <bearing>:<hue>[:<meters>] ...
#        scripts/seed-targets.sh [--at <lat>,<lon>] many
#        scripts/seed-targets.sh none
# Replaces the app's waypoints on the attached emulator with targets at the given true bearings (degrees) and
# distances (meters, 400 if left out) from a position, each in the given hue (0 to 360), then starts the app.
# The position is the one the tests feed (43.7022,-72.2896) unless --at names another; run
# `scripts/geo-feed.sh <lon> <lat> &` with the same position so the bearings come out as asked.
#   many   thirty targets 12 degrees apart from 5, in the hues the app gives the first thirty waypoints; the
#          thirtieth is at 77, the bearing of the seventh.
#   none   no waypoints at all.
# The rows go into the app's own database file with the app stopped, and the location permission is granted.
# It refuses to run on anything but an emulator.
# Rows are named Waypoint 1, 2, ... in the order given and the last one is the newest, so it has the focus unless
# the focus stored in the app's settings names another row that exists. The settings are not touched, so the
# number the app gives its next waypoint does not follow these names.
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
    specs = [f"{5 + i * 12}:{(i * 137.508) % 360:.2f}" for i in range(29)] + [f"77:{(29 * 137.508) % 360:.2f}"]
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
count=$($ADB shell "run-as $PKG sqlite3 databases/waypoints.db \"$sql SELECT count(*) FROM waypoints;\"" | tr -d '\r')
if [[ "$count" != <-> ]]; then
  echo "could not write the waypoints (has the app been started once, so its database exists?): $count" >&2
  exit 1
fi
$ADB shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
$ADB shell am start -n $PKG/.MainActivity > /dev/null
echo "$count targets around $at; app started"
