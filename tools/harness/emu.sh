#!/usr/bin/env bash
# Test harness for the PICO emulator, pinned to ONE device/AVD. Callable from Git Bash.
#   DEV (default emulator-5564), AVD (default PICO_6_1_Factory3), PKG (default io.embodit.furniturefit), TAG (default SpatialRuler)
#   (for the feasibility spike: PKG=io.embodit.rulerspike TAG=RulerSpike)
#
# Host input goes through emu.ps1 (PostMessage to the qemu PID of $AVD only; real cursor/focus untouched).
#
#   emu.sh info | wins                          window geometry / all windows of the emulator PID
#   emu.sh move X Y                             gaze point to framebuffer pixel X Y (console-screenshot pixels)
#   emu.sh click X Y [-HoldMs n] [-SettleMs n]  pinch-tap at X Y
#   emu.sh clicks X1 Y1 X2 Y2 ... [-GapMs n]    several pinch-taps in one process (double taps; repeat a point to tap it twice)
#       click / clicks / drag need 60 fps: when the emulator does not reach 50 fps they are NOT sent and the exit code is 3
#       (-NoFast sends a slow press instead: it clicks Compose buttons but is never a spatial tap)
#   emu.sh drag X1 Y1 X2 Y2 [-Steps n] [-StepMs n] [-HoldMs n] [-EndHoldMs n] [-Button left|right|middle] [-PauseAtStep n -PauseMs m] [-Return -ReturnMs m]
#   emu.sh key NAME [-HoldMs n] | keydown NAME | keyup NAME | wheel X Y NOTCHES | wiggle X Y -Ms n
#   emu.sh fast on|off                          60 fps on / back to the idle 2-4 fps (posted WM_SETFOCUS, no real focus change)
#   emu.sh shot NAME                            console screenshot (2160x2158)  -> $SHOTS/NAME.png
#   emu.sh winshot NAME [HWND]                  PrintWindow of the emulator window -> $SHOTS/NAME.png
#   emu.sh mark                                 remember "now" (device clock); click/drag/key/cmd do this automatically
#   emu.sh waitlog REGEX [TIMEOUT_S] [all]      poll logcat since the mark until REGEX matches (tag $TAG unless "all")
#   emu.sh log [REGEX] [all]                    logcat lines since the mark
#   emu.sh cmd NAME [FLOATS] [STRING]           debug broadcast to $PKG
#   emu.sh fps                                  last compositor FPS line (PxrMetric)
#   emu.sh pose                                 head pose (needs the app's `pose` debug command)
#   emu.sh calibrate [args]                     see calibrate.py
set -u
DEV="${DEV:-emulator-5564}"
AVD="${AVD:-PICO_6_1_Factory3}"
PKG="${PKG:-io.embodit.theremin}"
TAG="${TAG:-Theremin}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SHOTS="${SHOTS:-$ROOT/.scratch/shots/harness}"
STATE="$HERE/.state"
mkdir -p "$SHOTS" "$STATE"
PS1FILE="$(cygpath -w "$HERE/emu.ps1")"

check() {
  local avd
  avd="$(adb -s "$DEV" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
  if [ "$avd" != "$AVD" ]; then echo "WRONG AVD on $DEV: '$avd' (expected $AVD)" >&2; exit 2; fi
}
ps() { MSYS_NO_PATHCONV=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PS1FILE" "$@" -Avd "$AVD" | tr -d '\r'; }
mark() { adb -s "$DEV" shell "date '+%m-%d %H:%M:%S.%3N'" | tr -d '\r' > "$STATE/mark"; }
since() { [ -f "$STATE/mark" ] || mark; cat "$STATE/mark"; }
logsince() {
  # $1 = "all" for every tag
  if [ "${1:-}" = "all" ]; then adb -s "$DEV" logcat -d -v time -T "$(since)" 2>/dev/null
  else adb -s "$DEV" logcat -d -v time -T "$(since)" -s "$TAG:V" 2>/dev/null; fi | tr -d '\r'
}

# 60 fps handling. Observed emulator behaviour (6.1.0): it renders at 2-4 fps unless Qt gets a focus-in; a posted
# WM_KILLFOCUS + WM_SETFOCUS pair gives ~10 s of 60 fps, then it throttles again by itself (the real foreground window
# is never changed). A focus-in during a fast period does NOT extend it. At 2-4 fps a short press is not sampled and a
# long press is not a spatial tap, so every click/drag must sit inside a fresh fast period.
devnow() { adb -s "$DEV" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r'; }
lastfps() { adb -s "$DEV" logcat -d -v time -T "$1" 2>/dev/null | grep -E 'PxrMetric' | tail -1 | sed -E 's/.*FPS=([0-9]+).*/\1/'; }
wait_fast() {
  local budget="${1:-5}" since=0 t0 f i
  [ -f "$STATE/fast_since" ] && since="$(cat "$STATE/fast_since")"
  if [ $(( $(date +%s) - since )) -le "$budget" ]; then return 0; fi       # inside a fresh fast period
  t0="$(devnow)"                                                          # let an old fast period expire first
  for i in $(seq 1 60); do f="$(lastfps "$t0")"; if [ -n "$f" ] && [ "$f" -lt 10 ]; then break; fi; sleep 0.3; done
  t0="$(devnow)"; ps fast on >/dev/null
  for i in $(seq 1 40); do
    f="$(lastfps "$t0")"
    if [ -n "$f" ] && [ "$f" -ge "${MINFPS:-50}" ]; then date +%s > "$STATE/fast_since"; return 0; fi
    sleep 0.25
  done
  echo "WARN: emulator did not reach 50 fps after the posted focus-in" >&2
  return 1
}
has() { local x; for x in "${@:2}"; do [ "$x" = "$1" ] && return 0; done; return 1; }

act="${1:-info}"; shift || true
case "$act" in
  info|wins|capwin|rawmove|msg|fast) ps "$act" "$@" ;;
  move|down|up|wheel|wiggle|keydown|keyup|blip) ps "$act" "$@" ;;
  click|clicks|drag)
    check
    if has -NoFast "$@"; then mark; ps "$act" "$@"
    else
      if [ "$act" = click ]; then budget=5; else budget=1; fi
      if ! wait_fast "$budget"; then
        echo "ERROR: 50 fps gate failed, $act not sent (exit 3)" >&2
        exit 3
      fi
      mark; ps "$act" "$@" -NoRearm
    fi ;;
  key|rawclick) check; mark; ps "$act" "$@" ;;
  waitfast) check; wait_fast "${1:-5}" && echo "fast" ;;
  mark) check; mark; cat "$STATE/mark" ;;
  shot)
    check
    name="${1:?shot NAME}"; tmp="$SHOTS/.emu"; mkdir -p "$tmp"; rm -f "$tmp"/Screenshot_*.png
    adb -s "$DEV" emu screenrecord screenshot "$(cygpath -w "$tmp")" >/dev/null 2>&1
    for i in $(seq 1 40); do
      f="$(ls -1t "$tmp"/Screenshot_*.png 2>/dev/null | head -1)"
      if [ -n "$f" ]; then
        s1=$(stat -c %s "$f"); sleep 0.3; s2=$(stat -c %s "$f")
        if [ "$s1" = "$s2" ] && [ "$s1" -gt 1000 ]; then mv -f "$f" "$SHOTS/$name.png"; echo "OK $SHOTS/$name.png"; exit 0; fi
      fi
      sleep 0.25
    done
    echo "console screenshot failed"; exit 1 ;;
  winshot)
    name="${1:?winshot NAME [HWND]}"
    hwnd="${2:-}"
    if [ -z "$hwnd" ]; then hwnd="$(ps info | sed -n 's/^main hwnd=\(0x[0-9A-Fa-f]*\).*/\1/p')"; fi
    ps capwin "$hwnd" "$(cygpath -w "$SHOTS/$name.png")" ;;
  waitlog)
    check
    re="${1:?waitlog REGEX [TIMEOUT_S] [all]}"; to="${2:-10}"; scope="${3:-}"
    end=$(( $(date +%s) + to ))
    while :; do
      hit="$(logsince "$scope" | grep -E -- "$re" | tail -n 5)"
      if [ -n "$hit" ]; then echo "$hit" | cut -c1-1200; exit 0; fi
      [ "$(date +%s)" -ge "$end" ] && { echo "TIMEOUT after ${to}s waiting for /$re/" >&2; exit 1; }
      sleep 0.3
    done ;;
  log)
    check; logsince "${2:-}" | grep -E -- "${1:-.}" | cut -c1-1200 ;;
  cmd)
    check; mark
    extra=""
    [ -n "${2:-}" ] && extra="$extra --es a '$2'"
    [ -n "${3:-}" ] && extra="$extra --es s '$3'"
    adb -s "$DEV" shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd ${1:?cmd NAME} $extra" | tail -1 | tr -d '\r' ;;
  fps)
    check; adb -s "$DEV" logcat -d -v time -t 400 2>/dev/null | grep -E 'PxrMetric' | tail -n "${1:-1}" | sed -E 's/,MTP.*//' | tr -d '\r' ;;
  pose)
    check; mark
    adb -s "$DEV" shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd pose" >/dev/null
    "$0" waitlog 'POSE hmd' "${1:-10}" | tail -1 | sed -E 's/^.*POSE /POSE /' ;;
  calibrate) check; python "$HERE/calibrate.py" "$@" ;;
  *) echo "usage: emu.sh info|wins|move|click|clicks|drag|key|keydown|keyup|wheel|wiggle|shot|winshot|mark|waitlog|log|cmd|fps|pose|calibrate|capwin|rawclick|rawmove|msg" >&2; exit 64 ;;
esac
