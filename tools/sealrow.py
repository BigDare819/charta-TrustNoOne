from PIL import Image
p=r"C:/Users/BigMer/Desktop/charta/阿瓦隆/zh_cn/任务卡.png"
im=Image.open(p).convert("L"); W,H=im.size
px=im.load()
for yf in (0.375,0.38305,0.395,0.405,0.36,0.35):
    y=int(yf*H)
    runs=[];x=0
    while x<W:
        if px[x,y]<110:
            s=x
            while x<W and px[x,y]<110: x+=1
            runs.append((s,x-1))
        else: x+=1
    runs=[r for r in runs if r[1]-r[0]>=3 and 40<r[0]<W-40]
    print(f"y={y} frac={yf:.4f} dark runs:", [( (a+b)//2, b-a+1) for a,b in runs])
print("expected seal x:", [round(f*2020) for f in (0.12525,0.30891,0.50099,0.68465,0.86832)])
print("expected seal y:", round(0.38305*1180))
