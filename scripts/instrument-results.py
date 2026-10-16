"""Reads the raw output of `adb shell am instrument -r -w ...` (the instrumented tests, run without Gradle).

Usage (run with python3 -I):
  instrument-results.py stamp < raw > stamped
      Copies each line of its input to its output with the host's time in milliseconds and a tab in front, as the
      line arrives, so that each test's time can be taken from when its start and its end were printed.
  instrument-results.py report <stamped> <out.xml> <device-name> [<stamped listing>]
      Writes the results as JUnit XML in the form the Android Gradle plugin writes for connectedDebugAndroidTest
      (one <testsuite> per class, a <failure> holding the stack trace; a test JUnit skipped because an assumption
      did not hold is written as the plugin writes it, a <failure> whose text is the AssumptionViolatedException,
      which scripts/test-counts.sh counts as skipped; a test marked @Ignore is written with <skipped/>), and prints
      one line per test: PASS, FAIL (with the first four lines of its stack trace), SKIP, or NOT RUN.
      A test that started and has no end (the process died in it) is written and printed as failed.
      The listing is the output of the same run made with `-e log true`, which names every test the run would run
      without running any: a test it names that the run has no result for is printed NOT RUN and written as failed.
      Exit status 1 if any test failed, was skipped or was not run, if the run did not end with its own result
      (INSTRUMENTATION_CODE -1), or if no test ran at all; 0 otherwise.
  instrument-results.py list <stamped listing>
      Prints the tests a `-e log true` run named, one Class#method per line (the class's simple name). Exit status 1
      if that run did not end with its own result.
"""
import sys
import time
import xml.etree.ElementTree as ET

ASSUMPTION = ("org.junit.AssumptionViolatedException", "org.junit.internal.AssumptionViolatedException")


def stamp():
    for line in sys.stdin:
        sys.stdout.write(f"{int(time.time() * 1000)}\t{line.rstrip(chr(10)).rstrip(chr(13))}\n")
        sys.stdout.flush()


def parse(path):
    """The status blocks in order, each (time ms, code, {key: value}), and the run's result code and message."""
    blocks, fields, key = [], {}, None
    result_code, result_text, result = None, [], False
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            raw = raw.rstrip("\n")
            ms, _, line = raw.partition("\t")
            try:
                ms = int(ms)
            except ValueError:
                ms, line = 0, raw
            if line.startswith("INSTRUMENTATION_STATUS: "):
                k, _, v = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
                key = k
                fields[k] = v
                result = False
            elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
                try:
                    code = int(line.split(": ", 1)[1])
                except ValueError:
                    code = None
                blocks.append((ms, code, fields))
                fields, key = {}, None
            elif line.startswith("INSTRUMENTATION_RESULT: "):
                result = True
                key = None
                result_text.append(line[len("INSTRUMENTATION_RESULT: "):])
            elif line.startswith("INSTRUMENTATION_CODE: "):
                try:
                    result_code = int(line.split(": ", 1)[1])
                except ValueError:
                    result_code = None
                result = False
            elif result:
                result_text.append(line)
            elif key is not None:
                fields[key] += "\n" + line
    return blocks, result_code, "\n".join(result_text).strip()


def tests(blocks):
    """Each test in the order it started: [class, method, start ms, end ms or None, state, stack]."""
    out, open_ = [], {}
    for ms, code, f in blocks:
        cls, name = f.get("class"), f.get("test")
        if not cls or name is None:
            continue
        k = (cls, name)
        if code == 1:
            t = [cls, name, ms, None, None, ""]
            out.append(t)
            open_[k] = t
        else:
            t = open_.pop(k, None)
            if t is None:
                t = [cls, name, ms, None, None, ""]
                out.append(t)
            t[3] = ms
            stack = f.get("stack", "")
            if code == 0:
                t[4] = "pass"
            elif code == -3:
                t[4] = "ignored"
            elif code == -4 or (code in (-1, -2) and stack.strip().split(":")[0].strip() in ASSUMPTION):
                t[4] = "assumption"
                t[5] = stack
            else:
                t[4] = "fail"
                t[5] = stack or f.get("stream", "") or f"status code {code}"
    return out


def report(stamped, out_xml, device, listing=None):
    blocks, code, text = parse(stamped)
    ran = tests(blocks)
    bad = False
    for t in ran:
        if t[4] is None:
            t[4] = "fail"
            t[5] = "the test started and did not end: " + (text.splitlines()[0] if text else "the run stopped")
    names = {(t[0], t[1]) for t in ran}
    if listing:
        lblocks, lcode, ltext = parse(listing)
        if lcode != -1:
            print(f"the listing run did not end with its own result: {ltext.splitlines()[0] if ltext else code}")
            bad = True
        for t in tests(lblocks):
            if (t[0], t[1]) not in names:
                ran.append([t[0], t[1], 0, 0, "notrun", "not run: the listing of this run names it, and the run has no result for it"])
                names.add((t[0], t[1]))
    by_class = {}
    for t in ran:
        by_class.setdefault(t[0], []).append(t)
    root = ET.Element("testsuites", tests=str(len(ran)), failures=str(sum(t[4] in ("fail", "notrun") for t in ran)),
                      errors="0", skipped=str(sum(t[4] in ("ignored", "assumption") for t in ran)), time="0.000")
    for cls, ts in by_class.items():
        suite = ET.SubElement(root, "testsuite", name=cls, tests=str(len(ts)),
                              failures=str(sum(t[4] in ("fail", "notrun") for t in ts)), errors="0",
                              skipped=str(sum(t[4] in ("ignored", "assumption") for t in ts)),
                              time=f"{sum(((t[3] or t[2]) - t[2]) for t in ts) / 1000:.3f}")
        props = ET.SubElement(suite, "properties")
        ET.SubElement(props, "property", name="device", value=device)
        for t in ts:
            case = ET.SubElement(suite, "testcase", name=t[1], classname=cls,
                                 time=f"{((t[3] or t[2]) - t[2]) / 1000:.3f}")
            if t[4] in ("fail", "notrun", "assumption"):
                ET.SubElement(case, "failure").text = t[5]
            elif t[4] == "ignored":
                ET.SubElement(case, "skipped")
    ET.ElementTree(root).write(out_xml, encoding="UTF-8", xml_declaration=True)
    for t in ran:
        short = t[0].split(".")[-1] + "#" + t[1]
        secs = f"{((t[3] or t[2]) - t[2]) / 1000:.1f} s"
        state = {"pass": "PASS", "fail": "FAIL", "notrun": "NOT RUN", "ignored": "SKIP", "assumption": "SKIP"}[t[4]]
        print(state, short, secs if t[4] != "notrun" else "")
        if t[4] in ("fail", "assumption"):
            print("    " + "\n    ".join(t[5].strip().splitlines()[:4]))
        bad = bad or t[4] != "pass"
    if code != -1:
        print(f"the run did not end with its own result (INSTRUMENTATION_CODE {code}): {text.splitlines()[0] if text else 'no result line'}")
        bad = True
    if not ran:
        print("no test ran")
        bad = True
    return 1 if bad else 0


def listing(stamped):
    blocks, code, text = parse(stamped)
    for t in tests(blocks):
        print(t[0].split(".")[-1] + "#" + t[1])
    if code != -1:
        print(f"the listing run did not end with its own result: {text.splitlines()[0] if text else code}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else ""
    if what == "stamp":
        stamp()
    elif what == "report" and len(sys.argv) in (5, 6):
        sys.exit(report(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5] if len(sys.argv) == 6 else None))
    elif what == "list" and len(sys.argv) == 3:
        sys.exit(listing(sys.argv[2]))
    else:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
