#!/bin/zsh
# Usage: scripts/frame-times.sh <label> [from=0] [to=350] [step=10] [pause-seconds=0.3]
# Measures how long the app's frames take while the compass turns: lays the virtual watch flat, resets the
# system's frame statistics for the app, steps the magnetic heading from <from> to <to>, and prints
#   the system's own totals since the reset (`dumpsys gfxinfo`: frames, janky frames, 50th, 90th, 95th and 99th
#   percentile of the frame time in ms), and
#   from the last 120 frames (`dumpsys gfxinfo framestats`): the median, 90th and 99th percentile and the largest
#   frame time (intended vsync to frame completed), the share over 33.4 ms, and the median of each stage in ms
#   (ui: input to sync queued; issue: issuing draw commands; swap: swap buffers to frame completed).
# The compass has to be the screen in view, with whatever is to be measured already on it.
# The emulator draws through the host's graphics; these numbers say how the stages compare, not what a watch does.
source "$(dirname "$0")/emulator-only.sh" || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
PKG=com.infinite8looper.compass
if [ -z "$1" ]; then echo "usage: scripts/frame-times.sh <label> [from] [to] [step] [pause-seconds]" >&2; exit 1; fi
label=$1; from=${2:-0}; to=${3:-350}; step=${4:-10}; pause=${5:-0.3}
heading() {
  $ADB emu sensor set acceleration 0:0:9.81 > /dev/null
  $ADB emu sensor set magnetic-field "$(awk -v h="$1" 'BEGIN { r = h * atan2(0, -1) / 180; printf "%.3f:%.3f:-45", -20 * sin(r), 20 * cos(r) }')" > /dev/null
}
heading $from; sleep 6
$ADB shell dumpsys gfxinfo $PKG reset > /dev/null || { echo "no device, or the app is not running" >&2; exit 1; }
h=$from; while [ $h -le $to ]; do heading $h; sleep $pause; h=$((h + step)); done
sleep 1
echo "$label: $($ADB shell dumpsys gfxinfo $PKG | grep -E 'Total frames rendered|Janky frames:|50th percentile|90th percentile|95th percentile|99th percentile' | head -6 | tr -d '\r' | tr '\n' ' ' | sed 's/  */ /g')"
$ADB shell dumpsys gfxinfo $PKG framestats | python3 -c '
import sys
rows, header, inside = [], None, False
for line in sys.stdin:
    line = line.strip()
    if line == "---PROFILEDATA---":
        inside, header = not inside, None
        continue
    if not inside:
        continue
    if line.startswith("Flags,"):
        header = line.rstrip(",").split(",")
    elif header:
        v = line.rstrip(",").split(",")
        if len(v) >= len(header):
            rows.append(dict(zip(header, v)))
seen, total, stages = set(), [], {"ui": [], "issue": [], "swap": []}
for r in rows:
    if r["Flags"] != "0" or r["IntendedVsync"] in seen:
        continue
    seen.add(r["IntendedVsync"])
    start, end = int(r["IntendedVsync"]), int(r["FrameCompleted"])
    if end <= start:
        continue
    total.append((end - start) / 1e6)
    stages["ui"].append((int(r["SyncQueued"]) - int(r["HandleInputStart"])) / 1e6)
    stages["issue"].append((int(r["SwapBuffers"]) - int(r["IssueDrawCommandsStart"])) / 1e6)
    stages["swap"].append((end - int(r["SwapBuffers"])) / 1e6)
def pct(a, p):
    a = sorted(a)
    return a[min(len(a) - 1, int(round(p / 100 * (len(a) - 1))))]
if not total:
    sys.exit("no frames in the statistics")
late = 100 * sum(1 for x in total if x > 33.4) / len(total)
print("%s, last %d frames: median %.1f, 90th %.1f, 99th %.1f, largest %.1f ms; %.1f%% over 33.4 ms; stage medians: %s" % (
    sys.argv[1], len(total), pct(total, 50), pct(total, 90), pct(total, 99), max(total), late,
    ", ".join("%s %.2f" % (k, pct(v, 50)) for k, v in stages.items())))
' "$label"
