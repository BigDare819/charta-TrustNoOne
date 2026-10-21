import os, re, glob, bisect, statistics
from PIL import Image
from collections import deque

ROOT = r"D:/deepseekharness/MyAvalon/runs/client"
LOG = os.path.join(ROOT,"logs","latest.log")
SHOTS = os.path.join(ROOT,"screenshots")
SCALE=1.3333334; OX=0.333313; OY=0.0

line_re=re.compile(r"\[(\d\d):(\d\d):(\d\d)\].*framed tooltip win=\((\d+),(\d+)\) -> frame=\((\d+),(\d+)\)")
E=[]
with open(LOG,encoding="utf-8",errors="replace") as f:
    for ln in f:
        m=line_re.search(ln)
        if m:
            h,mi,s,wx,wy,fx,fy=m.groups()
            E.append((int(h)*3600+int(mi)*60+int(s),int(fx),int(fy)))
E.sort(); T=[e[0] for e in E]
def nearest(t):
    i=bisect.bisect_left(T,t); best=None
    for j in (i-1,i,i+1):
        if 0<=j<len(E):
            d=abs(E[j][0]-t)
            if best is None or d<best[0]: best=(d,E[j])
    return best

def is_bg(r,g,b): return (g+3<r) and (g+3<b) and (r<95) and (b<115)

def near_mouse_box(path, mxp, myp, rad=170):
    im=Image.open(path).convert("RGB"); w,h=im.size; px=im.load()
    x0=max(0,int(mxp-rad)); x1=min(w,int(mxp+rad)); y0=max(0,int(myp-rad)); y1=min(h,int(myp+rad))
    mask={}
    for y in range(y0,y1):
        for x in range(x0,x1):
            if is_bg(*px[x,y]): mask[(x,y)]=True
    seen=set(); comps=[]
    for p in mask:
        if p in seen: continue
        q=deque([p]); seen.add(p); bb=[p[0],p[1],p[0],p[1]]; n=0
        while q:
            cx,cy=q.popleft(); n+=1
            bb[0]=min(bb[0],cx);bb[1]=min(bb[1],cy);bb[2]=max(bb[2],cx);bb[3]=max(bb[3],cy)
            for dx,dy in ((1,0),(-1,0),(0,1),(0,-1)):
                np_=(cx+dx,cy+dy)
                if np_ in mask and np_ not in seen:
                    seen.add(np_); q.append(np_)
        comps.append((n,bb))
    if not comps: return None
    comps.sort(reverse=True, key=lambda c:c[0])
    return comps[0][1]

rows=[]
for p in sorted(glob.glob(os.path.join(SHOTS,"*.png"))):
    nm=os.path.basename(p)
    m=re.search(r"_(\d\d)\.(\d\d)\.(\d\d)\.png$",nm)
    if not m: continue
    h,mi,s=map(int,m.groups()); t=h*3600+mi*60+s
    nb=nearest(t)
    if nb is None or nb[0]>2: continue
    fx,fy=nb[1][1],nb[1][2]
    mxp=fx*SCALE+OX; myp=fy*SCALE+OY
    bb=near_mouse_box(p,mxp,myp)
    if bb is None:
        rows.append((nm,fx,fy,None)); continue
    dxf=(bb[0]-OX)/SCALE - (fx+12)
    dyf=(bb[1]-OY)/SCALE - (fy-12)
    rows.append((nm,fx,fy,(dxf,dyf,bb)))

ok=[r for r in rows if r[3]]
print(f"shots with nearby comp: {len(ok)}/{len(rows)}")
for nm,fx,fy,info in ok:
    dxf,dyf,bb=info
    print(f"{nm[11:]} mouse=({fx:4},{fy:4}) box={bb} dFrame=({dxf:+7.2f},{dyf:+7.2f})")
if ok:
    dxs=[r[3][0] for r in ok]; dys=[r[3][1] for r in ok]
    print(f"\ndx mean={statistics.mean(dxs):+.2f} sd={statistics.pstdev(dxs):.2f} range=({min(dxs):+.2f},{max(dxs):+.2f})")
    print(f"dy mean={statistics.mean(dys):+.2f} sd={statistics.pstdev(dys):.2f} range=({min(dys):+.2f},{max(dys):+.2f})")
