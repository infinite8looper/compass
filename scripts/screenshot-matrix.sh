#!/bin/zsh
# Usage: scripts/screenshot-matrix.sh <suffix> [section ...]        (suffix: large, small, ...)
# Drives the debug app on the attached emulator through its screens and states and writes one screenshot of each to
# docs/screenshots/<state>-<suffix>.png (MATRIX_OUT names another folder). With no section it runs them all, in
# this order; each section sets up what it needs, so any one can be run by itself:
#   permission   the location dialog; `Needs access to precise location` on the compass, the waypoints band and the
#                settings row (only approximate location granted); the compass before any position (`MAG`)
#   compass      clean; targets; facing; magnetic north and meters; eight colors of the ring; thirty targets;
#                `No target`; `Arrived at target`; an old distance; `Location is off`; `Locating…`
#   moving       hold-flat without and with a target (four frames each); a name sliding on the compass, in the list
#                and on the edit screen (several frames each)
#   log          `Saving…`, `Saved Waypoint N`, `No GPS fix`, `Location is off`, `Couldn't save`
#   coords       coordinates with no target, with a target, old, and before the first fix
#   trail        a crumb saved, laying, the trail's row, retracing, facing the crumb, `Hold to clear`, `Trail complete`
#   waypoints    empty, a few, thirty (top and end), old distances, the status band after `Log here`
#   edit         a short name, the color being dragged (finger down), `Hold to delete` half way
#   settings     top, middle, end; every switch off
#   calibration  normal; interference
#   panes        the three screens side by side; the row half way through a swipe; the compass's three ticks
# It wipes the app's data (pm clear), puts rows in its database with the device's sqlite3, feeds positions
# (scripts/geo-feed.sh), sets the virtual sensors, switches the emulator's location setting off and on again, and
# for `Couldn't save` makes the database file read-only for one press. Emulators only; it refuses anything else.
# The image must have sqlite3 and `input motionevent` (the API 34 and 35 images do).
# Pictures of something that moves are several frames side by side, first on the left.
# A status that shows for two or three seconds is caught by taking pictures until its color is in the readout; if
# it never is, the last picture is kept and the script says so. Every picture has to be looked at afterwards.
# Needs python3 with Pillow, ImageMagick (`magick`) and the scripts beside it. Takes about 25 minutes for all sections.
# It leaves the app's data wiped, the app in front, location on, the watch flat and no position feed running.
ADB=~/Library/Android/sdk/platform-tools/adb
PKG=com.infinite8looper.compass
cd "$(dirname "$0")/.." || exit 1
S=scripts
if [ -z "$1" ]; then echo "usage: scripts/screenshot-matrix.sh <suffix> [section ...]" >&2; exit 1; fi
SUF=$1; shift
OUT=${MATRIX_OUT:-docs/screenshots}
mkdir -p "$OUT"
serial=$($ADB get-serialno 2>/dev/null | tr -d '\r')
qemu="$($ADB shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')$($ADB shell getprop ro.boot.qemu 2>/dev/null | tr -d '\r')"
if [[ "$serial" != emulator-* ]] || [[ "$qemu" != *1* ]]; then
  echo "refusing: the attached device (${serial:-none, or more than one}) is not an emulator, and this wipes the app's data" >&2; exit 1
fi
size=$($ADB shell wm size | tr -d '\r' | sed -n 's/^Physical size: //p')
W=${size%x*}; H=${size#*x}; C=$((W / 2))
HERE_LAT=43.7022; HERE_LON=-72.2896
GREEN='#7FD99A'; RED='#FF3B30'      # the confirming green and the error color where the watch gives no scheme
tmp=$(mktemp -d) || exit 1
trap 'pkill -f geo-feed.sh; rm -rf "$tmp"' EXIT

# ---- small tools -------------------------------------------------------------------------------------------
shot() {   # shot <state>: one picture
  $ADB exec-out "screencap -p 2>/dev/null" > "$tmp/shot.png"
  if [ "$(head -c 8 "$tmp/shot.png" | xxd -p)" != "89504e470d0a1a0a" ]; then echo "NO PICTURE for $1" >&2; return 1; fi
  cp "$tmp/shot.png" "$OUT/$1-$SUF.png" && echo "  $1-$SUF.png"
}
frames() {  # frames <state> <count> <gap-seconds>: several pictures side by side
  local a=()
  for i in $(seq 1 $2); do $ADB exec-out "screencap -p 2>/dev/null" > "$tmp/f$i.png"; a+=("$tmp/f$i.png"); [ $i -lt $2 ] && sleep $3; done
  magick "${a[@]}" +append "$OUT/$1-$SUF.png" && echo "  $1-$SUF.png ($2 frames)"
}
join() {    # join <state> <picture> <picture> ...: pictures already taken, side by side
  local out=$1; shift; magick "$@" +append "$OUT/$out-$SUF.png" && echo "  $out-$SUF.png ($# joined)"
}
colored() { # colored <png> <color> <top%> <bottom%>: does the middle of the picture, between two heights, hold that color?
  python3 -c '
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB"); w, h = im.size
want = tuple(int(sys.argv[2][i:i + 2], 16) for i in (1, 3, 5))
box = im.crop((w * 20 // 100, h * int(sys.argv[3]) // 100, w * 80 // 100, h * int(sys.argv[4]) // 100))
n = sum(1 for p in box.getdata() if max(abs(p[k] - want[k]) for k in range(3)) <= 10)
sys.exit(0 if n >= 25 else 1)' "$1" "$2" "$3" "$4"
}
shot_when() { # shot_when <state> <color> <top%> <bottom%> [tries=24]: pictures until the color shows there
  for i in $(seq 1 ${5:-24}); do
    $ADB exec-out "screencap -p 2>/dev/null" > "$tmp/w.png"
    if colored "$tmp/w.png" "$2" $3 $4; then cp "$tmp/w.png" "$OUT/$1-$SUF.png"; echo "  $1-$SUF.png"; return 0; fi
    sleep 0.15
  done
  cp "$tmp/w.png" "$OUT/$1-$SUF.png"; echo "  $1-$SUF.png  NOT CAUGHT: the color $2 never showed; the last picture was kept" >&2; return 1
}
dump() { $ADB exec-out uiautomator dump /dev/tty 2>/dev/null; }
row() {     # row <text>: "x y checked" of the thing with that text (checked is its row's)
  dump | python3 -c '
import re, sys
import xml.etree.ElementTree as ET
doc = sys.stdin.read()
if "<hierarchy" not in doc: sys.exit(1)
root = ET.fromstring(doc[doc.index("<hierarchy"):doc.rindex("</hierarchy>") + len("</hierarchy>")])
parent = {c: p for p in root.iter() for c in p}
for n in root.iter("node"):
    if n.get("text") == sys.argv[1]:
        l, t, r, b = map(int, re.findall(r"-?\d+", n.get("bounds")))
        p = parent[n]
        while p.get("checkable") != "true" and p.get("clickable") != "true" and p in parent: p = parent[p]
        print((l + r) // 2, (t + b) // 2, p.get("checked"))
        break
' "$1"
}
shown() { dump | python3 -c 'import re,sys; m=re.findall(r"text=\"(-?\d+)°\"", sys.stdin.read()); print(m[0] if m else "?")'; }
tap() { local r=($(row "$1")); [ -n "$r" ] || { echo "no '$1' on screen" >&2; return 1; }; $ADB shell input tap $r[1] $r[2]; }
hold() { local r=($(row "$1")); [ -n "$r" ] || { echo "no '$1' on screen" >&2; return 1; }; $ADB shell input swipe $r[1] $r[2] $r[1] $r[2] $2; }
find_row() { # scrolls the list from its top until the row with that text is near the middle
  for k in 1 2 3; do $ADB shell input swipe $C $((H * 35 / 100)) $C $((H * 85 / 100)) 150; done; sleep 1
  for i in $(seq 1 14); do
    local r=($(row "$1"))
    if [ -n "$r" ] && [ $r[2] -gt $((H * 36 / 100)) ] && [ $r[2] -lt $((H * 57 / 100)) ]; then echo $r; return 0; fi
    $ADB shell input swipe $C $((H * 60 / 100)) $C $((H * 42 / 100)) 600; sleep 0.8
  done
  echo "no row '$1'" >&2; return 1
}
setting() { # setting <label> on|off, on the settings screen
  local r=($(find_row "$1")) || return 1
  local want=true; [ "$2" = off ] && want=false
  if [ "$r[3]" != "$want" ]; then $ADB shell input tap $r[1] $r[2]; sleep 1; fi
}
swipe_to() { # swipe_to left|right: the finger's direction; left brings in the screen on the right
  if [ $1 = left ]; then $S/raw-swipe.sh line $((W * 84 / 100)) $C $((W * 22 / 100)) $C 10; else $S/raw-swipe.sh line $((W * 22 / 100)) $C $((W * 84 / 100)) $C 10; fi
  sleep 1.5
}
# From the compass: settings is a finger to the right, waypoints a finger to the left; and back the other way.
to_settings() { swipe_to right; }; from_settings() { swipe_to left; }
to_waypoints() { swipe_to left; }; from_waypoints() { swipe_to right; }
leave_route() { $ADB shell input keyevent KEYCODE_BACK; sleep 1.5; }      # edit and calibration
press() { $ADB shell input keyevent KEYCODE_STEM_1; }
double_press() { $ADB shell "input keyevent KEYCODE_STEM_1; input keyevent KEYCODE_STEM_1"; }
flat() { $ADB emu sensor set acceleration 0:0:9.81 > /dev/null; }
upright() { $ADB emu sensor set acceleration 0:9.81:0 > /dev/null; }
magnetic() { flat; $ADB emu sensor set magnetic-field "$(awk -v h="$1" 'BEGIN { r = h * atan2(0, -1) / 180; printf "%.3f:%.3f:-45", -20 * sin(r), 20 * cos(r) }')" > /dev/null; }
face() {    # face <heading>: turns the virtual watch until the compass shows that heading (the fusion is slow, and drifts)
  local want=$1 set=$(( ($1 + ${2:-14} + 360) % 360 )); magnetic $set; sleep 24
  for i in 1 2 3 4; do
    local h=$(shown); [ "$h" = "$want" ] && return 0
    [[ "$h" == <-> ]] || { sleep 3; continue; }
    set=$(( (set + (want - h + 540) % 360 - 180 + 360) % 360 )); magnetic $set; sleep 14
  done
  echo "the compass shows $(shown), not $want" >&2
}
feed() { pkill -f geo-feed.sh; ($S/geo-feed.sh $2 $1 4000 > /dev/null 2>&1 &); }      # feed <lat> <lon>
location() { $ADB shell settings put secure location_mode $([ $1 = on ] && echo 3 || echo 0); }
app_start() { $ADB shell am start -n $PKG/.MainActivity > /dev/null 2>&1; }
app_stop() { $ADB shell am force-stop $PKG; }
home() { $ADB shell input keyevent KEYCODE_HOME; sleep 1; }
sql() { $ADB shell "run-as $PKG sqlite3 databases/waypoints.db \"$1\""; }
fresh() {   # fresh [granted]: wiped data, the app started once; with `granted` the precise location permission too
  location on; app_stop; $ADB shell pm clear $PKG > /dev/null
  [ "$1" = granted ] && $ADB shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
  $ADB shell input keyevent KEYCODE_WAKEUP; flat; app_start; sleep 4
}
seed() { $S/seed-targets.sh "$@" > /dev/null || { echo "seeding failed: $*" >&2; return 1; }; sleep 7; }
THREE=(60:137.5:400 200:275:1500 300:52.5:5000)
LONG='Trailhead parking lot, north entrance by the old sugar maple'
long_names() { app_stop; sleep 1; sql "UPDATE waypoints SET name='$LONG' WHERE name='Waypoint 3'; UPDATE waypoints SET name='Camp' WHERE name='Waypoint 1'; UPDATE waypoints SET name='Summit of Mount Moosilauke via Gorge Brook' WHERE name='Waypoint 2';"; app_start; sleep 6; }

dialog_choice() { # scrolls the system's permission dialog until the choice is well on screen, and taps it
  for i in 1 2 3 4; do
    local r=($(row "$1"))
    if [ -n "$r" ] && [ $r[2] -lt $((H * 80 / 100)) ]; then $ADB shell input tap $r[1] $r[2]; return 0; fi
    $ADB shell input swipe $C $((H * 70 / 100)) $C $((H * 40 / 100)) 400; sleep 1
  done
  echo "no choice '$1' in a dialog" >&2; return 1
}

# ---- sections ----------------------------------------------------------------------------------------------
sec_permission() {
  pkill -f geo-feed.sh; fresh; magnetic 40; sleep 22
  shot compass-mag
  press; sleep 2.5; shot permission-dialog
  tap "Use precise location"; sleep 1
  dialog_choice "While using app"
  shot_when log-needs-access $RED 50 72
  sleep 3; to_waypoints; tap "Log here"; sleep 2.5        # the system now offers to change approximate to precise
  dialog_choice "Keep approximate location"
  shot_when waypoints-needs-access $RED 8 32
  sleep 3; from_waypoints; to_settings
  local r=($(find_row "Show coordinates")); $ADB shell input tap $r[1] $r[2]; sleep 2.5
  dialog_choice "Keep approximate location" 2> /dev/null      # asked once more on some images
  sleep 1.5; find_row "Show coordinates" > /dev/null; shot settings-needs-access
  from_settings
}
sec_compass() {
  fresh granted; feed $HERE_LAT $HERE_LON; sleep 8
  seed $THREE; face 290; shot compass-targets
  face 300; sleep 1; shot compass-facing
  to_settings; setting "Feet and miles" off; setting "True north" off; from_settings; sleep 10; shot compass-targets-mag-metric
  to_settings; setting "True north" on; from_settings
  seed 0:0:900 45:45:900 90:90:900 135:135:900 180:180:900 225:225:900 270:270:900 315:315:900; face 20; shot compass-ring8
  seed many; shot compass-many
  seed 0:0:4; shot compass-arrived
  seed none; shot compass-clean          # true north: a position is known from the targets before
  seed $THREE; to_waypoints; tap "3 of 3 targets"; sleep 1; from_waypoints; sleep 1; shot compass-no-target
  to_waypoints; tap "0 of 3 targets"; sleep 1; from_waypoints; sleep 3
  location off; sleep 25; shot compass-stale
  app_stop; app_start; sleep 4; shot compass-location-off
  location on; home; app_stop; app_start; sleep 1.2; shot compass-locating; sleep 6
}
sec_moving() {
  fresh granted; feed $HERE_LAT $HERE_LON; sleep 8; magnetic 44; sleep 15
  seed none; upright; sleep 3; frames holdflat-frames 4 0.45; flat; sleep 4
  seed $THREE; upright; sleep 3; frames holdflat-target-frames 4 0.45; flat; sleep 5
  long_names; sleep 1; frames name-sliding-compass 4 1.7
  to_waypoints; sleep 1; frames name-sliding-list 3 2.2
  hold "$LONG" 900 || hold "Log here" 1; sleep 2; frames name-sliding-edit 3 2.2
  leave_route; from_waypoints
}
sec_log() {
  fresh granted; feed $HERE_LAT $HERE_LON; sleep 8; magnetic 44
  seed $THREE
  app_stop; app_start; sleep 1.2; press; sleep 0.9; shot log-saving          # before the session's first fix
  shot_when log-saved $GREEN 50 72 40
  for try in 1 2 3 4; do      # a press followed at once by Home, with location off while the app is off screen:
    sleep 4                   # no position reaches the save in its 20 seconds, and its result waits for the return
    $ADB shell "input keyevent KEYCODE_STEM_1 KEYCODE_HOME; settings put secure location_mode 0"; sleep 23
    location on; sleep 1; app_start; shot_when log-no-gps-fix $RED 50 72 2> /dev/null && break
    [ $try = 4 ] && echo "  log-no-gps-fix-$SUF.png  NOT CAUGHT in four tries (a fix reached the app before it left the screen each time)" >&2
  done
  sleep 4; location off; home; app_start; sleep 2; press; shot_when log-location-off $RED 50 72
  location on; sleep 4; app_stop; $ADB shell run-as $PKG chmod 440 databases/waypoints.db; app_start; sleep 8
  press; shot_when log-couldnt-save $RED 50 72 60
  app_stop; $ADB shell run-as $PKG chmod 660 databases/waypoints.db; app_start; sleep 3
}
sec_coords() {
  fresh granted; feed $HERE_LAT $HERE_LON; sleep 6
  to_settings; setting "Show coordinates" on; from_settings
  seed none; face 127; shot coords-clean
  location off; sleep 25; shot coords-clean-old; location on; home; app_start; sleep 6
  seed $THREE; shot coords-target
  location off; sleep 2; location on; home; app_stop; app_start; sleep 1.2; shot coords-locating; sleep 5
}
sec_trail() {
  fresh granted; feed $HERE_LAT $HERE_LON; sleep 6
  to_settings; setting "Breadcrumb trail" on; setting "Feet and miles" off; from_settings
  seed 60:137.5:400; face 160
  double_press; sleep 2; press; shot_when trail-crumb-saved $GREEN 50 72 40; sleep 4
  feed 43.70274 $HERE_LON; sleep 8; press; sleep 5
  feed 43.70328 $HERE_LON; sleep 8; press; sleep 5
  feed 43.70420 $HERE_LON; sleep 9; shot trail-laying
  to_waypoints; shot trail-row-laying; from_waypoints
  double_press; sleep 2.5; shot trail-retracing
  face 180; sleep 1; shot trail-facing
  to_waypoints; shot trail-row-retracing
  local r=($(row "Trail")); ($ADB shell input swipe $r[1] $r[2] $r[1] $r[2] 1300 &); sleep 1.25; shot trail-hold-to-clear; sleep 1.5
  from_waypoints
  feed 43.70328 $HERE_LON; sleep 10; feed 43.70274 $HERE_LON; sleep 10; shot trail-crumb-1
  feed $HERE_LAT $HERE_LON; shot_when trail-complete $GREEN 50 72 60
  sleep 3
}
sec_waypoints() {
  fresh granted; feed $HERE_LAT $HERE_LON; sleep 6
  to_settings; setting "Feet and miles" off; from_settings
  to_waypoints; shot waypoints-empty; from_waypoints
  seed $THREE; long_names; to_waypoints; $ADB shell input swipe $C $((H * 62 / 100)) $C $((H * 42 / 100)) 600; sleep 1
  tap "Summit of Mount Moosilauke via Gorge Brook"; sleep 1.5; shot waypoints-few; from_waypoints
  seed many; to_waypoints; shot waypoints-many
  for i in $(seq 1 12); do $ADB shell input swipe $C $((H * 80 / 100)) $C $((H * 15 / 100)) 120; done; sleep 2; shot waypoints-many-end
  for k in 1 2 3 4 5 6 7 8 9 10 11 12; do $ADB shell input swipe $C $((H * 20 / 100)) $C $((H * 85 / 100)) 120; done; sleep 1.5
  location off; sleep 25; shot waypoints-old; location on; from_waypoints; home; app_start; sleep 6
  to_waypoints; tap "Log here"; shot_when waypoints-saved-band $GREEN 8 32 40; sleep 3; from_waypoints
}
sec_edit() {
  fresh granted; feed $HERE_LAT $HERE_LON; sleep 6
  seed $THREE; long_names; to_waypoints
  local r=($(find_row "Camp")); $ADB shell input swipe $r[1] $r[2] $r[1] $r[2] 900; sleep 2; shot edit-short
  # a finger put down on the ring near 4 o'clock and dragged back to past 1 o'clock, still down when the picture is taken
  local pts=($(python3 -c "
import math
c = $C; print(' '.join(f'{c + 0.93 * c * math.sin(math.radians(a)):.0f},{c - 0.93 * c * math.cos(math.radians(a)):.0f}' for a in range(115, 39, -15)))"))
  $ADB shell input motionevent DOWN ${pts[1]/,/ }
  for p in ${pts[2,-1]}; do $ADB shell input motionevent MOVE ${p/,/ }; done
  shot edit-recolor
  $ADB shell input motionevent UP ${pts[-1]/,/ }; sleep 1
  r=($(row "Hold to delete")); ($ADB shell input swipe $r[1] $r[2] $r[1] $r[2] 800 &); sleep 0.75; shot edit-hold; sleep 1.5
  leave_route; from_waypoints
}
sec_settings() {
  fresh granted; to_settings
  for k in 1 2 3; do $ADB shell input swipe $C $((H * 35 / 100)) $C $((H * 85 / 100)) 150; done; sleep 1; shot settings-top
  find_row "Blind navigation" > /dev/null; shot settings-middle
  find_row "Calibrate" > /dev/null; shot settings-end
  for s in "True north" "Feet and miles" "Tick feedback" "Blind navigation" "Breadcrumb trail" "Show coordinates" "Back up waypoints"; do setting "$s" off; done
  for k in 1 2 3; do $ADB shell input swipe $C $((H * 35 / 100)) $C $((H * 85 / 100)) 150; done; sleep 1
  $ADB exec-out "screencap -p 2>/dev/null" > "$tmp/o1.png"
  find_row "Blind navigation" > /dev/null; $ADB exec-out "screencap -p 2>/dev/null" > "$tmp/o2.png"
  find_row "Back up waypoints" > /dev/null; $ADB exec-out "screencap -p 2>/dev/null" > "$tmp/o3.png"
  join settings-all-off "$tmp/o1.png" "$tmp/o2.png" "$tmp/o3.png"
  from_settings
}
sec_calibration() {
  fresh granted; magnetic 40; sleep 8; to_settings
  local r=($(find_row "Calibrate")); $ADB shell input tap $r[1] $r[2]; sleep 3; shot calibrate
  $ADB emu sensor set magnetic-field 0:80:-45 > /dev/null; sleep 3; shot calibrate-interference
  magnetic 40; sleep 2; leave_route; from_settings
}
sec_panes() {
  fresh granted; feed $HERE_LAT $HERE_LON; sleep 6; seed $THREE; face 20
  $ADB exec-out "screencap -p 2>/dev/null" > "$tmp/p2.png"
  to_settings; $ADB exec-out "screencap -p 2>/dev/null" > "$tmp/p1.png"; from_settings
  to_waypoints; $ADB exec-out "screencap -p 2>/dev/null" > "$tmp/p3.png"; from_waypoints
  join panes "$tmp/p1.png" "$tmp/p2.png" "$tmp/p3.png"
  # a finger that has dragged the row a third of the way toward waypoints and is still down
  $ADB shell input motionevent DOWN $((W * 80 / 100)) $C; $ADB shell input motionevent MOVE $((W * 65 / 100)) $C; $ADB shell input motionevent MOVE $((W * 45 / 100)) $C
  shot pane-swipe
  $ADB shell input motionevent MOVE $((W * 70 / 100)) $C; $ADB shell input motionevent UP $((W * 70 / 100)) $C; sleep 0.2
  shot compass-pane-ticks        # the row has sprung back to the compass; its three ticks show for about a second
  sleep 2
}

ALL=(permission compass moving log coords trail waypoints edit settings calibration panes)
[ $# -eq 0 ] && set -- $ALL
for s in "$@"; do
  if [[ ${ALL[(i)$s]} -gt ${#ALL} ]]; then echo "no section '$s' (sections: $ALL)" >&2; exit 1; fi
done
for s in "$@"; do echo "== $s"; sec_$s; done
pkill -f geo-feed.sh; location on; flat; fresh > /dev/null
echo "done: pictures in $OUT, app data wiped"
