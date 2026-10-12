#!/bin/zsh
# Usage: scripts/swipe.sh left|right|up|down|diagonal [milliseconds] [fraction]
#        scripts/swipe.sh hold [milliseconds]
# Swipes across the middle of the attached emulator's screen, whatever its size: the finger travels the given
# fraction of the screen's width (0.6 if left out) in the given time (150 ms if left out), centered on the middle.
# "left" moves the finger to the left, which brings in the screen on the right.
#   diagonal   down and to the left, the same distance each way.
#   hold       a finger held still on the middle of the screen for the given time (800 ms if left out).
# A scripted swipe back from a screen opened from a row (edit, calibration) can leave the screen open, about once
# in forty: `input swipe` waits for the app to handle each touch sample and places the next one by the clock. When
# the app takes more than about 40 ms over one sample (seen on the first swipe back after a start, when the screen
# underneath is being built) the samples it would have sent meanwhile are never sent, and the lift follows a
# sample that is short of half the screen and reads as a finger at rest. The screen goes back for a swipe that has
# passed half the width or is faster than about 2000 px a second, and this one is neither. A check that depends on
# going back should look, and swipe again. On the edit screen a swipe of more than about 0.7 of the width starts
# on the color wheel, which takes it.
ADB=~/Library/Android/sdk/platform-tools/adb
usage() { echo "usage: scripts/swipe.sh left|right|up|down|diagonal [milliseconds] [fraction] | hold [milliseconds]" >&2; exit 1; }
[[ "$1" == (left|right|up|down|diagonal|hold) ]] || usage
[[ -z "$2" || "$2" == <-> ]] || usage
# A fraction is a number from 0 to 1.
[[ -z "$3" || "$3" == <->(|.<->) ]] || usage
[[ -z "$3" ]] || (( $3 <= 1 )) || usage
size=$($ADB shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Physical size: //p')
if [[ "$size" != <->x<-> ]]; then echo "no device, or it did not say its screen size" >&2; exit 1; fi
w=${size%x*}; h=${size#*x}
far=$(( w * ${3:-0.6} / 2 )); far=${far%.*}
cx=$(( w / 2 )); cy=$(( h / 2 ))
case $1 in
  left)     from=($(( cx + far )) $cy); to=($(( cx - far )) $cy) ;;
  right)    from=($(( cx - far )) $cy); to=($(( cx + far )) $cy) ;;
  up)       from=($cx $(( cy + far ))); to=($cx $(( cy - far ))) ;;
  down)     from=($cx $(( cy - far ))); to=($cx $(( cy + far ))) ;;
  diagonal) from=($(( cx + far )) $(( cy - far ))); to=($(( cx - far )) $(( cy + far ))) ;;
  hold)     from=($cx $cy); to=($cx $cy) ;;
esac
ms=${2:-$([ "$1" = hold ] && echo 800 || echo 150)}
$ADB shell input swipe $from $to $ms
