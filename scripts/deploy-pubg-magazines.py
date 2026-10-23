"""Deploy the approved magazine pack and matching development outputs; never launch Minecraft."""
from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT / "neoforge-1.21.1"
WORK = ROOT / "assets-workbench/pubg-magazines"
NS = "tacz_pubg_workbench"
SOURCE_ZIP = WORK / "pubg-magazines-tacz-pack.zip"
TARGET_ZIP = PROJECT / "run/tacz/pubg-magazines-tacz-pack+1.21.1.zip"
LEGACY_TARGET_ZIP = PROJECT / "run/tacz/pubg-magazines-tacz-pack.zip"
ADDON = PROJECT / "build/libs/tacz_pubg-1.0.0.jar"
IDE_OUTPUT = PROJECT / "bin/main"
RECEIPT = ROOT / "logs/pubg-magazine-deployment.json"
CBRG = PROJECT / "run/tacz/classic_battleroyale_gun+1.21.1.zip"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def relative(path):
    return path.relative_to(ROOT).as_posix()


def inside(path, directory):
    resolved = path.resolve()
    if not resolved.is_relative_to(directory.resolve()):
        raise ValueError(f"Path leaves intended directory: {path}")
    return resolved


def main():
    global TARGET_ZIP
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deploy", action="store_true", help="Write the deployment after validation")
    args = parser.parse_args()
    existing_targets = [p for p in (TARGET_ZIP, LEGACY_TARGET_ZIP) if p.is_file()]
    assert len(existing_targets) <= 1, "Two deployed copies would register the same magazine IDs"
    if existing_targets:
        TARGET_ZIP = existing_targets[0]
    manifest = json.loads((WORK / "tacz-pack-manifest.json").read_text(encoding="utf-8"))
    assert digest(SOURCE_ZIP) == manifest["zip"]["sha256"], "Native ZIP is stale"
    assert digest(ADDON) == manifest["required_companion_addon"]["sha256"], "Companion JAR is stale"
    source_files = manifest["required_companion_addon"]["source_sha256"]
    assert all(digest(ROOT / name) == sha for name, sha in source_files.items()), "Rebuild changed sources first"
    source_hash = digest(CBRG)
    worlds = sorted((PROJECT / "run/saves").glob("*/serverconfig/tacz_pubg-loot.json"))
    worlds += sorted((PROJECT / "run").glob("*/serverconfig/tacz_pubg-loot.json"))
    world_hashes = {relative(p): digest(p) for p in set(worlds)}
    common_config = PROJECT / "run/config/tacz_pubg-common.toml"
    common_hash = digest(common_config) if common_config.is_file() else None

    # runClient binds the addon source set. A second addon JAR in run/mods would duplicate its mod ID.
    for mod in (PROJECT / "run/mods").glob("*.jar"):
        with zipfile.ZipFile(mod) as archive:
            if "META-INF/neoforge.mods.toml" in archive.namelist():
                metadata = archive.read("META-INF/neoforge.mods.toml").decode("utf-8")
                assert not re.search(r'modId\s*=\s*"tacz_pubg"', metadata), f"Duplicate development addon: {mod}"

    with zipfile.ZipFile(SOURCE_ZIP) as pack:
        assert pack.testzip() is None
        indices = [p for p in pack.namelist() if p.startswith(f"data/{NS}/index/attachments/")]
        assert len(indices) == 9
        assert json.loads(pack.read("gunpack.meta.json"))["namespace"] == NS
        assert not any("/allow_attachments/" in p for p in pack.namelist())
    with zipfile.ZipFile(ADDON) as addon:
        assert addon.testzip() is None
        compiled = {p: addon.read(p) for p in addon.namelist()
                    if not p.endswith("/") and p != "META-INF/MANIFEST.MF"}
    assert "com/example/examplemod/gunbalance/PubgMagazinePolicy.class" in compiled
    assert "com/example/examplemod/gunbalance/mixin/AttachmentMagazineExperimentMixin.class" in compiled
    policy_path = "data/tacz_pubg/gun_balance/pubg_magazine_policy.json"
    assert compiled[policy_path] == (PROJECT / "src/main/resources" / policy_path).read_bytes(), "Compiled per-gun policy is stale"
    changes = [p for p, data in compiled.items()
               if not (IDE_OUTPUT / p).is_file() or (IDE_OUTPUT / p).read_bytes() != data]
    plan = {"native_zip": relative(TARGET_ZIP), "ide_output": relative(IDE_OUTPUT),
            "ide_files_to_update": len(changes), "addon_jar_copied_to_run_mods": False,
            "config_selection_changed": False, "game_launched": False}
    if not args.deploy:
        print(json.dumps({"status": "validated_dry_run", **plan}, ensure_ascii=False, indent=2))
        return

    backup = ROOT / "logs/source-backups" / ("pubg-magazine-runtime-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
    backup.mkdir(parents=True)
    created = []

    def prepare(target):
        inside(target, PROJECT)
        if target.exists():
            saved = backup / relative(target)
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, saved)
        else:
            created.append(relative(target))
        target.parent.mkdir(parents=True, exist_ok=True)

    if not TARGET_ZIP.is_file() or digest(TARGET_ZIP) != digest(SOURCE_ZIP):
        prepare(TARGET_ZIP)
        shutil.copy2(SOURCE_ZIP, TARGET_ZIP)
    for name in changes:
        target = inside(IDE_OUTPUT / name, IDE_OUTPUT)
        prepare(target)
        target.write_bytes(compiled[name])
    assert digest(TARGET_ZIP) == digest(SOURCE_ZIP)
    assert all((IDE_OUTPUT / name).read_bytes() == data for name, data in compiled.items())
    assert digest(CBRG) == source_hash, "Original CBRG pack changed"
    assert all(digest(ROOT / name) == sha for name, sha in world_hashes.items()), "A world selection changed"
    assert common_hash is None or digest(common_config) == common_hash
    receipt = {"status": "deployed_file_verified", "installed": True, "runtime_tested": False,
               "deployed_at_local": datetime.now().astimezone().isoformat(), **plan,
               "pack_sha256": digest(TARGET_ZIP), "companion_jar": relative(ADDON),
               "companion_jar_sha256": digest(ADDON), "ide_compiled_files_verified": len(compiled),
               "source_pack_unchanged": True, "cbrg_sha256": source_hash,
               "world_config_sha256_unchanged": world_hashes, "common_config_unchanged": True,
               "backup_directory": relative(backup), "created_paths": created,
               "activation": "config → 原始/实验性 → 弹匣 → 实验性调整 → 保存全部；等待生效通知。",
               "restart_required_for_new_java": True}
    RECEIPT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in receipt.items() if k != "created_paths"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
