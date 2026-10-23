"""Audit the deployed pixel canopy against the original C4D/pixel archives.

This is an offline asset check, not an in-game render test. Run after
export_parachute_canopy_pixel.py using the bundled NumPy/Pillow interpreter.
"""
from pathlib import Path

import numpy as np
from PIL import Image

from export_parachute_canopy_pixel import world_vertices
from parachute_rope_geometry import (LOD_DISTANCES, geometry_hash, rope_levels,
                                     rope_skeleton, rope_uv, segment_quads,
                                     source_regions, static_pixel_faces)
from render_preview import face_vertices, read_json
from workflow_common import digest, write_json


def main():
    workflow = Path(__file__).resolve().parent
    pack = workflow / "output/pack_82"
    assets = workflow.parent / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg"
    entry = next(item for item in read_json(pack / "pack.json")["items"] if item["id"] == "item_042_01")
    original = read_json(pack / entry["pixel"])
    original_quads = np.asarray([face_vertices(element, name, face)
                                 for element in original["elements"]
                                 for name, face in element["faces"].items()])
    source = read_json(pack / entry["scene"])
    triangles = np.concatenate([np.asarray(mesh["vertices"]).reshape(-1, 3, 8) for mesh in source["meshes"]])
    points, indices, rope, canopy, _ = source_regions(triangles)
    expected_source, retained, _, clipping = static_pixel_faces(triangles, canopy, original_quads)
    expected_body = world_vertices(expected_source).round(6)
    deployed = read_json(assets / "meshes/entity/parachute_canopy_pixel.json")
    body = np.asarray(deployed["vertices"]).reshape(-1, 4, 8)
    assert np.array_equal(body, expected_body), "Canopy footprint/UVs changed relative to source patches"
    assert body[..., 1].min() > 2.5, "Bottom pixel harness block remains"
    texture_path = assets / "textures/entity/parachute_canopy_pixel.png"
    original_texture = (pack / entry["pixel"]).with_name("texture.png")
    assert digest(texture_path) == digest(original_texture), "Original pixel atlas changed"
    uv, _ = rope_uv(original_quads, retained, np.asarray(Image.open(original_texture).convert("RGBA")))
    centers, links = rope_skeleton(points, indices, rope)
    levels, _ = rope_levels(centers, links)
    world_centers = world_vertices(np.concatenate((centers, np.zeros((len(centers), 5))), axis=1))[..., :3]
    segments = np.asarray([segment_quads(world_centers[a], world_centers[b], uv) for a, b in links]).round(6)
    rope_arrays = []
    for index, (distance, level) in enumerate(zip(LOD_DISTANCES, levels)):
        actual = deployed["rope_lods"][index]
        assert actual["max_distance"] == distance
        values = np.asarray(actual["vertices"]).reshape(-1, 4, 8)
        assert np.array_equal(values, segments[level].reshape(-1, 4, 8)), "Rope left its source-derived path"
        assert len(body) + len(values) <= 2400
        assert np.isfinite(values).all()
        assert np.allclose(np.linalg.norm(values[..., 5:8], axis=-1), 1, atol=1e-6)
        rope_arrays.append(values)
    assert set(levels[2]) < set(levels[1]) < set(levels[0]), "LOD paths are not nested"
    # Mirrors the resource-load bounds calculation; every discarded/subset tier
    # must fit within the full near geometry, including its lowest rope endpoint.
    near_points = np.concatenate((body[..., :3].reshape(-1, 3), rope_arrays[0][..., :3].reshape(-1, 3)))
    radius = np.linalg.norm(near_points[:, [0, 2]], axis=1).max()
    min_y, max_y = near_points[:, 1].min(), near_points[:, 1].max()
    for values in rope_arrays:
        assert values[..., 1].min() >= min_y and values[..., 1].max() <= max_y
        assert np.linalg.norm(values[..., [0, 2]], axis=-1).max() <= radius
    report = read_json(workflow / "output/parachute_canopy_pixel/export.json")
    assert report["preserved_geometry_sha256"] == geometry_hash(body)
    result = {"passed": True, "canopy_patch_sha256": geometry_hash(body),
              "atlas_sha256": digest(texture_path), "bottom_block_absent": True,
              "canopy_original_surface_and_uv_equivalent": True,
              "unmodified_source_quad_count": clipping["unchanged_original_quads"],
              "mixed_original_quad_count": clipping["clipped_original_quads"],
              "canopy_quad_count": len(body), "source_derived_rope_segment_count": len(links),
              "lod_segments": list(map(len, levels)),
              "lod_submitted_vertices": [(len(body) + len(values)) * 4 for values in rope_arrays] + [len(body) * 4],
              "all_rope_endpoints_in_culling_bounds": True,
              "culling_bounds": {"radius": float(radius), "min_y": float(min_y), "max_y": float(max_y)},
              "in_game_verified": False}
    write_json(workflow / "output/parachute_canopy_pixel/verification.json", result, pretty=True)
    print(f"PASS: exact original canopy patches/UVs/atlas; bottom block absent; source ropes and nested LODs {list(map(len, levels))}")
    print(f"PASS: all rope endpoints within loaded culling bounds; submitted vertices {result['lod_submitted_vertices']}")
    print(f"Canopy SHA256: {result['canopy_patch_sha256']}")


if __name__ == "__main__":
    main()
