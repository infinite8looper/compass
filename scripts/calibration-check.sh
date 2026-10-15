#!/bin/zsh
# Usage: scripts/calibration-check.sh
# Checks the calibration screen's ring against fields set through the emulator's sensor console (v3 spec,
# "Calibration": a segment lights when the measured field has pointed into its region of directions). It starts the
# app, sets the field the watch sees lying flat facing north, opens calibration from the settings, and reads the
# share covered under `Covered` (scripts/ui-dump.sh). Then it sets, one after another, the fields of the fourteen
# poses CalibrationTest records (an earth field of (0, 20, -45) microtesla seen from the watch lying flat facing
# each way, face down, upright, rolled and tilted), and reads the share again after each. Nothing wakes the screen
# once calibration is open (calibration keeps it on): after each pose the power manager's wakefulness is read
# (`dumpsys power`), and the check fails if it is anything but Awake.
# Then a field of 92 microtesla, which no earth field has, pointing into a region none of the poses did: the segment
# lights all the same (the spec: a segment "lights when the field has been there") and the warning shows.
# Passes when the screen stayed Awake throughout, the share never goes down, ends at 50% (the 12 regions of 24 those poses point into, as
# CalibrationTest works out), and the 92 microtesla field takes it to 54% (13 of 24) with the warning. At the end it lays the watch flat at a magnetic
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
# The power manager's wakefulness (Awake, Dozing, Asleep, Dreaming), logged and checked after each change of pose.
awake() {
  local w=$($ADB shell dumpsys power | sed -n 's/^ *mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1)
  echo "  wakefulness: ${w:-unknown}"
  if [ "$w" != "Awake" ]; then bad=1; echo "  the screen left Awake ($w) while calibration was open" >&2; fi
}
# The fields CalibrationTest records, in microtesla, in the watch's frame.
for f in 0:20:-45 -20:0:-45 0:-20:-45 20:0:-45 0:20:45 20:0:45 0:-45:-20 -20:-45:0 0:-45:20 20:-45:0 45:20:0 -45:20:0 -14.142:-21.82:-41.82 23.157:-41.82:-11.825; do
  $ADB emu sensor set magnetic-field $f > /dev/null
  sleep 1.5
  now=$(share)
  echo "field $f: ${now:-none}"
  awake
  if [ -z "$now" ] || [ ${now%\%} -lt ${last%\%} ]; then bad=1; echo "  the share went down or could not be read" >&2; fi
  last=$now
done
if [ "$last" != "50%" ]; then echo "ended at $last, not 50%" >&2; bad=1; fi
# 92 microtesla through the back of the watch toward its upper right: region 1, which no pose above reached.
$ADB emu sensor set magnetic-field 46:46:-65 > /dev/null
sleep 2
now=$(share)
warned=$(scripts/ui-dump.sh 2>/dev/null | grep -c "Magnetic interference nearby")
echo "field 46:46:-65 (92 microtesla): ${now:-none}, warning shown: $warned"
awake
if [ "$now" != "54%" ] || [ "$warned" -lt 1 ]; then bad=1; echo "  the 92 microtesla field did not light its segment (54%), or no warning" >&2; fi
$ADB shell input keyevent KEYCODE_BACK
finish
if [ $bad -eq 0 ]; then echo "passed"; else echo "FAILED"; fi
exit $bad
