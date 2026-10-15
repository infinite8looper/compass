#!/bin/zsh
# Usage: scripts/calibration-check.sh
# Checks the calibration screen's ring against fields set through the emulator's sensor console (v3 spec,
# "Calibration": a segment lights when the measured field has pointed into its region of directions). It starts the
# app, sets the field the watch sees lying flat facing north, opens calibration from the settings, and reads the
# share covered under `Covered` (scripts/ui-dump.sh). Then it sets, one after another, the fields of the fourteen
# poses CalibrationTest records (an earth field of (0, 20, -45) microtesla seen from the watch lying flat facing
# each way, face down, upright, rolled and tilted), waking the screen after each, and reads the share again.
# Then a field of 92 microtesla, which no earth field has: the share must not change and the warning must show.
# Passes when the share never goes down, ends at 50% (the 12 regions of 24 those poses point into, as
# CalibrationTest works out), and the interference adds nothing. At the end it lays the watch flat at a magnetic
# heading of 40 (scripts/set-heading.sh), the pose scripts/all-suites.sh leaves, and goes Back.
# Exit status 0 when it passed, 1 otherwise. Emulators only.
source "$(dirname "$0")/emulator-only.sh" || exit 1
cd "$(dirname "$0")/.." || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
share() { scripts/ui-dump.sh 2>/dev/null | sed -n "s/.*text='\([0-9]\{1,3\}%\)'.*/\1/p" | head -1; }
finish() { $ADB emu sensor set acceleration 0:0:9.81 > /dev/null; scripts/set-heading.sh 40 > /dev/null; $ADB shell input keyevent KEYCODE_WAKEUP; }
$ADB emu sensor set acceleration 0:0:9.81 > /dev/null
$ADB emu sensor set magnetic-field 0:20:-45 > /dev/null
$ADB shell input keyevent KEYCODE_WAKEUP
$ADB shell am start -W -n com.infinite8looper.compass/.MainActivity > /dev/null
sleep 3
# Two of the images put the screen to sleep a moment after a change of pose; the swipe is tried until the settings show.
for i in 1 2 3; do
  $ADB shell input keyevent KEYCODE_WAKEUP; sleep 1
  scripts/swipe.sh right > /dev/null; sleep 2
  scripts/ui-dump.sh 2>/dev/null | grep -q "text='Settings'" && break
done
for i in 1 2 3 4 5 6; do
  scripts/ui-dump.sh 2>/dev/null | grep -q "text='Calibrate'" && break
  scripts/swipe.sh up 300 0.4 > /dev/null; sleep 1
done
scripts/tap-text.sh Calibrate > /dev/null || { echo "the Calibrate row was not found" >&2; finish; exit 1; }
sleep 2
if ! scripts/ui-dump.sh 2>/dev/null | grep -q "text='Covered'"; then echo "calibration did not open" >&2; finish; exit 1; fi
last=$(share); echo "opened: ${last:-none}"
bad=0
# The fields CalibrationTest records, in microtesla, in the watch's frame.
for f in 0:20:-45 -20:0:-45 0:-20:-45 20:0:-45 0:20:45 20:0:45 0:-45:-20 -20:-45:0 0:-45:20 20:-45:0 45:20:0 -45:20:0 -14.142:-21.82:-41.82 23.157:-41.82:-11.825; do
  $ADB emu sensor set magnetic-field $f > /dev/null
  $ADB shell input keyevent KEYCODE_WAKEUP
  sleep 1.5
  now=$(share)
  echo "field $f: ${now:-none}"
  if [ -z "$now" ] || [ ${now%\%} -lt ${last%\%} ]; then bad=1; echo "  the share went down or could not be read" >&2; fi
  last=$now
done
if [ "$last" != "50%" ]; then echo "ended at $last, not 50%" >&2; bad=1; fi
$ADB emu sensor set magnetic-field 0:80:-45 > /dev/null
$ADB shell input keyevent KEYCODE_WAKEUP
sleep 2
now=$(share)
warned=$(scripts/ui-dump.sh 2>/dev/null | grep -c "Magnetic interference nearby")
echo "field 0:80:-45 (92 microtesla): ${now:-none}, warning shown: $warned"
if [ "$now" != "$last" ] || [ "$warned" -lt 1 ]; then bad=1; echo "  the interference changed the share, or no warning" >&2; fi
$ADB shell input keyevent KEYCODE_BACK
finish
if [ $bad -eq 0 ]; then echo "passed"; else echo "FAILED"; fi
exit $bad
