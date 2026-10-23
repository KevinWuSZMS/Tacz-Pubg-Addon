"""Conforming axis-aligned surface partitioning, without changing sampled UVs.

Greedy voxel quads may meet another quad halfway along an edge. Those T joints
can open subpixel cracks after float transforms; the entity-outline postprocess
then highlights the cracks. Retain every boundary point, triangulate affected
faces and pair adjacent triangles back to quads without adding any positions.
"""
from __future__ import annotations

from bisect import bisect_left, bisect_right
from copy import deepcopy
from itertools import product

from render_preview import FACE_CORNERS


PRECISION = 7
PREDICATE_EPSILON = 1e-5  # Well below one density-96 voxel, above JSON/float grid rounding.
NORMAL_AXIS = {"west": 0, "east": 0, "down": 1, "up": 1, "north": 2, "south": 2}
# Face UV u/v axes and signs, matching Minecraft FaceInfo / BlockFaceUV.
UV_AXES = {"west": ((2, 1), (1, -1)), "east": ((2, -1), (1, -1)),
           "down": ((0, 1), (2, -1)), "up": ((0, 1), (2, 1)),
           "north": ((0, -1), (1, -1)), "south": ((0, 1), (1, -1))}


def point_key(point):
    return tuple(round(float(x), PRECISION) for x in point)


def corners(element):
    normal = NORMAL_AXIS[next(iter(element["faces"]))]
    axes = [i for i in range(3) if i != normal]
    for a, b in product((0, 1), repeat=2):
        p = list(element["from"])
        p[axes[0]] = element["to" if a else "from"][axes[0]]
        p[axes[1]] = element["to" if b else "from"][axes[1]]
        yield point_key(p)


def vertex_lines(elements):
    lines = {}
    for element in elements:
        for p in corners(element):
            for axis in range(3):
                key = (axis, *(p[i] for i in range(3) if i != axis))
                lines.setdefault(key, set()).add(p[axis])
    return {key: sorted(values) for key, values in lines.items()}


def face_cuts(element, lines):
    low, high = point_key(element["from"]), point_key(element["to"])
    normal = NORMAL_AXIS[next(iter(element["faces"]))]
    cuts = {}
    for axis in (i for i in range(3) if i != normal):
        other = next(i for i in range(3) if i != normal and i != axis)
        values = {}
        for end in (low[other], high[other]):
            p = list(low)
            p[other] = end
            key = (axis, *(p[i] for i in range(3) if i != axis))
            points = lines.get(key, ())
            for value in points[bisect_right(points, low[axis]):bisect_left(points, high[axis])]:
                values[value] = values.get(value, 0) + 1
        if values:
            cuts[axis] = values
    return cuts


def t_junction_count(elements):
    lines = vertex_lines(elements)
    return sum(sum(len(v) for v in face_cuts(e, lines).values()) for e in elements)


def boundary_polygon(element, lines):
    direction = next(iter(element["faces"]))
    low, high = point_key(element["from"]), point_key(element["to"])
    base = [tuple(high[i] if select[i] else low[i] for i in range(3))
            for select in FACE_CORNERS[direction]]
    result = []
    for a, b in zip(base, base[1:] + base[:1]):
        axis = next(i for i in range(3) if a[i] != b[i])
        key = (axis, *(a[i] for i in range(3) if i != axis))
        points = lines[key]
        middle = points[bisect_right(points, min(a[axis], b[axis])):
                        bisect_left(points, max(a[axis], b[axis]))]
        if a[axis] > b[axis]:
            middle = reversed(middle)
        result.append(a)
        for value in middle:
            p = list(a)
            p[axis] = value
            result.append(tuple(p))
    return result


def _cross(a, b, c):
    return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])


def triangulate_polygon(points, normal_axis):
    """Ear clip a weakly convex boundary, retaining every collinear edge point.

    Reject ears whose diagonal passes through any remaining boundary vertex;
    otherwise a superficially valid triangle fan can recreate the same T joint.
    """
    axes = [i for i in range(3) if i != normal_axis]
    xy = [(p[axes[0]], p[axes[1]]) for p in points]
    signed_area = sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(xy, xy[1:] + xy[:1]))
    orientation = 1 if signed_area > 0 else -1
    remaining = list(range(len(points)))
    result = []
    while len(remaining) > 3:
        found = False
        for at, center in enumerate(remaining):
            before, after = remaining[at - 1], remaining[(at + 1) % len(remaining)]
            a, b, c = xy[before], xy[center], xy[after]
            if orientation * _cross(a, b, c) <= PREDICATE_EPSILON:
                continue
            if any(orientation * _cross(a, b, xy[i]) >= -PREDICATE_EPSILON
                   and orientation * _cross(b, c, xy[i]) >= -PREDICATE_EPSILON
                   and orientation * _cross(c, a, xy[i]) >= -PREDICATE_EPSILON
                   for i in remaining if i not in (before, center, after)):
                continue
            result.append((before, center, after))
            remaining.pop(at)
            found = True
            break
        if not found:
            raise ValueError("Could not triangulate a pixel boundary without dropping an edge vertex")
    if orientation * _cross(*(xy[i] for i in remaining)) <= PREDICATE_EPSILON:
        raise ValueError("Triangulation ended in a zero-area face")
    result.append(tuple(remaining))
    return result


def pair_triangles(triangles):
    """Maximum matching on the triangulation's dual tree, packing two per quad."""
    edges = {}
    for i, triangle in enumerate(triangles):
        for a, b in zip(triangle, triangle[1:] + triangle[:1]):
            edges.setdefault(tuple(sorted((a, b))), []).append(i)
    neighbors = {i: set() for i in range(len(triangles))}
    shared = {}
    for edge, adjacent in edges.items():
        if len(adjacent) == 2:
            a, b = adjacent
            neighbors[a].add(b)
            neighbors[b].add(a)
            shared[tuple(sorted((a, b)))] = edge
    active = set(neighbors)
    result = []
    while active:
        # Matching a leaf to its only neighbor is safe for maximum matching in
        # a tree. Removing both produces a forest, to which the same rule applies.
        a = next(i for i in sorted(active) if len(neighbors[i] & active) <= 1)
        others = neighbors[a] & active
        if not others:
            t = triangles[a]
            result.append((*t, t[-1]))
            active.remove(a)
            continue
        b = next(iter(others))
        common = shared[tuple(sorted((a, b)))]
        oriented = {}
        for index in (a, b):
            t = triangles[index]
            for x, y in zip(t, t[1:] + t[:1]):
                if tuple(sorted((x, y))) != common:
                    oriented[x] = y
        first = common[0]
        quad = [first]
        for _ in range(3):
            quad.append(oriented[quad[-1]])
        if quad[2] != common[1] or oriented[quad[-1]] != first:
            raise ValueError("Triangle pairing produced an invalid quad diagonal")
        result.append(tuple(quad))
        active.difference_update((a, b))
    return result


def point_uv(element, point):
    direction = next(iter(element["faces"]))
    face = element["faces"][direction]
    if face.get("rotation", 0):
        raise ValueError("Converted pixel surfaces must have unrotated face UVs")
    result = []
    for uv_axis, (axis, sign) in enumerate(UV_AXES[direction]):
        fraction = ((point[axis] - element["from"][axis]) /
                    (element["to"][axis] - element["from"][axis]))
        if sign < 0:
            fraction = 1 - fraction
        a, b = face["uv"][uv_axis], face["uv"][uv_axis + 2]
        result.append(a + (b - a) * fraction)
    return result


def seamless_model(model):
    """Keep legacy elements and add a compact conforming baked-quad payload."""
    elements = model["elements"]
    # Also perform the shared input restrictions used by rectangular repair.
    for element in elements:
        if len(element.get("faces", {})) != 1 or element.get("rotation"):
            raise ValueError("Only converted one-face unrotated pixel surfaces can be repaired")
        direction = next(iter(element["faces"]))
        axis = NORMAL_AXIS[direction]
        if element["from"][axis] != element["to"][axis]:
            raise ValueError("Expected a zero-thickness outward surface")
    lines = vertex_lines(elements)
    quads, affected, valid_triangles, added_edge_vertices, degenerate = [], 0, 0, 0, 0
    for element in elements:
        direction = next(iter(element["faces"]))
        points = boundary_polygon(element, lines)
        if len(points) == 4:
            indices = [(0, 1, 2, 3)]
            valid_triangles += 2
        else:
            affected += 1
            added_edge_vertices += len(points) - 4
            triangles = triangulate_polygon(points, NORMAL_AXIS[direction])
            valid_triangles += len(triangles)
            indices = pair_triangles(triangles)
        for quad in indices:
            vertices = []
            if quad[2] == quad[3]:
                degenerate += 1
            for i in quad:
                vertices.extend((*points[i], *point_uv(element, points[i])))
            quads.append({"face": direction, "vertices": vertices})
    result = deepcopy(model)
    result["loader"] = "tacz_pubg:seamless_pixel"
    result["render_type"] = "minecraft:cutout"
    result["seamless_quads"] = quads
    report = {"legacy_quads": len(elements), "affected_faces": affected,
              "inserted_boundary_vertices": added_edge_vertices,
              "gpu_quads": len(quads), "gpu_vertices": len(quads) * 4,
              "gpu_triangles": len(quads) * 2, "valid_triangles": valid_triangles,
              "degenerate_triangles": degenerate,
              "quad_overhead_percent": round((len(quads) / len(elements) - 1) * 100, 3),
              "remaining_t_junctions": 0}
    return result, report


def audit_seamless(model):
    """Validate real rendered edges, source area, winding, coverage and affine UVs."""
    import numpy as np

    normals = {"west": (-1, 0, 0), "east": (1, 0, 0), "down": (0, -1, 0),
               "up": (0, 1, 0), "north": (0, 0, -1), "south": (0, 0, 1)}
    sources, source_areas, output_areas = {}, {}, {}
    original_vertices = set()
    for element in model["elements"]:
        direction = next(iter(element["faces"]))
        axis = NORMAL_AXIS[direction]
        key = direction, element["from"][axis]
        sources.setdefault(key, []).append(element)
        tangential = [i for i in range(3) if i != axis]
        area = (element["to"][tangential[0]] - element["from"][tangential[0]]) * (
            element["to"][tangential[1]] - element["from"][tangential[1]])
        source_areas[key] = source_areas.get(key, 0) + area
        original_vertices.update(corners(element))
    # The output only reuses source vertices; no new coordinates need propagation.
    vertex_array = np.asarray(sorted(original_vertices))
    planes = {}
    for axis in range(3):
        for value in np.unique(vertex_array[:, axis]):
            planes[axis, value] = vertex_array[vertex_array[:, axis] == value]
    edges, valid_triangles, degenerate = set(), 0, 0
    for record in model["seamless_quads"]:
        direction = record["face"]
        values = np.asarray(record["vertices"], dtype=float).reshape(4, 5)
        if not np.isfinite(values).all():
            raise ValueError("Nonfinite seamless vertex")
        axis = NORMAL_AXIS[direction]
        if not np.all(values[:, axis] == values[0, axis]):
            raise ValueError("Seamless face left its original plane")
        key = direction, values[0, axis]
        for p in values[:, :3]:
            if tuple(p) not in original_vertices:
                raise ValueError("Seam repair introduced a position absent from original geometry")
        for indices in ((0, 1, 2), (0, 2, 3)):
            triangle = values[list(indices)]
            a, b, c = triangle[:, :3]
            cross = np.cross(b - a, c - a)
            area2 = float(cross @ np.asarray(normals[direction]))
            if abs(area2) < 1e-12:
                degenerate += 1
                continue
            if area2 < PREDICATE_EPSILON:
                raise ValueError("Flipped or numerically unstable seamless triangle")
            valid_triangles += 1
            output_areas[key] = output_areas.get(key, 0) + area2 / 2
            # Every triangle must fit within one source rectangle and preserve
            # its original affine UV map at all three corners.
            matches = [e for e in sources.get(key, ()) if all(
                all(e["from"][i] - 1e-8 <= p[i] <= e["to"][i] + 1e-8 for i in range(3))
                and np.allclose(p[3:5], point_uv(e, p[:3]), atol=1e-8, rtol=0)
                for p in triangle)]
            if not matches:
                raise ValueError("Seam repair changed geometry coverage or source affine UV mapping")
            for p, q in ((a, b), (b, c), (c, a)):
                edge = tuple(sorted((tuple(p), tuple(q))))
                edges.add((axis, *edge))
    for key, area in source_areas.items():
        if abs(area - output_areas.get(key, 0)) > max(1e-7, area * 1e-10):
            raise ValueError("Seam repair changed source surface area")
    for axis, a, b in edges:
        candidates = planes[axis, a[axis]]
        a, b = np.asarray(a), np.asarray(b)
        ab = b - a
        length2 = float(ab @ ab)
        t = (candidates - a) @ ab / length2
        interior = candidates[(t > 1e-7) & (t < 1 - 1e-7)]
        if len(interior) and np.any(np.linalg.norm(np.cross(interior - a, ab), axis=1) < PREDICATE_EPSILON):
            raise ValueError(f"A rendered triangle edge still has a T junction: {a} -> {b}")
    return {"audited_valid_triangles": valid_triangles, "degenerate_triangles": degenerate,
            "audited_unique_edges": len(edges), "remaining_rendered_edge_t_junctions": 0,
            "source_surface_area": sum(source_areas.values()),
            "repaired_surface_area": sum(output_areas.values()),
            "all_positions_reused": True, "all_affine_uvs_preserved": True,
            "source_coverage_and_winding_preserved": True}
