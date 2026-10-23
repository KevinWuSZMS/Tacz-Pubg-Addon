"""Headless C4D/OBJ -> existing Playful World triangle JSON + lossless PNG.

This exports static geometry, not the source application's lighting or PBR.
The explicit triangle budget prevents accidentally shipping a cinematic mesh.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re

import numpy as np
from PIL import Image

from workflow_common import prepare_input, source_stats, write_json, png_lossless, digest


def _power_of_two(value):
    return 1 << (max(1, int(value)) - 1).bit_length()


def _pack_rectangles(rectangles, maximum=8192):
    """Pack padded rectangles on 2D shelves, without allocating bitmap memory.

    Try every allowed power-of-two width and retain the smallest complete atlas.
    This avoids a tall one-column atlas for scenes with many source materials.
    """
    if not rectangles:
        raise ValueError("Cannot pack an empty material atlas")
    if any(w <= 0 or h <= 0 or max(w, h) > maximum for w, h in rectangles):
        raise ValueError(f"A lossless material tile including gutters exceeds {maximum}")
    ordered = sorted(enumerate(rectangles), key=lambda entry: (-entry[1][1], -entry[1][0], entry[0]))
    candidates = []
    width = _power_of_two(max(w for w, _ in rectangles))
    while width <= maximum:
        shelves, positions = [], {}
        for index, (w, h) in ordered:
            fitting = [s for s in shelves if s[1] >= h and s[2] + w <= width]
            if fitting:
                shelf = min(fitting, key=lambda s: (s[1] - h, width - s[2] - w, s[0]))
            else:
                y = sum(s[1] for s in shelves)
                shelf = [y, h, 0]
                shelves.append(shelf)
            positions[index] = (shelf[2], shelf[0])
            shelf[2] += w
        height = _power_of_two(sum(s[1] for s in shelves))
        if height <= maximum:
            candidates.append((width * height, max(width, height), width, height, positions))
        width *= 2
    if not candidates:
        raise ValueError(f"Lossless 2D material atlas exceeds {maximum}; split/prepare materials explicitly")
    _, _, width, height, positions = min(candidates, key=lambda entry: entry[:4])
    return width, height, positions


def _repeat_domain(uv):
    """Return whole repeat tiles covering UVs, correcting only edge roundoff.

    UVs remain continuous across tile boundaries. Taking each vertex modulo one
    would corrupt interpolation on triangles crossing a texture seam.
    """
    near_zero = (uv < 0) & (uv >= -1e-4)
    near_one = (uv > 1) & (uv <= 1 + 1e-4)
    corrected = int(np.count_nonzero(near_zero | near_one))
    uv[near_zero], uv[near_one] = 0., 1.
    low = np.floor(uv.min(axis=0)).astype(int)
    high = np.maximum(np.ceil(uv.max(axis=0)).astype(int), low + 1)
    return (*low.tolist(), *high.tolist()), corrected


def _material_atlas(scene, arrays, used, texture_path, maximum=8192):
    """Repeat original bitmap pixels over used integer domains, then pack them."""
    padding = 2
    tiles, material_tiles, reports = [], {}, []
    unique = {}
    for index in used:
        source = Path(scene["materials"][index]["base_color_texture"])
        members = [a for mesh, a in zip(scene["meshes"], arrays) if int(mesh["material"]) == index]
        all_uv = np.concatenate([a[:, 3:5] for a in members])
        domain, corrected = _repeat_domain(all_uv)
        # Correct the same roundoff in the actual geometry before affine remap.
        for a in members:
            uv = a[:, 3:5]
            uv[(uv < 0) & (uv >= -1e-4)] = 0.
            uv[(uv > 1) & (uv <= 1 + 1e-4)] = 1.
        key = (str(source.resolve()), domain)
        if key not in unique:
            with Image.open(source) as original:
                image = original.convert("RGBA")
            u0, v0, u1, v1 = domain
            tiled_size = (image.width * (u1 - u0), image.height * (v1 - v0))
            if max(tiled_size) + padding * 2 > maximum:
                return _cropped_material_atlas(scene,arrays,used,texture_path,maximum)
            unique[key] = len(tiles)
            tiles.append({"image": image, "domain": domain, "size": tiled_size})
        tile_index = unique[key]
        material_tiles[index] = tile_index
        tile = tiles[tile_index]
        reports.append({"material": index, "size": list(tile["image"].size),
                        "pixels_preserved": True, "source_sha256": digest(source),
                        "repeat_domain": list(domain), "repeated_size": list(tile["size"]),
                        "uv_edge_roundoff_values_clamped": corrected, "atlas_tile": tile_index})
    try:
        width, height, placements = _pack_rectangles(
            [(tile["size"][0] + padding * 2, tile["size"][1] + padding * 2) for tile in tiles], maximum)
    except ValueError:
        # A tiny UV overflow must not require two or four whole duplicate images.
        # Retain exact original texels plus their periodic filtering neighbors.
        return _cropped_material_atlas(scene,arrays,used,texture_path,maximum)
    atlas = Image.new("RGBA", (width, height))
    for tile_index, tile in enumerate(tiles):
        image = tile["image"]
        w, h = tile["size"]
        tile_image = Image.new("RGBA", (w, h))
        for y in range(0, h, image.height):
            for x in range(0, w, image.width):
                tile_image.paste(image, (x, y))
        x, y = placements[tile_index]
        x, y = x + padding, y + padding
        tile["placement"] = (x, y)
        atlas.paste(tile_image, (x, y))
        # Opposite-edge gutters preserve repeat sampling even exactly at the
        # integer domain's outer boundary (and under base-level filtering).
        for i in range(padding):
            left = (w - padding + i) % w
            right = i % w
            atlas.paste(tile_image.crop((left, 0, left + 1, h)), (x - padding + i, y))
            atlas.paste(tile_image.crop((right, 0, right + 1, h)), (x + w + i, y))
        for i in range(padding):
            top = y + (h - padding + i) % h
            bottom = y + i % h
            atlas.paste(atlas.crop((x - padding, top, x + w + padding, top + 1)), (x - padding, y - padding + i))
            atlas.paste(atlas.crop((x - padding, bottom, x + w + padding, bottom + 1)), (x - padding, y + h + i))
    for mesh, a in zip(scene["meshes"], arrays):
        tile = tiles[material_tiles[int(mesh["material"])]]
        x, y = tile["placement"]
        u0, v0, _, _ = tile["domain"]
        original_w, original_h = tile["image"].size
        a[:, 3] = (x + (a[:, 3] - u0) * original_w) / width
        a[:, 4] = (y + (a[:, 4] - v0) * original_h) / height
    for report in reports:
        report["atlas_position"] = list(tiles[report["atlas_tile"]]["placement"])
        report["atlas_size"] = [width, height]
        report["atlas_gutter_pixels"] = padding
        report["atlas_gutter_mode"] = "repeat opposite edges"
    atlas.save(texture_path, optimize=True)
    return reports


def _cropped_material_atlas(scene, arrays, used, texture_path, maximum=8192):
    """Losslessly retain only the used integer-texel rectangle of repeated maps.

    UV mapping is affine across repeat seams; no vertex modulo, resampling or
    topology changes. Gutters come from the ORIGINAL periodic bitmap, not the
    opposite edge of a cropped rectangle (which need not span a whole repeat).
    """
    padding=2
    tiles=[]; by_material={}; unique={}; reports=[]
    for index in used:
        source=Path(scene["materials"][index]["base_color_texture"])
        members=[a for mesh,a in zip(scene["meshes"],arrays) if int(mesh["material"])==index]
        for a in members:
            uv=a[:,3:5]
            uv[(uv<0)&(uv>=-1e-4)]=0.
            uv[(uv>1)&(uv<=1+1e-4)]=1.
        uv=np.concatenate([a[:,3:5] for a in members])
        with Image.open(source) as image:
            size=np.array(image.size,dtype=int)
        low=np.floor(uv.min(0)*size).astype(int)
        high=np.maximum(np.ceil(uv.max(0)*size).astype(int),low+1)
        key=(str(source.resolve()),*low.tolist(),*high.tolist())
        if key not in unique:
            unique[key]=len(tiles)
            tiles.append({"source":source,"low":low,"high":high,"source_size":size})
        by_material[index]=unique[key]
        domain,_=_repeat_domain(uv.copy())
        reports.append({"material":index,"size":size.tolist(),"pixels_preserved":True,
                        "source_sha256":digest(source),"repeat_domain":list(domain),
                        "retained_texel_bounds":[low.tolist(),high.tolist()],
                        "retained_size":(high-low).tolist(),"atlas_tile":by_material[index],
                        "atlas_domain_mode":"exact used texel bounds; original periodic gutters"})
    width,height,placements=_pack_rectangles(
        [(int(t["high"][0]-t["low"][0])+2*padding,int(t["high"][1]-t["low"][1])+2*padding) for t in tiles],maximum)
    atlas=Image.new("RGBA",(width,height))
    for index,tile in enumerate(tiles):
        with Image.open(tile["source"]) as image:
            pixels=np.asarray(image.convert("RGBA"))
        low,high=tile["low"],tile["high"]
        xs=np.arange(low[0]-padding,high[0]+padding)%pixels.shape[1]
        ys=np.arange(low[1]-padding,high[1]+padding)%pixels.shape[0]
        patch=Image.fromarray(pixels[ys[:,None],xs[None,:]])
        x,y=placements[index]
        atlas.paste(patch,(x,y))
        tile["placement"]=np.array([x+padding,y+padding])
    for mesh,a in zip(scene["meshes"],arrays):
        tile=tiles[by_material[int(mesh["material"])]]
        a[:,3:5]=(a[:,3:5]*tile["source_size"]-tile["low"]+tile["placement"])/[width,height]
    for report in reports:
        report.update(atlas_position=tiles[report["atlas_tile"]]["placement"].tolist(),
                      atlas_size=[width,height],atlas_gutter_pixels=padding,
                      atlas_gutter_mode="original periodic bitmap neighbors")
    atlas.save(texture_path,optimize=True)
    return reports


def export(scene, output: Path, name="med_kit", namespace="tacz_pubg", triangle_budget=6000):
    if not re.fullmatch(r"[a-z0-9_]+", name) or not re.fullmatch(r"[a-z0-9_]+", namespace):
        raise ValueError("name and namespace must be lowercase resource identifiers")
    stats = source_stats(scene)
    if stats["triangles"] > triangle_budget:
        raise ValueError(f"Source has {stats['triangles']} triangles, above budget {triangle_budget}. "
                         "Simplify in the authoring application with UV seams protected, inspect the result, "
                         "then re-export; increasing the budget must be an explicit choice.")
    arrays = [np.asarray(m["vertices"], dtype=np.float64).reshape(-1, 8).copy() for m in scene["meshes"]]
    used = sorted({int(m["material"]) for m in scene["meshes"]})
    if scene.get("uv_origin", "top_left") == "bottom_left":
        for a in arrays:
            a[:, 4] = 1 - a[:, 4]
    elif scene.get("uv_origin", "top_left") != "top_left":
        raise ValueError("Unsupported uv_origin; use top_left or bottom_left")
    for index in used:
        factor = np.asarray(scene["materials"][index].get("base_color_factor", [1, 1, 1, 1]))
        if not np.allclose(factor, 1):
            raise ValueError("Tinted material needs explicit bitmap baking; would change original pixels")
    output.mkdir(parents=True, exist_ok=True)
    texture_path = output / "texture.png"
    texture_reports = []
    if len(used) == 1:
        source_texture = Path(scene["materials"][used[0]]["base_color_texture"])
        texture_reports.append(png_lossless(source_texture, texture_path))
        # One original bitmap, no UV adjustment, atlas resampling or lighting bake.
    else:
        texture_reports = _material_atlas(scene, arrays, used, texture_path)
    vertices = np.concatenate(arrays)
    out = {
        "format_version": 1,
        "vertex_format": "x,y,z,u,v,nx,ny,nz",
        "texture": f"{namespace}:textures/item/detail/{name}.png",
        "vertices": np.round(vertices, 6).ravel().tolist(),
    }
    write_json(output / "model.json", out)
    channels = []
    for index in used:
        mat = scene["materials"][index]
        for key in ("normal_texture", "roughness_metallic_texture"):
            if mat.get(key):
                channels.append({"material": index, "channel": key, "source": mat[key],
                                 "sha256": digest(Path(mat[key])), "runtime_applied": False})
    report = {
        "workflow": "playful_world", "schema": "agent-model-export/1", "name": name,
        "namespace": namespace, "source": scene.get("metadata", {}), "geometry": stats,
        "triangle_budget": triangle_budget, "exported_triangles": len(vertices)//3,
        "submitted_vertices_per_instance": len(vertices)//3*4,
        "decimation": "none; complete source geometry retained within budget",
        "texture_conversion": texture_reports,
        "material_policy": "base color + source vertex normals; no implicit light/normal/RM bake",
        "retained_source_channels": channels,
        "runtime_files": {"model.json": f"meshes/item/{name}.json", "texture.png": f"textures/item/detail/{name}.png"},
        "model_sha256": digest(output / "model.json"), "texture_sha256": digest(texture_path),
    }
    write_json(output / "report.json", report, pretty=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--source", type=Path, help="Static .c4d or exported .obj")
    inputs.add_argument("--scene", type=Path, help="Previously prepared canonical scene")
    parser.add_argument("--mesh", action="append", help="Select whole source mesh by exact name or zero-based index; repeat to group parts")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--name", default="med_kit")
    parser.add_argument("--namespace", default="tacz_pubg")
    parser.add_argument("--triangle-budget", type=int, default=6000)
    parser.add_argument("--max-size", type=float, default=14)
    parser.add_argument("--rotate", nargs=3, type=float, default=(0, 0, 0), metavar=("X", "Y", "Z"))
    args = parser.parse_args()
    if args.triangle_budget < 1:
        parser.error("triangle-budget must be positive")
    scene, canonical = prepare_input(scene_path=args.scene, source=args.source,
                                     output=args.output, max_size=args.max_size, rotate=args.rotate,
                                     mesh_selectors=args.mesh)
    report = export(scene, args.output, args.name, args.namespace, args.triangle_budget)
    print(f"Playful World: {report['exported_triangles']} triangles, canonical={canonical}, output={args.output}")


if __name__ == "__main__":
    main()
