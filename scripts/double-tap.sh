#!/bin/zsh
# Usage: scripts/double-tap.sh [x y]
# Two taps one right after the other at the same place on the attached emulator's screen, in screen pixels: on the
# compass that is the double tap that saves the position (a waypoint, or a crumb while a trail is being laid).
# With no place given it taps the middle of the screen's width, three tenths of the way down, where the compass
# has no tap area of its own.
# Both taps are sent by one shell on the device, a tenth of a second apart, so that the second follows the first
# inside the system's double-tap time (300 ms) and not sooner than a double tap may (40 ms). It prints how long
# after the first the second was sent, by the device's clock: where that is over 300 ms the taps were two single
# taps and saved nothing.
# Emulators only.
source "$(dirname "$0")/emulator-only.sh" || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
if [ $# -ne 0 ] && [ $# -ne 2 ]; then
  echo "usage: scripts/double-tap.sh [x y]" >&2
  exit 1
fi
if [ $# -eq 2 ]; then
  x=$1; y=$2
else
  size=$($ADB shell wm size | tr -d '\r' | sed -n 's/^Physical size: //p')
  w=${size%x*}; h=${size#*x}
  if ! [[ "$w" == <-> && "$h" == <-> ]]; then echo "could not read the screen's size" >&2; exit 1; fi
  x=$((w / 2)); y=$((h * 3 / 10))
fi
if ! [[ "$x" == <-> && "$y" == <-> ]]; then echo "x and y must be whole numbers of pixels" >&2; exit 1; fi
# The pause keeps the second tap from following the first sooner than a double tap may (40 ms).
gap=$($ADB shell "a=\$(date +%s%N); input tap $x $y; sleep 0.1; b=\$(date +%s%N); input tap $x $y; echo \$(( (b - a) / 1000000 ))" | tr -d '\r')
echo "double tap at $x $y, the second tap sent $gap ms after the first"
