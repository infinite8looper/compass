#!/bin/zsh
# Usage: scripts/device-tests.sh <out-dir> <seconds> [-e <name> <value> ...]
# Runs instrumented tests on one emulator without Gradle, from APKs already built (./gradlew assembleDebug
# assembleDebugAndroidTest): app/build/outputs/apk/debug/app-debug.apk and
# app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk. The arguments after the first two are given to
# the test runner as they are (for example `-e class com.infinite8looper.compass.A,com.infinite8looper.compass.B#m`
# or `-e notAnnotation com.infinite8looper.compass.TakesMinutes`). scripts/quick-tests.sh and scripts/all-suites.sh
# run it; it can be run by hand too.
# The emulator: the one named by EMULATOR_ONLY_SERIAL (others may be running), or else the one attached
# (scripts/emulator-only.sh). Every script it starts addresses that emulator alone.
# In order, it:
#   - lays the virtual watch flat at a magnetic heading of 40 (scripts/set-heading.sh), reads the pose back from the
#     emulator (`adb emu sensor get acceleration`) and does not run unless it is flat; wakes the screen and does not
#     run unless the watch says it is awake (the API 33 and 37 images put the screen to sleep when the pose changes);
#   - looks again that the emulator is attached and that no device that is not an emulator is;
#   - uninstalls the app and the test app, installs both (`adb install -r -t`, the way Gradle's connected tests
#     install them: no runtime permission is granted at install), and sets the three animation scales
#     (window_animation_scale, transition_animation_scale, animator_duration_scale) to 0, as Gradle does for
#     `animationsDisabled = true` (app/build.gradle.kts), noting what they were;
#   - lists the tests the run would run (the same arguments and `-e log true`, which runs none), into
#     <out-dir>/listing.txt;
#   - starts scripts/geo-feed.sh at 43.7022,-72.2896 and scripts/field-requests.sh for this emulator;
#   - looks again that the emulator is attached and that no device that is not an emulator is, then runs
#     `adb shell am instrument -r -w <arguments> -e additionalTestOutputDir
#     /sdcard/Android/media/com.infinite8looper.compass/additional_test_output
#     com.infinite8looper.compass.test/androidx.test.runner.AndroidJUnitRunner` (the command Gradle's connected
#     tests send, seen on the API 37 emulator on 2026-10-16), its output with the host's time on each line into
#     <out-dir>/instrument.txt. After the given number of seconds the run is stopped (the app is force-stopped,
#     which ends the instrumentation) and counts as failed;
#   - stops the feed and the relay, puts the three animation scales back as they were, uninstalls both apps (Gradle
#     does too, so the tests' data does not stay on the emulator);
#   - writes <out-dir>/TEST-<avd>.xml (JUnit XML, readable by `scripts/test-counts.sh device <out-dir>`) and prints
#     each test: PASS, FAIL with the first lines of its failure, SKIP, or NOT RUN (named by the listing, with no
#     result), by scripts/instrument-results.py.
# Exit status 0 if the run ended with its own result and every test it listed passed; 1 otherwise (a failed,
# skipped or not run test, a run cut short or past its deadline, a watch not flat or not awake, a refused check).
# Emulators only (scripts/emulator-only.sh). Needs python3.
source "$(dirname "$0")/emulator-only.sh" || exit 1
emulator_pinned || exit 1      # and an emulator's serial to address: no adb call below goes out unpinned
ADB=~/Library/Android/sdk/platform-tools/adb
cd "$(dirname "$0")/.." || exit 1
if [ -z "$2" ]; then echo "usage: scripts/device-tests.sh <out-dir> <seconds> [-e <name> <value> ...]" >&2; exit 1; fi
out=$1; seconds=$2; shift 2
APP=app/build/outputs/apk/debug/app-debug.apk
TESTAPK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
PKG=com.infinite8looper.compass
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
for f in $APP $TESTAPK; do
  if [ ! -f $f ]; then echo "no $f: build with ./gradlew assembleDebug assembleDebugAndroidTest" >&2; exit 1; fi
done
# Every script started from here addresses this emulator and no other.
export EMULATOR_ONLY_SERIAL=$ANDROID_SERIAL
avd=$($ADB shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')
[ -n "$avd" ] || avd=$ANDROID_SERIAL
mkdir -p $out || exit 1
rm -f $out/listing.txt $out/instrument.txt; find $out -name "TEST-*.xml" -delete

scripts/set-heading.sh 40 > /dev/null; posed=$?
# Flat is gravity along the watch's z axis and next to none along x and y.
pose=$($ADB emu sensor get acceleration 2>/dev/null | tr -d '\r' | sed -n 's/^acceleration = //p' | head -1)
if [ $posed -ne 0 ] || ! echo "$pose" | awk -F: 'NF == 3 && $1 > -1 && $1 < 1 && $2 > -1 && $2 < 1 && $3 > 9 { ok = 1 } END { exit !ok }'; then
  echo "$avd: the watch could not be laid flat (set-heading.sh exit status $posed, acceleration read back: ${pose:-none}), not run"
  exit 1
fi
awake=""
for try in 1 2 3 4 5; do
  sleep 3; $ADB shell input keyevent KEYCODE_WAKEUP; sleep 2
  awake=$($ADB shell dumpsys power | tr -d '\r' | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1)
  [ "$awake" = "Awake" ] && break
done
if [ "$awake" != "Awake" ]; then
  echo "$avd: the screen would not stay awake after the watch was laid flat ($awake), not run"
  exit 1
fi
emulator_pinned || { echo "$avd: not run"; exit 1; }

$ADB uninstall $PKG.test > /dev/null 2>&1
$ADB uninstall $PKG > /dev/null 2>&1
$ADB install -r -t $APP | tail -1 | grep -q '^Success' || { echo "$avd: the app could not be installed, not run"; exit 1; }
$ADB install -r -t $TESTAPK | tail -1 | grep -q '^Success' || { echo "$avd: the test app could not be installed, not run"; $ADB uninstall $PKG > /dev/null 2>&1; exit 1; }
$ADB shell mkdir -p /sdcard/Android/media/$PKG/additional_test_output
scales=(window_animation_scale transition_animation_scale animator_duration_scale)
typeset -A was
for s in $scales; do
  was[$s]=$($ADB shell settings get global $s | tr -d '\r')
  $ADB shell settings put global $s 0
done

feed_pid=""; fields_pid=""; run_pid=""
finish() {
  if [ -n "$run_pid" ]; then kill $run_pid 2>/dev/null; $ADB shell am force-stop $PKG > /dev/null 2>&1; wait $run_pid 2>/dev/null; run_pid=""; fi
  if [ -n "$feed_pid" ]; then kill $feed_pid 2>/dev/null; wait $feed_pid 2>/dev/null; feed_pid=""; fi
  if [ -n "$fields_pid" ]; then kill $fields_pid 2>/dev/null; wait $fields_pid 2>/dev/null; fields_pid=""; fi
  for s in $scales; do
    if [ -z "${was[$s]}" ] || [ "${was[$s]}" = "null" ]; then $ADB shell settings delete global $s > /dev/null
    else $ADB shell settings put global $s ${was[$s]}; fi
  done
  $ADB uninstall $PKG.test > /dev/null 2>&1
  $ADB uninstall $PKG > /dev/null 2>&1
}
trap 'finish; exit 1' INT TERM

$ADB shell am instrument -r -w "$@" -e log true $RUNNER 2>&1 | python3 -I scripts/instrument-results.py stamp > $out/listing.txt

scripts/geo-feed.sh -72.2896 43.7022 $(( seconds + 120 )) > /dev/null 2>&1 &
feed_pid=$!
scripts/field-requests.sh $(( seconds + 120 )) > /dev/null 2>&1 &
fields_pid=$!
# Looked at again at the last moment: the tests wipe the app's data and must run on this emulator alone.
if ! emulator_pinned; then finish; echo "$avd: not run"; exit 1; fi
echo "$avd: running on $ANDROID_SERIAL"
started=$(date +%s)
$ADB shell am instrument -r -w "$@" -e additionalTestOutputDir /sdcard/Android/media/$PKG/additional_test_output $RUNNER 2>&1 \
  | python3 -I scripts/instrument-results.py stamp > $out/instrument.txt &
run_pid=$!
deadline=$(( started + seconds ))
timed_out=0
while kill -0 $run_pid 2>/dev/null; do
  if [ $(date +%s) -gt $deadline ]; then timed_out=1; break; fi
  sleep 5
done
if [ $timed_out -eq 0 ]; then wait $run_pid; run_pid=""; fi
finish
trap - INT TERM
echo "$avd: the run took $(( $(date +%s) - started )) s"
python3 -I scripts/instrument-results.py report $out/instrument.txt "$out/TEST-$avd.xml" "$ANDROID_SERIAL" $out/listing.txt; result=$?
if [ $timed_out -eq 1 ]; then echo "$avd: the run did not finish in $seconds s and was stopped"; result=1; fi
exit $result
