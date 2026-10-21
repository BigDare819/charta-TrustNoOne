import sys
from PIL import Image
p=sys.argv[1]; mx,my=map(int,sys.argv[2:4])
im=Image.open(p).convert("RGB");W,H=im.size;px=im.load()
def isB(r,g,b): return b>r+35 and b>g+35 and b>90
for y in range(0,H):
    x=0
    while x<W:
        if isB(*px[x,y]):
            s=x
            while x<W and isB(*px[x,y]): x+=1
            if x-s>=16 and abs(y-my)<60 and abs((s+x)//2-mx)<250:
                print(f"y={y:4d} x={s:4d}..{x-1:4d} len={x-s}")
        else: x+=1
print("mouse",mx,my,"expected border x=",round((mx/1.3333334+12-3)*1.3333334),"y=",round((my/1.3333334-12-3)*1.3333334))
