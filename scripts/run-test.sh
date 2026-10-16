#!/bin/zsh
# Usage: scripts/run-test.sh <Class[#method]> [more ...]
# Runs the named instrumented tests (classes of com.infinite8looper.compass, or one test of a class) on the one
# attached emulator, one Gradle run for each name (a list in the `class` argument runs only its first entry), and
# prints each test's result with the first lines of a failure. Exit status 1 if any failed or was skipped.
# For the tests that read a heading, lay the emulator flat first (scripts/set-heading.sh 40). Gradle uninstalls the
# app at the end of each run. While the tests run, scripts/field-requests.sh passes a test's requests for a magnetic
# field to the emulator's sensor console; it is stopped at the end. Emulators only (scripts/emulator-only.sh).
source "$(dirname "$0")/emulator-only.sh" || exit 1
cd "$(dirname "$0")/.." || exit 1
if [ -z "$1" ]; then echo "usage: scripts/run-test.sh <Class[#method]> [more ...]" >&2; exit 1; fi
if [ -z "$JAVA_HOME" ]; then export JAVA_HOME=$(/usr/libexec/java_home -v 17) || exit 1; fi
bad=0
scripts/field-requests.sh 86400 > /dev/null 2>&1 &
fields=$!
trap 'kill $fields 2>/dev/null; wait $fields 2>/dev/null; exit 1' INT TERM
for t in "$@"; do
  emulator_only || { kill $fields 2>/dev/null; wait $fields 2>/dev/null; exit 1; }
  ./gradlew -q connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.infinite8looper.compass.$t > /dev/null 2>&1
  python3 - <<'PY' || bad=1
import xml.etree.ElementTree as ET, glob, sys
bad = False; seen = False
for f in glob.glob('app/build/outputs/androidTest-results/connected/debug/*.xml'):
    for tc in ET.parse(f).getroot().iter('testcase'):
        seen = True
        fl = tc.find('failure'); sk = tc.find('skipped')
        state = 'FAIL' if fl is not None else ('SKIP' if sk is not None else 'PASS')
        bad = bad or state != 'PASS'
        print(state, tc.get('classname').split('.')[-1] + '#' + tc.get('name'), tc.get('time') + ' s')
        if fl is not None: print('   ', '\n    '.join((fl.text or '').splitlines()[:4]))
if not seen: print('no result: the run did not start'); bad = True
sys.exit(1 if bad else 0)
PY
done
kill $fields 2>/dev/null; wait $fields 2>/dev/null
exit $bad
