#!/bin/zsh
# Usage: scripts/switch-emulator.sh <avd-name>
# Makes the named emulator the one that is running: stops whichever is attached (waiting up to 90 s for it to go),
# boots the named one with scripts/emulator.sh, waits up to 120 s for its package manager, and installs the debug
# app if it has been built. Does not restart an emulator that is already the named one.
# AVDs: Wear_OS_Large_Round, Wear_OS_Small_Round (API 35), Wear_OS_API34, Wear_OS_API33, Wear_OS_Newest (API 37).
EMULATOR_ONLY_MAY_BE_NONE=1   # this script boots the emulator, so none may be attached yet
source "$(dirname "$0")/emulator-only.sh" || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
APK=app/build/outputs/apk/debug/app-debug.apk
if [ -z "$1" ]; then
  echo "usage: scripts/switch-emulator.sh <avd-name>" >&2
  exit 1
fi
cd "$(dirname "$0")/.." || exit 1
running=""
if [ -n "$ANDROID_SERIAL" ]; then running=$($ADB shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r'); fi
if [ "$running" != "$1" ]; then
  if [ -n "$ANDROID_SERIAL" ]; then $ADB emu kill > /dev/null 2>&1; fi
  deadline=$(( $(date +%s) + 90 ))
  while $ADB devices | grep -q "^emulator-"; do
    if [ $(date +%s) -gt $deadline ]; then echo "the running emulator did not stop in 90 s" >&2; exit 1; fi
    sleep 2
  done
  sleep 3   # the emulator's process lets go of the AVD's files a moment after it leaves the device list
fi
scripts/emulator.sh "$1" || exit 1
# From here on adb addresses the emulator that was booted, by its serial, and nothing else.
emulator_only || exit 1
if [ -z "$ANDROID_SERIAL" ]; then echo "no emulator is attached after the boot" >&2; exit 1; fi
deadline=$(( $(date +%s) + 120 ))
until $ADB shell pm path android 2>/dev/null | grep -q "^package:"; do
  if [ $(date +%s) -gt $deadline ]; then echo "the package manager did not answer in 120 s" >&2; exit 1; fi
  sleep 3
done
if [ -f $APK ]; then
  $ADB install -r $APK | tail -1
else
  echo "no $APK: build it with ./gradlew assembleDebug"
fi
echo "$($ADB shell getprop ro.boot.qemu.avd_name | tr -d '\r'): $($ADB shell wm size | tr -d '\r'), $($ADB shell wm density | tr -d '\r')"
