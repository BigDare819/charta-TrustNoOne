import sys
from PIL import Image
import numpy as np
RAMP = " .:-=+*#%@"
p = sys.argv[1]
im = Image.open(p).convert("RGBA")
a = np.asarray(im).astype(np.int32)
h, w, _ = a.shape
cols = 120
rows = max(1, int(cols*h/w/2))
print(f"{p} {w}x{h}")
lum = a[:,:,:3].mean(axis=2); alpha=a[:,:,3]
for ry in range(rows):
    line=""
    for rx in range(cols):
        y0,y1 = ry*h//rows, max(ry*h//rows+1,(ry+1)*h//rows)
        x0,x1 = rx*w//cols, max(rx*w//cols+1,(rx+1)*w//cols)
        if alpha[y0:y1,x0:x1].mean()<16: line+=" "; continue
        line += RAMP[min(9,int(lum[y0:y1,x0:x1].mean()/256*10))]
    print(line)
