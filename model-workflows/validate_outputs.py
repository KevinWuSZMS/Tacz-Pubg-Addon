"""Validate both exported styles and optional deployed assets, without launching Minecraft."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import numpy as np
from PIL import Image

from render_preview import FACE_CORNERS, face_vertices, mesh_triangles, read_json
from pixel_surface_seams import audit_seamless


def check(condition, message):
    if not condition:
        raise ValueError(message)


def finite(value, shape, name):
    array = np.asarray(value, dtype=float)
    check(array.shape == shape and np.isfinite(array).all(), f"Invalid finite {name}: expected {shape}")
    return array


def pixels(path):
    with Image.open(path) as image:
        return np.asarray(image.convert("RGBA"))


def resource(value: str, *, assets_root: Path | None, texture: Path, detailed=False):
    check(isinstance(value, str), "Texture reference must be a string")
    namespace, name = value.split(":", 1) if ":" in value else ("minecraft", value)
    check(re.fullmatch(r"[a-z0-9_.-]+", namespace) is not None
          and re.fullmatch(r"[a-z0-9/._-]+", name) is not None
          and ".." not in name.split("/") and not name.startswith("/"), f"Invalid resource location: {value}")
    if detailed:
        check(name.startswith("textures/") and name.endswith(".png"),
              "Detailed texture reference must include textures/ and .png")
        relative = name
    else:
        check(not name.startswith("textures/") and not name.endswith(".png"),
              "Vanilla texture reference must omit textures/ and .png")
        relative = f"textures/{name}.png"
    if assets_root:
        target = assets_root / namespace / relative
        check(target.is_file(), f"Missing deployed texture resource: {target}")
        check(np.array_equal(pixels(target), pixels(texture)), f"Deployed texture differs from supplied output: {target}")
        return str(target.resolve())
    return f"{namespace}:{name}"


def resolve_texture(value: str, textures: dict) -> str:
    seen = set()
    while value.startswith("#"):
        check(value not in seen, f"Cyclic texture reference: {value}")
        seen.add(value)
        name = value[1:]
        check(name in textures, f"Unknown texture variable: {value}")
        value = textures[name]
        check(isinstance(value, str), "Texture value must be a resource reference")
    return value


def validate(mesh_path, mesh_texture, pixel_path, pixel_texture, *, assets_root=None,
             source_tga=None, triangle_budget=6000, face_budget=6000):
    mesh, pixel = read_json(mesh_path), read_json(pixel_path)
    detailed_pixels, pixel_pixels = pixels(mesh_texture), pixels(pixel_texture)
    check(detailed_pixels.size > 0 and pixel_pixels.size > 0, "Textures must contain pixels")
    mesh_data = mesh_triangles(mesh)
    triangles = len(mesh_data)
    check(triangles <= 100_000, f"Mesh exceeds runtime limit of 100,000 triangles: {triangles}")
    check(triangles <= triangle_budget, f"Mesh exceeds requested triangle budget {triangle_budget}: {triangles}")
    normals = np.linalg.norm(mesh_data[:, :, 5:8], axis=-1)
    check(np.all(np.abs(normals - 1) < .02), "Mesh normals must be finite unit vectors within 2%")
    area = np.linalg.norm(np.cross(mesh_data[:, 1, :3] - mesh_data[:, 0, :3],
                                   mesh_data[:, 2, :3] - mesh_data[:, 0, :3]), axis=-1)
    check(np.all(area > 1e-10), "Mesh contains degenerate triangles")
    refs = {resource(mesh["texture"], assets_root=assets_root, texture=mesh_texture, detailed=True)}
    positions = mesh_data[:, :, :3].reshape(-1, 3)
    check(np.all(positions >= -16) and np.all(positions <= 32), "Detailed positions exceed agreed item-coordinate range [-16,32]")

    elements = pixel.get("elements")
    check(isinstance(elements, list) and len(elements) > 0, "Pixel model needs explicit elements")
    textures = pixel.get("textures", {})
    check(isinstance(textures, dict) and textures, "Pixel model needs texture references")
    faces, zero_thickness, face_points, pixel_refs = 0, 0, [], set()
    for index, element in enumerate(elements):
        low = finite(element.get("from"), (3,), f"element {index} from")
        high = finite(element.get("to"), (3,), f"element {index} to")
        check(np.all(low <= high), f"Reversed element bounds: {index}")
        check(np.all(low >= -16) and np.all(high <= 32), f"Element {index} outside Minecraft [-16,32] coordinates")
        zero_thickness += bool(np.any(np.abs(high - low) < 1e-10))
        rotation = element.get("rotation")
        if rotation:
            finite(rotation.get("origin"), (3,), f"element {index} rotation origin")
            check(rotation.get("axis") in "xyz" and len(rotation.get("axis", "")) == 1, f"Invalid rotation axis: {index}")
            check(rotation.get("angle") in (-45, -22.5, 0, 22.5, 45), f"Unsupported element rotation: {index}")
        actual_faces = element.get("faces")
        check(isinstance(actual_faces, dict) and 1 <= len(actual_faces) <= 6, f"Element {index} needs 1..6 actual faces")
        for name, face in actual_faces.items():
            check(name in FACE_CORNERS, f"Unknown face direction: {name}")
            uv = finite(face.get("uv"), (4,), f"element {index} {name} UV")
            check(np.all(uv >= 0) and np.all(uv <= 16), f"UV outside [0,16]: element {index} {name}")
            check(abs(uv[2] - uv[0]) > 1e-10 and abs(uv[3] - uv[1]) > 1e-10,
                  f"Zero-area UV: element {index} {name}")
            check(face.get("rotation", 0) in (0, 90, 180, 270), f"Invalid UV rotation: element {index} {name}")
            ref = resolve_texture(face.get("texture", ""), textures)
            pixel_refs.add(ref)
            quad = face_vertices(element, name, face)
            face_points.append(quad[:, :3])
            faces += 1
    check(faces <= face_budget, f"Pixel model exceeds requested face budget {face_budget}: {faces}")
    for ref in pixel_refs:
        refs.add(resource(ref, assets_root=assets_root, texture=pixel_texture))
    point_array = np.concatenate(face_points)
    check(np.isfinite(point_array).all(), "Invalid transformed pixel coordinates")
    repair = None
    gpu_quads, valid_pixel_triangles, degenerate_pixel_triangles = faces, faces * 2, 0
    if pixel.get("loader") == "tacz_pubg:seamless_pixel":
        check(pixel.get("render_type") == "minecraft:cutout", "Seamless opaque pixel surfaces must use cutout")
        quads = pixel.get("seamless_quads")
        check(isinstance(quads, list) and 0 < len(quads) <= 20_000, "Invalid seamless GPU quad count")
        for index, quad in enumerate(quads):
            check(quad.get("face") in FACE_CORNERS, f"Invalid seamless face direction: {index}")
            values = finite(quad.get("vertices"), (20,), f"seamless quad {index}").reshape(4, 5)
            check(np.all(values[:, :3] >= -16) and np.all(values[:, :3] <= 32), "Seamless position outside item coordinates")
            check(np.all(values[:, 3:5] >= 0) and np.all(values[:, 3:5] <= 16), "Seamless UV outside [0,16]")
        repair = audit_seamless(pixel)
        gpu_quads = len(quads)
        valid_pixel_triangles = repair["audited_valid_triangles"]
        degenerate_pixel_triangles = repair["degenerate_triangles"]
    source_verified = None
    if source_tga:
        check(np.array_equal(pixels(source_tga), detailed_pixels), "Source TGA and detailed PNG decoded pixels differ")
        source_verified = str(source_tga.resolve())
    return {
        "valid": True, "mesh": str(mesh_path.resolve()), "pixel": str(pixel_path.resolve()),
        "mesh_triangles": triangles, "mesh_runtime_limit": 100_000, "mesh_target_budget": triangle_budget,
        "mesh_bounds": [positions.min(0).tolist(), positions.max(0).tolist()],
        "mesh_texture_size": [detailed_pixels.shape[1], detailed_pixels.shape[0]],
        "pixel_elements": len(elements), "pixel_legacy_faces": faces,
        "pixel_faces": gpu_quads, "pixel_gpu_quads": gpu_quads, "pixel_triangles": gpu_quads * 2,
        "pixel_valid_triangles": valid_pixel_triangles, "pixel_degenerate_triangles": degenerate_pixel_triangles,
        "pixel_face_budget_met": gpu_quads <= face_budget,
        "pixel_legacy_face_budget_met": faces <= face_budget,
        "geometry_repair_overhead_quads": gpu_quads - faces,
        "geometry_repair_overhead_percent": round((gpu_quads / faces - 1) * 100, 3),
        "geometry_repair": repair,
        "pixel_target_face_budget": face_budget, "pixel_zero_thickness_elements": zero_thickness,
        "pixel_bounds": [point_array.min(0).tolist(), point_array.max(0).tolist()],
        "pixel_texture_size": [pixel_pixels.shape[1], pixel_pixels.shape[0]],
        "texture_resources": sorted(refs), "deployed_resources_verified": assets_root is not None,
        "source_tga_pixels_verified": source_verified, "minecraft_runtime_verified": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("mesh", "mesh-texture", "pixel", "pixel-texture"):
        parser.add_argument("--" + flag, type=Path, required=True)
    parser.add_argument("--assets-root", type=Path, help="Optional deployed resources/assets folder")
    parser.add_argument("--source-tga", type=Path, help="Optional source diffuse TGA, checked against detailed PNG")
    parser.add_argument("--triangle-budget", type=int, default=6000)
    parser.add_argument("--face-budget", type=int, default=6000)
    parser.add_argument("--output", type=Path, help="Optional JSON validation report")
    args = parser.parse_args()
    try:
        report = validate(args.mesh, args.mesh_texture, args.pixel, args.pixel_texture,
                          assets_root=args.assets_root, source_tga=args.source_tga,
                          triangle_budget=args.triangle_budget, face_budget=args.face_budget)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Validation failed: {error}\n")
    result = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result, encoding="utf-8")
    print(result, end="")


if __name__ == "__main__":
    main()
