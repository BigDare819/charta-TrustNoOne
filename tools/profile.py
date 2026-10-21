from PIL import Image
import sys
p=r"C:/Users/BigMer/Desktop/charta/阿瓦隆/zh_cn/任务卡.png"
im=Image.open(p).convert("L"); W,H=im.size
px=im.load()
def band(yf,tol=0.02):
    y0=int((yf-tol)*H); y1=int((yf+tol)*H)
    prof=[]
    for x in range(0,W,10):
        v=min(px[x,y] for y in range(y0,y1))
        prof.append(v)
    return y0,y1,prof
for yf in (0.38305,0.83686):
    y0,y1,prof=band(yf)
    print(f"--- band y={y0}..{y1} (frac {yf}) ---")
    # print profile as digits per 20px
    line=''
    for v in prof: line+= str(min(9,v//26))
    print(line)
y0,y1,prof=band(0.38305)
# find local minima
import statistics
m=statistics.mean(prof)
dips=[(x*10,v) for x,v in enumerate(prof) if v< m*0.6]
print("dark cols near seal band:", dips[:60])
print("mean",m)
