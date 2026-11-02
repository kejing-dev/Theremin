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
  domain/usecase/PlaceRules    waiting pose beside the panel, floor marker -> stand pose, analytic ray / virtual-floor hit (no real room)
  domain/usecase/PlaceUpright  THE panel pose rule (D9): yaw from the head heading only, pitch / roll 0, 0.85 m, 15 deg below the eye line
  platform/audio/ThereminAudio AudioTrack (float, low latency, 2 bursts) + its own URGENT_AUDIO thread that reads the
                               hand / controller providers every ~4 ms block and renders (no frame, no Compose in the path);
                               SpatialAudioTrackExtension OBJECT attached to the antenna; reference tone; recordWav
  platform/input/HandInput     HandTrackingProvider PALM / WRIST / pinch (never started on the emulator)
  (no platform/sense any more: v0.1.5 removed plane detection, plane colliders and the rest-body rule, taste D24)
  platform/input/ControllerInput  both controllers (play sources) + aiming ray, trigger, thumbstick
  ui/play/PlayViewModel        the state machine (rooms, stand pages, lessons, songs, hints, persistence, debug keys)
  ui/play/scene/PlayScene      ECS frame system: marker on the virtual floor, drag, stand panel on the instrument,
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

Keys (full list in `DebugCommands.kt`): `place=front` (`desk=`, `plane=`, `place=ghost|snap|down` are accepted and ignored since v0.1.5),
`hand=<d|off>,<h|off>` (d = pitch point to antenna axis, h = volume point over the loop, metres), `hand=idle|off`,
`joints=rightWrist:x,y,z;rightPalm:x,y,z;leftPalm:x,y,z` (instrument frame, full mapping), `pinch=0|1`,
`handpath=scale|glide|vibrato|song|song60|off`, `source=hands|controllers|none`, `lesson=L1..L6`, `step=n`,
`completetarget`, `done=1`, `song=G1..G6`, `beat=n`, `slow=0|1`, `score=n`, `seed=lessons`, `coach=1|2|3|reset`,
`timbre=sine|classic|warm|bright`, `octave=low|mid|high`, `scale=standard|wide`, `curve=soft|linear`,
`quantize=off|light|strong`, `mirror=0|1`, `mute=0|1`, `lang=zh|en|system`, `freeze=0|1`, `page=...`,
`library=basics|songs`, `settings`, `menu`, `back`, `replace`, `exit` / `exit=confirm` / `exit=cancel` (D26, the user's path),
`labeltest=1`, `recordwav=<s>` (files/rec.wav), `pitchinfo`, `dump`, `hmd`, `bounds`, `reality` (= `planes`; D24 audit lines),
`marker=floor`, `tomarker`, `move=dx,dy,dz`, `paneldrag=dx,dy`, `standdrag=dx,dy`, `standrecall`, `park`, `ui=<control>`. Log tag `Theremin`; every state change logs
`STATE Sxx[+S10+S11+S18+S06]`.

On the emulator (debug build) an empty injection source is present from the start, so S19 does not appear unless
`source=none`.

## QA screenshot list (design `stateModel.qaCombos`)

| Shot | Keys |
| --- | --- |
| S01 | launch; on the headset the loading panel is up ~1.7 s after the process starts. On the emulator the Stage itself opens 3.5 - 8 s after launch (arm64 under houdini) and at idle fps the shot lags the log by seconds: go by `STARTUP place panel visible t=` |
| S03 | shoot right after S01 (the instrument appears beside the panel within a second); `replace` returns to it |
| S06 | `marker=floor tomarker` (instrument on its stand on the virtual floor) |
| S04 / S05 / S20 | gone in v0.1.5 (no desk placing, no plane access; taste D24) |
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
| S21 | `song=G1 beat=6 handpath=song`; `song=G3 beat=14` |
| S22 | `song=G1 score=82` |
| mute | `mute=1` |
| exit (D26) | `settings` then `exit` (confirmation row at the bottom of Settings); `exit=confirm` ends the process, `exit=cancel` keeps playing |
| reality (D24) | `reality` -> logcat `REALITY planes=off mesh=off occlusion=none`, `COLLIDERS … realSurfaces=0` |
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

## v0.1.5 — the real room is never read (taste D24), upright panels (D9), exit in Settings (D26)

Headset feedback on v0.1.4: "为什么还要检测现实物体，导致现在我一开始启动，琴和 UI 都被我桌上显示屏挡住了，还导致射线交互用不了".
Root cause was ours (`../spatial-app-factory/apps/theremin/build/reality-audit.md`): `PlaneColliders` gave every detected
plane (the desk monitor is a SCREEN plane) a `CollisionComponent` + `InteractableComponent`, so the system pointer hit the
monitor instead of the content; `RestBodyUseCase` pushed the place panel off real planes; the waiting pose sat 1.4 m away
where the monitor stands. The SDK has no default occlusion (checked core / sense 6.1.9, Manifest, scaffold).
- Deleted: `PlaneTracker`, `PlaneColliders`, `RestBodyUseCase`, `HitSurfaceUseCase`, `PlaneSurface` / `SurfaceKind`, desk rules
  (`DeskRules` -> `PlaceRules`), ghost / snap frame views, `landOnDesk`, rooms PLACING / NO_PLANE (states S04 / S05 / S20).
- Colliders left: the instrument's tap box and `floorPad` (the app's virtual floor at Stage y = 0, enabled only while a marker is
  wanted). Rays meet the virtual floor analytically (`PlaceRules.floorHit`). Content is drawn over the passthrough wherever it is.
- Waiting pose: 0.35 m beyond the panel (was 0.70), 0.55 m to its right; drag release stays where it is (D1).
- D9: `PlaceUpright` is the one pose rule for panels: heading yaw only, pitch / roll 0, 0.85 m, 15 deg below the eye line; the stand
  panel lost its 10 deg lean, the place panel its 12 deg lean and gaze pitch; startup all-zero head poses are rejected
  (`PlaceUpright.validHead`). `PlaceUprightTest` covers head down 30 / up 30 / roll 20. `headpose-audit.md` lists every head read.
- D26: Settings ends with "退出应用" -> "退出空中琴？" [取消] [退出] -> `PlayEffect.ExitApp`: scene detached (audio, trackers, anchor
  released), `ActivityManager.appTasks.finishAndRemoveTask()`, `closeStage()`, `exitProcess(0)` 0.4 s later. Debug `exit`,
  `exit=confirm`, `exit=cancel`; probe names `exit_app`, `exit_confirm`, `exit_cancel`.
- Stand panel 450 x 560 dp (was 410): every row / button / segment control is 48 dp now (B19; the 34-40 dp rows put the segment text off-centre) and the Settings page ends with the exit row; `tomarker` debug key fixed (it was unknown to the ViewModel).
- `LAYOUT t+1/3/5s` log lines: panel / instrument distance, yaw, pitch relative to the head, panel tilt (D1 "一打开就要看得见").

## v0.1.6 — loading panel first, timings from the process start (QA #3 q3-1 / q3-2)

- Cold start (taste D1): `PlayScene.awaitLoadingPanel()` holds the instrument build until the place panel ("正在调音…" + bar)
  has been drawn for 700 ms / 4 frames (at most 6 s after the scene attaches). With no valid head 1.2 s after launch the panel
  is placed from the Stage's default eye pose and slides to the real one once the head is valid.
- Log lines, all timed from the process start (`ThLog.sinceStartMs()`): `STARTUP app|scene attach|place panel visible|build starts t=`,
  `SCENE attached build_ms= t=`, and `LAYOUT t+1s|3s|5s age_ms=` with the head, every panel (pos, shown, visible, distance / yaw /
  pitch from the head) and the instrument (pos, shown, built).
- Headset v0.1.6: Stage 0.38 s, panel visible 1.65 s, scene built 4.1 s. Emulator: Stage 7.1 - 7.5 s (about 6 s of it is
  `SpatialClient Load lib:spatial_client` under houdini; the runtime client library is arm64-v8a only, an x86_64 install crashes
  with UnsatisfiedLinkError, so `abiFilters` stays arm64-v8a). Details: `../spatial-app-factory/apps/theremin/build/ui-audit.md`.
- B19: the home timbre "chip" is a 48 dp `WordButton` now (SpatialUI `Chip` is 32 dp at most); probe name still `timbre_chip`, 96 x 48 dp in zh and en.

