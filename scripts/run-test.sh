#!/bin/zsh
# Usage: scripts/run-test.sh <Class[#method]> [more ...]
# The same as scripts/quick-tests.sh with the same names (which it runs): the named instrumented tests on the one
# attached emulator, in one run of the test runner, with scripts/field-requests.sh and scripts/geo-feed.sh running,
# laid flat at a magnetic heading of 40 first. Kept under this name for the places that name it.
exec "$(dirname "$0")/quick-tests.sh" "$@"
