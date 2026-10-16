#!/bin/zsh
# Usage: scripts/all-suites.sh [avd ...]
# Runs the whole instrumented test suite on each named emulator, all of them at the same time, and prints the
# per-class counts and the totals of each, then the sum over all of them. With no names it runs on
#   Wear_OS_API33, Wear_OS_API34, Wear_OS_Newest (Wear OS 7.0, API 37) and Wear_OS_Large_Round (API 35).
# In order, it:
#   - stops every running scripts/geo-feed.sh and scripts/field-requests.sh, including ones started by hand, since
#     a feed or a relay left over could run against the wrong emulator;
#   - boots each named emulator that is not running, each on a port of its own (scripts/emulator.sh <avd> <port>,
#     300 s each), while the app and the test app are built once (./gradlew assembleDebug assembleDebugAndroidTest,
#     with 1800 s, after which it is stopped and nothing is run);
#     an emulator that is already running is used as it is; each one's animation scales and font scale left by a
#     run cut short are put back by scripts/emulator.sh, and its package manager is waited for (120 s);
#   - lists, on the first emulator that is ready, the tests the test app marks TakesMinutes (`am instrument -e log
#     true -e annotation com.infinite8looper.compass.TakesMinutes`, which runs none), so the list comes from the
#     annotation in the test app as built, into app/build/all-suites/takes-minutes.txt (each install and the listing
#     with 300 s; past that, the next emulator is asked);
#   - runs scripts/device-tests.sh on every emulator at once, each in a process of its own that addresses that
#     emulator alone (EMULATOR_ONLY_SERIAL): it lays the watch flat at a magnetic heading of 40 and does not run
#     unless the pose read back is flat and the screen is awake, installs both apps, switches the animations off,
#     runs its own scripts/geo-feed.sh (43.7022,-72.2896) and scripts/field-requests.sh, runs the suite in one run
#     of the test runner with a deadline of ALL_SUITES_SECONDS (default 2400; ALL_SUITES_GRADLE_SECONDS, its old
#     name, is read when it is not set) after which it is stopped and counted as failed, then puts the animations back, stops the feed and the relay and uninstalls both apps;
#   - on Wear_OS_Newest (API 37) alone, before its run, sets the global setting dynamic_color_theme_enabled to 1 and
#     the secure setting theme_customization_overlay_packages to the seed 2F8BFF (TONAL_SPOT), as the suite has
#     been run there since v3 Task 4, reads both back and does not run if they differ; after the run it puts both
#     back as they were before (a setting that was unset is deleted) and reads them back, and a setting that does
#     not read back as it was makes the run count as failed;
#   - prints the counts of each emulator and the sum, then stops the named emulators but the last one, which is
#     left running with the debug app installed again; an emulator it was not given by name is left running. Every emulator the suite ran on was left lying flat at the
#     magnetic heading of 40: the pose is not put back to upright.
# Two tests run on Wear_OS_Newest (API 37) alone, by the owner's decisions of 2026-10-15 ("API 37 only" and "Add
# it, API 37 only"): CalibrationScreenTest#withNoNewSegmentTheScreenIsLetGoTwoMinutesAfterOpening, which waits about
# two minutes, and CheckScreenTest#theCheckScreenKeepsTheDisplayOnForTwoMinutesFromEachTouchAndTurnOfTheCrown, which
# waits about four. Both carry the annotation TakesMinutes, and so does any test marked with it later: every test
# in the listing above is left out of the run on every other image (`-e notAnnotation`), so there it is neither
# run nor counted (neither passed nor skipped), and the run counts as failed if one of them is in its results. On
# API 37 the run must have passed every one of them, or it counts as failed. An empty or failed listing counts as
# failed too.
# Output goes to app/build/all-suites/ (ignored by git): build.log, boot-<avd>.log, suite-<avd>.log (what the run
# printed: each test's PASS, FAIL, SKIP or NOT RUN) and results-<avd>/ (listing.txt, instrument.txt: the runner's
# raw output; TEST-<avd>.xml, readable with `scripts/test-counts.sh device app/build/all-suites/results-<avd>`).
# Exit status: 0 if every run succeeded, no test failed, no test was skipped and none listed was left unrun, 1
# otherwise: on an emulator laid flat no test of this suite is skipped, so a skipped one means a test did not check
# its subject.
# Emulators only (scripts/emulator-only.sh): it refuses to start while any device that is not an emulator is
# attached, and each emulator's process looks again before its run and right before the tests start (in
# scripts/device-tests.sh), addressing its emulator by its serial alone. A run that is refused counts as not run,
# and the exit status is 1.
# Needs JDK 17 (found with /usr/libexec/java_home) and python3.
typeset +x EMULATOR_ONLY_ANY=1   # several emulators run at once here; each one is named by its serial wherever it is addressed
source "$(dirname "$0")/emulator-only.sh" || exit 1
source "$(dirname "$0")/deadline.sh" || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
PKG=com.infinite8looper.compass
cd "$(dirname "$0")/.." || exit 1
if [ -z "$JAVA_HOME" ]; then export JAVA_HOME=$(/usr/libexec/java_home -v 17) || exit 1; fi
avds=("$@")
if [ ${#avds} -eq 0 ]; then avds=(Wear_OS_API33 Wear_OS_API34 Wear_OS_Newest Wear_OS_Large_Round); fi
out=app/build/all-suites
mkdir -p $out || exit 1
# ALL_SUITES_GRADLE_SECONDS was this setting's name while the runs went through Gradle; it is still read, when
# ALL_SUITES_SECONDS is not set, and the run says so.
if [ -z "$ALL_SUITES_SECONDS" ] && [ -n "$ALL_SUITES_GRADLE_SECONDS" ]; then
  echo "ALL_SUITES_GRADLE_SECONDS is the old name of ALL_SUITES_SECONDS: taken as ALL_SUITES_SECONDS=$ALL_SUITES_GRADLE_SECONDS"
  ALL_SUITES_SECONDS=$ALL_SUITES_GRADLE_SECONDS
fi
seconds=${ALL_SUITES_SECONDS:-2400}
SEED_JSON='{"android.theme.customization.system_palette":"2F8BFF","android.theme.customization.accent_color":"2F8BFF","android.theme.customization.color_source":"preset","android.theme.customization.theme_style":"TONAL_SPOT"}'
began=$(date +%s)
children=()
stop_children() { for p in $children; do pkill -TERM -P $p 2>/dev/null; kill -TERM $p 2>/dev/null; done; for p in $children; do wait $p 2>/dev/null; done; }
trap 'stop_children; exit 1' INT TERM
pkill -f geo-feed.sh   # a feed left over from an earlier session would run against the wrong emulator
pkill -f field-requests.sh   # and so would a relay of field requests
for avd in $avds; do rm -rf $out/results-$avd $out/suite-$avd.log $out/boot-$avd.log $out/status-$avd $out/sdk-$avd $out/colors-$avd.txt; done
rm -f $out/takes-minutes.txt

# The emulators that are running, by AVD. Only emulators' serials are asked, and only after the check above.
typeset -A serial_of
running_serials() { $ADB devices | awk 'NR > 1 && $1 ~ /^emulator-[0-9]+$/ { print $1 }'; }
for s in $(running_serials); do
  name=$($ADB -s $s shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')
  [ -n "$name" ] && serial_of[$name]=$s
done
used=($(running_serials | sed 's/^emulator-//'))
port=5554
for avd in $avds; do
  if [ -z "${serial_of[$avd]}" ]; then
    while (( ${used[(Ie)$port]} )); do port=$(( port + 2 )); done
    serial_of[$avd]=emulator-$port; used+=($port)
  fi
done

plan=(); for avd in $avds; do plan+=("$avd ${serial_of[$avd]}"); done
echo "== build, and boot: ${(j:, :)plan}"
# The build has 1800 s (scripts/deadline.sh); past that it is stopped and counts as failed.
deadline 1800 "the build" zsh -c './gradlew assembleDebug assembleDebugAndroidTest > $1 2>&1' _ $out/build.log &
build_pid=$!; children+=($build_pid)
typeset -A boot_pid
for avd in $avds; do
  scripts/emulator.sh $avd ${serial_of[$avd]#emulator-} > $out/boot-$avd.log 2>&1 &
  pid=$!; boot_pid[$avd]=$pid; children+=($pid)
done
wait $build_pid; build_status=$?
typeset -A booted
for avd in $avds; do
  wait ${boot_pid[$avd]} && booted[$avd]=1
  echo "$avd (${serial_of[$avd]}): $(tail -1 $out/boot-$avd.log)"
done
children=()
echo "== built and booted after $(( $(date +%s) - began )) s"
if [ $build_status -ne 0 ]; then
  tail -20 $out/build.log
  echo "the build failed: nothing was run"
  exit 1
fi

# The tests marked TakesMinutes, as the test app marks them, listed on the first emulator that is ready.
for avd in $avds; do
  [ -n "${booted[$avd]}" ] || continue
  if (
    export EMULATOR_ONLY_SERIAL=${serial_of[$avd]}
    emulator_pinned || exit 1
    $ADB uninstall $PKG.test > /dev/null 2>&1; $ADB uninstall $PKG > /dev/null 2>&1
    # The installs and the listing have 300 s each (scripts/deadline.sh); past that the next emulator is tried.
    install_apk() { $ADB install -r -t $1 > /dev/null 2>&1; }
    deadline 300 "$avd: installing the app" install_apk app/build/outputs/apk/debug/app-debug.apk &&
      deadline 300 "$avd: installing the test app" install_apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
    list_long() { $ADB shell am instrument -r -w -e log true -e annotation $PKG.TakesMinutes $PKG.test/androidx.test.runner.AndroidJUnitRunner \
      | python3 -I scripts/instrument-results.py stamp > $out/takes-minutes-listing.txt; }
    deadline 300 "$avd: listing the tests marked TakesMinutes" list_long || { $ADB uninstall $PKG.test > /dev/null 2>&1; $ADB uninstall $PKG > /dev/null 2>&1; exit 1; }
    $ADB uninstall $PKG.test > /dev/null 2>&1; $ADB uninstall $PKG > /dev/null 2>&1
    python3 -I scripts/instrument-results.py list $out/takes-minutes-listing.txt > $out/takes-minutes.txt
  ); then break; fi
  rm -f $out/takes-minutes.txt
done
bad=0
long=($(cat $out/takes-minutes.txt 2>/dev/null))
if [ ${#long} -eq 0 ]; then
  echo "the tests marked TakesMinutes could not be listed (or none is marked): every run counts as failed"; bad=1
else
  echo "marked TakesMinutes, run on API 37 alone: ${(j:, :)long}"
fi

# One emulator's whole run. Everything it addresses is that emulator alone.
device_run() {
  local avd=$1 sdk before_on before_seed rc=0 args=()
  export EMULATOR_ONLY_SERIAL=${serial_of[$avd]}
  emulator_pinned || { echo "$avd: not run (its emulator is not attached, or a device that is not an emulator is)"; return 1; }
  # The emulator on that serial must be the one planned: a port taken by another emulator after the plan was made
  # would otherwise have the suite run there, under this AVD's name.
  local found=$($ADB shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')
  if [ "$found" != "$avd" ]; then echo "$avd: not run (${serial_of[$avd]} is ${found:-an emulator whose AVD could not be read}, not $avd)"; return 1; fi
  local deadline=$(( $(date +%s) + 120 ))
  until $ADB shell pm path android 2>/dev/null | grep -q "^package:"; do
    if [ $(date +%s) -gt $deadline ]; then echo "$avd: the package manager did not answer in 120 s, not run"; return 1; fi
    sleep 3
  done
  sdk=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')
  echo $sdk > $out/sdk-$avd
  if [ "$sdk" = "37" ]; then
    before_on=$($ADB shell settings get global dynamic_color_theme_enabled | tr -d '\r')
    before_seed=$($ADB shell settings get secure theme_customization_overlay_packages | tr -d '\r')
    echo "before: dynamic_color_theme_enabled=$before_on theme_customization_overlay_packages=$before_seed" >> $out/colors-$avd.txt
    $ADB shell settings put global dynamic_color_theme_enabled 1
    $ADB shell settings put secure theme_customization_overlay_packages "'$SEED_JSON'"
    local now_on=$($ADB shell settings get global dynamic_color_theme_enabled | tr -d '\r')
    local now_seed=$($ADB shell settings get secure theme_customization_overlay_packages | tr -d '\r')
    echo "for the run: dynamic_color_theme_enabled=$now_on theme_customization_overlay_packages=$now_seed" >> $out/colors-$avd.txt
    if [ "$now_on" != "1" ] || [ "$now_seed" != "$SEED_JSON" ]; then
      echo "$avd: the colors setting did not read back as set, not run"; rc=1
    fi
  else
    args=(-e notAnnotation $PKG.TakesMinutes)
  fi
  if [ $rc -eq 0 ]; then
    scripts/device-tests.sh $out/results-$avd $seconds $args || rc=1
  fi
  if [ "$sdk" = "37" ]; then
    if [ "$before_on" = "null" ] || [ -z "$before_on" ]; then $ADB shell settings delete global dynamic_color_theme_enabled > /dev/null
    else $ADB shell settings put global dynamic_color_theme_enabled "$before_on"; fi
    if [ "$before_seed" = "null" ] || [ -z "$before_seed" ]; then $ADB shell settings delete secure theme_customization_overlay_packages > /dev/null
    else $ADB shell settings put secure theme_customization_overlay_packages "'$before_seed'"; fi
    local after_on=$($ADB shell settings get global dynamic_color_theme_enabled | tr -d '\r')
    local after_seed=$($ADB shell settings get secure theme_customization_overlay_packages | tr -d '\r')
    echo "put back: dynamic_color_theme_enabled=$after_on theme_customization_overlay_packages=$after_seed" >> $out/colors-$avd.txt
    if [ "$after_on" != "${before_on:-null}" ] || [ "$after_seed" != "${before_seed:-null}" ]; then
      echo "$avd: the colors settings did not read back as they were before the run"; rc=1
    fi
    cat $out/colors-$avd.txt
  fi
  return $rc
}

echo "== running, all at once"
run_began=$(date +%s)
typeset -A run_pid
for avd in $avds; do
  if [ -z "${booted[$avd]}" ]; then echo "$avd: did not boot, not run"; continue; fi
  ( trap - INT TERM; device_run $avd; echo $? > $out/status-$avd ) > $out/suite-$avd.log 2>&1 &
  pid=$!; run_pid[$avd]=$pid; children+=($pid)
done
for avd in ${(k)run_pid}; do wait ${run_pid[$avd]}; done
children=()
echo "== the runs took $(( $(date +%s) - run_began )) s"

sum_passed=0; sum_failed=0; sum_skipped=0
summary=()
for avd in $avds; do
  echo "== $avd"
  sdk=$(cat $out/sdk-$avd 2>/dev/null)
  rstatus=$(cat $out/status-$avd 2>/dev/null)
  grep -E "^$avd: |^the run did not end|^before: |^for the run: |^put back: " $out/suite-$avd.log 2>/dev/null
  if [ "$rstatus" != "0" ]; then bad=1; fi
  if [ -n "$(find $out/results-$avd -name 'TEST-*.xml' 2>/dev/null)" ]; then
    scripts/test-counts.sh device $out/results-$avd | sed "s/^device: /$avd: /" | cut -c1-300
    line=$(scripts/test-counts.sh device $out/results-$avd | grep "^device: total")
    counts=($(echo "$line" | sed -E 's/[^0-9]+/ /g'))
    if [ ${#counts} -lt 3 ]; then counts=(0 0 0); bad=1; fi
    sum_passed=$(( sum_passed + counts[1] )); sum_failed=$(( sum_failed + counts[2] )); sum_skipped=$(( sum_skipped + counts[3] ))
    grep -E "^(NOT RUN|FAIL) " $out/suite-$avd.log | head -20
    if [ "$sdk" = "37" ]; then
      # The tests run here alone must be among the passed ones: their one image.
      for t in $long; do
      if ! python3 -I -c 'import glob,sys,xml.etree.ElementTree as ET
cls, name = sys.argv[2].split("#")
for f in glob.glob(sys.argv[1] + "/**/*.xml", recursive=True):
    for tc in ET.parse(f).getroot().iter("testcase"):
        if tc.get("classname", "").endswith("." + cls) and tc.get("name") == name and len(tc) == 0: sys.exit(0)
sys.exit(1)' $out/results-$avd $t; then echo "$avd: $t did not pass here, its one image"; bad=1; fi
      done
      note=""
    else
      # And absent here: one of them in the results means the exclusion did not take.
      for t in $long; do
      if python3 -I -c 'import glob,sys,xml.etree.ElementTree as ET
cls, name = sys.argv[2].split("#")
for f in glob.glob(sys.argv[1] + "/**/*.xml", recursive=True):
    for tc in ET.parse(f).getroot().iter("testcase"):
        if tc.get("classname", "").endswith("." + cls) and tc.get("name") == name: sys.exit(0)
sys.exit(1)' $out/results-$avd $t; then echo "$avd: $t ran here, though it runs on API 37 alone"; bad=1; fi
      done
      note=" (without ${(j:, :)long}, which run on API 37 alone)"
    fi
    summary+=("$avd (API $sdk): ${counts[1]} passed, ${counts[2]} failed, ${counts[3]} skipped, run status ${rstatus:-none}$note")
    if [ "${counts[2]}" != "0" ]; then bad=1; fi
    if [ "${counts[3]}" != "0" ]; then echo "$avd: ${counts[3]} test(s) skipped, which counts as a failed run"; bad=1; fi
  else
    tail -5 $out/suite-$avd.log 2>/dev/null
    summary+=("$avd${sdk:+ (API $sdk)}: not run, no result files")
    bad=1
  fi
done

echo "== totals, one line per emulator"
for line in $summary; do echo "$line"; done
echo "all: $sum_passed passed, $sum_failed failed, $sum_skipped skipped, over ${#avds} emulator(s)"

# Back to one emulator of those named, the last one, with the debug app installed again; the other named ones are
# stopped. An emulator this run did not name (one another session uses, say) is left alone.
last=${avds[-1]}
stop=()
for avd in $avds; do
  s=${serial_of[$avd]}
  [ "$s" = "${serial_of[$last]}" ] && continue
  running_serials | grep -q -x -- "$s" || continue
  # Only if that serial is still the named AVD's emulator.
  [ "$($ADB -s $s shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')" = "$avd" ] || continue
  $ADB -s $s emu kill > /dev/null 2>&1; stop+=($s)
done
stop_by=$(( $(date +%s) + 90 ))
while [ ${#stop} -gt 0 ] && [ $(running_serials | grep -x -F "${(F)stop}" | wc -l) -gt 0 ]; do
  if [ $(date +%s) -gt $stop_by ]; then echo "the other emulators did not stop in 90 s: $(running_serials | grep -x -F "${(F)stop}" | tr '\n' ' ')"; break; fi
  sleep 2
done
if [ -n "${booted[$last]}" ] && (export EMULATOR_ONLY_SERIAL=${serial_of[$last]}; emulator_pinned && $ADB install -r app/build/outputs/apk/debug/app-debug.apk | tail -1); then :; fi
echo "== left running: $(running_serials | tr '\n' ' ')($last); all-suites took $(( $(date +%s) - began )) s"
exit $bad
