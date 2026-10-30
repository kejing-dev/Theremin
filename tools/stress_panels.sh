#!/usr/bin/env bash
# Panel in / out stress test: tools/stress_panels.sh [ROUNDS]   (default 30)
# Each round: Follow panel in (mode=pick), More menu open and closed, back to free play (short panel),
# then a console frame; the control panel area is checked for black glass (share of pixels below 24/255).
set -u
cd "$(dirname "$0")/.."
DEV=${DEV:-emulator-5564}; PKG=io.embodit.theremin
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
H="bash tools/harness/emu.sh"
OUT="${OUT:-$(pwd)/.scratch/stress}"; mkdir -p "$OUT/emu"
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$*'" >/dev/null 2>&1; }
shot() { before=$(ls -1 "$OUT/emu" | wc -l); adb -s $DEV emu screenrecord screenshot "$(cygpath -w "$OUT/emu")" >/dev/null 2>&1
  for i in $(seq 1 12); do sleep 0.5; [ "$(ls -1 "$OUT/emu" | wc -l)" -gt "$before" ] && break; done; sleep 0.3
  f=$(ls -1t "$OUT/emu"/*.png | head -1); mv -f "$f" "$OUT/$1.png"; }
black() { python -W ignore -c "
from PIL import Image
im=Image.open(r'$(cygpath -w "$OUT/$1.png")').convert('L').crop((300,1560,1060,1960)); px=list(im.getdata())
print('%.3f' % (sum(1 for p in px if p<24)/len(px)))"; }
N=${1:-30}; bad=0
for r in $(seq 1 $N); do
  cmd 'mode=pick'; sleep 1.5; $H fast on >/dev/null 2>&1; sleep 4
  shot "r${r}-follow"; bf=$(black "r${r}-follow")
  cmd 'menu=1'; sleep 1; cmd 'menu=0'; sleep 0.5; cmd 'mode=free'; sleep 1.5; $H fast on >/dev/null 2>&1; sleep 4
  shot "r${r}-free"; bs=$(black "r${r}-free")
  alive=$(adb -s $DEV shell pidof $PKG | tr -d '\r')
  flag=ok; python -W ignore -c "import sys; sys.exit(0 if max($bf,$bs) < 0.05 else 1)" || { flag=BLACK; bad=$((bad+1)); }
  echo "round $r follow_black=$bf free_black=$bs pid=$alive $flag"
done
$H fast off >/dev/null 2>&1
echo "RESULT rounds=$N black=$bad crash=$(adb -s $DEV logcat -d | grep -c 'FATAL EXCEPTION')"
