#!/bin/zsh
# Usage: scripts/set-heading.sh <degrees>
# Lays the emulator's virtual watch flat and points it at the given magnetic heading.
# The emulator's sensor fusion is slow: the reading takes about 20 seconds to settle.
source "$(dirname "$0")/emulator-only.sh" || exit 1
if [ -z "$1" ]; then
  echo "usage: scripts/set-heading.sh <degrees>" >&2
  exit 1
fi
ADB=~/Library/Android/sdk/platform-tools/adb
field=$(awk -v h="$1" 'BEGIN { r = h * atan2(0, -1) / 180; printf "%.3f:%.3f:-45", -20 * sin(r), 20 * cos(r) }')
$ADB emu sensor set acceleration 0:0:9.81 > /dev/null && $ADB emu sensor set magnetic-field "$field" > /dev/null \
  && echo "heading $1: magnetic-field $field (allow about 20 s to settle)"
