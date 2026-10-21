from PIL import Image
p=r"C:/Users/BigMer/Desktop/charta/阿瓦隆/zh_cn/任务卡.png"
im=Image.open(p).convert("L"); W,H=im.size
y0,y1=int(0.26*H),int(0.50*H)
im2=im.crop((0,y0,W,y1)).resize((160,28))
px=im2.load()
for y in range(28):
    row=''
    for x in range(160):
        v=px[x,y]
        row+=' ' if v<50 else '.' if v<90 else ':' if v<130 else '+' if v<180 else '-' if v<225 else '#'
    print(f"{y:3d} {row}")
print("y0",y0,"stepY",(y1-y0)/28,"stepX",W/160)
# predicted seal columns
for f in (0.12525,0.30891,0.50099,0.68465,0.86832):
    print("seal x col", f*160, "y row", (0.38305*H-y0)/((y1-y0)/28))
