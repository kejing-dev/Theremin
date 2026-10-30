#!/usr/bin/env bash
# One QA screenshot: tools/qa_shot.sh NAME ['key=value ...'] ['key=value ...']   (Chime Bells)
#   tools/qa_shot.sh S8 'place=auto' 'mode=follow,song=molihua,step=5 freeze=1'
# Each argument after NAME is one debug broadcast (--es cmd '...'), 0.6 s apart. Then the emulator is
# un-throttled to 60 fps (harness `fast on`) so the panels render, and a full console frame is captured to
# $SH (default ../spatial-app-factory/apps/theremin/build/shots). Prints the app's last STATE line.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5564}
PKG=io.embodit.theremin
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
H="bash $ROOT/tools/harness/emu.sh"
SH="${SH:-$ROOT/../spatial-app-factory/apps/theremin/build/shots}"; mkdir -p "$SH/emu"
name="$1"; shift
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$*'" >/dev/null 2>&1; }
cap() { before=$(ls -1 "$SH/emu" 2>/dev/null | wc -l); adb -s $DEV emu screenrecord screenshot "$(cygpath -w "$SH/emu")" >/dev/null 2>&1
  for i in $(seq 1 12); do sleep 0.5; after=$(ls -1 "$SH/emu" 2>/dev/null | wc -l); [ "$after" -gt "$before" ] && break; done; sleep 0.5
  newest=$(ls -1t "$SH/emu"/*.png 2>/dev/null | head -1); [ -n "$newest" ] && mv -f "$newest" "$SH/$1.png" && echo "OK $SH/$1.png" || echo "capture FAILED"; }
adb -s $DEV logcat -c
for c in "$@"; do cmd "$c"; sleep 0.6; done
sleep "${PRE:-1.5}"
$H fast on >/dev/null 2>&1
sleep "${SETTLE:-6}"
cap "$name"
$H fast off >/dev/null 2>&1
adb -s $DEV logcat -d -s Theremin:V 2>/dev/null | grep -E "STATE S|FATAL|PITCH " | tail -2 | cut -c1-200
