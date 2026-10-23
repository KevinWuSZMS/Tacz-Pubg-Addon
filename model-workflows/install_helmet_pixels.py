"""Install only the three visually identified PUBG helmet pixel assets, keeping density 36."""
from __future__ import annotations

from datetime import datetime, timezone
import argparse
import json
from pathlib import Path
import shutil

from validate_outputs import validate
from workflow_common import digest, write_json
from pixel_surface_seams import seamless_model

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "model-workflows/output/pack_82"
ASSETS = ROOT / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg"
OUTPUT = ROOT / "model-workflows/output/helmet_pixel_integration"
SELECTED = {
    "helmet_1": ("item_027_02", "M_Head_E_01", "green motorcycle helmet; open face and ear protection"),
    "helmet_2": ("item_027_03", "M_Head_F_01", "desert camouflage military helmet; brim and chin strap"),
    "helmet_3": ("item_027_04", "M_Head_G_01", "black Spetsnaz helmet; full face visor"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resume-backup", type=Path, help="Reuse the immutable backup if a previous validation was interrupted")
    args = parser.parse_args()
    catalog = {item["id"]: item for item in json.loads((PACK / "pack.json").read_text(encoding="utf-8"))["items"]}
    backup = args.resume_backup or ROOT / "model-workflows/backups/helmet_pixels" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    records = []
    for name, (source_id, source_object, reason) in SELECTED.items():
        source = catalog[source_id]
        if source["source_object_names"] != [source_object]:
            raise ValueError(f"Source object changed: {source_id}")
        pixel = PACK / source["pixel"]
        texture = pixel.parent / "texture.png"
        report = json.loads((pixel.parent / "report.json").read_text(encoding="utf-8"))
        if report["actual_resolution"] != 36 or source["pixel_quads"] > 3600:
            raise ValueError("Helmet requires fixed density 36 and explicit 3600-face allowance")
        model_path = ASSETS / f"models/item/{name}.json"
        texture_path = ASSETS / f"textures/item/{name}.png"
        current = json.loads(model_path.read_text(encoding="utf-8"))
        replacement = json.loads(pixel.read_text(encoding="utf-8"))
        replacement["display"] = current.get("display", {})
        replacement["textures"] = {key: f"tacz_pubg:item/{name}" for key in replacement["textures"]}
        replacement, repair_cost = seamless_model(replacement)
        detail_paths = [ASSETS / f"meshes/item/{name}.json", ASSETS / f"textures/item/detail/{name}.png"]
        detail_before = {str(path): digest(path) for path in detail_paths}
        files = []
        for destination in (model_path, texture_path):
            saved = backup / name / destination.name
            saved.parent.mkdir(parents=True, exist_ok=True)
            if not saved.exists():
                shutil.copy2(destination, saved)
            files.append({"destination": str(destination), "backup": str(saved), "before_sha256": digest(saved)})
        write_json(model_path, replacement)
        shutil.copy2(texture, texture_path)
        unchanged_detail_triangles = len(json.loads(detail_paths[0].read_text(encoding="utf-8"))["vertices"]) // 24
        verification = validate(detail_paths[0], detail_paths[1], model_path, texture_path,
                                assets_root=ASSETS.parent, triangle_budget=max(6000, unchanged_detail_triangles), face_budget=3600)
        for path, expected in detail_before.items():
            if digest(Path(path)) != expected:
                raise RuntimeError("Playful helmet changed during pixel-only installation")
        if digest(texture_path) != digest(texture):
            raise RuntimeError("Pixel atlas was not preserved exactly")
        if json.loads(model_path.read_text(encoding="utf-8"))["elements"] != json.loads(pixel.read_text(encoding="utf-8"))["elements"]:
            raise RuntimeError("Source pixel geometry was altered")
        for record in files:
            record["after_sha256"] = digest(Path(record["destination"]))
        records.append({"item": f"tacz_pubg:{name}", "source_id": source_id, "source_object": source_object,
                        "source_export": str(pixel.parent.parent), "visual_selection": reason,
                        "selection_preview": str(PACK / source["preview"]), "pixel_resolution": 36,
                        "pixel_faces": source["pixel_quads"], "explicit_face_budget": 3600,
                        "source_model_sha256": digest(pixel), "source_texture_sha256": digest(texture),
                        "playful_unchanged": detail_before, "files": files, "validation": verification})
        records[-1]["geometry_repair"] = repair_cost
        print(f"Installed pixel {name} <- {source_id}: {source['pixel_quads']} faces, density 36")
    write_json(OUTPUT / "installation.json", {"schema": "pubg-helmet-pixel-installation/1", "items": records,
               "source_and_playful_unchanged": True, "minecraft_runtime_verified": False}, pretty=True)


if __name__ == "__main__":
    main()
