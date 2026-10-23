"""Headless comparison of exported assets; this is a software preview, not a game capture.

Only NumPy and Pillow are required. Model coordinates are used directly, without
per-model fitting, so size differences remain visible in the comparison.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


# Vertex order copied from Minecraft 1.21.1 FaceInfo. Each triplet selects the
# minimum (0) or maximum (1) element coordinate. UV order follows BlockFaceUV.
FACE_CORNERS = {
    "down": ((0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)),
    "up": ((0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)),
    "north": ((1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)),
    "south": ((0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)),
    "west": ((0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)),
    "east": ((1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)),
}


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def mesh_triangles(model: dict) -> np.ndarray:
    values = np.asarray(model["vertices"], dtype=np.float64)
    if not values.size or values.size % 24 or not np.isfinite(values).all():
        raise ValueError("Invalid detailed mesh: expected finite x,y,z,u,v,nx,ny,nz triangles")
    return values.reshape(-1, 3, 8)


def missing_uv(low, high, face):
    x0, y0, z0 = low
    x1, y1, z1 = high
    return {
        "down": (x0, 16 - z1, x1, 16 - z0),
        "up": (x0, z0, x1, z1),
        "north": (16 - x1, 16 - y1, 16 - x0, 16 - y0),
        "south": (x0, 16 - y1, x1, 16 - y0),
        "west": (z0, 16 - y1, z1, 16 - y0),
        "east": (16 - z1, 16 - y1, 16 - z0, 16 - y0),
    }[face]


def face_vertices(element: dict, face_name: str, face: dict) -> np.ndarray:
    """Return four MC vertex records, respecting flipped UVs and UV rotation."""
    low, high = np.asarray(element["from"], dtype=float), np.asarray(element["to"], dtype=float)
    select = np.asarray(FACE_CORNERS[face_name])
    positions = np.where(select, high, low)
    u0, v0, u1, v1 = face.get("uv", missing_uv(low, high, face_name))
    uv = np.asarray(((u0, v0), (u0, v1), (u1, v1), (u1, v0)), dtype=float) / 16
    shift = int(face.get("rotation", 0)) // 90
    uv = uv[(np.arange(4) + shift) % 4]
    rotation = element.get("rotation")
    if rotation:
        axis = "xyz".index(rotation["axis"])
        angle = math.radians(rotation["angle"])
        c, s = math.cos(angle), math.sin(angle)
        if axis == 0:
            matrix = np.asarray(((1, 0, 0), (0, c, -s), (0, s, c)))
        elif axis == 1:
            matrix = np.asarray(((c, 0, s), (0, 1, 0), (-s, 0, c)))
        else:
            matrix = np.asarray(((c, -s, 0), (s, c, 0), (0, 0, 1)))
        origin = np.asarray(rotation["origin"], dtype=float)
        positions = (positions - origin) @ matrix.T
        if rotation.get("rescale"):
            scale = np.full(3, 1 / c)
            scale[axis] = 1
            positions *= scale
        positions += origin
    normal = np.cross(positions[1] - positions[0], positions[2] - positions[0])
    length = np.linalg.norm(normal)
    if length <= 1e-12:
        raise ValueError(f"Degenerate {face_name} face")
    normals = np.broadcast_to(normal / length, (4, 3))
    return np.concatenate((positions, uv, normals), axis=1)


def pixel_triangles(model: dict) -> np.ndarray:
    triangles = []
    if model.get("loader") == "tacz_pubg:seamless_pixel":
        normals = {"west": (-1, 0, 0), "east": (1, 0, 0), "down": (0, -1, 0),
                   "up": (0, 1, 0), "north": (0, 0, -1), "south": (0, 0, 1)}
        for record in model["seamless_quads"]:
            values = np.asarray(record["vertices"], dtype=float).reshape(4, 5)
            uv = values[:, 3:5] / 16
            quad = np.concatenate((values[:, :3], uv,
                                   np.broadcast_to(normals[record["face"]], (4, 3))), axis=1)
            for indices in ((0, 1, 2), (0, 2, 3)):
                triangle = quad[list(indices)]
                if np.linalg.norm(np.cross(triangle[1, :3] - triangle[0, :3],
                                           triangle[2, :3] - triangle[0, :3])) > 1e-12:
                    triangles.append(triangle)
        if not triangles:
            raise ValueError("Seamless pixel model has no non-degenerate faces")
        return np.asarray(triangles)
    for element in model["elements"]:
        # Zero-thickness greedy elements intentionally contain only their
        # exposed outward face; never synthesize the other five faces.
        for name, face in element["faces"].items():
            quad = face_vertices(element, name, face)
            triangles.extend((quad[[0, 1, 2]], quad[[0, 2, 3]]))
    if not triangles:
        raise ValueError("Pixel model has no faces")
    return np.asarray(triangles)


def rasterize(triangles: np.ndarray, texture: np.ndarray, *, yaw, pitch,
              size, center, scale, cull=False, wrap=False) -> Image.Image:
    """Orthographic, cutout, nearest-sampled diffuse preview with a z buffer."""
    yaw, pitch = math.radians(yaw), math.radians(pitch)
    camera = np.asarray((math.sin(yaw) * math.cos(pitch), math.sin(pitch), math.cos(yaw) * math.cos(pitch)))
    right = np.asarray((math.cos(yaw), 0, -math.sin(yaw)))
    up = np.cross(camera, right)
    points = triangles[:, :, :3] - center
    screen = np.stack((points @ right * scale + size / 2,
                       -points @ up * scale + size / 2,
                       points @ camera), axis=-1)
    image = np.full((size, size, 3), (239, 241, 242), dtype=np.uint8)
    depth = np.full((size, size), -np.inf)
    light = np.asarray((-.35, .82, .60))
    light /= np.linalg.norm(light)
    height, width = texture.shape[:2]
    for triangle, projected in zip(triangles, screen):
        if cull and float(triangle[0, 5:8] @ camera) <= 1e-9:
            continue
        xmin, ymin = np.maximum(np.floor(projected[:, :2].min(0)).astype(int), 0)
        xmax, ymax = np.minimum(np.ceil(projected[:, :2].max(0)).astype(int), size - 1)
        if xmax < xmin or ymax < ymin:
            continue
        a, b, c = projected
        denominator = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(denominator) < 1e-10:
            continue
        yy, xx = np.mgrid[ymin:ymax + 1, xmin:xmax + 1]
        xx, yy = xx + .5, yy + .5
        w0 = ((b[1] - c[1]) * (xx - c[0]) + (c[0] - b[0]) * (yy - c[1])) / denominator
        w1 = ((c[1] - a[1]) * (xx - c[0]) + (a[0] - c[0]) * (yy - c[1])) / denominator
        w2 = 1 - w0 - w1
        z = w0 * a[2] + w1 * b[2] + w2 * c[2]
        local_depth = depth[ymin:ymax + 1, xmin:xmax + 1]
        mask = (w0 >= -1e-7) & (w1 >= -1e-7) & (w2 >= -1e-7) & (z > local_depth)
        if not mask.any():
            continue
        weights = np.stack((w0[mask], w1[mask], w2[mask]), axis=-1)
        uv = weights @ triangle[:, 3:5]
        uv = np.mod(uv, 1) if wrap else np.clip(uv, 0, np.nextafter(1.0, 0))
        pixels = texture[np.minimum((uv[:, 1] * height).astype(int), height - 1),
                         np.minimum((uv[:, 0] * width).astype(int), width - 1)]
        normals = weights @ triangle[:, 5:8]
        normals /= np.maximum(np.linalg.norm(normals, axis=1, keepdims=True), 1e-12)
        shade = .48 + .52 * np.maximum(normals @ light, 0)
        rgb = np.clip(pixels[:, :3] * shade[:, None], 0, 255).astype(np.uint8)
        rows, cols = np.nonzero(mask)
        visible = pixels[:, 3] >= 128
        rows, cols = rows[visible], cols[visible]
        image[ymin + rows, xmin + cols] = rgb[visible]
        local_depth[rows, cols] = z[mask][visible]
    return Image.fromarray(image)


def font(size):
    for candidate in ("C:/Windows/Fonts/msyh.ttc", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(candidate, size)
        except OSError:
            pass
    return ImageFont.load_default()


def comparison(mesh, mesh_texture, pixel, pixel_texture, output, *, size=384, supersample=2):
    detailed, voxel = mesh_triangles(read_json(mesh)), pixel_triangles(read_json(pixel))
    with Image.open(mesh_texture) as image:
        detailed_texture = np.asarray(image.convert("RGBA"))
    with Image.open(pixel_texture) as image:
        voxel_texture = np.asarray(image.convert("RGBA"))
    all_points = np.concatenate((detailed[:, :, :3].reshape(-1, 3), voxel[:, :, :3].reshape(-1, 3)))
    low, high = all_points.min(0), all_points.max(0)
    center = (low + high) / 2
    # A common bounding sphere retains one scale for both styles and all angles.
    radius = float(np.linalg.norm(all_points - center, axis=1).max())
    if radius < 1e-9:
        raise ValueError("Model is empty or degenerate")
    scale = size * supersample * .43 / radius
    header, caption, footer = 76, 48, 62
    canvas = Image.new("RGB", (size * 4, header + 2 * (size + caption) + footer), (255, 255, 255))
    draw = ImageDraw.Draw(canvas)
    draw.text((20, 10), "Software model preview / 模型软件预览", fill=(24, 35, 43), font=font(25))
    draw.text((20, 44), "Orthographic · same scale and lighting · source diffuse texture", fill=(80, 91, 100), font=font(15))
    angles = (("Front +Z", 0, 8), ("Front 3/4", 40, 24), ("Back -Z", 180, 8), ("Back 3/4", 220, 24))
    for row, (triangles, texture, label, cull, wrap) in enumerate((
            (detailed, detailed_texture, f"萌趣世界 / Playful world · {len(detailed):,} triangles", False, True),
            (voxel, voxel_texture, f"像素硬核 / Pixel hardcore · {len(voxel) // 2:,} faces", True, False))):
        y = header + row * (size + caption)
        draw.text((16, y + 8), label, fill=(24, 35, 43), font=font(17))
        for col, (name, yaw, pitch) in enumerate(angles):
            rendered = rasterize(triangles, texture, yaw=yaw, pitch=pitch, size=size * supersample,
                                 center=center, scale=scale, cull=cull, wrap=wrap)
            if supersample != 1:
                rendered = rendered.resize((size, size), Image.Resampling.LANCZOS)
            canvas.paste(rendered, (col * size, y + caption))
            draw.text((col * size + 14, y + caption + 10), name, fill=(75, 85, 95), font=font(14))
    y = canvas.height - footer
    draw.text((20, y + 10), "Software preview only; Minecraft display transforms and in-game lighting are not verified.",
              fill=(80, 91, 100), font=font(14))
    draw.text((20, y + 32), "Diffuse + vertex/face normals. Normal maps and metallic/roughness maps are not applied.",
              fill=(80, 91, 100), font=font(14))
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output)
    return {"output": str(output.resolve()), "mesh_triangles": len(detailed), "pixel_faces": len(voxel) // 2,
            "shared_bounds": [low.tolist(), high.tolist()], "preview_only": True}


def item_sheet(items, output: Path, *, size=384, supersample=2):
    """One compact, ID-to-image sheet for independent items; reuse the same renderer."""
    if not items:
        raise ValueError("At least one item is required")
    loaded = []
    for item in items:
        detailed = mesh_triangles(read_json(Path(item["mesh"])))
        voxel = pixel_triangles(read_json(Path(item["pixel"])))
        with Image.open(item["mesh_texture"]) as im:
            dt = np.asarray(im.convert("RGBA"))
        with Image.open(item["pixel_texture"]) as im:
            vt = np.asarray(im.convert("RGBA"))
        loaded.append((item["id"], detailed, dt, voxel, vt))
    points = np.concatenate([a[:,:,:3].reshape(-1,3) for _, d, _, v, _ in loaded for a in (d,v)])
    center = (points.min(0) + points.max(0)) / 2
    radius = float(np.linalg.norm(points-center, axis=1).max())
    if radius <= 1e-9:
        raise ValueError("Degenerate item sheet")
    scale = size * supersample * .43 / radius
    header, caption, footer = 70, 52, 32
    canvas = Image.new("RGB", (len(items)*size, header+2*(caption+size)+footer), "white")
    draw = ImageDraw.Draw(canvas)
    draw.text((16,8), "Independent items / 独立物品", font=font(24), fill=(24,35,43))
    draw.text((16,40), "Top: Playful World / 萌趣世界   Bottom: Pixel Hardcore / 像素硬核", font=font(16), fill=(75,85,95))
    for col, (name, d, dt, v, vt) in enumerate(loaded):
        for row, (triangles, texture, cull, wrap, count) in enumerate((
                (d,dt,False,True,f"{len(d):,} triangles"), (v,vt,True,False,f"{len(v)//2:,} quads"))):
            y = header + row*(caption+size)
            draw.text((col*size+12,y+3), name, font=font(16), fill=(24,35,43))
            draw.text((col*size+12,y+26), count, font=font(14), fill=(75,85,95))
            rendered = rasterize(triangles, texture, yaw=40, pitch=20, size=size*supersample,
                                 center=center, scale=scale, cull=cull, wrap=wrap)
            if supersample != 1:
                rendered = rendered.resize((size,size), Image.Resampling.LANCZOS)
            canvas.paste(rendered, (col*size,y+caption))
    draw.text((16,canvas.height-25), "Software preview of exported geometry; not an in-game capture.", font=font(14), fill=(75,85,95))
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output)
    return {"output": str(output.resolve()), "items": [item["id"] for item in items], "preview_only": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("mesh", "mesh-texture", "pixel", "pixel-texture", "output"):
        parser.add_argument("--" + flag, type=Path, required=True)
    parser.add_argument("--size", type=int, default=384, help="Square view size before labels")
    parser.add_argument("--supersample", type=int, choices=(1, 2, 3), default=2)
    args = parser.parse_args()
    if not 128 <= args.size <= 2048:
        parser.error("--size must be between 128 and 2048")
    print(json.dumps(comparison(args.mesh, args.mesh_texture, args.pixel, args.pixel_texture,
                                args.output, size=args.size, supersample=args.supersample), ensure_ascii=False))


if __name__ == "__main__":
    main()
