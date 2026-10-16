#!/bin/zsh
# Usage: scripts/quick-tests.sh [--serial emulator-NNNN | --avd <avd-name>] <Class[#method]> [more ...] [--unit <Class> ...]
# Runs the named instrumented tests (classes of com.infinite8looper.compass by their simple names, or one test of
# a class; a name with a dot is taken as the full name) on one emulator that is already booted, all of them in ONE
# run of the test runner, and the named unit test classes, if any (after --unit). No emulator is booted or stopped.
# The emulator: the one named (by its serial, or by its AVD's name among the running emulators), or else the one
# attached; with another emulator running too it has to be named.
# It builds the app and the test app once (./gradlew assembleDebug assembleDebugAndroidTest, with
# testDebugUnitTest --tests for each unit class), then runs scripts/device-tests.sh on the emulator, which lays it
# flat at a magnetic heading of 40 and checks that it is awake, installs both apps, switches the animations off,
# starts scripts/geo-feed.sh and scripts/field-requests.sh for the run, runs the tests, puts all of that back and
# uninstalls the apps; it prints PASS, FAIL (with the first lines of the failure), SKIP or NOT RUN for each test.
# A name that matches no test is a failure of the run (the runner reports `initializationError`).
# On every image but Wear_OS_Newest (API 37) the tests marked TakesMinutes are left out (`-e notAnnotation`), as
# scripts/all-suites.sh does; on API 37 a named class runs them too (they take two and four minutes).
# Results: app/build/quick-tests/<avd>/ (listing.txt, instrument.txt, TEST-<avd>.xml; readable with
# `scripts/test-counts.sh device app/build/quick-tests/<avd>`). The run has QUICK_TESTS_SECONDS (default 2400) to end.
# Exit status 1 if the build failed, or any test failed, was skipped or was not run; 0 otherwise.
# Emulators only (scripts/emulator-only.sh). Needs JDK 17 (found with /usr/libexec/java_home) and python3.
ADB=~/Library/Android/sdk/platform-tools/adb
pick=""; pick_avd=""
device=(); unit=(); in_unit=0
while [ $# -gt 0 ]; do
  case $1 in
    --serial) pick=$2; shift 2 ;;
    --avd) pick_avd=$2; shift 2 ;;
    --unit) in_unit=1; shift ;;
    *) if [ $in_unit -eq 1 ]; then unit+=($1); else device+=($1); fi; shift ;;
  esac
done
if [ ${#device} -eq 0 ] && [ ${#unit} -eq 0 ]; then
  echo "usage: scripts/quick-tests.sh [--serial emulator-NNNN | --avd <avd-name>] <Class[#method]> [more ...] [--unit <Class> ...]" >&2
  exit 1
fi
if [ -n "$pick_avd" ]; then
  if $ADB devices | awk 'NR > 1 && NF { print $1 }' | grep -v -q -E '^emulator-[0-9]+$'; then
    echo "refusing: a device that is not an emulator is attached (adb devices). These scripts are for emulators only." >&2; exit 1
  fi
  # Only the emulators' serials are asked their AVD's name; anything else listed is refused just below.
  for s in $($ADB devices | awk 'NR > 1 && $1 ~ /^emulator-[0-9]+$/ && $2 == "device" { print $1 }'); do
    if [ "$($ADB -s $s shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')" = "$pick_avd" ]; then pick=$s; fi
  done
  if [ -z "$pick" ]; then echo "$pick_avd is not running: boot it first (scripts/switch-emulator.sh $pick_avd)" >&2; exit 1; fi
fi
if [ -n "$pick" ]; then export EMULATOR_ONLY_SERIAL=$pick; fi
if [ ${#device} -gt 0 ]; then
  source "$(dirname "$0")/emulator-only.sh" || exit 1
  emulator_pinned || exit 1      # and an emulator's serial to address
else
  # Unit tests alone need no device, and none is addressed.
  :
fi
cd "$(dirname "$0")/.." || exit 1
if [ -z "$JAVA_HOME" ]; then export JAVA_HOME=$(/usr/libexec/java_home -v 17) || exit 1; fi
full() { if [[ $1 == *.* ]]; then echo $1; else echo com.infinite8looper.compass.$1; fi; }
tasks=(assembleDebug assembleDebugAndroidTest)
bad=0
if [ ${#unit} -gt 0 ]; then
  tasks+=(testDebugUnitTest)
  # The unit test classes are in packages of their own (nav, ui): a simple name matches the class in any of them.
  for u in $unit; do if [[ $u == *.* ]]; then tasks+=(--tests "$u"); else tasks+=(--tests "*.$u"); fi; done
fi
echo "== build: ./gradlew -q ${tasks[*]}"
if ! ./gradlew -q $tasks > app/build/quick-tests-gradle.log 2>&1; then
  if [ ${#unit} -gt 0 ] && [ -f app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk ] && grep -q "There were failing tests" app/build/quick-tests-gradle.log; then
    echo "unit tests failed"; bad=1
  else
    tail -20 app/build/quick-tests-gradle.log; exit 1
  fi
fi
if [ ${#unit} -gt 0 ]; then
  echo "== unit"
  python3 -I - $unit <<'PY' || bad=1
import glob, sys, xml.etree.ElementTree as ET
want = {u.split(".")[-1] for u in sys.argv[1:]}
seen, bad = set(), False
for f in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    for tc in ET.parse(f).getroot().iter("testcase"):
        cls = tc.get("classname").split(".")[-1]
        if cls not in want: continue
        seen.add(cls)
        fl = tc.find("failure"); sk = tc.find("skipped")
        state = "FAIL" if fl is not None else ("SKIP" if sk is not None else "PASS")
        bad = bad or state != "PASS"
        print(state, cls + "#" + tc.get("name"), tc.get("time") + " s")
        if fl is not None: print("    " + "\n    ".join((fl.text or "").strip().splitlines()[:4]))
for cls in sorted(want - seen):
    print("NOT RUN", cls, "(no unit test class of that name ran)"); bad = True
sys.exit(1 if bad else 0)
PY
fi
if [ ${#device} -gt 0 ]; then
  emulator_pinned || exit 1
  avd=$($ADB shell getprop ro.boot.qemu.avd_name | tr -d '\r')
  sdk=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')
  names=()
  for d in $device; do names+=($(full $d)); done
  args=(-e class ${(j:,:)names})
  if [ "$sdk" != "37" ]; then args+=(-e notAnnotation com.infinite8looper.compass.TakesMinutes); fi
  echo "== $avd (API $sdk, $ANDROID_SERIAL): ${(j:,:)device}"
  scripts/device-tests.sh app/build/quick-tests/$avd ${QUICK_TESTS_SECONDS:-2400} $args || bad=1
  # The runner drops a class or a test of the list that matches nothing, without a word, when another name of the
  # list matches (seen on API 37, 2026-10-16): so each name has to have a result of its own.
  python3 -I - app/build/quick-tests/$avd $names <<'PY' || bad=1
import glob, sys, xml.etree.ElementTree as ET
got = set()
for f in glob.glob(sys.argv[1] + "/TEST-*.xml"):
    for tc in ET.parse(f).getroot().iter("testcase"):
        got.add((tc.get("classname"), tc.get("name")))
bad = False
for n in sys.argv[2:]:
    cls, _, method = n.partition("#")
    if not any(c == cls and (not method or m == method) for c, m in got):
        print("NOT RUN", n.split(".")[-1], "(no result for this name: no such test, or one marked TakesMinutes off API 37)")
        bad = True
sys.exit(1 if bad else 0)
PY
fi
if [ $bad -eq 0 ]; then echo "== all passed"; else echo "== FAILED"; fi
exit $bad
