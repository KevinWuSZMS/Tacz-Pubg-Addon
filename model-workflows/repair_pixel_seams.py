"""Audit/install conforming geometry for the 20 converted in-game pixel items.

No PNG, detailed model, display transform or canopy rope data is changed.
The original item JSON is backed up before --install writes the cached-loader
payload. Without --install this only writes the reviewable cost/audit report.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil

from pixel_surface_seams import seamless_model, audit_seamless

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg"
OUTPUT = ROOT / "model-workflows/output/pixel_seam_repair"
ITEMS = ("vest_1", "vest_2", "vest_3", "backpack_1", "backpack_2", "backpack_3", "parachute",
         "med_kit", "first_aid_kit", "energy_drink", "bandage", "adrenaline_syringe", "painkiller",
         "smoke_grenade", "frag_grenade", "stun_grenade", "molotov", "helmet_1", "helmet_2", "helmet_3")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def object_digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def repair(install=False):
    OUTPUT.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    backup = ROOT / "model-workflows/backups/pixel_seams" / stamp
    report = {"schema": "pubg-pixel-seam-repair/1", "installed": install, "items": [],
              "reason": "Greedy voxel T junctions can open subpixel raster cracks, amplified by entity-outline postprocessing.",
              "method": "Keep every original boundary vertex; ear-clip affected weakly convex faces; maximum-match adjacent triangles back to baked quads.",
              "density": "Original density 36 and all original elements are unchanged.",
              "budget_policy": "Original authoring budgets remain unchanged. Actual GPU repair overhead is reported explicitly.",
              "minecraft_runtime_verified": False}
    protected_paths = [p for folder in (ASSETS / "textures/item", ASSETS / "meshes/item", ASSETS / "meshes/entity", ASSETS / "textures/entity")
                       for p in folder.rglob("*") if p.is_file()]
    protected = {str(p): digest(p) for p in protected_paths}
    staged = []
    # Audit every item before mutating any deployed model.
    for name in ITEMS:
        path = ASSETS / "models/item" / f"{name}.json"
        original = json.loads(path.read_text(encoding="utf-8-sig"))
        repaired, cost = seamless_model(original)
        cost.update(item=name, audit=audit_seamless(repaired), model=str(path),
                    before_sha256=digest(path), elements_sha256=object_digest(original["elements"]),
                    display_sha256=object_digest(original.get("display")),
                    original_face_budget=3600 if name.startswith("helmet_") else 2400 if name.startswith("vest_") else 1800)
        assert repaired["elements"] == original["elements"]
        assert repaired.get("display") == original.get("display")
        assert repaired["textures"] == original["textures"]
        staged.append((path, repaired, cost))
        report["items"].append(cost)
    if install:
        backup.mkdir(parents=True)
        for path, repaired, cost in staged:
            old = backup / path.name
            shutil.copy2(path, old)
            if digest(old) != cost["before_sha256"]:
                raise RuntimeError("Backup failed byte verification")
            path.write_text(json.dumps(repaired, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
            installed = json.loads(path.read_text(encoding="utf-8"))
            if object_digest(installed["elements"]) != cost["elements_sha256"] or object_digest(installed.get("display")) != cost["display_sha256"]:
                raise RuntimeError(f"Protected geometry/display changed: {path}")
            cost.update(backup=str(old), after_sha256=digest(path))
        report["backup_directory"] = str(backup)
    for path, expected in protected.items():
        if digest(Path(path)) != expected:
            raise RuntimeError(f"A protected asset changed: {path}")
    report["protected_assets_unchanged"] = protected
    report["totals"] = {key: sum(item[key] for item in report["items"]) for key in
                        ("legacy_quads", "gpu_quads", "gpu_vertices", "gpu_triangles", "valid_triangles", "degenerate_triangles", "inserted_boundary_vertices")}
    report["totals"]["quad_overhead_percent"] = round((report["totals"]["gpu_quads"] / report["totals"]["legacy_quads"] - 1) * 100, 3)
    destination = OUTPUT / ("installation.json" if install else "review.json")
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"report": str(destination), "installed": install, "items": len(ITEMS), "totals": report["totals"]}, ensure_ascii=False))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install", action="store_true")
    repair(parser.parse_args().install)
