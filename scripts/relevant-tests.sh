#!/bin/zsh
# Usage: scripts/relevant-tests.sh [<git range or commit>]
# Lists the test classes that cover the files changed, as names for scripts/quick-tests.sh. With no argument the
# changes are those of the working tree against HEAD (staged, unstaged and new files not yet added); with a range
# (for example HEAD~3..HEAD, or main...HEAD), `git diff --name-only` of it; with a commit (no `..` in it), that
# commit's own change, `git diff <commit>^!` (its parent to it), not the commit against the working tree.
# For each changed file:
#   - a test class itself (a file of app/src/androidTest/ or app/src/test/ with @Test in it): that class; any other
#     file there (a helper, an annotation) is mapped as a file of the app is, below;
#   - a Kotlin or Java file of the app (app/src/main/...): every test class whose source names, as a whole word,
#     the file's own name or one of the names it declares at its top level and does not mark private (classes,
#     objects, interfaces, functions, properties, type aliases);
#   - a resource file (app/src/main/res/...): every test class whose source names, as R.<type>.<name>, a resource
#     named on a changed line (name="..."), or the file itself for a drawable, layout and the like;
#   - anything else (the build files, the manifest, scripts/, docs/): not mapped; it is printed under "not mapped"
#     (a build file or the manifest can change what every test sees: run the full suite).
# It prints the device classes, the unit classes, the files it did not map, and last the command
# `scripts/quick-tests.sh <device classes> --unit <unit classes>`.
# Its limits: it reads names in the text of the sources, nothing more. A test that reaches the changed code only
# through other code (most device tests go through MainActivity and the view model) is not listed unless its own
# source names a changed declaration; a name that is a common word can list a test that does not use it; and a
# change of behavior with no name in common with any test lists nothing. It is the input to a narrow run while
# working, not a replacement for scripts/all-suites.sh at the end of a task.
# Talks to no device. Needs git and python3.
cd "$(dirname "$0")/.." || exit 1
if [ -n "$1" ]; then
  range=$1
  if [[ $range != *..* ]]; then
    git rev-parse --verify -q "$range^{commit}" > /dev/null || { echo "not a commit or a range: $range" >&2; exit 1; }
    range="$range^!"
  fi
  changed=$(git diff --name-only "$range") || exit 1
  diff=$(git diff -U0 "$range")
else
  changed=$( { git diff --name-only HEAD; git ls-files --others --exclude-standard; } | sort -u)
  diff=$(git diff -U0 HEAD)
fi
print -r -- "$diff" > "${TMPDIR:-/tmp}/relevant-tests-diff.$$"
print -r -- "$changed" | python3 -I -c '
import os, re, sys
diff_lines = open(sys.argv[1], encoding="utf-8", errors="replace").read().splitlines()
changed = [l.strip() for l in sys.stdin if l.strip()]
ROOTS = {"device": "app/src/androidTest", "unit": "app/src/test"}
tests = {}
for kind, root in ROOTS.items():
    for d, _, fs in os.walk(root):
        for f in fs:
            if f.endswith((".kt", ".java")):
                p = os.path.join(d, f)
                tests[p] = (kind, f.rsplit(".", 1)[0], open(p, encoding="utf-8", errors="replace").read())
# A file of the test sources with no @Test in it (a helper, an annotation) is mapped like a file of the app.
def is_test(p): return "@Test" in tests[p][2]
DECL = re.compile(r"^((?:(?:public|internal|private|protected|data|enum|sealed|abstract|open|inline|value|annotation|const|lateinit|suspend|operator|infix|tailrec|external|override|expect|actual)\s+)*)(?:fun\s+interface|class|object|interface|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)", re.M)
found = {"device": set(), "unit": set()}
unmapped = []
def names_used(names):
    hits = 0
    for p, (kind, cls, text) in tests.items():
        if not is_test(p): continue
        if any(re.search(r"\b" + re.escape(n) + r"\b", text) for n in names):
            found[kind].add(cls); hits += 1
    return hits
# Resource names on changed lines, by file.
res_names, cur = {}, None
for l in diff_lines:
    if l.startswith("+++ b/") or l.startswith("--- a/"):
        if l.startswith("+++ b/"): cur = l[6:]
        elif cur is None: cur = l[6:]
        continue
    if l.startswith("diff --git"): cur = None; continue
    if cur and (l.startswith("+") or l.startswith("-")):
        for n in re.findall(r"name=\"([\w.]+)\"", l):
            res_names.setdefault(cur, set()).add(n.replace(".", "_"))
for f in changed:
    hit = [p for p in tests if os.path.normpath(p) == os.path.normpath(f)]
    if hit and "@Test" in tests[hit[0]][2]:
        kind, cls, _ = tests[hit[0]]
        found[kind].add(cls)
        continue
    if f.startswith("app/src/main/res/"):
        names = set(res_names.get(f, set()))
        base = os.path.basename(f).rsplit(".", 1)[0]
        if not f.split("/")[4].startswith("values"): names.add(base)
        hits = 0
        for p, (kind, cls, text) in tests.items():
            if is_test(p) and any(re.search(r"\bR\.\w+\." + re.escape(n) + r"\b", text) for n in names):
                found[kind].add(cls); hits += 1
        if not hits: unmapped.append(f + " (no test names its resources)")
        continue
    if (f.startswith("app/src/main/") or hit) and f.endswith((".kt", ".java")):
        names = {os.path.basename(f).rsplit(".", 1)[0]}
        if os.path.exists(f):
            for mods, n in DECL.findall(open(f, encoding="utf-8", errors="replace").read()):
                if "private" not in mods.split(): names.add(n)
        if not names_used(names): unmapped.append(f + " (no test names what it declares)")
        continue
    unmapped.append(f)
print("device:", " ".join(sorted(found["device"])) or "(none)")
print("unit:", " ".join(sorted(found["unit"])) or "(none)")
for u in unmapped: print("not mapped:", u)
cmd = "scripts/quick-tests.sh " + " ".join(sorted(found["device"]))
if found["unit"]: cmd += " --unit " + " ".join(sorted(found["unit"]))
print(cmd if found["device"] or found["unit"] else "# nothing to run")
' "${TMPDIR:-/tmp}/relevant-tests-diff.$$"
rc=$?
rm -f "${TMPDIR:-/tmp}/relevant-tests-diff.$$"
exit $rc
