#!/bin/zsh
# Usage: scripts/geo-feed.sh <lon> <lat> [seconds=120]
source "$(dirname "$0")/emulator-only.sh" || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
end=$(( $(date +%s) + ${3:-120} ))
while [ $(date +%s) -lt $end ]; do
  $ADB emu geo fix "$1" "$2" > /dev/null
  sleep 1
done
