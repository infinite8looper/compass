#!/bin/zsh
# Usage: scripts/haptics.sh mark            prints the attached emulator's clock now (MM-DD HH:MM:SS.mmm)
#        scripts/haptics.sh since <mark>    the app's vibrations the system recorded at or after that time
# Reads `adb shell dumpsys vibrator_manager`, where the system lists every vibration it was asked for, the haptic
# feedback of `View.performHapticFeedback` included, and prints the app's entries, oldest first, one line each:
#   time, the haptic feedback constant, the status, what was played
# then a line of totals. Take a mark, do the thing, read since the mark:
#   m=$(scripts/haptics.sh mark); ...; scripts/haptics.sh since "$m"
# The status is the system's: `finished`, `ignored_for_settings` (touch vibration is off in the settings),
# `ignored_unsupported` (the vibrator has no such effect), `cancelled_superseded` (cut short by the next one), ...
# The constant is recorded on API 35 and later (16 CONFIRM, 6 CONTEXT_CLICK, 27 SEGMENT_FREQUENT_TICK, 4 CLOCK_TICK,
# 0 LONG_PRESS); API 33 and 34 record only the effect, and the line says so.
# The totals sort the entries into the app's three kinds, by constant where there is one and by effect where not:
# locks (CONFIRM; two thuds or a click), heartbeats (CONTEXT_CLICK; the tick effect), ticks (the tick tap's
# constant; the texture tick effect), and the dial's three where they play as composition primitives (the table
# in app/src/main/java/com/infinite8looper/compass/ui/Vibrations.kt): thin (TICK), thick (THUD), target (two
# CLICKs). Where the vibrator has no texture tick (the API 37 emulator) a tick tap is played as the tick effect, which
# only the constant tells from a heartbeat. Where the watch lacks a primitive the dial's row plays its constant
# instead: a thin line as the tick tap's constant (counted as a tick), a thick line as VIRTUAL_KEY (1, the press's
# constant, counted as other) and the target as CONTEXT_CLICK (counted as a heartbeat).
# The system keeps a limited number of entries per kind of use and later folds old ones together, so read soon
# after the thing happened: a long run of tick taps pushes older entries out (20 are kept on API 33).
source "$(dirname "$0")/emulator-only.sh" || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
PKG=com.infinite8looper.compass
if [ "$1" = "mark" ] && [ $# -eq 1 ]; then
  $ADB shell 'date "+%m-%d %H:%M:%S.%3N"' </dev/null | tr -d '\r'
  exit
fi
if [ "$1" != "since" ] || [ $# -ne 2 ]; then
  echo "usage: scripts/haptics.sh mark | since <mark>" >&2
  exit 1
fi
dump=$($ADB shell dumpsys vibrator_manager </dev/null) || { echo "could not read the vibrator records" >&2; exit 1; }
print -r -- "$dump" | python3 -c '
import re, sys
# Two layouts. API 35 and later:
#   MM-DD HH:MM:SS.mmm | effect | status | duration: ... | reason: performHapticFeedback(constant=N): ... | played: X | ...
# API 33 and 34:
#   startTime: MM-DD HH:MM:SS.mmm, endTime: ..., status: S, effect: ..., originalEffect: ..., uid: ..., opPkg: ...
mark, pkg = sys.argv[1], sys.argv[2]
names = {"16": "CONFIRM", "6": "CONTEXT_CLICK", "27": "SEGMENT_FREQUENT_TICK", "4": "CLOCK_TICK", "0": "LONG_PRESS",
         "1": "VIRTUAL_KEY"}
kinds = {"16": "lock", "6": "heartbeat", "27": "tick", "4": "tick"}
def kind(constant, what):
    if constant: return kinds.get(constant, "other")
    played = re.findall(r"Primitive[={](?:primitive=)?(?:PRIMITIVE_)?([A-Z_]+)", what)
    if played == ["TICK"]: return "thin"
    if played == ["THUD"]: return "thick"
    if played == ["CLICK", "CLICK"]: return "target"
    if "THUD" in what or re.search(r"(effect=|Prebaked=)CLICK", what): return "lock"
    if "TEXTURE_TICK" in what: return "tick"
    if re.search(r"(effect=|Prebaked=)TICK", what): return "heartbeat"
    return "other"
seen, rows = set(), []
for line in sys.stdin:
    if pkg not in line: continue
    m = re.match(r"\s*(?:startTime: )?(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)", line)
    if not m or m.group(1) < mark: continue
    status = re.search(r"\|\s*([a-z_]+)\s*\| duration", line) or re.search(r"status: ([a-zA-Z_]+)", line)
    constant = re.search(r"constant=(\d+)", line)
    played = re.search(r"played: ([^|]*)", line)
    what = played.group(1).strip() if played else (
        ", ".join(re.findall(r"(?:Prebaked|Primitive)\{[^}]*\}", line.split("originalEffect")[0])) or line.strip()[:160])
    key = (m.group(1), what)
    if key in seen: continue   # the same entry is listed again in the folded history
    seen.add(key)
    rows.append((m.group(1), constant.group(1) if constant else "", status.group(1).lower() if status else "?", what))
rows.sort()
for t, c, status, what in rows:
    print(t, names.get(c, "constant=" + c) if c else "(no constant recorded)", status, what)
k = [kind(c, what) for _, c, _, what in rows]
print("total", len(rows), " locks", k.count("lock"), " heartbeats", k.count("heartbeat"), " ticks", k.count("tick"),
      " thin", k.count("thin"), " thick", k.count("thick"), " target", k.count("target"), " other", k.count("other"), " not finished", sum(1 for r in rows if r[2] != "finished"))
' "$2" "$PKG"
