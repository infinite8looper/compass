# Sourced, not run: `source "$(dirname "$0")/emulator-only.sh" || exit 1` near the top of every script here that
# talks to a device. These scripts set sensors, feed positions, tap, wipe the app's data and run the instrumented
# tests, which wipe it too. They are for emulators only.
#
# What it does, and what the function `emulator_only` does again whenever it is called:
#   - If what `adb devices` prints cannot be read as a list of devices, it prints that and returns 1.
#   - If `adb devices` lists anything whose serial is not `emulator-NNNN`, in whatever state (device, offline,
#     unauthorized), it prints why and returns 1. Nothing is sent to that device.
#   - If more than one emulator is attached it returns 1: the scripts work on one.
#   - If exactly one emulator is attached it exports ANDROID_SERIAL with its serial. adb addresses that device and
#     no other for every later call in the script, and so does Gradle's `connectedDebugAndroidTest`, which without
#     it runs on every attached device.
#   - If no emulator is attached it returns 1, unless EMULATOR_ONLY_MAY_BE_NONE=1 is set (the scripts that boot
#     one); ANDROID_SERIAL is then unset, and such a script must not call adb until an emulator is there.
# The answer comes from `adb devices` each time. A device plugged in after a check is not addressed by calls that
# are pinned, and is refused at the next check.
EMULATOR_ONLY_ADB=~/Library/Android/sdk/platform-tools/adb
emulator_only() {
  local out serials other emulators
  out=$($EMULATOR_ONLY_ADB devices 2>/dev/null)
  # adb can print lines of its own before the list (it does when this call starts its server). The devices are the
  # lines after the one that heads the list, each a serial, a tab and a state. No heading, or a line there that is
  # not such a line, is not understood, and what is not understood is refused.
  if ! print -r -- "$out" | grep -q '^List of devices'; then
    unset ANDROID_SERIAL
    echo "refusing: what adb devices printed could not be read as a list of devices, so it is not known that only an emulator is attached." >&2
    return 1
  fi
  local listed
  listed=$(print -r -- "$out" | awk 'found && NF { print } /^List of devices/ { found = 1 }')
  if [ -n "$listed" ] && print -r -- "$listed" | grep -v -q -E "^[^[:space:]]+$(printf '\t')[^[:space:]]"; then
    unset ANDROID_SERIAL
    echo "refusing: a line of adb devices could not be read as a device, so it is not known that only an emulator is attached." >&2
    return 1
  fi
  serials=$(print -r -- "$listed" | awk 'NF { print $1 }')
  other=$(echo "$serials" | grep -v -E '^emulator-[0-9]+$' | grep . | tr '\n' ' ')
  if [ -n "$other" ]; then
    unset ANDROID_SERIAL
    echo "refusing: a device that is not an emulator is attached (${other% }). These scripts are for emulators only; unplug it or disconnect it (adb disconnect) and run again." >&2
    return 1
  fi
  emulators=($(echo "$serials" | grep -E '^emulator-[0-9]+$'))
  if [ ${#emulators} -gt 1 ]; then
    unset ANDROID_SERIAL
    echo "refusing: more than one emulator is attached (${emulators[*]}). Stop all but one." >&2
    return 1
  fi
  if [ ${#emulators} -eq 0 ]; then
    unset ANDROID_SERIAL
    if [ "$EMULATOR_ONLY_MAY_BE_NONE" = "1" ]; then return 0; fi
    echo "no emulator is attached: start one with scripts/emulator.sh" >&2
    return 1
  fi
  export ANDROID_SERIAL=${emulators[1]}
  return 0
}
emulator_only
