#!/bin/zsh
# Usage: scripts/heading-sources-check.sh [degrees ...]
# Checks the three heading sources (v3 spec, "Heading": fused, magnetic, blend) against headings set through the
# emulator's sensor console. For each heading (0 90 200 300 if none are given) it lays the virtual watch flat at that
# magnetic heading (scripts/set-heading.sh), wakes the screen, waits 30 s for the emulator's sensor fusion to settle,
# and runs the device test HeadingSourceTest#atRestTheThreeHeadingsAgree with that heading as `expectedHeading`: it
# fails unless the fused heading, the magnetic one (from the geomagnetic rotation vector and from getRotationMatrix
# alike) and the blend are each within 3 degrees of it. It builds and installs the debug app and its tests first.
# At the end it lays the watch flat at 40 again, the pose scripts/all-suites.sh leaves.
# Exit status 0 when every heading passed, 1 otherwise. Emulators only.
source "$(dirname "$0")/emulator-only.sh" || exit 1
emulator_pinned || exit 1      # an emulator's serial to address, before Gradle installs on it
cd "$(dirname "$0")/.." || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
if [ -z "$JAVA_HOME" ]; then export JAVA_HOME=$(/usr/libexec/java_home -v 17) || exit 1; fi
headings=("$@")
if [ ${#headings} -eq 0 ]; then headings=(0 90 200 300); fi
ANDROID_SERIAL=$ANDROID_SERIAL ./gradlew installDebug installDebugAndroidTest -q > /dev/null || { echo "the app and its tests could not be installed" >&2; exit 1; }
bad=0
for h in $headings; do
  emulator_pinned || exit 1
  scripts/set-heading.sh $h > /dev/null || { echo "heading $h: could not be set" >&2; bad=1; continue; }
  $ADB shell input keyevent KEYCODE_WAKEUP
  sleep 30
  out=$($ADB shell am instrument -w -e class 'com.infinite8looper.compass.HeadingSourceTest#atRestTheThreeHeadingsAgree' \
    -e expectedHeading $h com.infinite8looper.compass.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tr -d '\r')
  if print -r -- "$out" | grep -q '^OK (1 test)'; then
    echo "heading $h: passed ($($ADB logcat -d -s HeadingSourceTest | grep 'expected '"$h" | tail -2 | sed 's/.*HeadingSourceTest: //' | tr '\n' ';'))"
  else
    echo "heading $h: FAILED"; print -r -- "$out" | grep -E 'AssertionError|Exception' | head -3; bad=1
  fi
done
scripts/set-heading.sh 40 > /dev/null; $ADB shell input keyevent KEYCODE_WAKEUP
exit $bad
