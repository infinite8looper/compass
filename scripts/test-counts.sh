#!/bin/zsh
# Usage: scripts/test-counts.sh unit|device|all
# Prints, from the result XML of the last run, how many tests each class has, each failure, and the totals.
#   unit    app/build/test-results/                              (./gradlew testDebugUnitTest)
#   device  app/build/outputs/androidTest-results/connected/     (./gradlew connectedDebugAndroidTest)
# Exits 1 if a test failed or no result file was found, so it can end a chain of commands.
if [ "$1" != "unit" ] && [ "$1" != "device" ] && [ "$1" != "all" ]; then
  echo "usage: scripts/test-counts.sh unit|device|all" >&2
  exit 1
fi
cd "$(dirname "$0")/.." || exit 1
python3 - "$1" <<'PY'
import collections, glob, sys
import xml.etree.ElementTree as ET

WHERE = {
    "unit": "app/build/test-results/**/*.xml",
    "device": "app/build/outputs/androidTest-results/connected/**/*.xml",
}
bad = False
for kind in (["unit", "device"] if sys.argv[1] == "all" else [sys.argv[1]]):
    files = sorted(glob.glob(WHERE[kind], recursive=True))
    if not files:
        print(f"{kind}: no result files under {WHERE[kind]}")
        bad = True
        continue
    passed, other, problems = collections.Counter(), collections.Counter(), []
    for path in files:
        root = ET.parse(path).getroot()
        for suite in ([root] if root.tag == "testsuite" else root.iter("testsuite")):
            for case in suite.iter("testcase"):
                cls = case.get("classname").split(".")[-1]
                failure = case.find("failure") if case.find("failure") is not None else case.find("error")
                if failure is not None:
                    other[(cls, "failed")] += 1
                    problems.append((cls, case.get("name"), ((failure.get("message") or failure.text or "").strip().splitlines() or [""])[0][:300]))
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
    bad = bad or failed > 0
sys.exit(1 if bad else 0)
PY
