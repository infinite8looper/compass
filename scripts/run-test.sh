#!/bin/zsh
# Usage: scripts/run-test.sh <Class[#method]> [more ...]
# Runs the named instrumented tests (in com.infinite8looper.compass) on the one attached emulator and prints each
# test's result with the first lines of a failure. The emulator must already be laid flat (scripts/set-heading.sh 40)
# for the tests that read a heading. Emulators only (scripts/emulator-only.sh). Gradle uninstalls the app at the end.
source "$(dirname "$0")/emulator-only.sh" || exit 1
cd "$(dirname "$0")/.." || exit 1
if [ -z "$JAVA_HOME" ]; then export JAVA_HOME=$(/usr/libexec/java_home -v 17) || exit 1; fi
classes=$(for t in "$@"; do print -n "com.infinite8looper.compass.$t,"; done)
./gradlew -q connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=${classes%,} 2>&1 | grep -v "^w: " | tail -5
python3 - <<'PY'
import xml.etree.ElementTree as ET, glob
for f in glob.glob('app/build/outputs/androidTest-results/connected/debug/*.xml'):
    for tc in ET.parse(f).getroot().iter('testcase'):
        fl = tc.find('failure')
        sk = tc.find('skipped')
        state = 'FAIL' if fl is not None else ('SKIP' if sk is not None else 'PASS')
        print(state, tc.get('classname').split('.')[-1] + '#' + tc.get('name'), tc.get('time') + ' s')
        if fl is not None: print('   ', '\n    '.join((fl.text or '').splitlines()[:4]))
PY
