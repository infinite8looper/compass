#!/bin/zsh
# Usage: scripts/screenshot.sh <name>   ->  docs/screenshots/<name>.png
# The picture is taken into a temporary file and moved into place only if it is a PNG, so a failed capture
# (no device, the screen service not answering) leaves no empty or broken file behind and exits 1.
if [ -z "$1" ]; then
  echo "usage: scripts/screenshot.sh <name>" >&2
  exit 1
fi
ADB=~/Library/Android/sdk/platform-tools/adb
cd "$(dirname "$0")/.." || exit 1
mkdir -p docs/screenshots
tmp=$(mktemp "docs/screenshots/.$1.XXXXXX") || exit 1
# The device's own error output is dropped there: on some images screencap prints a warning as it exits, and
# exec-out would append it to the picture.
$ADB exec-out "screencap -p 2>/dev/null" > "$tmp"
if [ "$(head -c 8 "$tmp" | xxd -p)" != "89504e470d0a1a0a" ]; then
  rm -f "$tmp"
  echo "no screenshot: the device did not return a PNG" >&2
  exit 1
fi
chmod 644 "$tmp" && mv "$tmp" "docs/screenshots/$1.png" && echo "wrote docs/screenshots/$1.png"
