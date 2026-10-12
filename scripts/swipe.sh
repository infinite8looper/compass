#!/bin/zsh
# Usage: scripts/swipe.sh left|right|up|down|diagonal [milliseconds] [fraction]
#        scripts/swipe.sh hold [milliseconds]
# Swipes across the middle of the attached emulator's screen, whatever its size: the finger travels the given
# fraction of the screen's width (0.6 if left out) in the given time (150 ms if left out), centered on the middle.
# "left" moves the finger to the left, which brings in the screen on the right.
#   diagonal   down and to the left, the same distance each way.
#   hold       a finger held still on the middle of the screen for the given time (800 ms if left out).
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
