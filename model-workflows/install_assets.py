"""Explicit OFFLINE copy of two finished exports into an existing addon source tree.

Not imported or executed by Minecraft. Makes a timestamped backup before replacing
only the four chosen resource files. Does not add a conversion command to the mod.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shutil

from workflow_common import digest, default_display, write_json
from pixel_surface_seams import seamless_model


def install(playful: Path, pixel: Path, assets_root: Path, backup_root: Path,
            name="med_kit", namespace="tacz_pubg"):
    if not re.fullmatch(r"[a-z0-9_]+", name) or not re.fullmatch(r"[a-z0-9_]+", namespace):
        raise ValueError("Invalid resource identifier")
    assets_root = assets_root.resolve()
    if assets_root.name != namespace or not (assets_root / "models/item").is_dir():
        raise ValueError("assets-root must be an existing assets/<namespace> directory")
    detailed = json.loads((playful / "model.json").read_text(encoding="utf-8"))
    low = json.loads((pixel / "model.json").read_text(encoding="utf-8"))
    repair_cost = None
    if namespace == "tacz_pubg" and low.get("credit", "").startswith("Offline pixel_hardcore.py"):
        # Existing immutable pack exports predate the seam fix. Installing one
        # must not regress the deployed shared-edge topology.
        low, repair_cost = seamless_model(low)
    if detailed.get("texture") != f"{namespace}:textures/item/detail/{name}.png":
        raise ValueError("Detailed export resource ID does not match destination")
    if low.get("textures", {}).get("0") != f"{namespace}:item/{name}":
        raise ValueError("Pixel export resource ID does not match destination")
    for file in (playful / "texture.png", pixel / "texture.png"):
        if not file.is_file():
            raise FileNotFoundError(file)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    backup = backup_root.resolve() / stamp
    replacements = [
        (playful / "model.json", Path(f"meshes/item/{name}.json")),
        (playful / "texture.png", Path(f"textures/item/detail/{name}.png")),
        (pixel / "model.json", Path(f"models/item/{name}.json")),
        (pixel / "texture.png", Path(f"textures/item/{name}.png")),
    ]
    old = assets_root / f"models/item/{name}.json"
    display = json.loads(old.read_text(encoding="utf-8")).get("display", default_display()) if old.exists() else default_display()
    record = {"name": name, "namespace": namespace, "assets_root": str(assets_root),
              "backup": str(backup), "files": [], "geometry_repair": repair_cost}
    # Prepare everything before overwriting any destination.
    staged = backup / "replacement"
    for source, relative in replacements:
        destination = assets_root / relative
        if destination.exists():
            saved = backup / "previous" / relative
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(destination, saved)
        ready = staged / relative
        ready.parent.mkdir(parents=True, exist_ok=True)
        if relative == Path(f"models/item/{name}.json"):
            low["display"] = display
            write_json(ready, low)
        else:
            shutil.copy2(source, ready)
        record["files"].append({"source": str(source.resolve()), "destination": str(destination),
                                 "before_sha256": digest(destination) if destination.exists() else None,
                                 "after_sha256": digest(ready)})
    write_json(backup / "install.json", record, pretty=True)
    for _, relative in replacements:
        destination = assets_root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(staged / relative, destination)
    for item in record["files"]:
        if digest(Path(item["destination"])) != item["after_sha256"]:
            raise RuntimeError("Installed file verification failed")
    write_json(backup_root / "latest-install.json", record, pretty=True)
    return record


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--playful", type=Path, required=True)
    p.add_argument("--pixel", type=Path, required=True)
    p.add_argument("--assets-root", type=Path, required=True)
    p.add_argument("--backup-root", type=Path, default=Path(__file__).parent / "backups")
    p.add_argument("--name", default="med_kit")
    p.add_argument("--namespace", default="tacz_pubg")
    a = p.parse_args()
    record = install(a.playful, a.pixel, a.assets_root, a.backup_root, a.name, a.namespace)
    print(f"Installed four resources; previous versions saved in {record['backup']}")


if __name__ == "__main__":
    main()
