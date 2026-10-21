import os,re,glob,bisect,statistics
from PIL import Image
ROOT=r"D:/deepseekharness/MyAvalon/runs/client"
LOG=os.path.join(ROOT,"logs","latest.log"); SHOTS=os.path.join(ROOT,"screenshots")
S=1.3333334; OX=0.333313; OY=0.0
lr=re.compile(r"\[(\d\d):(\d\d):(\d\d)\].*framed tooltip win=\((\d+),(\d+)\) -> frame=\((\d+),(\d+)\)")
E=[]
for ln in open(LOG,encoding="utf-8",errors="replace"):
    m=lr.search(ln)
    if m:
        h,mi,s,wx,wy,fx,fy=m.groups(); E.append((int(h)*3600+int(mi)*60+int(s),int(fx),int(fy)))
E.sort(); T=[e[0] for e in E]
def nearest(t):
    i=bisect.bisect_left(T,t); b=None
    for j in (i-1,i,i+1):
        if 0<=j<len(E):
            d=abs(E[j][0]-t)
            if b is None or d<b[0]: b=(d,E[j])
    return b
def isB(r,g,b): return b>r+35 and b>g+35 and b>90
def find_border(path,mx,my):
    im=Image.open(path).convert("RGB");W,H=im.size;px=im.load()
    best=None
    for y in range(max(0,int(my)-40),min(H,int(my)+40)):
        x=0
        while x<W:
            if isB(*px[x,y]):
                s=x
                while x<W and isB(*px[x,y]):x+=1
                if x-s>=16:
                    x0=s; w=x-s
                    # prefer runs near mouse horizontally starting near mouse+12px
                    d=abs((x0)- (mx+16))
                    if d<120:
                        cand=(d,y,x0,w)
                        if best is None or cand[0]<best[0]: best=cand
            else:x+=1
    return best
rows=[]
for p in sorted(glob.glob(os.path.join(SHOTS,"*.png"))):
    nm=os.path.basename(p); m=re.search(r"_(\d\d)\.(\d\d)\.(\d\d)\.png$",nm)
    if not m: continue
    h,mi,s=map(int,m.groups()); t=h*3600+mi*60+s
    nb=nearest(t)
    if nb is None or nb[0]>2: continue
    fx,fy=nb[1][1],nb[1][2]
    mx=fx*S+OX; my=fy*S+OY
    b=find_border(p,mx,my)
    if b is None: continue
    _,by,bx,bw=b
    dx=(bx-OX)/S-(fx+12); dy=(by-OY)/S-(fy-12)
    rows.append((nm,fx,fy,bx,by,bw,dx,dy))
for r in rows:
    print(f"{r[0][11:]} mouse=({r[1]:4},{r[2]:4}) border=({r[3]},{r[4]}) w={r[5]} dFrame=({r[6]:+6.2f},{r[7]:+6.2f})")
if rows:
    dxs=[r[6] for r in rows]; dys=[r[7] for r in rows]
    print(f"n={len(rows)} dx {statistics.mean(dxs):+.2f}±{statistics.pstdev(dxs):.2f}  dy {statistics.mean(dys):+.2f}±{statistics.pstdev(dys):.2f}")
