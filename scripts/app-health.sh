#!/usr/bin/env bash
# app-health.sh — snapshot the running NmeaBridge app's memory, CPU, GC, and
# recent error state over adb. Produces a single stdout report.
#
# Usage:
#   scripts/app-health.sh                   # auto-pick adb device, default pkg
#   ADB_SERIAL=192.168.1.115:44807 scripts/app-health.sh
#   PKG=uk.co.tfd.nmeabridge scripts/app-health.sh
#
# Assumes `adb` is either on PATH or at the standard macOS SDK location.

set -u

PKG="${PKG:-uk.co.tfd.nmeabridge}"

if ! command -v adb >/dev/null 2>&1; then
  export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
fi
if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found on PATH or at standard SDK location" >&2
  exit 1
fi

ADB_ARGS=()
if [ -n "${ADB_SERIAL:-}" ]; then
  ADB_ARGS=(-s "$ADB_SERIAL")
else
  # If multiple devices, pick the first — caller can override via ADB_SERIAL.
  count=$(adb devices | awk 'NR>1 && $2=="device"{n++} END{print n+0}')
  if [ "$count" -gt 1 ]; then
    pick=$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')
    echo "# Multiple devices attached — using $pick (override with ADB_SERIAL)"
    ADB_ARGS=(-s "$pick")
  fi
fi

sh() { adb "${ADB_ARGS[@]}" shell "$@"; }
lc() { adb "${ADB_ARGS[@]}" logcat -d 2>/dev/null; }

PID=$(sh pidof "$PKG" | tr -d '\r')
if [ -z "$PID" ]; then
  echo "Package $PKG is not running on the target device."
  exit 1
fi

section() { printf '\n=== %s ===\n' "$1"; }

echo "NmeaBridge health report"
echo "Package: $PKG"
echo "Pid:     $PID"
echo "Host:    $(date '+%Y-%m-%d %H:%M:%S %Z')"

section "process uptime"
# starttime (field 22) is in clock ticks since boot; /proc/uptime gives us
# seconds since boot. Convert to process age in seconds.
sh "awk -v pid=$PID '
  FNR==1 && FILENAME==\"/proc/uptime\" { up=\$1 }
  FNR==1 && FILENAME==\"/proc/\"pid\"/stat\" {
    n=split(\$0, a, \" \"); ticks=a[22];
    hz=100;                          # Linux default CLK_TCK on Android
    age=up - ticks/hz;
    printf \"uptime_host=%.0fs  process_age=%.0fs (%.1f h)\\n\", up, age, age/3600
  }
' /proc/uptime /proc/$PID/stat"

section "memory — /proc/$PID/status"
# VmPeak may show a very large number on 64-bit Android due to ASLR reservations
# — focus on VmHWM (peak RSS) and VmRSS (current RSS) for real memory use.
sh "grep -E 'VmPeak|VmSize|VmHWM|VmRSS|VmData|Threads|voluntary' /proc/$PID/status"

section "memory — dumpsys meminfo"
sh "dumpsys meminfo $PID" 2>/dev/null | head -45

section "cpu — main thread"
# utime/stime in clock ticks (hz=100), so /100 gives seconds of CPU consumed.
sh "cat /proc/$PID/task/$PID/stat | awk '{
  printf \"main_utime=%.1fs  main_stime=%.1fs\\n\", \$14/100, \$15/100
}'"

section "cpu — system view (last 5 min window)"
sh "dumpsys cpuinfo" 2>/dev/null | awk -v p="$PID" '
  NR<=2 || index($0, p) { print }
  /^[0-9]+% TOTAL/ { print; exit }
'

section "thread cpu table (top 10 by utime)"
sh "for t in /proc/$PID/task/*; do
  tid=\${t##*/}
  awk -v tid=\"\$tid\" '{
    # field 2 is (comm) including parens; strip them
    comm=\$2; gsub(/[()]/,\"\",comm);
    printf \"%s\\t%s\\t%s\\t%s\\n\", \$14, \$15, tid, comm
  }' \$t/stat 2>/dev/null
done | sort -rn | head -10 | awk 'BEGIN{print \"utime\\tstime\\ttid\\tcomm\"} {print}'"

section "gc activity"
gc_count=$(lc | grep " $PID " | grep -c "concurrent copying GC freed")
echo "gc_events_in_logcat_buffer=$gc_count"
echo "--- first gc in buffer:"
lc | grep " $PID " | grep "concurrent copying GC freed" | head -1
echo "--- last 3 gc entries:"
lc | grep " $PID " | grep "concurrent copying GC freed" | tail -3

section "errors / ANR / jank"
# Frame skips, ANRs, crashes, BLE anomalies.
hits=$(lc | grep " $PID " | grep -E "FATAL|ANR |Skipped [0-9]+ frames|OutOfMemory|DeadObject|Throwable|Exception" | wc -l | tr -d ' ')
echo "error_like_lines=$hits"
lc | grep " $PID " | grep -E "FATAL|ANR |Skipped [0-9]+ frames|OutOfMemory|DeadObject" | tail -20

section "ble reconnect events"
# Each GATT registerApp is a new BLE client session. Useful for spotting
# reconnect churn overnight.
reconnects=$(lc | grep " $PID " | grep -c "BluetoothGatt: registerApp()")
echo "gatt_registerApp_count=$reconnects"
lc | grep " $PID " | grep "BluetoothGatt: registerApp()" | tail -5

section "tcp clients on 10110"
# ss on Android runs unprivileged but can't read everything — combine with
# /proc for the authoritative per-pid picture. Port 10110 = 0x277E in hex.
sh "ss -tn 2>/dev/null | awk 'NR==1 || /:10110/'"
echo "---"
sh "awk '\$2 ~ /277E/ || \$3 ~ /277E/ {print \$2, \$3, \"state=\"\$4}' /proc/$PID/net/tcp"

echo
echo "# done"
