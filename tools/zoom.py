from PIL import Image
p=r"C:/Users/BigMer/Desktop/charta/阿瓦隆/zh_cn/任务卡.png"
im=Image.open(p).convert("L")
x0,y0,x1,y1=150,380,380,530
im2=im.crop((x0,y0,x1,y1)).resize((92,60))
px=im2.load()
for y in range(60):
    row=''
    for x in range(92):
        v=px[x,y]
        row+=' ' if v<50 else '.' if v<90 else ':' if v<130 else '+' if v<180 else '-' if v<225 else '#'
    print(f"{y0+int(y*(y1-y0)/60):4d} {row}")
print("x0",x0,"x1",x1,"stepX",(x1-x0)/92)
