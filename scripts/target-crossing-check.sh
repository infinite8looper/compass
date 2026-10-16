#!/bin/zsh
# Usage: scripts/target-crossing-check.sh [bearing=124.3]
# Turns the attached emulator's virtual watch past a target and checks, in the system's record of vibrations, that
# the target's vibration played (v3 spec, "Vibrations": one crossing gives the strongest kind that applies). The
# default bearing sits 0.3 degrees past the 124 degree line, so in a turn the line is passed just before the target,
# well inside the 40 ms that separates two line taps.
# Steps: starts scripts/geo-feed.sh at 43.7022,-72.2896 for 150 s; puts one target at the bearing with
# scripts/seed-targets.sh (which wipes the waypoints and starts the app); switches `Blind navigation` off in settings
# if it is on (with it on the target gives the lock pulse instead); returns to the compass; points the watch 20 degrees
# short of the bearing (magnetic heading, the declination there taken as -14 degrees), waits 30 s, marks the record,
# turns it to 20 degrees past, waits 30 s, and reads the record with scripts/haptics.sh. Then it turns back and
# reads again. The emulator's sensor fusion sets the speed of the turn.
# Exit status 0 when both turns recorded at least one target vibration, one entry with both its CLICK primitives,
# that finished; 1 when either did not, or when the target could not be put in.
# The feed is stopped before it exits. Emulators only.
source "$(dirname "$0")/emulator-only.sh" || exit 1
cd "$(dirname "$0")/.." || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
bearing=${1:-124.3}
scripts/geo-feed.sh -72.2896 43.7022 150 > /dev/null 2>&1 &
feed=$!
trap 'kill $feed 2>/dev/null; wait $feed 2>/dev/null' EXIT INT TERM
$ADB shell pm grant com.infinite8looper.compass android.permission.ACCESS_FINE_LOCATION
$ADB shell am start -W -n com.infinite8looper.compass/.MainActivity > /dev/null
sleep 3
# The seed's own status is tested: its output is kept, not piped, so a failed seed stops the check here.
seeded=$(scripts/seed-targets.sh "$bearing:200" 2>&1) || { print -r -- "$seeded" >&2; echo "the target could not be put in" >&2; exit 1; }
print -r -- "$seeded" | tail -1
sleep 5
scripts/swipe.sh right > /dev/null; sleep 2
scripts/swipe.sh up 300 0.4 > /dev/null; sleep 1; scripts/swipe.sh up 300 0.4 > /dev/null; sleep 1
row=$(scripts/ui-dump.sh | grep -B1 "text='Blind navigation'" | head -1)
if [ -z "$row" ]; then echo "the Blind navigation row was not found" >&2; exit 1; fi
if echo "$row" | grep -q ",checked,"; then scripts/tap-text.sh "Blind navigation" > /dev/null; sleep 1.5; fi
if scripts/ui-dump.sh | grep -B1 "text='Blind navigation'" | head -1 | grep -q ",checked,"; then
  echo "Blind navigation could not be switched off" >&2; exit 1
fi
scripts/swipe.sh left > /dev/null; sleep 2
from=$(awk -v b="$bearing" 'BEGIN { printf "%.1f", b + 14 - 20 }')
to=$(awk -v b="$bearing" 'BEGIN { printf "%.1f", b + 14 + 20 }')
bad=0
turn() {
  scripts/set-heading.sh "$1" > /dev/null; sleep 30
  local m=$(scripts/haptics.sh mark)
  scripts/set-heading.sh "$2" > /dev/null; sleep 30
  local out=$(scripts/haptics.sh since "$m")
  echo "turn from $1 to $2 (magnetic):"; print -r -- "$out" | tail -1
  # The target's vibration is one entry with both its CLICK primitives (two knocks), played to the end.
  print -r -- "$out" | grep " finished " | awk '{ if (gsub(/Primitive[{=](primitive=)?(PRIMITIVE_)?CLICK/, "") >= 2) found = 1 } END { exit !found }' ||
    { echo "  no target vibration with both its CLICKs finished"; bad=1; }
}
turn $from $to
turn $to $from
exit $bad
