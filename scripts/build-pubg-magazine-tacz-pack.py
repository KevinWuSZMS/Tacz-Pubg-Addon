"""Stage nine native TaCZ attachments without installing or changing any gun.

The local 1.1.8-hotfix-r7 JAR and CBRG pack are the schema references. Only
assets-workbench/pubg-magazines/tacz-pack and its workbench manifests are written.
Geometry/textures are copied byte-for-byte from the finalized visual-assets.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import shutil
import sys
import zipfile

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "assets-workbench/pubg-magazines"
VISUAL = WORK / "visual-assets"
PACK = WORK / "tacz-pack"
NS = "tacz_pubg_workbench"
VERSION = "1.1.8-hotfix-r7"
ADDON_VERSION = "1.0.0"
ADDON = ROOT / f"neoforge-1.21.1/build/libs/tacz_pubg-{ADDON_VERSION}.jar"
JAR = ROOT / f"neoforge-1.21.1/run/mods/tacz-neoforge-1.21.1-{VERSION}.jar"
CBRG = ROOT / "neoforge-1.21.1/run/tacz/classic_battleroyale_gun+1.21.1.zip"
INSTALLED = ROOT / "neoforge-1.21.1/run/tacz"
MANAGED_DEPLOYMENTS = {INSTALLED / "pubg-magazines-tacz-pack.zip", INSTALLED / "pubg-magazines-tacz-pack+1.21.1.zip"}
MANIFEST = WORK / "tacz-pack-manifest.json"
VALIDATION = WORK / "tacz-pack-validation.json"
GUIDE = WORK / "TACZ-INTEGRATION.md"
FAMILIES = ["rifle", "smg", "sniper"]
VARIANTS = ["quickdraw", "extended", "extended_quickdraw"]
LEVELS = {"quickdraw": 1, "extended": 2, "extended_quickdraw": 3}
FAMILY_ZH = {"rifle": "步枪／机枪／精确射手", "smg": "手枪／冲锋枪", "sniper": "狙击枪／精确射手"}
FAMILY_EN = {"rifle": "AR / LMG / DMR", "smg": "Pistol / SMG", "sniper": "SR / DMR"}
VARIANT_ZH = {"quickdraw": "快速弹匣", "extended": "扩容弹匣", "extended_quickdraw": "快速扩容弹匣"}
VARIANT_EN = {"quickdraw": "QuickDraw Magazine", "extended": "Extended Magazine", "extended_quickdraw": "Extended QuickDraw Magazine"}
RATES = {"quickdraw": 1.6, "extended": 1.0, "extended_quickdraw": 1.15}
TAG_NAMES = {"rifle": "magazine_rifle_lmg_dmr", "smg": "magazine_pistol_smg", "sniper": "magazine_sr_dmr"}
FACE_NAMES = {"north", "south", "east", "west", "up", "down"}
RESOURCE_ID = re.compile(r"^[a-z0-9_.-]+:[a-z0-9/._-]+$")


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def names():
    return [(family, variant, f"{variant}_mag_{family}") for family in FAMILIES for variant in VARIANTS]


def vector(value, length, context):
    require(isinstance(value, list) and len(value) == length, f"{context}: expected {length} numbers")
    require(all(isinstance(v, (int, float)) and math.isfinite(v) for v in value), f"{context}: invalid number")


def inspect_geometry(geo_path, texture_path, name):
    g = read_json(geo_path)
    require(set(g) == {"format_version", "minecraft:geometry"}, f"{name}: unexpected geometry root fields")
    require(g["format_version"] == "1.12.0", f"{name}: expected TaCZ Bedrock NEW 1.12.0")
    require(len(g["minecraft:geometry"]) == 1, f"{name}: expected one geometry")
    geometry = g["minecraft:geometry"][0]
    require(set(geometry) == {"description", "bones"}, f"{name}: unexpected geometry fields")
    desc = geometry["description"]
    require(desc["identifier"] == f"geometry.{NS}:{name}", f"{name}: geometry identifier mismatch")
    require(set(desc) <= {"identifier", "texture_width", "texture_height", "visible_bounds_width", "visible_bounds_height", "visible_bounds_offset"}, f"{name}: unexpected description fields")
    with Image.open(texture_path) as texture:
        texture.load()
        require(texture.format == "PNG", f"{name}: texture is not PNG")
        texture_size = texture.size
    require(texture_size == (desc["texture_width"], desc["texture_height"]), f"{name}: atlas resolution differs from geometry")
    bones = geometry["bones"]
    by_name = {b["name"]: b for b in bones}
    require(len(by_name) == len(bones), f"{name}: duplicate bone names")
    roots = [b for b in bones if not b.get("parent")]
    require(len(roots) == 1, f"{name}: expected one root bone")
    cube_count = face_count = 0
    for bone in bones:
        require(set(bone) <= {"name", "parent", "pivot", "rotation", "cubes", "mirror"}, f"{name}: unsupported bone metadata")
        for field in ["pivot", "rotation"]:
            if field in bone:
                vector(bone[field], 3, f"{name}/{bone['name']}/{field}")
        current = bone
        visited = {bone["name"]}
        while current.get("parent"):
            parent = current["parent"]
            require(parent in by_name, f"{name}: missing parent {parent}")
            require(parent not in visited, f"{name}: bone parent cycle")
            visited.add(parent)
            current = by_name[parent]
        for cube in bone.get("cubes", []):
            cube_count += 1
            require(set(cube) <= {"origin", "size", "pivot", "rotation", "inflate", "mirror", "uv"}, f"{name}: nonstandard cube fields")
            for field in ["origin", "size", "pivot", "rotation"]:
                if field in cube:
                    vector(cube[field], 3, f"{name}/{field}")
            require(all(v > 0 for v in cube["size"]), f"{name}: nonpositive cube dimensions")
            inflate = cube.get("inflate", 0)
            require(math.isfinite(inflate) and all(v + 2 * inflate > 0 for v in cube["size"]), f"{name}: inverted inflated cube")
            require(isinstance(cube["uv"], dict) and set(cube["uv"]) <= FACE_NAMES, f"{name}: expected per-face UV")
            for face, uv in cube["uv"].items():
                require(set(uv) == {"uv", "uv_size"}, f"{name}: unexpected UV fields")
                vector(uv["uv"], 2, f"{name}/{face}/uv")
                vector(uv["uv_size"], 2, f"{name}/{face}/uv_size")
                for axis, limit in enumerate(texture_size):
                    start, end = uv["uv"][axis], uv["uv"][axis] + uv["uv_size"][axis]
                    require(min(start, end) >= -1e-5 and max(start, end) <= limit + 1e-5, f"{name}: UV outside atlas")
                face_count += 1
    require(cube_count > 0 and face_count > 0, f"{name}: empty geometry")
    return {"cubes": cube_count, "faces": face_count, "triangles": 2 * face_count, "texture_size": list(texture_size), "bones": len(bones)}


def inspect_installed():
    require(JAR.is_file() and CBRG.is_file(), "Current local r7 JAR / CBRG schema reference is missing")
    with zipfile.ZipFile(JAR) as jar:
        mod_text = jar.read("META-INF/neoforge.mods.toml").decode("utf-8")
    require(f'version = "{VERSION}"' in mod_text, "Installed JAR version differs from verified schema")
    with zipfile.ZipFile(CBRG) as archive:
        meta = json.loads(archive.read("gunpack.meta.json"))
        require(meta["namespace"] == "cbrg", "Unexpected CBRG reference pack")
        for variant, level in LEVELS.items():
            name = f"{variant}_mag_rifle"
            index = json.loads(archive.read(f"data/cbrg/index/attachments/{name}.json"))
            data = json.loads(archive.read(f"data/cbrg/data/attachments/{name}_data.json"))
            require(index["type"] == "extended_mag" and data["extended_mag_level"] == level, "Reference extended_mag format changed")
    target_paths = {f"data/{NS}/index/attachments/{name}.json" for _, _, name in names()}
    collisions = []
    managed = None
    for entry in INSTALLED.iterdir():
        if entry.is_dir():
            collisions += [str(entry / p) for p in target_paths if (entry / p).is_file()]
        elif entry.suffix.lower() == ".zip":
            with zipfile.ZipFile(entry) as archive:
                paths = set(archive.namelist())
                overlap = target_paths & paths
                if entry in MANAGED_DEPLOYMENTS and overlap:
                    require(managed is None, "Two managed workbench ZIPs would register the same nine IDs")
                    require(len(paths) == len(archive.namelist()), "Managed deployed ZIP contains duplicate entries")
                    require(overlap == target_paths, "Managed deployed ZIP must contain all nine workbench IDs")
                    indices = {p for p in paths if "/index/attachments/" in p and p.endswith(".json")}
                    require(indices == target_paths, "Managed deployed ZIP contains unexpected attachment IDs")
                    require(json.loads(archive.read("gunpack.meta.json"))["namespace"] == NS,
                            "Managed deployed ZIP namespace mismatch")
                    require(all(not p.startswith("data/") or p.startswith(f"data/{NS}/") for p in paths),
                            "Managed deployed ZIP must not override an existing gun namespace")
                    require(not any("/guns/" in p or "/allow_attachments/" in p for p in paths),
                            "Managed deployed ZIP must not contain gun-specific changes")
                    managed = {"path": entry.relative_to(ROOT).as_posix(), "sha256": digest(entry), "attachment_count": 9}
                else:
                    collisions += [f"{entry}!{p}" for p in overlap]
    require(not collisions, "New IDs already exist in installed packs: " + "; ".join(collisions))
    return {"tacz_version": VERSION, "tacz_jar": str(JAR.relative_to(ROOT)).replace("\\", "/"), "tacz_jar_sha256": digest(JAR), "cbrg_sha256": digest(CBRG), "installed_id_collisions": collisions, "managed_deployment_detected": managed}


def inspect_addon():
    require(ADDON.is_file(), "Compile the companion addon before staging the native pack")
    expected = ["AttachmentMagazineExperimentMixin", "ShooterMagazineReloadRateMixin", "ModernGunMagazineReloadMixin", "ModernGunMagazineReloadClockMixin",
                "LocalPlayerMagazineReloadMixin", "GunAnimationMagazineReloadMixin", "AnimationContextMagazineReloadMixin",
                "ObjectAnimationRunnerMagazineReloadMixin", "SoundPlayManagerMagazineReloadMixin"]
    prefix = "com/example/examplemod/gunbalance/mixin/"
    java_root = ROOT / "neoforge-1.21.1/src/main/java/com/example/examplemod"
    source_files = sorted(java_root.rglob("*.java"))
    source_files += [ROOT / "neoforge-1.21.1/src/main/resources/tacz_pubg.gunbalance.mixins.json",
                     ROOT / "neoforge-1.21.1/src/main/resources/data/tacz_pubg/gun_balance/pubg_magazine_policy.json",
                     ROOT / "neoforge-1.21.1/src/main/resources/data/tacz_pubg/gun_balance/heavy_weapons.json",
                     ROOT / "neoforge-1.21.1/src/main/resources/assets/cbrg/lang/zh_cn.json",
                     ROOT / "neoforge-1.21.1/src/main/resources/assets/cbrg/lang/en_us.json",
                     ROOT / "neoforge-1.21.1/build.gradle"]
    require(all(p.is_file() and p.stat().st_mtime <= ADDON.stat().st_mtime for p in source_files), "Reload integration sources are newer than the companion JAR; rebuild the addon first")
    with zipfile.ZipFile(ADDON) as archive:
        content = set(archive.namelist())
        config = json.loads(archive.read("tacz_pubg.gunbalance.mixins.json"))
        configured = config["mixins"] + config["client"]
        require(all(prefix + name + ".class" in content and name in configured for name in expected), "Companion addon is missing the compiled reload hooks; rebuild it first")
        require("com/example/examplemod/gunbalance/reload/MagazineReloadTiming.class" in content, "Companion timing policy missing")
        require("com/example/examplemod/gunbalance/PubgMagazineCompatibility.class" in content,
                "Companion magazine compatibility gate missing")
        require("com/example/examplemod/gunbalance/GunExperimentPayloads$State.class" in content,
                "Companion server-confirmed magazine state missing")
    return {"mod_id": "tacz_pubg", "version": ADDON_VERSION, "path": str(ADDON.relative_to(ROOT)).replace("\\", "/"), "sha256": digest(ADDON), "reload_mixins": expected, "source_sha256": {p.relative_to(ROOT).as_posix(): digest(p) for p in source_files}, "runtime_tested": False, "installed_by_builder": False, "magazine_control": "world gunSettings.magazine + server-confirmed protocol-2 state + native marker/slot/matcher"}


def input_snapshot():
    snapshot = {}
    checks = []
    for family, variant, name in names():
        geo = VISUAL / f"assets/{NS}/geo_models/attachments/{name}_geo.json"
        texture = VISUAL / f"assets/{NS}/textures/attachments/{name}.png"
        require(geo.is_file() and texture.is_file(), f"Final visual asset missing: {name}")
        checks.append({"id": f"{NS}:{name}", "family": family, "variant": variant, "level": LEVELS[variant], **inspect_geometry(geo, texture, name)})
        for path in [geo, texture]:
            snapshot[str(path.relative_to(ROOT)).replace("\\", "/")] = digest(path)
    return snapshot, checks


def load_mesh_helper():
    # This module has a guarded main; importing only reads function definitions.
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("magazine_workbench_readonly", ROOT / "scripts/build-pubg-magazine-models.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def slot_icon(faces, texture, size=128):
    """Render the actual native mesh onto transparent RGBA for TaCZ's GUI slot."""
    resolution = size * 2
    vertices = np.concatenate([f[0] for f in faces])
    forward = np.array([1.0, .4, .62]); forward /= np.linalg.norm(forward)
    right = np.cross([0, 1, 0], forward); right /= np.linalg.norm(right)
    up = np.cross(forward, right)
    basis = np.array([right, up, forward]).T
    projected_all = vertices @ basis
    center = (projected_all.min(0) + projected_all.max(0)) / 2
    span = np.ptp(projected_all, axis=0)
    scale = min(resolution * .86 / max(span[0], .01), resolution * .86 / max(span[1], .01))
    pixels = np.zeros((resolution, resolution, 4), dtype=np.uint8)
    depth = np.full((resolution, resolution), -np.inf)
    tex = np.array(texture.convert("RGBA"))
    light = np.array([-.4, .8, .55]); light /= np.linalg.norm(light)
    fill = np.array([.8, .3, -.7]); fill /= np.linalg.norm(fill)
    for points, uv, normal in faces:
        view = points @ basis - center
        projected = np.c_[resolution / 2 + view[:, 0] * scale, resolution / 2 - view[:, 1] * scale, view[:, 2]]
        shade = .62 + .7 * max(0, np.dot(normal, light)) + .28 * max(0, np.dot(normal, fill))
        for triangle in [(0, 1, 2), (0, 2, 3)]:
            q, tuv = projected[list(triangle)], uv[list(triangle)]
            minx, maxx = max(0, int(np.floor(q[:, 0].min()))), min(resolution - 1, int(np.ceil(q[:, 0].max())))
            miny, maxy = max(0, int(np.floor(q[:, 1].min()))), min(resolution - 1, int(np.ceil(q[:, 1].max())))
            denominator = (q[1, 1] - q[2, 1]) * (q[0, 0] - q[2, 0]) + (q[2, 0] - q[1, 0]) * (q[0, 1] - q[2, 1])
            if maxx < minx or maxy < miny or abs(denominator) < 1e-8:
                continue
            xx, yy = np.meshgrid(np.arange(minx, maxx + 1) + .5, np.arange(miny, maxy + 1) + .5)
            w0 = ((q[1, 1] - q[2, 1]) * (xx - q[2, 0]) + (q[2, 0] - q[1, 0]) * (yy - q[2, 1])) / denominator
            w1 = ((q[2, 1] - q[0, 1]) * (xx - q[2, 0]) + (q[0, 0] - q[2, 0]) * (yy - q[2, 1])) / denominator
            w2 = 1 - w0 - w1
            z = w0 * q[0, 2] + w1 * q[1, 2] + w2 * q[2, 2]
            region_depth = depth[miny:maxy + 1, minx:maxx + 1]
            mask = (w0 >= -1e-7) & (w1 >= -1e-7) & (w2 >= -1e-7) & (z > region_depth)
            u = w0 * tuv[0, 0] + w1 * tuv[1, 0] + w2 * tuv[2, 0]
            v = w0 * tuv[0, 1] + w1 * tuv[1, 1] + w2 * tuv[2, 1]
            colors = tex[np.clip(np.floor(v).astype(int), 0, tex.shape[0] - 1), np.clip(np.floor(u).astype(int), 0, tex.shape[1] - 1)]
            mask &= colors[:, :, 3] > 0
            region = pixels[miny:maxy + 1, minx:maxx + 1]
            shaded = colors.copy(); shaded[:, :, :3] = np.clip(colors[:, :, :3].astype(float) * shade, 0, 255).astype(np.uint8)
            region[mask] = shaded[mask]; region_depth[mask] = z[mask]
    return Image.fromarray(pixels).resize((size, size), Image.Resampling.LANCZOS)


def tags():
    result = {TAG_NAMES[family]: [f"{NS}:{variant}_mag_{family}" for variant in VARIANTS] for family in FAMILIES}
    result["magazine_dmr_dual_family"] = [f"#{NS}:{TAG_NAMES['rifle']}", f"#{NS}:{TAG_NAMES['sniper']}"]
    return result


def validate_pack(snapshot):
    meta = read_json(PACK / "gunpack.meta.json")
    require(meta == {"namespace": NS, "dependencies": {"tacz": f"[{VERSION}]", "tacz_pubg": f"[{ADDON_VERSION}]"}}, "Unexpected pack metadata")
    langs = {lang: read_json(PACK / f"assets/{NS}/lang/{lang}.json") for lang in ["zh_cn", "en_us"]}
    results = []
    for family, variant, name in names():
        index = read_json(PACK / f"data/{NS}/index/attachments/{name}.json")
        require(set(index) == {"name", "tooltip", "display", "data", "type", "sort"}, f"{name}: unsupported index fields")
        require(index["type"] == "extended_mag", f"{name}: wrong attachment type")
        require(index["data"] == f"{NS}:{name}_data" and index["display"] == f"{NS}:{name}_display", f"{name}: bad resource ID")
        for lang in langs.values():
            require(index["name"] in lang and index["tooltip"] in lang, f"{name}: missing translation")
        data = read_json(PACK / f"data/{NS}/data/attachments/{name}_data.json")
        require(data == {"extended_mag_level": LEVELS[variant]}, f"{name}: unsupported gameplay fields")
        display = read_json(PACK / f"assets/{NS}/display/attachments/{name}_display.json")
        require(display == {"slot": f"{NS}:attachments/slot/{name}", "model": f"{NS}:attachments/{name}_geo", "texture": f"{NS}:attachments/{name}"}, f"{name}: unsupported display/reference fields")
        geo = PACK / f"assets/{NS}/geo_models/attachments/{name}_geo.json"
        texture = PACK / f"assets/{NS}/textures/attachments/{name}.png"
        check = inspect_geometry(geo, texture, name)
        for path in [geo, texture]:
            source_key = str((VISUAL / path.relative_to(PACK)).relative_to(ROOT)).replace("\\", "/")
            require(digest(path) == snapshot[source_key], f"{name}: copied asset differs from finalized visual asset")
        icon = PACK / f"assets/{NS}/textures/attachments/slot/{name}.png"
        with Image.open(icon) as image:
            require(image.size == (128, 128) and image.mode == "RGBA", f"{name}: invalid slot icon")
            require(image.getextrema()[3] == (0, 255), f"{name}: slot icon must have transparent and opaque pixels")
        results.append({"id": f"{NS}:{name}", "level": LEVELS[variant], "index_data_display_links_valid": True, "icon": str(icon.relative_to(PACK)).replace("\\", "/"), **check})
    expected_tags = tags()
    for tag, values in expected_tags.items():
        require(read_json(PACK / f"data/{NS}/tacz_tags/attachments/{tag}.json") == values, f"{tag}: family tag differs")
    def members(tag, visited=()):
        require(tag not in visited, "Family tag cycle")
        result = set()
        for value in expected_tags[tag]:
            if value.startswith("#"):
                require(value.startswith(f"#{NS}:"), "External family tag dependency")
                result.update(members(value.split(":", 1)[1], visited + (tag,)))
            else:
                require(RESOURCE_ID.fullmatch(value), "Invalid attachment resource ID")
                result.add(value)
        return result
    expanded = {tag: sorted(members(tag)) for tag in expected_tags}
    require(len(expanded["magazine_dmr_dual_family"]) == 6, "DMR union must contain both three-item families")
    data_files = [p.relative_to(PACK).as_posix() for p in (PACK / "data").rglob("*") if p.is_file()]
    require(all(p.startswith(f"data/{NS}/") for p in data_files), "Pack must not override any existing gun namespace")
    require(not any("/guns/" in p or "/allow_attachments/" in p for p in data_files), "Unexpected gun-specific change")
    info = read_json(PACK / f"assets/{NS}/gunpack_info.json")
    require(set(info) <= {"version", "name", "desc", "license", "authors", "date", "url"}, "Unsupported gunpack_info field")
    return {"status": "passed_static_validation", "installed": False, "runtime_tested": False, "runtime_modified": False,
            "target_tacz_version": VERSION, "attachment_count": len(results), "data_files": len(data_files),
            "family_tag_expansion": expanded, "registered_items": results,
            "native_registration_staged": True, "existing_guns_authorized_by_this_pack": 0,
            "compatibility_implemented_by": "companion addon, gated by world gunSettings.magazine=experimental",
            "initial_mode": "no new nine-ID compatibility, no quick reload; companion gate ignores installed workbench magazine level without removing NBT or ammunition",
            "reload_acceleration_implementation": "required companion addon: server clock/countdown + client animation/audio timing",
            "reload_rates": RATES, "reload_duration_multipliers": {variant: 1 / rate for variant, rate in RATES.items()},
            "native_attachment_data_implements_speed": False, "companion_addon_required": True,
            "gun_specific_capacities_written": False,
            "all_guns_rendered_with_new_magazine_geometry": False,
            "limitations": ["No game load performed; schema/resource validation and addon compilation only.", "The pack itself supplies family tags; companion compatibility requires the server world's magazine experiment and an original extended_mag slot.", "Capacity uses each gun's own level1/2/3 table, including QuickDraw.", "Use the companion addon artifact identified by its SHA-256; older builds with the same mod version may lack these hooks.", "PlayerAnimator third-person compatibility is not implemented; that optional mod is absent locally.", "Custom future reload scripts which bypass the native reload clock and animations require review.", "Gun-mounted magazine mesh requires extended_mag_pos or updated internal magazine nodes."]}


def build(make_zip=False):
    installed_before = inspect_installed()
    addon = inspect_addon()
    snapshot, _ = input_snapshot()
    require(GUIDE.is_file(), "TACZ-INTEGRATION.md is missing")
    helper = load_mesh_helper()
    PACK.mkdir(parents=True, exist_ok=True)
    write_json(PACK / "gunpack.meta.json", {"namespace": NS, "dependencies": {"tacz": f"[{VERSION}]", "tacz_pubg": f"[{ADDON_VERSION}]"}})
    language = {"zh_cn": {f"{NS}.pack.name": "PUBG 九弹匣工作台", f"{NS}.pack.desc": "九种原生弹匣；需配套附属及世界配置弹匣实验开关；容量读取原枪等级表，装枪外观须逐枪验证。"},
                "en_us": {f"{NS}.pack.name": "PUBG Nine-Magazine Workbench", f"{NS}.pack.desc": "Nine native magazines. Requires the companion addon and server magazine experiment; capacities use original gun level tables. Gun-mounted visuals require individual verification."}}
    icons = []
    for order, (family, variant, name) in enumerate(names()):
        model_relative = Path(f"assets/{NS}/geo_models/attachments/{name}_geo.json")
        texture_relative = Path(f"assets/{NS}/textures/attachments/{name}.png")
        for relative in [model_relative, texture_relative]:
            target = PACK / relative; target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(VISUAL / relative, target)
        display = {"slot": f"{NS}:attachments/slot/{name}", "model": f"{NS}:attachments/{name}_geo", "texture": f"{NS}:attachments/{name}"}
        write_json(PACK / f"assets/{NS}/display/attachments/{name}_display.json", display)
        write_json(PACK / f"data/{NS}/index/attachments/{name}.json", {"name": f"{NS}.attachments.{name}.name", "tooltip": f"{NS}.attachments.{name}.desc", "display": f"{NS}:{name}_display", "data": f"{NS}:{name}_data", "type": "extended_mag", "sort": 100 + order})
        write_json(PACK / f"data/{NS}/data/attachments/{name}_data.json", {"extended_mag_level": LEVELS[variant]})
        for lang in language:
            language[lang][f"{NS}.attachments.{name}.name"] = f"{VARIANT_ZH[variant]}（{FAMILY_ZH[family]}）" if lang == "zh_cn" else f"{VARIANT_EN[variant]} ({FAMILY_EN[family]})"
            language[lang][f"{NS}.attachments.{name}.desc"] = (f"弹匣实验启用时：等级 {LEVELS[variant]}，读取原枪该级容量；换弹进度 {RATES[variant]:g} 倍。" if lang == "zh_cn" else f"With server magazine experiment: level {LEVELS[variant]}, original gun level capacity; reload rate {RATES[variant]:g}x.")
        faces = helper.mesh_geometry(read_json(PACK / model_relative))
        with Image.open(PACK / texture_relative) as texture:
            icon = slot_icon(faces, texture)
        icon_path = PACK / f"assets/{NS}/textures/attachments/slot/{name}.png"
        icon_path.parent.mkdir(parents=True, exist_ok=True); icon.save(icon_path); icons.append(icon)
        print(f"Staged native attachment {NS}:{name}", file=sys.stderr, flush=True)
    for lang, values in language.items():
        write_json(PACK / f"assets/{NS}/lang/{lang}.json", values)
    write_json(PACK / f"assets/{NS}/gunpack_info.json", {"version": "0.1.0", "name": f"{NS}.pack.name", "desc": f"{NS}.pack.desc", "license": "CC BY-NC-SA 4.0", "authors": ["Local workbench adaptation", "Original CBRG pull-loop geometry: XiaoColorful"], "date": "2026-10-18"})
    for tag, values in tags().items():
        write_json(PACK / f"data/{NS}/tacz_tags/attachments/{tag}.json", values)
    cover = Image.new("RGBA", (448, 448), (22, 31, 28, 255))
    for i, icon in enumerate(icons):
        cover.alpha_composite(icon, (16 + (i % 3) * 144, 16 + (i // 3) * 144))
    cover.save(PACK / "pack.png")
    shutil.copyfile(GUIDE, PACK / "TACZ-INTEGRATION.md")
    require(input_snapshot()[0] == snapshot, "Visual assets changed during the build; wait for model completion and rebuild")
    require(digest(CBRG) == installed_before["cbrg_sha256"] and digest(JAR) == installed_before["tacz_jar_sha256"], "Installed schema references changed during build")
    validation = validate_pack(snapshot)
    files = {p.relative_to(PACK).as_posix(): digest(p) for p in sorted(PACK.rglob("*")) if p.is_file()}
    manifest = {"status": "native_pack_staged_not_installed", "namespace": NS, "installed": False, "runtime_tested": False, "pack_root": str(PACK.relative_to(ROOT)).replace("\\", "/"), "schema_reference": installed_before, "required_companion_addon": addon, "input_sha256": snapshot, "files_sha256": files, "file_count": len(files), "attachments": validation["registered_items"], "integration_document": str(GUIDE.relative_to(ROOT)).replace("\\", "/")}
    if make_zip:
        zip_path = WORK / "pubg-magazines-tacz-pack.zip"
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as archive:
            for relative in files:
                archive.write(PACK / relative, relative)
        with zipfile.ZipFile(zip_path) as archive:
            require(archive.testzip() is None and "gunpack.meta.json" in archive.namelist(), "Invalid native pack ZIP")
        manifest["zip"] = {"path": str(zip_path.relative_to(ROOT)).replace("\\", "/"), "sha256": digest(zip_path), "entries": len(files)}
    write_json(MANIFEST, manifest); write_json(VALIDATION, validation)
    return {"pack_root": str(PACK), "manifest": str(MANIFEST), "validation": str(VALIDATION), "files": len(files), "attachments": 9, "installed": False, "zip": manifest.get("zip")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-inputs", action="store_true", help="Read-only check of finalized visual assets and installed schema references")
    parser.add_argument("--validate-only", action="store_true", help="Read-only validation of an already staged native pack")
    parser.add_argument("--zip", action="store_true", help="Also build a ZIP with native TaCZ files at the archive root")
    args = parser.parse_args()
    if args.check_inputs:
        result = {"schema_reference": inspect_installed(), "input_snapshot": input_snapshot(), "written": False}
    elif args.validate_only:
        inspect_installed()
        inspect_addon()
        result = validate_pack(input_snapshot()[0])
    else:
        result = build(args.zip)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
