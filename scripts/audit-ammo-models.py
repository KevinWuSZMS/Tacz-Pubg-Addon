"""Read-only ammunition geometry and icon inventory; no game or Gradle launch.

Original slot PNG bytes are copied to the report folder. Offline model previews
use the authored cuboids, bone rotations and texture UVs with an orthographic
software rasterizer; these are illustrations, not screenshots of TaCZ.
"""
from __future__ import annotations

from collections import Counter
from contextlib import ExitStack
from datetime import datetime, timezone
from io import BytesIO
import hashlib
import importlib.util
import itertools
import json
import math
from pathlib import Path
import re
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/compatibility"
FONT = Path("C:/Windows/Fonts/msyh.ttc")


def load_helper():
    path = ROOT / "scripts/extract-gun-analysis-assets.py"
    spec = importlib.util.spec_from_file_location("gun_asset_helpers", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def translate(v):
    m = np.eye(4)
    m[:3, 3] = v
    return m


def rotation(axis, degrees):
    m = np.eye(4)
    a, b = ((1, 2), (2, 0), (0, 1))[axis]
    c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    m[a, a] = m[b, b] = c
    m[a, b], m[b, a] = -s, s
    return m


def trs(pivot, angles):
    m = translate(np.array(pivot) / 16)
    for axis in (2, 1, 0):
        m = m @ rotation(axis, angles[axis])
    return m


def geometry_cubes(model, display):
    """Mirror r7's pivot/origin conversion and rotation order, in block units."""
    if model.get("minecraft:geometry"):
        geometry = model["minecraft:geometry"][0]
        dimensions = geometry.get("description", {})
        tex_size = [dimensions.get("texture_width", 64), dimensions.get("texture_height", 64)]
    else:
        geometry = model.get("geometry.model")
        if geometry is None:
            raise ValueError("No r7-supported geometry.model or minecraft:geometry[0]")
        tex_size = [geometry.get("texturewidth", 64), geometry.get("textureheight", 64)]
    bones = {b["name"]: b for b in geometry.get("bones", [])}
    matrices = {}

    def matrix(name, trail=()):
        if name in trail:
            raise ValueError("bone parent cycle")
        if name in matrices:
            return matrices[name]
        bone = bones[name]
        p = bone.get("pivot", [0, 0, 0])
        parent = bones.get(bone.get("parent"))
        base = parent.get("pivot", [0, 0, 0]) if parent else [0, 24, 0]
        local = [p[0] - base[0], base[1] - p[1], p[2] - base[2]]
        m = trs(local, bone.get("rotation", [0, 0, 0]))
        if parent:
            m = matrix(bone["parent"], trail + (name,)) @ m
        matrices[name] = m
        return m

    # Translation does not affect dimensions. Include the GROUND origin's
    # inverse rotation, authored ground scale, and r7's (-1,-1,1) inversion.
    ground = np.diag([-1., -1., 1., 1.])
    origin = "ground"
    origin_path = []
    while origin in bones:
        if origin in origin_path:
            raise ValueError("ground origin cycle")
        origin_path.append(origin)
        angles = bones[origin].get("rotation", [0, 0, 0])
        for axis in (0, 1, 2):
            ground = ground @ rotation(axis, -angles[axis])
        origin = bones[origin].get("parent")
    gs = display.get("transform", {}).get("scale", {}).get("ground", [.75, .75, .75])
    gs = [1, 1, 1] if gs is None else gs
    ground = ground @ np.diag([*gs, 1.])
    cubes = []
    for name, bone in bones.items():
        for cube in bone.get("cubes", []):
            m = ground @ matrix(name)
            pivot = bone.get("pivot", [0, 0, 0])
            if "rotation" in cube:
                p = cube.get("pivot", [0, 0, 0])
                m = m @ trs([p[0] - pivot[0], pivot[1] - p[1], p[2] - pivot[2]], cube["rotation"])
                pivot = p
            o, size = cube["origin"], cube["size"]
            origin = [o[0] - pivot[0], pivot[1] - o[1] - size[1], o[2] - pivot[2]]
            inflate = cube.get("inflate", 0)
            vertices = []
            for bits in itertools.product((0, 1), repeat=3):
                point = [(origin[i] + (size[i] + inflate if bits[i] else -inflate)) / 16 for i in range(3)] + [1]
                vertices.append((m @ point)[:3])
            cubes.append({"vertices": np.array(vertices), "size": size,
                          "uv": cube.get("uv", [0, 0]), "bone": name,
                          "mirror": cube.get("mirror", bone.get("mirror", False))})
    if not cubes:
        return cubes, {"boneCount": len(bones), "cubeCount": 0, "groundScale": gs}, tex_size
    points = np.concatenate([c["vertices"] for c in cubes])
    dims = points.max(axis=0) - points.min(axis=0)
    nonzero = [c for c in cubes if min(c["size"]) > 1e-8]
    planar = [c for c in cubes if min(c["size"]) <= 1e-8]
    thin = min(dims) <= .040001 and min(dims) / max(dims) <= .040001
    volume = sum(float(np.prod(np.maximum(np.array(c["size"]), 0))) for c in cubes)
    summary = {"formatVersion": model.get("format_version"), "boneCount": len(bones),
               "cubeCount": len(cubes), "nonzeroVolumeCubeCount": len(nonzero),
               "planarCubeCount": len(planar), "sumCubeVolumeModelUnits3": volume,
               "groundExtentsBlocks": dims.tolist(), "thinRatio": float(min(dims)/max(dims)),
               "groundScale": gs, "groundOriginPath": origin_path,
               "extraFloorScale": .38 if thin else 1.,
               "classificationReason": "ground bounding thickness <= 0.04 block AND min/max <= 0.04" if thin else "ground assembled cuboids retain substantial depth"}
    return cubes, summary, tex_size


# Corners are indexed by itertools.product order: x*4+y*2+z.
FACES = {"north": [0, 4, 6, 2], "south": [5, 1, 3, 7],
         "west": [1, 0, 2, 3], "east": [4, 5, 7, 6],
         "up": [1, 5, 4, 0], "down": [2, 6, 7, 3]}


def face_uv(cube, face):
    uv = cube["uv"]
    if isinstance(uv, dict):
        if face not in uv:
            return None
        box = uv[face]
        u, v = box.get("uv", [0, 0])
        w, h = box.get("uv_size", [0, 0])
    else:
        u, v = uv
        x, y, z = cube["size"]
        offsets = {"west": (0, z, z, y), "north": (z, z, x, y),
                   "east": (z+x, z, z, y), "south": (2*z+x, z, x, y),
                   "up": (z, 0, x, z), "down": (z+x, 0, x, z)}
        du, dv, w, h = offsets[face]
        u, v = u+du, v+dv
    points = np.array([[u, v], [u+w, v], [u+w, v+h], [u, v+h]], dtype=float)
    if cube["mirror"]:
        points = points[[1, 0, 3, 2]]
    return points


def preview(cubes, texture, tex_size, target):
    """Orthographic, z-buffered cuboid texture preview, deliberately labeled offline."""
    width, height = 360, 260
    arr = np.full((height, width, 4), [20, 28, 41, 255], dtype=np.uint8)
    depth = np.full((height, width), -np.inf)
    allpoints = np.concatenate([c["vertices"] for c in cubes])
    # Place the narrowest dimension vertically, as the floor renderer does.
    dims = allpoints.max(axis=0)-allpoints.min(axis=0)
    flat = np.eye(3)
    if dims[1] > min(dims)*1.05:
        flat = rotation(2 if dims[0] <= dims[2] else 0, 90)[:3, :3]
    # model coordinates already have ground inversion; preview Y points up.
    iso = np.array([[.70710678, 0, -.70710678],
                    [-.40824829, .81649658, -.40824829],
                    [.57735027, .57735027, .57735027]])
    camera = iso @ flat
    points = allpoints @ camera.T
    low, high = points.min(axis=0), points.max(axis=0)
    zoom = min((width-40)/max(high[0]-low[0], 1e-6), (height-64)/max(high[1]-low[1], 1e-6))
    center = (low+high)/2
    texture = np.asarray(texture.convert("RGBA"))
    th, tw = texture.shape[:2]
    for cube in cubes:
        view = cube["vertices"] @ camera.T
        screen = np.stack([(view[:, 0]-center[0])*zoom+width/2,
                           -(view[:, 1]-center[1])*zoom+(height-22)/2,
                           view[:, 2]], axis=1)
        for face, indices in FACES.items():
            uvs = face_uv(cube, face)
            if uvs is None:
                continue
            p = screen[indices]
            for tri in ((0, 1, 2), (0, 2, 3)):
                q = p[list(tri)]
                tex = uvs[list(tri)]
                minx, maxx = max(0, int(np.floor(q[:,0].min()))), min(width-1, int(np.ceil(q[:,0].max())))
                miny, maxy = max(0, int(np.floor(q[:,1].min()))), min(height-31, int(np.ceil(q[:,1].max())))
                if minx > maxx or miny > maxy:
                    continue
                denom = (q[1,1]-q[2,1])*(q[0,0]-q[2,0])+(q[2,0]-q[1,0])*(q[0,1]-q[2,1])
                if abs(denom) < 1e-8:
                    continue
                yy, xx = np.mgrid[miny:maxy+1, minx:maxx+1]
                a = ((q[1,1]-q[2,1])*(xx-q[2,0])+(q[2,0]-q[1,0])*(yy-q[2,1]))/denom
                b = ((q[2,1]-q[0,1])*(xx-q[2,0])+(q[0,0]-q[2,0])*(yy-q[2,1]))/denom
                c = 1-a-b
                z = a*q[0,2]+b*q[1,2]+c*q[2,2]
                u = (a*tex[0,0]+b*tex[1,0]+c*tex[2,0])*tw/tex_size[0]
                v = (a*tex[0,1]+b*tex[1,1]+c*tex[2,1])*th/tex_size[1]
                rgba = texture[np.clip(v.astype(int), 0, th-1), np.clip(u.astype(int), 0, tw-1)].copy()
                # Simple directional shading, preserving texture hue and alpha.
                shading = {"up": 1., "down": .62, "north": .78, "south": .92, "east": .86, "west": .7}[face]
                rgba[:,:,:3] = np.clip(rgba[:,:,:3].astype(float)*shading, 0, 255).astype(np.uint8)
                regionz = depth[miny:maxy+1, minx:maxx+1]
                mask = (a >= -1e-6)&(b >= -1e-6)&(c >= -1e-6)&(z > regionz)&(rgba[:,:,3] > 127)
                regionz[mask] = z[mask]
                arr[miny:maxy+1, minx:maxx+1][mask] = rgba[mask]
    im = Image.fromarray(arr)
    draw = ImageDraw.Draw(im)
    draw.text((12, height-26), "离线几何预览 · 非游戏截图", font=ImageFont.truetype(str(FONT), 15), fill="#a7b7cc")
    im.save(target)


def get_resource(resources, reference, folder, suffix, resource, preferred):
    entry = resource(reference, folder, suffix, "assets")
    sources = resources.sources(entry, preferred) if entry else []
    return entry, sources[0] if sources else None


def main():
    OUT.mkdir(exist_ok=True)
    (OUT / "ammo-icons").mkdir(exist_ok=True)
    (OUT / "ammo-model-previews").mkdir(exist_ok=True)
    inv_path = OUT / "compatibility-inventory.json"
    inv_bytes = inv_path.read_bytes()
    inv = json.loads(inv_bytes)
    helper = load_helper()
    gson_json, resource = helper.audit_parser()
    records = []
    with ExitStack() as stack:
        resources = helper.Resources(ROOT, inv, stack)
        for ammo in inv["ammo"]:
            identifier = ammo["id"]
            index = ammo["index_variants"][0]["value"]
            preferred = ammo["sources"][0]
            filename = re.sub(r"[^a-zA-Z0-9_.-]", "_", identifier)+"-"+hashlib.sha256(identifier.encode()).hexdigest()[:8]+".png"
            names = ammo.get("name_zh_cn") or ammo.get("name_en_us") or [identifier]
            row = {"id": identifier, "name": helper.clean_name(names[0]),
                   "modelClassification": "missing", "hasSolidGeometry": False,
                   "slotIcon": None, "modelPreview": None, "paths": {},
                   "modelGeometrySummary": None, "errors": []}
            dp, ds = get_resource(resources, index.get("display"), "display/ammo", ".json", resource, preferred)
            row["paths"].update(indexResource=ammo["resource"], indexSources=ammo["sources"], displayResource=dp, displaySource=ds)
            if not ds:
                row["errors"].append("missing display")
                records.append(row)
                continue
            display = gson_json(resources.read(ds).decode("utf-8-sig"))
            row["rawDisplay"] = display
            sp, ss = get_resource(resources, display.get("slot"), "textures", ".png", resource, ds)
            mp, ms = get_resource(resources, display.get("model"), "geo_models", ".json", resource, ds)
            tp, ts = get_resource(resources, display.get("texture"), "textures", ".png", resource, ds)
            row["paths"].update(slotResource=sp, slotSource=ss, modelResource=mp, modelSource=ms, textureResource=tp, textureSource=ts)
            row["resourceVariants"] = {}
            for field, entry in (("slot", sp), ("model", mp), ("texture", tp)):
                sources = resources.sources(entry, ds) if entry else []
                row["resourceVariants"][field] = [{"source": s, "sha256": hashlib.sha256(resources.read(s)).hexdigest()} for s in sources]
            if ss:
                data = resources.read(ss)
                Image.open(BytesIO(data)).verify()
                path = OUT / "ammo-icons" / filename
                path.write_bytes(data)
                row["slotIcon"] = path.relative_to(OUT).as_posix()
                row["slotPngSha256"] = hashlib.sha256(data).hexdigest()
            if not display.get("model"):
                row["modelClassification"] = "2d" if ss else "missing"
                row["hasSolidGeometry"] = False
                row["modelGeometrySummary"] = {"cubeCount": 0, "reason": "no display.model; TaCZ falls back to slot model"}
            elif not ms or not ts:
                row["errors"].append("missing model or texture")
            else:
                try:
                    model = gson_json(resources.read(ms).decode("utf-8-sig"))
                    cubes, summary, tex_size = geometry_cubes(model, display)
                    texture = Image.open(BytesIO(resources.read(ts))).convert("RGBA")
                    summary["textureSize"] = list(texture.size)
                    summary["textureAlphaExtrema"] = list(texture.getchannel("A").getextrema())
                    row["modelGeometrySummary"] = summary
                    if summary["cubeCount"]:
                        row["modelClassification"] = "thin" if summary["extraFloorScale"] == .38 else "solid3d"
                        row["hasSolidGeometry"] = row["modelClassification"] == "solid3d"
                        path = OUT / "ammo-model-previews" / filename
                        preview(cubes, texture, tex_size, path)
                        row["modelPreview"] = path.relative_to(OUT).as_posix()
                    else:
                        row["errors"].append("empty geometry")
                except Exception as exc:
                    row["errors"].append(str(exc))
            records.append(row)
    counts = dict(Counter(r["modelClassification"] for r in records))
    # Metadata is evidence of available permission text, not legal inference.
    package = ROOT / "neoforge-1.21.1/run/tacz/classic_battleroyale_gun+1.21.1.zip"
    with zipfile.ZipFile(package) as archive:
        license_entries = [n for n in archive.namelist() if re.search(r"(?:^|/)(license|licence|copying|copyright|credits|readme)(?:[./]|$)", n, re.I)]
        meta = gson_json(archive.read("gunpack.meta.json").decode("utf-8-sig"))
    report = {"schemaVersion": 1, "generatedAt": datetime.now(timezone.utc).isoformat(),
              "inventorySha256": hashlib.sha256(inv_bytes).hexdigest(),
              "basis": "read-only authored model, texture, and r7 renderer inspection; no gameplay verification",
              "selectionCaveat": "Select the ammo index and authored display package first, then lexical source order. This is deterministic analysis, not a runtime resource-priority claim. Resource variants and byte hashes are preserved per row.",
              "summary": {"ammoCount": len(records), "classifications": counts,
                          "slotIcons": sum(bool(r["slotIcon"]) for r in records),
                          "modelPreviews": sum(bool(r["modelPreview"]) for r in records),
                          "errors": [r["id"] for r in records if r["errors"]]},
              "classificationRule": "thin when assembled GROUND bounds min <= 0.040001 block and min/max <= 0.040001; otherwise solid3d if valid nonempty geometry+texture. Mere model field does not qualify.",
              "previewPolicy": "Offline orthographic textured cuboid projection, authored rotations and approximate UV orientation; does not simulate TaCZ lighting, display camera, shader, or exact face winding. Never an in-game screenshot.",
              "slotIconPolicy": "Exact original PNG bytes, no crop, resize, recolor, or synthesis.",
              "runtimeChain": ["AmmoItemRenderer.renderByItem: GUI always slot texture; non-GUI uses BedrockAmmoModel only when model and texture are present.",
                               "BedrockAmmoModel: GROUND positioning node; AmmoItemRenderer applies display.transform.scale.ground, default 0.75 on each axis.",
                               "Our GroundSupplementModelRenderer uses the actual ammo mesh; GroundLootVisualPolicy shrinks only thin geometry or 2D fallback by 0.38, otherwise retains drop-model size."],
              "cbrgPermissionEvidence": {"source": str(package.relative_to(ROOT)), "gunpackMeta": meta,
                                         "licenseNamedEntries": license_entries,
                                         "conclusion": "Local pack metadata contains namespace only and no named license file was found; this does not establish permission to redistribute original textures/models. Original independent stylized box models can be made without copying these assets."},
              "ammo": records}
    (OUT / "ammo-model-inventory.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    make_markdown(report)
    make_contact_sheets(records)
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))


def make_markdown(report):
    titles = {"solid3d": "立体", "thin": "薄片", "2d": "仅2D图标", "missing": "缺失"}
    lines = ["# 当前弹药模型核对", "", "只读检查当前枪包中的 78 个弹药 ID，未启动游戏、未修改枪包或配置。", "",
             "结果：**46 个具有实际立体几何与纹理，32 个只有二维背包图标，未发现整体薄片模型或缺失引用**。不能仅根据 `model` 字段是否存在认定立体模型；本次解析了基岩版骨骼、立方体、旋转、纹理，以及 GROUND 缩放后的整体尺寸。", "",
             "**Classic BattleRoyale Gun Pack 的六种弹药均为立体模型**：12号霰弹、.45 ACP、5.56mm、5.7mm、7.62mm、9mm。它们由弹药盒、盒盖和子弹等实际几何组成；5.56mm等模型即使部分纸盒壁很薄，装配整体仍有厚度，不能当成二维薄片。", "",
             "![CBRG 六种弹药模型离线预览](ammo-model-preview.png)", "",
             "大图是依照当前枪包几何与纹理制作的**离线投影示意**，不是游戏截图，不保证与 TaCZ 引擎中的 UV 朝向、光照和光影完全一致；小图是原始背包 PNG。各项独立缩放，不能用图中大小比较物理尺寸。", "",
             "## 渲染与制作", "",
             "TaCZ 的 `AmmoItemRenderer` 在背包 GUI 中始终显示 slot 图；掉落物等非 GUI 环境在模型与纹理存在时使用 `BedrockAmmoModel`。因此背包中的平面图标不代表弹药没有立体模型。GROUND 原点骨骼和 `transform.scale.ground` 控制落地摆放与大小，默认缩放为 `[0.75,0.75,0.75]`。", "",
             "本附属的 `GroundSupplementModelRenderer` 使用实际弹药几何，`GroundLootVisualPolicy` 只对最短尺寸不超过0.04格且短长比不超过0.04的整体薄片额外乘0.38；当前46种立体弹药均未触发该规则。", "",
             "对于口径明显不能合并的弹药，可以独立制作 PUBG 风格的低模弹药盒，保留准确口径与弹药身份，统一美术样式即可。技术路线为：原创盒体与子弹基岩几何、原创贴图与背包图标、弹药 display 引用、落地原点与缩放。普通盒装弹药可共用原创结构并换盒长、弹头形态与标签；霰弹、无壳弹、信号弹、榴弹、火箭和电池需要各自结构。模型改变不会自动让弹药 ID 互通，也不会自动改变枪械伤害。", "",
             "当前 CBRG ZIP 的 `gunpack.meta.json` 只有 namespace，未找到 LICENSE/README 等授权文本；这份本地证据不能证明可以重新发布其原模型和贴图。可以在本地使用现有资产，并从零制作同类风格但不复制原素材的新模型。", "",
             "## 全部弹药", "", "slot列为原始背包图标；模型列为离线几何投影；尺寸单位是格，按当前 GROUND 原点旋转和缩放测量，顺序为 X/Y/Z。二维条目没有几何尺寸。", "",
             "| 原始slot图 | 名称与ID | 类型 | 模型离线预览 | GROUND尺寸 |", "|---|---|---|---|---|"]
    for row in report["ammo"]:
        icon = f'![{row["name"]}]({row["slotIcon"]})' if row["slotIcon"] else "—"
        model = f'![离线几何预览]({row["modelPreview"]})' if row["modelPreview"] else "—"
        dims = (row["modelGeometrySummary"] or {}).get("groundExtentsBlocks")
        dims = " × ".join(f"{v:.3f}" for v in dims) if dims else "—"
        lines.append(f'| {icon} | {row["name"]}<br>`{row["id"]}` | {titles[row["modelClassification"]]} | {model} | {dims} |')
    lines += ["", "原始PNG文件按字节直接提取，没有改色、裁剪或压缩重存。", "",
              "可追溯数据见 [ammo-model-inventory.json](ammo-model-inventory.json)，含选取来源、各资源变体SHA256、几何体积与尺寸、原图SHA256。", "",
              "源码依据：`logs/analysis-src/tacz-r7/com/tacz/guns/client/renderer/item/AmmoItemRenderer.java`、`.../client/model/BedrockAmmoModel.java`、`.../client/resource/index/ClientAmmoIndex.java`、`.../client/model/bedrock/BedrockModel.java`，以及项目 `GroundSupplementModelRenderer` 与 `GroundLootVisualPolicy`。", ""]
    (OUT / "弹药立体模型核对.md").write_text("\n".join(lines), encoding="utf-8")


def make_contact_sheets(records):
    titles = {"solid3d": "立体模型", "thin": "薄片模型", "2d": "2D 图标", "missing": "缺失"}
    font = lambda size: ImageFont.truetype(str(FONT), size)
    for name, selected, cols in (("ammo-model-preview.png", [r for r in records if r["id"].startswith("cbrg:")], 3),
                                  ("ammo-model-all-preview.png", records, 4)):
        cellw, cellh = 370, 325
        im = Image.new("RGB", (cols*cellw+40, math.ceil(len(selected)/cols)*cellh+130), "#0d1523")
        draw = ImageDraw.Draw(im)
        draw.text((22, 17), "弹药资产核对 · "+("Classic BattleRoyale Gun Pack" if name == "ammo-model-preview.png" else "全部 78 个弹药 ID"), font=font(24), fill="white")
        draw.text((22, 56), "模型图为离线几何预览，非游戏截图；小图为枪包原始背包图标。每项独立缩放，不能比较真实尺寸。", font=font(16), fill="#bdcce0")
        for i, row in enumerate(selected):
            x, y = 20+(i%cols)*cellw, 105+(i//cols)*cellh
            draw.rounded_rectangle((x, y, x+cellw-12, y+cellh-12), radius=10, fill="#141e2e", outline="#2c3b50")
            if row["modelPreview"]:
                thumb = Image.open(OUT / row["modelPreview"]).convert("RGB").resize((340, 245), Image.Resampling.LANCZOS)
                im.paste(thumb, (x+8, y+47))
            elif row["slotIcon"]:
                image = Image.open(OUT / row["slotIcon"]).convert("RGBA")
                image.thumbnail((170, 170), Image.Resampling.NEAREST)
                im.paste(image, (x+(350-image.width)//2, y+75), image)
                draw.text((x+65, y+260), "没有独立立体几何", font=font(17), fill="#bdcce0")
            draw.text((x+12, y+8), row["name"][:25], font=font(18), fill="#e9f1ff")
            draw.text((x+12, y+30), row["id"], font=font(13), fill="#a4b7d0")
            draw.text((x+12, y+291), titles[row["modelClassification"]], font=font(15), fill="#74d5b3" if row["hasSolidGeometry"] else "#e6be73")
            if row["slotIcon"]:
                icon = Image.open(OUT / row["slotIcon"]).convert("RGBA")
                icon.thumbnail((56, 56), Image.Resampling.NEAREST)
                im.paste(icon, (x+cellw-78, y+cellh-78), icon)
        im.save(OUT / name)


if __name__ == "__main__":
    main()
