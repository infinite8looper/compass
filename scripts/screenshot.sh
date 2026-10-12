#!/bin/zsh
# Usage: scripts/screenshot.sh <name>   ->  docs/screenshots/<name>.png
if [ -z "$1" ]; then
  echo "usage: scripts/screenshot.sh <name>" >&2
  exit 1
fi
ADB=~/Library/Android/sdk/platform-tools/adb
cd "$(dirname "$0")/.." || exit 1
mkdir -p docs/screenshots
# The device's own error output is dropped there: on some images screencap prints a warning as it exits, and
# exec-out would append it to the picture.
$ADB exec-out "screencap -p 2>/dev/null" > "docs/screenshots/$1.png" && echo "wrote docs/screenshots/$1.png"
