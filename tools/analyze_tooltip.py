"""
Compare, for each dev screenshot, the actually-drawn tooltip box against where the
DefaultTooltipPositioner says it should be (mouse frame + (12,-12)), both mapped back
into the 640x360 design frame.  A constant delta => measurement/border bias; a
position-dependent delta => clamp/flip bug.
"""
import os, re, glob, bisect, datetime
from PIL import Image

ROOT = r"D:\deepseekharness\MyAvalon\runs\client"
LOG = os.path.join(ROOT, "logs", "latest.log")
SHOTS = os.path.join(ROOT, "screenshots")

# frame -> gui -> screen px, taken from the log lines (scale/origin are constant here)
SCALE = 1.3333334
ORIGIN_X = 0.333313
ORIGIN_Y = 0.0
FRAME_W, FRAME_H = 640, 360

line_re = re.compile(
    r"\[(\d\d):(\d\d):(\d\d)\].*framed tooltip win=\((\d+),(\d+)\) -> frame=\((\d+),(\d+)\)")

entries = []  # (seconds_of_day, fx, fy)
with open(LOG, "r", encoding="utf-8", errors="replace") as f:
    for ln in f:
        m = line_re.search(ln)
        if not m:
            continue
        h, mi, s, wx, wy, fx, fy = m.groups()
        t = int(h) * 3600 + int(mi) * 60 + int(s)
        entries.append((t, int(fx), int(fy)))
entries.sort()
times = [e[0] for e in entries]

def nearest(t):
    i = bisect.bisect_left(times, t)
    best = None
    for j in (i - 1, i, i + 1):
        if 0 <= j < len(entries):
            d = abs(entries[j][0] - t)
            if best is None or d < best[0]:
                best = (d, entries[j])
    return best

def is_bg(r, g, b):
    return (g + 3 < r) and (g + 3 < b) and (r < 95) and (b < 115)

def box(path):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    xs = []; ys = []
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if is_bg(r, g, b):
                xs.append(x); ys.append(y)
    if not xs:
        return None
    return (min(xs), min(ys), max(xs), max(ys), w, h)

def to_frame(v, origin):
    return (v - origin) / SCALE

print(f"{'shot':>8} {'mouseFrame':>12} {'expectTL':>12} {'measTL':>12} {'delta':>14}  n_px")
deltas = []
for p in sorted(glob.glob(os.path.join(SHOTS, "*.png"))):
    name = os.path.basename(p)
    m = re.search(r"_(\d\d)\.(\d\d)\.(\d\d)\.png$", name)
    if not m:
        continue
    h, mi, s = map(int, m.groups())
    t = h * 3600 + mi * 60 + s
    nb = nearest(t)
    if nb is None or nb[0] > 2:
        continue
    _, (_, fx, fy) = nb
    bb = box(p)
    if bb is None:
        print(f"{name:>8} no tooltip pixels")
        continue
    x0, y0, x1, y1, w, hh = bb
    # frame-space measured top-left
    mfx = to_frame(x0, ORIGIN_X)
    mfy = to_frame(y0, ORIGIN_Y)
    efx, efy = fx + 12, fy - 12
    dx, dy = mfx - efx, mfy - efy
    deltas.append((dx, dy, fx, fy, w, hh))
    print(f"{name:>8} ({fx:>4},{fy:>4}) ({efx:>5},{efy:>5}) ({mfx:>6.1f},{mfy:>6.1f}) "
          f"({dx:>+6.2f},{dy:>+6.2f})  {len([])}")

if deltas:
    import statistics
    dxs = [d[0] for d in deltas]; dys = [d[1] for d in deltas]
    print(f"\nn={len(deltas)}  dx mean={statistics.mean(dxs):+.2f} sd={statistics.pstdev(dxs):.2f} "
          f"min={min(dxs):+.2f} max={max(dxs):+.2f}")
    print(f"          dy mean={statistics.mean(dys):+.2f} sd={statistics.pstdev(dys):.2f} "
          f"min={min(dys):+.2f} max={max(dys):+.2f}")
    print("distinct screen sizes:", sorted({(d[4], d[5]) for d in deltas}))
