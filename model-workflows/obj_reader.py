"""Strict static OBJ/MTL fallback for C4D-exported polygon geometry."""
from __future__ import annotations

import shlex
from pathlib import Path
import numpy as np


def read_scene(path: Path) -> dict:
    path = Path(path).resolve()
    points, uvs, normals, groups, materials = [], [], [], {}, {}
    material_name = None
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        fields = raw.strip().split()
        if not fields or fields[0].startswith("#"):
            continue
        kind, args = fields[0], fields[1:]
        if kind == "v":
            points.append([float(x) for x in args[:3]])
        elif kind == "vt":
            uvs.append([float(args[0]), 1 - float(args[1])])
        elif kind == "vn":
            normals.append([float(x) for x in args[:3]])
        elif kind == "mtllib":
            for name in shlex.split(raw.strip()[7:], posix=False):
                mtl = path.parent / name.strip('"')
                current = None
                for line in mtl.read_text(encoding="utf-8-sig").splitlines():
                    parts = line.strip().split(maxsplit=1)
                    if len(parts) < 2:
                        continue
                    if parts[0] == "newmtl":
                        current = {"name": parts[1]}
                        materials[parts[1]] = current
                    elif parts[0] == "map_Kd" and current is not None:
                        if parts[1].startswith("-"):
                            raise ValueError("OBJ map_Kd options need explicit baking; plain texture path required")
                        current["base_color_texture"] = str((mtl.parent / parts[1].strip('"')).resolve())
        elif kind == "usemtl":
            material_name = " ".join(args)
        elif kind == "f":
            if len(args) not in (3, 4):
                raise ValueError("Triangulate OBJ n-gons in the authoring tool first")
            face = []
            for entry in args:
                ids = entry.split("/")
                if len(ids) < 2 or not ids[1]:
                    raise ValueError("Textured OBJ faces require UVs")
                def index(value, size):
                    number = int(value)
                    return number - 1 if number > 0 else size + number
                p = np.array(points[index(ids[0], len(points))], float)
                uv = uvs[index(ids[1], len(uvs))]
                n = normals[index(ids[2], len(normals))] if len(ids) > 2 and ids[2] else None
                face.append((p, uv, n))
            for tri in ((0, 1, 2), (0, 2, 3))[:len(face)-2]:
                a, b, c = (face[i][0] for i in tri)
                fn = np.cross(b-a, c-a)
                length = np.linalg.norm(fn)
                if length <= 1e-12:
                    continue
                fn /= length
                out = groups.setdefault(material_name, [])
                for i in tri:
                    p, uv, n = face[i]
                    n = np.asarray(fn if n is None else n, float)
                    n /= np.linalg.norm(n)
                    out.extend([*p, *uv, *n])
    result_mats, meshes = [], []
    for name, data in groups.items():
        if name not in materials or "base_color_texture" not in materials[name]:
            raise ValueError(f"OBJ material needs a base color bitmap: {name}")
        meshes.append({"name": name, "material": len(result_mats), "vertices": data})
        result_mats.append(materials[name])
    if not meshes:
        raise ValueError("OBJ contains no textured triangles")
    return {"schema": "agent-model-scene/1", "materials": result_mats, "meshes": meshes,
            "metadata": {"reader": "strict-static-obj", "uv_convention": "v=0 top of image"}}
