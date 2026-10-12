#!/bin/zsh
# Usage: scripts/burst.sh <out.png> <count> [gap-seconds]
# Takes <count> screenshots of the attached emulator one after another, as fast as the device gives them (about
# three a second) or with the given pause between them, and joins them side by side, first on the left, into
# <out.png>. For catching something that moves: start it in the background, then make the move.
# The single pictures are taken into a temporary folder that is removed afterwards. Needs ffmpeg.
ADB=~/Library/Android/sdk/platform-tools/adb
FFMPEG=/opt/homebrew/bin/ffmpeg
if [ $# -lt 2 ] || [[ "$2" != <-> ]] || [ "$2" -lt 1 ] || [[ -n "$3" && "$3" != <->(|.<->) ]]; then
  echo "usage: scripts/burst.sh <out.png> <count> [gap-seconds]" >&2
  exit 1
fi
if [ ! -x $FFMPEG ]; then echo "no ffmpeg at $FFMPEG" >&2; exit 1; fi
dir=$(mktemp -d) || exit 1
trap 'rm -rf "$dir"' EXIT
inputs=()
for i in $(seq 1 $2); do
  shot=$dir/$i.png
  # The device's own error output is dropped there: exec-out would append it to the picture.
  $ADB exec-out "screencap -p 2>/dev/null" > $shot
  if [ "$(head -c 8 $shot | xxd -p)" != "89504e470d0a1a0a" ]; then
    echo "no screenshot: the device did not return a PNG (picture $i)" >&2
    exit 1
  fi
  inputs+=(-i $shot)
  if [ -n "$3" ] && [ $i -lt $2 ]; then sleep $3; fi
done
if [ $2 -eq 1 ]; then
  cp $dir/1.png "$1"
else
  $FFMPEG -v error -y $inputs -filter_complex "hstack=inputs=$2" "$1" || { echo "could not join the pictures" >&2; exit 1; }
fi
echo "wrote $1 ($2 pictures)"
