from PIL import Image
p=r'C:/Users/BigMer/.workbuddy/clipboard-images/clipboard-2026-10-10T15-41-09-540Z-42eb466d.png'
im=Image.open(p).convert("RGB"); W,H=im.size; px=im.load()
print("size",W,H)
def isB(r,g,b): return b>r+35 and b>g+35 and b>90
runs=[]
for y in range(H):
    x=0
    while x<W:
        if isB(*px[x,y]):
            s=x
            while x<W and isB(*px[x,y]): x+=1
            if x-s>=10: runs.append((y,s,x-1,x-s))
        else: x+=1
print("blue horiz runs>=10:", runs)
# coarse map
import math
sx=max(1,W//114); sy=max(1,H//48)
for yy in range(0,H,sy):
    row=''
    for xx in range(0,W,sx):
        # take max-ish over cell
        rs=gs=bs=0;n=0
        for dy in range(sy):
            for dx in range(sx):
                if xx+dx<W and yy+dy<H:
                    r,g,b=px[xx+dx,yy+dy];rs+=r;gs+=g;bs+=b;n+=1
        r,g,b=rs//n,gs//n,bs//n
        if b>r+30 and b>g+30 and b>90: c='B'
        elif r<70 and g<70 and b<70: c=' '
        elif (g+3<r and g+3<b and r<95 and b<115): c='.'
        elif r>200 and g>200 and b>200: c='#'
        elif r>140 and g>140 and b>140: c='+'
        elif g>r+8 and g>b+8: c='G'
        else: c='-'
        row+=c
    print(f"{yy:4d} {row}")
