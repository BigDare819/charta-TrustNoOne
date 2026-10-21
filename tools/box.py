from PIL import Image
p=r"C:/Users/BigMer/Desktop/charta/阿瓦隆/zh_cn/任务卡.png"
im=Image.open(p).convert("L"); W,H=im.size
px=im.load()
for cx in (253,624,1012,1383,1754):
    ys=[];xs=[]
    for y in range(370,500):
        for x in range(cx-70,cx+70):
            if px[x,y]<120: ys.append(y);xs.append(x)
    if ys: print(f"cx={cx} bbox x=({min(xs)},{max(xs)}) y=({min(ys)},{max(ys)}) centre=({(min(xs)+max(xs))//2},{(min(ys)+max(ys))//2})")
print("fracY predicted", 0.38305, "->", round(0.38305*H))
