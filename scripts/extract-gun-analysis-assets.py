"""Extract installed TaCZ analysis inputs without changing runtime assets.

Reads compatibility-inventory.json and installed resources. Writes only the
requested analysis directory; never launches Minecraft or Gradle. Uses the
existing audit's lenient JSON parser, without invoking that audit's main().
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from contextlib import ExitStack
from datetime import datetime, timezone
import hashlib
import importlib.util
from io import BytesIO
import json
import math
from pathlib import Path
import re
import zipfile

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
FORMAT_CODES = re.compile(r"\u00a7[0-9a-fk-orx]", re.IGNORECASE)

# These are observations of authored fields, not a reconstruction of Java/Lua
# defaults. Missing values remain missing in numericFields and rawData.
FIELDS = {
    "rpm": "positive_for_rate_division",
    "ammo_amount": "positive_for_magazine_comparison",
    "bullet.damage": "positive_for_damage_log_or_ratio",
    "bullet.bullet_amount": "positive_for_pellet_comparison",
    "bullet.speed": "positive_for_flight_time_division",
    "bullet.life": "positive_for_range_comparison",
    "bullet.gravity": "zero_can_be_intentional",
    "bullet.friction": "zero_can_be_intentional",
    "bullet.knockback": "zero_can_be_intentional",
    "bullet.pierce": "zero_requires_runtime_semantics",
    "bullet.tracer_count_interval": "zero_can_disable_tracers",
    "bullet.extra_damage.head_shot_multiplier": "positive_for_damage_ratio",
    "bullet.extra_damage.armor_ignore": "zero_is_valid_fraction",
    "bullet.explosion.damage": "optional_explosion_field",
    "bullet.explosion.radius": "optional_explosion_field",
    "bullet.explosion.delay": "zero_can_mean_immediate",
    "draw_time": "zero_has_instant_action_semantics",
    "put_away_time": "zero_has_instant_action_semantics",
    "aim_time": "zero_has_instant_action_semantics",
    "sprint_time": "zero_has_instant_action_semantics",
    "bolt_action_time": "optional_action_field",
    "weight": "zero_does_not_establish_no_movement_penalty",
    "movement_speed.base": "signed_modifier_zero_is_valid",
    "movement_speed.aim": "signed_modifier_zero_is_valid",
    "movement_speed.reload": "signed_modifier_zero_is_valid",
    "move_speed.base": "legacy_or_custom_field_review_needed",
    "move_speed.aim": "legacy_or_custom_field_review_needed",
    "reload.feed.empty": "positive_for_reload_comparison",
    "reload.feed.tactical": "positive_for_reload_comparison",
    "reload.cooldown.empty": "positive_for_reload_comparison",
    "reload.cooldown.tactical": "positive_for_reload_comparison",
    "inaccuracy.stand": "zero_is_perfect_authored_accuracy",
    "inaccuracy.move": "zero_is_perfect_authored_accuracy",
    "inaccuracy.sneak": "zero_is_perfect_authored_accuracy",
    "inaccuracy.lie": "zero_is_perfect_authored_accuracy",
    "inaccuracy.aim": "zero_is_perfect_authored_accuracy",
    "burst_data.count": "optional_positive_for_burst_comparison",
    "burst_data.bpm": "optional_positive_for_burst_rate",
    "burst_data.min_interval": "zero_can_mean_no_burst_interval",
    "crawl_recoil_multiplier": "optional_recoil_modifier",
}


def audit_parser():
    path = ROOT / "scripts/audit-ammo-attachment-compatibility.py"
    spec = importlib.util.spec_from_file_location("tacz_asset_audit_parser", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.gson_json, module.resource


def clean_name(value):
    return FORMAT_CODES.sub("", str(value))


def safe_json(value):
    """Write standards-compliant JSON, retaining non-finite facts explicitly."""
    if isinstance(value, float) and not math.isfinite(value):
        return {"nonFiniteNumber": repr(value)}
    if isinstance(value, dict):
        return {str(k): safe_json(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [safe_json(v) for v in value]
    return value


def numeric_leaves(value, prefix=""):
    if isinstance(value, dict):
        for key in sorted(value):
            yield from numeric_leaves(value[key], f"{prefix}.{key}" if prefix else key)
    elif isinstance(value, list):
        for i, item in enumerate(value):
            yield from numeric_leaves(item, f"{prefix}[{i}]")
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        yield prefix, value


MISSING = object()


def at(value, path):
    for key in path.split("."):
        if not isinstance(value, dict) or key not in value:
            return MISSING
        value = value[key]
    return value


def numeric_status(value):
    if value is MISSING:
        return "missing"
    if isinstance(value, bool) or not isinstance(value, (float, int)):
        return "non_numeric"
    if not math.isfinite(value):
        return "non_finite"
    if value == 0:
        return "zero"
    if value < 0:
        return "negative"
    return "positive"


class Resources:
    """Small read-only index; original source strings are preserved verbatim."""
    def __init__(self, root, inventory, stack):
        self.root = root
        self.entries = defaultdict(list)
        self.readers = {}
        packs = inventory["packages"]
        for package in sorted(packs, key=lambda p: p["path"]):
            path = root / package["path"].replace("\\", "/")
            prefix = "run/tacz/" + path.name
            if package["kind"] == "directory":
                for file in sorted(path.rglob("*")):
                    if file.is_file():
                        entry = file.relative_to(path).as_posix()
                        self.add(entry, prefix + "/" + entry, file.read_bytes)
            else:
                archive = stack.enter_context(zipfile.ZipFile(path))
                self.add_archive(archive, prefix)
        for jar in sorted((root / "neoforge-1.21.1/run/mods").glob("*.jar")):
            archive = stack.enter_context(zipfile.ZipFile(jar))
            self.add_archive(archive, "run/mods/" + jar.name)

    def add_archive(self, archive, prefix):
        for entry in archive.namelist():
            if not entry.endswith("/") and entry.startswith(("assets/", "data/")):
                self.add(entry, prefix + "!" + entry,
                         lambda e=entry, a=archive: a.read(e))

    def add(self, entry, source, read):
        self.entries[entry].append(source)
        self.readers[source] = read

    def read(self, source):
        return self.readers[source]()

    def sources(self, entry, preferred_source=None):
        values = sorted(self.entries.get(entry, []))
        preferred_pack = (preferred_source.split("!", 1)[0] if "!" in preferred_source
                          else "/".join(preferred_source.split("/")[:3])) if preferred_source else None
        # A gun's authored display pack is the first analysis choice, followed
        # by lexical source order. This is not a claim about runtime pack order.
        return sorted(values, key=lambda s: (
            not (preferred_pack and (s.startswith(preferred_pack + "!")
                                     or s.startswith(preferred_pack + "/"))), s))


def translation_sources(resources, gson_json):
    result = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
    for entry in sorted(resources.entries):
        match = re.fullmatch(r"assets/[^/]+/lang/(zh_cn|en_us)\.json", entry)
        if not match:
            continue
        for source in sorted(resources.entries[entry]):
            data = gson_json(resources.read(source).decode("utf-8-sig"))
            if isinstance(data, dict):
                for key, value in data.items():
                    result[match[1]][key][str(value)].append(source)
    return result


def placeholder(identifier, size):
    image = Image.new("RGBA", (size, size // 2), (27, 31, 40, 180))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, size - 1, size // 2 - 1), outline=(222, 164, 60, 255))
    font = ImageFont.load_default(size=10)
    ns, local = identifier.split(":", 1)
    # Clearly identified substitute, never a synthesized weapon silhouette.
    for y, text in ((5, "NO ICON"), (21, ns[:17]), (37, local[:17])):
        draw.text((4, y), text, font=font, fill=(242, 222, 188, 255))
    return image


def extract_icon(gun, resources, gson_json, resource, output, max_size):
    identifier = gun["id"]
    slug = re.sub(r"[^A-Za-z0-9_.-]+", "__", identifier)
    name = slug + "__" + hashlib.sha256(identifier.encode()).hexdigest()[:8] + ".png"
    target = output / "icons" / name
    display = gun.get("displays", [])[0] if gun.get("displays") else {}
    variant = display.get("variants", [])[0] if display.get("variants") else {}
    display_sources = variant.get("sources", [])
    display_source = sorted(display_sources)[0] if display_sources else None
    attempts, failure = [], None
    value = {}
    if display_source:
        try:
            value = gson_json(resources.read(display_source).decode("utf-8-sig"))
        except Exception as exc:
            failure = "display_read_error: " + str(exc)
    else:
        failure = "missing_display_variant"
    for field in ("slot", "hud"):
        reference = value.get(field)
        entry = resource(reference, "textures", ".png", "assets")
        sources = resources.sources(entry, display_source) if entry else []
        if not sources:
            attempts.append({"field": field, "reference": reference,
                             "resource": entry, "error": "missing_resource_or_reference"})
        for source in sources:
            try:
                raw_bytes = resources.read(source)
                with Image.open(BytesIO(raw_bytes)) as original:
                    original.load()
                    image = original.convert("RGBA")
                original_size = image.size
                original_pixels_sha256 = hashlib.sha256(image.tobytes()).hexdigest()
                resized = max(image.size) > max_size
                if resized:
                    image.thumbnail((max_size, max_size), Image.Resampling.LANCZOS)
                image.save(target, "PNG")
                with Image.open(target) as written:
                    assert written.size == image.size
                    assert written.convert("RGBA").tobytes() == image.tobytes()
                return {
                    "path": "icons/" + name,
                    "workspacePath": target.relative_to(ROOT).as_posix()
                    if target.is_relative_to(ROOT) else str(target),
                    "source": source, "resource": entry,
                    "sourceCandidates": sources, "displaySource": display_source,
                    "displayResource": display.get("resource"),
                    "field": field, "reference": reference,
                    "originalSize": list(original_size), "size": list(image.size),
                    "resized": resized, "placeholder": False,
                    "rgbaPixelSha256": hashlib.sha256(image.tobytes()).hexdigest(),
                    "originalRgbaPixelSha256": original_pixels_sha256,
                    "sourceFileSha256": hashlib.sha256(raw_bytes).hexdigest(),
                    "alphaExtrema": list(image.getchannel("A").getextrema()),
                    "attempts": attempts,
                }
            except Exception as exc:
                attempts.append({"field": field, "reference": reference,
                                 "resource": entry, "source": source, "error": str(exc)})
    image = placeholder(identifier, max_size)
    image.save(target, "PNG")
    return {"path": "icons/" + name,
            "workspacePath": target.relative_to(ROOT).as_posix()
            if target.is_relative_to(ROOT) else str(target),
            "source": None, "resource": None, "field": None,
            "displaySource": display_source, "displayResource": display.get("resource"),
            "size": list(image.size), "originalSize": None,
            "placeholder": True, "resized": False,
            "reason": failure or "no_readable_slot_or_hud_texture", "attempts": attempts}


def special_flags(identifier, index, data, names):
    """Explicit authored properties or labeled ID/name hints, not new classes."""
    flags = []
    def add(code, evidence, interpretation):
        flags.append({"code": code, "evidence": evidence, "interpretation": interpretation})
    if index.get("type") == "rpg":
        add("index_type_rpg", {"index.type": "rpg"}, "作者标记为 rpg；单独检查发射器逻辑。")
    explosion = at(data, "bullet.explosion")
    if isinstance(explosion, dict):
        enabled = explosion.get("explode") is True
        add("explosion_enabled" if enabled else "explosion_config_present_not_enabled",
            {"bullet.explosion": explosion},
            "显式启用爆炸。" if enabled else "存在爆炸参数但未显式启用，不能据此称为爆炸武器。")
    amount = at(data, "bullet.bullet_amount")
    if isinstance(amount, (int, float)) and not isinstance(amount, bool) and amount > 1:
        add("multiple_projectiles", {"bullet.bullet_amount": amount}, "单次发射多弹丸，单弹伤害不能直接当整次命中伤害。")
    if data.get("script"):
        add("scripted_gun_logic", {"script": data["script"], "script_param": data.get("script_param")},
            "脚本可能改变射击/装填等行为；本报告不执行或推断脚本。")
    for key in ("charging", "heat", "extras", "gunsmithlib_extension"):
        if key in data:
            add(key + "_configuration", {key: data[key]}, "存在扩展行为字段；通用属性评分需人工审查。")
    if "melee" in data:
        add("melee_configuration_present", {"melee": data["melee"]},
            "普通枪托近战也有此字段；字段存在不代表这是一把刀。")
    ignite = at(data, "bullet.ignite")
    if isinstance(ignite, dict) and any(v is True for v in ignite.values()):
        add("ignition_enabled", {"bullet.ignite": ignite}, "存在显式点燃行为。")
    for key in ("bullet.gravity", "bullet.friction", "bullet.speed", "bullet.damage"):
        if at(data, key) == 0:
            add(key.replace(".", "_") + "_zero", {key: 0}, "显式零值，仅作特殊行为/公式安全审查提示。")
    haystack = (identifier + " " + " ".join(names)).lower()
    hints = {
        "taser_id_or_name_hint": r"taser|stun.?gun|zeus|电击|泰瑟",
        "melee_id_or_name_hint": r"(?:^|[ :/_-])knife(?:$|[ :/_-])|katana|bayonet|刺刀|匕首|军刀|武士刀",
        "launcher_id_or_name_hint": r"launcher|grenade|榴弹|火箭筒|火箭弹|迫击炮",
        "shield_id_or_name_hint": r"shield|盾",
        "special_projectile_id_or_name_hint": r"\b(?:crossbow|bow|arrow|laser|beam)\b|雷射|激光|弩|弓箭",
    }
    for code, pattern in hints.items():
        match = re.search(pattern, haystack)
        if match:
            add(code, {"matchedText": match[0], "searched": "id + complete cleaned translation variants"},
                "仅 ID/作者名称提示，未改 index.type，未认定真实型号或实际运行机制。")
    reload_type = at(data, "reload.type")
    if reload_type == "inventory":
        add("inventory_reload", {"reload.type": reload_type},
            "作者指定直接使用物品栏弹药，不能强行把缺失的 ammo_amount 视为零弹匣。")
    return flags


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, default=ROOT / "docs/compatibility/compatibility-inventory.json")
    parser.add_argument("--output", type=Path, default=ROOT / "docs/gun-balance")
    parser.add_argument("--max-size", type=int, choices=(128, 256), default=128)
    args = parser.parse_args()
    output = args.output.resolve()
    # Never allow a mistaken output path to write into the live instance.
    if output == ROOT / "neoforge-1.21.1/run" or (ROOT / "neoforge-1.21.1/run") in output.parents:
        raise ValueError("analysis output cannot be inside the runtime directory")
    output.mkdir(parents=True, exist_ok=True)
    (output / "icons").mkdir(exist_ok=True)
    inventory_bytes = args.inventory.read_bytes()
    inventory = json.loads(inventory_bytes)
    candidates = sorted((g for g in inventory["guns"] if g.get("static_candidate")), key=lambda g: g["id"])
    assert len({g["id"] for g in candidates}) == len(candidates)
    gson_json, resource = audit_parser()
    field_counts = {path: Counter() for path in FIELDS}
    field_ids = {path: defaultdict(list) for path in FIELDS}
    field_ranges = defaultdict(list)
    guns, checks = [], []
    flag_ids, non_finite, all_numeric_counts = defaultdict(list), [], Counter()
    created = datetime.now(timezone.utc).isoformat()
    with ExitStack() as stack:
        resources = Resources(ROOT, inventory, stack)
        translations = translation_sources(resources, gson_json)
        for gun in candidates:
            index_variant = gun["index_variants"][0]
            index = index_variant["value"]
            case = gun["cases"][0]
            raw = case["raw_data"]
            zh_raw, en_raw = gun.get("name_zh_cn", []), gun.get("name_en_us", [])
            zh = list(dict.fromkeys(clean_name(s) for s in zh_raw))
            en = list(dict.fromkeys(clean_name(s) for s in en_raw))
            name_key = index.get("name")
            name_sources = {}
            for language, values in (("zh_cn", zh_raw), ("en_us", en_raw)):
                name_sources[language] = [{"raw": value, "cleaned": clean_name(value),
                    "sources": sorted({s for key in gun.get("name_keys", [name_key])
                        for s in translations[language][key].get(value, [])})} for value in values]
            flags = special_flags(gun["id"], index, raw, zh + en)
            for flag in flags:
                flag_ids[flag["code"]].append(gun["id"])
            leaves = dict(numeric_leaves(raw))
            for path, value in leaves.items():
                all_numeric_counts[path] += 1
                if not math.isfinite(value):
                    non_finite.append({"id": gun["id"], "path": path, "value": repr(value)})
            findings = []
            for path, rule in FIELDS.items():
                value = at(raw, path)
                status = numeric_status(value)
                field_counts[path][status] += 1
                field_ids[path][status].append(gun["id"])
                if status in ("positive", "negative", "zero"):
                    field_ranges[path].append(value)
                if status != "positive":
                    findings.append({"path": path, "status": status,
                        "authoredValue": None if value is MISSING else safe_json(value), "reviewRule": rule})
            special = {"id": gun["id"], "currentIndexType": index.get("type"),
                       "findings": findings, "specialFlags": flags,
                       "nonFiniteNumericLeaves": [{"path": p, "value": repr(v)}
                           for p, v in leaves.items() if not math.isfinite(v)],
                       "infiniteDistanceSentinels": [
                           {"index": i, "distance": v.get("distance"), "damage": v.get("damage")}
                           for i, v in enumerate(at(raw, "bullet.extra_damage.damage_adjust")
                               if isinstance(at(raw, "bullet.extra_damage.damage_adjust"), list) else [])
                           if isinstance(v, dict) and isinstance(v.get("distance"), str)]}
            checks.append(special)
            icon = extract_icon(gun, resources, gson_json, resource, output, args.max_size)
            guns.append({"id": gun["id"], "type": index.get("type"),
                "currentIndexType": index.get("type"),
                "name_zh": zh[0] if zh else None, "name_en": en[0] if en else None,
                "displayName": (zh or en or [name_key or gun["id"]])[0],
                "nameZhVariants": zh, "nameEnVariants": en,
                "namesRaw": {"zh_cn": zh_raw, "en_us": en_raw},
                "nameKey": name_key, "nameKeys": gun.get("name_keys", []),
                "translationSources": name_sources, "icon": icon,
                "chosenCandidate": {"indexVariant": 0, "case": 0,
                    "display": 0, "displayVariant": 0,
                    "indexSources": sorted(index_variant["sources"]),
                    "indexSource": sorted(index_variant["sources"])[0],
                    "indexResource": gun["resource"],
                    "indexSemanticSha256": index_variant.get("semantic_sha256"),
                    "dataSource": sorted(case["data_sources"])[0],
                    "dataSources": sorted(case["data_sources"]),
                    "dataResource": case["data_resource"],
                    "ambiguous": gun.get("ambiguous", False)},
                "rawIndex": index, "rawData": raw,
                "numericFields": {p: safe_json(v) for p, v in leaves.items()},
                "specialFlagCodes": [f["code"] for f in flags]})
    types = dict(sorted(Counter(g["type"] for g in guns).items()))
    icon_counts = {"real": sum(not g["icon"]["placeholder"] for g in guns),
                   "placeholder": sum(g["icon"]["placeholder"] for g in guns),
                   "resized": sum(g["icon"]["resized"] for g in guns),
                   "slot": sum(g["icon"]["field"] == "slot" for g in guns),
                   "hud": sum(g["icon"]["field"] == "hud" for g in guns)}
    manifest = {"schemaVersion": 1, "generatedAt": created,
        "inventorySource": str(args.inventory.resolve().relative_to(ROOT)).replace("\\", "/"),
        "inventorySha256": hashlib.sha256(inventory_bytes).hexdigest(),
        "candidateRule": "guns[].static_candidate == true; sort by id; index_variant[0] + cases[0]",
        "selectionCaveat": "Deterministic analysis selection, not a claim about runtime resource-pack precedence.",
        "sourceNotation": "run/tacz/<zip>!<entry>, run/tacz/<directory>/<entry>, or run/mods/<jar>!<entry>",
        "candidateCount": len(guns), "excludedInventoryIds": [g["id"] for g in inventory["guns"] if not g.get("static_candidate")],
        "typeCounts": types, "iconsDirectory": "icons", "iconCounts": icon_counts,
        "iconPolicy": {"maxDimension": args.max_size, "prefer": ["slot", "hud"],
            "processing": "RGBA pixels preserved for images within maxDimension; larger images only proportionally downscaled with Lanczos; no crop, recolor, synthesis or sharpening.",
            "naming": "sanitized ID plus first eight hex digits of ID SHA-256"},
        "guns": guns}
    audit = {"schemaVersion": 1, "generatedAt": created,
        "inventorySource": manifest["inventorySource"], "inventorySha256": manifest["inventorySha256"],
        "candidateCount": len(guns), "typeCounts": types, "iconCounts": icon_counts,
        "excludedInventoryIds": manifest["excludedInventoryIds"],
        "nameCoverage": {"missingZh": [g["id"] for g in guns if not g["name_zh"]],
                         "missingEn": [g["id"] for g in guns if not g["name_en"]]},
        "searchedHintCounts": {code: len(flag_ids.get(code, [])) for code in (
            "taser_id_or_name_hint", "melee_id_or_name_hint", "launcher_id_or_name_hint",
            "shield_id_or_name_hint", "special_projectile_id_or_name_hint")},
        "limitations": ["Static authored JSON only; no Java defaults, Lua evaluation, model/render or gameplay verification.",
            "Types are unchanged index.type; ID/name hints are not verified behavior or real-world model identification.",
            "Missing is not zero. Zero is not automatically erroneous; reviewRule states the modeling risk.",
            "melee configuration is common gun-butt behavior, not evidence that a gun is a knife.",
            "Explosion parameters with explode=false are reported separately from enabled explosions.",
            "String distance=infinite is an authored damage-range sentinel, not a non-finite numeric field."],
        "fields": {path: {"reviewRule": rule, "counts": dict(field_counts[path]),
            "finiteMin": min(field_ranges[path]) if field_ranges[path] else None,
            "finiteMax": max(field_ranges[path]) if field_ranges[path] else None,
            "idsByStatus": dict(field_ids[path])} for path, rule in FIELDS.items()},
        "allNumericPathCoverage": dict(sorted(all_numeric_counts.items())),
        "nonFiniteNumericLeaves": non_finite,
        "specialFlagCounts": {key: len(ids) for key, ids in sorted(flag_ids.items())},
        "specialFlagIds": dict(sorted(flag_ids.items())), "guns": checks}
    for name, value in (("gun-assets.json", manifest), ("属性字段静态审查.json", audit)):
        (output / name).write_text(json.dumps(safe_json(value), ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps({"candidateCount": len(guns), "typeCounts": types, "iconCounts": icon_counts,
        "nonFiniteCount": len(non_finite), "specialFlagCounts": audit["specialFlagCounts"],
        "output": str(output)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
