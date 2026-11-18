"""Build cuboid item models and their procedural materials.

The geometry and textures are generated together
so the model faces use the matching atlas.
"""
from pathlib import Path
import json, random, hashlib
from PIL import Image, ImageDraw
ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/"neoforge-1.21.1/src/main/resources/assets/tacz_pubg"
OUT=ROOT/"assets-workbench/pubgmc-reference"
NAMES={"bandage":"绷带","first_aid_kit":"急救包","med_kit":"医疗箱","energy_drink":"能量饮料",
       "painkiller":"止痛药","adrenaline_syringe":"肾上腺素注射器",
       "backpack_1":"一级背包","backpack_2":"二级背包","backpack_3":"三级背包",
       "frag_grenade":"破片手榴弹","stun_grenade":"震爆弹","smoke_grenade":"烟雾弹","molotov":"燃烧瓶",
       "sticky_bomb":"黏性炸弹","c4":"C4炸药","blue_zone_grenade":"蓝圈手榴弹",
       "helmet_1":"一级头盔","helmet_2":"二级头盔","helmet_3":"三级头盔",
       "vest_1":"一级防弹衣","vest_2":"二级防弹衣","vest_3":"三级防弹衣"}
# 4x4 original material tiles. Pigments are based on reference colors, not pixels.
COLORS=[(63,76,48),(194,175,134),(228,226,208),(175,39,34),(27,52,103),(129,139,133),
        (34,37,34),(183,110,29),(38,115,133),(101,106,88),(122,116,97),(78,72,57),
        (157,151,132),(57,64,47),(203,182,94),(146,158,149)]
def box(es,name,lo,hi,mat=0,rotation=None):
    x=(mat%4)*4;y=(mat//4)*4
    uv=[x+.10,y+.10,x+3.90,y+3.90]
    e={"name":name,"from":lo,"to":hi,"faces":{f:{"uv":uv,"texture":"#0"} for f in ["north","south","east","west","up","down"]}}
    if rotation:e["rotation"]=rotation
    es.append(e)
def rot(angle=45,axis="y",origin=(8,8,8)):
    return {"angle":angle,"axis":axis,"origin":list(origin)}
def octagon(es,name,cx,cz,y0,y1,r,mat):
    # Two central rectangles plus four inset diagonal strips form a convex
    # octagon. A full rotated square would protrude and create a star shape.
    s=r*.41421356
    box(es,name+"_x",[cx-r,y0,cz-s],[cx+r,y1,cz+s],mat)
    box(es,name+"_z",[cx-s,y0,cz-r],[cx+s,y1,cz+r],mat)
    for axis in range(2):
        for sign in [-1,1]:
            near,far=(s,r) if sign==1 else (-r,-s)
            lo=[cx-s,y0,cz+near];hi=[cx+s,y1,cz+far]
            if axis:lo=[cx+near,y0,cz-s];hi=[cx+far,y1,cz+s]
            box(es,name+"_diagonal",lo,hi,mat,rot(45,"y",(cx,8,cz)))
def material(identifier):
    im=Image.new("RGB",(64,64));p=im.load();rng=random.Random(identifier)
    for i,c in enumerate(COLORS):
        for y in range(16):
            for x in range(16):
                n=rng.randrange(-8,9)
                if i in (0,1,9,10,11,12,13):
                    n+=3 if (x+y)%3==0 else -2
                if i==12: n+=(-24 if ((x//4+y//3)%4==0) else 13 if ((x//3-y//4)%5==1) else 0)
                p[i%4*16+x,i//4*16+y]=tuple(max(0,min(255,v+n)) for v in c)
    return im
def create(identifier):
    e=[];b=lambda n,l,h,m=0,r=None:box(e,n,l,h,m,r)
    if identifier=="bandage":
        b("sealed_olive_wrapper",[2.5,4.5,6.2],[13.5,11.5,9.5],0)
        b("upper_crimp",[1.8,11,6.5],[14.2,12.1,9.1],13)
        b("lower_crimp",[1.8,3.9,6.5],[14.2,5,9.1],13)
        b("left_fold",[1.8,4.5,6.5],[2.7,11.5,9.1],0)
        b("right_fold",[13.3,4.5,6.5],[14.2,11.5,9.1],0)
        b("paper_label",[3.5,5.2,9.5],[12.5,10.9,9.61],1)
        b("blue_label_edge",[3.5,5.2,9.62],[5.1,10.9,9.72],8)
        for y in [6.2,7,8.7,9.5]:b("printed_label_line",[5.8,y,9.63],[11.8,y+.15,9.74],6)
        b("orange_seal",[10.5,5.7,9.64],[11.8,7,9.75],7)
    elif identifier=="first_aid_kit":
        b("soft_camo_pouch",[2.5,4,5.7],[13.5,12,9.9],12)
        b("bevel_top",[3.3,12,6.1],[12.7,12.7,9.5],1)
        b("bevel_bottom",[3.3,3.3,6.1],[12.7,4,9.5],10)
        b("left_seam",[1.9,4.8,6.1],[2.5,11.2,9.5],10)
        b("right_seam",[13.5,4.8,6.1],[14.1,11.2,9.5],10)
        b("rear_zipper",[2.6,7.6,5.5],[13.4,8,5.8],6)
        b("medical_patch",[5,5,9.9],[11,11.1,10.08],2)
        b("red_H_left",[5.8,5.7,10.08],[7.1,10.4,10.25],3)
        b("red_H_right",[8.9,5.7,10.08],[10.2,10.4,10.25],3)
        b("red_H_bar",[7.1,7.4,10.08],[8.9,8.7,10.25],3)
    elif identifier=="med_kit":
        b("opened_medical_case_base",[1.6,1.4,4],[14.4,3.3,13.1],13)
        b("case_front_rim",[1.6,3.3,12.2],[14.4,4.1,13.1],0)
        b("case_left_rim",[1.6,3.3,4],[2.3,4.1,12.2],0)
        b("case_right_rim",[13.7,3.3,4],[14.4,4.1,12.2],0)
        b("raised_case_lid",[1.6,3.3,3.1],[14.4,14,4.3],13)
        b("lid_top_edge",[2.3,14,3.2],[13.7,14.7,4.2],6)
        b("medical_kit_green_pouch",[3,3.4,7.7],[9.1,5,11.8],0)
        b("pouch_label",[4.1,5,8.7],[8,5.15,10.9],1)
        for i,m in enumerate([2,7,4]):b("folded_dressings",[9.6,3.5+i*.45,8],[12.9,3.9+i*.45,11.3],m)
        for x in [3.3,7.1,10.9]:
            b("lid_instrument_pocket",[x,5,4.3],[x+2.5,9.7,6.1],0)
            b("pocket_flap",[x-.15,9.1,4.4],[x+2.65,9.9,6.3],13)
            b("pocket_webbing",[x,6.8,6.1],[x+2.5,7.4,6.3],11)
        for x in [3.9,11.6]:
            b("vial",[x,10,4.3],[x+1.1,12.5,5.7],8)
            b("vial_cap",[x-.1,12.5,4.2],[x+1.2,13,5.8],5)
        b("handle_left",[5.5,14.7,3.3],[6.2,15.5,4.1],6)
        b("handle_right",[9.8,14.7,3.3],[10.5,15.5,4.1],6)
        b("handle_top",[6.2,15.1,3.3],[9.8,15.7,4.1],6)
    elif identifier=="energy_drink":
        octagon(e,"blue_can",8,8,3.2,12.8,3,4)
        octagon(e,"lower_silver_rim",8,8,2.6,3.2,3,5)
        octagon(e,"shoulder",8,8,12.8,13.3,2.7,5)
        octagon(e,"top_rim",8,8,13.3,13.6,2.6,5)
        b("pull_tab",[7.4,13.6,7.2],[8.7,13.82,8.7],6)
        b("cream_emblem",[6.1,5,10.95],[9.9,10.7,11.06],14)
        b("emblem_inset",[6.5,5.5,11.06],[9.5,10.2,11.17],4)
        b("label_silhouette_head",[7.3,8.6,11.17],[8.7,9.8,11.28],2)
        b("label_silhouette_body",[6.9,6,11.17],[9.1,8.6,11.28],2)
    elif identifier=="painkiller":
        octagon(e,"amber_pill_bottle",8,8,2.5,12.1,2.9,7)
        octagon(e,"white_paper_wrap",8,8,4.2,10.7,2.96,2)
        octagon(e,"blue_label_header",8,8,10.4,10.9,2.97,8)
        octagon(e,"neck",8,8,12.1,12.7,2.1,7)
        octagon(e,"gray_childproof_cap",8,8,12.5,13.6,3.1,5)
        octagon(e,"cap_top",8,8,13.6,14.1,2.6,15)
        for y in [5.3,6.1,7,8.5,9.2]:b("printed_label_line",[6.5,y,10.96],[9.5,y+.17,11.08],6)
    elif identifier=="adrenaline_syringe":
        b("barrel_housing",[7,4.4,7],[9,11.4,9],6)
        b("glass_window",[7.3,4.8,8.99],[8.7,10.9,9.12],2)
        b("fluid",[7.6,5.2,9.12],[8.4,8,9.22],8)
        for y in [6,7,8,9,10]:b("graduation",[8.4,y,9.13],[8.8,y+.1,9.24],6)
        b("blue_needle_hub",[7.3,11.4,7.3],[8.7,12.3,8.7],8)
        b("needle",[7.88,12.3,7.88],[8.12,15.9,8.12],5)
        b("plunger",[7.8,1.7,7.8],[8.2,4.4,8.2],5)
        b("plunger_press",[6.8,1.3,7.1],[9.2,1.7,8.9],6)
        for x in [4.3,9.4]:
            b("finger_ring_top",[x,4,7.7],[x+2.3,4.4,8.3],5)
            b("finger_ring_bottom",[x,2.3,7.7],[x+2.3,2.7,8.3],5)
            b("finger_ring_outer",[x,2.7,7.7],[x+.4,4,8.3],5)
            b("finger_ring_inner",[x+1.9,2.7,7.7],[x+2.3,4,8.3],5)
    elif identifier.startswith("backpack_"):
        level=int(identifier[-1]);mat=[1,0,12][level-1];width=[8,10,12][level-1];depth=[4,5,6][level-1]
        left=8-width/2;right=8+width/2;front=6+depth
        b("main_fabric_body",[left,2,6],[right,12.7,front],mat)
        b("rounded_top_step",[left+.7,12.7,6.4],[right-.7,14,front-.4],mat)
        b("rounded_crown",[left+1.5,14,6.8],[right-1.5,14.8,front-.8],mat)
        b("reinforced_bottom",[left+.2,1.4,6.2],[right-.2,3,front-.2],11)
        for x in [left+.7,right-1.6]:
            b("shoulder_strap_vertical",[x,3.5,3.9],[x+.9,12,4.6],11)
            b("shoulder_strap_top",[x,12,4.5],[x+.9,13,6.3],mat,rot(-22.5,"x",(8,12,6)))
            b("shoulder_strap_bottom",[x,2.9,4.4],[x+.9,3.8,6.3],mat,rot(22.5,"x",(8,3,6)))
        b("carry_handle_left",[6.2,14.8,7],[6.9,15.7,7.8],11)
        b("carry_handle_right",[9.1,14.8,7],[9.8,15.7,7.8],11)
        b("carry_handle_top",[6.9,15.1,7],[9.1,15.8,7.8],11)
        if level==1:
            b("single_front_pocket",[left+.7,3.5,front],[right-.7,8.5,front+1.1],1)
            b("vertical_front_strap",[7.5,6,front+1.15],[8.5,12.4,front+1.4],11)
            for y in [3.7,5,6.3]:b("lower_webbing",[left+.5,y,front+1.1],[right-.5,y+.45,front+1.35],11)
        elif level==2:
            b("large_front_pocket",[left+.5,3.1,front],[right-.5,8.8,front+1.3],0)
            b("front_pocket_flap",[left+.4,8.5,front+.2],[right-.4,9.2,front+1.6],10)
            b("top_flap",[left+.2,11.5,front-.2],[right-.2,13.2,front+.6],0)
            b("vertical_buckle_strap",[7.5,5,front+1.4],[8.5,12.6,front+1.65],11)
            b("buckle",[7.25,7.9,front+1.65],[8.75,9.1,front+1.82],5)
        else:
            b("large_tactical_front_pocket",[left+.5,3.1,front],[right-.5,10.6,front+1],12)
            for y in [3.8,5.1,6.4,7.7,9]:
                for x in [left+.7,left+3.4,left+6.1,left+8.8]:
                    b("MOLLE_webbing",[x,y,front+1],[min(x+2.2,right-.5),y+.55,front+1.25],10)
            for x in [left-.7,right]:b("side_pocket",[x,3.5,7],[x+.7,8.6,front-.6],12)
            b("top_zipper_seam",[left+.5,11.1,front-.02],[right-.5,11.35,front+.14],6)
    elif identifier in ("frag_grenade","blue_zone_grenade"):
        mat=0 if identifier=="frag_grenade" else 4
        octagon(e,"grenade_body",8,8,3,10,3.4,mat)
        octagon(e,"shoulder",8,8,10,11.2,2.4,mat)
        b("fuse",[7.1,11.2,7.1],[8.9,12.5,8.9],5)
        b("safety_lever",[8.7,5,7.4],[9.25,12.7,8.6],6,rot(-22.5,"z",(9,12,8)))
        for y in [4.7,6.8,8.9]:octagon(e,"body_groove",8,8,y,y+.3,3.45,6 if mat==0 else 8)
        b("pin_ring_top",[4.5,12.5,7.8],[7.2,12.8,8.2],5)
        b("pin_ring_outer",[4.5,10.8,7.8],[4.8,12.5,8.2],5)
        b("pin_ring_bottom",[4.8,10.5,7.8],[7.2,10.8,8.2],5)
    elif identifier in ("stun_grenade","smoke_grenade"):
        octagon(e,"metal_cylinder",8,8,2.8,11.8,2.8,5 if identifier=="stun_grenade" else 0)
        octagon(e,"top_fuse",8,8,11.8,13,1.8,6)
        for y in [4,6,8,10]:
            octagon(e,"band",8,8,y,y+.35,2.86,6 if identifier=="stun_grenade" else 2)
        b("long_safety_spoon",[10.4,5,7.4],[10.9,13.4,8.6],6)
        b("top_spoon",[7.4,13,7.4],[10.9,13.5,8.6],6)
        if identifier=="smoke_grenade":b("smoke_marking",[6.5,6.4,10.8],[9.5,9.3,10.93],2)
    elif identifier=="molotov":
        octagon(e,"glass_bottle",8,8,1.8,10,2.6,13)
        octagon(e,"bottle_shoulder",8,8,10,11.4,1.8,0)
        octagon(e,"bottle_neck",8,8,11.4,14,1.05,13)
        b("cloth_wick",[7.6,13.7,7.6],[8.4,16,8.4],1,rot(22.5,"z",(8,14,8)))
        b("paper_bottle_label",[6.5,4.5,10.6],[9.5,8.8,10.72],1)
    elif identifier.startswith("helmet_"):
        level=int(identifier[-1]);mat=[0,12,6][level-1]
        b("crown",[3,10,3],[13,14,13],mat)
        b("rounded_top",[4,14,4],[12,15,12],mat)
        b("left_shell",[2.5,5,3],[4,11,13],mat)
        b("right_shell",[12,5,3],[13.5,11,13],mat)
        b("rear_shell",[4,4,3],[12,11,4.5],mat)
        b("front_brim",[3,9.5,12.5],[13,10.3,14],mat)
        b("chinstrap_left",[3.2,2.5,8],[3.7,5,9],11)
        b("chinstrap_right",[12.3,2.5,8],[12.8,5,9],11)
        b("chinstrap_base",[3.7,2.5,8],[12.3,3,9],11)
        if level==3:
            b("face_visor_left",[3.6,4.2,13],[5,9.5,13.7],5)
            b("face_visor_right",[11,4.2,13],[12.4,9.5,13.7],5)
            b("face_visor_bottom",[5,4.2,13],[11,6.6,13.7],6)
            b("face_visor_top",[5,8.2,13],[11,9.5,13.7],6)
            for x in [2.3,12.8]:b("visor_hinge",[x,8,10],[x+.9,9.3,11.3],5)
    elif identifier.startswith("vest_"):
        level=int(identifier[-1]);mat=[6,9,0][level-1]
        b("front_plate",[3.4,2,9.4],[12.6,12.4,11.5],mat)
        b("rear_plate",[3.4,2,4.5],[12.6,12.4,6.5],mat)
        for x in [3.4,10.8]:b("shoulder_strap",[x,12.4,4.7],[x+1.8,14,11.3],mat)
        for x in [3,12]:b("waist_side_strap",[x,3.5,6.5],[x+1,6.5,9.5],11)
        b("chest_patch",[5.5,9.2,11.5],[10.5,11.6,11.7],11)
        for x in [4,6.8,9.6]:
            b("magazine_pouch",[x,3,11.5],[x+2.4,7.9,13],mat)
            b("pouch_closure",[x,7.5,11.6],[x+2.4,8.2,13.3],11)
        for y in [2.3,8.5]:b("horizontal_webbing",[3.5,y,11.5],[12.5,y+.4,11.8],11)
    elif identifier in ("sticky_bomb","c4"):
        b("explosive_charge",[3.1,3,5.1],[12.9,12.3,10.8],11 if identifier=="c4" else 7)
        for x in [4.1,10.9]:b("binding_tape",[x,2.9,5],[x+1,12.4,10.9],6)
        b("timer_housing",[5.7,6.3,10.8],[10.3,10.3,11.6],6)
        b("digital_display",[6.2,8.1,11.6],[9.8,9.7,11.72],8)
        for x in [6.5,7.5,8.5,9.5]:b("keypad_button",[x,6.9,11.6],[x+.5,7.4,11.82],5)
        b("red_wire",[3.6,12.3,7.1],[11.7,12.7,7.6],3)
    else:raise KeyError(identifier)
    return {"credit":"Original model geometry and texture; generated by build-pubg-medical-backpack-models.py",
            "texture_size":[64,64],"textures":{"0":f"tacz_pubg:item/{identifier}","particle":f"tacz_pubg:item/{identifier}"},
            "gui_light":"front","elements":e,
            "display":{"gui":{"rotation":[25,225,0],"translation":[0,0,0],"scale":[.85,.85,.85]},
                       "ground":{"rotation":[0,0,0],"translation":[0,3,0],"scale":[.5,.5,.5]},
                       "fixed":{"rotation":[0,0,0],"translation":[0,0,0],"scale":[1,1,1]},
                       "thirdperson_righthand":{"rotation":[0,0,0],"translation":[0,2,0],"scale":[.6,.6,.6]},
                       "firstperson_righthand":{"rotation":[0,-35,0],"translation":[0,1,0],"scale":[.7,.7,.7]},
                       "firstperson_lefthand":{"rotation":[0,35,0],"translation":[0,1,0],"scale":[.7,.7,.7]}}}
def main():
    entries=[]
    for identifier,name in NAMES.items():
        model=create(identifier)
        for e in model["elements"]:
            assert all(a<b for a,b in zip(e["from"],e["to"])),(identifier,e)
        jp=ASSET/"models/item"/f"{identifier}.json";jp.parent.mkdir(parents=True,exist_ok=True)
        tp=ASSET/"textures/item"/f"{identifier}.png";tp.parent.mkdir(parents=True,exist_ok=True)
        jp.write_text(json.dumps(model,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        material(identifier).save(tp)
        entries.append({"id":identifier,"name":name,"original_geometry":True,"original_texture":True,
                        "cuboids":len(model["elements"]),"model":str(jp.relative_to(ROOT)).replace("\\","/"),
                        "texture":str(tp.relative_to(ROOT)).replace("\\","/"),
                        "model_sha256":hashlib.sha256(jp.read_bytes()).hexdigest()})
    (OUT/"original-models.json").write_text(json.dumps({"style_zh":"像素硬核","style_en":"Pixel Hardcore","reference":"Reference images guide shape and color; geometry and textures are authored separately.","items":entries},ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(f"Wrote {len(entries)} original 3D models ({sum(x['cuboids'] for x in entries)} cuboids).")
if __name__=="__main__":main()
