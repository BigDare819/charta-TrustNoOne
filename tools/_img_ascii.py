import sys
from PIL import Image
import numpy as np

RAMP = " .:-=+*#%@"

def art(path, cols=72):
    im = Image.open(path).convert("RGBA")
    a = np.asarray(im).astype(np.int32)
    h, w, _ = a.shape
    alpha = a[:, :, 3]
    rows = max(1, int(cols * h / w / 2))
    print(f"=== {path} ({w}x{h}) ===")
    lum = a[:, :, :3].mean(axis=2)
    for ry in range(rows):
        line = ""
        for rx in range(cols):
            y0, y1 = ry * h // rows, max(ry * h // rows + 1, (ry + 1) * h // rows)
            x0, x1 = rx * w // cols, max(rx * w // cols + 1, (rx + 1) * w // cols)
            if alpha[y0:y1, x0:x1].mean() < 16:
                line += " "
                continue
            v = lum[y0:y1, x0:x1].mean()
            line += RAMP[min(len(RAMP) - 1, int(v / 256 * len(RAMP)))]
        print("|" + line + "|")

for p in sys.argv[1:]:
    art(p)
