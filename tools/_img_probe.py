import sys
from PIL import Image
import numpy as np

RAMP = " .:-=+*#%@"

def probe(path, cols=44):
    try:
        im = Image.open(path).convert("RGBA")
    except Exception as e:
        print(f"!! {path}: {e}")
        return
    a = np.asarray(im).astype(np.int32)
    h, w, _ = a.shape
    alpha = a[:, :, 3]
    mask = alpha > 8
    vis = a[mask]
    print(f"== {path}  size={w}x{h}  opaque={mask.sum()}/{w*h}")
    if len(vis):
        print(f"   mean RGB over opaque = ({vis[:,0].mean():.0f},{vis[:,1].mean():.0f},{vis[:,2].mean():.0f})  "
              f"max=({vis[:,0].max()},{vis[:,1].max()},{vis[:,2].max()})")
    # coarse luminance art
    rows = max(1, int(cols * h / w / 2))
    lum = a[:, :, :3].mean(axis=2)
    for ry in range(rows):
        line = ""
        for rx in range(cols):
            y0, y1 = ry * h // rows, max(ry * h // rows + 1, (ry + 1) * h // rows)
            x0, x1 = rx * w // cols, max(rx * w // cols + 1, (rx + 1) * w // cols)
            blk_a = alpha[y0:y1, x0:x1].mean()
            if blk_a < 16:
                line += " "
                continue
            v = lum[y0:y1, x0:x1].mean()
            line += RAMP[min(len(RAMP) - 1, int(v / 256 * len(RAMP)))]
        print("   |" + line + "|")

for p in sys.argv[1:]:
    probe(p)
