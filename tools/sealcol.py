from PIL import Image
p=r"C:/Users/BigMer/Desktop/charta/阿瓦隆/zh_cn/任务卡.png"
im=Image.open(p).convert("L"); W,H=im.size
px=im.load()
for x in (252,623,1012,1753):
    runs=[];y=300
    while y<600:
        if px[x,y]<130:
            s=y
            while y<600 and px[x,y]<130: y+=1
            runs.append((s,y-1))
        else: y+=1
    runs=[r for r in runs if r[1]-r[0]>=2]
    print(f"x={x} dark y-runs:", runs, " centre:", [ (a+b)//2 for a,b in runs])
print("expected y", round(0.38305*1180))
