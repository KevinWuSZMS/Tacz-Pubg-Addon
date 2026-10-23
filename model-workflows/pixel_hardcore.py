"""Convert an agent-model-scene/1 triangle scene into a low-cost vanilla item.

This is an offline agent tool, not a Minecraft mod feature.  It conservatively
voxelizes source triangles without filling enclosed space, samples their source
UVs, and greedily joins visible voxel faces into textured vanilla elements.

Usage::

    python pixel_hardcore.py --scene scene.json --output build/pixel \
        --name med_kit --namespace tacz_pubg --resolution 36 --max-elements 1800

Requires Python 3.10+, NumPy and Pillow.  Canonical texture UVs default to
top-left; a scene can explicitly set ``uv_origin`` to ``bottom_left``.
"""

from __future__ import annotations

import argparse
from collections import deque
import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image

from workflow_common import default_display, prepare_input
from pixel_surface_seams import seamless_model


SCHEMA = "agent-model-scene/1"
DEFAULT_RESOLUTION = 36
DIRECTIONS = (("west", 0, -1), ("east", 0, 1),
              ("down", 1, -1), ("up", 1, 1),
              ("north", 2, -1), ("south", 2, 1))


@dataclass
class Material:
    name: str
    image: np.ndarray | None
    factor: np.ndarray


@dataclass
class Scene:
    positions: np.ndarray
    uvs: np.ndarray
    material_ids: np.ndarray
    materials: list[Material]
    uv_origin: str
    source_path: Path


@dataclass
class VoxelSurface:
    occupied: np.ndarray
    colors: np.ndarray
    origin: np.ndarray
    pitch: float
    resolution: int
    degenerate_triangles: int


@dataclass
class Rectangle:
    direction: str
    start: list[float]
    end: list[float]
    pixels: np.ndarray
    uv: list[float] | None = None


def read_scene(path: Path) -> Scene:
    """Read the small, deliberately explicit interchange format used by agents."""
    path = path.resolve()
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if data.get("schema") != SCHEMA:
        raise ValueError(f"Expected schema {SCHEMA!r}; got {data.get('schema')!r}")
    materials = []
    for i, record in enumerate(data.get("materials", [])):
        image = None
        texture = record.get("base_color_texture")
        if texture:
            texture_path = Path(texture)
            if not texture_path.is_absolute():
                texture_path = path.parent / texture_path
            with Image.open(texture_path) as source:
                image = np.asarray(source.convert("RGBA"), dtype=np.uint8).copy()
        factor = np.asarray(record.get("base_color_factor", [1, 1, 1, 1]), dtype=float)
        if factor.size == 3:
            factor = np.append(factor, 1)
        if factor.shape != (4,) or not np.isfinite(factor).all():
            raise ValueError(f"Material {i} has an invalid base_color_factor")
        materials.append(Material(record.get("name", f"material_{i}"), image,
                                  np.clip(factor, 0, 1)))
    if not materials:
        materials = [Material("default", None, np.ones(4))]
    positions, uvs, material_ids = [], [], []
    for mesh in data.get("meshes", []):
        vertices = np.asarray(mesh.get("vertices", []), dtype=float)
        if vertices.size == 0:
            continue
        if vertices.size % 24:
            raise ValueError(f"Mesh {mesh.get('name')!r}: vertices must be triangle lists with stride 8")
        vertices = vertices.reshape(-1, 3, 8)
        if not np.isfinite(vertices).all():
            raise ValueError(f"Mesh {mesh.get('name')!r} contains non-finite vertex data")
        material_id = int(mesh.get("material", 0))
        if not 0 <= material_id < len(materials):
            raise ValueError(f"Mesh {mesh.get('name')!r} has invalid material {material_id}")
        positions.append(vertices[:, :, :3])
        uvs.append(vertices[:, :, 3:5])
        material_ids.extend([material_id] * len(vertices))
    if not positions:
        raise ValueError("Scene contains no triangles")
    positions_array = np.concatenate(positions)
    if np.any(positions_array < -16) or np.any(positions_array > 32):
        raise ValueError("Normalize source coordinates into Minecraft's [-16, 32] model range first")
    uv_origin = data.get("uv_origin", "top_left").replace("-", "_")
    if uv_origin not in ("top_left", "bottom_left"):
        raise ValueError("uv_origin must be top_left or bottom_left")
    return Scene(positions_array, np.concatenate(uvs), np.asarray(material_ids),
                 materials, uv_origin, path)


def closest_barycentric(points: np.ndarray, triangle: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Return squared distance and barycentric coordinates of closest points."""
    a, b, c = triangle
    ab, ac = b - a, c - a
    offset = points - a
    d00, d01, d11 = ab @ ab, ab @ ac, ac @ ac
    determinant = np.cross(ab, ac) @ np.cross(ab, ac)
    v = (d11 * (offset @ ab) - d01 * (offset @ ac)) / determinant
    w = (d00 * (offset @ ac) - d01 * (offset @ ab)) / determinant
    weights = np.column_stack((1 - v - w, v, w))
    projected = weights @ triangle
    distance = np.einsum("ij,ij->i", points - projected, points - projected)
    distance[np.any(weights < 0, axis=1)] = np.inf
    for start, end in ((0, 1), (1, 2), (2, 0)):
        edge = triangle[end] - triangle[start]
        denominator = edge @ edge
        if denominator < 1e-24:
            continue
        t = np.clip(((points - triangle[start]) @ edge) / denominator, 0, 1)
        on_edge = triangle[start] + t[:, None] * edge
        edge_distance = np.einsum("ij,ij->i", points - on_edge, points - on_edge)
        replace = edge_distance < distance
        if replace.any():
            distance[replace] = edge_distance[replace]
            weights[replace] = 0
            weights[replace, start] = 1 - t[replace]
            weights[replace, end] = t[replace]
    return distance, weights


def triangle_box_overlap(centers: np.ndarray, triangle: np.ndarray, half: float) -> np.ndarray:
    """Separating-axis triangle/box test; catches thin triangles without filling."""
    low, high = triangle.min(axis=0), triangle.max(axis=0)
    keep = np.all((centers + half >= low) & (centers - half <= high), axis=1)
    edges = np.roll(triangle, -1, axis=0) - triangle
    axes = [np.cross(edges[0], edges[1])]
    for edge in edges:
        axes.extend(np.cross(edge, axis) for axis in np.eye(3))
    for axis in axes:
        if np.linalg.norm(axis) < 1e-15:
            continue
        projection = triangle @ axis
        middle = centers @ axis
        radius = half * np.abs(axis).sum()
        keep &= (middle + radius >= projection.min()) & (middle - radius <= projection.max())
        if not keep.any():
            break
    return keep


def sample_material(material: Material, uv: np.ndarray, uv_origin: str) -> np.ndarray:
    """Sample diffuse RGBA only; no lighting or PBR effects are baked into color."""
    if material.image is None:
        return np.tile(np.rint(material.factor * 255).astype(np.uint8), (len(uv), 1))
    height, width = material.image.shape[:2]
    # Wrapping preserves tiled source UVs; exact 1.0 samples the repeat seam.
    texture_uv = np.mod(uv, 1.0)
    x = texture_uv[:, 0] * width - 0.5
    v = texture_uv[:, 1] if uv_origin == "top_left" else 1 - texture_uv[:, 1]
    y = v * height - 0.5
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    tx, ty = (x - x0)[:, None], (y - y0)[:, None]
    pixels = material.image
    value = ((1 - ty) * ((1 - tx) * pixels[y0 % height, x0 % width] + tx * pixels[y0 % height, (x0 + 1) % width])
             + ty * ((1 - tx) * pixels[(y0 + 1) % height, x0 % width] + tx * pixels[(y0 + 1) % height, (x0 + 1) % width]))
    return np.rint(np.clip(value * material.factor, 0, 255)).astype(np.uint8)


def voxelize(scene: Scene, resolution: int) -> VoxelSurface:
    bounds_min = scene.positions.min(axis=(0, 1))
    bounds_max = scene.positions.max(axis=(0, 1))
    span = bounds_max - bounds_min
    if span.max() < 1e-9:
        raise ValueError("Source mesh has zero spatial extent")
    pitch = float(span.max() / resolution)
    # Center the small rounding remainder so each axis remains within source bounds
    # to within half a voxel, without stretching the source independently per axis.
    shape = np.maximum(1, np.ceil(span / pitch - 1e-8).astype(int))
    origin = bounds_min - (shape * pitch - span) / 2
    if np.any(origin < -16) or np.any(origin + shape * pitch > 32):
        raise ValueError("Voxel shell exceeds Minecraft's element coordinate range; leave a half-voxel margin when normalizing")
    occupied = np.zeros(tuple(shape), dtype=bool)
    voxel_count = int(np.prod(shape))
    colors = np.zeros((voxel_count, 6, 4), dtype=np.uint8)
    scores = np.full((voxel_count, 6), np.inf, dtype=np.float32)
    degenerate = 0
    face_offsets = np.zeros((6, 3))
    for direction_id, (_, axis, sign) in enumerate(DIRECTIONS):
        face_offsets[direction_id, axis] = sign * pitch * 0.5
    for triangle_id, triangle in enumerate(scene.positions):
        normal = np.cross(triangle[1] - triangle[0], triangle[2] - triangle[0])
        normal_length = np.linalg.norm(normal)
        if normal_length < 1e-12:
            degenerate += 1
            continue
        normal /= normal_length
        start = np.clip(np.floor((triangle.min(axis=0) - origin) / pitch).astype(int), 0, shape - 1)
        stop = np.clip(np.floor((triangle.max(axis=0) - origin) / pitch).astype(int), 0, shape - 1)
        indices = np.stack(np.meshgrid(*(np.arange(start[i], stop[i] + 1) for i in range(3)), indexing="ij"), axis=-1).reshape(-1, 3)
        centers = origin + (indices + 0.5) * pitch
        keep = triangle_box_overlap(centers, triangle, pitch * (0.5 + 1e-7))
        indices, centers = indices[keep], centers[keep]
        if not len(indices):
            continue
        index_tuple = tuple(indices.T)
        occupied[index_tuple] = True
        flat_indices = np.ravel_multi_index(index_tuple, shape)
        material = scene.materials[int(scene.material_ids[triangle_id])]
        for direction_id in range(6):
            distance, weights = closest_barycentric(centers + face_offsets[direction_id], triangle)
            # Only a tie breaker: choose the least tangential original surface.
            distance += (1 - abs(normal @ (face_offsets[direction_id] / (pitch * 0.5)))) * pitch * pitch * 1e-6
            replace = distance < scores[flat_indices, direction_id]
            if replace.any():
                selected = flat_indices[replace]
                scores[selected, direction_id] = distance[replace]
                colors[selected, direction_id] = sample_material(material, weights[replace] @ scene.uvs[triangle_id], scene.uv_origin)
    return VoxelSurface(occupied, colors.reshape((*shape, 6, 4)), origin, pitch, resolution, degenerate)


def greedy_rectangles(mask: np.ndarray):
    """Partition a 2D mask into deterministic nonoverlapping maximal rectangles."""
    remaining = mask.copy()
    for first in range(remaining.shape[0]):
        second = 0
        while second < remaining.shape[1]:
            if not remaining[first, second]:
                second += 1
                continue
            end_second = second + 1
            while end_second < remaining.shape[1] and remaining[first, end_second]:
                end_second += 1
            end_first = first + 1
            while end_first < remaining.shape[0] and remaining[end_first, second:end_second].all():
                end_first += 1
            remaining[first:end_first, second:end_second] = False
            yield first, end_first, second, end_second
            second = end_second


def exterior_air(occupied: np.ndarray) -> np.ndarray:
    """Find reachable empty space without filling or changing source geometry."""
    reachable = np.zeros_like(occupied)
    queue = deque()
    for axis in range(3):
        other_axes = [index for index in range(3) if index != axis]
        for boundary in (0, occupied.shape[axis] - 1):
            for first, second in np.argwhere(~np.take(occupied, boundary, axis=axis)):
                point = [0, 0, 0]
                point[axis] = boundary
                point[other_axes[0]] = int(first)
                point[other_axes[1]] = int(second)
                index = tuple(point)
                if not reachable[index]:
                    reachable[index] = True
                    queue.append(index)
    while queue:
        point = queue.popleft()
        for axis in range(3):
            for sign in (-1, 1):
                neighbor = list(point)
                neighbor[axis] += sign
                if not 0 <= neighbor[axis] < occupied.shape[axis]:
                    continue
                index = tuple(neighbor)
                if not occupied[index] and not reachable[index]:
                    reachable[index] = True
                    queue.append(index)
    return reachable


def surface_rectangles(surface: VoxelSurface) -> tuple[list[Rectangle], int]:
    occupied = surface.occupied
    air = exterior_air(occupied)
    rectangles = []
    raw_faces = 0
    for direction_id, (direction, axis, sign) in enumerate(DIRECTIONS):
        other_axes = [i for i in range(3) if i != axis]
        for layer in range(occupied.shape[axis]):
            mask = np.take(occupied, layer, axis=axis).copy()
            neighbor = layer + sign
            if 0 <= neighbor < occupied.shape[axis]:
                mask &= np.take(air, neighbor, axis=axis)
            raw_faces += int(mask.sum())
            for a0, a1, b0, b1 in greedy_rectangles(mask):
                start = surface.origin.copy()
                end = surface.origin.copy()
                start[axis] += (layer + (sign > 0)) * surface.pitch
                end[axis] = start[axis]
                start[other_axes[0]] += a0 * surface.pitch
                end[other_axes[0]] += a1 * surface.pitch
                start[other_axes[1]] += b0 * surface.pitch
                end[other_axes[1]] += b1 * surface.pitch
                selector = [slice(None)] * 3
                selector[axis] = layer
                selector[other_axes[0]] = slice(a0, a1)
                selector[other_axes[1]] = slice(b0, b1)
                pixels = surface.colors[tuple(selector)][..., direction_id, :]
                # Vanilla face UV orientation (u right, v down) differs per side.
                if direction == "west":
                    pixels = pixels[::-1, :]
                elif direction == "east":
                    pixels = pixels[::-1, ::-1]
                elif direction == "down":
                    pixels = pixels[:, ::-1].transpose(1, 0, 2)
                elif direction == "up":
                    pixels = pixels.transpose(1, 0, 2)
                elif direction == "north":
                    pixels = pixels[::-1, ::-1].transpose(1, 0, 2)
                elif direction == "south":
                    pixels = pixels[:, ::-1].transpose(1, 0, 2)
                rectangles.append(Rectangle(direction, np.round(start, 7).tolist(),
                                            np.round(end, 7).tolist(), pixels.copy()))
    return rectangles, raw_faces


def next_power_of_two(value: int) -> int:
    return 1 << max(0, int(value - 1).bit_length())


def pack_atlas(rectangles: list[Rectangle], padding: int = 2) -> Image.Image:
    """Shelf-pack face patches, extruding borders to avoid atlas edge bleeding."""
    if not rectangles:
        raise ValueError("Voxelized scene produced no visible faces")
    order = sorted(range(len(rectangles)),
                   key=lambda i: (-rectangles[i].pixels.shape[0], -rectangles[i].pixels.shape[1], i))
    total_area = sum((rect.pixels.shape[0] + padding * 2) * (rect.pixels.shape[1] + padding * 2) for rect in rectangles)
    width = next_power_of_two(max(max(rect.pixels.shape[1] + 2 * padding for rect in rectangles), int(math.sqrt(total_area))))
    placements, x, y, row_height = [], 0, 0, 0
    for index in order:
        patch_height, patch_width = rectangles[index].pixels.shape[:2]
        box_width, box_height = patch_width + padding * 2, patch_height + padding * 2
        if x + box_width > width:
            x, y, row_height = 0, y + row_height, 0
        placements.append((index, x + padding, y + padding))
        x += box_width
        row_height = max(row_height, box_height)
    height = next_power_of_two(y + row_height)
    atlas = np.zeros((height, width, 4), dtype=np.uint8)
    for index, px, py in placements:
        rectangle = rectangles[index]
        ph, pw = rectangle.pixels.shape[:2]
        atlas[py - padding:py + ph + padding, px - padding:px + pw + padding] = np.pad(rectangle.pixels, ((padding, padding), (padding, padding), (0, 0)), mode="edge")
        rectangle.uv = [round(px / width * 16, 8), round(py / height * 16, 8),
                        round((px + pw) / width * 16, 8), round((py + ph) / height * 16, 8)]
    return Image.fromarray(atlas, "RGBA")


def make_model(rectangles: list[Rectangle], namespace: str, name: str) -> dict[str, Any]:
    texture = f"{namespace}:item/{name}"
    model = {
        "credit": "Offline pixel_hardcore.py conversion from original triangle mesh",
        "ambientocclusion": False,
        "gui_light": "front",
        "textures": {"particle": texture, "0": texture},
        "elements": [{"from": rect.start, "to": rect.end,
                      "faces": {rect.direction: {"uv": rect.uv, "texture": "#0"}}}
                     for rect in rectangles],
        "display": default_display(),
    }
    return seamless_model(model)[0]


def convert(scene_path: Path, output: Path, *, name: str = "med_kit", namespace: str = "tacz_pubg",
            resolution: int = DEFAULT_RESOLUTION, max_elements: int = 1800, min_resolution: int | None = None,
            display_template: Path | None = None) -> dict[str, Any]:
    # Quality is fixed unless the caller explicitly opts into an adaptive range.
    if min_resolution is None:
        min_resolution = resolution
    if not re.fullmatch(r"[a-z0-9_.-]+", namespace) or not re.fullmatch(r"[a-z0-9_./-]+", name) or ".." in name:
        raise ValueError("Use valid lowercase Minecraft namespace and resource path")
    if not 8 <= min_resolution <= resolution <= 96:
        raise ValueError("Require 8 <= min-resolution <= resolution <= 96")
    if max_elements < 6:
        raise ValueError("max-elements must be at least 6")
    scene = read_scene(scene_path)
    attempts = []
    current_resolution = resolution
    while True:
        surface = voxelize(scene, current_resolution)
        rectangles, raw_faces = surface_rectangles(surface)
        attempts.append({"resolution": current_resolution, "elements": len(rectangles), "raw_visible_faces": raw_faces})
        if len(rectangles) <= max_elements or current_resolution == min_resolution:
            break
        estimate = math.floor(current_resolution * math.sqrt(max_elements / len(rectangles)) * 0.96)
        current_resolution = max(min_resolution, min(current_resolution - 1, estimate))
    atlas = pack_atlas(rectangles)
    model = make_model(rectangles, namespace, name)
    _, seam_report = seamless_model(model)
    if display_template is not None:
        template = json.loads(display_template.read_text(encoding="utf-8-sig"))
        if not isinstance(template.get("display"), dict):
            raise ValueError("Display template must be a model JSON containing a display object")
        model["display"] = template["display"]
    output.mkdir(parents=True, exist_ok=True)
    (output / "model.json").write_text(json.dumps(model, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    atlas.save(output / "texture.png", optimize=True)
    report = {
        "workflow": "pixel_hardcore",
        "schema": "agent-model-conversion-report/1",
        "source_scene": str(scene.source_path),
        "source_triangles": len(scene.positions),
        "source_materials": len(scene.materials),
        "source_uv_origin": scene.uv_origin,
        "requested_resolution": resolution,
        "minimum_resolution": min_resolution,
        "resolution_policy": "fixed" if min_resolution == resolution else "adaptive_explicit",
        "actual_resolution": current_resolution,
        "max_elements_target": max_elements,
        "target_met": len(model["seamless_quads"]) <= max_elements,
        "legacy_greedy_target_met": len(rectangles) <= max_elements,
        "attempts": attempts,
        "voxel_grid": list(surface.occupied.shape),
        "occupied_voxels": int(surface.occupied.sum()),
        "raw_visible_faces": raw_faces,
        "elements": len(rectangles),
        "rendered_quads": len(model["seamless_quads"]),
        "triangle_equivalent": len(model["seamless_quads"]) * 2,
        "geometry_repair": seam_report,
        "degenerate_triangles_skipped": surface.degenerate_triangles,
        "voxel_size_model_units": surface.pitch,
        "source_bounds": [scene.positions.min(axis=(0, 1)).tolist(), scene.positions.max(axis=(0, 1)).tolist()],
        "result_bounds": [surface.origin.tolist(), (surface.origin + np.asarray(surface.occupied.shape) * surface.pitch).tolist()],
        "texture_size": list(atlas.size),
        "texture_rgba_bytes": atlas.width * atlas.height * 4,
        "resource_texture": f"{namespace}:item/{name}",
        "display_template": str(display_template.resolve()) if display_template else None,
        "quality_notes": [
            "Voxel shell is sampled from the real source triangles; no hand-authored substitute geometry.",
            "Conservative triangle/box intersection retains thin source parts at voxel scale.",
            "No volume filling: source cavities remain open unless narrower than the chosen voxel size.",
            "Exterior-air reachability removes invisible faces in sealed voids; it does not fill or seal accessible cavities.",
            "Hidden faces are removed; coplanar exposed faces are greedily merged without merging away their sampled color pixels.",
            "Zero-thickness vanilla elements each contribute one outward quad, not six cube faces.",
            "A cached custom baked model retains every shared boundary vertex to close floating-point T-junction cracks; original elements remain as an exact geometry and display reference.",
            "Only affected greedy faces are triangulated and adjacent triangles are paired back into quads. Geometry-repair GPU overhead is reported separately; resolution and original face budgets are not silently changed.",
            "Source diffuse RGBA is bilinearly sampled into an atlas with extruded borders; PBR normal/roughness/metallic effects are not used by vanilla elements.",
            "This is deliberately lossy pixel-style geometry; silhouettes and small gaps are quantized at the reported voxel size.",
            ("Resolution is fixed; exceeding the face target is reported without silently lowering detail."
             if min_resolution == resolution else
             "Caller explicitly enabled adaptive resolution; target_met reports if its minimum still exceeds the face target."),
        ],
        "outputs": {"model": "model.json", "texture": "texture.png", "report": "report.json"},
    }
    # Convert NumPy scalar dimensions to standard JSON integers.
    report["voxel_grid"] = [int(value) for value in surface.occupied.shape]
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--scene", type=Path, help="Normalized agent-model-scene/1 JSON")
    inputs.add_argument("--source", type=Path, help="Static .c4d or exported .obj")
    parser.add_argument("--mesh", action="append", help="Select whole source mesh by exact name or zero-based index; repeat to group parts")
    parser.add_argument("--output", type=Path, required=True, help="Directory for model.json, texture.png and report.json")
    parser.add_argument("--name", default="med_kit", help="Minecraft texture resource path under item/")
    parser.add_argument("--namespace", default="tacz_pubg")
    parser.add_argument("--resolution", type=int, default=DEFAULT_RESOLUTION, help="Voxel count along the longest source dimension; fixed by default")
    parser.add_argument("--max-elements", type=int, default=1800, help="Adaptive target for rendered quads/elements")
    parser.add_argument("--min-resolution", type=int, help="Explicitly allow adaptive downsampling to this minimum; omitted keeps resolution fixed")
    parser.add_argument("--max-size", type=float, default=14, help="Longest normalized model dimension for --source")
    parser.add_argument("--rotate", nargs=3, type=float, default=(0, 0, 0), metavar=("X", "Y", "Z"))
    parser.add_argument("--display-template", type=Path, help="Existing model JSON whose display transforms should be retained")
    args = parser.parse_args()
    _, canonical = prepare_input(scene_path=args.scene, source=args.source, output=args.output,
                                 max_size=args.max_size, rotate=args.rotate, mesh_selectors=args.mesh)
    report = convert(canonical, args.output, name=args.name, namespace=args.namespace,
                     resolution=args.resolution, max_elements=args.max_elements,
                     min_resolution=args.min_resolution, display_template=args.display_template)
    print(json.dumps({key: report[key] for key in ("actual_resolution", "occupied_voxels", "elements", "triangle_equivalent", "texture_size", "target_met")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
