from PIL import Image
p=r"C:/Users/BigMer/Desktop/charta/阿瓦隆/zh_cn/任务卡.png"
im=Image.open(p).convert("L")
x0,y0,x1,y1=150,350,640,570
im2=im.crop((x0,y0,x1,y1)).resize((147,66))
px=im2.load()
for y in range(66):
    row=''
    for x in range(147):
        v=px[x,y]
        row+=' ' if v<50 else '.' if v<90 else ':' if v<130 else '+' if v<180 else '-' if v<225 else '#'
    print(f"{y0+int(y*(y1-y0)/66):4d} {row}")
print("x0",x0,"stepX",(x1-x0)/147)
