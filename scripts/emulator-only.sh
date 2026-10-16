# Sourced, not run: `source "$(dirname "$0")/emulator-only.sh" || exit 1` near the top of every script here that
# talks to a device. These scripts set sensors, feed positions, tap, wipe the app's data and run the instrumented
# tests, which wipe it too. They are for emulators only.
#
# What it does, and what the function `emulator_only` does again whenever it is called:
#   - If what `adb devices` prints cannot be read as a list of devices, it prints that and returns 1.
#   - If `adb devices` lists anything whose serial is not `emulator-NNNN`, in whatever state (device, offline,
#     unauthorized), it prints why and returns 1. Nothing is sent to that device.
#   - If more than one emulator is attached it returns 1: the scripts work on one. Unless EMULATOR_ONLY_SERIAL names
#     one of them (below).
#   - If exactly one emulator is attached it exports ANDROID_SERIAL with its serial. adb addresses that device and
#     no other for every later call in the script, and so does Gradle's `connectedDebugAndroidTest`, which without
#     it runs on every attached device.
#   - If no emulator is attached it returns 1, unless EMULATOR_ONLY_MAY_BE_NONE=1 is set (the scripts that boot
#     one); ANDROID_SERIAL is then unset, and such a script must not call adb until an emulator is there.
#   - If EMULATOR_ONLY_SERIAL is set (an emulator's serial, emulator-NNNN), other emulators may be attached too: it
#     returns 1 if a device that is not an emulator is listed (as above), or if that serial is not listed with the
#     state `device` (with EMULATOR_ONLY_MAY_BE_NONE=1 it then returns 0 with ANDROID_SERIAL unset, for the scripts
#     that boot it); otherwise it exports ANDROID_SERIAL with that serial and nothing else. It never falls back to
#     another emulator. scripts/all-suites.sh runs several emulators at once and gives each process its own.
#   - If EMULATOR_ONLY_ANY=1 is set (and no EMULATOR_ONLY_SERIAL), any number of emulators may be attached, none
#     included: it returns 1 only for the reasons above that concern devices that are not emulators, and leaves
#     ANDROID_SERIAL unset, so a script that sets it must name each emulator it addresses (scripts/all-suites.sh).
# EMULATOR_ONLY_MAY_BE_NONE and EMULATOR_ONLY_ANY count only when the script that sources this sets them itself,
# unexported (`typeset +x EMULATOR_ONLY_ANY=1`): one that arrives from the environment, or is exported, is ignored, so
# neither can be handed to a script from outside (exported by hand, or leaked from another script).
# `emulator_pinned` is `emulator_only` and, besides, requires ANDROID_SERIAL to be set to an emulator's serial
# (emulator-NNNN): the scripts that address one emulator call it, so no adb call of theirs goes out unpinned.
# The answer comes from `adb devices` each time. A device plugged in after a check is not addressed by calls that
# are pinned, and is refused at the next check.
EMULATOR_ONLY_ADB=~/Library/Android/sdk/platform-tools/adb
emulator_only() {
  local out serials other emulators any=0 may_be_none=0
  # Set by the script itself and not exported, or not at all (above).
  [[ "$EMULATOR_ONLY_ANY" = 1 && ${(t)EMULATOR_ONLY_ANY} != *export* ]] && any=1
  [[ "$EMULATOR_ONLY_MAY_BE_NONE" = 1 && ${(t)EMULATOR_ONLY_MAY_BE_NONE} != *export* ]] && may_be_none=1
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
  if [ -n "$EMULATOR_ONLY_SERIAL" ]; then
    if [[ ! $EMULATOR_ONLY_SERIAL =~ '^emulator-[0-9]+$' ]]; then      # the whole value, one line
      unset ANDROID_SERIAL
      echo "refusing: EMULATOR_ONLY_SERIAL is not an emulator's serial ($EMULATOR_ONLY_SERIAL)." >&2
      return 1
    fi
    if print -r -- "$listed" | grep -q -E "^${EMULATOR_ONLY_SERIAL}$(printf '\t')device\$"; then
      export ANDROID_SERIAL=$EMULATOR_ONLY_SERIAL
      return 0
    fi
    unset ANDROID_SERIAL
    if [ $may_be_none = 1 ]; then return 0; fi
    echo "refusing: the emulator $EMULATOR_ONLY_SERIAL is not attached and ready." >&2
    return 1
  fi
  if [ $any = 1 ]; then
    unset ANDROID_SERIAL
    return 0
  fi
  if [ ${#emulators} -gt 1 ]; then
    unset ANDROID_SERIAL
    echo "refusing: more than one emulator is attached (${emulators[*]}). Stop all but one." >&2
    return 1
  fi
  if [ ${#emulators} -eq 0 ]; then
    unset ANDROID_SERIAL
    if [ $may_be_none = 1 ]; then return 0; fi
    echo "no emulator is attached: start one with scripts/emulator.sh" >&2
    return 1
  fi
  export ANDROID_SERIAL=${emulators[1]}
  return 0
}
emulator_pinned() {
  emulator_only || return 1
  if [[ "$ANDROID_SERIAL" != emulator-<-> ]]; then
    unset ANDROID_SERIAL
    echo "refusing: no emulator's serial to address (ANDROID_SERIAL is not emulator-NNNN), so no call is sent." >&2
    return 1
  fi
}
emulator_only
