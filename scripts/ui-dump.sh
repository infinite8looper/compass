#!/bin/zsh
# Usage: scripts/ui-dump.sh [all]
# Prints what the attached emulator's screen says to accessibility services (`uiautomator dump`), one line for
# each thing that has a text, a description or something that can be done with it: its class, text, description,
# its flags (clickable, long-clickable, checkable, checked, focusable, focused, scrollable, selected, DISABLED)
# and its bounds, indented by depth. With `all`, every node is printed.
# For reading a screen's words in a script, and for judging what a screen reader is given.
# Exits 1 if the tree cannot be read (it cannot while the screen keeps animating, as the hold-flat hint does).
source "$(dirname "$0")/emulator-only.sh" || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB exec-out uiautomator dump /dev/tty 2>/dev/null | python3 -c '
import sys
import xml.etree.ElementTree as ET
doc = sys.stdin.read()
if "<hierarchy" not in doc:
    sys.exit("could not read what is on screen")
root = ET.fromstring(doc[doc.index("<hierarchy"):doc.rindex("</hierarchy>") + len("</hierarchy>")])
everything = len(sys.argv) > 1 and sys.argv[1] == "all"
def walk(node, depth):
    a = node.attrib
    if node.tag == "node":
        flags = [k for k in ("clickable", "long-clickable", "checkable", "checked", "focusable", "focused", "scrollable", "selected") if a.get(k) == "true"]
        if a.get("enabled") != "true":
            flags.append("DISABLED")
        if everything or a.get("text") or a.get("content-desc") or flags:
            cls = a.get("class", "").split(".")[-1]
            print("  " * depth + "[%s] text=%r desc=%r %s %s" % (cls, a.get("text"), a.get("content-desc"), ",".join(flags), a.get("bounds")))
    for child in node:
        walk(child, depth + 1)
walk(root, 0)
' "$1"
