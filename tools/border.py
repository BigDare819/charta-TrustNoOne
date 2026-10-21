import sys
from PIL import Image
p=sys.argv[1]; mx,my=map(int,sys.argv[2:4])
im=Image.open(p).convert("RGB"); W,H=im.size; px=im.load()
def isB(r,g,b): return b>r+35 and b>g+35 and b>90
runs=[]
for y in range(H):
    x=0
    while x<W:
        if isB(*px[x,y]):
            s=x
            while x<W and isB(*px[x,y]): x+=1
            if x-s>=14: runs.append((y,s,x-1,x-s))
        else: x+=1
# keep runs whose centre within 220px of mouse
near=[r for r in runs if abs(r[1]+r[3]//2-mx)<230 and abs(r[0]-my)<230]
near.sort(key=lambda r:(r[0],r[1]))
for r in near[:40]:
    print(f"y={r[0]:4d} x={r[1]:4d}..{r[2]:4d} len={r[3]}")
print("mouse",mx,my)
