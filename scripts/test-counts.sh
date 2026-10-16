#!/bin/zsh
# Usage: scripts/test-counts.sh unit|device|all
#        scripts/test-counts.sh device <folder>     the device results in <folder> instead (a copy kept from an earlier run)
# Prints, from the result XML of the last run, how many tests each class has, each failure, each test skipped
# because an assumption did not hold (with its reason), and the totals.
#   unit    app/build/test-results/                              (./gradlew testDebugUnitTest)
#   device  app/build/outputs/androidTest-results/connected/     (./gradlew connectedDebugAndroidTest)
# Exits 1 if a test failed or no result file was found, so it can end a chain of commands.
if [ "$1" != "unit" ] && [ "$1" != "device" ] && [ "$1" != "all" ]; then
  echo "usage: scripts/test-counts.sh unit|device|all   or   scripts/test-counts.sh device <folder>" >&2
  exit 1
fi
if [ -n "$2" ] && { [ "$1" != "device" ] || [ ! -d "$2" ]; }; then
  echo "a folder can be given only with device, and it has to exist: $2" >&2
  exit 1
fi
folder=""
if [ -n "$2" ]; then folder="$(cd "$2" && pwd)" || exit 1; fi
cd "$(dirname "$0")/.." || exit 1
python3 - "$1" "$folder" <<'PY'
import collections, glob, sys
import xml.etree.ElementTree as ET

WHERE = {
    "unit": "app/build/test-results/**/*.xml",
    "device": "app/build/outputs/androidTest-results/connected/**/*.xml",
}
if len(sys.argv) > 2 and sys.argv[2]:
    WHERE["device"] = sys.argv[2] + "/**/*.xml"
ASSUMPTION_FAILURES = {"org.junit.AssumptionViolatedException", "org.junit.internal.AssumptionViolatedException"}
bad = False
for kind in (["unit", "device"] if sys.argv[1] == "all" else [sys.argv[1]]):
    files = sorted(glob.glob(WHERE[kind], recursive=True))
    if not files:
        print(f"{kind}: no result files under {WHERE[kind]}")
        bad = True
        continue
    passed, other, problems, notes = collections.Counter(), collections.Counter(), [], []
    for path in files:
        root = ET.parse(path).getroot()
        for suite in ([root] if root.tag == "testsuite" else root.iter("testsuite")):
            for case in suite.iter("testcase"):
                cls = case.get("classname").split(".")[-1]
                failure = case.find("failure") if case.find("failure") is not None else case.find("error")
                words = ((failure.get("message") or failure.text or "").strip().splitlines() or [""])[0][:300] if failure is not None else ""
                # A test that JUnit skipped because an assumption did not hold (`assumeTrue`) is written by the
                # Android plugin's device results as a failure whose text is the AssumptionViolatedException, while
                # Gradle itself counts it as skipped and the build succeeds. It is counted here as Gradle counts it.
                # Only that: what stands before the first colon of the first line, or the whole line when it has
                # no colon, has to be the name of one of JUnit's two assumption classes and nothing else.
                if failure is not None and words.split(":")[0].strip() in ASSUMPTION_FAILURES:
                    other[(cls, "skipped")] += 1
                    notes.append((cls, case.get("name"), words.split(":", 1)[-1].strip()))
                elif failure is not None:
                    other[(cls, "failed")] += 1
                    problems.append((cls, case.get("name"), words))
                elif case.find("skipped") is not None:
                    other[(cls, "skipped")] += 1
                else:
                    passed[cls] += 1
    classes = sorted(set(passed) | {c for c, _ in other})
    for cls in classes:
        extra = "".join(f", {other[(cls, k)]} {k}" for k in ("failed", "skipped") if other[(cls, k)])
        print(f"{kind}: {cls} {passed[cls]} passed{extra}")
    failed = sum(n for (_, k), n in other.items() if k == "failed")
    skipped = sum(n for (_, k), n in other.items() if k == "skipped")
    print(f"{kind}: total {sum(passed.values())} passed, {failed} failed, {skipped} skipped, in {len(files)} file(s)")
    for cls, name, text in problems:
        print(f"  FAILED {cls}.{name}: {text}")
    for cls, name, text in notes:
        print(f"  SKIPPED {cls}.{name}: {text}")
    bad = bad or failed > 0
sys.exit(1 if bad else 0)
PY
