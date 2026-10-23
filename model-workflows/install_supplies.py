"""Install the explicitly selected, already converted PUBG supplies offline.

Keeps the source pack immutable, rebinds only resource names, validates both
styles, and uses the existing four-file installer with recoverable backups.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil

from install_assets import install
from validate_outputs import validate
from workflow_common import digest, write_json

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "model-workflows/output/pack_82"
OUTPUT = ROOT / "model-workflows/output/supplies_integration"
ASSETS = ROOT / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg"
SELECTED = {
    "vest_1": "item_005_01", "vest_3": "item_005_03",
    "backpack_1": "item_006_06", "backpack_2": "item_006_05",
    "backpack_3": "item_006_08", "parachute": "item_006_09",
    "med_kit": "item_017_01", "energy_drink": "item_018_01",
    "bandage": "item_019_01", "adrenaline_syringe": "item_028_01",
    "frag_grenade": "item_036_01", "stun_grenade": "item_037_01",
    "smoke_grenade": "item_035_01",
    "molotov": "item_039_01", "painkiller": "item_044_01",
}
EXCLUDED = {
    "item_007_01": "enemy loot crate; description only; no new item",
    "item_014_01": "airdrop; description only; no new item",
    "item_080_01": "machete; description only; no new item",
    "item_081_01": "crowbar; description only; no new item",
    "item_082_01": "pan; description only; no new item",
}


def install_pair(name: str, source: Path, provenance: dict) -> dict:
    staged = OUTPUT / "staged" / name
    before = {}
    for style in ("playful", "pixel"):
        folder = source / style
        target = staged / style
        target.mkdir(parents=True, exist_ok=True)
        for filename in ("model.json", "texture.png"):
            path = folder / filename
            before[str(path)] = digest(path)
        model = json.loads((folder / "model.json").read_text(encoding="utf-8"))
        if style == "playful":
            model["texture"] = f"tacz_pubg:textures/item/detail/{name}.png"
        else:
            model["textures"] = {key: f"tacz_pubg:item/{name}" for key in model["textures"]}
            report = json.loads((folder / "report.json").read_text(encoding="utf-8"))
            if report["actual_resolution"] != 36:
                raise ValueError(f"{name}: expected fixed Pixel density 36")
        write_json(target / "model.json", model)
        shutil.copy2(folder / "texture.png", target / "texture.png")
    # These thin, hollow vests retain density 36. Their explicit face allowance
    # is 2,400; other selected items retain the ordinary 1,800-face target.
    face_budget = 2400 if name.startswith("vest_") else 1800
    args = (staged / "playful/model.json", staged / "playful/texture.png",
            staged / "pixel/model.json", staged / "pixel/texture.png")
    validate(*args, triangle_budget=6000, face_budget=face_budget)
    record = install(staged / "playful", staged / "pixel", ASSETS,
                     ROOT / "model-workflows/backups/supplies", name)
    deployed = validate(ASSETS / f"meshes/item/{name}.json", ASSETS / f"textures/item/detail/{name}.png",
                        ASSETS / f"models/item/{name}.json", ASSETS / f"textures/item/{name}.png",
                        assets_root=ASSETS.parent, triangle_budget=6000, face_budget=face_budget)
    for path, expected in before.items():
        if digest(Path(path)) != expected:
            raise RuntimeError(f"Source export changed: {path}")
    return {"item": f"tacz_pubg:{name}", "source": provenance,
            "source_export": str(source.resolve()), "source_hashes": before,
            "pixel_resolution": 36, "validation": deployed, "installation": record}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--first-aid", type=Path, help="Export directory containing playful/ and pixel/")
    parser.add_argument("--vest-2", type=Path, help="Recovered level-two vest export directory")
    args = parser.parse_args()
    catalog = json.loads((PACK / "pack.json").read_text(encoding="utf-8"))
    items = {item["id"]: item for item in catalog["items"]}
    manifest = {"schema": "pubg-supply-installation/1", "items": [],
                "description_only_excluded": EXCLUDED, "minecraft_runtime_verified": False,
                "canopy_source": "item_042_01; installed separately as deployed-flight entity geometry"}
    for name, item_id in SELECTED.items():
        item = items[item_id]
        source = (PACK / item["playful"]).parent.parent
        manifest["items"].append(install_pair(name, source, {
            "id": item_id, "objects": item["source_object_names"],
            "material_warnings": item.get("material_warnings", [])}))
        print(f"Installed {name} <- {item_id}", flush=True)
    for name, path in (("first_aid_kit", args.first_aid), ("vest_2", args.vest_2)):
        if path is not None:
            manifest["items"].append(install_pair(name, path, {"recovered_export": str(path.resolve())}))
            print(f"Installed {name} <- {path}", flush=True)
    write_json(OUTPUT / "installation.json", manifest, pretty=True)
    print(f"Validated and installed {len(manifest['items'])} supplies in both styles")


if __name__ == "__main__":
    main()
