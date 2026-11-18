"""Extract Java ModelBox geometry and Minecraft JSON for review.

Preserves constructor pivots, parent transforms, rotations, mirror and texture
UVs. Vehicle animation is the neutral constructor pose. No guessed replacement
geometry is used. Produces textured OBJ, metadata, PNGs and offline WebGL HTML.
"""
from pathlib import Path
import base64, hashlib, importlib.util, json, math, re, shutil, zipfile
import numpy as np
from PIL import Image, ImageDraw, ImageFont
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/"assets-workbench/pubgmc-reference"
SRC=OUT/"source/PUBGMC-f8c341eb784425b1bf7e015f36b851e760ab2999"
ASSETS=SRC/"src/main/resources/assets/pubgmc"
MODELS=SRC/"src/main/java/dev/toma/pubgmc/client/models"
RUNTIME=ROOT/"neoforge-1.21.1/src/main/resources/assets/tacz_pubg"
spec=importlib.util.spec_from_file_location("magazine_renderer",ROOT/"scripts/build-pubg-magazine-models.py")
renderer=importlib.util.module_from_spec(spec);spec.loader.exec_module(renderer)
def write(path,data):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def numbers(s):return [float(x.strip().rstrip("fFdD")) for x in s.split(",")]
def translate(p):
    m=np.eye(4);m[:3,3]=p;return m
def rotation(angles):
    result=np.eye(4)
    for axis in [2,1,0]:
        a,b=[(1,2),(2,0),(0,1)][axis];m=np.eye(4);c=math.cos(angles[axis]);s=math.sin(angles[axis])
        m[a,a]=m[b,b]=c;m[a,b]=-s;m[b,a]=s;result=result@m
    return result
def face(points,uv,center):
    normal=np.cross(points[1]-points[0],points[2]-points[0])
    length=np.linalg.norm(normal)
    if length<1e-10:return None
    normal/=length
    if np.dot(normal,points.mean(axis=0)-center)<0:normal=-normal
    return (points,uv,normal)
def constructor(text,name):
    start=text.index("public "+name+"()");start=text.index("{",start)+1;depth=1
    for end in range(start,len(text)):
        if text[end]=="{":depth+=1
        elif text[end]=="}":
            depth-=1
            if depth==0:return text[start:end]
    raise ValueError("Unclosed constructor")
def java_model(path,excluded=None):
    code=constructor(path.read_text(),path.stem);bones={}
    for name in re.findall(r"(\w+)\s*=\s*new ModelRenderer\(this\)",code):
        bones[name]={"pivot":[0,0,0],"rotation":[0,0,0],"boxes":[]}
    for name,args in re.findall(r"(\w+)\.setRotationPoint\(([^)]+)\)",code):bones[name]["pivot"]=numbers(args)
    for name,args in re.findall(r"setRotationAngle\((\w+),\s*([^)]+)\)",code):bones[name]["rotation"]=numbers(args)
    for parent,child in re.findall(r"(\w+)\.addChild\((\w+)\)",code):bones[child]["parent"]=parent
    count=0
    for name,args in re.findall(r"(\w+)\.cubeList\.add\(new ModelBox\(\w+,\s*([^)]+)\)\)",code):
        vals=args.split(",");mirror=vals[-1].strip()=="true";v=numbers(",".join(vals[:-1]))
        bones[name]["boxes"].append((v,mirror));count+=1
    assert count==code.count("new ModelBox("),(path,count)
    mats={}
    def matrix(name):
        if name not in mats:
            b=bones[name];m=translate(b["pivot"])@rotation(b["rotation"])
            mats[name]=matrix(b["parent"])@m if b.get("parent") else m
        return mats[name]
    def excluded_bone(name):
        return name==excluded or (bones[name].get("parent") and excluded_bone(bones[name]["parent"]))
    result=[];flip=np.diag([1,-1,1,1])
    for name,bone in bones.items():
        if excluded and excluded_bone(name):continue
        for vals,mirror in bone["boxes"]:
            u,v,x,y,z,w,h,d,inflate=vals
            low=np.array([x,y,z])-inflate;high=np.array([x+w,y+h,z+d])+inflate
            if mirror:low[0],high[0]=high[0],low[0]
            raw=np.array([[low[0],low[1],low[2]],[high[0],low[1],low[2]],[high[0],high[1],low[2]],[low[0],high[1],low[2]],
                          [low[0],low[1],high[2]],[high[0],low[1],high[2]],[high[0],high[1],high[2]],[low[0],high[1],high[2]]])
            world=(flip@matrix(name)@np.c_[raw,np.ones(8)].T).T[:,:3];center=world.mean(0)
            # Minecraft 1.12 ModelBox/TexturedQuad corner order and UV net.
            quads=[([5,1,2,6],[u+d+w,v+d,u+d+w+d,v+d+h]),
                   ([0,4,7,3],[u,v+d,u+d,v+d+h]),
                   ([5,4,0,1],[u+d,v,u+d+w,v+d]),
                   ([2,3,7,6],[u+d+w,v+d,u+d+w+w,v]),
                   ([1,0,3,2],[u+d,v+d,u+d+w,v+d+h]),
                   ([4,5,6,7],[u+d+w+d,v+d,u+d+w+d+w,v+d+h])]
            for ids,(u1,v1,u2,v2) in quads:
                points=world[ids];uv=np.array([[u2,v1],[u1,v1],[u1,v2],[u2,v2]])
                if mirror:points=points[::-1];uv=uv[::-1]
                f=face(points,uv,center)
                if f:result.append(f)
    width=int(re.search(r"textureWidth\s*=\s*(\d+)",code)[1]);height=int(re.search(r"textureHeight\s*=\s*(\d+)",code)[1])
    return result,(width,height),{"constructor_cuboids":count,"bones":len(bones),"excluded_bone":excluded}
def json_model(path,texture_base):
    data=json.loads(path.read_text(encoding="utf-8"));images={};textures=data["textures"]
    for k,value in textures.items():
        if k=="particle":continue
        if value.startswith("pubgmc:"):p=ASSETS/"textures"/(value.split(":")[1]+".png")
        elif value.startswith("tacz_pubg:"):p=RUNTIME/"textures"/(value.split(":")[1]+".png")
        else:
            p=OUT/"vanilla"/(value+".png")
            if not p.exists():
                with zipfile.ZipFile(OUT/"downloads/minecraft-1.12.2-client.jar") as jar:
                    p.parent.mkdir(parents=True,exist_ok=True)
                    p.write_bytes(jar.read("assets/minecraft/textures/"+value+".png"))
        images[k]=Image.open(p).convert("RGBA")
    tile=max(max(im.size) for im in images.values());atlas=Image.new("RGBA",(tile*len(images),tile))
    offsets={}
    for i,(key,im) in enumerate(images.items()):
        atlas.paste(im.resize((tile,tile),Image.Resampling.NEAREST),(i*tile,0));offsets[key]=i*tile
    result=[]
    for element in data["elements"]:
        low=np.array(element["from"]);size=np.array(element["to"])-low;m=np.eye(4)
        if "rotation" in element:
            r=element["rotation"];a=[0,0,0];a["xyz".index(r["axis"])]=math.radians(r["angle"])
            pivot=np.array(r["origin"]);m=translate(pivot)@rotation(a)@translate(-pivot)
            if r.get("rescale") and r["angle"]:
                s=np.ones(3)/math.cos(a["xyz".index(r["axis"])]);s["xyz".index(r["axis"])]=1
                scale=np.diag([*s,1]);m=m@translate(pivot)@scale@translate(-pivot)
        center=(m@np.r_[low+size*.5,1])[:3]
        for name,fd in element["faces"].items():
            points=np.array([low+np.array(bits)*size for bits in renderer.FACE_BITS[name]])
            points=(m@np.c_[points,np.ones(4)].T).T[:,:3]
            u1,v1,u2,v2=fd.get("uv",[0,0,16,16]);uv=np.array([[u1,v1],[u2,v1],[u2,v2],[u1,v2]],dtype=float)
            uv=np.roll(uv,fd.get("rotation",0)//90,axis=0);uv*=tile/16;uv[:,0]+=offsets[fd["texture"].lstrip("#")]
            f=face(points,uv,center)
            if f:result.append(f)
    return result,atlas,{"constructor_cuboids":len(data["elements"]),"bones":0}
def export(name,title,path,faces,tex,size,details,kind="参考模型"):
    objdir=OUT/"obj";objdir.mkdir(exist_ok=True);tex.save(objdir/(name+".png"))
    lines=[f"mtllib {name}.mtl","usemtl original_texture"];idx=1
    for points,uv,normal in faces:
        lines.extend("v "+" ".join(f"{x:.6f}" for x in p) for p in points)
        lines.extend(f"vt {p[0]/size[0]:.7f} {1-p[1]/size[1]:.7f}" for p in uv)
        lines.append("f "+" ".join(f"{idx+i}/{idx+i}" for i in range(4)));idx+=4
    (objdir/(name+".obj")).write_text("\n".join(lines)+"\n",encoding="utf-8")
    (objdir/(name+".mtl")).write_text(f"newmtl original_texture\nKd 1 1 1\nmap_Kd {name}.png\n",encoding="utf-8")
    vertex=[]
    for points,uv,normal in faces:
        for i in [0,1,2,0,2,3]:vertex.extend([*points[i],uv[i][0]/size[0],uv[i][1]/size[1],*normal])
    points=np.concatenate([f[0] for f in faces]);lo=points.min(0);hi=points.max(0)
    png=OUT/"previews"/(name+".png");png.parent.mkdir(exist_ok=True)
    renderer.raster(faces,tex,size,width=600,height=500,angle=1,fit=True).save(png)
    rel=str(path.relative_to(ROOT)).replace("\\","/")
    metadata={"id":name,"name":title,"kind":kind,"source":rel,"source_sha256":sha(path),
              "quads":len(faces),"triangles":len(faces)*2,"bounds":[lo.tolist(),hi.tolist()],**details}
    write(OUT/"meshes"/(name+".json"),{**metadata,"vertex_format":"x,y,z,u,v,nx,ny,nz","vertices":vertex})
    texture="data:image/png;base64,"+base64.b64encode((objdir/(name+".png")).read_bytes()).decode()
    return {**metadata,"vertices":[round(float(x),6) for x in vertex],"texture":texture}
def build_html(entries):
    template=(ROOT/"scripts/pubgmc-review-template.html").read_text(encoding="utf-8")
    (OUT/"review.html").write_text(template.replace("__MODELS_JSON__",json.dumps(entries,ensure_ascii=False,separators=(",",":"))),encoding="utf-8")
def export_detail(item, title):
    path=ROOT/item["model"]; data=json.loads(path.read_text(encoding="utf-8"))
    a=np.array(data["vertices"],dtype=float).reshape(-1,8)
    tex=Image.open(ROOT/item["texture"]).convert("RGBA")
    name=item["id"]+"_detail"; objdir=OUT/"obj"
    tex.save(objdir/(name+".png"))
    lines=[f"mtllib {name}.mtl","usemtl original_texture"]
    lines.extend("v "+" ".join(f"{x:.6f}" for x in row[:3]) for row in a)
    lines.extend(f"vt {row[3]:.7f} {1-row[4]:.7f}" for row in a)
    lines.extend("vn "+" ".join(f"{x:.6f}" for x in row[5:]) for row in a)
    lines.extend("f "+" ".join(f"{j}/{j}/{j}" for j in range(i+1,i+4)) for i in range(0,len(a),3))
    (objdir/(name+".obj")).write_text("\n".join(lines)+"\n",encoding="utf-8")
    (objdir/(name+".mtl")).write_text(f"newmtl original_texture\nKd 1 1 1\nmap_Kd {name}.png\n",encoding="utf-8")
    faces=[]
    for tri in a.reshape(-1,3,8):
        q=tri[[0,1,2,2]]
        n=tri[:,5:].mean(0); n/=max(np.linalg.norm(n),1e-10)
        faces.append((q[:,:3],q[:,3:5]*tex.size,n))
    renderer.raster(faces,tex,tex.size,width=600,height=500,angle=1,fit=True).save(OUT/"previews"/(name+".png"))
    metadata={"id":name,"name":title+" · 萌趣世界","kind":"萌趣世界","style_en":"Playful World","source":item["model"],
              "source_sha256":sha(path),"triangles":len(a)//3,"bounds":item["bounds"],
              "constructor_cuboids":0,"authoring":"Curved parametric surfaces, sewn fabric pouches, lathed metal/glass and tubular straps."}
    write(OUT/"meshes"/(name+".json"),{**metadata,"vertex_format":"x,y,z,u,v,nx,ny,nz","vertices":data["vertices"]})
    print(f"Rendered detailed {name}: {len(a)//3} triangles",flush=True)
    return {**metadata,"vertices":data["vertices"],"texture":"data:image/png;base64,"+base64.b64encode((objdir/(name+".png")).read_bytes()).decode()}
def sheet(entries,outfile,cols):
    rows=math.ceil(len(entries)/cols);im=Image.new("RGB",(cols*600,rows*560),(238,240,239));draw=ImageDraw.Draw(im);font=renderer.font(24,True)
    for i,e in enumerate(entries):
        x=i%cols*600;y=i//cols*560
        im.paste(Image.open(OUT/"previews"/(e["id"]+".png")),(x,y))
        draw.text((x+22,y+508),e["name"],font=font,fill=(35,42,35))
        geometry=f'{e["triangles"]:,} triangles' if e["kind"]=="萌趣世界" else f'{e["constructor_cuboids"]} cubes'
        draw.text((x+22,y+539),f'{geometry} | {e["kind"]}',font=renderer.font(15),fill=(80,85,80))
    im.save(OUT/outfile)
def main():
    entries=[]
    selections=[("plane","飞机 · 四发运输机","vehicles/ModelPlane.java","vehicle/plane.png",None),
                ("parachute","降落伞","ModelParachute.java","entity/parachute.png",None),
                *[(f"dacia_{c}","Dacia · "+n,"vehicles/ModelDacia.java",f"vehicle/dacia_{c}.png",None) for c,n in [("white","白色"),("blue","蓝色"),("orange","橙色"),("yellow","黄色"),("burned","烧毁")]],
                ("uaz","UAZ · 军绿","vehicles/ModelUAZ.java","vehicle/uaz.png",None),
                ("uaz_burned","UAZ · 烧毁","vehicles/ModelUAZ.java","vehicle/uaz_burned.png",None),
                ("airdrop","普通空投 · 落地箱体","ModelAirdrop.java","entity/airdrop.png","chute"),
                ("airdrop_falling","普通空投 · 带伞","ModelAirdrop.java","entity/airdrop.png",None),
                ("big_airdrop","大型空投 · 落地箱体","ModelBigAirdrop.java","entity/big_airdrop.png","chute"),
                ("big_airdrop_falling","大型空投 · 带伞","ModelBigAirdrop.java","entity/big_airdrop.png",None)]
    for name,title,model,texture,excluded in selections:
        path=MODELS/model;faces,size,details=java_model(path,excluded)
        tex=Image.open(ASSETS/"textures"/texture).convert("RGBA")
        entries.append(export(name,title,path,faces,tex,size,details))
    path=ASSETS/"models/block/player_crate.json";faces,tex,details=json_model(path,ASSETS)
    entries.append(export("player_crate","死亡盒子 · 原版橡木/云杉木贴图",path,faces,tex,tex.size,details))
    original=json.loads((OUT/"original-models.json").read_text(encoding="utf-8"))
    for item in original["items"]:
        path=ROOT/item["model"];faces,tex,details=json_model(path,RUNTIME)
        entries.append(export(item["id"],item["name"]+" · 像素硬核",path,faces,tex,tex.size,{**details,"style_en":"Pixel Hardcore"},"像素硬核"))
    detailed=OUT/"detailed-models.json"
    if detailed.exists():
        names={item["id"]:item["name"] for item in original["items"]}
        for item in json.loads(detailed.read_text(encoding="utf-8"))["items"]:
            entries.append(export_detail(item,names[item["id"]]))
    build_html(entries)
    sheet([e for e in entries if e["id"] in ("plane","parachute","dacia_white","uaz","airdrop","big_airdrop","player_crate","airdrop_falling","big_airdrop_falling")],"pubgmc-model-overview.png",3)
    sheet([e for e in entries if e["kind"]=="像素硬核"],"original-model-overview.png",4)
    if detailed.exists():sheet([e for e in entries if e["kind"]=="萌趣世界"],"detailed-model-overview.png",4)
    write(OUT/"review-manifest.json",{"models":[{k:v for k,v in e.items() if k not in ("vertices","texture")} for e in entries],
        "vanilla_texture_archive_sha256":sha(OUT/"downloads/minecraft-1.12.2-client.jar"),
        "all_vehicles_present_in_source":["ModelDacia","ModelUAZ"],"notes":["Review uses static neutral constructor pose; does not emulate animated wheels or propellers.",
        "Java transforms: parent * translation * Rz * Ry * Rx; exact Minecraft 1.12 ModelBox UV net and mirror.","All source model cubes are parsed and checked against constructor new ModelBox count.",
        "Air-drop landed views hide the exact chute subtree; complete falling variants also included.",
        "Death crate references original Minecraft 1.12.2 oak and spruce textures, fetched from Mojang official client archive."]})
    print(f"Generated {len(entries)} review models with PNG, OBJ and standalone WebGL review.html")
if __name__=="__main__":main()
