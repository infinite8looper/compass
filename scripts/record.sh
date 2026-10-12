#!/bin/zsh
# Usage: scripts/record.sh <out.mp4> <seconds> [command ...]
# Records the attached emulator's screen for <seconds> (180 at most) into <out.mp4>, running the command, if one
# is given, while the recording runs: for example
#   scripts/record.sh /some/folder/press.mp4 12 'sleep 2; ~/Library/Android/sdk/platform-tools/adb shell input keyevent KEYCODE_STEM_1'
# Extra options for the device's screenrecord can be given in RECORD_OPTS, such as RECORD_OPTS="--bit-rate 2000000".
# Recordings are for review and do not belong in the repository.
ADB=~/Library/Android/sdk/platform-tools/adb
if [ $# -lt 2 ] || [[ "$2" != <-> ]]; then
  echo "usage: scripts/record.sh <out.mp4> <seconds> [command ...]" >&2
  exit 1
fi
out=$1; secs=$2; shift 2
($ADB shell screenrecord ${=RECORD_OPTS} --time-limit $secs /sdcard/compass-record.mp4 > /dev/null 2>&1 &)
started=$(date +%s)
sleep 1   # the recorder needs a moment before its first frame
if [ $# -gt 0 ]; then eval "$@"; fi
# The recorder stops by itself at the time limit and then writes the file's index: wait for the process to go.
deadline=$(( started + secs + 30 ))
while [ $(date +%s) -lt $(( started + secs )) ] || $ADB shell pgrep screenrecord > /dev/null; do
  if [ $(date +%s) -gt $deadline ]; then echo "the recorder is still running 30 s after its time limit" >&2; exit 1; fi
  sleep 1
done
sleep 1
# adb reports the transfer on its error output: it is shown only when the pull fails.
pulled=$($ADB pull /sdcard/compass-record.mp4 "$out" 2>&1) || { echo "could not pull the recording: $pulled" >&2; exit 1; }
$ADB shell rm /sdcard/compass-record.mp4
echo "wrote $out ($(( $(stat -f %z "$out") / 1024 )) KB)"
