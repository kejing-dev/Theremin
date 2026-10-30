"""Air Theremin icon: the 512 export for the factory gallery and the legacy launcher PNG, drawn from the same
shapes as the adaptive icon (`res/drawable/ic_launcher_foreground.xml` on `@color/ic_launcher_background`):
a vertical antenna on the right, three concentric ripples on its left, an amber bead on the middle one.
Usage: python -I tools/make_icon.py <factory icon.png path>
"""
import math
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
BG = (0x4A, 0x2C, 0x1A, 255)
WHITE = (255, 255, 255, 255)
AMBER = (0xFF, 0xB5, 0x47, 255)


def draw(size, rounded=True):
    k = 4
    s = size * k
    u = s / 108.0  # adaptive icon viewport units
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if rounded:
        d.rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.22), fill=BG)
    else:
        d.rectangle([0, 0, s, s], fill=BG)
    # the adaptive foreground is 108 units; the visible icon is the middle 72: scale 108 -> 72 view
    z = 108.0 / 72.0
    def p(x, y):
        return ((x - 18) * z * u, (y - 18) * z * u)
    cx, cy, top = 68.0, 54.0, 30.0
    w = 7.5 * z * u
    d.line([p(cx, 78), p(cx, top)], fill=WHITE, width=int(w))
    for (x, y) in (p(cx, 78), p(cx, top)):
        r = w / 2
        d.ellipse([x - r, y - r, x + r, y + r], fill=WHITE)
    r0 = 5.5 * z * u
    x, y = p(cx, top - 1)
    d.ellipse([x - r0, y - r0, x + r0, y + r0], fill=WHITE)
    for r in (12, 21, 30):
        rr = r * z * u
        c = p(cx, cy)
        aw = int(5.4 * z * u)
        # PIL angles are clockwise from +x; the arcs span 155..235 degrees counter-clockwise (y up)
        d.arc([c[0] - rr, c[1] - rr, c[0] + rr, c[1] + rr], start=360 - 215, end=360 - 145, fill=WHITE, width=aw)
        for a in (145, 215):
            ex = c[0] + rr * math.cos(math.radians(a)) - 0
            ey = c[1] - rr * math.sin(math.radians(a))
            # round caps sit on the stroke's centre line
            rc = aw / 2
            mx = c[0] + (rr - aw / 2) * math.cos(math.radians(a))
            my = c[1] - (rr - aw / 2) * math.sin(math.radians(a))
            d.ellipse([mx - rc, my - rc, mx + rc, my + rc], fill=WHITE)
    bx, by = p(cx - 21, cy)
    rb = 6.5 * z * u
    d.ellipse([bx - rb, by - rb, bx + rb, by + rb], fill=AMBER)
    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "icon-512.png")
    draw(512).save(out)
    draw(192, rounded=False).save(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png"))
    print("wrote", out)
