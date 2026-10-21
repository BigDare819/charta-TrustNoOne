import sys, os
from PIL import Image
from collections import deque

def is_bg(r,g,b):
    return (g+3<r) and (g+3<b) and (r<95) and (b<115)

path = sys.argv[1]
im = Image.open(path).convert("RGB")
w,h = im.size
px = im.load()
mask = [[False]*w for _ in range(h)]
for y in range(h):
    for x in range(w):
        if is_bg(*px[x,y]): mask[y][x]=True
seen=[[False]*w for _ in range(h)]
comps=[]
for y in range(h):
    for x in range(w):
        if mask[y][x] and not seen[y][x]:
            q=deque([(x,y)]); seen[y][x]=True
            x0=x1=x; y0=y1=y; n=0
            while q:
                cx,cy=q.popleft(); n+=1
                x0=min(x0,cx);x1=max(x1,cx);y0=min(y0,cy);y1=max(y1,cy)
                for dx,dy in ((1,0),(-1,0),(0,1),(0,-1)):
                    nx,ny=cx+dx,cy+dy
                    if 0<=nx<w and 0<=ny<h and mask[ny][nx] and not seen[ny][nx]:
                        seen[ny][nx]=True; q.append((nx,ny))
            comps.append((n,x0,y0,x1,y1))
comps.sort(reverse=True)
print(path, im.size)
for n,x0,y0,x1,y1 in comps[:8]:
    print(f"  px={n:6d} bbox=({x0},{y0})-({x1},{y1}) size={x1-x0+1}x{y1-y0+1}")
