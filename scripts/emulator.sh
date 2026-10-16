#!/bin/zsh
# Usage: scripts/emulator.sh [avd-name] [port]
# Boots a Wear OS emulator and waits for it, up to 5 minutes. Does nothing but wait if one is already attached.
# With a port (an even number from 5554 to 5584) it boots the AVD as emulator-<port> even while other emulators are
# running, does nothing but wait if emulator-<port> is already attached, and addresses that emulator alone
# (EMULATOR_ONLY_SERIAL, scripts/emulator-only.sh); its output goes to /tmp/compass-emulator-<port>.log.
# scripts/all-suites.sh boots its emulators this way, side by side.
# AVDs: Wear_OS_Large_Round (API 35, the default), Wear_OS_Small_Round (API 35), Wear_OS_API34, Wear_OS_API33,
# Wear_OS_Newest (Wear OS 7.0, API 37, large round; a signed image: no `adb root` and no sqlite3 on it).
# The other scripts assume one attached device: stop a running emulator with `adb emu kill` before booting another.
EMULATOR_ONLY_MAY_BE_NONE=1   # this script boots the emulator, so none may be attached yet
PORT=$2
if [ -n "$PORT" ]; then
  if ! [[ $PORT =~ '^55(5[4-9]|[67][0-9]|8[0-4])$' ]] || (( PORT % 2 )); then
    echo "the port has to be an even number from 5554 to 5584: $PORT" >&2; exit 1
  fi
  export EMULATOR_ONLY_SERIAL=emulator-$PORT
fi
source "$(dirname "$0")/emulator-only.sh" || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
EMU=~/Library/Android/sdk/emulator/emulator
AVD=${1:-Wear_OS_Large_Round}
if [ -n "$PORT" ]; then
  if ! $ADB devices | grep -q "^emulator-$PORT[[:space:]]"; then
    nohup $EMU -avd "$AVD" -port $PORT -no-snapshot-save > /tmp/compass-emulator-$PORT.log 2>&1 &
  fi
elif ! $ADB devices | grep -q "emulator-.*device"; then
  nohup $EMU -avd "$AVD" -no-snapshot-save > /tmp/compass-emulator.log 2>&1 &
fi
deadline=$(( $(date +%s) + 300 ))
# Each look at the device list is checked: a device that is not an emulator ends this, and adb is called only once
# the emulator is listed and is the one device addressed.
until emulator_only && [ -n "$ANDROID_SERIAL" ] && [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  if $ADB devices | tail -n +2 | awk 'NF { print $1 }' | grep -v -q -E '^emulator-[0-9]+$'; then exit 1; fi
  if [ $(date +%s) -gt $deadline ]; then echo "emulator did not boot in 300 s"; exit 1; fi
  sleep 3
done
echo "emulator ready: $($ADB shell getprop ro.build.version.sdk | tr -d '\r')"
# The instrumented tests run with the system's animations switched off (testOptions.animationsDisabled) and the
# scales are put back when a run ends. A run that is cut short can leave them at 0, and then the app's own
# animations stand still in every later manual check. So: report the three scales and put any 0 back to 1.
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  value=$($ADB shell settings get global $scale | tr -d '\r')
  if [ "$value" = "0" ] || [ "$value" = "0.0" ]; then
    $ADB shell settings put global $scale 1
    echo "$scale was $value: restored to 1"
  else
    echo "$scale: $value"
  fi
done
# FontScaleTest sets the system's font scale and puts it back when it ends. A run that is cut short can leave it at
# 1.24 or 0.94, and every later picture and manual check is then at that size. Reported, and put back to 1.0.
font=$($ADB shell settings get system font_scale | tr -d '\r')
if [ "$font" = "1.0" ] || [ "$font" = "null" ] || [ -z "$font" ]; then
  echo "font_scale: $font"
else
  $ADB shell settings put system font_scale 1.0
  echo "font_scale was $font: restored to 1.0"
fi
