"""Build exact, reviewable inventory mappings. No fuzzy matching or changed source pixels."""
import json
import struct
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "neoforge-1.21.1/src/main/resources"
manifest = json.loads((ROOT / "assets-workbench/pubgmc-reference/icon-mapping-candidates.json").read_text(encoding="utf-8"))
official = {x["item_name"].lower(): x for x in manifest["items"] if x["source"] != "PUBGMC"}
icons = {}


def add(kind, ids, original):
    source = official[original.lower()]
    texture = source["texture"].replace("tacz_pubg:", "tacz_pubg:textures/") + ".png"
    path = RES / "assets/tacz_pubg" / texture.split(":", 1)[1]
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    width, height = struct.unpack(">II", data[16:24])
    for item_id in ids.split():
        key = f"{kind}:{item_id}"
        assert key not in icons, key
        icons[key] = {"texture": texture, "width": width, "height": height,
                      "source_item": source["item_name"], "sha256": source["sha256"]}


for source in manifest["items"]:
    if source["source"] != "PUBGMC":
        for own_id in source.get("known_mod_ids", []):
            add("item", own_id, source["item_name"])
add("item", "tacz_pubg:parachute", "Item_Back_B_01_StartParachutePack_C")
add("item", "minecraft:crossbow", "Item_Weapon_Crossbow_C")

# Named counterparts in the installed packs; cosmetic/modernized variants use their base firearm.
# Similar-sounding weapons with a different identity (SCAR-H, AK74, MP5A5, etc.) stay unchanged.
guns = {
    "AK47": "tacz:ak47 cib:cs_ak47 ccrp:ak47_spent_bullet gz:black_market_akm suffuse:saddam_golden_ak",
    "ACE32": "cib:galilace32",
    "AUG": "tacz:aug ccrp:aug_a3s ccrp:aug_a3_m2kit gz:auga3cqc",
    "Berreta686": "cib:686 tacz:db_long",
    "BerylM762": "cbrg:beryl_m762 cbrg:beryl_m762_2",
    "BizonPP19": "cib:pp19 suffuse:pp19 gz:enhanced_pp19 gz:pp19 gz:pp19_01 gz:tactical_pp19_01",
    "DP28": "classicr:dp28",
    "Dragunov": "cib:svd suffuse:svd",
    "FAMASG2": "rainforest:famas",
    "FNFal": "cbrg:slr tacz:fn_fal cib:fal rainforest:fal classicr:fn_fal_tac",
    "Groza": "cbrg:groza cib:ots14",
    "HK416": "tacz:hk416d ccrp:hk416 ccrp:hk416_sopmod ccrp:hk416a8 classicr:hk416_a5",
    "K2": "cib:k2",
    "Kar98k": "tacz:kar98 classicr:kar98",
    "M16A4": "tacz:m16a4 cib:m16a4",
    "M249": "tacz:m249 ccrp:m249_saw",
    "M24": "classicr:m24_renewed",
    "MG3": "cib:mg3",
    "Mini14": "cib:mini",
    "Mk14": "tacz:mk14",
    "Mk47Mutant": "classicr:mk47",
    "Mosin": "hamster:mosin91 hamster:mosin9130",
    "MP5K": "ccrp:mp5k ccrp:mp5k_pdw",
    "MP9": "classicr:mp9 ccrp:mp9_modernrized ccrp:mp9_thunder",
    "OriginS12": "cib:origin12",
    "P90": "tacz:p90 origin:p90 ccrp:p90_effen_90 ccrp:p90_paw ccrp:p90_shround_s",
    "QBU88": "cib:qbu88",
    "QBZ95": "tacz:qbz_95 cib:qbz951 cibs:qbz951_asiimov suffuse:qbz951",
    "SCAR-L": "tacz:scar_l ccrp:scar_16s",
    "SKS": "tacz:sks_tactical hamster:sks",
    "Saiga12": "gz:sasg_12 gz:tactical_sasg_12k",
    "Thompson": "classicr:m1a1_smg",
    "UMP": "tacz:ump45 suffuse:ump45",
    "UZI": "tacz:uzi ccrp:uzi45",
    "Vector": "tacz:vector45 gz:1st_vector45acp",
    "Win1894": "hamster:win1894",
    "Winchester": "tacz:m870",
    "DesertEagle": "tacz:deagle tacz:deagle_golden",
    "FlareGun": "hamster:flaregun",
    "G18": "cib:g18c classicr:glock_18",
    "M1911": "tacz:m1911 ccrp:chisato_m1911",
    "M79": "suffuse:m79",
    "M9": "tacz:m9a4 classicr:m92fs",
    "NagantM1895": "hamster:nagantm1895 suffuse:m1895",
    "Rhino": "tacz:rhino357",
    "Sawnoff": "tacz:db_short",
}
for name, ids in guns.items():
    add("gun", ids, f"Item_Weapon_{name}_C")

ammo = {
    "12Guage": "tacz:12g cbrg:ammo_12g",
    "45ACP": "tacz:45acp cbrg:ammo_45acp",
    "556mm": "tacz:556x45 cbrg:ammo_556 ccrp:556x45_m855 ccrp:556x45_m995",
    "762mm": "tacz:762x39 tacz:308 tacz:762x54 tacz:762x54r tacz:762x39copper cbrg:ammo_762",
    "9mm": "tacz:9mm cbrg:ammo_9mm",
    "57mm": "tacz:57x28 cbrg:ammo_57mm",
    "40mm": "tacz:40mm",
    "Flare": "hamster:flares_ammo",
}
for name, ids in ammo.items():
    add("ammo", ids, f"Item_Ammo_{name}_C")

attachments = {
    "Upper_PM2_01": "cbrg:15x_scope",
    "Upper_Aimpoint": "cbrg:2x_scope",
    "Upper_Scope3x": "cbrg:3x_scope",
    "Upper_ACOG_01": "cbrg:4x_scope tacz:scope_acog_ta31",
    "Upper_Scope6x": "cbrg:6x_scope",
    "Upper_CQBSS": "cbrg:8x_scope tacz:scope_standard_8x",
    "Upper_Holosight": "cbrg:holographic_sight tacz:sight_552",
    "Upper_DotSight_01": "cbrg:red_dot tacz:sight_t1",
    "SideRail_DotSight_RMR": "cbrg:canted_sight",
    "Lower_AngledForeGrip": "cbrg:angled_foregrip",
    "Lower_Foregrip": "cbrg:vertical_foregrip",
    "Lower_HalfGrip": "cbrg:halfgrip",
    "Lower_ThumbGrip": "cbrg:thumbgrip",
    "Lower_LightweightForeGrip": "cbrg:lightweight_grip",
    "Lower_LaserPointer": "cbrg:laser_sight",
    "Stock_SniperRifle_CheekPad": "cbrg:cheek_pad",
    "Stock_AR_Composite": "tacz:stock_tactical_ar",
    "Muzzle_Compensator_Large": "cbrg:compensator_rifle",
    "Muzzle_FlashHider_Large": "cbrg:flash_hider_rifle",
    "Muzzle_Suppressor_Large": "cbrg:suppressor_rifle",
    "Muzzle_Suppressor_Small": "cbrg:suppressor_pistol",
    "Muzzle_Suppressor_SniperRifle": "cbrg:suppressor_sr",
    "Muzzle_Choke": "tacz:muzzle_choke_sg",
    "Magazine_Extended_Large": "cbrg:extended_mag_rifle tacz_pubg_workbench:extended_mag_rifle",
    "Magazine_QuickDraw_Large": "cbrg:quickdraw_mag_rifle tacz_pubg_workbench:quickdraw_mag_rifle",
    "Magazine_ExtendedQuickDraw_Large": "cbrg:extended_quickdraw_mag_rifle tacz_pubg_workbench:extended_quickdraw_mag_rifle",
    "Magazine_Extended_Medium": "tacz_pubg_workbench:extended_mag_smg",
    "Magazine_QuickDraw_Medium": "tacz_pubg_workbench:quickdraw_mag_smg",
    "Magazine_ExtendedQuickDraw_Medium": "tacz_pubg_workbench:extended_quickdraw_mag_smg",
    "Magazine_Extended_SniperRifle": "tacz_pubg_workbench:extended_mag_sniper",
    "Magazine_QuickDraw_SniperRifle": "tacz_pubg_workbench:quickdraw_mag_sniper",
    "Magazine_ExtendedQuickDraw_SniperRifle": "tacz_pubg_workbench:extended_quickdraw_mag_sniper",
}
for name, ids in attachments.items():
    add("attachment", ids, f"Item_Attach_Weapon_{name}_C")

for lr_id, name in {"m67": "Grenade", "rgn": "Grenade", "smoke_grenade": "SmokeBomb",
                    "flash_grenade": "FlashBang", "molotov": "Molotov", "c4": "C4"}.items():
    add("throwable", "lrtactical:" + lr_id, f"Item_Weapon_{name}_C")

target = RES / "assets/tacz_pubg/original_icons.json"
target.write_text(json.dumps({"schema": 1, "defaultEnabled": False,
    "sourceManifest": "textures/pubg_original/sources.json",
    "notes": ["GUI only. Unknown IDs keep their models.",
              "Explicit base-firearm mappings include cosmetic/modernized variants; no fuzzy runtime matching."],
    "icons": dict(sorted(icons.items()))}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Wrote {len(icons)} exact mappings: {dict(Counter(x.split(':')[0] for x in icons))}")
