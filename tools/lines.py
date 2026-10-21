from PIL import Image
p=r'C:/Users/BigMer/.workbuddy/clipboard-images/clipboard-2026-10-10T15-41-09-540Z-42eb466d.png'
im=Image.open(p).convert("RGB"); W,H=im.size; px=im.load()
def bright(r,g,b): return r>170 and g>170 and b>170
hr=[]
for y in range(H):
    x=0
    while x<W:
        if bright(*px[x,y]):
            s=x
            while x<W and bright(*px[x,y]): x+=1
            if x-s>=30: hr.append((y,s,x-1))
        else: x+=1
print("long bright horizontal runs:")
for r in hr: print("  y=%d x=%d..%d len=%d"%(r[0],r[1],r[2],r[2]-r[1]+1))
