#!/bin/zsh
# Usage: scripts/field-requests.sh [seconds=600]
# Sets the emulator's magnetic field through its sensor console when an instrumented test asks for it: a test
# writes the line `magnetic-field <x>:<y>:<z>` (microtesla, in the watch's frame) to the log under the tag
# `CompassFieldRequest`, and this script, reading the log from the moment it starts, passes it on as
# `adb emu sensor set magnetic-field <x>:<y>:<z>`. Nothing else is passed on: a line that is not three numbers in
# that form is ignored. The test sees the change in the magnetometer's readings (CalibrationScreenTest).
# A test cannot reach the console from the watch itself, which is why the request goes through the log.
# scripts/device-tests.sh (run by scripts/quick-tests.sh, scripts/run-test.sh and scripts/all-suites.sh) starts it for
# the length of its run. It ends after the
# given number of seconds, or when it is stopped (TERM or INT), and then stops the log reader it started.
# Emulators only (scripts/emulator-only.sh).
source "$(dirname "$0")/emulator-only.sh" || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
end=$(( $(date +%s) + ${1:-600} ))
# The log from now on: the device's clock, as logcat takes it (seconds since the epoch).
since=$($ADB shell date +%s | tr -d '\r')
[ -n "$since" ] || { echo "the emulator's clock could not be read" >&2; exit 1; }
coproc $ADB logcat -v raw -T "$since.000" -s CompassFieldRequest:I
reader=$!
trap 'kill $reader 2>/dev/null; exit 0' INT TERM
while [ $(date +%s) -lt $end ]; do
  if read -t 2 -r -p line; then
    line=${line%$'\r'}
    if [[ $line =~ '^magnetic-field (-?[0-9]+(\.[0-9]+)?:-?[0-9]+(\.[0-9]+)?:-?[0-9]+(\.[0-9]+)?)$' ]]; then
      $ADB emu sensor set magnetic-field "${match[1]}" > /dev/null && echo "magnetic-field ${match[1]}"
    fi
  elif ! kill -0 $reader 2>/dev/null; then
    echo "the log reader ended" >&2; exit 1
  fi
done
kill $reader 2>/dev/null
exit 0
