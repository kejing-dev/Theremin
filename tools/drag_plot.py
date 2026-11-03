"""Plots the panel's x (Stage) over time for two v0.1.7 drag logs: the gesture on the moving panel (feedback) and on
the SpatialView grab collider (fixed). Output: a PNG <= 1600 px wide for feedback/spatial-ui-skills/images/.

    python -I tools/drag_plot.py <feedback.log> <fixed.log> <out.png>
"""
import re
import sys

from PIL import Image, ImageDraw

frame = re.compile(r"DRAGF t=(\d+) panel=standPanel held=(\w+) pos=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)")


def series(path):
    pts = []
    for line in open(path, encoding="utf-8"):
        m = frame.search(line)
        if m:
            pts.append((int(m[1]), float(m[3]), m[2] == "true"))
    t0 = pts[0][0]
    x0 = pts[0][1]
    return [((t - t0) / 1000.0, x - x0, held) for t, x, held in pts]


W, H, PAD = 1500, 560, 70
img = Image.new("RGB", (W, H), "white")
d = ImageDraw.Draw(img)
runs = [(series(sys.argv[1]), (200, 60, 40), "gesture on the moving panel (v0.1.6 way): pointer still 3 s, panel swings"),
        (series(sys.argv[2]), (30, 110, 200), "gesture on the SpatialView grab collider (v0.1.7): settles, 0 reversals")]
tmax = max(p[-1][0] for p, _, _ in runs)
xs = [x for p, _, _ in runs for _, x, _ in p]
lo, hi = min(xs) - 0.02, max(xs) + 0.02
sx = lambda t: PAD + (W - 2 * PAD) * t / tmax
sy = lambda x: H - PAD - (H - 2 * PAD) * (x - lo) / (hi - lo)
d.line([(PAD, H - PAD), (W - PAD, H - PAD)], fill="black")
d.line([(PAD, PAD), (PAD, H - PAD)], fill="black")
d.text((PAD, 20), "theremin v0.1.7 emulator: stand panel displacement along x from the grab (m) while held, drag 3 steps then pointer rests 3 s", fill="black")
d.text((W - PAD - 60, H - PAD + 10), f"{tmax:.1f} s", fill="black")
d.text((10, PAD), f"{hi:.2f} m", fill="black")
d.text((10, H - PAD - 10), f"{lo:.2f} m", fill="black")
for i, (pts, col, label) in enumerate(runs):
    d.line([(sx(t), sy(x)) for t, x, _ in pts], fill=col, width=3)
    d.text((PAD + 20, PAD + 10 + 18 * i), label, fill=col)
img.save(sys.argv[3])
