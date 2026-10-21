from PIL import Image
p=r'C:/Users/BigMer/.workbuddy/clipboard-images/clipboard-2026-10-10T15-41-09-540Z-42eb466d.png'
im=Image.open(p).convert("RGB"); W,H=im.size; px=im.load()
def bright(r,g,b): return r>185 and g>185 and b>185
def green(r,g,b): return g>r+10 and g>b+10 and g>60
for name,f in (("bright",bright),("green",green)):
    xs=[];ys=[]
    for y in range(H):
        for x in range(W):
            if f(*px[x,y]): xs.append(x);ys.append(y)
    if xs:
        print(name,"bbox",min(xs),min(ys),max(xs),max(ys),"n",len(xs))
        # column histogram extremes
        import collections
        colc=collections.Counter(xs)
        print("  x range cols>5:", min(c for c,v in colc.items() if v>5), max(c for c,v in colc.items() if v>5))
        rowc=collections.Counter(ys)
        print("  y range rows>5:", min(c for c,v in rowc.items() if v>5), max(c for c,v in rowc.items() if v>5))
