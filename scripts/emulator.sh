#!/bin/zsh
# Usage: scripts/emulator.sh [avd-name]
# Boots a Wear OS emulator and waits for it, up to 5 minutes. Does nothing but wait if one is already attached.
# AVDs: Wear_OS_Large_Round (API 35, the default), Wear_OS_Small_Round (API 35), Wear_OS_API34, Wear_OS_API33.
# The other scripts assume one attached device: stop a running emulator with `adb emu kill` before booting another.
ADB=~/Library/Android/sdk/platform-tools/adb
EMU=~/Library/Android/sdk/emulator/emulator
AVD=${1:-Wear_OS_Large_Round}
if ! $ADB devices | grep -q "emulator-.*device"; then
  nohup $EMU -avd "$AVD" -no-snapshot-save > /tmp/compass-emulator.log 2>&1 &
fi
deadline=$(( $(date +%s) + 300 ))
until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  if [ $(date +%s) -gt $deadline ]; then echo "emulator did not boot in 300 s"; exit 1; fi
  sleep 3
done
echo "emulator ready: $($ADB shell getprop ro.build.version.sdk | tr -d '\r')"
