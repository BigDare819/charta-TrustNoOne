import sys
from PIL import Image
p=sys.argv[1]
sx=float(sys.argv[2]); sy=float(sys.argv[3]); k=float(sys.argv[4])
im=Image.open(p).convert("RGB"); W,H=im.size
print("size",W,H)
im2=im.resize((102,60))
px=im2.load()
# marks from fractions in args 5.. as x,y pairs
marks=[(sx-0.006,sy-0.012),(sx-0.006,sy+0.012),(sx+0.006,sy-0.012),(sx+0.006,sy+0.012)]
for y in range(60):
    row=''
    for x in range(102):
        r,g,b=px[x,y]
        v=(r+g+b)/3
        row += ' ' if v<50 else '.' if v<100 else ':' if v<150 else '+' if v<200 else '#'
    print(f"{y:3d} {row}")
