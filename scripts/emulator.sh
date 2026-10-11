#!/bin/zsh
# Boots a Wear OS emulator (default Wear_OS_Large_Round) and waits for it, up to 5 minutes.
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
