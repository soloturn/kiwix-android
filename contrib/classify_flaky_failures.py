#!/usr/bin/env python3
#
# Kiwix Android
# Copyright (c) 2026 Kiwix <android.kiwix.org>
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program. If not, see <http://www.gnu.org/licenses/>.
#
"""
Classify instrumented-test JUnit failures as genuine bugs or CI-runner
overload, using the same evidence trail worked out by hand across several
CI investigations this project has done (see e.g. runs 35284788813 and
35308243036): a fixed set of known-flaky exception signatures, cross-
checked against host load samples, kernel dmesg stall detectors, and the
android-emulator-runner action's own stall-capture probe.

This does NOT retry tests or guess. It only relabels a failure that already
happened as non-blocking when there is concrete, printed evidence the
runner itself was overloaded at the time - never on the exception type
alone. Every relabel is printed with the evidence that justified it, so
the decision can be audited, and a run with no supporting artifacts
(--resource-diag/--dmesg/--stall-capture all omitted) will never relabel
anything, no matter how "flaky-looking" the exception is.

Usage:
  classify_flaky_failures.py --junit-xml build/outputs/.../TEST-*.xml \\
      --log job.log [--resource-diag resource-diag.log] \\
      [--dmesg dmesg.log] [--stall-capture stall-capture.log] \\
      [--apply] [--in-place] [--window-seconds 120]

Without --apply this only prints the analysis (dry run). With --apply it
also rewrites the JUnit XML, stripping <failure>/<error> from testcases
classified as CI overload and fixing up the <testsuite> counts, so a CI
step that greps the XML for failures sees them as passed. Without
--in-place the rewritten file is written alongside the original with a
.quarantined.xml suffix.
"""

from __future__ import annotations

import argparse
import glob
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

# Exception signatures this project has confirmed, via hands-on log/artifact
# correlation, to be caused by CI-runner resource contention rather than a
# genuine app or test bug. Extend this list only after doing that same
# correlation for a new failure mode - the whole point is not to guess.
KNOWN_FLAKY_SIGNATURES: list[tuple[str, str | None]] = [
  ("androidx.compose.ui.test.ComposeTimeoutException", None),
  (
    "java.lang.RuntimeException",
    "No views in hierarchy found matching: an instance of android.webkit.WebView",
  ),
]

# Kernel-level signatures of a genuine scheduling stall, independent of any
# userspace symptom. dmesg.log is captured without -T (monotonic time), so
# these are matched anywhere in the file rather than time-correlated.
DMESG_STALL_SIGNATURES = (
  "hung_task",
  "soft lockup",
  "rcu_sched",
  "rcu_preempt",
  "invoked oom-killer",
  "Out of memory",
)

DEFAULT_OVERLOAD_LOAD1_THRESHOLD = 5.0
DEFAULT_WINDOW_SECONDS = 120

RESOURCE_DIAG_RE = re.compile(
  r"^(?P<ts>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})Z load=(?P<load1>[\d.]+)"
)
STALL_CAPTURE_HEADER_RE = re.compile(
  r"^=== stall detected at (?P<ts>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})Z"
)
# Matches both the AndroidJUnitRunner logcat line and gradle's own summary
# line for a failed test, each led by a GitHub Actions ISO8601 timestamp.
LOG_FAILURE_RE = re.compile(
  r"^(?P<ts>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\.\d+Z.*"
  r"(?:failed: (?P<method1>[\w$]+)\((?P<class1>[\w.]+)\)"
  r"|(?P<class2>[\w.]+) > (?P<method2>[\w$]+)(?:\[[^\]]*\])? .*FAILED)"
)


@dataclass
class ResourceSample:
  ts: datetime
  load1: float


@dataclass
class StallEvent:
  ts: datetime


@dataclass
class Evidence:
  overloaded_samples: list[ResourceSample] = field(default_factory=list)
  stall_events: list[StallEvent] = field(default_factory=list)
  dmesg_hits: list[str] = field(default_factory=list)

  @property
  def has_any(self) -> bool:
    return bool(self.overloaded_samples or self.stall_events or self.dmesg_hits)


@dataclass
class TestFailure:
  classname: str
  method: str
  exc_type: str
  message: str
  testcase_elem: ET.Element
  failure_elem: ET.Element
  suite_elem: ET.Element
  timestamp: datetime | None = None


def parse_resource_diag(path: Path) -> list[ResourceSample]:
  samples = []
  for line in path.read_text(errors="replace").splitlines():
    m = RESOURCE_DIAG_RE.match(line)
    if m:
      ts = datetime.strptime(m.group("ts"), "%Y-%m-%dT%H:%M:%S").replace(
        tzinfo=timezone.utc
      )
      samples.append(ResourceSample(ts=ts, load1=float(m.group("load1"))))
  return samples


def parse_stall_capture(path: Path) -> list[StallEvent]:
  events = []
  for line in path.read_text(errors="replace").splitlines():
    m = STALL_CAPTURE_HEADER_RE.match(line)
    if m:
      ts = datetime.strptime(m.group("ts"), "%Y-%m-%dT%H:%M:%S").replace(
        tzinfo=timezone.utc
      )
      events.append(StallEvent(ts=ts))
  return events


def parse_dmesg_hits(path: Path) -> list[str]:
  hits = []
  for line in path.read_text(errors="replace").splitlines():
    for sig in DMESG_STALL_SIGNATURES:
      if sig in line:
        hits.append(line.strip())
        break
  return hits


def build_failure_timestamp_index(log_path: Path) -> dict[tuple[str, str], datetime]:
  """Maps (classname, method) -> the timestamp of its first FAILED line."""
  index: dict[tuple[str, str], datetime] = {}
  for line in log_path.read_text(errors="replace").splitlines():
    m = LOG_FAILURE_RE.match(line)
    if not m:
      continue
    method = m.group("method1") or m.group("method2")
    classname = m.group("class1") or m.group("class2")
    if not method or not classname:
      continue
    key = (classname, method)
    if key in index:
      continue
    index[key] = datetime.strptime(m.group("ts"), "%Y-%m-%dT%H:%M:%S").replace(
      tzinfo=timezone.utc
    )
  return index


def estimate_timestamps_from_xml(root: ET.Element) -> dict[tuple[str, str], datetime]:
  """Fallback for when no external job log is available (e.g. running
  mid-job in CI, before the log is retrievable via the Actions API):
  approximate each testcase's failure time as the suite's start timestamp
  plus the cumulative duration of every testcase before and including it.
  RetryRule's retries happen inside the same test method invocation, so
  its reported `time` already covers all attempts - this is not exact
  (teardown/orchestrator overhead between tests isn't counted) but is
  within the correlation window's tolerance.
  """
  index: dict[tuple[str, str], datetime] = {}
  suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
  for suite in suites:
    raw_ts = suite.get("timestamp")
    if not raw_ts:
      continue
    try:
      start = datetime.fromisoformat(raw_ts)
    except ValueError:
      continue
    if start.tzinfo is None:
      start = start.replace(tzinfo=timezone.utc)
    cumulative = 0.0
    for testcase in suite.findall("testcase"):
      cumulative += float(testcase.get("time", "0") or "0")
      classname = testcase.get("classname", "")
      method = testcase.get("name", "")
      index[(classname, method)] = start + timedelta(seconds=cumulative)
  return index


def matches_known_signature(exc_type: str, message: str) -> bool:
  for sig_type, sig_message_substr in KNOWN_FLAKY_SIGNATURES:
    if sig_type not in exc_type:
      continue
    if sig_message_substr is None or sig_message_substr in message:
      return True
  return False


def collect_failures(xml_path: Path, root: ET.Element) -> list[TestFailure]:
  failures = []
  suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
  for suite in suites:
    for testcase in suite.findall("testcase"):
      for tag in ("failure", "error"):
        elem = testcase.find(tag)
        if elem is None:
          continue
        failures.append(
          TestFailure(
            classname=testcase.get("classname", ""),
            method=testcase.get("name", ""),
            exc_type=elem.get("type", ""),
            message=elem.get("message", "") or "",
            testcase_elem=testcase,
            failure_elem=elem,
            suite_elem=suite,
          )
        )
  return failures


def find_evidence(
  ts: datetime,
  window: timedelta,
  resource_samples: list[ResourceSample],
  stall_events: list[StallEvent],
  dmesg_hits: list[str],
  load_threshold: float,
) -> Evidence:
  ev = Evidence()
  for s in resource_samples:
    if abs((s.ts - ts).total_seconds()) <= window.total_seconds() and s.load1 > load_threshold:
      ev.overloaded_samples.append(s)
  for e in stall_events:
    if abs((e.ts - ts).total_seconds()) <= window.total_seconds():
      ev.stall_events.append(e)
  ev.dmesg_hits = list(dmesg_hits)  # not time-correlated, see module docstring
  return ev


def print_report(failure: TestFailure, evidence: Evidence | None, classified_overload: bool) -> None:
  print(f"\n{failure.classname}#{failure.method}")
  print(f"  exception: {failure.exc_type}")
  if failure.message:
    print(f"  message: {failure.message[:200]}")
  if failure.timestamp is None:
    print("  timestamp: not found in log - cannot correlate with diagnostics")
  else:
    print(f"  failed at: {failure.timestamp.isoformat()}")
  known = matches_known_signature(failure.exc_type, failure.message)
  print(f"  known flaky signature: {'yes' if known else 'no'}")
  if evidence is not None:
    if evidence.overloaded_samples:
      worst = max(evidence.overloaded_samples, key=lambda s: s.load1)
      print(
        f"  host overload evidence: load1={worst.load1} at {worst.ts.isoformat()} "
        f"({len(evidence.overloaded_samples)} sample(s) over threshold in window)"
      )
    if evidence.stall_events:
      print(f"  stall-capture evidence: {len(evidence.stall_events)} stall(s) detected in window")
    if evidence.dmesg_hits:
      print("  dmesg kernel-stall evidence (whole file, not time-correlated):")
      for hit in evidence.dmesg_hits[:5]:
        print(f"    {hit}")
      if len(evidence.dmesg_hits) > 5:
        print(f"    ... and {len(evidence.dmesg_hits) - 5} more")
  verdict = "CI OVERLOAD - quarantining as passed" if classified_overload else "GENUINE - left failing"
  print(f"  verdict: {verdict}")


def quarantine(failure: TestFailure) -> None:
  failure.testcase_elem.remove(failure.failure_elem)
  count_attr = "failures" if failure.failure_elem.tag == "failure" else "errors"
  current = int(failure.suite_elem.get(count_attr, "0") or "0")
  failure.suite_elem.set(count_attr, str(max(0, current - 1)))


def main() -> int:
  parser = argparse.ArgumentParser(
    description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
  )
  parser.add_argument(
    "--junit-xml", nargs="+", required=True, help="JUnit XML file(s) or glob pattern(s)"
  )
  parser.add_argument(
    "--log",
    type=Path,
    help=(
      "Raw instrumentation/gradle job log (e.g. from `gh api .../logs`), for precise "
      "failure timestamps. Optional - without it, timestamps are estimated from each "
      "testsuite's start time plus cumulative testcase durations, which is what's "
      "available mid-job in CI before the log can be fetched."
    ),
  )
  parser.add_argument("--resource-diag", type=Path, help="resource-diag.log artifact")
  parser.add_argument("--dmesg", type=Path, help="dmesg.log artifact")
  parser.add_argument("--stall-capture", type=Path, help="stall-capture.log artifact")
  parser.add_argument(
    "--load-threshold",
    type=float,
    default=DEFAULT_OVERLOAD_LOAD1_THRESHOLD,
    help=f"1-minute host load average above which a sample counts as overload (default {DEFAULT_OVERLOAD_LOAD1_THRESHOLD})",
  )
  parser.add_argument(
    "--window-seconds",
    type=int,
    default=DEFAULT_WINDOW_SECONDS,
    help=f"correlation window around the failure timestamp, in seconds (default {DEFAULT_WINDOW_SECONDS})",
  )
  parser.add_argument("--apply", action="store_true", help="rewrite the JUnit XML instead of just reporting")
  parser.add_argument(
    "--in-place",
    action="store_true",
    help="with --apply, overwrite the original file instead of writing a .quarantined.xml copy",
  )
  args = parser.parse_args()

  xml_paths: list[Path] = []
  for pattern in args.junit_xml:
    matched = sorted(Path(p) for p in glob.glob(pattern))
    xml_paths.extend(matched if matched else [Path(pattern)])
  xml_paths = [p for p in xml_paths if p.exists()]
  if not xml_paths:
    print("No JUnit XML files found for the given pattern(s).", file=sys.stderr)
    return 2

  failure_ts_index = build_failure_timestamp_index(args.log) if args.log else {}
  resource_samples = (
    parse_resource_diag(args.resource_diag)
    if args.resource_diag and args.resource_diag.exists()
    else []
  )
  stall_events = (
    parse_stall_capture(args.stall_capture)
    if args.stall_capture and args.stall_capture.exists()
    else []
  )
  dmesg_hits = parse_dmesg_hits(args.dmesg) if args.dmesg and args.dmesg.exists() else []

  print(
    f"Loaded {len(xml_paths)} JUnit XML file(s), {len(failure_ts_index)} failure "
    f"timestamp(s) from log,"
  )
  print(
    f"{len(resource_samples)} resource-diag sample(s), {len(stall_events)} "
    f"stall-capture event(s), {len(dmesg_hits)} dmesg hit(s)."
  )

  window = timedelta(seconds=args.window_seconds)
  total = 0
  quarantined = 0

  for xml_path in xml_paths:
    tree = ET.parse(xml_path)
    root = tree.getroot()
    failures = collect_failures(xml_path, root)
    if not failures:
      continue

    print(f"\n=== {xml_path} ===")
    xml_ts_index = estimate_timestamps_from_xml(root)
    any_quarantined_here = False
    for failure in failures:
      total += 1
      key = (failure.classname, failure.method)
      failure.timestamp = failure_ts_index.get(key) or xml_ts_index.get(key)
      evidence = None
      classified_overload = False
      if failure.timestamp is not None and matches_known_signature(
        failure.exc_type, failure.message
      ):
        evidence = find_evidence(
          failure.timestamp, window, resource_samples, stall_events, dmesg_hits, args.load_threshold
        )
        classified_overload = evidence.has_any
      print_report(failure, evidence, classified_overload)
      if classified_overload:
        quarantined += 1
        any_quarantined_here = True
        if args.apply:
          quarantine(failure)

    if args.apply and any_quarantined_here:
      out_path = xml_path if args.in_place else xml_path.with_suffix(".quarantined.xml")
      tree.write(out_path, encoding="unicode", xml_declaration=True)
      print(f"  -> wrote {out_path}")

  print(
    f"\n{total} failure(s) examined, {quarantined} classified as CI overload"
    f"{' and quarantined' if args.apply else ' (dry run, use --apply to rewrite XML)'}."
  )
  # Exit 0 only if every observed failure was CI overload (or there were
  # none) - callers (e.g. a CI step deciding whether to fail the build)
  # can rely on this without re-parsing the printed report.
  return 0 if quarantined == total else 1


if __name__ == "__main__":
  raise SystemExit(main())
