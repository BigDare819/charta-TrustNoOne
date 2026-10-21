from PIL import Image
p=r'C:/Users/BigMer/.workbuddy/clipboard-images/clipboard-2026-10-10T15-41-09-540Z-42eb466d.png'
im=Image.open(p).convert("RGB"); W,H=im.size; px=im.load()
def blk(r,g,b): return r<45 and g<45 and b<45
rows={}
for y in range(H):
    c=0
    for x in range(W):
        if blk(*px[x,y]): c+=1
    rows[y]=c
print("rows with >200 black px:", [y for y,c in rows.items() if c>200])
# for a few rows, the black x-range
for y in (30,60,100,120,140,160):
    xs=[x for x in range(W) if blk(*px[x,y])]
    print(y, "black x:", (min(xs),max(xs),len(xs)) if xs else None)
# histogram of black per column
cols={}
for x in range(W):
    c=0
    for y in range(H):
        if blk(*px[x,y]): c+=1
    cols[x]=c
print("cols with >150 black:", [x for x,c in cols.items() if c>150][:5], "...", [x for x,c in cols.items() if c>150][-5:])
