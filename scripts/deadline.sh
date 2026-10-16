# Sourced, not run: `source "$(dirname "$0")/deadline.sh"`.
# `deadline <seconds> <what> <command> [arguments ...]` runs the command (a program or a function of the script) in
# the background and waits for it. It returns the command's exit status, or, if the command has not ended after that
# many seconds, stops it and the processes it started (TERM), prints "<what> did not end in <seconds> s and was
# stopped" and returns 1. scripts/device-tests.sh puts the installs and the listing under it, scripts/all-suites.sh
# the build and the listing of the tests marked TakesMinutes.
deadline() {
  local secs=$1 what=$2; shift 2
  "$@" &
  local p=$! end=$(( $(date +%s) + secs ))
  while kill -0 $p 2>/dev/null; do
    if [ $(date +%s) -gt $end ]; then
      pkill -TERM -P $p 2>/dev/null; kill -TERM $p 2>/dev/null; wait $p 2>/dev/null
      echo "$what did not end in $secs s and was stopped" >&2
      return 1
    fi
    sleep 1
  done
  wait $p
}
