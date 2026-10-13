#!/bin/zsh
# Usage: scripts/all-suites.sh [avd ...]
# Runs the instrumented test suite (./gradlew connectedDebugAndroidTest) on each named emulator in turn and prints
# the per-class counts and the totals of each, then the sum over all of them. With no names it runs on
#   Wear_OS_API33, Wear_OS_API34, Wear_OS_Newest (Wear OS 7.0, API 37) and Wear_OS_Large_Round (API 35).
# For each emulator it: switches to it with scripts/switch-emulator.sh (which stops the one that is attached, boots
# the named one and installs the debug app if it has been built), wakes the screen, starts scripts/geo-feed.sh at
# 43.7022,-72.2896 (the position some of the tests need, and the one the tests compare against), runs the suite,
# stops the feed, and keeps a copy of the result files.
# The tests wipe the app's data and Gradle uninstalls the app when a run ends. Use emulators only.
# Output goes to app/build/all-suites/ (ignored by git): suite-<avd>.log (Gradle's output) and results-<avd>/ (the
# result XML, readable with `scripts/test-counts.sh device app/build/all-suites/results-<avd>`).
# Deadlines: the switch has the deadlines of scripts/switch-emulator.sh (90 s for the old emulator to stop, 300 s
# to boot, 120 s for the package manager); the Gradle run has 40 minutes, after which it is stopped and counted as
# failed (ALL_SUITES_GRADLE_SECONDS changes that).
# Exit status: 0 if every run succeeded and no test failed, 1 otherwise. The last emulator in the list is left
# running with the debug app installed again. Needs JDK 17 (found with /usr/libexec/java_home) and python3.
ADB=~/Library/Android/sdk/platform-tools/adb
cd "$(dirname "$0")/.." || exit 1
if [ -z "$JAVA_HOME" ]; then export JAVA_HOME=$(/usr/libexec/java_home -v 17) || exit 1; fi
avds=("$@")
if [ ${#avds} -eq 0 ]; then avds=(Wear_OS_API33 Wear_OS_API34 Wear_OS_Newest Wear_OS_Large_Round); fi
out=app/build/all-suites
mkdir -p $out || exit 1
gradle_seconds=${ALL_SUITES_GRADLE_SECONDS:-2400}
feed_pid=""
gradle_pid=""
stop_feed() { if [ -n "$feed_pid" ]; then kill $feed_pid 2>/dev/null; wait $feed_pid 2>/dev/null; feed_pid=""; fi; }
stop_gradle() { if [ -n "$gradle_pid" ]; then pkill -P $gradle_pid 2>/dev/null; kill $gradle_pid 2>/dev/null; gradle_pid=""; fi; }
trap 'stop_feed; stop_gradle; exit 1' INT TERM
pkill -f geo-feed.sh   # a feed left over from an earlier session would run against the wrong emulator

bad=0
sum_passed=0; sum_failed=0; sum_skipped=0
summary=()
for avd in $avds; do
  echo "== $avd"
  rm -rf $out/results-$avd $out/suite-$avd.log
  scripts/switch-emulator.sh $avd 2>&1 | tail -3
  running=$($ADB shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')
  if [ "$running" != "$avd" ]; then
    echo "$avd: not running after the switch, skipped"
    summary+=("$avd: not run"); bad=1; continue
  fi
  sdk=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')
  $ADB shell input keyevent KEYCODE_WAKEUP
  scripts/geo-feed.sh -72.2896 43.7022 $(( gradle_seconds + 120 )) > /dev/null 2>&1 &
  feed_pid=$!
  ./gradlew connectedDebugAndroidTest > $out/suite-$avd.log 2>&1 &
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
    summary+=("$avd (API $sdk): ${counts[1]} passed, ${counts[2]} failed, ${counts[3]} skipped, gradle $gstatus")
    if [ "${counts[2]}" != "0" ]; then bad=1; fi
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
