from PIL import Image
p=r'C:/Users/BigMer/.workbuddy/clipboard-images/clipboard-2026-10-10T15-41-09-540Z-42eb466d.png'
im=Image.open(p).convert("RGB"); W,H=im.size; px=im.load()
st=2
for y in range(0,H,st):
    row=''
    for x in range(0,W,st):
        r,g,b=px[x,y]
        if b>r+35 and b>g+35 and b>90: c='B'
        elif r<70 and g<70 and b<70: c=' '
        elif (g+3<r and g+3<b and r<95 and b<115): c='.'
        elif r>200 and g>200 and b>200: c='#'
        elif r>140 and g>140 and b>140: c='+'
        elif g>r+10 and g>b+10: c='G'
        else: c='-'
        row+=c
    print(f"{y:4d} {row}")
