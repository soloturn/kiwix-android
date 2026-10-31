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

logcat_pid=
progress_pid=
diagnostics_pid=
# Appended across attempts (the action reruns this script on a fresh emulator
# on the same runner), so a rerun can skip tests that already finished.
progress_log=/tmp/instrumentation_progress.log
stop_diagnostics() {
  kill "$diagnostics_pid" 2>/dev/null || true
  wait "$diagnostics_pid" 2>/dev/null || true
}
start_logcat() {
  # Keep the adb client PID so it cannot outlive this script and hold the
  # emulator action's output pipe open during teardown.
  # shellcheck disable=SC2035
  adb logcat TestRunner:I System.err:W AndroidRuntime:E ActivityManager:W *:E -v color &
  logcat_pid=$!
  adb logcat TestRunner:I '*:S' -v brief >>"$progress_log" &
  progress_pid=$!
}
stop_logcat() {
  if [ -n "$logcat_pid" ]; then
    kill "$logcat_pid" "$progress_pid" 2>/dev/null || true
    wait "$logcat_pid" "$progress_pid" 2>/dev/null || true
    logcat_pid=
    progress_pid=
  fi
}
# Keep runner evidence alongside the JUnit failures for the classifier below.
bash contrib/ci_emulator_diagnostics.sh &
diagnostics_pid=$!
cleanup() {
  stop_logcat
  stop_diagnostics
}
trap cleanup EXIT
# Marks that this script actually started running, i.e. the emulator finished
# booting and reactivecircus/android-emulator-runner handed control to us.
# .github/actions/android-emulator-runner checks for this file to tell an
# emulator boot-time crash (e.g. kiwix/kiwix-android#5047) apart from a
# genuine test failure, and only retries the whole step for the former.
touch /tmp/emulator_script_started

# Enable Wi-Fi on the emulator
adb shell svc wifi enable
adb logcat -c
# Check if the stylus_handwriting_enabled setting exists before disabling
if adb shell settings list secure | grep -q "stylus_handwriting_enabled"; then
  adb shell settings put secure stylus_handwriting_enabled 0
fi
start_logcat

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

# Tests that finished without failing in an earlier attempt, as Class#method
# for AndroidJUnitRunner's notClass filter. Their results died with that emulator.
finished_tests() {
  python3 - "$progress_log" <<'PY'
import re
import sys

passed, failed = set(), set()
try:
    lines = open(sys.argv[1], errors="ignore").read().splitlines()
except OSError:
    lines = []
for line in lines:
    m = re.search(r"TestRunner.*?(finished|failed): (.+)\(([\w.$]+)\)\s*$", line)
    if m:
        (passed if m.group(1) == "finished" else failed).add(f"{m.group(3)}#{m.group(2)}")
print(",".join(sorted(passed - failed)))
PY
}

retry=0
while [ $retry -le 4 ]; do
  skip_args=()
  skip_tests="$(finished_tests)"
  if [ -n "$skip_tests" ]; then
    echo "Skipping $(tr ',' '\n' <<<"$skip_tests" | wc -l) tests that already finished" >&2
    skip_args=("-Pandroid.testInstrumentationRunnerArguments.notClass=${skip_tests}")
  fi
  # Bound each test invocation so a stalled instrumentation run fails in finite time.
  # A retry must classify only this attempt's results, never stale XML from a
  # previous infrastructure failure.
  find "$test_results_dir" -name 'TEST-*.xml' -type f -delete 2>/dev/null || true

  # Slower tests can override this default with @Test(timeout).
  if ./gradlew "$task" "${shard_args[@]}" "${skip_args[@]}" \
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
    stop_logcat
    # A dead emulator would hang every adb call below; fail so the action retries on a fresh one.
    if ! timeout 30 adb shell true; then
      echo "Emulator is offline; failing fast so the action retries on a fresh emulator" >&2
      exit 75
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
    start_logcat

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
