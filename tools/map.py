import sys
from PIL import Image
p=sys.argv[1]; mx,my=map(int,sys.argv[2:4])
im=Image.open(p).convert("RGB"); W,H=im.size; px=im.load()
x0=max(0,mx-140); x1=min(W,mx+180); y0=max(0,my-130); y1=min(H,my+130)
def cls(r,g,b):
    if b>r+35 and b>g+35 and b>90: return 'B'   # blue border
    if g>r+10 and g>b+10: return 'G'
    if r>200 and g>200 and b>200: return '#'     # white text
    if r>140 and g>140 and b>140: return '+'     # light gray
    if (g+3<r) and (g+3<b) and (r<95) and (b<115): return '.'  # dark purple
    if r<60 and g<60 and b<60: return ' '        # near black
    return '-'
for y in range(y0,y1,2):
    row=''.join(cls(*px[x,y]) for x in range(x0,x1,2))
    mark='<<' if abs(y-my)<2 else ''
    print(f"{y:4d} {row} {mark}")
print(f"mouse screen=({mx},{my}) x0={x0}")
