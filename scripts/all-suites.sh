#!/bin/zsh
# Usage: scripts/all-suites.sh [avd ...]
# Runs the instrumented test suite (./gradlew connectedDebugAndroidTest) on each named emulator in turn and prints
# the per-class counts and the totals of each, then the sum over all of them. With no names it runs on
#   Wear_OS_API33, Wear_OS_API34, Wear_OS_Newest (Wear OS 7.0, API 37) and Wear_OS_Large_Round (API 35).
# For each emulator it: switches to it with scripts/switch-emulator.sh (which stops the one that is attached, boots
# the named one and installs the debug app if it has been built), lays the virtual watch flat at a magnetic
# heading of 40 (scripts/set-heading.sh: a booted emulator is upright, where the compass shows its hold-flat hint
# and no heading, and the test of the heading's description would be skipped), reads the pose back from the
# emulator and does not run the suite unless it is flat, wakes the screen and checks that it is awake (two of the
# images put it to sleep when the pose changes), starts scripts/geo-feed.sh at
# 43.7022,-72.2896 (the position some of the tests need, and the one the tests compare against) and
# scripts/field-requests.sh (which passes a test's requests for a magnetic field to the sensor console), runs the
# suite, stops both, and keeps a copy of the result files.
# The tests wipe the app's data and Gradle uninstalls the app when a run ends. Use emulators only.
# It first stops every running scripts/geo-feed.sh, including one started by hand, since a feed left over would
# run against the wrong emulator.
# Output goes to app/build/all-suites/ (ignored by git): suite-<avd>.log (Gradle's output) and results-<avd>/ (the
# result XML, readable with `scripts/test-counts.sh device app/build/all-suites/results-<avd>`).
# Deadlines: the switch has the deadlines of scripts/switch-emulator.sh (90 s for the old emulator to stop, 300 s
# to boot, 120 s for the package manager); the Gradle run has 40 minutes, after which it is stopped and counted as
# failed (ALL_SUITES_GRADLE_SECONDS changes that).
# Two tests run on Wear_OS_Newest (API 37) alone, by the owner's decisions of 2026-10-15 ("API 37 only" and "Add
# it, API 37 only"): CalibrationScreenTest#withNoNewSegmentTheScreenIsLetGoTwoMinutesAfterOpening, which waits about
# two minutes, and CheckScreenTest#theCheckScreenKeepsTheDisplayOnForTwoMinutesFromEachTouchAndTurnOfTheCrown, which
# waits about four. Both carry the annotation TakesMinutes; on every other image the run is given it as `notAnnotation`, so they
# are not run there and not counted (neither passed nor skipped); each such line says so, and the run counts as
# failed if either is in its results. On API 37 the run must have passed both, or the run counts as failed.
# Exit status: 0 if every run succeeded, no test failed and no test was skipped, 1 otherwise: on an emulator
# laid flat no test of this suite is skipped, so a skipped one means a test did not check its subject.
# The last emulator in the list is left running with the debug app installed again, and every emulator the suite
# ran on is left lying flat at the magnetic heading of 40: the pose is not put back to upright.
# Emulators only (scripts/emulator-only.sh): it refuses to start while any device that is not an emulator is attached,
# looks again right before each Gradle run, and gives Gradle the emulator's serial (ANDROID_SERIAL) so the tests run
# on that device alone. A run that is refused counts as not run, and the exit status is 1.
# Needs JDK 17 (found with /usr/libexec/java_home) and python3.
EMULATOR_ONLY_MAY_BE_NONE=1   # this script boots the emulator, so none may be attached yet
source "$(dirname "$0")/emulator-only.sh" || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
cd "$(dirname "$0")/.." || exit 1
if [ -z "$JAVA_HOME" ]; then export JAVA_HOME=$(/usr/libexec/java_home -v 17) || exit 1; fi
avds=("$@")
if [ ${#avds} -eq 0 ]; then avds=(Wear_OS_API33 Wear_OS_API34 Wear_OS_Newest Wear_OS_Large_Round); fi
out=app/build/all-suites
mkdir -p $out || exit 1
gradle_seconds=${ALL_SUITES_GRADLE_SECONDS:-2400}
ONLY_37=(CalibrationScreenTest#withNoNewSegmentTheScreenIsLetGoTwoMinutesAfterOpening CheckScreenTest#theCheckScreenKeepsTheDisplayOnForTwoMinutesFromEachTouchAndTurnOfTheCrown)
feed_pid=""
gradle_pid=""
fields_pid=""
stop_feed() {
  if [ -n "$feed_pid" ]; then kill $feed_pid 2>/dev/null; wait $feed_pid 2>/dev/null; feed_pid=""; fi
  if [ -n "$fields_pid" ]; then kill $fields_pid 2>/dev/null; wait $fields_pid 2>/dev/null; fields_pid=""; fi
}
stop_gradle() { if [ -n "$gradle_pid" ]; then pkill -P $gradle_pid 2>/dev/null; kill $gradle_pid 2>/dev/null; gradle_pid=""; fi; }
trap 'stop_feed; stop_gradle; exit 1' INT TERM
pkill -f geo-feed.sh   # a feed left over from an earlier session would run against the wrong emulator
pkill -f field-requests.sh   # and so would a relay of field requests

bad=0
sum_passed=0; sum_failed=0; sum_skipped=0
summary=()
for avd in $avds; do
  echo "== $avd"
  rm -rf $out/results-$avd $out/suite-$avd.log
  scripts/switch-emulator.sh $avd 2>&1 | tail -3
  # Only the one emulator is addressed from here: by adb, and by Gradle, which takes ANDROID_SERIAL too.
  if ! emulator_only || [ -z "$ANDROID_SERIAL" ]; then
    echo "$avd: not run (the attached devices are not one emulator and nothing else)"
    summary+=("$avd: not run"); bad=1; continue
  fi
  running=$($ADB shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')
  if [ "$running" != "$avd" ]; then
    echo "$avd: not running after the switch, skipped"
    summary+=("$avd: not run"); bad=1; continue
  fi
  sdk=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')
  # A booted emulator's virtual watch is upright, which is the hold-flat state: the compass shows no heading there
  # and the test of the heading's description would be skipped. Laid flat, through the emulator's sensors.
  # The API 33 and Wear OS 7.0 images put the screen to sleep when the pose is changed this way, and there no
  # activity is resumed and no position is delivered: so the screen is woken after it, and the suite is not
  # started unless the watch says it is awake.
  scripts/set-heading.sh 40 > /dev/null; posed=$?
  # The pose as the emulator gives it back: flat is gravity along the watch's z axis and next to none along x and y.
  pose=$($ADB emu sensor get acceleration 2>/dev/null | tr -d '\r' | sed -n 's/^acceleration = //p' | head -1)
  if [ $posed -ne 0 ] || ! echo "$pose" | awk -F: 'NF == 3 && $1 > -1 && $1 < 1 && $2 > -1 && $2 < 1 && $3 > 9 { ok = 1 } END { exit !ok }'; then
    echo "$avd: the watch could not be laid flat (set-heading.sh exit status $posed, acceleration read back: ${pose:-none}), not run"
    summary+=("$avd: not run"); bad=1; continue
  fi
  awake=""
  for try in 1 2 3 4 5; do
    sleep 3; $ADB shell input keyevent KEYCODE_WAKEUP; sleep 2
    awake=$($ADB shell dumpsys power | tr -d '\r' | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1)
    [ "$awake" = "Awake" ] && break
  done
  if [ "$awake" != "Awake" ]; then
    echo "$avd: the screen would not stay awake after the watch was laid flat ($awake), not run"
    summary+=("$avd: not run"); bad=1; continue
  fi
  # Looked at again at the last moment: the suite wipes the app's data and must run on the emulator alone.
  if ! emulator_only || [ -z "$ANDROID_SERIAL" ]; then
    echo "$avd: not run (a device that is not this emulator is attached)"
    summary+=("$avd: not run"); bad=1; continue
  fi
  echo "$avd: running on $ANDROID_SERIAL"
  scripts/geo-feed.sh -72.2896 43.7022 $(( gradle_seconds + 120 )) > /dev/null 2>&1 &
  feed_pid=$!
  scripts/field-requests.sh $(( gradle_seconds + 120 )) > /dev/null 2>&1 &
  fields_pid=$!
  only37=()
  # Gradle passes on only the first entry of a list in a runner argument, so the tests are left out by their
  # annotation (TakesMinutes), and on API 37 each is looked for by name below.
  if [ "$sdk" != "37" ]; then only37=("-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.infinite8looper.compass.TakesMinutes"); fi
  ANDROID_SERIAL=$ANDROID_SERIAL ./gradlew connectedDebugAndroidTest $only37 > $out/suite-$avd.log 2>&1 &
  gradle_pid=$!
  deadline=$(( $(date +%s) + gradle_seconds ))
  timed_out=0
  while kill -0 $gradle_pid 2>/dev/null; do
    if [ $(date +%s) -gt $deadline ]; then timed_out=1; stop_gradle; break; fi
    sleep 5
  done
  if [ $timed_out -eq 1 ]; then
    gstatus=1; echo "$avd: Gradle did not finish in $gradle_seconds s and was stopped"
  else
    wait $gradle_pid; gstatus=$?
  fi
  gradle_pid=""
  stop_feed
  echo "$avd (API $sdk): gradle exit status $gstatus"
  if [ -d app/build/outputs/androidTest-results/connected ]; then
    cp -R app/build/outputs/androidTest-results/connected $out/results-$avd
    scripts/test-counts.sh device $out/results-$avd | sed "s/^device: /$avd: /" | cut -c1-300
    line=$(scripts/test-counts.sh device $out/results-$avd | grep "^device: total")
    counts=($(echo "$line" | sed -E 's/[^0-9]+/ /g'))
    if [ ${#counts} -lt 3 ]; then counts=(0 0 0); bad=1; fi
    sum_passed=$(( sum_passed + counts[1] )); sum_failed=$(( sum_failed + counts[2] )); sum_skipped=$(( sum_skipped + counts[3] ))
    if [ "$sdk" = "37" ]; then
      # The tests run here alone must be among the passed ones: their one image.
      for t in $ONLY_37; do
      if ! python3 -c 'import glob,sys,xml.etree.ElementTree as ET
cls, name = sys.argv[2].split("#")
for f in glob.glob(sys.argv[1] + "/**/*.xml", recursive=True):
    for tc in ET.parse(f).getroot().iter("testcase"):
        if tc.get("classname", "").endswith(cls) and tc.get("name") == name and len(tc) == 0: sys.exit(0)
sys.exit(1)' $out/results-$avd $t; then echo "$avd: $t did not pass here, its one image"; bad=1; fi
      done
      note=""
    else
      # And absent here: a test of them in the results means the exclusion did not take.
      for t in $ONLY_37; do
      if python3 -c 'import glob,sys,xml.etree.ElementTree as ET
cls, name = sys.argv[2].split("#")
for f in glob.glob(sys.argv[1] + "/**/*.xml", recursive=True):
    for tc in ET.parse(f).getroot().iter("testcase"):
        if tc.get("classname", "").endswith(cls) and tc.get("name") == name: sys.exit(0)
sys.exit(1)' $out/results-$avd $t; then echo "$avd: $t ran here, though it runs on API 37 alone"; bad=1; fi
      done
      note=" (without ${(j:, :)ONLY_37}, which run on API 37 alone)"
    fi
    summary+=("$avd (API $sdk): ${counts[1]} passed, ${counts[2]} failed, ${counts[3]} skipped, gradle $gstatus$note")
    if [ "${counts[2]}" != "0" ]; then bad=1; fi
    if [ "${counts[3]}" != "0" ]; then echo "$avd: ${counts[3]} test(s) skipped, which counts as a failed run"; bad=1; fi
  else
    summary+=("$avd (API $sdk): no result files, gradle $gstatus")
    bad=1
  fi
  if [ $gstatus -ne 0 ]; then bad=1; fi
  rm -rf app/build/outputs/androidTest-results/connected   # so a later emulator's run is never read as this one's
done

echo "== totals, one line per emulator"
for line in $summary; do echo "$line"; done
echo "all: $sum_passed passed, $sum_failed failed, $sum_skipped skipped, over ${#avds} emulator(s)"
if [ -f app/build/outputs/apk/debug/app-debug.apk ]; then $ADB install -r app/build/outputs/apk/debug/app-debug.apk | tail -1; fi
exit $bad
