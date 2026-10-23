"""Create editable magazine assets and render the exact exported Bedrock geometry.

No game, gunpack, runtime registry, world or source archive is changed. Run with
the bundled Python (Pillow + NumPy); images are a software 3D rasterization, not
generated artwork. All nine silhouettes follow archived official icons; the
six pull loops preserve/adapt the local cbrg author's nine-cube bevel topology.
"""
from __future__ import annotations

import base64
import copy
import hashlib
import io
import json
import math
import pathlib
import uuid
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "assets-workbench/pubg-magazines"
DOC = ROOT / "docs/attachments/magazines"
SOURCE = ROOT / "neoforge-1.21.1/run/tacz/classic_battleroyale_gun+1.21.1.zip"
NS = "tacz_pubg_workbench"
REF = OUT / "reference/pubg-api"
REF_FAMILY = {"rifle":"Large", "smg":"Medium", "sniper":"SniperRifle"}
REF_VARIANT = {"quickdraw":"QuickDraw", "extended":"Extended", "extended_quickdraw":"ExtendedQuickDraw"}
VARIANTS = ["quickdraw", "extended", "extended_quickdraw"]
V_NAMES = {"quickdraw": "快速", "extended": "扩容", "extended_quickdraw": "快速扩容"}
MAG_LEVEL = {"quickdraw":1,"extended":2,"extended_quickdraw":3}
F_NAMES = {"rifle": "步枪 / 机枪 / 精确射手", "smg": "手枪 / 冲锋枪", "sniper": "狙击枪 / 精确射手"}
FACE_BITS = {
    "north": [(1,1,0),(0,1,0),(0,0,0),(1,0,0)],
    "south": [(0,1,1),(1,1,1),(1,0,1),(0,0,1)],
    "east": [(1,1,1),(1,1,0),(1,0,0),(1,0,1)],
    "west": [(0,1,0),(0,1,1),(0,0,1),(0,0,0)],
    "up": [(1,1,1),(0,1,1),(0,1,0),(1,1,0)],
    "down": [(1,0,0),(0,0,0),(0,0,1),(1,0,1)],
}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def stable_uuid(label):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "tacz-pubg-magazine-workbench/" + label))


def rotate(angles):
    # Bedrock is left-handed around X/Z. TaCZ's Y-down model space is flipped
    # back to this Y-up workbench; using raw right-handed angles breaks the
    # joints of cbrg's curved magazines (segments then separate at the pivots).
    angles = [-angles[0], angles[1], -angles[2]]
    result = np.eye(4)
    for axis in [2, 1, 0]:
        angle = math.radians(angles[axis])
        a, b = [(1,2),(2,0),(0,1)][axis]
        r = np.eye(4)
        r[a,a] = r[b,b] = math.cos(angle)
        r[a,b], r[b,a] = -math.sin(angle), math.sin(angle)
        result = result @ r
    return result


def translate(v):
    m = np.eye(4)
    m[:3,3] = v
    return m


def around(pivot, angles):
    return translate(pivot) @ rotate(angles) @ translate(-np.array(pivot))


def mesh_geometry(geometry):
    """Flatten bone/cube pivot transforms without changing the source cube UVs."""
    bones = {b["name"]: b for b in geometry["minecraft:geometry"][0]["bones"]}
    matrices = {}
    def bone_matrix(name):
        if name not in matrices:
            b = bones[name]
            m = around(b.get("pivot",[0,0,0]), b.get("rotation",[0,0,0]))
            matrices[name] = bone_matrix(b["parent"]) @ m if b.get("parent") else m
        return matrices[name]
    faces = []
    for name, bone in bones.items():
        for cube in bone.get("cubes",[]):
            origin = np.array(cube["origin"], dtype=float)
            size = np.array(cube["size"], dtype=float)
            inflation = float(cube.get("inflate", 0))
            matrix = bone_matrix(name) @ around(cube.get("pivot",[0,0,0]), cube.get("rotation",[0,0,0]))
            center = (matrix @ np.r_[origin+size*.5,1])[:3]
            for face, bits in FACE_BITS.items():
                # Explicit missing faces are deliberately absent in TaCZ.
                if face not in cube.get("uv",{}):
                    continue
                points = np.array([(matrix @ np.r_[origin-inflation+np.array(bit)*(size+2*inflation),1])[:3] for bit in bits])
                normal = np.cross(points[1]-points[0], points[2]-points[0])
                if np.linalg.norm(normal) < 1e-9:
                    continue
                normal /= np.linalg.norm(normal)
                if np.dot(normal, points.mean(axis=0)-center)<0:
                    normal *= -1
                info = cube["uv"][face]
                u,v = info["uv"]; w,h = info["uv_size"]
                uv = np.array([[u,v],[u+w,v],[u+w,v+h],[u,v+h]], dtype=float)
                faces.append((points, uv, normal))
    return faces


def weathered_tile(color, seed, metal=True):
    """Original procedural paint: grain, oxidized scuffs, and fine bright wear."""
    rng=np.random.default_rng(seed)
    h=w=128
    noise=rng.normal(0,3.0,(h,w,1))
    coarse=np.array(Image.fromarray(np.uint8(rng.integers(20,235,(16,16)))).resize((w,h),Image.Resampling.BICUBIC))
    noise += (coarse[:,:,None].astype(float)-128)*.045
    pixels=np.clip(np.array(color)[None,None,:]+noise,0,255).astype(np.uint8)
    tile=Image.fromarray(pixels).convert("RGBA");d=ImageDraw.Draw(tile)
    if metal:
        for _ in range(420):
            x,y=map(int,rng.integers(2,126,2));v=int(rng.integers(6,24))
            c=tuple(min(255,int(a)+v) for a in color)
            d.line((x,y,x+int(rng.integers(0,3)),y+int(rng.integers(0,3))),fill=c+(255,),width=1)
        for _ in range(23):
            x,y=map(int,rng.integers(5,121,2));v=int(rng.integers(12,29))
            c=tuple(min(255,int(a)+v) for a in color)
            d.line((x,y,x+int(rng.integers(-6,7)),y+int(rng.integers(4,22))),fill=c+(255,),width=1)
        # A raised rim reads as worn metal at small render sizes.
        edge=tuple(min(255,c+25) for c in color)+(255,)
        d.line((7,7,120,7),fill=edge,width=1);d.line((7,7,7,120),fill=edge,width=1)
    return tile


def atlas(family,variant):
    blue=variant=="quickdraw"
    body=((48,76,89) if family!="sniper" else (61,83,94)) if blue else ((45,49,50) if family=="smg" else (58,64,68) if family=="sniper" else (39,43,44))
    colors=[body, tuple(max(8,c-15) for c in body),(20,27,30),
        tuple(min(255,c+25) for c in body),(18,23,25),(90,100,101),
        (137,119,63),(158,116,84),(36,41,44),(54,65,70),(87,94,92),
        (27,32,35),(64,78,83),(77,87,89),(131,136,130),(30,40,44)]
    image=Image.new("RGBA",(512,512))
    for i,color in enumerate(colors):
        image.paste(weathered_tile(color,762556+i,metal=i!=4),((i%4)*128,(i//4)*128))
    return image


def rifle_texture(original,variant):
    """Keep cbrg UV layout/alpha; derive worn blue steel or charcoal materials."""
    original=original.convert("RGBA").resize((256,256),Image.Resampling.NEAREST)
    arr=np.array(original);rgb=arr[:,:,:3].astype(float)
    lum=rgb.mean(2);rng=np.random.default_rng(260101+VARIANTS.index(variant))
    if variant=="quickdraw":
        # The source body is blue-grey but far too flat/dark against the official
        # QuickDraw_Large reference. Preserve black recesses and existing alpha.
        fac=np.clip(lum/60,.16,1.8)
        rgb=np.stack([fac*44,fac*73,fac*91],axis=-1)
        rgb[lum<22]*=.67
    else:
        rgb=rgb*.93+np.array([2,3,4])
    noise=rng.normal(0,3.2,(256,256,1));rgb=np.clip(rgb+noise,0,255)
    arr[:,:,:3]=rgb.astype(np.uint8);image=Image.fromarray(arr)
    d=ImageDraw.Draw(image)
    for _ in range(650):
        x,y=map(int,rng.integers(1,255,2))
        if arr[y,x,3] and lum[y,x]>20:
            c=tuple(min(255,int(a)+int(rng.integers(8,28))) for a in arr[y,x,:3])+(255,)
            d.point((x,y),fill=c)
    return image


def cube(name, origin, size, swatch=0, rotation=None, pivot=None):
    u=(swatch%4)*128+8; v=(swatch//4)*128+8
    result={"origin":origin,"size":size,
            "uv":{f:{"uv":[u,v],"uv_size":[112,112]} for f in FACE_BITS},
            "_name":name}
    if rotation: result["rotation"]=rotation
    if pivot: result["pivot"]=pivot
    return result


def adapted_original_pull_loop(family,variant,root,anchor,target_angle,depth):
    """Uniform-scale the original cbrg nine-cube beveled loop, never a new frame."""
    source_name=("quickdraw" if variant=="quickdraw" else "extended_quickdraw")+"_mag_rifle_geo.json"
    source=json.loads((OUT/"reference"/source_name).read_text(encoding="utf-8"))
    source_bone=next(b for b in source["minecraft:geometry"][0]["bones"] if b["name"] in ["quick_hook2","quick_hook"])
    probe=copy.deepcopy(source_bone);probe.pop("parent",None)
    probe_geo={"minecraft:geometry":[{"bones":[probe]}]}
    points=np.concatenate([f[0] for f in mesh_geometry(probe_geo)])
    # Establish the installation plane in the FOOT frame, after undoing the
    # source -30 degree foot angle. AABB-highest in the unlevelled source frame
    # belongs to the angled collar and leaves both actual posts floating when
    # subsequently rotated. Source post0/post2 tops share Y=1.013844 here.
    levelling=rotate([30,0,0])[:3,:3]
    level_points=points@levelling.T;lo=level_points.min(0);hi=level_points.max(0)
    post_probe=copy.deepcopy(probe);post_probe["cubes"]=[probe["cubes"][0]]
    post_points=np.concatenate([f[0] for f in mesh_geometry({"minecraft:geometry":[{"bones":[post_probe]}]})])
    contact_y=(post_points@levelling.T)[:,1].max()
    level_anchor=np.array([(lo[0]+hi[0])/2,contact_y,(lo[2]+hi[2])/2])
    original_anchor=level_anchor@levelling
    # Uniform scaling and a whole-bone rotation preserve bevel angles exactly.
    scale=depth/(hi[2]-lo[2]);anchor=np.array(anchor)
    def remap(v):return [round(float(x),6) for x in (np.array(v)-original_anchor)*scale+anchor]
    hook=copy.deepcopy(source_bone);hook["name"]="original_beveled_pull_loop";hook["parent"]="pull_loop_adapter"
    hook["pivot"]=remap(hook["pivot"])
    for i,c in enumerate(hook["cubes"]):
        c["origin"]=remap(c["origin"]);c["size"]=[round(float(v*scale),6) for v in c["size"]]
        if "pivot" in c:c["pivot"]=remap(c["pivot"])
        if "inflate" in c:c["inflate"]*=scale
        c["_name"]="cbrg_original_beveled_loop_"+str(i)
        new_uv=cube("",[0,0,0],[1,1,1],1 if i<4 else 3)["uv"]
        c["uv"]={face:new_uv[face] for face in c["uv"]}
    adapter={"name":"pull_loop_adapter","parent":root,"pivot":anchor.tolist(),"rotation":[target_angle+30,0,0]}
    return [adapter,hook]


def new_magazine(family, variant):
    """Editable low-poly interpretations of the nine actual official icons."""
    quick=variant!="extended";bodies=[];loop_bones=[]
    def add(name,origin,size,swatch=0,rotation=None,pivot=None):
        bodies.append(cube(name,origin,size,swatch,rotation,pivot))
    if family in ["smg","rifle"]:
        thickness,depth=(.80,2.35) if family=="smg" else (1.0,3.25)
        height=({"quickdraw":9.2,"extended":10.7,"extended_quickdraw":10.3} if family=="smg" else {"quickdraw":10.2,"extended":11.05,"extended_quickdraw":10.7})[variant]
        count=9;y=.40;z=0;length=height/count
        # Segment pivots create an actual curved silhouette, including the rim,
        # central raised rib and four rivets visible in the official Medium icon.
        for segment in range(count):
            angle=(31 if family=="smg" else -29)*(1-(segment+.5)/count)
            pivot=[0,y,z];rot=[angle,0,0]
            def curved(name,origin,size,swatch=0):
                add(f"{name}_{segment}",[origin[0],origin[1]+y,origin[2]+z],size,swatch,rot,pivot)
            if family=="rifle" and variant=="quickdraw":
                curved("curved_recessed_core",[-thickness/2+.10,-.055,-depth/2],[thickness-.20,length+.13,depth],2)
                cuts=[-depth/2,-depth/2+.66,-depth/2+.84,depth/2-.84,depth/2-.66,depth/2]
                for side in [-1,1]:
                    sx=-thickness/2-.04 if side<0 else thickness/2-.06
                    for a,b in [(cuts[0],cuts[1]),(cuts[2],cuts[3]),(cuts[4],cuts[5])]:
                        curved("blue_steel_grooved_panel",[sx,-.055,a],[.10,length+.13,b-a])
                    for rz in [cuts[1],cuts[3]]:
                        curved("recess_worn_edge",[sx-.008,-.055,rz-.026],[.116,length+.13,.026],3)
                        if segment in [0,count-1]:
                            curved("recess_round_end",[sx,0 if segment==0 else length-.16,rz],[.10,.16,.18])
            else:
                curved("curved_steel_body",[-thickness/2,-.055,-depth/2],[thickness,length+.13,depth])
            for side in [-1,1]:
                sx=-thickness/2-.055 if side<0 else thickness/2
                for edge in [-1,1]:
                    curved("panel_border",[sx,0,edge*(depth/2-.12)-.055],[.055,length+.02,.11],3)
                if family=="smg" and 2<=segment<=6:
                    curved("single_center_rib",[sx-.01,-.055,-.12],[.075,length+.13,.24],3)
                if family=="smg" and segment in [0,3]:
                    for edge in [-1,1]:
                        rz=edge*(depth/2-.43)-.055
                        curved("round_rivet",[sx-.018,.25,rz],[.085,.11,.11],3)
                        curved("round_rivet_dark_center",[sx-.023,.278,rz+.028],[.095,.055,.055],1)
                if family=="rifle" and variant!="quickdraw" and segment<=4:
                    # Official polymer variants have framed lower pockets and
                    # a broad plain upper neck, not continuous bright steel ribs.
                    for rz in [-.89,.71]:
                        curved("lower_panel_vertical_border",[sx,-.055,rz],[.065,length+.13,.12],1)
                    for ly in [.05,length-.13]:
                        curved("lower_panel_cross_border",[sx,ly,-.89],[.065,.12,1.72],1)
                if family=="rifle" and variant!="quickdraw" and segment==7:
                    curved("magazine_catch_recess",[sx-.008,.20,.23],[.078,.28,.35],2)
                    curved("magazine_catch_metal",[sx-.012,.25,.29],[.085,.16,.23],5)
            if segment==0:
                curved("bottom_plate",[-thickness/2-.10,-.15,-depth/2-.12],[thickness+.20,.24,depth+.24],1)
                if quick:
                    loop_bones=adapted_original_pull_loop(family,variant,f"{variant}_mag_{family}",[0,y-.06,z],angle,depth+.04)
            y+=length*math.cos(math.radians(angle));z-=length*math.sin(math.radians(angle))
        # Neck/rim angle approaches vertical at the top of the curved body.
        add("feed_neck",[-thickness/2+.04,y-.02,z-depth/2+.05],[thickness-.08,.32,depth-.10],1)
        add("follower",[-thickness/2+.09,y+.24,z-depth/2+.13],[thickness-.18,.10,depth-.26],4)
        for side in [-1,1]:
            sx=-thickness/2 if side<0 else thickness/2-.10
            add("folded_feed_lip",[sx,y+.22,z-depth/2+.08],[.10,.24,depth-.16],3)
        for label,cz,cl,cw,ch,material in [("case",z-depth/2+.18,1.48,.30,.19,6),("tip",z-depth/2+1.66,.28,.23,.13,7),("tip_end",z-depth/2+1.94,.13,.13,.075,7)]:
            add("visible_cartridge_"+label,[-cw/2,y+.31,cz],[cw,ch,cl],material)
    else:
        thickness,depth=1.20,4.30
        height={"quickdraw":4.25,"extended":5.35,"extended_quickdraw":5.05}[variant]
        x=-thickness/2;z=-depth/2;y=.35
        # Deep grooves are openings in each side plate over a dark recessed core,
        # rather than raised bright sticks pasted onto a featureless black box.
        add("recessed_core",[x+.10,y,z],[thickness-.20,height,depth],2)
        cuts=[z,z+.78,z+1.01,z+3.15,z+3.38,z+depth]
        for side in [-1,1]:
            sx=x-.055 if side<0 else -x-.045
            for a,b in [(cuts[0],cuts[1]),(cuts[2],cuts[3]),(cuts[4],cuts[5])]:
                add("steel_side_panel",[sx,y,a],[.10,height,b-a])
            for rz in [cuts[1],cuts[3]]:
                add("groove_closed_bottom",[sx,y,rz],[.10,.35,.23])
                add("groove_closed_top",[sx,y+height-.36,rz],[.10,.36,.23])
                add("groove_worn_edge",[sx-.008,y+.35,rz-.035],[.116,height-.71,.035],3)
            add("upper_worn_seam",[sx-.01,y+height-.35,z],[.12,.06,depth],3)
        for rz in [z,z+depth-.10]:
            add("folded_side_spine",[x-.07,y,rz],[thickness+.14,height,.10],1)
        add("base_plate",[x-.13,.12,z-.12],[thickness+.26,.28,depth+.24],1)
        add("base_plate_worn_rim",[x-.15,.12,z-.15],[thickness+.30,.065,depth+.30],3)
        if quick:
            loop_bones=adapted_original_pull_loop(family,variant,f"{variant}_mag_{family}",[0,.20,0],0,depth+.10)
        # Cartridge sits between dark steel lips; brass and copper are subtle.
        add("follower",[x+.10,y+height-.08,z+.12],[thickness-.20,.15,depth-.24],4)
        for side in [-1,1]:
            sx=x if side<0 else -x-.12
            add("feed_lip",[sx,y+height-.04,z+.04],[.12,.23,depth-.08],3)
        add("visible_brass_case",[-.18,y+height+.025,z+.30],[.36,.18,2.65],6)
        add("visible_copper_tip",[-.135,y+height+.045,z+2.95],[.27,.14,.45],7)
        add("visible_copper_tip_end",[-.075,y+height+.075,z+3.40],[.15,.075,.23],7)
    for c in bodies:
        for k in ["origin","size","pivot","rotation"]:
            if k in c:c[k]=[round(float(a),5) for a in c[k]]
    return {"format_version":"1.12.0","minecraft:geometry":[{
        "description":{"identifier":f"geometry.{NS}:{variant}_mag_{family}","texture_width":512,"texture_height":512,
            "visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,.4,0]},
        "bones":[{"name":f"{variant}_mag_{family}","pivot":[0,0,0],"cubes":bodies}]+loop_bones}]}


def bbmodel(geometry, texture, name):
    desc=geometry["minecraft:geometry"][0]["description"]
    bones=geometry["minecraft:geometry"][0]["bones"]
    elements=[]; outliner=[];groups={}
    for b in bones:
        group={"name":b["name"],"origin":b.get("pivot",[0,0,0]),"uuid":stable_uuid(name+"/"+b["name"]),
               "export":True,"visibility":True,"children":[]}
        if b.get("rotation"):group["rotation"]=b["rotation"]
        for i,c in enumerate(b.get("cubes",[])):
            uid=stable_uuid(name+"/"+b["name"]+"/cube/"+str(i))
            entry={"name":c.get("_name","cube_"+str(i)),"box_uv":False,"from":c["origin"],
                   "to":[c["origin"][a]+c["size"][a] for a in range(3)],
                   "origin":c.get("pivot",b.get("pivot",[0,0,0])),"uuid":uid,
                   "faces":{},"type":"cube","autouv":0,"color":0}
            if c.get("rotation"):entry["rotation"]=c["rotation"]
            if c.get("inflate"):entry["inflate"]=c["inflate"]
            for f,uv in c["uv"].items():
                u,v=uv["uv"]; w,h=uv["uv_size"]
                entry["faces"][f]={"uv":[u,v,u+w,v+h],"texture":0}
            elements.append(entry);group["children"].append(uid)
        groups[b["name"]]=group
    for b in bones:
        if b.get("parent"):groups[b["parent"]]["children"].append(groups[b["name"]])
        else:outliner.append(groups[b["name"]])
    return {"meta":{"format_version":"4.10","model_format":"free","box_uv":False},
        "name":name,"model_identifier":f"{NS}:{name}","resolution":{"width":desc["texture_width"],"height":desc["texture_height"]},
        "elements":elements,"outliner":outliner,"textures":[{
            "name":name+".png","id":"0","uuid":stable_uuid(name+"/texture"),"width":texture.width,"height":texture.height,
            "uv_width":desc["texture_width"],"uv_height":desc["texture_height"],"visible":True,"internal":True,
            "source":"data:image/png;base64,"+base64.b64encode(png_bytes(texture)).decode(),"relative_path":"../../textures/attachments/"+name+".png"}]}


def png_bytes(image):
    stream=io.BytesIO();image.save(stream,format="PNG");return stream.getvalue()


def export_obj(path, faces, texture, texture_size):
    """Baked triangulated OBJ + MTL using the very same triangles as the preview."""
    lines=["# Exact Bedrock geometry, one unit = 1/16 Minecraft block",f"mtllib {path.stem}.mtl","usemtl magazine"]
    for points,uv,normal in faces:
        for p in points:lines.append("v "+" ".join(f"{v:.7f}" for v in p))
    tw,th=texture_size
    for points,uv,normal in faces:
        for u,v in uv:lines.append(f"vt {u/tw:.7f} {1-v/th:.7f}")
    for i,_ in enumerate(faces):
        a=i*4+1
        lines.extend([f"f {a}/{a} {a+1}/{a+1} {a+2}/{a+2}",f"f {a}/{a} {a+2}/{a+2} {a+3}/{a+3}"])
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text("\n".join(lines)+"\n",encoding="utf-8")
    path.with_suffix(".mtl").write_text(f"newmtl magazine\nKa 0.2 0.2 0.2\nKd 1 1 1\nKs 0.1 0.1 0.1\nNs 12\nmap_Kd {texture}\n",encoding="utf-8")


def raster(faces, texture, texture_size, width=760, height=760, angle=1, fit=False):
    """Orthographic z-buffer textured rendering; source alpha and UVs are honored."""
    ss=2; width*=ss;height*=ss
    bg=np.zeros((height,width,3),dtype=np.float32)
    for y in range(height):
        t=y/height;bg[y,:,:]=np.array([244,245,244])*(1-t)+np.array([224,227,225])*t
    zbuffer=np.full((height,width),-np.inf)
    points=np.concatenate([f[0] for f in faces])
    low=points.min(axis=0);high=points.max(axis=0)
    center=np.array([(low[0]+high[0])/2,low[1],(low[2]+high[2])/2])
    forward=np.array([1,.40,.62*angle]);forward/=np.linalg.norm(forward)
    right=np.cross([0,1,0],forward);right/=np.linalg.norm(right)
    up=np.cross(forward,right)
    basis=np.array([right,up,forward]).T
    scale=47*ss
    ox,oy=width*.50,height*.88
    if fit:
        viewpoints=(points-center)@basis
        span=np.ptp(viewpoints,axis=0)
        scale=min(width*.74/max(span[0],.1),height*.80/max(span[1],.1))
        ox=width*.50-(viewpoints[:,0].min()+viewpoints[:,0].max())*.5*scale
        oy=height*.52+(viewpoints[:,1].min()+viewpoints[:,1].max())*.5*scale
    tex=np.array(texture.convert("RGBA"))
    tw,th=texture_size
    texscale=np.array([texture.width/tw,texture.height/th])
    light=np.array([-.4,.8,.55]);light/=np.linalg.norm(light)
    fill=np.array([.8,.3,-.7]);fill/=np.linalg.norm(fill)
    # Soft neutral studio contact shadow, all visible geometry comes from faces.
    shadow=Image.new("RGBA",(width,height),(0,0,0,0));sd=ImageDraw.Draw(shadow)
    depth=max(2,high[2]-low[2]);sx=(depth*1.1+1.4)*scale
    sd.ellipse((ox-sx/2,oy-13*ss,ox+sx/2,oy+13*ss),fill=(30,38,32,44))
    shadow=shadow.filter(ImageFilter.GaussianBlur(13*ss))
    bg_image=Image.fromarray(bg.astype(np.uint8)).convert("RGBA");bg_image.alpha_composite(shadow);bg=np.array(bg_image)[:,:,:3].astype(np.float32)
    for p,uv,normal in faces:
        view=(p-center)@basis
        projected=np.c_[ox+view[:,0]*scale,oy-view[:,1]*scale,view[:,2]]
        # Charcoal steel stays charcoal; directional fill reveals its edge ribs.
        shade=.62+.70*max(0,np.dot(normal,light))+.28*max(0,np.dot(normal,fill))
        for triangle in [(0,1,2),(0,2,3)]:
            q=projected[list(triangle)];tuv=uv[list(triangle)]*texscale
            minx=max(0,int(np.floor(q[:,0].min())));maxx=min(width-1,int(np.ceil(q[:,0].max())))
            miny=max(0,int(np.floor(q[:,1].min())));maxy=min(height-1,int(np.ceil(q[:,1].max())))
            if maxx<minx or maxy<miny:continue
            denominator=(q[1,1]-q[2,1])*(q[0,0]-q[2,0])+(q[2,0]-q[1,0])*(q[0,1]-q[2,1])
            if abs(denominator)<1e-8:continue
            xx,yy=np.meshgrid(np.arange(minx,maxx+1)+.5,np.arange(miny,maxy+1)+.5)
            w0=((q[1,1]-q[2,1])*(xx-q[2,0])+(q[2,0]-q[1,0])*(yy-q[2,1]))/denominator
            w1=((q[2,1]-q[0,1])*(xx-q[2,0])+(q[0,0]-q[2,0])*(yy-q[2,1]))/denominator
            w2=1-w0-w1
            z=w0*q[0,2]+w1*q[1,2]+w2*q[2,2]
            mask=(w0>=-1e-7)&(w1>=-1e-7)&(w2>=-1e-7)&(z>zbuffer[miny:maxy+1,minx:maxx+1])
            if not mask.any():continue
            uvx=w0*tuv[0,0]+w1*tuv[1,0]+w2*tuv[2,0]
            uvy=w0*tuv[0,1]+w1*tuv[1,1]+w2*tuv[2,1]
            tx=np.clip(np.floor(uvx).astype(int),0,texture.width-1)
            ty=np.clip(np.floor(uvy).astype(int),0,texture.height-1)
            colors=tex[ty,tx]
            mask &= colors[:,:,3]>0
            region=bg[miny:maxy+1,minx:maxx+1];zr=zbuffer[miny:maxy+1,minx:maxx+1]
            alpha=colors[:,:,3]/255
            lit=np.clip(colors[:,:,:3]*shade,0,255)
            region[mask]=lit[mask]*alpha[mask,None]+region[mask]*(1-alpha[mask,None]);zr[mask]=z[mask]
    return Image.fromarray(np.uint8(np.clip(bg,0,255))).resize((width//ss,height//ss),Image.Resampling.LANCZOS)


def font(size,bold=False):
    path=pathlib.Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc")
    return ImageFont.truetype(str(path),size)


def render_card(faces,texture,texture_size,family,variant):
    image=Image.new("RGB",(900,1010),(240,242,240));draw=ImageDraw.Draw(image)
    draw.text((44,25),F_NAMES[family],font=font(28,True),fill=(30,41,34))
    draw.text((44,69),V_NAMES[variant]+"弹匣",font=font(42,True),fill=(30,41,34))
    draw.text((44,128),f"{NS}:{variant}_mag_{family}",font=font(18),fill=(85,96,88))
    scene=raster(faces,texture,texture_size,900,760)
    image.paste(scene,(0,171))
    draw=ImageDraw.Draw(image)
    draw.text((44,946),"实际模型 · 相同比例 · Bedrock JSON / Blockbench / OBJ",font=font(20),fill=(64,79,67))
    draw.text((44,980),"官网造型参照 · 原 AR 折弯拉环适配 · 未安装至游戏",font=font(17),fill=(95,104,97))
    return image


def main():
    source_before=hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    DOC.mkdir(parents=True,exist_ok=True)
    entries=[];cards=[];comparison_scenes=[]
    reference_sources=json.loads((REF/"reference-sources.json").read_text(encoding="utf-8"))
    reference_index={(r["family"],r["variant"]):r for r in reference_sources["images"]}
    policy=json.loads((ROOT/"docs/attachments/official-pubg-references.json").read_text(encoding="utf-8"))["magazine_policy"]
    pack=OUT/"visual-assets"
    with zipfile.ZipFile(SOURCE) as archive:
        for family in ["rifle","smg","sniper"]:
            for variant in VARIANTS:
                name=f"{variant}_mag_{family}"
                original=f"{variant}_mag_rifle"
                geom=new_magazine(family,variant);texture=atlas(family,variant)
                provenance={"kind":"original-body-with-source-derived-handle","geometry_change":
                        "rebuilt curved rifle body, two recessed long ribs for QuickDraw, framed lower polymer panels and magazine catch for Extended/EQD" if family=="rifle" else
                        "9 curved body segments, continuous single central rib, four rivets per broad side, broad-face open loop and visible cartridge" if family=="smg" else
                        "wide squat steel box with two real recessed longitudinal grooves per side, folded rim, broad open pull loop and visible cartridge",
                        "texture_change":"original procedural 512px steel/brass/rubber atlas; blue steel only QuickDraw, charcoal Extended/EQD; fine grain, scuffs, edge wear"}
                if variant!="extended":
                    provenance["pull_loop"]={"source_id":"cbrg:"+original,"source_geometry":"assets-workbench/pubg-magazines/reference/"+original+"_geo.json",
                        "source_bone":"quick_hook2" if variant=="quickdraw" else "quick_hook","source_cube_count":9,
                        "adaptation":"same nine cube sizes/origins/pivots under uniform scale and translation; preserved cube/bone rotations; whole-bone rotation aligns the new foot; replacement atlas UVs"}
                ref=reference_index[(REF_FAMILY[family],REF_VARIANT[variant])]
                refpath=REF/ref["file"]
                assert hashlib.sha256(refpath.read_bytes()).hexdigest()==ref["sha256"]
                provenance["official_visual_reference"]={"publisher":"PUBG official api-assets","url":ref["url"],"path":str(refpath.relative_to(ROOT)).replace("\\","/"),"sha256":ref["sha256"],"use":"visual reference only; original icon pixels are not baked into textures"}
                bb=bbmodel(geom,texture,name)
                # Workbench names are stripped from Bedrock standard-schema export.
                exported=copy.deepcopy(geom)
                for b in exported["minecraft:geometry"][0]["bones"]:
                    for c in b.get("cubes",[]):c.pop("_name",None)
                geo_path=pack/f"assets/{NS}/geo_models/attachments/{name}_geo.json"
                texture_path=pack/f"assets/{NS}/textures/attachments/{name}.png"
                bb_path=pack/f"assets/{NS}/bbmodel/attachments/{name}.bbmodel"
                write_json(geo_path,exported);write_json(bb_path,bb)
                texture_path.parent.mkdir(parents=True,exist_ok=True)
                texture.save(texture_path)
                # Re-read the actual exported file so every preview/OBJ is
                # tied to the delivery asset rather than an alternate scene.
                exported=json.loads(geo_path.read_text(encoding="utf-8"))
                faces=mesh_geometry(exported)
                desc=exported["minecraft:geometry"][0]["description"]
                texsize=(desc["texture_width"],desc["texture_height"])
                obj_path=OUT/f"obj/{name}.obj"
                tex_obj=obj_path.parent/(name+".png");tex_obj.parent.mkdir(parents=True,exist_ok=True);tex_obj.write_bytes(texture_path.read_bytes())
                export_obj(obj_path,faces,tex_obj.name,texsize)
                # Model/texture/display references are valid TaCZ asset layout;
                # no data/index registration is emitted before gameplay approval.
                write_json(pack/f"assets/{NS}/display/attachments/{name}_display.json",{
                    "model":f"{NS}:attachments/{name}_geo","texture":f"{NS}:attachments/{name}"})
                rendered=render_card(faces,texture,texsize,family,variant)
                render_path=DOC/(name+".png");rendered.save(render_path)
                cards.append(rendered)
                comparison_scenes.append((family,variant,refpath,raster(faces,texture,texsize,440,490,fit=True)))
                points=np.concatenate([f[0] for f in faces]);dimensions=points.max(0)-points.min(0)
                entry={"id":f"{NS}:{name}","family":family,"variant":variant,"label":F_NAMES[family]+" · "+V_NAMES[variant],
                    "geometry":str(geo_path.relative_to(ROOT)).replace("\\","/"),"texture":str(texture_path.relative_to(ROOT)).replace("\\","/"),
                    "modelPath":str(geo_path.relative_to(ROOT)).replace("\\","/"),"texturePath":str(texture_path.relative_to(ROOT)).replace("\\","/"),
                    "blockbench":str(bb_path.relative_to(ROOT)).replace("\\","/"),"obj":str(obj_path.relative_to(ROOT)).replace("\\","/"),
                    "render":str(render_path.relative_to(ROOT)).replace("\\","/"),"renderPath":str(render_path.relative_to(ROOT)).replace("\\","/"),
                    "category":family,"cubes":sum(len(b.get("cubes",[])) for b in exported["minecraft:geometry"][0]["bones"]),
                    "visible_triangles":len(faces)*2,"dimensions_model_units":[round(float(v),4) for v in dimensions],
                    "provenance":provenance,"gameplay_attributes":{
                        "status":"proposal_not_installed","slot":"magazine","extended_mag_level":MAG_LEVEL[variant],
                        "reload_speed_bonus_pct":{"quickdraw":60,"extended":0,"extended_quickdraw":15}[variant],
                        "reload_duration_multiplier":policy["proposed_tacz_reload_duration_multipliers"][variant],
                        "reload_mapping":"our proposed TaCZ mapping: duration / (1 + official speed bonus); PUBG engine formula not verified",
                        "official_m416_example_only":{k:policy["official_m416_example"][k] for k in ["base",variant,"source"]} if family=="rifle" else None,
                        "capacity_rule":"Read each gun's existing extended_mag_ammo_amount at native level1/2/3 respectively; preserve the gun's capacity table. QuickDraw is level1 and must not be assumed equal to base capacity.",
                        "other_gun_capacities":"use the existing per-gun native capacity table; no universal42/40 or new capacity values",
                        "dmr_allowed":family in ["rifle","sniper"],"source":"docs/attachments/official-pubg-references.json#magazine_policy"},"installed":False}
                entries.append(entry)
                print(f"Rendered {name}: {entry['cubes']} cubes / {entry['visible_triangles']} triangles",flush=True)
    overview=Image.new("RGB",(1800,2260),(233,237,234));d=ImageDraw.Draw(overview)
    d.text((45,25),"PUBG 弹匣 · 九件真实模型预览",font=font(40,True),fill=(28,43,32))
    d.text((45,83),"快速 / 扩容 / 快速扩容；各行使用相同镜头与模型比例",font=font(25),fill=(74,91,78))
    for i,card in enumerate(cards):overview.paste(card.resize((600,673),Image.Resampling.LANCZOS),((i%3)*600,145+(i//3)*687))
    d=ImageDraw.Draw(overview)
    d.text((45,2220),"精确射手步枪也可使用步枪类弹匣；各枪的安装限制与容量另列规则表。",font=font(24),fill=(53,72,58))
    overview.save(DOC/"magazine-overview.png")
    comparison=Image.new("RGB",(2700,2180),(235,239,236));cd=ImageDraw.Draw(comparison)
    cd.text((38,24),"PUBG 官方图标与实际导出模型对照",font=font(40,True),fill=(28,43,32))
    cd.text((38,80),"左：官方 api-assets 参照；右：同一 Bedrock / Blockbench / OBJ 几何与贴图",font=font(26),fill=(74,91,78))
    for i,(family,variant,refpath,scene) in enumerate(comparison_scenes):
        x=(i%3)*900;y=135+(i//3)*660
        cd.rounded_rectangle((x+12,y,x+888,y+638),radius=12,fill=(247,248,246))
        cd.text((x+30,y+16),F_NAMES[family]+" · "+V_NAMES[variant],font=font(24,True),fill=(30,41,34))
        reference=Image.open(refpath).convert("RGBA");reference.thumbnail((400,470),Image.Resampling.LANCZOS)
        reference_bg=Image.new("RGBA",reference.size,(237,240,238,255));reference_bg.alpha_composite(reference)
        comparison.paste(reference_bg.convert("RGB"),(x+28+(400-reference.width)//2,y+100))
        comparison.paste(scene,(x+445,y+82))
        cd=ImageDraw.Draw(comparison)
        cd.text((x+32,y+579),"官方参照",font=font(20),fill=(74,91,78))
        cd.text((x+460,y+579),"真实模型渲染 · 非概念图",font=font(20),fill=(74,91,78))
    cd.text((38,2135),"各模型按外接框独立取景；仅工作台提案，未修改枪包、运行注册或存档。",font=font(25),fill=(53,72,58))
    comparison.save(DOC/"magazine-reference-comparison.png")
    source_after=hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    assert source_before==source_after,"Source gunpack changed unexpectedly"
    manifest={"version":2,"revision":"official-reference-fidelity-2026-10-18","reference_sources":"assets-workbench/pubg-magazines/reference/pubg-api/reference-sources.json",
              "comparison_render":"docs/attachments/magazines/magazine-reference-comparison.png","source_archive":str(SOURCE.relative_to(ROOT)).replace("\\","/"),"source_sha256_before":source_before,
              "source_sha256_after":source_after,"source_unchanged":True,"renderer":"local numpy textured orthographic triangle rasterizer; exported Bedrock geometry is re-read",
              "installed":False,"count":len(entries),"entries":entries}
    write_json(OUT/"manifest.json",manifest)
    write_json(DOC/"magazine-models.json",manifest)
    (OUT/"README.md").write_text("""# PUBG 弹匣模型工作台

九件可编辑模型及实际几何渲染。未修改原枪包，未注册或安装至游戏。

- `visual-assets/assets/tacz_pubg_workbench/geo_models/attachments/`：TaCZ/Bedrock 1.12 模型 JSON。
- 同目录体系下 `textures/attachments/` 为贴图，`bbmodel/attachments/` 为 Blockbench 源文件。
- `obj/`：同一导出几何的 OBJ / MTL / PNG，可导入常规三维软件。
- `manifest.json`：模型清单、源文件证据、尺寸与三角面数。
- 预览：`docs/attachments/magazines/magazine-overview.png` 及九张单件图。

本次逐张对照 PUBG 官方 `pubg/api-assets` 九张物品图标修订。步枪三件重建曲面主体：快速款有两条真实凹槽和明显磨损蓝钢色；扩容/快速扩容保留黑灰材质，并在下半部增加分格面板和上部卡扣。SMG 三件重建为弯曲钢匣，加入单条中肋、每侧四铆钉和顶部弹头；SR 三件改为较宽矮盒匣，两条纵向槽是真实凹入侧板。蓝色只用于参照中有蓝钢色的快速款，没有把所有款式统一染蓝。

六件快速/快速扩容弹匣的拉环均提取自原 cbrg 步枪快速/快速扩容的 `quick_hook2` / `quick_hook` 九块立方体拓扑，保留原有斜接、折弯、异轴倒角及骨骼/立方体旋转，通过统一缩放、平移和整骨旋转适配新底座，未使用简单矩形框替代。纯扩容三件不带拉环。来源与适配方式逐件记入清单，源枪包只读。

贴图为本地派生或原创程序材质，含金属颗粒、擦痕和边缘磨损，不直接把官网图标贴到模型上。官方源图、URL 和 SHA-256 见 `reference/pubg-api/reference-sources.json` 及每件清单的 `provenance.official_visual_reference`。`docs/attachments/magazines/magazine-reference-comparison.png` 为九件官方图标与模型对照；九件总览与单件图仍从导出的 Bedrock 文件重新读取，并使用相同几何、UV 和贴图渲染，不是独立概念图。Blockbench 内嵌贴图与外部 PNG 一致，OBJ 使用同一变换后的面。

SMG 类外观是共用物品图标/地面模型，不能代表每支现实枪的实际弹匣尺寸；装枪后的扩容外观仍须使用各枪原生的 `mag_extended_1/2/3` 骨骼或逐枪适配。SR/DMR 与 AR 弹匣规则允许重叠；某枪同时适配两类时容量必须取该枪的统一目标，不能靠换类叠加容量。

`gameplay_attributes` 仅保存未安装的方案元数据。按本次明确需求，快速/扩容/快速扩容分别使用 TaCZ 原生 `extended_mag_level` 1/2/3，容量读取各枪既有 `extended_mag_ammo_amount` 对应级别，不覆盖原枪容量表，也不笼统假定快速=基础容量。快速额外加速60%，快速扩容额外加速15%，扩容不加速；本项目近似为换弹时间÷1.6（0.625）/÷1.15（0.869565），这是明确的实现假设，不能宣称已核实 PUBG 引擎公式。官方 PC15.1 的 M416 基础/快速30、扩容42、快速扩容40仍只保留为历史官网示例，不把42/40强套到其他枪。DMR 可走 AR 或 SR 类但只能占一个弹匣槽。模型生成器不写运行注册。

修订前产物保存在 `revisions/before-reference-fix/`，原枪包与官方参考均未覆盖；前后哈希和导出一致性验证见 `manifest.json` / `validation.json`。

重建：使用带 Pillow/NumPy 的 Python 运行 `scripts/build-pubg-magazine-models.py`。不需要启动 Minecraft。
""",encoding="utf-8")
    validation={"count":len(entries),"exactly_nine":len(entries)==9,"source_unchanged":source_before==source_after,
        "installed":False,"renderer_reads_exported_bedrock":True,"checks":[],
        "quickdraw_blue_texture_checks":[],"official_reference_hash_checks":[],"pull_loop_checks":[]}
    for entry in entries:
        texture_path=ROOT/entry["texture"];bb=json.loads((ROOT/entry["blockbench"]).read_text(encoding="utf-8"))
        embedded=base64.b64decode(bb["textures"][0]["source"].split(",",1)[1])
        tex=Image.open(texture_path).convert("RGBA");rgb=np.array(tex)
        uids=[e["uuid"] for e in bb["elements"]]
        check={"id":entry["id"],"embedded_texture_matches_png":embedded==texture_path.read_bytes(),
            "obj_texture_matches_png":(OUT/"obj"/texture_path.name).read_bytes()==texture_path.read_bytes(),
            "unique_cube_uuids":len(uids)==len(set(uids)),"bb_cube_count_matches_bedrock":len(uids)==entry["cubes"]}
        assert all(check[k] for k in check if k!="id"),check
        validation["checks"].append(check)
        geo=json.loads((ROOT/entry["geometry"]).read_text(encoding="utf-8"))
        bones=geo["minecraft:geometry"][0]["bones"]
        for bone in bones:
            for c in bone.get("cubes",[]):
                assert min(c["size"])>0 and np.isfinite(c["origin"]+c["size"]).all()
                for uv in c["uv"].values():
                    u,v=uv["uv"];w,h=uv["uv_size"]
                    assert 0<=min(u,u+w)<=max(u,u+w)<=512 and 0<=min(v,v+h)<=max(v,v+h)<=512
        hook=next((b for b in bones if b["name"]=="original_beveled_pull_loop"),None)
        if entry["variant"]=="extended":
            assert hook is None,"Extended must have no quickdraw loop"
        else:
            assert hook is not None and len(hook["cubes"])==9
            source=json.loads((ROOT/entry["provenance"]["pull_loop"]["source_geometry"]).read_text(encoding="utf-8"))
            source_hook=next(b for b in source["minecraft:geometry"][0]["bones"] if b["name"]==entry["provenance"]["pull_loop"]["source_bone"])
            scales=np.concatenate([np.array(c["size"])/np.array(s["size"]) for c,s in zip(hook["cubes"],source_hook["cubes"])])
            rotations_preserved=hook.get("rotation")==source_hook.get("rotation") and all(c.get("rotation")==s.get("rotation") for c,s in zip(hook["cubes"],source_hook["cubes"]))
            assert np.ptp(scales)<2e-5 and rotations_preserved
            foot=next(c for b in bones for c in b.get("cubes",[]) if c["origin"][1]<.5 and c["size"][1]<.31 and c["size"][2]>2 and b["name"]!=hook["name"])
            inverse=np.linalg.inv(around(foot.get("pivot",[0,0,0]),foot.get("rotation",[0,0,0])))
            footlo=np.array(foot["origin"]);foothi=footlo+np.array(foot["size"])
            overlaps=[]
            for post_index in [0,2]:
                isolated=copy.deepcopy(geo)
                for b in isolated["minecraft:geometry"][0]["bones"]:
                    b["cubes"]=[b["cubes"][post_index]] if b["name"]==hook["name"] else []
                pp=np.concatenate([f[0] for f in mesh_geometry(isolated)])
                local=(inverse@np.c_[pp,np.ones(len(pp))].T).T[:,:3]
                overlap=np.minimum(local.max(0),foothi)-np.maximum(local.min(0),footlo)
                assert min(overlap)>0, (entry["id"],post_index,overlap)
                overlaps.append([round(float(v),6) for v in overlap])
            validation["pull_loop_checks"].append({"id":entry["id"],"source_cubes":9,"uniform_scale":round(float(scales.mean()),6),
                "original_bevel_rotations_preserved":rotations_preserved,"both_posts_intersect_baseplate":True,"contact_overlap_xyz":overlaps})
        if entry["variant"]=="quickdraw":
            mask=(rgb[:,:,3]>0)&(rgb[:,:,2]>rgb[:,:,0]+10)&(rgb[:,:,1]>rgb[:,:,0]+6)
            fraction=float(mask.sum()/max(1,(rgb[:,:,3]>0).sum()))
            assert fraction>.08,"QuickDraw blue-steel material missing"
            validation["quickdraw_blue_texture_checks"].append({"id":entry["id"],"blue_pixel_fraction":round(fraction,4)})
        ref=entry["provenance"]["official_visual_reference"]
        same=hashlib.sha256((ROOT/ref["path"]).read_bytes()).hexdigest()==ref["sha256"]
        assert same
        validation["official_reference_hash_checks"].append({"id":entry["id"],"unchanged":same})
    assert validation["exactly_nine"]
    write_json(OUT/"validation.json",validation)
    print("Done:",DOC/"magazine-overview.png",flush=True)


if __name__=="__main__":main()
