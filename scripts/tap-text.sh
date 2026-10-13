#!/bin/zsh
# Usage: scripts/tap-text.sh <text>
# Taps the middle of the first thing on the attached emulator's screen whose text is exactly <text>, as the
# accessibility tree reports it, and prints where it tapped. Exits 1 if nothing on screen has that text or the
# tree cannot be read (it cannot while the screen keeps animating, which the compass does while the hold-flat
# hint loops).
# The system's own dialogs use a typographic apostrophe: the permission dialog's button is "Don’t allow".
source "$(dirname "$0")/emulator-only.sh" || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
if [ -z "$1" ]; then
  echo "usage: scripts/tap-text.sh <text>" >&2
  exit 1
fi
xml=$($ADB exec-out uiautomator dump /dev/tty 2>/dev/null)
if [[ "$xml" != *"<hierarchy"* ]]; then echo "could not read what is on screen" >&2; exit 1; fi
xy=$(print -r -- "$xml" | python3 -c '
import re, sys
import xml.etree.ElementTree as ET
doc = sys.stdin.read()
root = ET.fromstring(doc[doc.index("<hierarchy"):doc.rindex("</hierarchy>") + len("</hierarchy>")])
for node in root.iter("node"):
    if node.get("text") == sys.argv[1]:
        left, top, right, bottom = map(int, re.findall(r"-?\d+", node.get("bounds")))
        print((left + right) // 2, (top + bottom) // 2)
        break
' "$1") || { echo "could not read what is on screen" >&2; exit 1; }
if [ -z "$xy" ]; then echo "no '$1' on screen" >&2; exit 1; fi
$ADB shell input tap ${=xy} && echo "tapped '$1' at $xy"
