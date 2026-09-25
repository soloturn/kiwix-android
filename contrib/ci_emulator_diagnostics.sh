#!/usr/bin/env bash

# Capture lightweight runner and emulator health data while instrumentation runs.
set -u

serial="${ANDROID_SERIAL:-emulator-5554}"
cores="${CI_EMULATOR_CORES:-4}"
: > /tmp/resource-diag.log
: > /tmp/dmesg.log
: > /tmp/stall-capture.log

sudo -n dmesg -w >> /tmp/dmesg.log 2>&1 &
dmesg_pid=$!
stop_dmesg() {
  kill "$dmesg_pid" 2>/dev/null || true
  wait "$dmesg_pid" 2>/dev/null || true
}
trap stop_dmesg EXIT TERM INT

while true; do
  timestamp="$(date -u +%FT%TZ)"
  load="$(cut -d' ' -f1 /proc/loadavg)"
  memory="$(free -m | awk 'NR==2 {printf "mem_used=%sMiB mem_avail=%sMiB", $3, $7}')"
  emulator_pid="$(ps -eo pid=,comm= | awk '$2 ~ /emulator|qemu-system/ {print $1; exit}')"
  rss=""
  if [ -n "$emulator_pid" ]; then
    rss="$(ps -o rss= -p "$emulator_pid" 2>/dev/null | tr -d ' ')"
  fi
  guest_load="$(timeout 5 adb -s "$serial" shell cat /proc/loadavg 2>/dev/null | tr -d '\r')"
  echo "$timestamp load=$load $memory emulator_rss=${rss:-unknown}KiB guest_load=${guest_load:-unknown}" >> /tmp/resource-diag.log

  start_ns="$(date +%s%N)"
  timeout 8 adb -s "$serial" shell echo ping >/dev/null 2>&1 || true
  end_ns="$(date +%s%N)"
  elapsed_ms=$(( (end_ns - start_ns) / 1000000 ))
  guest_load1="${guest_load%% *}"
  guest_overloaded=false
  if [[ "$guest_load1" =~ ^[0-9]+([.][0-9]+)?$ ]] && awk -v l="$guest_load1" -v c="$cores" 'BEGIN { exit !(l > c) }'; then
    guest_overloaded=true
  fi
  if [ "$elapsed_ms" -gt 5000 ] || [ "$guest_overloaded" = true ]; then
    {
      echo "=== stall detected at $timestamp, adb echo took ${elapsed_ms}ms, guest loadavg=[$guest_load] ==="
      if [ -n "$emulator_pid" ]; then
        echo "--- emulator threads ---"
        ps -L -o tid,stat,wchan:32,comm -p "$emulator_pid" 2>/dev/null || true
        for tid_dir in /proc/"$emulator_pid"/task/*; do
          [ -d "$tid_dir" ] || continue
          echo "tid $(basename "$tid_dir"):"
          sudo -n cat "$tid_dir/stack" 2>/dev/null || true
        done
      fi
      echo "--- recent dmesg ---"
      sudo -n dmesg -T 2>/dev/null | tail -80 || true
      echo "=== end stall capture ==="
    } >> /tmp/stall-capture.log
  fi
  sleep 3
done
