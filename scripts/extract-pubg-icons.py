"""Copy flat item icons and fetch pinned official PUBG item icons.

Model texture atlases are deliberately excluded from GUI icon candidates.
Original bytes and SHA-256 provenance are preserved; no image generation.
"""
from pathlib import Path
import concurrent.futures, hashlib, json, shutil, urllib.request, time
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "assets-workbench/pubgmc-reference"
SRC = OUT / "source/PUBGMC-f8c341eb784425b1bf7e015f36b851e760ab2999"
ASSETS = SRC / "src/main/resources/assets/pubgmc"
DEST = ROOT / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg/textures/pubg_original"
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p, value):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2)+"\n",encoding="utf-8")
entries=[]
for model in sorted((ASSETS/"models/item").glob("*.json")):
    data=json.loads(model.read_text(encoding="utf-8"))
    texture=data.get("textures",{}).get("layer0","")
    if data.get("parent") not in ("item/generated","item/handheld") or not texture.startswith("pubgmc:"): continue
    source=ASSETS/"textures"/(texture.split(":")[1]+".png")
    if not source.exists(): continue
    target=DEST/"pubgmc"/source.name
    target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    entries.append({"source":"PUBGMC","source_model":str(model.relative_to(SRC)).replace("\\","/"),
                    "source_path":str(source.relative_to(SRC)).replace("\\","/"),
                    "item_name":model.stem,"texture":"tacz_pubg:pubg_original/pubgmc/"+source.stem,
                    "sha256":digest(target),"candidate_name":model.stem,"genuine_flat_icon":True})
tree=json.loads((OUT/"downloads/pubg-api-assets-tree.json").read_text(encoding="utf-8-sig"))
sha=tree["sha"]
paths=[item["path"] for item in tree["tree"] if item["path"].startswith("Assets/Item/") and item["path"].endswith(".png")]
aliases={"Item_Heal_Bandage_C":"bandage","Item_Heal_FirstAid_C":"first_aid_kit",
         "Item_Heal_MedKit_C":"med_kit","Item_Boost_EnergyDrink_C":"energy_drink",
         "Item_Boost_PainKiller_C":"painkiller","Item_Boost_AdrenalineSyringe_C":"adrenaline_syringe",
         "Item_Back_E_01_Lv1_C":"backpack_1","Item_Back_F_01_Lv2_C":"backpack_2","Item_Back_C_01_Lv3_C":"backpack_3",
         "Item_Head_E_01_Lv1_C":"helmet_1","Item_Head_F_01_Lv2_C":"helmet_2","Item_Head_G_01_Lv3_C":"helmet_3",
         "Item_Armor_E_01_Lv1_C":"vest_1","Item_Armor_D_01_Lv2_C":"vest_2","Item_Armor_C_01_Lv3_C":"vest_3",
         "Item_Weapon_Grenade_C":"frag_grenade","Item_Weapon_FlashBang_C":"stun_grenade",
         "Item_Weapon_SmokeBomb_C":"smoke_grenade","Item_Weapon_Molotov_C":"molotov",
         "Item_Weapon_C4_C":"c4","Item_Weapon_BluezoneGrenade_C":"blue_zone_grenade",
         "Item_Weapon_StickyGrenade_C":"sticky_bomb","Item_Weapon_DecoyGrenade_C":"decoy_grenade"}
def fetch(path):
    rel=path.removeprefix("Assets/Item/").lower()
    target=DEST/"official"/rel
    source=OUT/"official"/path
    url=f"https://raw.githubusercontent.com/pubg/api-assets/{sha}/{path}"
    for attempt in range(4):
        try:
            if not source.exists():
                source.parent.mkdir(parents=True,exist_ok=True)
                with urllib.request.urlopen(url,timeout=45) as res: source.write_bytes(res.read())
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
            return {"source":"PUBG official api-assets","source_path":path,"source_url":url,
                    "item_name":Path(path).stem,"texture":"tacz_pubg:pubg_original/official/"+rel[:-4],
                    "sha256":digest(target),"candidate_name":aliases.get(Path(path).stem,Path(path).stem),
                    "genuine_flat_icon":True,
                    "known_mod_ids":(["tacz_pubg:"+aliases[Path(path).stem]] if Path(path).stem in aliases and aliases[Path(path).stem]!="decoy_grenade" else [])}
        except Exception:
            if attempt==3: raise
            time.sleep(1+attempt)
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    for item in pool.map(fetch,paths): entries.append(item)
write(OUT/"icon-mapping-candidates.json",{"pubgmc_commit":"f8c341eb784425b1bf7e015f36b851e760ab2999","official_commit":sha,
    "notes":["models/items/*_model.png and blocks/energy.png are UV atlases and are excluded.",
             "candidate_name is an identity hint, not automatic matching to unrelated guns.",
             "All copied textures preserve original bytes; original dimensions are retained."],"items":entries})
write(DEST/"sources.json",{"official_commit":sha,"pubgmc_commit":"f8c341eb784425b1bf7e015f36b851e760ab2999","items":entries})
print(f"Copied {len(entries)} genuine icon mappings ({len(paths)} official assets).",flush=True)
