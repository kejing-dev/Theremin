#!/usr/bin/env bash
# All design states on the emulator (AGENTS.md screenshot list). tools/shoot_all.sh [placed|fresh]
#   placed: the shots after the instrument stands (app already running, fresh start ~50 s before)
#   fresh:  S01 / S03 / S06 / S20 (relaunches the app three times)
set -u
cd "$(dirname "$0")/.."
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
DEV=${DEV:-emulator-5564}; PKG=io.embodit.theremin
S() { bash tools/qa_shot.sh "$@"; }
launch() { adb -s $DEV shell am force-stop $PKG; sleep 2; MSYS_NO_PATHCONV=1 adb -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$*'" >/dev/null 2>&1; }
if [ "${1:-placed}" = fresh ]; then
  launch; sleep 4; PRE=0.2 SETTLE=1 S S01 'lang=zh'
  sleep 25; cmd 'desk=none'; PRE=0.3 SETTLE=1 S S03 'lang=zh'
  sleep 6; S S06 'lang=zh'
  launch; sleep 30; S S20 'lang=zh plane=denied'
  exit 0
fi
S S04 'lang=zh desk=seed place=ghost'
S S05 'place=snap'
S S07_1 'place=down'
S S07_2 'coach=2'
S S07_3 'freeze=1 coach=3 hand=0.398,0.15'
S S08 'freeze=0 page=home' 'hand=0.25,off'
S S09_low 'scale=standard octave=mid hand=0.319,0.12'
S S09_center 'hand=0.310,0.12'
S S08_S10 'hand=off'
S S09_rightlost 'hand=0.31,0.12' 'hand=off,0.12'
S S09_S11 'source=controllers hand=0.31,0.12'
S S12 'source=hands hand=0.25,off seed=lessons page=lessons'
S S12_songs 'library=songs'
S S13_half 'lesson=L1 hand=0.398,0.12' 'hand=0.398,0.12' 'freeze=1'
S S14_on 'freeze=1 lesson=L4 step=4 hand=0.244,0.12'
S S14_high 'hand=0.234,0.12'
S S15 'freeze=0 hand=0.25,off lesson=L4 done=1'
S S15_L6 'lesson=L6 done=1'
S S16 'page=home settings'
S S16_wide 'scale=wide'
S S16_mirror 'scale=standard mirror=1'
S S16_strong 'mirror=0 quantize=strong'
S S17 'quantize=off page=home menu'
S S18_S09 'lang=en page=home hand=0.31,0.12'
S S18_S12 'page=lessons'
S S18_S16 'settings'
S S21_G1 'lang=zh song=G1 beat=6 handpath=song'
S S21_G3 'song=G3 beat=14'
S S22 'handpath=off hand=0.25,off song=G1 score=82'
S mute 'page=home mute=1'
S labeltest 'mute=0 labeltest=1 hand=0.31,0.12'
PRE=4 S S19 'labeltest=0 source=none'
cmd 'source=hands'
