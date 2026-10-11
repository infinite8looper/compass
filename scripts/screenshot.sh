#!/bin/zsh
# Usage: scripts/screenshot.sh <name>   ->  docs/screenshots/<name>.png
ADB=~/Library/Android/sdk/platform-tools/adb
mkdir -p docs/screenshots
$ADB exec-out screencap -p > "docs/screenshots/$1.png" && echo "wrote docs/screenshots/$1.png"
