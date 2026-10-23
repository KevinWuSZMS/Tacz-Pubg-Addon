"""Build the mod's scope policy and the illustrated, searchable audit table.

Reads frozen, local inventories only. Never rewrites source gun packs or saves.
"""
from __future__ import annotations

import collections
import csv
import hashlib
import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "docs/gun-balance/gun-assets.json"
INVENTORY = ROOT / "docs/compatibility/compatibility-inventory.json"
COMPARISON = ROOT / "docs/pubg-weapons/cbrg-0.4.8-comparison.json"
RESEARCH = ROOT / "logs/pubg-scope-rules-research.json"
RESOURCE = ROOT / "neoforge-1.21.1/src/main/resources/data/tacz_pubg/gun_balance/pubg_scope_policy.json"
OUT = ROOT / "docs/compatibility"
SOURCE_DATE = "2026-10-18"

SCOPES = [
    ("cbrg:red_dot", "红点"),
    ("cbrg:holographic_sight", "全息"),
    ("cbrg:2x_scope", "2倍"),
    ("cbrg:3x_scope", "3倍"),
    ("cbrg:4x_scope", "4倍"),
    ("cbrg:6x_scope", "6倍"),
    ("cbrg:8x_scope", "8倍"),
    ("cbrg:15x_scope", "15倍"),
    ("cbrg:canted_sight", "侧瞄"),
]
SCOPE_IDS = [s[0] for s in SCOPES]
LABELS = dict(SCOPES)
LOW = SCOPE_IDS[:2]
MEDIUM = SCOPE_IDS[:6]
LONG = SCOPE_IDS[:8]
CANTED = SCOPE_IDS[8]
CAT_LABELS = {
    "AR": "突击／战斗步枪", "DMR": "精确射手步枪", "SR": "狙击步枪",
    "SMG": "冲锋枪", "LMG": "机枪", "SG": "霰弹枪", "PISTOL": "手枪", "LAUNCHER": "发射器",
}
TYPE_CAT = {"rifle": "AR", "sniper": "SR", "smg": "SMG", "mg": "LMG", "shotgun": "SG", "pistol": "PISTOL", "rpg": "LAUNCHER"}

# Fixed local CBRG comparison distinguishes author-intended names from model
# fidelity. Other packs get family or category inferences, never an exact claim.
FAMILY_MAP = {
    "cib:m16a4": ("M16A4", "AR"),
    "tacz:kar98": ("Kar98k", "SR"),
    "classicr:m24_renewed": ("M24", "SR"),
    "rainforest:rpd": ("RPD", "LMG"),
    "classicr:dp28": ("DP-28", "LMG"),
    "cib:pp19": ("PP-19 Bizon", "SMG"),
    "tacz:m1911": ("P1911", "PISTOL"),
    "cib:qbu88": ("QBU", "DMR"),
    "hamster:mosin9130": ("Mosin Nagant", "SR"),
    "ccrp:mp9_modernrized": ("MP9", "SMG"),
    "ccrp:mp9_thunder": ("MP9", "SMG"),
    "suffuse:ump45": ("UMP", "SMG"),
    "ccrp:vector10": ("Vector", "SMG"),
    "gz:1st_vector45acp": ("Vector", "SMG"),
}
DMR_IDS = {
    "ccrp:aug_a3_dmr", "ccrp:cslr_44", "ccrp:hk417", "ccrp:m110", "ccrp:m305a", "ccrp:m38_spr",
    "ccrp:m39_emr", "ccrp:qbu_191", "ccrp:sr25", "ccrp:troy_m14_sass", "cib:qbu191", "cib:qbu88",
    "cib:svd", "classicr:scar_mk20", "classicr:spr_15", "gz:black_market_t81sr", "gz:classic_m1a",
    "gz:t81sr", "hamster:sks", "rainforest:hk_spg1", "suffuse:qbu191",
}

SOURCES = [
    {"id": "pubg20_1", "title": "PUBG 官方更新 20.1：M16A4/Mk47 的 8、15 倍镜；O12 的 2–6 倍镜", "url": "https://pubg.com/en/news/1730?category=patch_notes", "kind": "official"},
    {"id": "pubg15_2", "title": "PUBG 官方更新 15.2：Win94/VSS 的固定瞄具", "url": "https://pubg.com/en-ca/news/1725", "kind": "official"},
    {"id": "pubg13_2", "title": "PUBG 官方更新 13.2：P90 的不可拆卸预装配件", "url": "https://pubg.com/en/news/1720", "kind": "official"},
    {"id": "pubg37_1", "title": "PUBG 官方更新 37.1：移除侧瞄", "url": "https://www.pubg.com/en/news/9002", "kind": "official"},
    {"id": "cbrg0_4_8", "title": "Classic BattleRoyale Gun 0.4.8 固定发行档", "url": "https://www.curseforge.com/minecraft/customization/tacz-classic-battleroyale-gun/files/7589555", "kind": "pack_author"},
]


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def classify(g, mapped):
    gid = g["id"]
    if gid in mapped:
        m = mapped[gid]
        return m["category"], m["pubgName"], m["basis"], m["explanation"]
    if gid in FAMILY_MAP:
        name, cat = FAMILY_MAP[gid]
        return cat, name, "same_family_inference", "其他枪包同名／同族型号类推；不是 CBRG 逐项复刻证明。"
    if gid in DMR_IDS:
        return "DMR", None, "role_inference", "本地型号／名称指向精确射手角色，按 PUBG DMR 类推；非官方逐枪许可。"
    return TYPE_CAT[g["type"]], None, "category_inference", "非 PUBG 或未明确对应的型号，按 TaCZ 枪类保守类推；非官方逐枪许可。"


def build_rules():
    # Historical canted support is maintained deliberately at the user's request.
    # The explicit legacy weapon list is completed from the research audit.
    return {
        "ar_standard": {"description": "普通 AR：红点、全息、2–6 倍；侧瞄按历史白名单", "allowedScopes": MEDIUM, "sourceIds": ["official_2018_limits", "official_17_1", "official_22_2"]},
        "ar_long": {"description": "M16A4 / Mk47 Mutant：额外允许 8、15 倍", "allowedScopes": LONG, "sourceIds": ["official_20_1"]},
        "dmr": {"description": "可更换瞄具的 DMR：红点、全息、2–15 倍", "allowedScopes": LONG, "sourceIds": ["raw_mk12"]},
        "sr": {"description": "可更换瞄具的 SR：红点、全息、2–15 倍", "allowedScopes": LONG, "sourceIds": ["raw_lynx", "raw_mosin"]},
        "smg_standard": {"description": "普通 SMG：红点、全息、2–6 倍", "allowedScopes": MEDIUM, "sourceIds": ["official_27_steam", "raw_mp5k"]},
        "smg_short": {"description": "Micro UZI / Tommy Gun / MP9：只允许红点、全息", "allowedScopes": LOW, "sourceIds": ["official_19_1", "official_6_3_steam"]},
        "lmg": {"description": "LMG：红点、全息、2–6 倍", "allowedScopes": MEDIUM, "sourceIds": ["official_2018_limits", "raw_dp28", "raw_mg3"]},
        "shotgun_optics": {"description": "S12K / DBS / O12 及可装镜霰弹枪角色类推：红点、全息、2–6 倍", "allowedScopes": MEDIUM, "sourceIds": ["official_20_1", "raw_dbs"]},
        "pistol": {"description": "可装镜手枪：红点、全息", "allowedScopes": LOW, "sourceIds": ["official_27_steam"]},
        "no_optics": {"description": "PUBG 无可换瞄具型号（S1897 / S686 / Sawed-Off / R1895）", "allowedScopes": [], "sourceIds": ["raw_index_2023"]},
        "fixed_optics": {"description": "P90 / Win94 / VSS 固定瞄具；不添加外装主瞄具", "allowedScopes": [], "sourceIds": ["official_13_2", "official_15_2"]},
        "launcher": {"description": "发射器无普通 PUBG 可换瞄具对应规则，保守不添加 CBRG 瞄具", "allowedScopes": [], "sourceIds": ["raw_index_2023"]},
    }


def choose_rule(cat, name):
    if name in {"P90", "Win94", "VSS"}:
        return "fixed_optics"
    if name in {"S1897", "S686", "Sawed-Off", "R1895"}:
        return "no_optics"
    if name in {"M16A4", "Mk47 Mutant"}:
        return "ar_long"
    if name in {"Micro UZI", "Tommy Gun", "MP9"}:
        return "smg_short"
    return {"AR": "ar_standard", "DMR": "dmr", "SR": "sr", "SMG": "smg_standard", "LMG": "lmg", "SG": "shotgun_optics", "PISTOL": "pistol", "LAUNCHER": "launcher"}[cat]


# The historical canted whitelist is intentionally explicit. No pistol, no
# launcher, no short SMG, and no fixed-optic exception is inferred automatically.
LEGACY_CANTED_WEAPONS = {
    "M416", "SCAR-L", "G36C", "QBZ", "AUG", "AKM", "Beryl M762", "Mk47 Mutant", "ACE32", "M16A4", "K2", "FAMAS",
    "Mini14", "Mk12", "SKS", "SLR", "Mk14", "QBU", "VSS",
    "UMP", "Vector", "MP5K", "PP-19 Bizon",
    "Kar98k", "M24", "AWM", "Mosin Nagant", "M249", "S12K", "DBS", "O12",
}
LEGACY_CANTED_EXISTING_ONLY = {"Dragunov", "JS9"}
LEGACY_CANTED_DENIED = {"Groza", "DP-28", "MG3", "Lynx AMR", "Crossbow", "MP9", "P90", "Win94", "RPD"}
CANTED_SPECIFIC_SOURCES = {
    "M16A4": ["official_27_steam"], "VSS": ["official_15_2"], "O12": ["official_20_2"],
    "ACE32": ["raw_ace32"], "MP5K": ["raw_mp5k"], "FAMAS": ["raw_famas"], "K2": ["raw_k2"],
    "DBS": ["raw_dbs"], "Mk12": ["raw_mk12"], "Mosin Nagant": ["raw_mosin"],
    "Groza": ["raw_groza"], "DP-28": ["raw_dp28"], "MG3": ["raw_mg3"], "Lynx AMR": ["raw_lynx"],
}


def main():
    global SOURCES
    assets = read_json(ASSETS)
    inv = {g["id"]: g for g in read_json(INVENTORY)["guns"]}
    comp = read_json(COMPARISON)
    research = read_json(RESEARCH)
    # Use original official announcements and raw Blueprint exports as support.
    # Secondary wiki pages and a derived compatibility index are not promoted
    # to official proof. JS9/Dragunov are explicitly existing-pack inferences.
    source_titles = {
        "official_2018_limits": "PUBG 官方：步枪、机枪的高倍率镜限制",
        "official_20_1": "PUBG 官方 20.1：M16A4 / Mk47 的 8、15 倍镜；O12 的 2–6 倍镜",
        "official_20_2": "PUBG 官方 20.2：O12 加入侧瞄",
        "official_15_2": "PUBG 官方 15.2：Win94 / VSS 固定镜及 VSS 历史侧瞄",
        "official_13_2": "PUBG 官方 13.2：P90 固定预装瞄具",
        "official_19_1": "PUBG 官方 19.1：MP9 仅红点、全息",
        "official_22_2": "PUBG 官方 22.2：FAMAS 至多 6 倍镜",
        "official_25_2": "PUBG 官方 25.2：Dragunov 可装至 15 倍镜",
        "official_17_1": "PUBG 官方 17.1：ACE32 至多 6 倍镜",
        "official_37_1": "PUBG 官方 37.1：移除侧瞄及其槽位",
        "official_41_1": "PUBG 官方 41.1：新增 Hybrid Scope，不等于侧瞄",
        "official_legacy_canted": "PUBG 官方：最初的侧瞄允许枪械清单",
        "official_27_steam": "PUBG 官方 Steam 更新 #27：手枪全息、M16A4 侧瞄、MP5K 瞄具",
        "official_6_3_steam": "PUBG 官方 Steam 更新 6.3：汤姆逊可用红点、全息",
        "raw_index_2023": "2023 历史原始 Blueprint 导出索引（社区归档）",
    }
    SOURCES = [{"id": s["id"], "title": source_titles.get(s["id"], "2023 原始 Blueprint：" + s["url"].rsplit("/", 1)[-1]), "supports": s.get("supports", ""), "url": s["url"], "kind": s["kind"]}
               for s in research["sources"]
               if s["kind"] in {"official_patch", "official_steam_announcement", "historical_datamined_original_asset_export"}]
    SOURCES.append({"id": "cbrg0_4_8", "title": "Classic BattleRoyale Gun 0.4.8 固定发行档", "url": "https://www.curseforge.com/minecraft/customization/tacz-classic-battleroyale-gun/files/7589555", "kind": "pack_author"})
    mapped = {}
    for w in comp["currentConventionalFirearms"]:
        for candidate in w["candidates"]:
            mapped[candidate["gunId"]] = {
                "category": w["category"], "pubgName": w["pubgName"],
                "basis": "cbrg_substitute" if w["status"] == "model_variant_or_substitute" else "cbrg_same_family_candidate",
                "explanation": w["note"] + " 使用作者名称意图对应的 PUBG 瞄具规则，不证明资产精确复刻。",
            }
    rules = build_rules()
    guns = []
    for g in assets["guns"]:
        cat, pubg_name, basis, explanation = classify(g, mapped)
        original_slot = "scope" in g["rawData"].get("allow_attachment_types", [])
        rule_id = choose_rule(cat, pubg_name)
        allowed = list(rules[rule_id]["allowedScopes"])
        original = inv[g["id"]].get("compatible_attachments", {}).get("scope", [])
        historical_canted = pubg_name in LEGACY_CANTED_WEAPONS
        canted_basis = "historical_primary_evidence" if historical_canted else "not_permitted_or_unconfirmed"
        if pubg_name in LEGACY_CANTED_EXISTING_ONLY and CANTED in original:
            historical_canted = True
            canted_basis = "existing_pack_permission_with_uncertain_history"
        # Non-PUBG category analogies stay conservative on canted as well: only
        # AR/DMR/SR infer broadly supported historical attachment points. LMG
        # has too many explicit exceptions (MG3, DP28) to infer a generic mount.
        if pubg_name is None and cat in {"AR", "DMR", "SR"}:
            historical_canted = True
            canted_basis = "non_pubg_category_inference"
        if historical_canted:
            allowed.append(CANTED)
        if not original_slot:
            allowed = []
        gun = {
            "gunId": g["id"], "originalScopeSlot": original_slot, "category": cat,
            "taczCategory": g["type"], "pubgWeapon": pubg_name, "basis": basis,
            "basisExplanation": explanation, "ruleId": rule_id,
            "allowedScopes": [sid for sid in SCOPE_IDS if sid in allowed],
            "originalCbrgScopes": [sid for sid in SCOPE_IDS if sid in original],
            "originalTotalScopeCount": len(original),
            "historicalCantedRule": historical_canted,
            "cantedBasis": canted_basis,
            "cantedSourceIds": CANTED_SPECIFIC_SOURCES.get(pubg_name, ["official_legacy_canted"] if canted_basis in {"historical_primary_evidence", "non_pubg_category_inference"} else []),
            "name": g["displayName"], "icon": g["icon"]["workspacePath"],
        }
        guns.append(gun)
    assert len(guns) == 424
    assert len({g["gunId"] for g in guns}) == 424
    assert all(g["originalScopeSlot"] or not g["allowedScopes"] for g in guns)
    assert all(set(g["allowedScopes"]) <= set(SCOPE_IDS) for g in guns)
    counts = {
        "guns": len(guns), "originalScopeSlot": sum(g["originalScopeSlot"] for g in guns),
        "noOriginalScopeSlot": sum(not g["originalScopeSlot"] for g in guns),
        "allowedAny": sum(bool(g["allowedScopes"]) for g in guns),
        "deniedAll": sum(not g["allowedScopes"] for g in guns),
        "allowedPairs": sum(len(g["allowedScopes"]) for g in guns),
        "byCategory": dict(collections.Counter(g["category"] for g in guns)),
        "byBasis": dict(collections.Counter(g["basis"] for g in guns)),
    }
    policy = {
        "schemaVersion": 1, "sourceDate": SOURCE_DATE,
        "policyName": "PUBG PC 43.2 主体规则 + 移除前历史侧瞄许可",
        "scope": "Only the nine CBRG scopes; preserve original TaCZ scope-slot capability as a hard precondition.",
        "historicalCantedOptIn": True,
        "unknownGunPolicy": "Do not add compatibility for unknown gun IDs.",
        "inventorySha256": hashlib.sha256(ASSETS.read_bytes()).hexdigest(),
        "counts": counts, "scopes": [{"id": k, "name": v} for k, v in SCOPES],
        "rules": rules, "legacyCantedWeapons": sorted(LEGACY_CANTED_WEAPONS),
        "legacyCantedExistingOnly": sorted(LEGACY_CANTED_EXISTING_ONLY),
        "legacyCantedDeniedOrUnconfirmed": sorted(LEGACY_CANTED_DENIED),
        "sources": SOURCES, "guns": guns,
    }
    RESOURCE.parent.mkdir(parents=True, exist_ok=True)
    RESOURCE.write_text(json.dumps(policy, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "pubg-scope-policy.json").write_text(json.dumps(policy, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_html(policy)
    write_markdown(policy)
    write_preview(policy)
    with (OUT / "pubg-scope-policy.csv").open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.writer(stream)
        writer.writerow(["gunId", "name", "category", "pubgWeapon", "basis", "originalScopeSlot", "allowedScopes", "originalCbrgScopes"])
        for g in guns:
            writer.writerow([g["gunId"], g["name"], g["category"], g["pubgWeapon"] or "", g["basis"], g["originalScopeSlot"], ";".join(g["allowedScopes"]), ";".join(g["originalCbrgScopes"])])
    print(json.dumps(counts, ensure_ascii=False, indent=2))


def source_links(ids):
    by_id = {s["id"]: s for s in SOURCES}
    return " ".join(f'<a href="{html.escape(by_id[i]["url"], quote=True)}" target="_blank">{"官方依据" if by_id[i]["kind"].startswith("official") else "历史原始数据"}</a>' for i in ids)


def write_html(policy):
    esc = html.escape
    stats = policy["counts"]
    cat_options = "".join(f'<option value="{cat}">{label}</option>' for cat, label in CAT_LABELS.items())
    scope_options = "".join(f'<option value="{sid}">{label}</option>' for sid, label in SCOPES)
    category_cards = []
    for cat, label in CAT_LABELS.items():
        gs = [g for g in policy["guns"] if g["category"] == cat]
        if not gs:
            continue
        icon = "../" + gs[0]["icon"].removeprefix("docs/")
        category_cards.append(f'<article><img src="{esc(icon, quote=True)}" alt=""><strong>{label}</strong><span>{len(gs)} 把 · {sum(bool(g["allowedScopes"]) for g in gs)} 把允许新增</span></article>')
    rows = []
    for g in policy["guns"]:
        label = "原有 scope 槽" if g["originalScopeSlot"] else "无原始 scope 槽"
        orig = "、".join(LABELS[s] for s in g["originalCbrgScopes"]) or "无"
        basis = {"cbrg_same_family_candidate": "CBRG 同枪型候选", "cbrg_substitute": "CBRG 变体／替代", "same_family_inference": "同族型号类推", "role_inference": "DMR 角色类推", "category_inference": "非 PUBG 枪类推断"}[g["basis"]]
        icon = "../" + g["icon"].removeprefix("docs/")
        canted_note = {
            "historical_primary_evidence": "侧瞄：历史官方公告或原始 Blueprint 证据",
            "existing_pack_permission_with_uncertain_history": "侧瞄：仅保留源包展开标签后已有的许可，历史依据较弱；不新增推广",
            "non_pubg_category_inference": "侧瞄：非 PUBG 型号按 AR / DMR / SR 角色类推，并非官方逐枪许可",
            "not_permitted_or_unconfirmed": "侧瞄：型号不支持或历史许可未确认，保守不添加",
        }[g["cantedBasis"]]
        cells = ""
        for s in SCOPE_IDS:
            yes = s in g["allowedScopes"]
            weak = s == CANTED and yes and g["cantedBasis"] == "existing_pack_permission_with_uncertain_history"
            tooltip = canted_note if s == CANTED else LABELS[s] + ("：允许" if yes else "：不添加")
            cells += f'<td class="mark {"yes" if yes else "no"}" title="{esc(tooltip, quote=True)}">{"●*" if weak else "●" if yes else "—"}</td>'
        search = (g["name"] + " " + g["gunId"] + " " + (g["pubgWeapon"] or "") + " " + basis).lower()
        rows.append(
            f'<tr data-category="{g["category"]}" data-slot="{int(g["originalScopeSlot"])}" data-basis="{g["basis"]}" data-scopes="{esc(" ".join(g["allowedScopes"]), quote=True)}" data-search="{esc(search, quote=True)}">'
            f'<th class="weapon"><img loading="lazy" src="{esc(icon, quote=True)}" alt="{esc(g["name"], quote=True)}"><div><strong>{esc(g["name"])}</strong><code>{esc(g["gunId"])}</code></div></th>'
            f'<td>{CAT_LABELS[g["category"]]}</td>'
            f'<td class="basis"><strong>{esc(g["pubgWeapon"] or "无 PUBG 精确对应")}</strong><span>{basis}</span><details><summary>判定依据</summary>{esc(g["basisExplanation"])}<br>{esc(policy["rules"][g["ruleId"]]["description"])}<br>{esc(canted_note)}<br>{source_links(policy["rules"][g["ruleId"]]["sourceIds"] + g["cantedSourceIds"])}</details></td>'
            f'<td class="original {"slot" if g["originalScopeSlot"] else "blocked"}"><strong>{label}</strong><small>初始 CBRG：{orig}<br>原允许镜总数 {g["originalTotalScopeCount"]}</small></td>' + cells + '</tr>'
        )
    sources = "".join(f'<li><a href="{esc(s["url"], quote=True)}">{esc(s["title"])}</a></li>' for s in policy["sources"])
    headings = "".join(f'<th class="scope-heading" title="{sid}">{name}</th>' for sid, name in SCOPES)
    text = r'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>PUBG 瞄具装配政策 · 424 把枪</title><style>
:root{color-scheme:dark;--bg:#10151c;--panel:#1b2430;--text:#eaf0f6;--muted:#9eacbc;--line:#354253;--blue:#7cc5ff;--green:#7bddb0;--orange:#ffbd78}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.65 "Microsoft YaHei",sans-serif}main{max-width:1800px;padding:32px;margin:auto}h1{margin:0;font-size:30px}h2{font-size:21px}a{color:var(--blue)}p{max-width:1250px}code{font-family:Consolas,monospace;color:var(--muted);font-size:12px;overflow-wrap:anywhere}.intro{color:var(--muted)}.stats{display:flex;flex-wrap:wrap;gap:12px;margin:22px 0}.stat{padding:14px 22px;background:var(--panel);border:1px solid var(--line);border-radius:10px}.stat strong{display:block;font-size:28px;color:var(--blue)}.notice{padding:15px 20px;border-left:4px solid var(--orange);background:#28231d;border-radius:4px}.categories{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:12px;margin:22px 0}article{background:var(--panel);padding:10px;border:1px solid var(--line);border-radius:8px;min-height:90px}article img{float:left;width:76px;height:76px;object-fit:contain;margin-right:10px}article strong,article span{display:block}article strong{padding-top:12px}article span{color:var(--muted);font-size:12px}.controls{display:flex;gap:12px;flex-wrap:wrap;align-items:center;background:var(--panel);padding:14px;border:1px solid var(--line);border-radius:8px;margin:16px 0}input,select,button{background:#111a25;border:1px solid #526175;color:var(--text);border-radius:5px;padding:10px;font:inherit}input{min-width:270px;flex:1}button{cursor:pointer}#visible{color:var(--blue)}.table-wrap{overflow:auto;border:1px solid var(--line);border-radius:8px;max-height:78vh}table{width:100%;min-width:1560px;border-collapse:collapse;font-size:13px}th,td{padding:10px;border-bottom:1px solid var(--line);text-align:left;vertical-align:middle}thead th{position:sticky;top:0;z-index:2;background:#273344;box-shadow:0 1px var(--line)}tbody tr:nth-child(even){background:#151e29}tbody tr:hover{background:#253347}.weapon{width:330px;min-width:300px}.weapon img{width:82px;height:74px;object-fit:contain;float:left;margin-right:12px}.weapon strong{display:block;padding-top:10px;font-size:14px}.weapon code{display:block;line-height:1.5}.basis{min-width:185px;width:220px}.basis span,.original small{display:block;color:var(--muted)}.original{width:170px;min-width:160px}.original strong{font-size:12px}.blocked strong{color:var(--orange)}.slot strong{color:var(--muted)}.scope-heading,.mark{width:49px;min-width:45px;text-align:center}.yes{color:var(--green);font-size:20px}.no{color:#546170}.basis details{font-size:11px;color:var(--muted);line-height:1.5;max-width:230px}.basis summary{cursor:pointer;color:var(--blue)}footer{color:var(--muted);font-size:13px;padding-bottom:40px}tr[hidden]{display:none}.legend{color:var(--muted)}@media(max-width:700px){main{padding:16px}.stat{padding:10px}h1{font-size:24px}}
</style></head><body><main>
<h1>PUBG 瞄具装配政策</h1>
<p class="intro">核对日期 __DATE__ · 当前本地 424 把枪 · CBRG 0.4.8 的 9 种瞄具 · 主体采用 PC 43.2，侧瞄按用户选择保留移除前历史规则。</p>
<div class="stats"><div class="stat"><strong>424</strong>全部图鉴</div><div class="stat"><strong>__SLOTS__</strong>原有瞄具槽</div><div class="stat"><strong>__NOSLOTS__</strong>无槽硬性禁止</div><div class="stat"><strong>__ALLOWED__</strong>实验性方案可装</div><div class="stat"><strong>__PAIRS__</strong>允许装配组合</div></div>
<p class="notice">表中的绿色圆点表示<strong>瞄具实验性调整</strong>允许该 CBRG 镜。没有原始 scope 槽时，无论 PUBG 对应枪如何，都不会开放。初始模式使用枪包原规则。非 PUBG 枪械按角色／枪类推断，表中明确标注；不是 PUBG 官方逐枪许可，也不证明模型挂点贴合。</p>
<div class="categories">__CARDS__</div>
<h2>逐枪允许装配表</h2><p class="legend">● 允许　— 不添加／禁止　●* 仅保留源包已有侧瞄许可，历史依据较弱　|　8 倍、15 倍并非所有步枪可用；M16A4 / Mk47 是特例。侧瞄是一种历史兼容项，并不等于实现 PUBG 独立双镜切换。</p>
<div class="controls"><input id="search" placeholder="搜索枪名、ID、PUBG 对应型号…" aria-label="搜索枪械"><select id="category" aria-label="枪类"><option value="">全部枪类</option>__CATS__</select><select id="slot" aria-label="原始瞄具能力"><option value="">全部原始能力</option><option value="1">原有 scope 槽</option><option value="0">原来没有 scope 槽</option></select><select id="scope" aria-label="允许瞄具"><option value="">全部允许组合</option>__SCOPES__</select><select id="basis" aria-label="对应依据"><option value="">全部依据</option><option value="cbrg">CBRG 名称意图映射</option><option value="inference">同族／角色／枪类推断</option></select><button id="reset">重置</button><span id="visible" aria-live="polite">424 / 424</span></div>
<div class="table-wrap"><table><thead><tr><th>图片 · 名字 · ID</th><th>规则分类</th><th>PUBG 对应／依据</th><th>原始能力</th>__HEADINGS__</tr></thead><tbody>__ROWS__</tbody></table></div>
<h2>使用边界</h2><p>只修改这 9 种 CBRG 瞄具的允许列表，不新增原本缺少的 scope 槽，也不修改枪包源文件。未知新增 gunId 不自动扩大适配。VSS、Win94、P90 的固定镜规则不转成通用外装镜；S686、S1897、Sawed-Off、R1895 保持无外装镜。</p>
<p><a href="pubg-scope-policy.csv">下载逐枪 CSV</a> · <a href="pubg-scope-policy.json">机器可读政策</a> · <a href="pubg-scope-policy.md">说明文档</a></p>
<h2>来源</h2><ul>__SOURCES__</ul><footer>图标来自工作区已有枪包纹理。全部 424 图标采用本地图鉴资源，不是新生成图片。数据为静态核对，未启动游戏。</footer>
</main><script>
const rows=[...document.querySelectorAll('tbody tr')];const ids=['search','category','slot','scope','basis'];const controls=Object.fromEntries(ids.map(k=>[k,document.getElementById(k)]));function filter(){const q=controls.search.value.trim().toLowerCase();let n=0;for(const row of rows){const d=row.dataset;const ok=(!q||d.search.includes(q))&&(!controls.category.value||d.category===controls.category.value)&&(!controls.slot.value||d.slot===controls.slot.value)&&(!controls.scope.value||d.scopes.split(' ').includes(controls.scope.value))&&(!controls.basis.value||(controls.basis.value==='cbrg'?d.basis.startsWith('cbrg'):!d.basis.startsWith('cbrg')));row.hidden=!ok;if(ok)n++;}document.getElementById('visible').textContent=n+' / '+rows.length;}for(const c of Object.values(controls)){c.addEventListener('input',filter);c.addEventListener('change',filter);}document.getElementById('reset').addEventListener('click',()=>{for(const c of Object.values(controls))c.value='';filter();});
</script></body></html>'''
    replacements = {"DATE": policy["sourceDate"], "SLOTS": stats["originalScopeSlot"], "NOSLOTS": stats["noOriginalScopeSlot"], "ALLOWED": stats["allowedAny"], "PAIRS": stats["allowedPairs"], "CARDS": "".join(category_cards), "CATS": cat_options, "SCOPES": scope_options, "HEADINGS": headings, "ROWS": "\n".join(rows), "SOURCES": sources}
    for key, value in replacements.items():
        text = text.replace("__" + key + "__", str(value))
    (OUT / "pubg-scope-policy.html").write_text(text, encoding="utf-8")


def write_markdown(policy):
    c = policy["counts"]
    lines = [
        "# PUBG 瞄具允许装配表", "", f"核对日期：{SOURCE_DATE}。主体规则采用 PUBG: BATTLEGROUNDS PC 43.2；按用户选择保留移除前的历史侧瞄许可。", "",
        "[打开全部 424 把枪的带图筛选表](pubg-scope-policy.html) · [逐枪 CSV](pubg-scope-policy.csv) · [机器政策](pubg-scope-policy.json)", "",
        f"覆盖 **{c['guns']} 把枪**，其中 **{c['originalScopeSlot']} 把原有 scope 槽**，**{c['noOriginalScopeSlot']} 把无原始槽，硬性禁止新增瞄具**。实验性瞄具方案允许 {c['allowedAny']} 把枪、{c['allowedPairs']} 个枪械×瞄具组合。", "",
        "本表只管 CBRG 的红点、全息、2、3、4、6、8、15 倍及侧瞄；初始模式恢复枪包自身规则。没有给无原始瞄具槽的枪创建新槽，也不改源枪包。", "",
        "## 规则与图片举例", "", "| 枪类／特例 | 本地示例图片 | 允许的主瞄具 | 说明 |", "|---|---|---|---|",
    ]
    examples = [
        ("普通突击步枪", "tacz:hk416d", "红点、全息、2、3、4、6 倍", "AR 不一概允许 8、15 倍"),
        ("M16A4／Mk47", "tacz:m16a4", "红点、全息、2、3、4、6、8、15 倍", "20.1 新增的长倍率特例"),
        ("精确射手步枪", "tacz:mk14", "红点、全息、2、3、4、6、8、15 倍", "TaCZ 可能把 DMR 注册为 rifle，按型号角色识别"),
        ("狙击步枪", "tacz:ai_awp", "红点、全息、2、3、4、6、8、15 倍", "Win94 固定镜例外"),
        ("普通冲锋枪", "tacz:ump45", "红点、全息、2、3、4、6 倍", "P90 固定镜；短型 SMG 特例见下"),
        ("UZI／汤姆逊／MP9", "tacz:uzi", "红点、全息", "不放开倍率镜"),
        ("机枪", "tacz:m249", "红点、全息、2、3、4、6 倍", "仍需原始 scope 槽"),
        ("S12K／O12 等", "gz:tactical_sasg_12k", "红点、全息、2、3、4、6 倍", "O12 在 20.1 扩展至 2–6 倍"),
        ("可装镜手枪", "tacz:deagle", "红点、全息", "R1895 无外装镜；无槽手枪不新增"),
        ("固定镜／无镜武器", "origin:p90", "不添加通用外装镜", "P90、Win94、VSS；S1897、S686、Sawed-Off、R1895"),
        ("发射器", "tacz:rpg7", "不添加 CBRG 镜", "无普通 PUBG 可换镜对应，保守保留原能力"),
    ]
    by_id = {g["gunId"]: g for g in policy["guns"]}
    for label, gid, allowed, note in examples:
        g = by_id[gid]
        icon = "../" + g["icon"].removeprefix("docs/")
        lines.append(f"| {label} | ![{g['name']}]({icon})<br>{g['name']}<br>`{gid}` | {allowed} | {note} |")
    lines += ["", "侧瞄另按历史白名单开放，表中最后一列单独显示。不是所有枪都有侧瞄，也不自动实现双镜独立安装或切换。Groza、DP-28、MG3、Lynx AMR 不添加侧瞄；RPD 无移除前的已确认历史许可，保守不添加。JS9 / Dragunov 仅在源包原本已允许侧瞄时保留，标 `●*`，不宣传为已获官方逐枪确认。非 PUBG 的 AR / DMR / SR 侧瞄是项目类推，非 PUBG 机枪不一概推广。", "", "## 映射可信度与限制", "",
        "CBRG 名称意图映射引用本地固定 0.4.8 对照，不把替代模型说成精确 PUBG 复刻。例如 P92 使用 P320 模型、Skorpion 使用 CZ75；这里服从作者想对应的玩法型号。其他枪包按同族型号、DMR 角色或 TaCZ 枪类保守类推，HTML 的每一行都显示依据。非 PUBG、科幻和历史枪没有 PUBG 官方逐枪许可。", "",
        "机器资源与 HTML / CSV 出自同一生成器。规则最终计算是：原始 `allow_attachment_types` 含 `scope` **并且** 该镜在规则白名单中。原始无槽是硬条件，不能被映射、研究方案或后备模型挂点覆盖。未知新增 gunId 不自动扩大适配。", "",
        "全部图标使用已有本地纹理。完成的是静态数据核对；没有启动游戏，模型挂点贴合和开镜显示仍以实际枪包能力为限。", "", "## 来源", ""]
    for s in policy["sources"]:
        lines.append(f"- [{s['title']}]({s['url']})")
    lines += ["- [本地 CBRG 逐项对照](../PUBG枪械逐项对照.md)", "- [本地枪械图鉴与源数据](../gun-balance/gun-assets.json)", "", "逐枪完整名单、图片及 9 列允许矩阵见 HTML，避免在 Markdown 中重复 424 行造成阅读困难。", ""]
    (OUT / "pubg-scope-policy.md").write_text("\n".join(lines), encoding="utf-8")


def write_preview(policy):
    """A representative, static table for chat previews; uses the same rows."""
    from PIL import Image, ImageDraw, ImageFont
    selected = [
        "tacz:hk416d", "tacz:m16a4", "tacz:mk14", "tacz:ai_awp", "tacz:ump45",
        "tacz:uzi", "classicr:mp9", "tacz:m249", "cib:mg3", "cib:origin12",
        "tacz:deagle", "cbrg:groza", "suffuse:gm6", "origin:p90", "cib:js9",
    ]
    by_id = {g["gunId"]: g for g in policy["guns"]}
    width, row_height, top = 1500, 88, 212
    canvas = Image.new("RGB", (width, top + row_height * len(selected) + 100), "#10151c")
    draw = ImageDraw.Draw(canvas)
    font_path = "C:/Windows/Fonts/msyh.ttc"
    f_title = ImageFont.truetype(font_path, 34)
    f_text = ImageFont.truetype(font_path, 23)
    f_small = ImageFont.truetype(font_path, 17)
    f_marker = ImageFont.truetype(font_path, 29)
    draw.text((30, 22), "PUBG 瞄具允许装配 · 代表例", font=f_title, fill="#eaf0f6")
    draw.text((30, 75), "424 把枪完整图表另附 HTML｜主体 PC 43.2 + 历史侧瞄｜2026-10-18", font=f_text, fill="#9eacbc")
    draw.text((30, 118), "● 允许   — 不添加   ●* 仅保留源包已有侧瞄，历史证据较弱   原来无槽不会新增", font=f_small, fill="#ffbd78")
    draw.rectangle((20, 163, width - 20, top), fill="#273344")
    draw.text((35, 172), "枪图 · 名字 · ID", font=f_text, fill="#eaf0f6")
    draw.text((465, 172), "原槽", font=f_text, fill="#eaf0f6")
    x0, cell_width = 575, 99
    for i, (_, label) in enumerate(SCOPES):
        draw.text((x0 + i * cell_width + 49, 172), label, font=f_text, anchor="mt", fill="#eaf0f6")
    for row, gid in enumerate(selected):
        g = by_id[gid]
        y = top + row * row_height
        if row % 2 == 0:
            draw.rectangle((20, y, width - 20, y + row_height), fill="#1b2430")
        draw.line((20, y + row_height, width - 20, y + row_height), fill="#354253")
        icon = Image.open(ROOT / g["icon"]).convert("RGBA")
        icon.thumbnail((100, 76), Image.Resampling.LANCZOS)
        canvas.paste(icon, (35 + (100 - icon.width) // 2, y + (row_height - icon.height) // 2), icon)
        name = g["pubgWeapon"] or g["name"]
        draw.text((150, y + 15), name, font=f_text, fill="#eaf0f6")
        draw.text((150, y + 48), gid, font=f_small, fill="#9eacbc")
        draw.text((492, y + 28), "有" if g["originalScopeSlot"] else "无", font=f_text, fill="#9eacbc" if g["originalScopeSlot"] else "#ffbd78", anchor="mt")
        for i, (sid, _) in enumerate(SCOPES):
            yes = sid in g["allowedScopes"]
            weak = yes and sid == CANTED and g["cantedBasis"] == "existing_pack_permission_with_uncertain_history"
            marker = "●*" if weak else "●" if yes else "—"
            draw.text((x0 + i * cell_width + 49, y + 23), marker, font=f_marker, anchor="mt", fill="#7bddb0" if yes else "#546170")
    draw.text((30, canvas.height - 74), "CBRG 对应含变体／替代模型；所有 424 图标、分类、原始能力及依据见完整图表。", font=f_small, fill="#9eacbc")
    draw.text((30, canvas.height - 43), "本图由实际机器政策生成。只管 9 种 CBRG 瞄具；没有运行游戏。", font=f_small, fill="#9eacbc")
    canvas.save(OUT / "pubg-scope-policy-preview.png")


if __name__ == "__main__":
    main()
