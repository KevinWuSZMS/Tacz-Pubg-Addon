"""Read-only current TaCZ attachment audit; only writes docs/attachments and logs.

The original gun packs, worlds and mod sources are never modified. No game launch.
"""
from pathlib import Path
import collections
import contextlib
import csv
import hashlib
import importlib.util
import io
import json
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/attachments"
ASSETS = OUT / "assets"
SNAPSHOT = ROOT / "logs/attachments-current-audit"
spec = importlib.util.spec_from_file_location("compat_audit", ROOT / "scripts/audit-ammo-attachment-compatibility.py")
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def csv_dump(path, rows):
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        for row in rows:
            writer.writerow({k: json.dumps(v, ensure_ascii=False, separators=(",", ":")) if isinstance(v, (dict, list)) else v for k, v in row.items()})


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    audit.OUT = SNAPSHOT
    capture = io.StringIO()
    with contextlib.redirect_stdout(capture):
        audit.main()
    (SNAPSHOT / "audit-summary.log").write_text(capture.getvalue(), encoding="utf-8")
    inventory = json.loads((SNAPSHOT / "compatibility-inventory.json").read_text(encoding="utf-8"))
    records = {g["id"]: g for g in inventory["guns"] if g["static_candidate"] and not g["ambiguous"]}
    attachments = {a["id"]: a for a in inventory["attachments"] if a["id"].startswith("cbrg:")}
    opened = {}
    files = collections.defaultdict(list)
    source_hashes = []
    for pack in sorted((ROOT / "neoforge-1.21.1/run/tacz").iterdir()):
        if pack.is_dir() and (pack / "gunpack.meta.json").is_file():
            for path in sorted(pack.rglob("*")):
                if path.is_file():
                    files[path.relative_to(pack).as_posix()].append((pack, path))
        elif pack.suffix == ".zip":
            opened[pack] = zipfile.ZipFile(pack)
            source_hashes.append({"path": str(pack.relative_to(ROOT)), "sha256": hashlib.sha256(pack.read_bytes()).hexdigest()})
            for name in opened[pack].namelist():
                if not name.endswith("/"):
                    files[name].append((pack, name))

    def read(path):
        variants = files.get(path, [])
        if not variants:
            return None
        blobs = [p.read_bytes() if isinstance(p, Path) else opened[pack].read(p) for pack, p in variants]
        if len({hashlib.sha256(b).digest() for b in blobs}) != 1:
            # Preserve source variants; never infer a runtime winner.
            jsons = [audit.gson_json(b.decode("utf-8-sig")) for b in blobs]
            if len({audit.canonical(j) for j in jsons}) != 1:
                raise ValueError("ambiguous asset: " + path)
        return blobs[0]

    def js(path):
        b = read(path)
        return audit.gson_json(b.decode("utf-8-sig")) if b is not None else None

    def extract(path, filename):
        if path is None:
            return None
        b = read(path)
        if b is None:
            return None
        target = ASSETS / filename
        target.write_bytes(b)
        return {"source_resource": path, "local": target.relative_to(OUT).as_posix(), "sha256": hashlib.sha256(b).hexdigest(), "bytes": len(b)}

    def expand(entries, trail=()):
        found = set()
        for entry in entries:
            if entry.startswith("#"):
                tag = entry[1:]
                if tag not in trail:
                    found.update(expand(inventory["tag_union"].get(tag, []), trail + (tag,)))
            else:
                found.add(entry)
        return found

    expanded_tags = {tag: expand(values, (tag,)) for tag, values in inventory["tag_union"].items()}
    rows = []
    for aid, attachment in sorted(attachments.items()):
        case = attachment["cases"][0]
        display = js(attachment["displays"][0]["resource"])
        local = aid.split(":", 1)[1]
        paths = {
            "icon": (audit.resource(display.get("slot"), "textures", ".png", "assets"), local + "_icon.png"),
            "model": (audit.resource(display.get("model"), "geo_models", side="assets"), local + "_geo.json"),
            "texture": (audit.resource(display.get("texture"), "textures", ".png", "assets"), local + "_texture.png"),
            "display": (attachment["displays"][0]["resource"], local + "_display.json"),
        }
        lod = display.get("lod") or {}
        if isinstance(lod, dict):
            paths["lod_model"] = (audit.resource(lod.get("model"), "geo_models", side="assets"), local + "_lod_geo.json")
            paths["lod_texture"] = (audit.resource(lod.get("texture"), "textures", ".png", "assets"), local + "_lod_texture.png")
        assets = {name: extract(path, filename) for name, (path, filename) in paths.items()}
        model = js(paths["model"][0]) or {}
        model_bones = model.get("minecraft:geometry", [{}])[0].get("bones", [])
        rows.append({
            "id": aid,
            "name_zh_cn": attachment["name_zh_cn"],
            "name_en_us": attachment["name_en_us"],
            "name_keys": attachment["name_keys"],
            "type": case["type"],
            "raw_data": case["raw_data"],
            "raw_display": display,
            "data_resource": case["data_resource"],
            "index_resource": attachment["resource"],
            "tags": sorted(tag for tag, ids in expanded_tags.items() if aid in ids),
            "source_files": attachment["sources"],
            "assets": assets,
            "model_bones": [{k: b[k] for k in ("name", "parent", "pivot", "rotation") if k in b} for b in model_bones],
            "model_cube_count": sum(len(b.get("cubes", [])) for b in model_bones),
            "compatible_gun_count": len(attachment["compatible_guns"]),
            "compatible_guns": attachment["compatible_guns"],
            "gun_specific_data_overrides": {gid: gun["cases"][0]["exclusive_attachments"][aid] for gid, gun in records.items() if aid in gun["cases"][0]["exclusive_attachments"]},
        })
    metadata = {
        "basis": "fresh installed original files; static r7 rules; not runtime tested",
        "source_snapshot": "../../logs/attachments-current-audit/compatibility-inventory.json",
        "gun_count": len(records),
        "attachment_count": len(rows),
        "counts_by_type": dict(collections.Counter(r["type"] for r in rows)),
        "source_archives": source_hashes,
        "notes": [
            "CBRG laser_sight is type=grip. It competes with grips, not the LASER slot.",
            "The original allow tags and slots are recorded. Existing experimental scope policy is a runtime overlay and not included in these original-file edges.",
            "Gun-specific exclusive_attachments override general attachment modifiers when present.",
            "Model resource existence and bones do not prove the attachment aligns physically. Mount transforms still require visual review.",
        ],
        "attachments": rows,
    }
    dump(OUT / "cbrg-attachments-current.json", metadata)
    csv_dump(OUT / "cbrg-attachments-current.csv", [{
        "id": r["id"], "name_zh_cn": "; ".join(r["name_zh_cn"]), "name_en_us": "; ".join(r["name_en_us"]),
        "type": r["type"], "raw_data": r["raw_data"], "icon": r["assets"]["icon"]["local"] if r["assets"]["icon"] else "",
        "model": r["assets"]["model"]["local"] if r["assets"]["model"] else "", "texture": r["assets"]["texture"]["local"] if r["assets"]["texture"] else "",
        "compatible_gun_count": r["compatible_gun_count"], "exclusive_override_count": len(r["gun_specific_data_overrides"]), "tags": r["tags"],
    } for r in rows])

    matrix = []
    for gid, gun in sorted(records.items()):
        case = gun["cases"][0]
        display_ref = gun["displays"][0]["resource"]
        display = js(display_ref) or {}
        model_resource = audit.resource(display.get("model"), "geo_models", side="assets")
        model = js(model_resource) or {}
        bones = {b.get("name"): b for b in model.get("minecraft:geometry", [{}])[0].get("bones", [])}
        mounts = {typ: typ + "_pos" in bones for typ in audit.TYPES}
        support = {}
        for a in rows:
            aid, typ = a["id"], a["type"]
            allowed_type = typ in case["allowed_attachment_types"]
            tag_allowed = aid in gun["expanded_tag_ids"]
            mounted = mounts[typ]
            if typ == "extended_mag":
                mounted = mounted or any("mag_extended_" + str(i) in bones for i in (1, 2, 3))
            support[aid] = {
                "slot_open": allowed_type,
                "tag_allowed": tag_allowed,
                "current_static_compatible": allowed_type and tag_allowed,
                "mount_or_builtin_mag_nodes_present": mounted,
                "specific_modifier_override": aid in case["exclusive_attachments"],
                "status": "compatible" if allowed_type and tag_allowed else "slot_closed" if not allowed_type else "tag_not_allowed",
            }
        raw = case["raw_data"]
        matrix.append({
            "id": gid, "name_zh_cn": gun["name_zh_cn"], "name_en_us": gun["name_en_us"], "gun_type": case["type"],
            "original_slots": case["allowed_attachment_types"],
            "builtin_attachments": case["builtin_attachments"],
            "exclusive_attachments": case["exclusive_attachments"],
            "reload": raw.get("reload"), "script": raw.get("script"),
            "base_magazine_capacity": case["base_magazine_capacity"],
            "extended_mag_ammo_amount": case["extended_mag_ammo_amount"],
            "display_resource": display_ref, "model_resource": model_resource, "model_resource_exists": model_resource in files,
            "mount_bones": {name: {k: b[k] for k in ("parent", "pivot", "rotation") if k in b} for name, b in bones.items() if name in {t + "_pos" for t in audit.TYPES} or name in {"laser_beam", "magazine", "mag_standard", "mag_extended_1", "mag_extended_2", "mag_extended_3"}},
            "cbrg_support": support,
        })
    slots = collections.Counter(t for g in matrix for t in g["original_slots"])
    compatible_missing_mount = [{"gun": g["id"], "attachment": aid} for g in matrix for aid, s in g["cbrg_support"].items() if s["current_static_compatible"] and not s["mount_or_builtin_mag_nodes_present"]]
    summary = {
        "gun_count": len(matrix), "original_open_slots": dict(slots),
        "compatible_missing_mount_count": len(compatible_missing_mount),
        "compatible_missing_mount_edges": compatible_missing_mount,
        "guns_without_conventional_attachment_mounts": [g["id"] for g in matrix if not g["mount_bones"]],
        "guns_missing_full_model_resource": [g["id"] for g in matrix if not g["model_resource_exists"]],
        "status_counts": dict(collections.Counter(s["status"] for g in matrix for s in g["cbrg_support"].values())),
    }
    dump(OUT / "current-gun-attachment-matrix.json", {"basis": metadata["basis"], "summary": summary, "guns": matrix})
    csv_dump(OUT / "current-gun-attachment-matrix.csv", [{
        "id": g["id"], "name_zh_cn": "; ".join(g["name_zh_cn"]), "name_en_us": "; ".join(g["name_en_us"]),
        "gun_type": g["gun_type"], "original_slots": g["original_slots"], "builtin_attachments": g["builtin_attachments"],
        "base_magazine_capacity": g["base_magazine_capacity"], "extended_mag_ammo_amount": g["extended_mag_ammo_amount"],
        "mount_bones": list(g["mount_bones"]),
        **{aid: data["status"] + ("; no_mount_node" if data["current_static_compatible"] and not data["mount_or_builtin_mag_nodes_present"] else "") for aid, data in g["cbrg_support"].items()},
    } for g in matrix])
    after_hashes = [{"path": h["path"], "sha256": hashlib.sha256((ROOT / h["path"]).read_bytes()).hexdigest()} for h in source_hashes]
    assert source_hashes == after_hashes, "original source archive unexpectedly changed"
    assert len(matrix) == 424 and len(rows) == 24
    dump(OUT / "current-audit-validation.json", {"source_archives_unchanged": True, "guns": len(matrix), "cbrg_attachments": len(rows), "summary": summary})
    print(json.dumps({"gun_count": len(matrix), "cbrg_attachment_count": len(rows), "cbrg_by_type": metadata["counts_by_type"], "open_slots": dict(slots), "compatible_missing_mounts": len(compatible_missing_mount)}, ensure_ascii=False, indent=2))
    for z in opened.values():
        z.close()


if __name__ == "__main__":
    main()
