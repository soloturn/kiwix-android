#!/usr/bin/env bash

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
#

# Keep runner evidence alongside the JUnit failures for the classifier below.
bash contrib/ci_emulator_diagnostics.sh &
diagnostics_pid=$!
stop_diagnostics() {
  kill "$diagnostics_pid" 2>/dev/null || true
  wait "$diagnostics_pid" 2>/dev/null || true
}
trap stop_diagnostics EXIT

# Marks that this script actually started running, i.e. the emulator finished
# booting and reactivecircus/android-emulator-runner handed control to us.
# .github/actions/android-emulator-runner checks for this file to tell an
# emulator boot-time crash (e.g. kiwix/kiwix-android#5047) apart from a
# genuine test failure, and only retries the whole step for the former.
touch /tmp/emulator_script_started

# The emulator's crashpad_handler subprocess can survive `adb emu kill` and
# hang the android-emulator-runner action's teardown
# (https://github.com/ReactiveCircus/android-emulator-runner/issues/385).
# Kill it once this script exits, regardless of the test outcome. Extended
# below (once logcat_loop_pid exists) rather than appended, since a second
# `trap ... EXIT` would replace this one instead of adding to it.
trap 'killall -INT crashpad_handler 2>/dev/null || true' EXIT

# Enable Wi-Fi on the emulator
adb shell svc wifi enable
adb logcat -c
# Check if the stylus_handwriting_enabled setting exists before disabling
if adb shell settings list secure | grep -q "stylus_handwriting_enabled"; then
  adb shell settings put secure stylus_handwriting_enabled 0
fi
# adb logcat is known to silently stop producing output for a while and
# then resume (documented upstream, e.g. https://issuetracker.google.com/issues/150558653) -
# left running once for a 40-50min job with no supervision, that shows up
# as gaps in what we can see. Restart it whenever the client exits instead
# of a single fire-and-forget background process.
#
# RetryRule's own diagnostics (System.err.println on each retry attempt)
# land in logcat under tag System.err at priority W, not E - a bare "*:E"
# filter silently drops them, so a flaky test's retries are invisible in
# every capture we have. Add System.err:W so a run like 35284788813 (two
# ComposeTimeoutException failures with no way to tell whether RetryRule
# actually retried 3x or gave up early) can be diagnosed from its own log.
#
# lmkd logs kills under tag lowmemorykiller/lmkd at Info priority, below
# the *:E floor - same blind spot System.err:W above was added to close.
# ActivityManager also logs its own process kills at Info. Confirmed via
# run 35470368074: every aw_browser_terminator "crash detected (code -1)"
# line this project chased across several CI investigations is
# immediately followed by "ActivityManager: Killing
# <pid>:...:sandboxed_process0:...(adj 0): isolated not needed" - ordinary
# per-test process teardown (ANDROIDX_TEST_ORCHESTRATOR recycling a
# process per test), not a crash, not lmkd, not OOM. Settled; no further
# investigation needed if this line reappears.
(
  while true; do
    # shellcheck disable=SC2035
    adb logcat *:E System.err:W lowmemorykiller:V lmkd:V ActivityManager:I -v color | tee -a /tmp/logcat-capture.log
    sleep 1
  done
) &
logcat_loop_pid=$!
# This loop is never awaited, so a normal script exit leaves it as an
# orphan that still holds this step's stdout pipe open - GitHub Actions
# won't see EOF (and the step won't complete) until it exits too. `adb
# logcat` has no per-call timeout, so if it's mid-blocking-read exactly
# when the emulator teardown below severs the connection, it can hang
# indefinitely instead of erroring out - observed in run 35470368074's
# job (30,0): the actual test task succeeded in 5 minutes, but the step
# then ran for 3 hours until the hard EMULATOR_STEP_TIMEOUT_MINUTES kill.
# pkill by pattern rather than just the loop's own PID, since that PID is
# the `while` subshell, not the adb logcat process actually stuck inside it.
trap '
  kill "$logcat_loop_pid" 2>/dev/null
  pkill -f "adb logcat" 2>/dev/null
  killall -INT crashpad_handler 2>/dev/null
  true
' EXIT

PACKAGE_NAME="org.kiwix.kiwixmobile"
TEST_PACKAGE_NAME="${PACKAGE_NAME}.test"
TEST_SERVICES_PACKAGE="androidx.test.services"
TEST_ORCHESTRATOR_PACKAGE="androidx.test.orchestrator"
# Function to check if the application is installed
is_app_installed() {
  adb shell pm list packages | grep -q "$1"
}

if is_app_installed "$PACKAGE_NAME"; then
  # Delete the application to properly run the test cases.
  adb uninstall "${PACKAGE_NAME}"
fi

if is_app_installed "$TEST_PACKAGE_NAME"; then
  # Delete the test application to properly run the test cases.
  adb uninstall "${TEST_PACKAGE_NAME}"
fi

if is_app_installed "$TEST_SERVICES_PACKAGE"; then
  adb uninstall "${TEST_SERVICES_PACKAGE}"
fi

if is_app_installed "$TEST_ORCHESTRATOR_PACKAGE"; then
  adb uninstall "${TEST_ORCHESTRATOR_PACKAGE}"
fi
# NUM_SHARDS/SHARD_INDEX shard the suite via AndroidJUnitRunner's built-in sharding.
shard_args=()
if [ "${NUM_SHARDS:-1}" -gt 1 ]; then
  shard_args=(
    "-Pandroid.testInstrumentationRunnerArguments.numShards=${NUM_SHARDS}"
    "-Pandroid.testInstrumentationRunnerArguments.shardIndex=${SHARD_INDEX:-0}"
  )
fi
# Scoped to :app - the bare name also matches core, defaultmigration
# and objectboxmigration, none of which have any tests.
task="${INSTRUMENTATION_GRADLE_TASK:-:app:connectedDebugAndroidTest}"

test_results_dir="app/build/outputs/androidTest-results/connected"
has_test_failures() {
  local junit_xmls=()
  mapfile -t junit_xmls < <(find "$test_results_dir" -name 'TEST-*.xml' -type f 2>/dev/null)
  [ "${#junit_xmls[@]}" -gt 0 ] || return 1
  python3 - "${junit_xmls[@]}" <<'PY'
import sys
import xml.etree.ElementTree as ET

for path in sys.argv[1:]:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        continue
    if any(case.find("failure") is not None or case.find("error") is not None
           for case in root.iter("testcase")):
        raise SystemExit(0)
raise SystemExit(1)
PY
}

retry=0
while [ $retry -le 4 ]; do
  # Bound each test invocation so a stalled instrumentation run fails in finite time.
  # A retry must classify only this attempt's results, never stale XML from a
  # previous infrastructure failure.
  find "$test_results_dir" -name 'TEST-*.xml' -type f -delete 2>/dev/null || true
  # Slower tests can override this default with @Test(timeout).
  if ./gradlew "$task" "${shard_args[@]}" \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000; then
    echo "$task succeeded" >&2
    break
  else
    if has_test_failures; then
      echo "$task reported failed tests; classifying now instead of rerunning the whole suite" >&2
      mapfile -t junit_xmls < <(find "$test_results_dir" -name 'TEST-*.xml' -type f 2>/dev/null)
      if python3 contrib/classify_flaky_failures.py \
        --junit-xml "${junit_xmls[@]}" \
        --resource-diag /tmp/resource-diag.log \
        --dmesg /tmp/dmesg.log \
        --stall-capture /tmp/stall-capture.log \
        --apply --in-place; then
        echo "All failures were CI-runner overload with supporting evidence - not failing the build" >&2
        break
      fi
      echo "$task has genuine test failures; not retrying the full suite" >&2
      exit 1
    fi

    adb kill-server
    adb start-server
    # Enable Wi-Fi on the emulator
    adb shell svc wifi enable
    adb logcat -c
    # Check if the stylus_handwriting_enabled setting exists before disabling
    if adb shell settings list secure | grep -q "stylus_handwriting_enabled"; then
      adb shell settings put secure stylus_handwriting_enabled 0
    fi
    # shellcheck disable=SC2035
    adb logcat TestRunner:I System.err:W AndroidRuntime:E ActivityManager:W *:E -v color &

    if is_app_installed "$PACKAGE_NAME"; then
      # Delete the application to properly run the test cases.
      adb uninstall "${PACKAGE_NAME}"
    fi
    if is_app_installed "$TEST_PACKAGE_NAME"; then
      # Delete the test application to properly run the test cases.
      adb uninstall "${TEST_PACKAGE_NAME}"
    fi
    if is_app_installed "$TEST_SERVICES_PACKAGE"; then
      adb uninstall "${TEST_SERVICES_PACKAGE}"
    fi
    if is_app_installed "$TEST_ORCHESTRATOR_PACKAGE"; then
      adb uninstall "${TEST_ORCHESTRATOR_PACKAGE}"
    fi
    ./gradlew --stop
    retry=$(( retry + 1 ))
    if [ $retry -eq 4 ]; then
      adb exec-out screencap -p >screencap.png
      echo "$task failed after $retry attempts - checking whether every failure is CI-runner overload" >&2
      mapfile -t junit_xmls < <(find "$test_results_dir" -name 'TEST-*.xml' -type f 2>/dev/null)
      if [ "${#junit_xmls[@]}" -eq 0 ] || ! python3 contrib/classify_flaky_failures.py \
        --junit-xml "${junit_xmls[@]}" \
        --log /tmp/logcat-capture.log \
        --resource-diag /tmp/resource-diag.log \
        --dmesg /tmp/dmesg.log \
        --stall-capture /tmp/stall-capture.log \
        --apply --in-place; then
        exit 1
      fi
      echo "All failures were CI-runner overload with supporting evidence - not failing the build" >&2
      break
    fi
    # Give a transient outage (e.g. a Maven Central 403) time to clear: 5s, 20s, 80s.
    sleep $(( 5 * 4 ** (retry - 1) ))
  fi
done
