"""Measure original C4D sizes and apply explicit, reversible per-family item display baselines.

Original geometry and textures remain immutable. Display scales correct the independent
14-unit normalization; GUI icons retain their existing readable, individually fitted size.
The file-unit declaration is evidence, not an assumption about physically correct size.
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import html
import json
from pathlib import Path
import shutil

import numpy as np
from PIL import Image, ImageDraw

import c4d_reader as c4d
from render_preview import mesh_triangles, pixel_triangles, rasterize, font
from workflow_common import digest, rotate_matrix, write_json

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "model-workflows/output/pack_82"
ASSETS = ROOT / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg"
OUTPUT = ROOT / "model-workflows/output/supply_size_audit"
LABELS = {"vest_1": "一级甲", "vest_2": "二级甲", "vest_3": "三级甲",
          "backpack_1": "一级包", "backpack_2": "二级包", "backpack_3": "三级包", "parachute": "伞包物品",
          "med_kit": "医疗箱", "first_aid_kit": "急救包", "energy_drink": "能量饮料", "bandage": "绷带",
          "painkiller": "止痛药", "adrenaline_syringe": "肾上腺素", "frag_grenade": "破片手榴弹",
          "stun_grenade": "震爆弹", "smoke_grenade": "烟雾弹", "molotov": "燃烧瓶",
          "helmet_1": "一级头盔", "helmet_2": "二级头盔", "helmet_3": "三级头盔"}
UNIT_NAMES = {0: "undefined", 1: "km", 2: "m", 3: "cm", 4: "mm", 5: "micrometre", 6: "nm",
              7: "mile", 8: "yard", 9: "foot", 10: "inch"}
UNIT_METRES = {1: 1000, 2: 1, 3: .01, 4: .001, 5: 1e-6, 6: 1e-9,
               7: 1609.344, 8: .9144, 9: .3048, 10: .0254}
CONTEXTS = ("ground", "fixed", "thirdperson_righthand", "thirdperson_lefthand",
            "firstperson_righthand", "firstperson_lefthand", "gui")


def json_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def unit_record(source: Path):
    """Read the actual saved DocumentUnit typed payload, using the existing strict token reader."""
    root = c4d._read_document(source.read_bytes())
    found = []
    for node in root.walk(132):
        seq = node.items
        for index, key in enumerate(seq[:-2]):
            datatype = seq[index + 1]
            if not (isinstance(key, c4d.Value) and key.kind == 15 and key.data == 2117
                    and isinstance(datatype, c4d.Value) and datatype.kind == 15 and datatype.data == 200000275):
                continue
            payload = seq[index + 2]
            if not isinstance(payload, c4d.Chunk) or payload.ident != 0:
                raise ValueError("Unsupported DocumentUnit serialization")
            values = payload.values()
            if len(values) != 2 or values[0].kind != 19 or values[1].kind != 15:
                raise ValueError("Unexpected DocumentUnit payload")
            scale, unit = values[0].data, values[1].data
            found.append({"parameter": 2117, "datatype": 200000275, "offset_hex": hex(key.offset),
                          "scale": scale, "unit_id": unit, "unit": UNIT_NAMES.get(unit, "unknown"),
                          "declared_metres_per_unit": scale * UNIT_METRES[unit] if unit in UNIT_METRES else None,
                          "source_file_version": root.level})
    if len(found) != 1:
        raise ValueError(f"Expected exactly one DocumentUnit: {source}, found {len(found)}")
    return found[0]


def canonical(source_export: Path):
    for relative in ("scene.json", "source/scene.json", "playful/source/scene.json"):
        candidate = source_export / relative
        if candidate.is_file():
            return candidate, json.loads(candidate.read_text(encoding="utf-8"))
    raise FileNotFoundError(source_export / "scene.json")


def bounds(points):
    return [points.min(0).tolist(), points.max(0).tolist()]


def spans(points):
    return points.max(0) - points.min(0)


def display_points(points, display):
    # ItemTransform.apply: T * Quaternion.rotationXYZ * S * model-centering.
    rotation = display.get("rotation", [0, 0, 0])
    # workflow_common uses Rz*Ry*Rx; transpose/reverse angles yields Rx*Ry*Rz.
    matrix = rotate_matrix([-value for value in rotation]).T
    transformed = ((points / 16 - .5) * np.array(display.get("scale", [1, 1, 1]))) @ matrix.T
    return transformed + np.array(display.get("translation", [0, 0, 0])) / 16


def family(name, raw_max, raw_by_name):
    if name.startswith("vest_"):
        return "vest", .58 / raw_by_name["vest_3"], "三级甲最长边58cm锚点；同族保留原源数值比例"
    if name.startswith("backpack_") or name == "parachute":
        return "backpack", .60 / raw_by_name["backpack_2"], "二级包最长边60cm锚点；包族含伞包保留原源比例"
    if name in ("med_kit", "first_aid_kit"):
        return "medical_case", .027, "医疗箱/急救包同族0.027m/源数值；两者均增大并保留相互比例"
    if name.startswith("helmet_"):
        return "helmet", .0125, "头盔同族0.0125m/源数值；约30至35cm最长边"
    return "small_supply", .015, "其余手持物资同族0.015m/源数值；约厘米形状基准乘1.5可读性倍率"


def sources():
    result = []
    supplies = json.loads((ROOT / "model-workflows/output/supplies_integration/installation.json").read_text(encoding="utf-8"))
    for entry in supplies["items"]:
        name = entry["item"].split(":")[1]
        result.append({"name": name, "source_id": entry["source"].get("id", "recovered_" + name),
                       "source_export": entry["source_export"], "styles": "playful+pixel"})
    helmets = json.loads((ROOT / "model-workflows/output/helmet_pixel_integration/installation.json").read_text(encoding="utf-8"))
    for entry in helmets["items"]:
        result.append({"name": entry["item"].split(":")[1], "source_id": entry["source_id"],
                       "source_export": entry["source_export"], "styles": "pixel; existing playful unchanged"})
    if len(result) != 20 or len({entry["name"] for entry in result}) != 20:
        raise ValueError("Expected 17 supplies plus three pixel helmets")
    return result


def preview(rows, output):
    chosen = ["med_kit", "first_aid_kit", "energy_drink", "frag_grenade", "backpack_2"]
    by_name = {row["name"]: row for row in rows}
    tile, header, caption = 290, 95, 56
    canvas = Image.new("RGB", (tile * len(chosen), header + 4 * (tile + caption) + 54), "white")
    draw = ImageDraw.Draw(canvas)
    draw.text((18, 10), "物资比例修正前后 / 同一米尺、同一相机、无逐件自动放大", font=font(25), fill="#1e293b")
    draw.text((18, 49), "软件预览；每米280px。细节版和像素版均按真实源几何 + GROUND display 绘制。", font=font(17), fill="#475569")
    for column, name in enumerate(chosen):
        row = by_name[name]
        model = json.loads((ASSETS / f"models/item/{name}.json").read_text(encoding="utf-8"))
        mesh = json.loads((ASSETS / f"meshes/item/{name}.json").read_text(encoding="utf-8"))
        for view, (style, state) in enumerate((("playful", "before"), ("playful", "after"), ("pixel", "before"), ("pixel", "after"))):
            triangles = mesh_triangles(mesh) if style == "playful" else pixel_triangles(model)
            data = triangles.copy()
            data[:, :, :3] = display_points(data[:, :, :3].reshape(-1, 3), row[state + "_display"]["ground"]).reshape(-1, 3, 3)
            low, high = data[:, :, :3].reshape(-1, 3).min(0), data[:, :, :3].reshape(-1, 3).max(0)
            # Center each object only; use one fixed pixels/metre scale for every image.
            centre = (low + high) / 2
            texture_path = ASSETS / (f"textures/item/detail/{name}.png" if style == "playful" else f"textures/item/{name}.png")
            with Image.open(texture_path) as image:
                texture = np.asarray(image.convert("RGBA"))
            image = rasterize(data, texture, yaw=25, pitch=15, size=tile, center=centre, scale=280,
                              cull=style == "pixel", wrap=style == "playful")
            y = header + view * (tile + caption)
            draw.text((column * tile + 9, y + 3), f"{LABELS[name]} · {'细节' if style == 'playful' else '像素'} · {'修正前' if state == 'before' else '修正后'}",
                      font=font(17), fill="#1e293b")
            reference = row[state + "_ground_max_m"]
            draw.text((column * tile + 9, y + 27), f"最长边基准 {reference * 100:.1f} cm", font=font(16), fill="#475569")
            canvas.paste(image, (column * tile, y + caption))
            ruler_y = y + caption + tile - 18
            draw.line((column * tile + 20, ruler_y, column * tile + 160, ruler_y), fill="#0f172a", width=2)
            draw.text((column * tile + 165, ruler_y - 10), "50cm", font=font(13), fill="#0f172a")
    draw.text((18, canvas.height - 35), "仅用于相对尺寸核对；不是Minecraft截图，不验证镜头透视、手臂遮挡、贴地或实际帧率。", font=font(16), fill="#475569")
    canvas.save(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Back up and replace display transforms only")
    parser.add_argument("--preview", action="store_true", help="Draw a true-scale software comparison with the existing renderer")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    previous_report_path = OUTPUT / "size-baseline.json"
    previous_report = json.loads(previous_report_path.read_text(encoding="utf-8")) if previous_report_path.exists() else {}
    old_path = OUTPUT / "before-baseline.json"
    before = json.loads(old_path.read_text(encoding="utf-8")) if old_path.exists() else {}
    entries, units, source_hashes = [], {}, {}
    for entry in sources():
        scene_path, scene = canonical(Path(entry["source_export"]))
        metadata = scene["metadata"]
        normalization = metadata["normalization"]
        normalized = np.concatenate([np.array(mesh["vertices"]).reshape(-1, 8)[:, :3] for mesh in scene["meshes"]])
        original = (normalized - np.array(normalization["offset"])) / normalization["uniform_scale"]
        original = original @ rotate_matrix(normalization["rotation_degrees_xyz"])
        if normalization.get("reflected_axis") == "z":
            original[:, 2] *= -1
        source = Path(metadata["source"])
        if str(source) not in units:
            units[str(source)] = unit_record(source)
            source_hashes[str(source)] = digest(source)
            if source_hashes[str(source)] != metadata["source_sha256"]:
                raise ValueError(f"Source C4D has changed: {source}")
        entry.update({"source_scene": str(scene_path), "source_c4d": str(source), "source_sha256": source_hashes[str(source)],
                      "source_docunit": units[str(source)], "source_world_bounds": bounds(original),
                      "source_world_span": spans(original).tolist(), "raw_max": float(max(spans(original))),
                      "normalization": normalization})
        entries.append(entry)
    raw_by_name = {entry["name"]: entry["raw_max"] for entry in entries}
    backup = ROOT / "model-workflows/backups/supply_size_baseline" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    rows, changes = [], []
    for entry in entries:
        name = entry["name"]
        path = ASSETS / f"models/item/{name}.json"
        model = json.loads(path.read_text(encoding="utf-8"))
        mesh_path = ASSETS / f"meshes/item/{name}.json"
        mesh = json.loads(mesh_path.read_text(encoding="utf-8"))
        detail_points = np.array(mesh["vertices"]).reshape(-1, 8)[:, :3]
        pixel_points = pixel_triangles(model)[:, :, :3].reshape(-1, 3)
        before.setdefault(name, {"display": model.get("display", {}), "model_sha256": digest(path)})
        old_display = before[name]["display"]
        new_display = json.loads(json.dumps(old_display))
        group, metres_per_raw, policy = family(name, entry["raw_max"], raw_by_name)
        desired_max = entry["raw_max"] * metres_per_raw
        # Use the normalized source reference (14 units), without refitting either style's
        # voxel shell or existing playful helmet. Shape and quantization differences stay visible.
        base = desired_max / (entry["normalization"]["max_dimension"] / 16)
        for context in CONTEXTS:
            if context == "gui":
                continue
            source_context = "thirdperson_righthand" if context == "thirdperson_lefthand" else context
            transform = json.loads(json.dumps(new_display.get(context, new_display.get(source_context, {}))))
            factor = 1.35 if context.startswith("firstperson") else 1.10 if context.startswith("thirdperson") else 1
            transform["scale"] = [round(base * factor, 8)] * 3
            new_display[context] = transform
        if args.apply and model.get("display") != new_display:
            saved = backup / f"{name}.json"
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, saved)
            old_geometry_hash = json_digest({key: value for key, value in model.items() if key != "display"})
            mesh_hash = digest(mesh_path)
            texture_hashes = {str(p): digest(p) for p in (ASSETS / f"textures/item/{name}.png", ASSETS / f"textures/item/detail/{name}.png")}
            model["display"] = new_display
            write_json(path, model)
            reread = json.loads(path.read_text(encoding="utf-8"))
            assert old_geometry_hash == json_digest({key: value for key, value in reread.items() if key != "display"})
            assert digest(mesh_path) == mesh_hash and all(digest(Path(p)) == expected for p, expected in texture_hashes.items())
            changes.append({"item": "tacz_pubg:" + name, "path": str(path), "backup": str(saved),
                            "before_sha256": digest(saved), "after_sha256": digest(path),
                            "geometry_except_display_unchanged": True, "detailed_mesh_unchanged": mesh_hash,
                            "textures_unchanged": texture_hashes})
        row = {**entry, "label": LABELS[name], "family": group, "baseline_metres_per_source_number": metres_per_raw,
               "policy": policy, "after_ground_max_m": desired_max, "before_display": old_display, "after_display": new_display,
               "before_ground_max_m": entry["normalization"]["max_dimension"] / 16 * old_display["ground"]["scale"][0],
               "normalized_detail_span": spans(detail_points).tolist(), "normalized_pixel_span": spans(pixel_points).tolist(),
               "contexts": {}}
        row["ground_size_factor"] = desired_max / row["before_ground_max_m"]
        for context in CONTEXTS:
            old_transform = old_display.get(context, old_display.get("thirdperson_righthand", {}))
            new_transform = new_display.get(context, {})
            row["contexts"][context] = {
                "before_scale": old_transform.get("scale", [1, 1, 1]), "after_scale": new_transform.get("scale", [1, 1, 1]),
                "before_detail_span_m": spans(display_points(detail_points, old_transform)).tolist(),
                "after_detail_span_m": spans(display_points(detail_points, new_transform)).tolist(),
                "before_pixel_span_m": spans(display_points(pixel_points, old_transform)).tolist(),
                "after_pixel_span_m": spans(display_points(pixel_points, new_transform)).tolist()}
        rows.append(row)
    if args.apply:
        write_json(old_path, before, pretty=True)
    # Independent-file evidence against blindly accepting the 82-pack inch declaration.
    armor_inventory_path = ROOT / "model-workflows/output/armor_integration/inventory.json"
    armor_inventory = json.loads(armor_inventory_path.read_text(encoding="utf-8"))
    same_armor = next(obj for obj in armor_inventory["objects"] if obj["name"] == "0010_F_Armor_E_01")
    character_span = (np.array(same_armor["bounds"][1]) - np.array(same_armor["bounds"][0])).tolist()
    vest_1 = next(row for row in rows if row["name"] == "vest_1")
    vest_2 = next(row for row in rows if row["name"] == "vest_2")
    contradiction = {"pubg82_source": vest_1["source_c4d"], "pubg82_object": "F_Armor_E_01",
                     "pubg82_docunit": vest_1["source_docunit"], "pubg82_span": vest_1["source_world_span"],
                     "character_source": vest_2["source_c4d"], "character_object": "0010_F_Armor_E_01",
                     "character_docunit": vest_2["source_docunit"], "character_span": character_span,
                     "inventory_evidence": str(armor_inventory_path),
                     "height_numeric_difference_ratio": abs(vest_1["source_world_span"][1] / character_span[1] - 1),
                     "naive_document_unit_size_conflict": 2.54,
                     "conclusion": "Same armor keeps near-identical numeric geometry despite cm/inch document declarations. DocumentUnit alone is not a trustworthy cross-pack physical scale."}
    pack = json.loads((PACK / "pack.json").read_text(encoding="utf-8"))
    canopy_entry = next(item for item in pack["items"] if item["id"] == "item_042_01")
    canopy_scene_path, canopy_scene = canonical((PACK / canopy_entry["scene"]).parent)
    canopy_points = np.concatenate([np.array(mesh["vertices"]).reshape(-1, 8)[:, :3] for mesh in canopy_scene["meshes"]])
    canopy_source = Path(canopy_scene["metadata"]["source"])
    source_hashes[str(canopy_source)] = digest(canopy_source)
    assert source_hashes[str(canopy_source)] == canopy_scene["metadata"]["source_sha256"]
    canopy = {"source_id": "item_042_01", "source_c4d": str(canopy_source), "source_docunit": unit_record(canopy_source),
              "source_sha256": source_hashes[str(canopy_source)],
              "source_scene": str(canopy_scene_path),
              "source_span": (spans(canopy_points) / canopy_scene["metadata"]["normalization"]["uniform_scale"]).tolist(),
              "normalization": canopy_scene["metadata"]["normalization"], "styles": {}, "changed": False,
              "role": "deployed canopy entity, separate from the packed parachute inventory item; existing explicit 6m span retained"}
    for style, name in (("playful", "parachute_canopy"), ("pixel", "parachute_canopy_pixel")):
        model = json.loads((ASSETS / f"meshes/entity/{name}.json").read_text(encoding="utf-8"))
        points = np.array(model["vertices"]).reshape(-1, 8)[:, :3]
        canopy["styles"][style] = {"world_span_m": spans(points).tolist(), "coordinate_units": model["coordinate_units"],
                                     "sha256": digest(ASSETS / f"meshes/entity/{name}.json")}
    for source, expected in source_hashes.items():
        assert digest(Path(source)) == expected
    current_displays_match = all(json.loads((ASSETS / f"models/item/{row['name']}.json").read_text(encoding="utf-8"))["display"] == row["after_display"] for row in rows)
    report = {"schema": "pubg-supply-size-baseline/1", "item_count": len(rows), "applied": current_displays_match,
              "apply_requested": args.apply, "last_run_changed_models": len(changes),
              "source_docunit_warning": contradiction["conclusion"], "cross_pack_scale_evidence": contradiction,
              "policy": "Gameplay readability calibration, not asserted real-world measurements; uniform XYZ within every model, source ratios retained within each explicitly listed family. GUI unchanged; third-person x1.10 and first-person x1.35 of world size.",
              "metres_per_block": 1, "model_units_per_block": 16, "items": rows, "deployed_canopy": canopy,
              "changes": previous_report.get("changes", []) + changes, "source_hashes_verified": source_hashes, "minecraft_runtime_verified": False}
    write_json(OUTPUT / "size-baseline.json", report, pretty=True)
    fieldnames = ["item", "label", "source_id", "source_c4d", "source_docunit", "document_unit_scale", "source_x", "source_y", "source_z", "source_max",
                  "normalization_scale", "family", "metres_per_source_number", "before_ground_max_m", "after_ground_max_m", "ground_factor",
                  "before_gui_scale", "after_gui_scale", "after_thirdperson_scale", "after_firstperson_scale", "detail_ground_xyz_m", "pixel_ground_xyz_m", "policy"]
    with (OUTPUT / "size-comparison.csv").open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow({"item": "tacz_pubg:" + row["name"], "label": row["label"], "source_id": row["source_id"], "source_c4d": row["source_c4d"],
                             "source_docunit": row["source_docunit"]["unit"], "document_unit_scale": row["source_docunit"]["scale"],
                             **dict(zip(("source_x", "source_y", "source_z"), row["source_world_span"])), "source_max": row["raw_max"],
                             "normalization_scale": row["normalization"]["uniform_scale"], "family": row["family"],
                             "metres_per_source_number": row["baseline_metres_per_source_number"], "before_ground_max_m": row["before_ground_max_m"],
                             "after_ground_max_m": row["after_ground_max_m"], "ground_factor": row["ground_size_factor"],
                             "before_gui_scale": row["contexts"]["gui"]["before_scale"], "after_gui_scale": row["contexts"]["gui"]["after_scale"],
                             "after_thirdperson_scale": row["contexts"]["thirdperson_righthand"]["after_scale"],
                             "after_firstperson_scale": row["contexts"]["firstperson_righthand"]["after_scale"],
                             "detail_ground_xyz_m": row["contexts"]["ground"]["after_detail_span_m"], "pixel_ground_xyz_m": row["contexts"]["ground"]["after_pixel_span_m"],
                             "policy": row["policy"]})
    table = "".join(f'<tr><td>{html.escape(row["label"])}</td><td><code>{row["source_id"]}</code></td><td>{row["source_docunit"]["scale"]:g} {row["source_docunit"]["unit"]}</td>'
                    f'<td>{" × ".join(f"{v:.3f}" for v in row["source_world_span"])}</td><td>{row["normalization"]["uniform_scale"]:.5f}</td>'
                    f'<td>{row["before_ground_max_m"] * 100:.1f}</td><td><strong>{row["after_ground_max_m"] * 100:.1f}</strong><div class="bar" style="width:{row["after_ground_max_m"] * 180:.1f}px"></div></td>'
                    f'<td>{row["ground_size_factor"]:.3f}×</td><td>{html.escape(row["family"])}</td></tr>' for row in rows)
    document = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>物资模型真实比例核对</title>
<style>body{{font:16px/1.65 system-ui,"Microsoft YaHei",sans-serif;margin:40px;color:#172033;background:#f8fafc}}main{{max-width:1500px;margin:auto}}h1{{font-size:30px}}p{{max-width:1100px}}.warning{{background:#fff1db;padding:18px;border-left:5px solid #d98a16}}table{{border-collapse:collapse;width:100%;background:white}}th,td{{text-align:left;padding:10px 12px;border-bottom:1px solid #e2e8f0;vertical-align:top}}th{{position:sticky;top:0;background:#e2e8f0}}code{{font-size:13px}}.bar{{height:5px;background:#338482;border-radius:8px}}small{{color:#526277}}img{{width:100%;height:auto;background:white}}a{{color:#116d8b}}</style><main>
<h1>20件物资的源尺寸与显示比例</h1><p>原始C4D保留了网格尺寸、物体变换与文档单位。导出时每件最长边独立压到14模型单位，再统一ground=0.5，导致医疗箱、饮料、手雷在地上几乎等长43.75cm；这不是按原物资比例显示。</p>
<p class="warning"><strong>源文件“单位”不能直接视为真实物理大小。</strong>82模型包与急救包保存1 inch，CharacterS保存1 cm。同一件一级甲在前者源数值高{vest_1["source_world_span"][1]:.5f}、后者高{character_span[1]:.5f}，只差{contradiction["height_numeric_difference_ratio"]*100:.4f}%，若机械换算英寸却会相差2.54倍。因此此表分别保留“文件声明单位”和“原始坐标数值”，新尺寸明确是游戏可读性校准，不伪称实测现实尺寸。</p>
<p>新基准保留每个模型XYZ外形，以及同族源数值的相对比例。医疗箱/急救包按同一0.027m/源数值，改为约69/52cm；甲族锚三级甲58cm，包族锚二级包60cm，头盔族0.0125m/源数值，其余物资0.015m/源数值。GUI图标沿用原比例；第三人称手持为世界尺寸×1.10，第一人称手持为×1.35。穿戴适配与开伞伞面独立，不用物品display冒充穿戴大小。</p>
<p><a href="size-comparison.csv">下载完整CSV</a> · <a href="size-baseline.json">完整原始证据、两种风格、全部显示场景的JSON</a> · 源文件与几何/贴图均未修改；只改20件物品display，备份位于model-workflows/backups/supply_size_baseline。</p>
<table><thead><tr><th>物品</th><th>来源</th><th>源声明单位</th><th>源坐标跨度X×Y×Z</th><th>独立归一化因子</th><th>旧最长边cm</th><th>新最长边cm</th><th>新/旧</th><th>校准族</th></tr></thead><tbody>{table}</tbody></table>
<p><small>表中最长边是14单位源参考乘display后的方向无关基准。像素壳的体素边缘和保留的既有细节版头盔几何可能略有差异，JSON的contexts逐项列出了实际旋转后的XYZ包围盒，不能把旋转后的屏幕宽度当作原长边。</small></p>
<h2>开伞模型</h2><p>item_042_01原始跨度 {" × ".join(f"{v:.3f}" for v in canopy["source_span"])} 源数值。它是已展开的伞盖/绳索；不是item_006_09伞包物品。保留现有显式6米伞面跨度，两种风格都采用blocks坐标，不再经过物品display缩放。</p>
<h2>同一米尺的软件预览</h2><p>前两行细节版修正前/后，后两行像素版修正前/后。没有逐件自动拟合画幅。此图是软件渲染，不是游戏截图。</p><img src="medical-before-after.png" alt="医疗箱、急救包、饮料、手雷和背包按同一米尺的修正前后比较">
<p><small>离线核对包括源C4D哈希、DocumentUnit二进制偏移、原始边界逆算、几何/纹理不变、左右手场景及GUI不变。未启动Minecraft，未验证实际手臂遮挡、透视观感或穿戴效果。</small></p></main></html>'''
    (OUTPUT / "size-comparison.html").write_text(document, encoding="utf-8")
    if args.preview:
        preview(rows, OUTPUT / "medical-before-after.png")
    print(json.dumps({"items": len(rows), "changed_models": len(changes), "applied": args.apply,
                      "report": str(OUTPUT / "size-comparison.html"), "raw_files_verified": len(source_hashes)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
