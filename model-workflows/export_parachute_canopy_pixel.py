"""Export the existing density36 canopy with source-derived thin suspension ropes.

Preserves the existing canopy surface, UV mapping and atlas. Mixed greedy quads
are clipped on original voxel boundaries; rope-only voxel faces are replaced
with square-section source centreline segments. The bottom pixel harness block
is omitted as requested.
Three rope-only LODs are built offline; no source files or registrations change.
Run from the workspace root with the bundled NumPy/Pillow Python interpreter.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import shutil

import numpy as np
from PIL import Image, ImageDraw

from render_preview import face_vertices, font, mesh_triangles, pixel_triangles, rasterize, read_json
from parachute_rope_geometry import (LOD_DISTANCES, ROPE_WIDTH_METRES, geometry_hash,
                                     rope_levels, rope_skeleton, rope_uv, segment_quads,
                                     source_regions, static_pixel_faces)
from workflow_common import digest, write_json


def world_vertices(vertices):
    """Match the detailed canopy's 6-metre span, +90 Y rotation and harness origin."""
    result = np.array(vertices, dtype=np.float64, copy=True)
    original = result.copy()
    scale = 6 / 14
    result[..., 0] = (original[..., 2] - 8) * scale
    result[..., 1] = (original[..., 1] - 1) * scale
    result[..., 2] = -(original[..., 0] - 8) * scale
    result[..., 5] = original[..., 7]
    result[..., 6] = original[..., 6]
    result[..., 7] = -original[..., 5]
    return result


def preview_styles(detailed_path, detailed_texture, pixel_quads, pixel_texture, output, size=384):
    detailed = mesh_triangles(read_json(detailed_path))
    pixel = pixel_quads[:, [[0, 1, 2], [0, 2, 3]], :].reshape(-1, 3, 8)
    points = np.concatenate((detailed[..., :3].reshape(-1, 3), pixel[..., :3].reshape(-1, 3)))
    center = (points.min(0) + points.max(0)) / 2
    radius = np.linalg.norm(points - center, axis=1).max()
    scale = size * .43 / radius
    canvas = Image.new("RGB", (size * 3, size * 2 + 90), "white")
    draw = ImageDraw.Draw(canvas)
    draw.text((16, 8), "Deployed source canopy / detailed and preserved density36 canopy + thin ropes",
              fill="#18232b", font=font(20))
    for row, (label, geometry, texture_path) in enumerate((
            (f"Detailed / {len(detailed):,} triangles", detailed, detailed_texture),
            (f"Pixel / {len(pixel_quads):,} quads / 1 cm source-derived ropes", pixel, pixel_texture))):
        texture = np.asarray(Image.open(texture_path).convert("RGBA"))
        draw.text((16, 40 + row * (size + 25)), label, fill="#18232b", font=font(18))
        for column, (yaw, pitch) in enumerate(((0, 15), (90, 15), (40, 25))):
            rendered = rasterize(geometry, texture, yaw=yaw, pitch=pitch, size=size,
                                 center=center, scale=scale, cull=False, wrap=row == 0)
            canvas.paste(rendered, (column * size, 65 + row * (size + 25)))
    canvas.save(output)


def preview_lods(original, body, rope_quads, texture_path, output, size=384):
    texture = np.asarray(Image.open(texture_path).convert("RGBA"))
    points = original[..., :3].reshape(-1, 3)
    center = (points.min(0) + points.max(0)) / 2
    radius = np.linalg.norm(points - center, axis=1).max()
    near = np.concatenate((body, rope_quads[0]))
    sheet = Image.new("RGB", (size * 3, size * 2 + 80), "white")
    draw = ImageDraw.Draw(sheet)
    for row, (label, quads) in enumerate((("Before: 16.7 cm voxel ropes", original),
                                          ("After: 1 cm ropes; original canopy / atlas; bottom block removed", near))):
        draw.text((12, 8 + row * (size + 35)), label, fill="#18232b", font=font(17))
        triangles = quads[:, [[0, 1, 2], [0, 2, 3]], :].reshape(-1, 3, 8)
        for col, (yaw, pitch) in enumerate(((0, 15), (90, 15), (40, 25))):
            rendered = rasterize(triangles, texture, yaw=yaw, pitch=pitch, size=size * 2,
                                 center=center, scale=size * 2 * .43 / radius, cull=False)
            rendered = rendered.resize((size, size), Image.Resampling.LANCZOS)
            sheet.paste(rendered, (col * size, 35 + row * (size + 35)))
    sheet.save(output / "rope_before_after.png")

    # All four levels shown at a common scale to inspect attachment paths.
    # A second row uses the same focal length at actual 16/48/96/160 m distances.
    sheet = Image.new("RGB", (size * 4, size * 2 + 90), "white")
    draw = ImageDraw.Draw(sheet)
    levels = [np.concatenate((body, ropes)) for ropes in rope_quads] + [body]
    for col, (label, distance, quads) in enumerate(zip(
            ("Near <= 32 m", "Medium <= 64 m", "Far <= 128 m", "Beyond 128 m"),
            (16, 48, 96, 160), levels)):
        draw.text((col * size + 12, 8), f"{label} / {len(quads)} quads", fill="#18232b", font=font(16))
        triangles = quads[:, [[0, 1, 2], [0, 2, 3]], :].reshape(-1, 3, 8)
        for row in range(2):
            scale = size * 2 * .43 / radius if row == 0 else size * 2 / (2 * np.tan(np.deg2rad(35)) * distance)
            rendered = rasterize(triangles, texture, yaw=40, pitch=15, size=size * 2,
                                 center=center, scale=scale, cull=False)
            rendered = rendered.resize((size, size), Image.Resampling.LANCZOS)
            sheet.paste(rendered, (col * size, 35 + row * (size + 30)))
            if row:
                draw.text((col * size + 12, size + 38), f"70 deg FOV / {distance} m", fill="#18232b", font=font(16))
    sheet.save(output / "rope_lod_preview.png")


def run(pack_root, output, assets_root):
    pack = read_json(pack_root / "pack.json")
    entry = next(item for item in pack["items"] if item["id"] == "item_042_01")
    if entry.get("pixel_resolution") != 36:
        raise ValueError("The deployed canopy requires the existing fixed-density36 pixel source")
    source_path = pack_root / entry["pixel"]
    texture_path = source_path.with_name("texture.png")
    source = read_json(source_path)
    quads = np.asarray([face_vertices(element, name, face)
                        for element in source["elements"]
                        for name, face in element["faces"].items()])
    if not 0 < len(quads) <= 2400:
        raise ValueError("Canopy pixel source exceeds its explicit 2400-quad entity budget")
    deployed = world_vertices(quads).round(6)
    triangles = deployed[:, [[0, 1, 2], [0, 2, 3]], :].reshape(-1, 3, 8)
    original_triangles = world_vertices(pixel_triangles(source))
    if not np.allclose(triangles, original_triangles, rtol=0, atol=5.01e-7):
        raise ValueError("Converted quads differ from the established pixel decoder")
    if not np.isfinite(deployed).all() or not np.allclose(np.linalg.norm(deployed[..., 5:8], axis=-1), 1):
        raise ValueError("Invalid deployed pixel positions or normals")
    if not np.allclose(deployed[..., 3:5], quads[..., 3:5], rtol=0, atol=5.01e-7):
        raise ValueError("Pixel atlas UVs changed")
    points = deployed[..., :3].reshape(-1, 3)
    if not np.isclose(np.ptp(points[:, 0]), 6, atol=1e-6):
        raise ValueError("Pixel canopy no longer matches the detailed 6-metre span")
    scene_path = pack_root / entry["scene"]
    scene = read_json(scene_path)
    source_triangles = np.concatenate([np.asarray(mesh["vertices"]).reshape(-1, 3, 8)
                                       for mesh in scene["meshes"]])
    source_points, indices, rope, canopy, components = source_regions(source_triangles)
    body_source, retained, source_pitch, clipping_report = static_pixel_faces(source_triangles, canopy, quads)
    body = world_vertices(body_source).round(6)
    centers, links = rope_skeleton(source_points, indices, rope)
    levels, attachments = rope_levels(centers, links)
    texture = np.asarray(Image.open(texture_path).convert("RGBA"))
    uv, rope_color = rope_uv(quads, retained, texture)
    world_centers = world_vertices(np.concatenate((centers, np.zeros((len(centers), 5))), axis=1))[..., :3]
    segments = np.asarray([segment_quads(world_centers[a], world_centers[b], uv) for a, b in links]).round(6)
    rope_quads = [segments[level].reshape(-1, 4, 8) for level in levels]
    for output_face, source_face in zip(body, clipping_report["fragment_source_face_indices"]):
        if source_face in clipping_report["unchanged_face_indices"] and not np.array_equal(output_face, deployed[source_face]):
            raise ValueError("An unchanged canopy quad vertex or UV changed")
    if body[..., 1].min() <= 2.5:
        raise ValueError("The removed bottom pixel harness block is still present")
    if len(body) + len(rope_quads[0]) > 2400:
        raise ValueError("Thin-rope canopy exceeds its runtime quad budget")
    for values in rope_quads:
        if not np.isfinite(values).all() or not np.allclose(
                np.linalg.norm(values[..., 5:8], axis=-1), 1, atol=1e-6):
            raise ValueError("Invalid thin-rope positions or normals")
    model = {"format_version": 2, "vertex_format": "x,y,z,u,v,nx,ny,nz", "primitive": "quads",
             "coordinate_units": "blocks", "texture": "tacz_pubg:textures/entity/parachute_canopy_pixel.png",
             "vertices": body.reshape(-1).tolist(),
             "rope_lods": [{"max_distance": distance, "source_segments": len(level),
                            "canopy_attachments": attachment, "vertices": values.reshape(-1).tolist()}
                           for distance, level, attachment, values in zip(LOD_DISTANCES, levels, attachments, rope_quads)]}
    output.mkdir(parents=True, exist_ok=True)
    write_json(output / "model.json", model)
    shutil.copy2(texture_path, output / "texture.png")
    for folder in ("meshes/entity", "textures/entity"):
        (assets_root / folder).mkdir(parents=True, exist_ok=True)
    target_model = assets_root / "meshes/entity/parachute_canopy_pixel.json"
    target_texture = assets_root / "textures/entity/parachute_canopy_pixel.png"
    shutil.copy2(output / "model.json", target_model)
    shutil.copy2(texture_path, target_texture)
    if digest(target_texture) != digest(texture_path):
        raise ValueError("Source pixel atlas pixels were not preserved")
    preview_styles(assets_root / "meshes/entity/parachute_canopy.json",
                   assets_root / "textures/entity/parachute_canopy.png",
                   np.concatenate((body, rope_quads[0])), target_texture, output / "styles_preview.png")
    preview_lods(deployed, body, rope_quads, target_texture, output)
    report = {"source_item": entry["id"], "source_pixel": str(source_path),
              "source_model_sha256": digest(source_path), "source_texture_sha256": digest(texture_path),
              "source_scene": str(scene_path), "source_scene_sha256": digest(scene_path),
              "pixel_resolution": 36, "original_quads": len(quads),
              "preserved_canopy_quads": len(body), "removed_rope_and_harness_voxel_quads": len(quads) - len(retained),
              "canopy_clipping": clipping_report,
              "preserved_geometry_sha256": geometry_hash(body),
              "exported_quads": len(body) + len(rope_quads[0]), "quad_budget": 2400,
              "canopy_surface_uv_mapping_exactly_preserved": True,
              "bottom_harness_block_removed": True, "canopy_min_y_metres": float(body[..., 1].min()),
              "source_rope_triangles": int(rope.sum()), "source_components": components,
              "source_rope_segments": len(links), "rope_width_metres": ROPE_WIDTH_METRES,
              "original_voxel_width_metres": source_pitch * 6 / 14,
              "rope_width_ratio": ROPE_WIDTH_METRES / (source_pitch * 6 / 14),
              "rope_color_rgba": rope_color, "rope_atlas_uv": uv.tolist(),
              "rope_lods": [{"max_distance": distance, "segments": len(level),
                             "canopy_attachments": attachment, "rope_quads": len(values),
                             "total_quads": len(body) + len(values), "submitted_vertices": (len(body) + len(values)) * 4}
                            for distance, level, attachment, values in zip(LOD_DISTANCES, levels, attachments, rope_quads)],
              "beyond_128m_rope_quads": 0, "beyond_128m_canopy_quads": len(body),
              "local_player_rope_lod": "always full, including first person",
              "atlas_pixels_preserved": True, "decoder": "render_preview.face_vertices",
              "span_metres": 6, "uniform_scale": 6 / 14, "source_harness_origin": [8, 1, 8],
              "rotation_degrees_y": 90, "coordinate_units": "blocks",
              "block_bounds": [points.min(0).tolist(), points.max(0).tolist()],
              "model_sha256": digest(target_model), "texture_sha256": digest(target_texture),
              "validation": "Original canopy surface footprint and affine UV equality; mixed-face clipping coverage; bottom block absent; source-topology paths; normals; span; atlas hash; four-distance software preview",
              "in_game_verified": False}
    write_json(output / "export.json", report, pretty=True)
    return report


def main():
    workflow = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, default=workflow / "output/pack_82")
    parser.add_argument("--output", type=Path, default=workflow / "output/parachute_canopy_pixel")
    parser.add_argument("--assets-root", type=Path,
                        default=workflow.parent / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg")
    args = parser.parse_args()
    report = run(args.pack, args.output, args.assets_root)
    print(f"PASS: {report['preserved_canopy_quads']} original canopy surface quads; "
          f"{report['source_rope_segments']} source rope segments; 1 cm square-section ropes; atlas preserved")
    print(f"Mixed original faces clipped: {report['canopy_clipping']['clipped_original_quads']}; bottom block removed")
    print(f"LOD quads: {[level['total_quads'] for level in report['rope_lods']]} / "
          f"{report['beyond_128m_canopy_quads']}; distance thresholds: {LOD_DISTANCES} m")
    print(f"Preview: {args.output / 'rope_lod_preview.png'}")


if __name__ == "__main__":
    main()
