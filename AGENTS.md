# Air Theremin (空中琴)

A theremin standing on your real desk: the pitch hand's distance to the brass antenna sets the note (log
mapping, one semitone every 2.2 cm, A4 = 440 Hz), the volume hand's height over the loop sets the loudness.
Glowing pitch ripples show where every semitone is; lessons and six follow-along songs score how in tune you
are. PICO OS 6, Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space, passthrough), package
`io.embodit.theremin`, v0.1.0.

Design package (binding): `../spatial-app-factory/apps/theremin/design-package.json` (archetype D,
STAGE_MIXED, single_panel_with_popup, 22 states S01 - S22, S02 unused). Mockup: `../spatial-app-factory/apps/theremin/sample.html`.
Skeleton forked from `../bianzhong` (Chime Bells: panel pool, lazy follow, plane tracking, theme, debug bus).

Built by the factory's build station on 2026-10-27; v0.1.1 passed QA #2 on emulator-5564 (2026-10-28).

## v0.1.2 — content never placed from the head (taste D1 / D20)

Headset feedback on another factory app ("你怎么又用 headset 射线来定物体位置…点 bring it to 标记处"; "点了 next，他就自动出现在我眼前，应该 anchor 在固定位置"), applied to every app:
- No content position comes from the head pose any more (removed: `DeskRules.front`, `placeInFront`, the system drag that mapped
  pixels along the head's axes, `HeadFrame`). Desk-edge choice and facing use a user reference point: the controller ray origin,
  else the panel being read carried 0.6 m towards the user.
- Marker: controller ray + trigger, or gaze + pinch, on the floor or a desk sets an amber ring; "移到标记处" (place panel button,
  hint-strip button, More page row) moves the instrument there (floor marker = on the stand, top 0.75 m). While placing on a desk
  the ghost is the marker and a tap / trigger puts it down directly.
- No desk after 5 s, or "移到标记处" with no marker: the instrument waits 0.7 m beyond and 0.55 m to the right of the place
  panel (`DeskRules.besidePanel`), never between user and panel; a sticky hint asks for a marker.
- The anchor is fixed for the session; only a new marker (+ move) or a controller drag changes it.
- The instrument fades to 30 % when a part enters the eye -> music-stand cone (`PlayScene.inPanelCone`, log `CONE`).
- Grep gate and its table: `../spatial-app-factory/apps/theremin/build/ui-audit.md`.
- Debug: `marker=desk|floor` sets a marker from the user's side, `tomarker` presses 移到标记处.

## Structure

```
app/src/main/java/io/embodit/theremin/
  domain/model/Instrument      geometry + both mappings (pure): ripples r(n), semitone s(d), A4 = 440, cents,
                               quantize, pitch point (palm + 4 cm along wrist->palm, level), loop height, volume curve,
                               mirror (x negated part by part), note names; InstrumentPose
  domain/audio/SynthCore       the voice: phase accumulator, harmonics of one phase, per-sample smoothing (pitch tau
                               6 ms in the log domain, volume 12 / 30 ms + 25 / 60 ms slope limits), auto vibrato,
                               DC blocker, soft limit; four timbres RMS-matched; lesson cue
  domain/play/PlayMapper       hands -> pitch / volume: per-role source (hand > controller, or debug injection),
                               1-euro filter, 60 ms source cross-fade, loss rule (hold 150 ms), pinch freeze, trigger mute
  domain/play/Practice, Songs  six lessons + hold tracker; songs.json + follow-along scorer (+-40 cents, 120 ms grace)
  domain/usecase/DeskRules     desk planes, nearest-edge snap frame (0.45 m in, shallow desk rule), hysteresis, front pose
  platform/audio/ThereminAudio AudioTrack (float, low latency, 2 bursts) + its own URGENT_AUDIO thread that reads the
                               hand / controller providers every ~4 ms block and renders (no frame, no Compose in the path);
                               SpatialAudioTrackExtension OBJECT attached to the antenna; reference tone; recordWav
  platform/input/HandInput     HandTrackingProvider PALM / WRIST / pinch (never started on the emulator)
  platform/input/ControllerInput  both controllers (play sources) + aiming ray, trigger, thumbstick
  ui/play/PlayViewModel        the state machine (rooms, stand pages, lessons, songs, hints, persistence, debug keys)
  ui/play/scene/PlayScene      ECS frame system: placing ghost / snap frame / stick, drag, stand panel on the instrument,
                               bead / hand tag / column / ripple highlights / pillar drawn from the mapper's last frame
  ui/play/scene/InstrumentView procedural instrument, ripples, labels (billboard about Y only), tag, column, stand
  ui/play/components           PlayStage (SpatialView + 2 pooled panels), Panels (place panel, music stand 9 pages,
                               pitch needle, song lane), Common (glass, keys, buttons)
app/src/debug/                 DebugCommandReceiver + DebugCommands (the QA hooks)
app/src/test/                  InstrumentTest, PlayTest, SynthTest (also writes app/build/render/*.wav)
tools/make_icon.py             512 icon export + legacy launcher PNG
```

`gradlew testDebugUnitTest`: 31 / 31 (A4 = 440.00, QA capture values, volume curve, mirror, reach, mapper loss /
freeze / cross-fade / trigger, lessons, all six songs ideal >= 95 and +60 cents < 10, desk snap, synth f0 / harmonics
/ loudness / no clicks / no zipper / mute).

## Debug broadcast

```
adb -s <serial> shell "am broadcast -a io.embodit.theremin.DEBUG -p io.embodit.theremin --es cmd '<keys>'"
```

Keys (full list in `DebugCommands.kt`): `desk=seed|none|real`, `plane=denied`, `place=ghost|snap|down|front`,
`hand=<d|off>,<h|off>` (d = pitch point to antenna axis, h = volume point over the loop, metres), `hand=idle|off`,
`joints=rightWrist:x,y,z;rightPalm:x,y,z;leftPalm:x,y,z` (instrument frame, full mapping), `pinch=0|1`,
`handpath=scale|glide|vibrato|song|song60|off`, `source=hands|controllers|none`, `lesson=L1..L6`, `step=n`,
`completetarget`, `done=1`, `song=G1..G6`, `beat=n`, `slow=0|1`, `score=n`, `seed=lessons`, `coach=1|2|3|reset`,
`timbre=sine|classic|warm|bright`, `octave=low|mid|high`, `scale=standard|wide`, `curve=soft|linear`,
`quantize=off|light|strong`, `mirror=0|1`, `mute=0|1`, `lang=zh|en|system`, `freeze=0|1`, `page=...`,
`library=basics|songs`, `settings`, `menu`, `back`, `replace`, `labeltest=1`, `recordwav=<s>` (files/rec.wav),
`pitchinfo`, `dump`, `hmd`, `bounds`, `planes`, `ui=<control>`. Log tag `Theremin`; every state change logs
`STATE Sxx[+S10+S11+S18+S06]`.

On the emulator (debug build) an empty injection source is present from the start, so S19 does not appear unless
`source=none`.

## QA screenshot list (design `stateModel.qaCombos`)

| Shot | Keys |
| --- | --- |
| S01 | launch, shoot within 1 s |
| S03 | `desk=none` right after launch (before 5 s) |
| S06 | `desk=none`, wait 6 s |
| S04 | `desk=seed place=ghost` |
| S05 | `desk=seed place=snap freeze=1` |
| S07 ① ② ③ | `coach=1` / `coach=2` / `coach=3 hand=0.398,0.15` |
| S08 | `hand=0.25,off` |
| S09 low | `scale=standard octave=mid hand=0.319,0.12` (mi · E4, about -41 cents, bright ↓) |
| S09 centre | `hand=0.310,0.12` |
| S08 + S10 | `hand=0.25,0.12` then `hand=off` |
| S09 only right lost | `hand=off,0.12` after a sounding frame |
| S09 + S11 | `source=controllers hand=0.31,0.12` |
| S12 / songs | `seed=lessons page=lessons` / `library=songs` |
| S13 half | `lesson=L1 hand=0.398,0.12`, wait 1.5 s |
| S14 on / high | `lesson=L4 step=4 hand=0.244,0.12` / `hand=0.234,0.12` |
| S15 / L6 | `lesson=L4 done=1` / `lesson=L6 done=1` |
| S16 / wide / mirror / strong | `settings` / `scale=wide` / `mirror=1` / `quantize=strong` |
| S17 | `menu` |
| S18 | `lang=en` + S09 / S12 / S16 |
| S19 | `source=none`, wait 3 s |
| S20 | `plane=denied` right after launch |
| S21 | `song=G1 beat=6 handpath=song`; `song=G3 beat=14` |
| S22 | `song=G1 score=82` |
| mute | `mute=1` |
| labels | `labeltest=1` (every 3D label shows "F↑": must read upright, not mirrored) |

Audio checks: `hand=0.31,0.12 recordwav=2` then `adb pull /data/data/io.embodit.theremin/files/rec.wav` (run-as).

## Not verified yet

- Nothing has run on an emulator or a headset: panel layout, 3D layout, label orientation, UI occlusion audit
  (`../spatial-app-factory/apps/theremin/build/ui-audit.md` pending), gaze + pinch on the stand with hand tracking on,
  latency (buffer only; HandTrackingData.timestamp is internal in 6.1.9), whether SpatialAudioTrackExtension keeps
  the low-latency path.
- G6 The Swan is a transcription from memory (transposed G -> C), not yet checked against the IMSLP score.

## v0.1.3

- Instrument yaw at placement = towards the user's real position (`PlayScene.userAt()`: head, level; else controller;
  else panel reference), computed when the marker is set and again when moving to it, then locked. Never use the
  panel-side `userRef()` for facing: a marker nearer than the panel turned the instrument backwards.
- `Instrument.standRotation(pose)` is the one place the stand's turn is defined; test
  `placedInstrumentFacesTheUserFromEverySide` guards facing / mirroring from four sides.
- Place panel is world-fixed (taste D21): `Follower` sets it once, then it moves only by its grab bar
  (`PlayScene.dragPanel`) or 「叫回面前」 (`recallPanel`). Do not bring back lazy follow or per-frame facing.
  QA debug: `paneldrag=dx,dy`, probe click `place_recall`.

## v0.1.4

- Placement = world anchor (`platform/anchor/InstrumentAnchor`), no desk needed: the instrument appears in the air
  beside the place panel; any resting pose replaces the anchor; next launch restores it before SceneReady.
- Drag any time: controller trigger 0.3 s on the cabinet, or pinch 0.3 s with the palm in `GrabRules` (never the
  playing zones). Debug `move=dx,dy,dz`.
- Music stand: `StandPlacement` above the instrument, yaw-only towards the user, grab bar + 叫回 (`standdrag`, `standrecall`).
- Sound: `OneEuro.skipRepeats` (the 4 ms audio loop must not re-feed one tracker point), two-stage glides, buffer
  3 bursts growing on underrun, room reverb. `PerformanceRenderTest` renders before / after into build/render.
