#!/bin/zsh
# Usage: scripts/raw-swipe.sh line <x1> <y1> <x2> <y2> [samples=10]
#        scripts/raw-swipe.sh arc <angle1> <angle2> <fraction> [samples=20]
#        scripts/raw-swipe.sh path <samples-per-leg> <x,y> <x,y> [<x,y> ...]
# One finger's touch on the attached emulator, written to its touch screen as raw input events: down at the first
# point, a move for every sample about 16 ms apart, up at the last point.
#   line   straight from one point to another, in screen pixels.
#   arc    along a circle about the middle of the screen, from one clock angle to another (degrees, 0 at 12
#          o'clock, clockwise), at the given fraction of the screen's radius.
#   path   straight legs through the given points, in screen pixels.
# Why this exists beside swipe.sh: `adb shell input swipe` waits for the app to handle each sample and places the
# next by the clock, so when the app is slow over one sample the rest are never sent and the swipe ends early (see
# swipe.sh). Events written to the touch device do not wait for the app, as a finger does not, and a gesture that
# is not a straight line can be given.
# Writing to the device needs adbd running as root, so this works on emulator images that allow `adb root`. The
# script switches adbd to root for the gesture and back afterwards, and waits at most 30 s for each switch; it
# leaves adbd unrooted even if it found it rooted. It refuses to run on anything but an emulator.
# However it ends (finished, a wrong argument, Ctrl-C, a kill), it lifts the finger if it had put one down, removes
# its files from the device and from this machine, and unroots adbd, once, and exits with the status it had.
# A sample count must be a whole number from 1 to 500, and every other number finite.
ADB=~/Library/Android/sdk/platform-tools/adb
usage() {
  echo "usage: scripts/raw-swipe.sh line <x1> <y1> <x2> <y2> [samples] | arc <angle1> <angle2> <fraction> [samples] | path <samples-per-leg> <x,y> <x,y> ..." >&2
  exit 1
}
[[ "$1" == (line|arc|path) ]] || usage
serial=$($ADB get-serialno 2>/dev/null | tr -d '\r')
qemu="$($ADB shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')$($ADB shell getprop ro.boot.qemu 2>/dev/null | tr -d '\r')"
if [[ "$serial" != emulator-* ]] || [[ "$qemu" != *1* ]]; then
  echo "refusing: the attached device (${serial:-none, or more than one}) is not an emulator" >&2; exit 1
fi
size=$($ADB shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Physical size: //p')
if [[ "$size" != <->x<-> ]]; then echo "the device did not say its screen size" >&2; exit 1; fi
remote=/data/local/tmp/raw-swipe
dir=$(mktemp -d) || exit 1
dev=""          # the touch device, once found
down=0          # 1 while a finger this script put down has not been lifted
rooted=0        # 1 once this script has asked adbd to be root, or found it so
gesture=""      # the process that plays the gesture, while it runs
cleaned=0
# Waits up to 30 s for adbd to come back as the given user id (0 is root, 2000 is shell).
adbd_as() {
  local deadline=$(( $(date +%s) + 30 ))
  until [ "$($ADB shell id -u 2>/dev/null | tr -d '\r')" = "$1" ]; do
    if [ $(date +%s) -gt $deadline ]; then return 1; fi
    sleep 1
  done
}
# Runs once, however the script ends, and keeps the status the script was ending with.
cleanup() {
  local rc=$?
  [ $cleaned = 1 ] && return
  cleaned=1
  trap - EXIT INT TERM
  rm -rf "$dir"
  # A gesture that is still being played is stopped: without its connection the loop on the device ends.
  [ -n "$gesture" ] && kill $gesture 2>/dev/null
  if [ $rooted = 1 ]; then
    # An interrupted switch to root may still be restarting adbd: give it its time before asking anything of it.
    adbd_as 0 || adbd_as 2000
    if [ "$($ADB shell id -u 2>/dev/null | tr -d '\r')" = "0" ]; then
      if [ $down = 1 ] && [ -n "$dev" ]; then
        # Whatever of the gesture is still running on the device is ended first, then the finger is lifted.
        $ADB shell "pkill -f 'cd $remote' ; sleep 0.2; cat $remote/u > /dev/input/$dev" > /dev/null 2>&1
      fi
      $ADB shell rm -rf $remote 2>/dev/null
      $ADB unroot > /dev/null 2>&1
      adbd_as 2000 || echo "adbd did not come back unrooted in 30 s: run 'adb unroot'" >&2
    fi
  fi
  exit $rc
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# The events, as the kernel's struct input_event (two 64-bit time fields, type, code, value): one file for the
# down, one for each move, one for the up. The touch screen reports positions from 0 to 32767 each way.
python3 - "$dir" "${size%x*}" "${size#*x}" "$@" <<'PY' || usage
import math, struct, sys
out, w, h, kind, a = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4], sys.argv[5:]
def count(text):
    # A whole number of samples, written as digits: not 0, not negative, not "inf", not "1e3".
    if not text.isdigit() or not 1 <= int(text) <= 500: raise ValueError(text)
    return int(text)
def number(text):
    v = float(text)
    if not math.isfinite(v): raise ValueError(text)
    return v
try:
    if kind == "line":
        if len(a) not in (4, 5): raise ValueError
        x1, y1, x2, y2 = (number(v) for v in a[:4]); n = count(a[4]) if len(a) > 4 else 10
        pts = [(x1 + (x2 - x1) * i / n, y1 + (y2 - y1) * i / n) for i in range(n + 1)]
    elif kind == "arc":
        if len(a) not in (3, 4): raise ValueError
        a1, a2, f = (number(v) for v in a[:3]); n = count(a[3]) if len(a) > 3 else 20
        r = min(w, h) / 2
        at = lambda d: (w / 2 + r * f * math.sin(math.radians(d)), h / 2 - r * f * math.cos(math.radians(d)))
        pts = [at(a1 + (a2 - a1) * i / n) for i in range(n + 1)]
    else:
        n = count(a[0]); ks = [tuple(number(v) for v in p.split(",")) for p in a[1:]]
        if len(ks) < 2 or any(len(k) != 2 for k in ks): raise ValueError
        pts = [ks[0]]
        for (xa, ya), (xb, yb) in zip(ks, ks[1:]): pts += [(xa + (xb - xa) * i / n, ya + (yb - ya) * i / n) for i in range(1, n + 1)]
except (ValueError, IndexError, OverflowError, ZeroDivisionError):
    sys.exit(1)
EV_SYN, EV_KEY, EV_ABS = 0, 1, 3
SLOT, TOUCH_MAJOR, X, Y, TRACKING_ID, PRESSURE, BTN_TOUCH = 0x2f, 0x30, 0x35, 0x36, 0x39, 0x3a, 0x14a
ev = lambda t, c, v: struct.pack("<qqHHi", 0, 0, t, c, v)
sx = lambda v: max(0, min(32767, int(v / w * 32767))); sy = lambda v: max(0, min(32767, int(v / h * 32767)))
report = ev(EV_SYN, 0, 0)
with open(f"{out}/d", "wb") as f:
    f.write(ev(EV_ABS, SLOT, 0) + ev(EV_ABS, TRACKING_ID, 77) + ev(EV_ABS, X, sx(pts[0][0])) + ev(EV_ABS, Y, sy(pts[0][1]))
            + ev(EV_ABS, TOUCH_MAJOR, 200) + ev(EV_ABS, PRESSURE, 600) + ev(EV_KEY, BTN_TOUCH, 1) + report)
for i, (x, y) in enumerate(pts[1:], 1):
    with open(f"{out}/m{i:03d}", "wb") as f: f.write(ev(EV_ABS, X, sx(x)) + ev(EV_ABS, Y, sy(y)) + report)
with open(f"{out}/u", "wb") as f: f.write(ev(EV_ABS, TRACKING_ID, -1) + ev(EV_KEY, BTN_TOUCH, 0) + report)
PY
rooted=1
if [ "$($ADB shell id -u 2>/dev/null | tr -d '\r')" != "0" ]; then
  $ADB root > /dev/null 2>&1
  if ! adbd_as 0; then echo "adbd did not come back as root in 30 s (does this image allow 'adb root'?)" >&2; exit 1; fi
fi
# The touch screen of the main display is the first of the emulator's multi-touch devices; its event number
# differs between images.
dev=$($ADB shell cat /proc/bus/input/devices | tr -d '\r' | awk '/^N: /{touch = /"virtio_input_multi_touch_1"/} touch && /^H: / && match($0, /event[0-9]+/) {print substr($0, RSTART, RLENGTH)}' | head -1)
if [ -z "$dev" ]; then echo "no touch device named virtio_input_multi_touch_1 on this emulator" >&2; exit 1; fi
$ADB shell rm -rf $remote
if ! $ADB push "$dir" $remote > /dev/null 2>&1; then echo "could not copy the events to the device" >&2; exit 1; fi
down=1
# The gesture runs beside this script, which waits for it: a signal then reaches this script at once, not when the
# gesture has finished.
$ADB shell "cd $remote && cat d > /dev/input/$dev && for f in m*; do cat \$f > /dev/input/$dev; sleep 0.012; done; cat u > /dev/input/$dev" &
gesture=$!
wait $gesture || exit 1
gesture=""
down=0
exit 0
