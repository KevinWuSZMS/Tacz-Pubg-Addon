"""Exercise installed Minecraft formatters for CBRG and nine workbench magazines; no game launch."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT / "neoforge-1.21.1"
CBRG = PROJECT / "run/tacz/classic_battleroyale_gun+1.21.1.zip"
TACZ = PROJECT / "run/mods/tacz-neoforge-1.21.1-1.1.8-hotfix-r7.jar"
SOURCES = PROJECT / "build/moddev/artifacts/neoforge-21.1.259-sources.jar"
MINECRAFT = PROJECT / "build/moddev/artifacts/neoforge-21.1.259.jar"
ADDON = PROJECT / "build/libs/tacz_pubg-1.0.0.jar"
OUT = ROOT / "logs/pubg-attachment-tooltip-validation.json"
JDK = Path("C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot/bin")
LANGUAGES = ["zh_cn", "en_us"]
VARIANTS = ["quickdraw", "extended", "extended_quickdraw"]
OLD_FAMILIES = ["pistol", "rifle", "sr"]
NEW_FAMILIES = ["rifle", "smg", "sniper"]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(arguments):
    result = subprocess.run([str(arg) for arg in arguments], cwd=ROOT, capture_output=True,
                            encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout.strip()


def validate(check_addon=False):
    before = digest(CBRG)
    translations, cases, broken, overrides = {}, [], [], {}
    old_indices, old_language_only = set(), set()
    with zipfile.ZipFile(CBRG) as cbrg, zipfile.ZipFile(TACZ) as tacz:
        for language in LANGUAGES:
            original = json.loads(cbrg.read(f"assets/cbrg/lang/{language}.json"))
            override_path = PROJECT / f"src/main/resources/assets/cbrg/lang/{language}.json"
            override = json.loads(override_path.read_text(encoding="utf-8"))
            expected_keys = {f"cbrg.attachments.{variant}_mag_{family}.desc"
                             for variant in ["quickdraw", "extended_quickdraw"] for family in OLD_FAMILIES}
            assert set(override) == expected_keys, "Only the six broken magazine descriptions may be overridden"
            overrides[override_path.relative_to(ROOT).as_posix()] = digest(override_path)
            for key, value in override.items():
                assert value == original[key].replace("%", "%%"), f"Preserve original wording/percent: {key}"
                original_key = f"original/{language}/{key}"
                translations[original_key] = original[key]
                broken.append(original_key)
            effective = original | override
            workbench = json.loads((ROOT / f"assets-workbench/pubg-magazines/tacz-pack/assets/tacz_pubg_workbench/lang/{language}.json").read_text(encoding="utf-8"))
            for namespace, families, values in [("cbrg", OLD_FAMILIES, effective),
                                                ("tacz_pubg_workbench", NEW_FAMILIES, workbench)]:
                for variant in VARIANTS:
                    for family in families:
                        attachment = f"{variant}_mag_{family}"
                        if namespace == "cbrg":
                            index_path = f"data/{namespace}/index/attachments/{attachment}.json"
                            if index_path in cbrg.namelist():
                                index = json.loads(cbrg.read(index_path))
                                old_indices.add(f"{namespace}:{attachment}")
                            else:
                                index = None
                                old_language_only.add(f"{namespace}:{attachment}")
                        else:
                            index = json.loads((ROOT / f"assets-workbench/pubg-magazines/tacz-pack/data/{namespace}/index/attachments/{attachment}.json").read_text(encoding="utf-8"))
                        if index is not None:
                            assert index["name"] == f"{namespace}.attachments.{attachment}.name"
                            assert index["tooltip"] == f"{namespace}.attachments.{attachment}.desc"
                        for field in ["name", "desc"]:
                            key = f"{namespace}.attachments.{variant}_mag_{family}.{field}"
                            fixture_key = f"{language}/{key}"
                            translations[fixture_key] = values[key]
                            # These native index name/tooltip calls pass no formatting arguments.
                            expected = original[key] if namespace == "cbrg" else values[key]
                            cases.append({"key": fixture_key, "args": [], "expected": expected})
            native = json.loads(tacz.read(f"assets/tacz/lang/{language}.json"))
            contracts = {
                "tooltip.tacz.attachment.extended_mag_level_1": [],
                "tooltip.tacz.attachment.extended_mag_level_2": [],
                "tooltip.tacz.attachment.extended_mag_level_3": [],
                "tooltip.tacz.attachment.yaw.shift": [],
                "tooltip.tacz.attachment.yaw.support": [],
                "tooltip.tacz.attachment.laser.color": ["#AABBCC"],
                "tooltip.tacz.attachment.zoom": ["x2.0, x4.0"],
            }
            for key, parameters in contracts.items():
                fixture_key = f"{language}/{key}"
                translations[fixture_key] = native[key]
                expected = native[key]
                for parameter in parameters:
                    expected = expected.replace("%s", parameter, 1)
                cases.append({"key": fixture_key, "args": parameters, "expected": expected})

    with zipfile.ZipFile(SOURCES) as sources:
        i18n = sources.read("net/minecraft/client/resources/language/I18n.java").decode()
        assert 'String.format(s, parameters)' in i18n and '"Format error: " + s' in i18n
        contents = sources.read("net/minecraft/network/chat/contents/TranslatableContents.java").decode()
        assert 'decomposeTemplate(String formatTemplate' in contents and '"%%".equals(s1)' in contents
        assert 'if (!"s".equals(s4))' in contents
        assert 'index >= 0 && index < this.args.length' in contents
        mod_loader = sources.read("net/neoforged/neoforge/resource/ResourcePackLoader.java").decode()
        assert 'new PackSelectionConfig(false, Pack.Position.TOP, false)' in mod_loader
    with zipfile.ZipFile(ROOT / "downloads/tacz/tacz-neoforge-1.21.1-1.1.8-hotfix-r7-sources.jar") as sources:
        tooltip = sources.read("com/tacz/guns/client/tooltip/ClientAttachmentItemTooltip.java").decode()
        assert 'String text = I18n.get(tooltipKey);' in tooltip
        assert all(f'Component.translatable("tooltip.tacz.attachment.extended_mag_level_{level}")' in tooltip for level in [1, 2, 3])
        assert 'Component.translatable("tooltip.tacz.attachment.laser.color", rgbToHex(color))' in tooltip
        assert 'Component.translatable("tooltip.tacz.attachment.zoom", zoomJoinText)' in tooltip
        assert 'Pack.Position.BOTTOM' in sources.read("com/tacz/guns/resource/GunPackLoader.java").decode()

    checked_addon = False
    if check_addon:
        with zipfile.ZipFile(ADDON) as addon:
            for language in LANGUAGES:
                resource = f"assets/cbrg/lang/{language}.json"
                expected = (PROJECT / "src/main/resources" / resource).read_bytes()
                assert addon.read(resource) == expected, f"Addon JAR lacks latest translation repair: {resource}"
        checked_addon = True

    classpath = [str(MINECRAFT)] + (PROJECT / "build/moddev/clientLegacyClasspath.txt").read_text(encoding="utf-8").splitlines()
    assert all(Path(path).is_file() for path in classpath), "Prepared local client dependencies are required"
    fixture = {"translations": translations, "cases": cases, "original_broken_keys": broken}
    with tempfile.TemporaryDirectory(prefix="pubg-tooltip-") as temporary:
        temp = Path(temporary)
        data = temp / "fixtures.json"
        data.write_text(json.dumps(fixture, ensure_ascii=False), encoding="utf-8")
        cp = os.pathsep.join(classpath)
        run([JDK / "javac.exe", "-encoding", "UTF-8", "-classpath", cp, "-d", temp,
             ROOT / "scripts/validation/AttachmentTooltipFormatChecks.java"])
        result = run([JDK / "java.exe", "-Dfile.encoding=UTF-8", "-classpath", str(temp) + os.pathsep + cp,
                      "AttachmentTooltipFormatChecks", data])
    assert before == digest(CBRG), "Source CBRG pack was modified"
    receipt = {"status": "passed", "game_launched": False, "runtime_tooltip_rendered": False,
               "formatter_check": result, "minecraft_version": "1.21.1", "tacz_version": "1.1.8-hotfix-r7",
               "formatter_paths": ["I18n.get -> String.format", "TranslatableContents.decomposeTemplate"],
               "literal_percent": "%% requires zero arguments and renders one % in both paths",
               "language_cases": len(cases), "original_failures_reproduced": len(broken),
               "repaired_description_keys_per_language": 6, "tested_old_magazine_translation_sets": 9,
               "registered_old_magazine_ids": sorted(old_indices), "old_language_only_ids": sorted(old_language_only),
               "tested_workbench_magazine_ids": 9, "languages": LANGUAGES,
               "native_extended_mag_level_arguments": {"1": 0, "2": 0, "3": 0},
               "native_laser_color_arguments": 1, "native_zoom_arguments": 1,
               "source_cbrg_unchanged": True, "source_cbrg_sha256": before,
               "override_resource_sha256": overrides, "built_addon_resources_verified": checked_addon,
               "resource_priority_evidence": "TaCZ GunPackLoader BOTTOM; NeoForge mod resources TOP; ClientLanguage reads stack in order with later keys replacing earlier values."}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-built-addon", action="store_true", help="Also require current repair resources inside the built addon JAR")
    validate(parser.parse_args().check_built_addon)
