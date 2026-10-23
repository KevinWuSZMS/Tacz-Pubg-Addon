"""Author smooth PUBG-inspired item meshes; no game mesh/texture is copied.

All silhouettes are parametrically authored from the official item reference
sheet. The reproducible atlas uses original fabric noise, stitching and labels.
Runtime contract: interleaved x,y,z,u,v,nx,ny,nz, three vertices per triangle.
Coordinates use the same 0..16 item units and +Z front as the low-poly models.
"""
from pathlib import Path
import hashlib, json, math, random
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "assets-workbench/pubgmc-reference"
ASSETS = ROOT / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg"
TAU = math.tau
COLORS = [
    (67, 77, 49), (130, 109, 77), (45, 49, 47), (185, 190, 185),
    (219, 211, 183), (33, 57, 107), (157, 49, 42), (30, 142, 149),
    (82, 58, 35), (204, 185, 137), (116, 123, 115), (25, 29, 29),
    (120, 143, 137), (180, 132, 50), (69, 86, 70), (194, 197, 188),
    (71, 84, 58), (137, 128, 106), (55, 65, 54), (50, 61, 66),
    (26, 52, 98), (210, 205, 179), (206, 197, 160), (220, 220, 205),
    (41, 103, 158), (159, 122, 55), (203, 167, 70), (146, 62, 43),
    (43, 66, 51), (80, 73, 62), (74, 71, 65), (80, 82, 75),
    (205, 199, 179), (140, 158, 160), (90, 102, 102), (57, 65, 60),
    (22, 35, 29), (133, 123, 102), (147, 112, 49),
]


def normalize(v):
    v = np.asarray(v, dtype=float)
    return v / max(float(np.linalg.norm(v)), 1e-12)


def font(size, bold=False):
    for path in [Path("C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf")]:
        if path.exists():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default()


def atlas():
    random.seed(334691)
    im = Image.new("RGB", (512, 512), (50, 50, 50))
    dr = ImageDraw.Draw(im)
    for i, color in enumerate(COLORS):
        ox, oy = i % 8 * 64, i // 8 * 64
        dr.rectangle((ox, oy, ox + 63, oy + 63), fill=color)
        fabric = i in (0, 1, 4, 8, 9, 14, 16, 17, 18, 21, 22, 28, 29, 30, 32)
        for y in range(64):
            for x in range(64):
                grain = random.randint(-5, 5) + (2 if fabric and (x + y) % 3 == 0 else 0)
                im.putpixel((ox + x, oy + y), tuple(max(0, min(255, c + grain)) for c in color))
        if i in (16, 17, 18, 32):
            camo = [(88, 88, 71), (173, 164, 134), (113, 117, 98)] if i != 18 else [(36, 45, 38), (89, 91, 65), (107, 103, 74)]
            if i == 32:
                camo = [(110, 108, 101), (162, 157, 141), (63, 65, 62)]
            for _ in range(54):
                x, y = random.randrange(56), random.randrange(56)
                w, h = random.randrange(3, 15), random.randrange(3, 9)
                dr.rectangle((ox + x, oy + y, ox + min(63, x + w), oy + min(63, y + h)), fill=random.choice(camo))
        if fabric:
            for y in (3, 60):
                for x in range(4, 59, 5):
                    dr.line((ox + x, oy + y, ox + x + 2, oy + y), fill=tuple(min(255, c + 38) for c in color))
    def tile(index):
        return index % 8 * 64, index // 8 * 64
    # Cylindrical label is repeated around the body so its front remains legible.
    x, y = tile(20)
    dr.rectangle((x, y, x + 63, y + 5), fill=(198, 187, 137))
    dr.rectangle((x, y + 58, x + 63, y + 63), fill=(198, 187, 137))
    for cx in (16, 48):
        dr.ellipse((x + cx - 11, y + 14, x + cx + 11, y + 43), fill=(208, 192, 136), outline=(239, 220, 159), width=2)
        dr.ellipse((x + cx - 4, y + 18, x + cx + 4, y + 27), fill=(86, 84, 67))
        dr.polygon([(x + cx, y + 27), (x + cx - 7, y + 39), (x + cx + 8, y + 39)], fill=(86, 84, 67))
    dr.text((x + 8, y + 45), "ENERGY", font=font(11, True), fill=(231, 217, 169))
    x, y = tile(21)
    dr.ellipse((x + 7, y + 7, x + 57, y + 57), fill=(218, 216, 195), outline=(87, 82, 65), width=2)
    dr.text((x + 17, y + 10), "H", font=font(40, True), fill=(158, 45, 41))
    x, y = tile(22)
    dr.rectangle((x + 4, y + 7, x + 59, y + 16), fill=(41, 89, 115))
    dr.text((x + 7, y + 20), "BANDAGE", font=font(10, True), fill=(51, 61, 54))
    for j in range(3):
        dr.line((x + 9, y + 38 + j * 5, x + 54 - j * 6, y + 38 + j * 5), fill=(95, 94, 75), width=1)
    dr.rectangle((x + 43, y + 48, x + 58, y + 58), fill=(170, 102, 52))
    x, y = tile(23)
    dr.rectangle((x, y + 5, x + 63, y + 20), fill=(30, 143, 158))
    dr.text((x + 4, y + 8), "PAINKILLER", font=font(8, True), fill=(231, 237, 224))
    for j in range(5):
        dr.line((x + 7, y + 29 + j * 5, x + 55 - j % 2 * 9, y + 29 + j * 5), fill=(74, 85, 78))
    x, y = tile(24)
    dr.rectangle((x + 5, y + 5, x + 58, y + 58), outline=(175, 207, 219), width=2)
    dr.text((x + 10, y + 12), "BZ", font=font(24, True), fill=(213, 234, 236))
    dr.text((x + 6, y + 41), "EMITTER", font=font(10, True), fill=(213, 234, 236))
    x, y = tile(25)
    dr.text((x + 9, y + 10), "SMOKE", font=font(12, True), fill=(22, 35, 26))
    dr.line((x + 7, y + 31, x + 56, y + 31), fill=(52, 55, 32), width=3)
    dr.text((x + 11, y + 38), "M18", font=font(18, True), fill=(30, 38, 26))
    x, y = tile(26)
    dr.polygon([(x + 32, y + 8), (x + 8, y + 51), (x + 56, y + 51)], fill=(209, 177, 67), outline=(35, 37, 30))
    dr.text((x + 27, y + 17), "!", font=font(27, True), fill=(36, 37, 30))
    x, y = tile(33)
    for j in range(1, 12):
        dr.line((x + 7, y + j * 5, x + (30 if j % 2 else 40), y + j * 5), fill=(44, 58, 61))
    x, y = tile(36)
    dr.rectangle((x+3,y+6,x+60,y+57),outline=(70,95,75),width=2)
    dr.text((x+8,y+11),"ARMED",font=font(11,True),fill=(202,83,49))
    dr.text((x+7,y+29),"00:16",font=font(19,True),fill=(222,106,54))
    x, y = tile(37)
    dr.rectangle((x+4,y+7,x+59,y+57),outline=(77,75,64),width=2)
    dr.text((x+8,y+14),"ARMOR",font=font(13,True),fill=(43,48,41))
    dr.text((x+9,y+34),"LEVEL III",font=font(10,True),fill=(53,57,49))
    return im


class Mesh:
    def __init__(self, name):
        self.name = name
        self.vertices = []
        self.parts = {}

    def tri(self, points, normals, mat, uv=None):
        points = np.asarray(points)
        if np.linalg.norm(np.cross(points[1] - points[0], points[2] - points[0])) < 1e-9:
            return
        normals = np.asarray(normals)
        if normals.ndim == 1:
            normals = np.tile(normals, (3, 1))
        uv = np.asarray(uv if uv is not None else [(0, 0), (1, 0), (1, 1)])
        if np.dot(np.cross(points[1] - points[0], points[2] - points[0]), normals.mean(0)) < 0:
            points, normals, uv = points[::-1], normals[::-1], uv[::-1]
        for p, n, tex in zip(points, normals, uv):
            # Keep a one-pixel gutter to avoid neighboring atlas tiles bleeding.
            u = (mat % 8 * 64 + 1.5 + tex[0] * 61) / 512
            v = (mat // 8 * 64 + 1.5 + tex[1] * 61) / 512
            self.vertices.extend([*p, u, v, *normalize(n)])

    def quad(self, points, normals, mat, uv=None):
        normals = np.asarray(normals)
        if normals.ndim == 1:
            normals = np.tile(normals, (4, 1))
        uv = np.asarray(uv if uv is not None else [(0, 0), (1, 0), (1, 1), (0, 1)])
        for inds in ([0, 1, 2], [0, 2, 3]):
            self.tri(np.asarray(points)[inds], normals[inds], mat, uv[inds])

    def rounded(self, center, size, radius, mat, steps=3):
        """Six rounded faces, projecting a cube onto a Minkowski rounded box."""
        c = np.array(center, dtype=float)
        h = np.array(size, dtype=float) / 2
        r = min(radius, min(h) * .98)
        inner = h - r
        for axis in range(3):
            a, b = [i for i in range(3) if i != axis]
            def coords(k):
                return np.unique(np.r_[np.linspace(-h[k], -inner[k], steps + 1), inner[k],
                                       np.linspace(inner[k], h[k], steps + 1)])
            xs, ys = coords(a), coords(b)
            for sign in (-1, 1):
                def vert(x, y):
                    p = np.zeros(3); p[axis] = sign * h[axis]; p[a] = x; p[b] = y
                    nearest = np.clip(p, -inner, inner)
                    n = normalize(p - nearest)
                    return c + nearest + n * r, n
                for i in range(len(xs) - 1):
                    for j in range(len(ys) - 1):
                        xy = [(xs[i], ys[j]), (xs[i+1], ys[j]), (xs[i+1], ys[j+1]), (xs[i], ys[j+1])]
                        v = [vert(x, y) for x, y in xy]
                        uv = [((x / h[a] + 1) / 2, 1 - (y / h[b] + 1) / 2) for x, y in xy]
                        self.quad([p for p, n in v], [n for p, n in v], mat, uv)

    def lathe(self, center, profile, mat, segments=48, scale_z=1, angle_start=0, angle_end=TAU):
        c = np.asarray(center)
        y0, y1 = profile[0][1], profile[-1][1]
        for j in range(len(profile) - 1):
            r0, yy0 = profile[j]; r1, yy1 = profile[j+1]
            def vert(r, y, theta, ring):
                prev = profile[max(0, ring-1)]; nxt = profile[min(len(profile)-1, ring+1)]
                n = normalize([math.cos(theta), -(nxt[0]-prev[0]) / max(1e-6, nxt[1]-prev[1]), math.sin(theta)/scale_z])
                if r == 0: n = np.array([0, -1 if ring == 0 else 1, 0])
                return c + [r * math.cos(theta), y, r * scale_z * math.sin(theta)], n
            for i in range(segments):
                a = angle_start + (angle_end-angle_start)*i/segments
                b = angle_start + (angle_end-angle_start)*(i+1)/segments
                vv = [vert(r0, yy0, a, j), vert(r0, yy0, b, j), vert(r1, yy1, b, j+1), vert(r1, yy1, a, j+1)]
                u0, u1 = i/segments, (i+1)/segments
                v0, v1 = 1-(yy0-y0)/max(.01,y1-y0), 1-(yy1-y0)/max(.01,y1-y0)
                self.quad([p for p,n in vv], [n for p,n in vv], mat, [(u0,v0),(u1,v0),(u1,v1),(u0,v1)])

    def pillow(self, center, size, mat, exponent=.5, rows=18, segments=40, wrinkle=.015):
        """Closed smooth superellipsoid, with a cloth-like uneven surface."""
        c, half = np.asarray(center), np.asarray(size)/2
        def signed(x, p): return math.copysign(abs(x)**p, x)
        def position(t, p):
            cp = max(0, math.cos(p))
            xx, yy, zz = signed(math.cos(t),exponent), signed(math.sin(p),exponent), signed(math.sin(t),exponent)
            w = 1 + wrinkle*math.sin(t*9+p*3)*math.cos(p*7)
            return c + half*np.array([cp**exponent*xx*w, yy, cp**exponent*zz*w])
        def vertex(t,p):
            point = position(t,p)
            dt = position(t+.0001,p)-position(t-.0001,p)
            dp = position(t,min(math.pi/2,p+.0001))-position(t,max(-math.pi/2,p-.0001))
            n = normalize(np.cross(dp,dt))
            if np.dot(n, point-c)<0:n=-n
            if np.linalg.norm(n)<.1:n=normalize(point-c)
            return point,n
        for j in range(rows):
            p0=-math.pi/2+math.pi*j/rows;p1=-math.pi/2+math.pi*(j+1)/rows
            for i in range(segments):
                t0=TAU*i/segments;t1=TAU*(i+1)/segments
                v=[vertex(t0,p0),vertex(t1,p0),vertex(t1,p1),vertex(t0,p1)]
                self.quad([p for p,n in v],[n for p,n in v],mat,[(i/segments,1-j/rows),((i+1)/segments,1-j/rows),((i+1)/segments,1-(j+1)/rows),(i/segments,1-(j+1)/rows)])

    def tube(self, path, radius, mat, segments=10, closed=False):
        path=np.array(path,dtype=float)
        if closed:path=np.concatenate([path,path[:1]])
        rings=[]
        for j,p in enumerate(path):
            tangent=normalize(path[min(j+1,len(path)-1)]-path[max(0,j-1)])
            if closed and (j==0 or j==len(path)-1):tangent=normalize(path[1]-path[-2])
            up=np.array([0,1,0]) if abs(tangent[1])<.85 else np.array([0,0,1])
            side=normalize(np.cross(tangent,up));up=normalize(np.cross(side,tangent))
            rings.append([(p+radius*(math.cos(TAU*i/segments)*side+math.sin(TAU*i/segments)*up),
                           math.cos(TAU*i/segments)*side+math.sin(TAU*i/segments)*up) for i in range(segments)])
        for j in range(len(path)-1):
            for i in range(segments):
                k=(i+1)%segments;v=[rings[j][i],rings[j][k],rings[j+1][k],rings[j+1][i]]
                self.quad([p for p,n in v],[n for p,n in v],mat)

    def ring(self, center, radius, thickness, mat, axis="z", segments=36):
        c=np.array(center);axes={"x":(1,2),"y":(0,2),"z":(0,1)}[axis];path=[]
        for i in range(segments):
            p=c.copy().astype(float);p[axes[0]]+=radius*math.cos(TAU*i/segments);p[axes[1]]+=radius*math.sin(TAU*i/segments);path.append(p)
        self.tube(path,thickness,mat,8,True)

    def strap(self, path, width, depth, mat):
        """Curved rectangular ribbon with rounded outside edges."""
        path=np.array(path,dtype=float)
        for j in range(len(path)-1):
            for side in (-1,1):
                self.quad([path[j]+[-width/2,0,side*depth/2],path[j]+[width/2,0,side*depth/2],
                           path[j+1]+[width/2,0,side*depth/2],path[j+1]+[-width/2,0,side*depth/2]],
                          normalize(np.cross(path[j+1]-path[j],[1,0,0]))*side,mat)
            for side in (-1,1):
                self.quad([path[j]+[side*width/2,0,-depth/2],path[j]+[side*width/2,0,depth/2],
                           path[j+1]+[side*width/2,0,depth/2],path[j+1]+[side*width/2,0,-depth/2]],[side,0,0],mat)

    def patch(self, center, size, mat):
        # One flat printable panel floating very slightly above its curved host.
        x,y,z=center;w,h=size
        self.quad([[x-w/2,y-h/2,z],[x+w/2,y-h/2,z],[x+w/2,y+h/2,z],[x-w/2,y+h/2,z]],[0,0,1],mat,
                  [(0,1),(1,1),(1,0),(0,0)])

    def save(self, texture):
        a=np.asarray(self.vertices,dtype=float).reshape(-1,8)
        assert np.all(np.isfinite(a)),self.name
        assert len(a)%3==0
        lengths=np.linalg.norm(a[:,5:],axis=1)
        assert np.all(abs(lengths-1)<1e-6),(self.name,lengths.min(),lengths.max())
        assert np.max(a[:,:3])<20 and np.min(a[:,:3])>-4,(self.name,a[:,:3].min(0),a[:,:3].max(0))
        target=ASSETS/"meshes/item"/(self.name+".json");target.parent.mkdir(parents=True,exist_ok=True)
        tex=ASSETS/"textures/item/detail"/(self.name+".png");tex.parent.mkdir(parents=True,exist_ok=True);texture.save(tex)
        data={"format_version":1,"vertex_format":"x,y,z,u,v,nx,ny,nz","texture":f"tacz_pubg:textures/item/detail/{self.name}.png",
              "vertices":[round(float(x),6) for x in self.vertices]}
        target.write_text(json.dumps(data,separators=(",",":"))+"\n",encoding="utf-8")
        return {"id":self.name,"model":str(target.relative_to(ROOT)).replace("\\","/"),"texture":str(tex.relative_to(ROOT)).replace("\\","/"),
                "triangles":len(a)//3,"bounds":[a[:,:3].min(0).tolist(),a[:,:3].max(0).tolist()],
                "model_sha256":hashlib.sha256(target.read_bytes()).hexdigest(),"texture_sha256":hashlib.sha256(tex.read_bytes()).hexdigest()}


def bezier(points, steps=20):
    a,b,c,d=map(np.array,points)
    return [(1-t)**3*a+3*(1-t)**2*t*b+3*(1-t)*t*t*c+t**3*d for t in np.linspace(0,1,steps)]


def cylinder(m, center, radius, height, mat, segments=40):
    bevel=min(.14,radius*.15,height*.12)
    m.lathe(center,[(0,-height/2),(radius-bevel,-height/2),(radius,-height/2+bevel),
                    (radius,height/2-bevel),(radius-bevel,height/2),(0,height/2)],mat,segments)


def handle(m, x,y,z, width, mat):
    m.tube(bezier([(x-width/2,y,z),(x-width/2,y+1.25,z),(x+width/2,y+1.25,z),(x+width/2,y,z)]),.18,mat)


def pouches(m, xs, y,z,w,h,mat):
    for x in xs:
        m.pillow((x,y,z),(w,h,.9),mat,exponent=.36,rows=10,segments=24,wrinkle=.008)
        m.rounded((x,y+h*.32,z+.42),(w*.94,.5,.12),.06,mat,2)
        m.rounded((x,y-h*.25,z+.49),(.28,.6,.08),.03,2,2)


def medical_models():
    result=[]
    m=Mesh("bandage")
    m.pillow((8,8,8),(7.1,9.2,3.3),14,.32)
    for y in (3.45,12.55):
        m.rounded((8,y,8),(7.6,.38,3.0),.15,16)
        for x in np.linspace(4.6,11.4,18):m.rounded((x,y,9.55),(.07,.25,.045),.02,0,1)
    m.patch((8,8.2,9.69),(5.8,6.7),22)
    result.append(m)
    m=Mesh("first_aid_kit")
    m.pillow((8,7.8,8),(9.8,8.7,4.6),17,.38)
    m.tube([(3.25,5,9.95),(3.3,10,9.95),(4,11.7,9.95),(12,11.7,9.95),(12.7,10,9.95),(12.75,5,9.95)],.08,8)
    handle(m,8,12,7.5,3.8,8)
    m.patch((8,8.2,10.39),(5.5,5.4),21)
    for x in (4.2,11.8):m.rounded((x,6.3,10.35),(.75,2,.18),.08,8)
    m.ring((12.4,5,10.2),.32,.075,3)
    result.append(m)
    m=Mesh("med_kit")
    # Open organizer with separate curved shell, side gussets, pockets and tools.
    m.pillow((8,7,6.6),(10.1,11,1.2),28,.4)
    m.rounded((8,2.1,8),(9.7,1.3,6),.45,0)
    m.pillow((3.5,6.6,8.3),(1.25,9.5,4.4),28,.45)
    m.pillow((12.5,6.6,8.3),(1.25,9.5,4.4),28,.45)
    m.pillow((8,4,10.2),(8.7,3.8,1.1),14,.38)
    handle(m,8,12.4,6.5,3,2)
    for x in (5,7,9,11):
        cylinder(m,(x,8,8.1),.6,4.6,4,24)
        cylinder(m,(x,10.5,8.1),.56,.6,6 if x in (5,9) else 7,24)
        m.rounded((x,6.8,9.2),(1.6,3,.5),.18,0)
    m.patch((8,4.1,10.77),(3.2,2.5),21)
    m.strap(bezier([(3.2,4,6),(2,13,5),(14,13,5),(12.8,4,6)]),.7,.2,2)
    result.append(m)
    m=Mesh("energy_drink")
    m.lathe((8,0,8),[(0,2.3),(2.1,2.3),(2.35,2.5),(2.46,3.1),(2.46,11.9),(2.32,12.5),(2.05,12.8),(0,12.8)],20,64)
    for y in (2.5,12.8):m.ring((8,y,8),2.12,.15,3,"y",48)
    cylinder(m,(8,12.85,8),2.08,.12,3,56)
    m.rounded((8,12.98,8.55),(1.3,.06,1.4),.025,11)
    m.ring((8,13.06,7.7),.6,.14,3,"y",32)
    cylinder(m,(8,13.04,8.1),.15,.1,10,20)
    result.append(m)
    m=Mesh("painkiller")
    m.lathe((8,0,8),[(0,2.5),(2.25,2.5),(2.6,2.9),(2.65,3.5),(2.65,9.4),(2.5,10.2),(1.95,11.2),(1.9,11.8),(0,11.8)],38,56)
    m.lathe((8,0,8),[(2.67,4.1),(2.67,8.9)],23,56)
    cylinder(m,(8,12,8),2.35,1.7,3,56)
    for i in range(44):
        a=TAU*i/44
        m.tube([(8+2.35*math.cos(a),11.35,8+2.35*math.sin(a)),(8+2.35*math.cos(a),12.65,8+2.35*math.sin(a))],.035,10,5)
    result.append(m)
    m=Mesh("adrenaline_syringe")
    cylinder(m,(8,6.6,8),.92,7.4,33,48)
    for y in (3.2,10.2):cylinder(m,(8,y,8),1.0,.35,3,40)
    cylinder(m,(8,11.5,8),.24,2.5,3,28)
    cylinder(m,(8,12.85,8),1.1,.3,3,40)
    for x in (5.9,10.1):
        m.ring((x,10.1,8),1.0,.19,3,"z",36)
        m.rounded(((x+8)/2,10.1,8),(1.2,.25,.36),.09,3)
    m.lathe((8,0,8),[(.1,.7),(.13,2.25),(.5,2.3),(.52,3.0)],7,32)
    m.tube([(8,-.2,8),(8,.8,8)],.055,3,8)
    result.append(m)
    return result


def backpack(tier):
    m=Mesh(f"backpack_{tier}")
    mat={1:1,2:18,3:32}[tier]; trim={1:8,2:8,3:2}[tier]
    width={1:8,2:9,3:10}[tier]; height={1:10,2:11,3:11.5}[tier]; depth={1:4.8,2:5.7,3:6.1}[tier]
    m.pillow((8,7.3,7.5),(width,height,depth),mat,.48,24,56,.009)
    # Padded back panel and shoulder straps curve continuously around the body.
    m.pillow((8,7.3,5.2),(width*.78,height*.86,.7),2,.4,14,32,.005)
    for x in (5.8,10.2):
        m.strap(bezier([(x,11.8,5.4),(x,14,1.5),(x,2,1.1),(x,2.7,5.7)],28),.95,.27,trim)
        m.rounded((x,4.2,3.1),(1.05,.9,.35),.12,2)
        m.tube(bezier([(x-.34,11.9,5.3),(x-.34,13.5,1.8),(x-.34,2.2,1.5),(x-.34,3,5.6)]),.025,9,5)
    handle(m,8,12.7,6.6,2.5,trim)
    m.pillow((8,5.6,7.5+depth/2),(width*.76,4.6,1.65),mat,.35,16,40,.01)
    if tier==1:
        m.tube([(4.9,7.2,10.65),(5.5,7.8,10.9),(10.5,7.8,10.9),(11.1,7.2,10.65)],.08,8)
        m.ring((10.9,7.3,10.9),.25,.06,3)
        m.patch((8,9.5,10.0),(1.8,.7),8)
    else:
        m.pillow((8,10.8,9),(width*.88,3.7,3.4),mat,.46,16,40)
        for x in (5.7,10.3):
            m.strap(bezier([(x,12.2,10),(x,10.5,11.1),(x,6.9,11.3),(x,3.6,11.3)]),.5,.12,trim)
            m.rounded((x,7.8,11.27),(.9,.65,.26),.1,2)
    for x in (8-width/2-.3,8+width/2+.3):
        m.pillow((x,5.8,7.8),(1.55,5,3.5),mat,.42,14,32)
        m.rounded((x,7.3,9.3),(1.4,.65,.2),.1,trim)
    if tier==3:
        for y in (4.3,5.6,8.8):
            for x in (5.3,6.7,8.1,9.5,10.9):
                m.rounded((x,y,11.57),(1.15,.38,.15),.06,30,2)
        for x in (6.2,9.7):
            m.tube([(x,11.8,11),(x,9.7,11.5)],.08,9)
    return m


def grenade_top(m, y, mat=2):
    cylinder(m,(8,y,8),.78,1.05,mat,32)
    m.rounded((8,y+.65,8),(2.2,.35,1.0),.12,10)
    m.strap([(8.85,y+.7,8),(10.1,y+.35,8),(10.5,y-1.5,8),(10.5,y-3.1,8)],.62,.23,10)
    m.tube([(7.8,y+.45,8.4),(6.65,y+.5,8.4),(6.1,y+.05,8.4)],.09,3)
    m.ring((6,y-.35,8.4),.8,.11,3,"z",32)


def throwables():
    result=[]
    m=Mesh("frag_grenade")
    m.lathe((8,0,8),[(0,2.7),(1.2,2.8),(2.1,3.3),(2.65,4.7),(2.8,6.2),(2.5,8.3),(1.65,10),(0,10.4)],0,64)
    # Cast segmentation follows the curved body; individual cells retain gaps.
    for j,(y,r) in enumerate([(4,2.5),(5.4,2.8),(6.8,2.75),(8.2,2.4)]):
        m.ring((8,y,8),r,.07,2,"y",48)
    for i in range(12):
        a=TAU*i/12
        m.tube([(8+r*math.cos(a),y,8+r*math.sin(a)) for y,r in [(3.3,2.0),(4,2.5),(5.4,2.8),(6.8,2.75),(8.2,2.4),(9.3,1.9)]],.055,2,6)
    grenade_top(m,10.5);result.append(m)
    m=Mesh("stun_grenade")
    cylinder(m,(8,6.7,8),1.8,8.5,10,48)
    for y in (2.8,4.1,9.1,10.5):m.ring((8,y,8),1.84,.18,2,"y",40)
    for i in range(10):
        a=TAU*i/10
        # Long vent tubes conform to the cylinder, preserving a rounded silhouette.
        m.tube([(8+1.82*math.cos(a),4.7,8+1.82*math.sin(a)),(8+1.82*math.cos(a),8.5,8+1.82*math.sin(a))],.16,11,8)
    grenade_top(m,11.3);result.append(m)
    m=Mesh("smoke_grenade")
    m.lathe((8,0,8),[(0,2.6),(2.2,2.6),(2.35,2.8),(2.35,10.6),(2.15,10.9),(0,10.9)],0,56)
    m.lathe((8,0,8),[(2.365,4.3),(2.365,8.9)],25,56)
    for y in (2.9,10.7):m.ring((8,y,8),2.32,.09,10,"y")
    grenade_top(m,11.5);result.append(m)
    m=Mesh("molotov")
    m.lathe((8,0,8),[(0,1.5),(1.7,1.5),(1.92,1.9),(1.95,7),(1.7,8),(1.05,9.2),(.65,10),(.65,12.4),(.7,12.55),(0,12.55)],27,64)
    m.lathe((8,0,8),[(1.97,3),(1.97,6.7)],4,64)
    m.patch((8,4.8,9.98),(2.1,2.5),26)
    m.strap(bezier([(8,12,8),(9,14.5,8),(10.5,13.6,8),(11,12.7,8)],20),.65,.12,4)
    m.strap(bezier([(8,12,8.1),(7,13.9,8.8),(6,12.2,9),(5.5,11.9,9.4)],20),.4,.08,9)
    result.append(m)
    m=Mesh("blue_zone_grenade")
    m.lathe((8,0,8),[(0,2.5),(1.9,2.5),(2.45,3.2),(2.45,9.5),(1.9,10.2),(0,10.2)],24,56)
    for y in (3.1,9.5):m.ring((8,y,8),2.48,.23,2,"y",40)
    for i in range(4):
        a=TAU*i/4
        m.tube([(8+2.52*math.cos(a),3.3,8+2.52*math.sin(a)),(8+2.52*math.cos(a),9.1,8+2.52*math.sin(a))],.16,3)
    grenade_top(m,10.8);result.append(m)
    m=Mesh("sticky_bomb")
    m.pillow((8,7,8),(7.5,8.2,3.4),9,.32,18,40,.008)
    for x in (5.5,10.5):m.rounded((x,7,9.75),(.65,8.2,.22),.09,2)
    m.rounded((8,8.2,10.1),(3.7,4.1,.8),.32,2)
    m.patch((8,9,10.52),(2.5,1.25),26)
    m.tube(bezier([(6.5,10,10.4),(5,13,10),(11,13,10.3),(9,7,10.5)],22),.105,6)
    m.tube(bezier([(7.1,10,10.5),(6,11.7,10),(10.6,11.7,10.5),(9.3,6.6,10.5)],22),.08,3)
    cylinder(m,(8,11.5,8),.38,2.5,10,24)
    result.append(m)
    m=Mesh("c4")
    for x in (5.1,8,10.9):m.pillow((x,7.6,7.5),(2.8,9.7,3.5),9,.3,18,32,.01)
    for y in (4.4,10.8):m.rounded((8,y,9.3),(8.8,.65,.25),.09,2)
    m.rounded((8,7.9,10),(6.8,5,.9),.3,0)
    m.rounded((8,8.8,10.5),(4.8,1.7,.13),.06,11)
    m.patch((8,8.8,10.58),(4.3,1.3),36)
    for x in (6.3,7.4,8.5,9.6):
        for y in (6.3,7.15):m.rounded((x,y,10.6),(.75,.5,.25),.1,3,2)
    m.tube(bezier([(5,6,10),(2.7,13.6,10),(13,14.8,9),(11,9,10.5)],26),.11,6)
    m.tube(bezier([(5.5,6,10.2),(3.3,13,10),(12,13.8,9),(10.4,9,10.5)],26),.09,11)
    result.append(m)
    return result


def helmet(tier):
    m=Mesh(f"helmet_{tier}"); mat={1:19,2:17,3:2}[tier]
    # Open helmet shell. The front rises over the face; sides extend to ears.
    def edge(theta):
        front=max(0,math.sin(theta))
        return math.pi*.56-front**3*math.pi*.23
    rows,cols=20,64
    for j in range(rows):
        for i in range(cols):
            ts=[TAU*i/cols,TAU*(i+1)/cols];v=[]
            for theta,frac in [(ts[0],j/rows),(ts[1],j/rows),(ts[1],(j+1)/rows),(ts[0],(j+1)/rows)]:
                phi=edge(theta)*frac
                p=np.array([8+4.85*math.sin(phi)*math.cos(theta),7.1+5.4*math.cos(phi),8+5.0*math.sin(phi)*math.sin(theta)])
                n=normalize((p-np.array([8,7.1,8]))/np.array([4.85,5.4,5]))
                v.append((p,n))
            m.quad([p for p,n in v],[n for p,n in v],mat,[(i/cols,j/rows),((i+1)/cols,j/rows),((i+1)/cols,(j+1)/rows),(i/cols,(j+1)/rows)])
    rim=[]
    for i in range(cols):
        t=TAU*i/cols;p=edge(t);rim.append([8+4.85*math.sin(p)*math.cos(t),7.1+5.4*math.cos(p),8+5*math.sin(p)*math.sin(t)])
    m.tube(rim,.15,2,10,True)
    # Inner liner is a curved band below the shell, visible through its opening.
    for i in range(cols):
        t0,t1=TAU*i/cols,TAU*(i+1)/cols
        ps=[]
        for t,offset in [(t0,0),(t1,0),(t1,.7),(t0,.7)]:
            p=edge(t);ps.append([8+4.57*math.sin(p)*math.cos(t),7.1+5.15*math.cos(p)+offset,8+4.72*math.sin(p)*math.sin(t)])
        m.quad(ps,normalize([8,8,8]-np.mean(ps,axis=0)),11)
    if tier==2:
        # Projecting curved cloth-covered brim.
        for i in range(30):
            a=math.pi*.1+math.pi*.8*i/30;b=math.pi*.1+math.pi*.8*(i+1)/30
            pa,pb=edge(a),edge(b)
            m.quad([[8+4.7*math.sin(pa)*math.cos(a),7.1+5.4*math.cos(pa),8+5*math.sin(pa)*math.sin(a)],
                    [8+4.7*math.sin(pb)*math.cos(b),7.1+5.4*math.cos(pb),8+5*math.sin(pb)*math.sin(b)],
                    [8+5.2*math.sin(pb)*math.cos(b),6.9+5.4*math.cos(pb),8+6*math.sin(pb)*math.sin(b)],
                    [8+5.2*math.sin(pa)*math.cos(a),6.9+5.4*math.cos(pa),8+6*math.sin(pa)*math.sin(a)]],[0,1,0],17)
        for x in (3.9,12.1):
            m.strap(bezier([(x,6,9),(x,3.8,9),(8,2.9,10),(8,3.2,10)]),.45,.1,2)
    elif tier==3:
        # Thick curved face shield with actual open rectangular eye slit.
        for y0,y1 in [(4.2,6.7),(7.65,9.35)]:
            for i in range(32):
                a=math.pi*.19+math.pi*.62*i/32;b=math.pi*.19+math.pi*.62*(i+1)/32
                m.quad([[8+5.1*math.cos(a),y0,8+5.3*math.sin(a)],[8+5.1*math.cos(b),y0,8+5.3*math.sin(b)],
                        [8+5.1*math.cos(b),y1,8+5.3*math.sin(b)],[8+5.1*math.cos(a),y1,8+5.3*math.sin(a)]],
                       [[math.cos(a),0,math.sin(a)],[math.cos(b),0,math.sin(b)],[math.cos(b),0,math.sin(b)],[math.cos(a),0,math.sin(a)]],2)
        for x in (4.0,12.0):
            m.rounded((x,6.8,11.05),(.65,4.6,.8),.28,2)
            for y in (5.4,8.1):
                m.pillow((x,y,11.55),(.28,.28,.18),10,1,8,12,0)
        m.tube(bezier([(3.8,5.5,9),(4,1.6,12),(12,1.6,12),(12.2,5.5,9)]),.23,11)
    else:
        # Leather ear flaps and a connected chin strap; no detached fasteners.
        for x in (3.55,12.45):
            m.pillow((x,5.7,7.8),(.55,2.7,2.6),2,.55,12,24,.005)
            m.strap(bezier([(x,5,8.8),(x,3.3,9.5),(8,2.7,10),(8,3,10)]),.42,.12,11)
        m.ring((8,3,10.1),.27,.07,3)
    return m


def vest(tier):
    m=Mesh(f"vest_{tier}"); mat={1:32,2:2,3:29}[tier]
    # Torso curved plates are separated from the shoulder bridges and neck hole.
    m.pillow((8,6.4,9.4),(9.8,9.0,2.0),mat,.48,24,48,.005)
    m.pillow((8,6.7,5.8),(9.8,9.4,1.5),mat,.46,24,48,.005)
    for x in (4.7,11.3):
        m.strap(bezier([(x,9.2,10),(x,15,10.1),(x,15,5),(x,9.5,5.3)],24),1.45,.4,mat)
        m.rounded((x,11.6,9.7),(1.6,.7,.45),.12,10)
    for x in (3.45,12.55):
        m.pillow((x,5.6,7.65),(1.3,4.3,4.5),mat,.44,14,32,.008)
    if tier==1:
        for y in (7.5,8.8):
            for x in (4.5,6.25,8,9.75,11.5):m.rounded((x,y,10.46),(1.45,.55,.12),.08,30,2)
        pouches(m,[5.25,8,10.75],4.6,10.5,2.25,4.4,mat)
        m.rounded((8,3.2,11.1),(1.1,.7,.22),.1,2)
    elif tier==2:
        m.tube([(8,2.1,10.45),(8,10.65,10.45)],.12,10)
        pouches(m,[5.4,10.6],5.1,10.5,2.8,4.8,2)
        # Quilted seams form diamond panels on the police vest.
        for side in (-1,1):
            for off in (0,.8,1.6):
                x=8+side*1.8
                m.tube([(x-side*.9,8.8+off*.3,10.4),(x+side*.9,7.3+off*.3,10.4)],.035,10,5)
                m.tube([(x-side*.9,7.3+off*.3,10.4),(x+side*.9,8.8+off*.3,10.4)],.035,10,5)
    else:
        m.rounded((8,8.4,10.5),(7.2,3.8,.5),.35,1)
        m.patch((8,8.8,10.76),(3.5,1.4),37)
        pouches(m,[4.8,7,9.2,11.4],4.5,10.75,1.8,4.4,29)
        for y in (6.4,7.4):
            for x in (4.8,6.4,8,9.6,11.2):m.rounded((x,y,11.4),(1.35,.48,.15),.06,8,2)
        for x in (5.6,10.4):m.rounded((x,6.7,11.55),(.7,.85,.18),.1,2)
    for y in (3,5.2):m.rounded((8,y,5.05),(8.6,.55,.15),.07,mat,2)
    return m


def main():
    texture=atlas()
    models=medical_models()+[backpack(i) for i in (1,2,3)]+throwables()+[helmet(i) for i in (1,2,3)]+[vest(i) for i in (1,2,3)]
    assert len(models)==22 and len({m.name for m in models})==22
    entries=[]
    for m in models:
        entry=m.save(texture);entries.append(entry)
        print(f'{m.name}: {entry["triangles"]} triangles',flush=True)
    manifest={"authoring":"Original procedural mesh geometry and material atlas, inspired by PUBG item reference images. No source game meshes or UV textures copied.",
              "style_zh":"萌趣世界","style_en":"Playful World",
              "generator":"scripts/build-pubg-detailed-models.py",
              "reference_images":"assets-workbench/pubgmc-reference/official/Assets/Item",
              "reference_repository":"https://github.com/pubg/api-assets/tree/32b13b51128b8d8909ae5e77f3b833e01230b24d/Assets/Item",
              "coordinate_system":"Item units 0..16, Y up, Z front. Baked item model display transforms apply.",
              "items":entries}
    (OUT/"detailed-models.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(f"Authored {len(models)} curved models, {sum(x['triangles'] for x in entries):,} triangles total.")


if __name__=="__main__":
    main()
