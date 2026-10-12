#!/bin/zsh
# Usage: scripts/crown.sh <clicks> [times]     (times: 1 or more)
# Turns the attached emulator's crown by <clicks> (a whole number; negative turns it the other way), once or
# the given number of times, a fifth of a second apart.
# On API 34 and later this is `input rotaryencoder`, where one click scrolls by the system's scroll step (128 px on
# the emulators' screens). The API 33 image does not have that command, so there the turn is written to the
# crown's input device, in that device's own units, 72 of which scroll as far as one click. That needs adbd
# running as root: the script switches it to root and says so. Run `adb unroot` when done.
ADB=~/Library/Android/sdk/platform-tools/adb
if [[ "$1" != (|-)<-> ]] || [[ -n "$2" && "$2" != <-> ]] || [[ -n "$2" && "$2" -lt 1 ]]; then
  echo "usage: scripts/crown.sh <clicks> [times]" >&2
  exit 1
fi
sdk=$($ADB shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
if [[ "$sdk" != <-> ]]; then echo "no device" >&2; exit 1; fi
if [ $sdk -ge 34 ]; then
  for i in $(seq 1 ${2:-1}); do
    $ADB shell input rotaryencoder scroll --axis SCROLL,$1 || exit 1
    [ $i -lt ${2:-1} ] && sleep 0.2
  done
  exit 0
fi
if [ "$($ADB shell id -u | tr -d '\r')" != "0" ]; then
  $ADB root > /dev/null
  deadline=$(( $(date +%s) + 30 ))
  until [ "$($ADB shell id -u 2>/dev/null | tr -d '\r')" = "0" ]; do
    if [ $(date +%s) -gt $deadline ]; then echo "adbd did not come back as root in 30 s" >&2; exit 1; fi
    sleep 1
  done
  echo "adbd is now root: run 'adb unroot' when done" >&2
fi
# The crown is the input device named virtio_input_rotary; its event number differs between images.
dev=$($ADB shell cat /proc/bus/input/devices | tr -d '\r' | awk '/^N: /{rotary = /rotary/} rotary && /^H: / && match($0, /event[0-9]+/) {print substr($0, RSTART, RLENGTH)}' | head -1)
if [ -z "$dev" ]; then echo "no rotary input device on this emulator (hw.rotaryInput=yes in its config.ini?)" >&2; exit 1; fi
for i in $(seq 1 ${2:-1}); do
  # EV_REL (2), REL_WHEEL (8), then the report that ends the event.
  $ADB shell "sendevent /dev/input/$dev 2 8 $(( $1 * 72 )); sendevent /dev/input/$dev 0 0 0" || exit 1
  [ $i -lt ${2:-1} ] && sleep 0.2
done
exit 0
