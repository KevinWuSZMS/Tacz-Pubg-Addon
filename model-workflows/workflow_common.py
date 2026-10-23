"""Shared offline asset utilities. Nothing in this folder is a Minecraft feature."""
from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image

SCHEMA = "agent-model-scene/1"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value, *, pretty=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, allow_nan=False,
                               indent=2 if pretty else None,
                               separators=None if pretty else (",", ":")) + "\n", encoding="utf-8")


def load_scene(path: Path) -> dict:
    scene = json.loads(path.read_text(encoding="utf-8-sig"))
    if scene.get("schema") != SCHEMA:
        raise ValueError(f"Expected {SCHEMA}: {path}")
    if not scene.get("materials") or not scene.get("meshes"):
        raise ValueError("Scene needs materials and meshes")
    for material in scene["materials"]:
        for key in ("base_color_texture", "normal_texture", "roughness_metallic_texture"):
            if material.get(key):
                texture = Path(material[key])
                texture = texture if texture.is_absolute() else path.parent / texture
                if not texture.is_file():
                    raise FileNotFoundError(texture)
                material[key] = str(texture.resolve())
    for mesh in scene["meshes"]:
        vertices = np.asarray(mesh["vertices"], dtype=np.float64)
        if not vertices.size or vertices.size % 24 or not np.isfinite(vertices).all():
            raise ValueError(f"Invalid x,y,z,u,v,nx,ny,nz triangles: {mesh.get('name')}")
        if not 0 <= int(mesh["material"]) < len(scene["materials"]):
            raise ValueError("Invalid material index")
    return scene


def select_meshes(scene: dict, selectors: list[str | int]) -> dict:
    """Select whole source objects, preserving all islands such as screws.

    Object selection must precede per-item normalization. A source object's
    disconnected pieces are not automatically treated as separate inventory items.
    """
    if not selectors:
        raise ValueError("Select at least one source mesh")
    meshes = scene["meshes"]
    selected = []
    for selector in selectors:
        if isinstance(selector, int) or (isinstance(selector, str) and selector.isdecimal()):
            index = int(selector)
            if not 0 <= index < len(meshes):
                raise ValueError(f"Unknown mesh index: {selector}")
        else:
            matches = [i for i, mesh in enumerate(meshes) if mesh.get("name") == selector]
            if not matches:
                raise ValueError(f"Unknown mesh name: {selector}")
            if len(matches) != 1:
                raise ValueError(f"Ambiguous mesh name: {selector}; use a zero-based index")
            index = matches[0]
        if index in selected:
            raise ValueError(f"Duplicate mesh selection: {selector}")
        selected.append(index)
    result = json.loads(json.dumps(scene))
    material_indices = sorted({int(meshes[i]["material"]) for i in selected})
    remap = {original: new for new, original in enumerate(material_indices)}
    result["meshes"] = [result["meshes"][i] for i in selected]
    result["materials"] = [result["materials"][i] for i in material_indices]
    for mesh in result["meshes"]:
        mesh["material"] = remap[int(mesh["material"])]
    result.setdefault("metadata", {})["selection"] = {
        "source_mesh_count": len(meshes), "mesh_indices": selected,
        "mesh_names": [meshes[i].get("name", "") for i in selected],
        "material_indices": material_indices,
    }
    if isinstance(result["metadata"].get("objects"), list):
        objects = result["metadata"]["objects"]
        source_objects = {meshes[i].get("source_object_index") for i in selected}
        if None not in source_objects and all("source_object_index" in o for o in objects):
            result["metadata"]["objects"] = [o for o in objects if o["source_object_index"] in source_objects]
        elif len(objects) == len(meshes) and all(o.get("name") == m.get("name") for o,m in zip(objects,meshes)):
            result["metadata"]["objects"] = [objects[i] for i in selected]
        else:
            names = set(result["metadata"]["selection"]["mesh_names"])
            result["metadata"]["objects"] = [o for o in objects if o.get("name") in names]
    return result


def read_source(source: Path, texture_roots=(), texture_overrides=None) -> dict:
    source = Path(source).resolve()
    if source.suffix.lower() == ".c4d":
        from c4d_reader import read_scene
        scene = read_scene(source, texture_roots=texture_roots, texture_overrides=texture_overrides)
    elif source.suffix.lower() == ".obj":
        from obj_reader import read_scene
        if texture_roots or texture_overrides:
            raise ValueError("Explicit texture search roots currently apply to .c4d sources")
        scene = read_scene(source)
    else:
        raise ValueError("Supported source files: .c4d, .obj; or --scene canonical JSON")
    scene.setdefault("metadata", {}).update({"source": str(source), "source_sha256": digest(source)})
    return scene


def rotate_matrix(degrees):
    x, y, z = np.radians(degrees)
    rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    return rz @ ry @ rx


def normalize_scene(scene: dict, *, max_size=14.0, rotate=(0, 0, 0)) -> dict:
    """Uniform scale, Y-up, centered XZ and ground at y=1 in 0..16 item units."""
    if not 0 < max_size <= 16:
        raise ValueError("max-size must be in (0,16]")
    result = json.loads(json.dumps(scene))
    r = rotate_matrix(rotate)
    arrays = [np.array(m["vertices"], dtype=float).reshape(-1, 8) for m in result["meshes"]]
    handedness = result.get("metadata", {}).get("handedness", "right")
    if handedness not in ("left", "right"):
        raise ValueError("Source handedness must be explicitly left or right")
    if handedness == "left":
        # C4D is left handed; Minecraft item/camera geometry is right handed.
        # Reflect points AND normals, then reverse winding with associated UVs.
        for index, a in enumerate(arrays):
            a[:, 2] *= -1
            a[:, 7] *= -1
            arrays[index] = a.reshape(-1, 3, 8)[:, [0, 2, 1], :].reshape(-1, 8).copy()
    for a in arrays:
        a[:, :3] = a[:, :3] @ r.T
        a[:, 5:8] = a[:, 5:8] @ r.T
    points = np.concatenate([a[:, :3] for a in arrays])
    low, high = points.min(0), points.max(0)
    span = float(np.max(high - low))
    if not math.isfinite(span) or span < 1e-12:
        raise ValueError("Source geometry has no measurable size")
    scale = max_size / span
    offset = np.array([8, 1, 8]) - np.array([(low[0] + high[0]) / 2, low[1], (low[2] + high[2]) / 2]) * scale
    for mesh, a in zip(result["meshes"], arrays):
        a[:, :3] = a[:, :3] * scale + offset
        lengths = np.linalg.norm(a[:, 5:8], axis=1)
        if np.any(lengths < 1e-12):
            raise ValueError("Source has zero normals")
        a[:, 5:8] /= lengths[:, None]
        mesh["vertices"] = a.ravel().tolist()
    result.setdefault("metadata", {})["handedness"] = "right"
    result["metadata"]["normalization"] = {
        "source_handedness": handedness,
        "reflected_axis": "z" if handedness == "left" else None,
        "rotation_degrees_xyz": list(rotate), "uniform_scale": scale,
        "offset": offset.tolist(), "max_dimension": max_size,
        "coordinate_system": "Y up, +Z front, XZ centered on 8; item units /16 in Minecraft",
        "uv_convention": "v=0 is top of image",
    }
    return result


def prepare_input(*, scene_path=None, source=None, output: Path, max_size=14.0,
                  rotate=(0, 0, 0), mesh_selectors=None) -> tuple[dict, Path]:
    if (scene_path is None) == (source is None):
        raise ValueError("Supply exactly one of --scene or --source")
    if scene_path:
        scene = load_scene(Path(scene_path))
        if mesh_selectors is None:
            return scene, Path(scene_path).resolve()
    else:
        scene = read_source(Path(source))
    if mesh_selectors is not None:
        scene = select_meshes(scene, mesh_selectors)
    scene = normalize_scene(scene, max_size=max_size, rotate=rotate)
    canonical = output / "source" / "scene.json"
    write_json(canonical, scene)
    return load_scene(canonical), canonical


def source_stats(scene: dict):
    arrays = [np.asarray(m["vertices"]).reshape(-1, 8) for m in scene["meshes"]]
    p = np.concatenate([a[:, :3] for a in arrays])
    return {"triangles": sum(len(a) // 3 for a in arrays),
            "bounds": [p.min(0).tolist(), p.max(0).tolist()],
            "meshes": len(arrays), "materials": len(scene["materials"])}


def png_lossless(source: Path, destination: Path):
    """Preserve decoded RGB(A) pixels; never resize or bake lighting implicitly."""
    with Image.open(source) as original:
        pixels = original.convert("RGBA" if "A" in original.getbands() else "RGB")
        destination.parent.mkdir(parents=True, exist_ok=True)
        pixels.save(destination, optimize=True)
        with Image.open(destination) as exported:
            if exported.mode != pixels.mode or exported.size != pixels.size or exported.tobytes() != pixels.tobytes():
                raise RuntimeError("PNG pixel roundtrip mismatch")
        return {"size": list(pixels.size), "mode": pixels.mode, "pixels_preserved": True,
                "source_sha256": digest(source), "png_sha256": digest(destination)}


def default_display():
    return {
        "gui": {"rotation": [25, 225, 0], "translation": [0, 0, 0], "scale": [.85] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [.5] * 3},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1] * 3},
        "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [.6] * 3},
        "firstperson_righthand": {"rotation": [0, -35, 0], "translation": [0, 1, 0], "scale": [.7] * 3},
        "firstperson_lefthand": {"rotation": [0, 35, 0], "translation": [0, 1, 0], "scale": [.7] * 3},
    }
