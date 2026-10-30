#!/usr/bin/env bash
# Cold start + ANR watch: tools/launch_check.sh [SECONDS]  (default 60). Force-stops, clears logcat, launches,
# then watches the window list and logcat for "Application Not Responding" / am_anr and prints the app's STATE lines.
set -u
DEV=${DEV:-emulator-5564}; PKG=io.embodit.theremin; TAG=Theremin; SECS=${1:-60}
ADB="${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s $DEV shell am force-stop $PKG; sleep 2
"$ADB" -s $DEV logcat -c
t0=$(date +%s)
"$ADB" -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
anr=0
while [ $(( $(date +%s) - t0 )) -lt "$SECS" ]; do
  sleep 5
  w=$("$ADB" -s $DEV shell dumpsys window windows 2>/dev/null | grep -c "Application Not Responding")
  l=$("$ADB" -s $DEV logcat -d 2>/dev/null | grep -c -E "ANR in $PKG|am_anr.*$PKG|failed to complete startup")
  st=$("$ADB" -s $DEV logcat -d -s $TAG:V 2>/dev/null | grep -E "STATE |SCENE attached|PLANES n=" | tail -1 | cut -c1-120 | tr -d '\r')
  echo "t+$(( $(date +%s) - t0 ))s anrWin=$w anrLog=$l | $st"
  [ "$w" != "0" -o "$l" != "0" ] && anr=1
done
echo "RESULT anr=$anr"
"$ADB" -s $DEV logcat -d -s $TAG:V 2>/dev/null | grep -E "STATE |SCENE|PLANES|MESH|TRACK|FATAL|PANEL bound" | head -40 | cut -c1-200
"$ADB" -s $DEV logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|AndroidRuntime.*$PKG" | head -5
