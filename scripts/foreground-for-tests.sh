#!/bin/zsh
# Usage: scripts/foreground-for-tests.sh [seconds=300]
# Android only delivers location to an app that is on screen (the "while in use" capability), and the
# instrumentation process alone does not have it. Run this in the background before
# `./gradlew connectedDebugAndroidTest`: it waits for the instrumented app process to appear, then opens
# MainActivity in that same process, exactly as a user holding the app open would.
ADB=~/Library/Android/sdk/platform-tools/adb
end=$(( $(date +%s) + ${1:-300} ))
while [ $(date +%s) -lt $end ]; do
  if $ADB shell dumpsys activity processes 2>/dev/null | grep -q "ActiveInstrumentation.*com.infinite8looper.compass.test"; then
    sleep 3
    $ADB shell am start -n com.infinite8looper.compass/.MainActivity > /dev/null
    exit 0
  fi
  sleep 0.5
done
echo "instrumentation never started" >&2
exit 1
