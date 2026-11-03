"""Still-hand jitter from a v0.1.7 drag log (taste D21: < 2 mm while the hand rests, yaw unchanged while held).

Reads `GRIP start` and the per-frame `DRAGF t=<ms> panel=<id> held=<bool> pos=(x, y, z) yaw=<deg>` lines. For the
window [from_ms, to_ms] after the grab (the pointer resting: emu.sh drag -PauseAtStep / -PauseMs) it prints the
largest distance of the panel from where it was at the window start and the number of direction reversals (> 0.1
mm); over the whole hold, the yaw spread. A feedback loop shows as reversals; the system's own easing of the drag
point shows as a small one-way creep.

    python -I tools/drag_jitter.py <log> <panel> <from_ms> <to_ms>
"""
import re
import sys

lines = open(sys.argv[1], encoding="utf-8").read().splitlines()
panel, t_from, t_to = sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
frame = re.compile(r"DRAGF t=(\d+) panel=(\S+) held=(\w+) pos=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\) yaw=([-\d.]+)")
frames = []
for line in lines:
    f = frame.search(line)
    if f and f[2] == panel and f[3] == "true":
        frames.append((int(f[1]), tuple(float(f[i]) for i in (4, 5, 6)), float(f[7])))
if not frames:
    sys.exit("no held frames for " + panel)
t0 = frames[0][0]
win = [f for f in frames if t_from <= f[0] - t0 <= t_to]
p0 = win[0][1]
dist = lambda a, b: sum((x - y) ** 2 for x, y in zip(a, b)) ** 0.5
dev = max(dist(f[1], p0) for f in win)
rev, last = 0, None
for a, b in zip(win, win[1:]):
    d = tuple(y - x for x, y in zip(a[1], b[1]))
    if dist(d, (0, 0, 0)) < 1e-4:
        continue
    if last is not None and sum(x * y for x, y in zip(d, last)) < 0:
        rev += 1
    last = d
yaws = [f[2] for f in frames]
print(f"held_frames={len(frames)} window={t_from}-{t_to}ms frames={len(win)} max_dev_mm={dev * 1000:.2f} reversals={rev} "
      f"yaw_spread_deg={max(yaws) - min(yaws):.2f}")
