"""Build an offline attachment review from frozen local audits; never install assets.

Writes only docs/attachments/attachment-guide.{html,json,csv}. Official evidence is
kept separate from project proposals and from current TaCZ behavior.
"""
from __future__ import annotations

import csv
import hashlib
import html
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs/attachments"
DATE = "2026-10-18"

LABELS = {
    "vertical_foregrip": "垂直握把", "halfgrip": "半截式握把",
    "thumbgrip": "拇指握把", "lightweight_grip": "轻型握把",
    "angled_foregrip": "直角前握把", "laser_sight": "激光瞄准器",
    "cheek_pad": "托腮板", "compensator_rifle": "步枪补偿器",
    "flash_hider_rifle": "步枪消焰器", "suppressor_rifle": "步枪消音器",
    "suppressor_pistol": "手枪／冲锋枪消音器", "suppressor_sr": "狙击枪消音器",
    "quickdraw_mag_rifle": "步枪快速弹匣", "extended_mag_rifle": "步枪扩容弹匣",
    "extended_quickdraw_mag_rifle": "步枪快速扩容弹匣",
    "tilted_grip": "斜向握把", "muzzle_brake": "枪口制退器",
    "tactical_stock": "战术枪托", "heavy_stock": "重型枪托",
}
ORDER = list(LABELS)
OFFICIAL_IDS = {"halfgrip": "half_grip", "thumbgrip": "thumb_grip",
                "laser_sight": "laser", "compensator_rifle": "compensator",
                "flash_hider_rifle": "flash_hider", "suppressor_rifle": "suppressor",
                "suppressor_pistol": "suppressor", "suppressor_sr": "suppressor"}
TYPES = {"grip": "握把／激光", "stock": "枪托", "muzzle": "枪口", "extended_mag": "弹匣"}
SLOTS = {"muzzle": "枪口", "grip": "握把", "extended_mag": "弹匣", "scope": "瞄具",
         "stock": "枪托", "laser": "激光"}
ROLES = {"AR": "突击／战斗步枪", "DMR": "精确射手步枪", "SR": "狙击步枪",
         "SMG": "冲锋枪", "LMG": "机枪", "SG": "霰弹枪", "PISTOL": "手枪", "LAUNCHER": "发射器"}
TYPE_ROLE = {"rifle": "AR", "sniper": "SR", "smg": "SMG", "mg": "LMG", "pistol": "PISTOL",
             "shotgun": "SG", "rpg": "LAUNCHER"}
STATUS = {"compatible": "原槽＋标签允许", "compatible_but_mount_not_found": "允许，常规挂点待核",
          "slot_closed": "原槽未开放", "tag_not_allowed": "标签不允许"}

OFFICIAL_TEXT = {
    "vertical_foregrip": "垂直控制 +15%；枪口上抬控制 0%。",
    "half_grip": "垂直控制 +8%、水平 +16%；呼吸晃动控制 −10%、后坐恢复 +10%、枪口上抬控制 −5%。",
    "thumb_grip": "垂直控制 +10%、ADS 速度 +40%、呼吸晃动控制 +20%、单发后坐恢复 +10%。",
    "lightweight_grip": "枪口上抬控制 +10%、首发后坐控制 +30%、呼吸晃动控制 +20%、射后恢复 +20%。",
    "angled_foregrip": "水平控制 +25%；历史 ADS 速度 +10%、呼吸晃动控制 −10%。41.1 移出世界刷新。",
    "laser": "腰射／肩射定位；与握把竞争下导轨。现行 PC 散布百分比未核实；激光效果的历史文字另含主机归档。",
    "cheek_pad": "25.2 明确 Dragunov 可用。完整现行效果百分比未取得，不能以当前 ×0.8 当成 PUBG 标准。",
    "compensator": "控制垂直及部分水平后坐。现行 PC 完整精确百分比未取得。",
    "flash_hider": "隐藏枪口焰，并轻度控制后坐。现行 PC 完整精确百分比未取得。",
    "suppressor": "降低枪声／回声并减少枪口焰。现行 PC 完整精确百分比未取得；额外散布增益未获官方支持。",
    "tilted_grip": "垂直控制 +12%、水平 +6%、射击镜头抖动控制 +25%（41.1）。",
    "muzzle_brake": "垂直控制 +10%、水平 +8%、镜头抖动控制 +35%（32.1／36.1 修订后）。",
    "tactical_stock": "官方历史公告描述后坐恢复、动画踢动、晃动、ADS 与腰射改善；完整现行百分比未取得。",
    "heavy_stock": "垂直／水平控制各 +10%、后坐恢复 +5%；ADS 速度 −10%、腰射精度 −10%。",
}
PROPOSAL = {
    "vertical_foregrip": ("核心 · 连射控枪", "保留垂直控制路线。若采用倒数映射，1÷1.15≈0.8696；这是 TaCZ 映射选择，不是官网公开的引擎公式。"),
    "halfgrip": ("核心 · 双轴连射", "垂直可用 1÷1.08≈0.9259、水平 1÷1.16≈0.8621 作为候选；另补恢复和稳定性代价，不能只给双轴无代价增益。"),
    "thumbgrip": ("核心 · 快速开镜", "现有垂直／ADS 倍率接近倒数映射；仍缺呼吸晃动和单发恢复。ADS 时间 ×0.7143 是时间减少 28.57%，不是减少 40%。"),
    "lightweight_grip": ("核心 · 点射／DMR", "按首发、点射恢复、晃动分别实现。当前每发纵向 ×0.909 不能代表轻型握把的完整定位。"),
    "angled_foregrip": ("经典可选 · 不列现行刷新核心", "保留历史玩法可选；水平 ×0.8 对应减少 20% 幅度，亦可解释为倒数意义控制 +25%。不要改名冒充新斜向握把。"),
    "laser_sight": ("核心 · 腰射选择", "保留 GRIP 类型，与握把互斥；核对 grip_pos 和已有光束。若需要第三人称可见，再独立实现，不能误把 LASER 槽当现有安装槽。"),
    "cheek_pad": ("核心 · 精确射击硬件", "仅给真实有肩托／贴腮安装位置的枪；与其他枪托互斥。官方缺失指标保留待核，不擅填完整数值。"),
    "compensator_rifle": ("核心 · 后坐优先", "现有两轴倍率可暂作本项目候选；按实际枪口、口径与白名单适配，不能宣称完全复刻官网精确数值。"),
    "flash_hider_rifle": ("核心 · 消焰折中", "先补独立消焰能力；当前只有后坐修正。不要用 show_muzzle 隐藏默认枪口零件来冒充隐藏枪口火焰。"),
    "suppressor_rifle": ("核心 · 隐蔽射击", "先修广播半径：当前 64−96=−32 导致周围第三人称枪声不广播。保留可听见的消音效果；散布增益标作本项目设计，不冒称 PUBG 标准。"),
    "suppressor_pistol": ("核心 · 手枪／SMG", "与步枪消音器同样修正负广播半径；按手枪／SMG 白名单，排除内置消音器和不适用枪口。"),
    "suppressor_sr": ("核心 · SR／DMR", "与其他消音器同样修正负广播半径；按 SR／DMR 实际枪口与挂点适配，不能仅靠枪类放开。"),
    "quickdraw_mag_rifle": ("九弹匣族 · 速度优先", "使用各枪原有扩容等级 1 容量，再加快速换弹；项目换弹用时 ×0.625（速度 +60% 的倒数解释），同步服务器供弹／结束时刻与客户端动画。等级 1 不强制等于基础容量。"),
    "extended_mag_rifle": ("九弹匣族 · 容量优先", "使用各枪原有扩容等级 2 容量，不增加换弹加速。不能把全部枪统一套 42 发或容量 ×1.4。"),
    "extended_quickdraw_mag_rifle": ("九弹匣族 · 折中", "使用各枪原有扩容等级 3 容量，再加快速换弹；项目换弹用时 ×0.869565（速度 +15% 的倒数解释）。不是已验证的 PUBG 引擎公式；等级 3 容量可低于等级 2。"),
    "tilted_grip": ("待新增 · 现行候选", "CBRG 没有该物品／模型，需单独制作；镜头抖动与真实后坐独立处理。"),
    "muzzle_brake": ("待新增 · 现行候选", "CBRG 没有该物品／模型；先实现视觉镜头震动控制，再按允许枪口逐枪配置。"),
    "tactical_stock": ("待新增 · 百分比待核", "CBRG 没有独立战术枪托。先做硬件白名单和模型；不凭社区表填充官方缺失的百分比。"),
    "heavy_stock": ("待新增 · 稳定性换代价", "CBRG 没有独立重型枪托；保留 ADS／腰射代价。19.2 只明确 M416、ACE32、M16A4、Mk47、Vector、MP5K、M249 等，不能全 AR／LMG 开放。"),
}


def load(relative):
    return json.loads((ROOT / relative).read_text(encoding="utf-8"))


def clean(value):
    return re.sub(r"§.", "", value or "").strip()


def fmt_multiplier(value):
    return f"×{value:g}（减少 {(1 - value) * 100:.2f}%）"


def current_effect(data):
    parts = []
    for axis, label in [("pitch", "纵向后坐"), ("yaw", "横向后坐")]:
        if axis in data.get("recoil", {}):
            parts.append(label + " " + fmt_multiplier(data["recoil"][axis]["multiplier"]))
    if "ads" in data:
        parts.append("ADS 时间 " + fmt_multiplier(data["ads"]["multiplier"]))
    if "inaccuracy" in data:
        parts.append("站立／移动腰射散布 " + fmt_multiplier(data["inaccuracy"]["multiplier"]) + "；不作用 AIM／SNEAK／LIE")
    if "silence" in data:
        parts.append("声距加 −96；消音音效开启；当前基础声距 64，第三人称广播半径变为 −32")
    if "extended_mag_level" in data:
        parts.append(f"仅 extended_mag_level={data['extended_mag_level']}；容量与换弹由枪械逻辑决定")
    return "；".join(parts)


def build_report():
    audit = load("docs/attachments/cbrg-attachments-current.json")
    matrix = load("docs/attachments/current-gun-attachment-matrix.json")
    official = load("docs/attachments/official-pubg-references.json")
    models = load("docs/attachments/magazines/magazine-models.json")
    native_manifest_path = ROOT / "assets-workbench/pubg-magazines/tacz-pack-manifest.json"
    native_pack = json.loads(native_manifest_path.read_text(encoding="utf-8")) if native_manifest_path.is_file() else None
    deployment_path = ROOT / "logs/pubg-magazine-deployment.json"
    deployment = json.loads(deployment_path.read_text(encoding="utf-8")) if deployment_path.is_file() else None
    policy_path = ROOT / "docs/compatibility/pubg-magazine-policy.json"
    magazine_policy = json.loads(policy_path.read_text(encoding="utf-8")) if policy_path.is_file() else None
    magazine_rules = {g["gunId"]: g for g in magazine_policy["guns"]} if magazine_policy else {}
    asset_data = load("docs/gun-balance/gun-assets.json")
    scope_data = load("docs/compatibility/pubg-scope-policy.json")
    source_map = {x["key"]: x for x in official["sources"]}
    evidence = {x["id"]: x for x in official["official_effects"]}
    existing = {x["id"].split(":")[1]: x for x in audit["attachments"] if x["type"] != "scope"}
    attachments = []
    for key in ORDER:
        old = existing.get(key)
        official_id = OFFICIAL_IDS.get(key, key)
        e = evidence.get(official_id, {})
        magazine = key.endswith("mag_rifle")
        source_ids = ["patch15_1"] if magazine else e.get("source", [])
        assets = {kind: v["local"] for kind, v in (old or {}).get("assets", {}).items()}
        for asset in assets.values():
            assert (OUT / asset).is_file(), asset
        selection, plan = PROPOSAL[key]
        attachments.append({
            "id": old["id"] if old else key, "name": LABELS[key],
            "type": old["type"] if old else ("grip" if key == "tilted_grip" else "muzzle" if key == "muzzle_brake" else "stock"),
            "existing_cbrg": bool(old), "selection": selection,
            "current_effect": current_effect(old["raw_data"]) if old else "当前 CBRG 未提供；不能假称已经存在。",
            "current_raw_data": old["raw_data"] if old else None,
            "compatible_gun_count": old["compatible_gun_count"] if old else None,
            "official_effect": ("PC 15.1 的 M416 示例：基础 30；快速 30／换弹更快 60%；扩容 42；快扩 40／更快 15%。公告未给时间计算式。" if magazine else OFFICIAL_TEXT[official_id]),
            "official_fields": e.get("official"), "proposal": plan,
            "sources": [{"key": s, "title": source_map[s]["title"], "url": source_map[s]["url"]} for s in source_ids],
            "assets": assets,
        })
    asset_map = {g["id"]: g for g in asset_data["guns"]}
    role_map = {g["gunId"]: g for g in scope_data["guns"]}
    guns = []
    non_scope_ids = [a["id"] for a in attachments if a["existing_cbrg"]]
    for old in matrix["guns"]:
        art = asset_map[old["id"]]
        role_info = role_map[old["id"]]
        role = role_info.get("category", TYPE_ROLE[old["gun_type"]])
        compat = {k: v for k, v in old["cbrg_support"].items() if k in non_scope_ids}
        allowed = [k for k, v in compat.items() if v["current_static_compatible"]]
        mount_review = [k for k in allowed if not compat[k]["mount_or_builtin_mag_nodes_present"]]
        slots = old["original_slots"]
        family = []
        if "extended_mag" in slots:
            family = ({"AR": ["步枪／机枪／DMR"], "LMG": ["步枪／机枪／DMR"],
                       "DMR": ["步枪／机枪／DMR", "SR／DMR"], "SR": ["SR／DMR"],
                       "SMG": ["手枪／SMG"], "PISTOL": ["手枪／SMG"]}).get(role, [])
        magazine_rule = magazine_rules.get(old["id"])
        if magazine_rule is not None:
            family_names = {"rifle": "步枪／机枪／DMR", "smg": "手枪／SMG", "sniper": "SR／DMR"}
            family = [family_names[f] for f in magazine_rule["families"]]
        plan = ["保留原始未开放槽；不自动新增配件槽。"]
        if magazine_rule is not None:
            plan.append("弹匣实验：" + ("按审核开放上列家族，切回初始撤销新九 ID。" if magazine_rule["eligible"] else
                        "不授权新九种弹匣；具体供弹／原槽／容量排除依据见逐枪策略 JSON。"))
        if family:
            plan.append("弹匣族仅列候选，仍须核对实际供弹机构；快速／扩容／快扩读取各枪原等级 1／2／3 容量，快速与快扩另加换弹加速。")
        elif "extended_mag" in slots:
            plan.append("有弹匣槽但无通用族自动判定；保留原规则，逐枪核对供弹方式。")
        if role == "DMR":
            plan.append("DMR 可候选兼容步枪族及 SR 族；单一弹匣槽，不叠加。")
        if mount_review:
            plan.append(f"{len(mount_review)} 件当前允许配件未找到常规挂点／等级弹匣骨骼，需查 full／LOD 和装配姿态。")
        if old["builtin_attachments"]:
            plan.append("存在内置配件，核对是否应排除同槽外装配件。")
        if "extended_mag" in slots and not old["script"]:
            plan.append("未声明服务端枪械脚本；不能仅凭弹匣等级认定快速换弹生效。")
        if "grip" in slots:
            plan.append("激光沿用 GRIP 互斥；逐枪验证握把下导轨／grip_pos。")
        icon = "../gun-balance/" + art["icon"]["path"]
        assert (OUT / icon).resolve().is_file(), icon
        guns.append({
            "id": old["id"], "name": clean(art.get("displayName") or old["name_zh_cn"][0]),
            "name_en": clean(art.get("name_en", "")), "icon": icon,
            "gun_type": old["gun_type"], "role": role, "role_label": ROLES.get(role, role),
            "role_basis": role_info.get("basis", "project_classification"),
            "role_note": "沿用项目瞄具表的角色判定，仅供本方案分类；不是所有枪的 PUBG 官方许可。",
            "namespace": old["id"].split(":")[0], "original_slots": slots,
            "current_compatible": allowed, "current_support": compat,
            "mount_review": mount_review, "builtin_attachments": old["builtin_attachments"],
            "exclusive_attachments": old["exclusive_attachments"],
            "base_capacity": old["base_magazine_capacity"], "level_capacities": old["extended_mag_ammo_amount"],
            "reload": old["reload"], "gun_script": old["script"],
            "magazine_family_candidates": family, "adaptation_plan": plan,
            "magazine_experiment_rule": magazine_rules.get(old["id"]),
        })
    inputs = ["docs/attachments/cbrg-attachments-current.json", "docs/attachments/current-gun-attachment-matrix.json",
              "docs/attachments/official-pubg-references.json", "docs/attachments/magazines/magazine-models.json",
              "docs/gun-balance/gun-assets.json", "docs/compatibility/pubg-scope-policy.json"]
    return {
        "schema_version": 1, "checked_at": DATE, "title": "CBRG 配件与 PUBG 端游对照及全枪适配方案",
        "status": "magazine_experiment_deployed" if deployment and deployment.get("installed") else "assets_and_reload_addon_staged_not_installed" if native_pack else "review_proposal_only_not_installed",
        "game_launched": False, "runtime_modified": bool(deployment and deployment.get("installed")),
        "basis": audit["basis"], "official_scope": official["scope"],
        "latest_patch_checked": official["latest_patch_checked"],
        "counts": {"guns": len(guns), "all_cbrg_attachments": audit["attachment_count"],
                   "existing_non_scope_attachments": len(existing), "missing_candidates": 4,
                   "magazine_models": len(models["entries"]),
                   "non_scope_mount_review_edges": sum(len(g["mount_review"]) for g in guns),
                   "all_attachment_mount_review_edges": matrix["summary"]["compatible_missing_mount_count"]},
        "original_open_slots": matrix["summary"]["original_open_slots"],
        "attachments": attachments, "magazine_policy": official["magazine_policy"],
        "magazine_models": models["entries"], "magazine_models_installed": bool(deployment and deployment.get("installed")),
        "magazine_native_pack": native_pack,
        "magazine_deployment": deployment, "magazine_experiment_policy": magazine_policy,
        "magazine_visual_references": {
            "publisher_repository": "https://github.com/pubg/api-assets",
            "local_board": "../../assets-workbench/pubg-magazines/reference/pubg-api/reference-overview.png",
            "source_manifest": "../../assets-workbench/pubg-magazines/reference/pubg-api/reference-sources.json",
            "comparison": "magazines/magazine-reference-comparison.png" if (OUT / "magazines/magazine-reference-comparison.png").is_file() else None,
            "scope": "official item icon references; local low-poly models are a project adaptation, not extracted PUBG geometry",
        },
        "guns": guns, "sources": official["sources"], "not_verified": official["not_verified"],
        "input_sha256": {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in inputs},
    }


def h(value):
    return html.escape(str(value), quote=True)


def attachment_rows(report):
    rows = []
    for a in report["attachments"]:
        icon = a["assets"].get("icon")
        picture = f'<img class="attachment-icon" src="{h(icon)}" alt="{h(a["name"])}原图">' if icon else '<span class="missing-art">待新建</span>'
        links = " · ".join(f'<a href="{h(a["assets"][kind])}">{label}</a>' for kind, label in [("model", "原模型"), ("texture", "原纹理")] if kind in a["assets"])
        source_labels = {"support_pt": "官方支持", "steam27": "官方 PC #27", "console_archive_laser": "历史主机归档"}
        sources = " ".join(f'<a class="source" href="{h(s["url"])}" target="_blank" rel="noreferrer">{h(source_labels.get(s["key"], s["key"].replace("patch", "PC ").replace("_", ".")))}</a>' for s in a["sources"])
        count = f'{a["compatible_gun_count"]} 把原始静态兼容' if a["existing_cbrg"] else 'CBRG 尚缺'
        rows.append(f'<tr><th scope="row"><div class="item">{picture}<div>{h(a["name"])}<small>{h(a["id"])}</small></div></div><span class="badge">{h(a["selection"])}</span><small>{count} · {links}</small></th><td>{h(a["current_effect"])}</td><td>{h(a["official_effect"])}<div>{sources}</div></td><td>{h(a["proposal"])}</td></tr>')
    return "\n".join(rows)


def magazine_cards(report):
    cards = []
    for m in report["magazine_models"]:
        render_path = m.get("renderPath", m.get("render"))
        render = Path(render_path).relative_to("docs/attachments").as_posix()
        links = []
        for key, label in [("modelPath", "Bedrock 模型"), ("texturePath", "纹理"), ("blockbench", "Blockbench"), ("obj", "OBJ")]:
            if m.get(key):
                target = "../../" + m[key]
                assert (OUT / target).resolve().is_file(), target
                links.append(f'<a href="{h(target)}">{label}</a>')
        kind = m.get("provenance", {}).get("kind", "")
        provenance = "CBRG 原模型衍生" if kind == "local-source-derived" else "本地可编辑展示模型"
        status = "原生包已部署；由存档弹匣实验开关启用" if report["magazine_deployment"] else "尚未部署"
        cards.append(f'<article class="mag-card"><a href="{h(render)}"><img src="{h(render)}" alt="{h(m["label"])}" loading="lazy"></a><h3>{h(m["label"])}</h3><p>{provenance} · {status}</p><div>{" · ".join(links)}</div></article>')
    return "\n".join(cards)


def native_delivery(report):
    if not report["magazine_native_pack"] or not report["magazine_native_pack"].get("zip"):
        return ""
    deployed = bool(report["magazine_deployment"] and report["magazine_deployment"].get("installed"))
    summary = report["magazine_experiment_policy"].get("summary", {}) if report["magazine_experiment_policy"] else {}
    state = (f'已部署至当前开发实例；{summary.get("eligible", 0)} 把审核通过，'
             f'{summary.get("excluded", 0)} 把保留原规则。重启游戏后，在 '
             '<strong>config → 枪械设置 → 弹匣 → 实验性调整 → 保存全部</strong>，等待生效通知。'
             if deployed else '原生包已准备，尚未部署。')
    return f'''<div class="note"><strong>TaCZ 原生包与配套换弹附属。</strong>{state}<br>
    快速＝level 1＋速度 1.6；扩容＝level 2；快扩＝level 3＋速度 1.15，容量沿用每枪原等级表。
    切回初始撤销新配件兼容、扩容等级与加速，不删除物品或已装填弹药。<br>
    <a href="../../assets-workbench/pubg-magazines/pubg-magazines-tacz-pack.zip">TaCZ 原生包 ZIP</a> ·
    <a href="../../neoforge-1.21.1/build/libs/tacz_pubg-1.0.0.jar">本次配套附属 JAR</a> ·
    <a href="../../assets-workbench/pubg-magazines/TACZ-INTEGRATION.md">接入说明与实现边界</a> ·
    <a href="../compatibility/pubg-magazine-policy.json">424 枪逐项策略</a> ·
    <a href="../../assets-workbench/pubg-magazines/tacz-pack-validation.json">原生资源检查结果</a><br>
    构建、静态与时序检查通过，尚未启动游戏实测。图内“未安装”是模型定稿时的标注；当前文件部署状态以此处为准。
    枪上仍沿用原生挂点／等级弹匣骨骼；预览图为这九种配件自身模型。</div>'''


def render_html(report):
    sources = "\n".join(f'<li><a href="{h(s["url"])}" target="_blank" rel="noreferrer">{h(s["title"])}</a><small>{h(s["date"])} · {h(s.get("note", ""))}</small></li>' for s in report["sources"])
    data = json.dumps({"guns": report["guns"], "attachments": report["attachments"], "slots": SLOTS, "roles": ROLES}, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
    template = '''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>CBRG 配件 × PUBG 端游 · 对照与适配方案</title>
<style>
:root{--bg:#101715;--panel:#19231f;--panel2:#202d27;--line:#34443a;--text:#ecf0e7;--muted:#afbeb1;--accent:#e0bc76;--green:#9dd4aa;--red:#e9ad98}*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.7 "Microsoft YaHei UI","Microsoft YaHei",sans-serif}a{color:var(--accent);text-underline-offset:4px}main{max-width:1540px;margin:auto;padding:42px 40px 60px}.eyebrow{color:var(--accent);letter-spacing:2px;font-size:12px;font-weight:700}h1{font-size:42px;line-height:1.25;margin:14px 0 18px;letter-spacing:-1px}h2{font-size:27px;margin:0 0 15px;line-height:1.4}h3{font-size:17px;line-height:1.6}p{margin:10px 0}.intro{max-width:1000px;color:var(--muted);font-size:17px}.state{display:inline-block;border:1px solid #6a5840;color:var(--accent);background:#2e291f;border-radius:4px;padding:3px 11px;font-size:13px}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin:28px 0}.stat{border:1px solid var(--line);background:var(--panel);padding:17px 22px;border-radius:8px}.stat b{font-size:34px;color:var(--accent);line-height:1.4}.stat span{display:block;color:var(--muted);font-size:13px}nav{display:flex;gap:26px;flex-wrap:wrap;position:sticky;top:0;z-index:5;background:#101715f5;border-block:1px solid var(--line);padding:15px 0;backdrop-filter:blur(12px)}nav a{text-decoration:none;font-weight:700;font-size:14px}section{padding-top:38px;scroll-margin-top:62px}.lead{color:var(--muted);max-width:1100px}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:18px}.card{background:var(--panel);border:1px solid var(--line);padding:20px 23px;border-radius:8px}.card h3{margin:0 0 9px;color:var(--accent)}.card p{font-size:14px;color:var(--muted)}.note{border-left:3px solid var(--accent);padding:13px 18px;margin:18px 0;background:#25261d;font-size:14px}.alert{border-color:var(--red);background:#2d231e}.table-wrap{overflow:auto;border:1px solid var(--line);border-radius:8px;margin:19px 0}table{border-collapse:collapse;width:100%;font-size:14px}th,td{vertical-align:top;text-align:left;padding:17px 18px;border-bottom:1px solid var(--line)}thead{background:var(--panel2);color:var(--accent)}tbody tr:nth-child(even){background:#17211c}tbody th{font-weight:600;min-width:225px}#comparison table{min-width:1100px;table-layout:fixed}#comparison thead th:first-child{width:23%}#comparison thead th{width:25.7%}small{display:block;color:var(--muted);font-size:12px;font-weight:400;line-height:1.65;margin-top:5px;overflow-wrap:anywhere}.item{display:flex;gap:12px;align-items:center;min-height:60px}.attachment-icon{height:58px;width:58px;object-fit:contain;background:#101713;border-radius:6px;image-rendering:auto}.missing-art{display:grid;place-items:center;flex:none;width:58px;height:58px;border:1px dashed var(--line);border-radius:6px;color:var(--muted);font-size:12px}.badge{display:inline-block;border-radius:3px;background:#334235;color:#d8e4c8;padding:2px 7px;font-size:11px;margin:8px 0 3px}.source{display:inline-block;margin:9px 5px 0 0;font-size:11px;background:#263329;border:1px solid var(--line);border-radius:3px;padding:1px 6px}.mag-overview{display:block;width:100%;border:1px solid var(--line);border-radius:8px;margin:20px 0}.mag-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}.mag-card{border:1px solid var(--line);background:var(--panel);border-radius:8px;overflow:hidden;padding-bottom:16px}.mag-card img{display:block;width:100%;aspect-ratio:1.32;object-fit:contain;background:#141b18}.mag-card h3,.mag-card p,.mag-card div{margin:8px 16px}.mag-card p,.mag-card div{font-size:12px;color:var(--muted)}.mag-card h3{font-size:15px}.controls{display:flex;gap:12px;flex-wrap:wrap;align-items:center;padding:17px;background:var(--panel);border:1px solid var(--line);border-radius:8px}input,select,button{font:inherit;color:var(--text);background:#101914;border:1px solid #4a5b4e;border-radius:5px;padding:9px 12px}input{min-width:280px;flex:1}button{cursor:pointer}button:hover{border-color:var(--accent)}button:disabled{opacity:.4;cursor:default}.counter{color:var(--muted);font-size:13px;margin:11px 0}.gun-table{min-width:1190px}.gun-table th:nth-child(1){width:25%}.gun-table th:nth-child(2){width:16%}.gun-table th:nth-child(3){width:23%}.gun-table th:nth-child(4){width:18%}.gun-table td{padding:13px 15px}.gun-id{font-family:Consolas,monospace;font-size:11px}.gun-icon{width:75px;height:64px;object-fit:contain;background:#0e1711;border-radius:4px;flex:none}.gun-name{font-weight:700}.ok{color:var(--green)}.warn{color:var(--red)}details{margin-top:9px;font-size:12px}summary{cursor:pointer;color:var(--accent)}.support-detail{display:grid;grid-template-columns:1fr;gap:3px;padding:9px 0}.pager{display:flex;gap:12px;align-items:center;justify-content:flex-end}.source-list{columns:2;column-gap:40px;padding-left:21px}.source-list li{break-inside:avoid;margin-bottom:17px;font-size:14px}.steps{padding-left:22px}.steps li{margin:10px 0}.formula{font-family:Consolas,monospace;color:var(--green)}footer{border-top:1px solid var(--line);padding-top:20px;margin-top:35px;color:var(--muted);font-size:12px}@media(max-width:900px){main{padding:26px 18px}h1{font-size:31px}.stats{grid-template-columns:repeat(2,1fr)}.grid,.mag-grid{grid-template-columns:1fr}.source-list{columns:1}nav{gap:16px}.mag-card img{max-height:320px}.controls input{min-width:200px}}@media print{body{background:white;color:#17251b}main{max-width:none;padding:0}nav,.controls,.pager{display:none}.card,.stat,thead,.note{background:#edf1eb;color:#17251b}.table-wrap{overflow:visible}#comparison table,.gun-table{min-width:0}a{color:#4b663d}.mag-grid{grid-template-columns:repeat(3,1fr)}section{break-inside:avoid}small,.lead,.intro{color:#485c4b}}
</style></head><body><main>
<div class="eyebrow">CBRG ATTACHMENT REVIEW / PUBG: BATTLEGROUNDS PC / 2026.10.18</div>
<h1>CBRG 配件，按 PUBG 端游重整</h1>
<p class="intro">从现有真实配件出发：看清当前效果，选择有分工的核心组合，再按每把枪的槽位、供弹机构与模型挂点适配。</p>
<span class="state">__DEPLOYMENT_STATE__ · 未启动游戏实测</span>
<div class="stats"><div class="stat"><b>15</b><span>现有非瞄具配件 / 全包共 24 件</span></div><div class="stat"><b>424</b><span>静态可用枪械候选 / 完整逐枪表</span></div><div class="stat"><b>9</b><span>弹匣展示模型 / 三族 × 三种</span></div><div class="stat"><b>__MOUNT_COUNT__</b><span>非瞄具允许关系需核对常规挂点</span></div></div>
<nav><a href="#selection">推荐组合</a><a href="#comparison">属性对照</a><a href="#magazines">九种弹匣</a><a href="#guns">全部枪械适配</a><a href="#sources">来源与边界</a></nav>
<section id="selection"><h2>选择有取舍的组合</h2><p class="lead">以 PC 43.2 时点已核实的官方更新为基准。直角握把保留为经典选项；追随现行端游的新配件单列待新增，不把 CBRG 原物品改名充数。</p>
<div class="grid"><article class="card"><h3>握把与激光 · 同槽择一</h3><p>垂直：连射纵向控制；半截：双轴连射与稳定性代价；拇指：快速开镜；轻型：首发、点射与 DMR。激光走腰射路线，沿用 GRIP 槽与握把互斥。</p><p>直角握把列经典可选，41.1 已移出世界刷新；新斜向握把需另做物品、模型与镜头震动能力。</p></article>
<article class="card"><h3>枪口 · 控枪、消焰、隐蔽</h3><p>补偿器、消焰器、消音器保留三条路线。先解决当前消焰器不隐藏枪焰、消音器负声音广播半径的问题，再讨论精确数值平衡。</p><p>CBRG 没有独立的 SMG／SR 补偿器与消焰器；新制退器也需单列新增。</p></article>
<article class="card"><h3>枪托与弹匣 · 按硬件适配</h3><p>现有托腮板用于实际有贴腮安装位置的枪。战术／重型枪托需新增，彼此同槽互斥；没有肩托或合适挂点的枪不强行开放。</p><p>九个弹匣是三类外观与功能标记。DMR 可用步枪族及 SR 族；每枪容量与换弹独立配置。</p></article></div>
<div class="note"><strong>三个数字必须分清：</strong>官网“控制 +15%”、TaCZ 参数“×0.8696”、实际幅度“减少 13.04%”不是同一种指标。多配件乘数相乘，不能把减少百分比简单相加。</div>
</section>
<section id="comparison"><h2>现有属性与官方 PC 对照</h2><p class="lead">图片、模型、纹理链接均来自现有本地资源。下表的“方案”是待实施设计；官方未公开的当前精确百分比保留空缺。</p><div class="table-wrap"><table><thead><tr><th>配件与选择</th><th>当前 CBRG / TaCZ 实际行为</th><th>PUBG 官方可核实内容</th><th>建议映射与缺失能力</th></tr></thead><tbody>__ATTACHMENTS__</tbody></table></div>
<div class="note alert"><strong>已经查明的偏差：</strong>CBRG 的 4 把枪（Beryl 两种外观、Groza、SLR）快速／快扩等级未接换弹加速；消焰器当前不隐藏枪焰；三种消音器在当前基础声距 64 下把周围枪声广播半径算成 −32。这些结论来自本地源码审计，尚未在游戏中测试。</div>
<p><a href="local-audit-notes.md">查看本地源码位置与计算依据</a> · <a href="cbrg-attachments-current.json">原始配件属性与资源</a> · <a href="attachment-guide.csv">下载对照 CSV</a> · <a href="attachment-guide.json">完整方案 JSON</a></p>
</section>
<section id="magazines"><h2>九种弹匣：三类造型，三种功能</h2><p class="lead">步枪／机枪／DMR，手枪／SMG，SR／DMR。DMR 双族共用一个弹匣槽，容量与速度不叠加。模型已经定稿，图为实际导出几何渲染。</p>
<p><a href="../../assets-workbench/pubg-magazines/reference/pubg-api/reference-overview.png">官方物品图参考板</a> · <a href="../../assets-workbench/pubg-magazines/reference/pubg-api/reference-sources.json">九张原图来源与 SHA-256</a> · <a href="https://github.com/pubg/api-assets">PUBG 官方 api-assets</a>__MODEL_COMPARISON_LINK__</p><p class="lead">官方物品图用于形状参考；展示模型是本地可编辑低面数改编，不是 PUBG 原始三维模型提取。</p>
<a href="magazines/magazine-overview.png"><img class="mag-overview" src="magazines/magazine-overview.png" alt="九种弹匣模型总览" loading="lazy"></a>
__TACZ_DELIVERY__
<div class="table-wrap"><table><thead><tr><th>功能</th><th>TaCZ 容量规则</th><th>项目换弹倍率</th><th>依据与限制</th></tr></thead><tbody><tr><th>快速</th><td>扩容等级 1，读取各枪现有对应容量</td><td>用时 ×0.625（速度 +60%）</td><td rowspan="3">按用户确定的等级规则：快速＝1＋加速，扩容＝2，快扩＝3＋加速。PC 15.1 的 M416 示例为基础／快速／扩容／快扩 30／30／42／40 发，不能推广到所有枪。<strong>官网没有公布时间公式</strong>，倒数速度换算是本项目近似。<br><a href="https://www.pubg.com/en/news/1727">官方 15.1</a></td></tr><tr><th>扩容</th><td>扩容等级 2，读取各枪现有对应容量</td><td>用时 ×1，不增加加速</td></tr><tr><th>快速扩容</th><td>扩容等级 3，读取各枪现有对应容量</td><td>用时 ×0.869565（速度 +15%）</td></tr></tbody></table></div>
<div class="note">Mk12 的官方发布表明确可用 AR／SR 两类弹匣；Dragunov 明确支持 AR／DMR。机枪需区分盒式弹匣、弹鼓、弹链；固定内置弹仓、转轮弹巢、管状供弹、发射器不自动套用。<a href="https://www.pubg.com/en/news/1740">Mk12</a> · <a href="https://pubg.com/en/news/6616">Dragunov</a></div>
<div class="mag-grid">__MAGAZINES__</div>
<h3>CBRG 四把枪：当前容量与真实总换弹时间</h3><div class="table-wrap"><table><thead><tr><th>枪械</th><th>基础 / 快速 / 扩容 / 快扩</th><th>战术 / 空仓总时长</th><th>当前状态</th></tr></thead><tbody><tr><th>Beryl M762，两种外观</th><td>30 / 30 / 42 / 40</td><td>2.45 s / 3.62 s</td><td rowspan="3">1／3 等级目前没有加速这 4 把枪。总结束时刻取 cooldown，不能把 feed 与 cooldown 相加。服务器装填、结束时刻和客户端动画需同时适配。</td></tr><tr><th>Groza</th><td>30 / 30 / 42 / 40</td><td>2.25 s / 2.88 s</td></tr><tr><th>SLR</th><td>10 / 10 / 22 / 20</td><td>2.83 s / 3.60 s</td></tr></tbody></table></div>
</section>
<section id="guns"><h2>全部 424 把枪：现状与适配待办</h2><p class="lead">当前兼容 = 原始槽位开放且标签允许；挂点仍需独立核对。下表保留原包事实，不把方案写成已经生效。枪械角色沿用项目瞄具表的分类，DMR 可能原生注册为 rifle；这不构成非 PUBG 枪的官方逐枪许可。</p>
<div class="controls"><input id="search" aria-label="搜索枪械" placeholder="搜索枪名、ID、命名空间或配件名称…"><select id="role" aria-label="筛选枪械角色"><option value="">全部枪械角色</option></select><select id="pack" aria-label="筛选命名空间"><option value="">全部命名空间</option></select><select id="review" aria-label="筛选适配状态"><option value="">全部适配状态</option><option value="mount">常规挂点待核</option><option value="mag">原有弹匣槽</option><option value="no-mag">原无弹匣槽</option><option value="builtin">有内置配件</option><option value="dmr">DMR 双弹匣族候选</option></select><button id="reset">重置</button></div>
<div id="counter" class="counter" aria-live="polite"></div><div class="table-wrap"><table class="gun-table"><thead><tr><th>枪械 / 原生类型</th><th>原始开放槽</th><th>现有非瞄具兼容</th><th>弹匣族候选 / 当前容量</th><th>适配复核</th></tr></thead><tbody id="gun-body"></tbody></table></div><div class="pager"><button id="prev">上一页</button><span id="page-label"></span><button id="next">下一页</button></div>
<p><a href="current-gun-attachment-matrix.csv">原始逐枪矩阵 CSV</a> · <a href="current-gun-attachment-matrix.json">完整原始槽位、挂点与专属覆盖</a> · <a href="../compatibility/pubg-scope-policy.html">既有瞄具允许表</a></p>
<h3>适配顺序</h3><ol class="steps"><li><strong>按真实能力保留原槽。</strong>先检查枪口、下导轨、肩托、供弹机构和内置配件；没有原槽默认不新增。</li><li><strong>标签、实体槽位与模型同时通过。</strong>核对 full／LOD 挂点、adapter、默认零件隐藏和握持姿态；仅有标签不能证明装得上。</li><li><strong>每枪专属数据覆盖。</strong>容量、快速换弹、枪口尺寸与偏移逐枪明确；9 种展示外观不能自动通用于 424 把枪的装配尺寸。</li><li><strong>补齐独立效果能力。</strong>首发控制、恢复、呼吸晃动、视觉镜头震动、腰射散布、ADS 分别处理；修正消音广播与消焰实现。</li><li><strong>作为可逆实验实施后再验证。</strong>当前仅文档与展示资产；后续需分别验证第一／第三人称、地面模型和服务器时序。</li></ol>
<div class="note">全 CBRG 24 件配件共 66 条“允许但未找到常规挂点／等级弹匣骨骼”关系，其中非瞄具 __MOUNT_COUNT__ 条。它们是人工几何复核候选，不是已经证实的游戏渲染错误。</div>
</section>
<section id="sources"><h2>来源与未核实内容</h2><p class="lead">官方数值依据相关版本公告重建，最新核查到 PC 43.2；不是现行客户端逐项提取。官方支持页及 2019 公告用于定性／历史依据；主机激光归档不充当现行 PC 精确数值证据。</p>
<div class="note"><strong>仍缺：</strong>全部枪的现行三弹匣容量表；PUBG 官方速度到时长的引擎公式；补偿器、消焰器、消音器、战术枪托、托腮板及激光的完整现行精确百分比；每枪实际装配贴合和运行时验证。</div><ol class="source-list">__SOURCES__</ol>
<p><a href="official-pubg-references.md">官网研究说明</a> · <a href="official-pubg-references.json">结构化官方证据</a> · <a href="current-audit-validation.json">原枪包未修改的审计校验</a></p></section>
<footer>生成自本地审计、官方证据与九种模型清单。弹匣原生包与附属部署状态见上文；原枪包保留。未启动 Minecraft 进行本次游戏内验证。页面离线可筛选。</footer>
<script id="report-data" type="application/json">__DATA__</script><script>
const D=JSON.parse(document.getElementById('report-data').textContent),G=D.guns,A=Object.fromEntries(D.attachments.map(a=>[a.id,a]));
const $=id=>document.getElementById(id),esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let page=0,filtered=G;const size=25;
Object.entries(D.roles).forEach(([v,n])=>$('role').insertAdjacentHTML('beforeend',`<option value="${esc(v)}">${esc(n)}</option>`));
[...new Set(G.map(g=>g.namespace))].sort().forEach(n=>$('pack').insertAdjacentHTML('beforeend',`<option value="${esc(n)}">${esc(n)}</option>`));
function gunRow(g){const groups=Object.keys(D.slots).filter(t=>t!=='scope'&&t!=='laser').map(t=>{let ids=g.current_compatible.filter(id=>A[id].type===t);return `${esc(D.slots[t])}：${ids.length?ids.map(id=>esc(A[id].name)).join('、'):'—'}`}).join('<br>');
const status=Object.entries(g.current_support).map(([id,v])=>{let txt=!v.slot_open?'原槽未开放':!v.tag_allowed?'标签不允许':v.mount_or_builtin_mag_nodes_present?'原槽＋标签允许':'允许，常规挂点待核';return `<span class="${v.current_static_compatible?(v.mount_or_builtin_mag_nodes_present?'ok':'warn'):''}">${esc(A[id].name)}：${txt}</span>`}).join('');
const cap=`${esc(g.base_capacity)} / ${g.level_capacities?.length?g.level_capacities.map(esc).join(' / '):'未声明等级容量'}`;
return `<tr><td><div class="item"><img class="gun-icon" loading="lazy" src="${esc(g.icon)}" alt="${esc(g.name)}"><div class="gun-name">${esc(g.name)}<small>${esc(g.name_en)}</small></div></div><small class="gun-id">${esc(g.id)}</small><small>${esc(g.role_label)} · 原生 ${esc(g.gun_type)}</small><details><summary>角色与内置数据依据</summary><small>${esc(g.role_basis)}；${esc(g.role_note)}</small><small>内置：${esc(JSON.stringify(g.builtin_attachments))}<br>服务端脚本：${esc(g.gun_script||'未声明')}</small></details></td><td>${g.original_slots.map(t=>esc(D.slots[t]||t)).join(' · ')||'无'}<small>“激光槽”不代表可安装 CBRG 的 GRIP 型激光。</small></td><td>${groups}<details><summary>展开 15 件逐项关系</summary><div class="support-detail">${status}</div></details></td><td>${g.magazine_family_candidates.length?g.magazine_family_candidates.map(esc).join('<br>')+'<small>仅候选，供弹机构待核</small>':'不自动分配通用族'}<small>当前基础 / level1 / 2 / 3<br>${cap}</small></td><td>${g.mount_review.length?`<span class="warn">${g.mount_review.length} 件常规挂点待核</span>`:'<span class="ok">无已记录的缺挂点兼容关系</span>'}<details><summary>逐枪适配待办</summary>${g.adaptation_plan.map(p=>`<p>${esc(p)}</p>`).join('')}<small>当前换弹数据：${esc(JSON.stringify(g.reload))}</small></details></td></tr>`;}
function draw(){const total=Math.max(1,Math.ceil(filtered.length/size));page=Math.min(page,total-1);$('gun-body').innerHTML=filtered.slice(page*size,(page+1)*size).map(gunRow).join('')||'<tr><td colspan="5">没有匹配的枪械。</td></tr>';$('counter').textContent=`匹配 ${filtered.length} / ${G.length} 把枪 · 每页 ${size} 把 · 展开行内详情查看完整关系`;$('page-label').textContent=`${page+1} / ${total}`;$('prev').disabled=page===0;$('next').disabled=page>=total-1;}
function filter(){const q=$('search').value.trim().toLowerCase(),r=$('role').value,p=$('pack').value,v=$('review').value;filtered=G.filter(g=>(!r||g.role===r)&&(!p||g.namespace===p)&&(!q||[g.id,g.name,g.name_en,g.namespace,...g.current_compatible.map(id=>A[id].name)].join(' ').toLowerCase().includes(q))&&(!v||(v==='mount'&&g.mount_review.length)||(v==='mag'&&g.original_slots.includes('extended_mag'))||(v==='no-mag'&&!g.original_slots.includes('extended_mag'))||(v==='builtin'&&Object.keys(g.builtin_attachments).length)||(v==='dmr'&&g.role==='DMR'&&g.magazine_family_candidates.length===2)));page=0;draw();}
['search','role','pack','review'].forEach(id=>$(id).addEventListener(id==='search'?'input':'change',filter));$('reset').onclick=()=>{['search','role','pack','review'].forEach(id=>$(id).value='');filter()};$('prev').onclick=()=>{page--;draw()};$('next').onclick=()=>{page++;draw()};draw();
</script></main></body></html>'''
    return (template.replace("__ATTACHMENTS__", attachment_rows(report)).replace("__MAGAZINES__", magazine_cards(report))
            .replace("__SOURCES__", sources).replace("__DATA__", data)
            .replace("__MODEL_COMPARISON_LINK__", ' · <a href="magazines/magazine-reference-comparison.png">官方图与新模型对照</a>' if report["magazine_visual_references"]["comparison"] else '')
            .replace("__TACZ_DELIVERY__", native_delivery(report))
            .replace("__DEPLOYMENT_STATE__", "弹匣实验已部署；其余配件为审阅方案" if report["magazine_deployment"] else "审阅方案 · 尚未部署")
            .replace("弹匣族候选 / 当前容量", "弹匣实验适配 / 原等级容量")
            .replace("仅候选，供弹机构待核", "弹匣实验开关启用时可装配")
            .replace("不自动分配通用族", "不授权新九种弹匣")
            .replace("当前仅文档与展示资产；后续需分别验证", "弹匣实验代码与资源已交付；后续仍需分别验证")
            .replace("__MOUNT_COUNT__", str(report["counts"]["non_scope_mount_review_edges"])))


def main():
    report = build_report()
    assert report["counts"]["guns"] == 424
    assert report["counts"]["existing_non_scope_attachments"] == 15
    assert report["counts"]["magazine_models"] == 9
    assert not report["game_launched"]
    assert len({g["id"] for g in report["guns"]}) == 424
    assert all(len(g["current_support"]) == 15 for g in report["guns"])
    (OUT / "attachment-guide.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    columns = ["id", "name", "type", "existing_cbrg", "selection", "compatible_gun_count", "current_effect", "official_effect", "proposal", "sources"]
    with (OUT / "attachment-guide.csv").open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=columns)
        writer.writeheader()
        for a in report["attachments"]:
            row = {k: a[k] for k in columns}
            row["sources"] = " | ".join(s["url"] for s in a["sources"])
            writer.writerow(row)
    (OUT / "attachment-guide.html").write_text(render_html(report), encoding="utf-8")
    print(json.dumps({"outputs": [str(OUT / f"attachment-guide.{ext}") for ext in ["html", "json", "csv"]], "counts": report["counts"], "magazines_deployed": report["magazine_models_installed"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
