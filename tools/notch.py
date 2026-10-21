from PIL import Image
p=r"C:/Users/BigMer/Desktop/charta/阿瓦隆/zh_cn/任务卡.png"
im=Image.open(p).convert("L");W,H=im.size;px=im.load()
for y in (960,975,987,1000,1015):
    runs=[];x=0
    while x<W:
        if px[x,y]<120:
            s=x
            while x<W and px[x,y]<120:x+=1
            if x-s>=4 and 40<s<W-40: runs.append(((s+x)//2,x-s))
        else:x+=1
    print(f"y={y}:",runs)
print("notch x expected:", [round(f*2020) for f in (0.09554,0.23688,0.37996,0.52426,0.66064)], "y", round(0.83686*1180))
