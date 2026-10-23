"""Derive thin pixel suspension lines from the existing authored C4D topology.

Canopy pixels are selected on the original density36 voxel grid.
Unmixed quads are kept exactly; mixed greedy quads are clipped to their original
canopy footprint with the original affine UV mapping. The obsolete bottom
pixel harness block is deliberately omitted. No canopy surface
is generated here, and neither the density nor the pixel atlas changes.
"""
from __future__ import annotations

import hashlib
import heapq
from collections import Counter

import numpy as np

from pixel_hardcore import greedy_rectangles, triangle_box_overlap


ROPE_WIDTH_METRES = .010
LOD_DISTANCES = (32, 64, 128)


class DisjointSet:
    def __init__(self, count):
        self.parent = np.arange(count)

    def find(self, value):
        while self.parent[value] != value:
            self.parent[value] = self.parent[self.parent[value]]
            value = self.parent[value]
        return int(value)

    def union(self, first, second):
        self.parent[self.find(second)] = self.find(first)


def edges(indices):
    for a, b, c in indices:
        yield int(a), int(b)
        yield int(b), int(c)
        yield int(c), int(a)


def source_regions(triangles):
    """Find disconnected authored parts without relying on material names.

The source is one skinned mesh: its large upper component and two short end
bars form the canopy; its low small components form the harness. Suspension
    components extend above Y=5 and use the authored rope texture strip V<0.12.
    The cloth starts at V=0.1214, including the short bars along its end edges.
    These boundaries are audited against this particular item_042_01, not a generic
assumption about all future parachute assets.
"""
    points, indices = np.unique(triangles[..., :3].reshape(-1, 3).round(4),
                                axis=0, return_inverse=True)
    indices = indices.reshape(-1, 3)
    connected = DisjointSet(len(points))
    for first, second in edges(indices):
        connected.union(first, second)
    labels = np.array([connected.find(i) for i in indices[:, 0]])
    rope = np.zeros(len(triangles), dtype=bool)
    canopy = np.zeros(len(triangles), dtype=bool)
    components = []
    for label in sorted(set(labels)):
        selected = labels == label
        part = triangles[selected, :, :3]
        low, high = part.min((0, 1)), part.max((0, 1))
        is_rope = high[1] > 5 and triangles[selected, :, 4].max() < .12
        rope |= selected if is_rope else False
        is_canopy = low[1] > 7.9 and not is_rope
        canopy |= selected if is_canopy else False
        components.append({"triangles": int(selected.sum()), "rope": bool(is_rope),
                           "canopy": bool(is_canopy),
                           "bounds": [low.tolist(), high.tolist()]})
    if int(rope.sum()) != 3752:
        raise ValueError(f"Authored rope topology changed ({int(rope.sum())}); review source-region classification")
    if int(canopy.sum()) != 5992 or np.any(canopy & rope):
        raise ValueError(f"Authored canopy topology changed ({int(canopy.sum())}); review source-region classification")
    return points, indices, rope, canopy, components


def static_pixel_faces(triangles, canopy, quads):
    """Retain each original canopy surface patch at its original UVs.

This uses the exact bounds, origin, pitch and triangle/box test of pixel_hardcore.
    A mixed greedy face is clipped on existing voxel boundaries. This does not
    generate new exposed surfaces or close holes: the result is a strict subset
    of the original pixel faces, with equivalent affine UVs at every point.
"""
    positions = triangles[..., :3]
    low, high = positions.min((0, 1)), positions.max((0, 1))
    pitch = float((high - low).max() / 36)
    shape = np.maximum(1, np.ceil((high - low) / pitch - 1e-8).astype(int))
    origin = low - (shape * pitch - (high - low)) / 2
    occupied = np.zeros(tuple(shape), dtype=bool)
    for triangle in positions[canopy]:
        start = np.clip(np.floor((triangle.min(0) - origin) / pitch).astype(int), 0, shape - 1)
        stop = np.clip(np.floor((triangle.max(0) - origin) / pitch).astype(int), 0, shape - 1)
        cells = np.stack(np.meshgrid(*(np.arange(start[i], stop[i] + 1)
                                     for i in range(3)), indexing="ij"), axis=-1).reshape(-1, 3)
        centers = origin + (cells + .5) * pitch
        cells = cells[triangle_box_overlap(centers, triangle, pitch * (.5 + 1e-7))]
        if len(cells):
            occupied[tuple(cells.T)] = True
    body, retained, mixed, fragment_sources = [], [], [], []
    preserved_cells, max_uv_error, max_plane_error = 0, 0., 0.
    for face_index, quad in enumerate(quads):
        # Outward normal points away from the voxel that owns this exposed face.
        normal = quad[0, 5:8]
        axis = int(np.abs(normal).argmax())
        plane = quad[0, axis]
        cell_plane = int(round((plane - origin[axis]) / pitch)) - (normal[axis] > 0)
        low_index = np.rint((quad[:, :3].min(0) - origin) / pitch).astype(int)
        high_index = np.rint((quad[:, :3].max(0) - origin) / pitch).astype(int)
        low_index[axis], high_index[axis] = cell_plane, cell_plane + 1
        low_index = np.maximum(0, low_index)
        high_index = np.minimum(shape, high_index)
        patch = occupied[tuple(slice(int(a), int(b)) for a, b in zip(low_index, high_index))]
        mask = np.squeeze(patch, axis=axis)
        if not mask.any():
            continue
        preserved_cells += int(mask.sum())
        if mask.all():
            body.append(quad)
            retained.append(face_index)
            fragment_sources.append(face_index)
            continue
        mixed.append(face_index)
        remaining_axes = [i for i in range(3) if i != axis]
        original_low, original_high = quad[:, :3].min(0), quad[:, :3].max(0)
        coverage = np.zeros_like(mask, dtype=int)
        for first, end_first, second, end_second in greedy_rectangles(mask):
            coverage[first:end_first, second:end_second] += 1
            a, b = remaining_axes
            fragment_low, fragment_high = original_low.copy(), original_high.copy()
            fragment_low[a] = origin[a] + (low_index[a] + first) * pitch
            fragment_high[a] = origin[a] + (low_index[a] + end_first) * pitch
            fragment_low[b] = origin[b] + (low_index[b] + second) * pitch
            fragment_high[b] = origin[b] + (low_index[b] + end_second) * pitch
            # Preserve exact original outer bounds where the fragment reaches them.
            if first == 0:
                fragment_low[a] = original_low[a]
            if end_first == mask.shape[0]:
                fragment_high[a] = original_high[a]
            if second == 0:
                fragment_low[b] = original_low[b]
            if end_second == mask.shape[1]:
                fragment_high[b] = original_high[b]
            fragment = quad.copy()
            fragment[:, :3] = np.where(np.isclose(quad[:, :3], original_low, atol=1e-8),
                                       fragment_low, fragment_high)
            edge01 = quad[1, :3] - quad[0, :3]
            edge03 = quad[3, :3] - quad[0, :3]
            delta = fragment[:, :3] - quad[0, :3]
            s = delta @ edge01 / (edge01 @ edge01)
            t = delta @ edge03 / (edge03 @ edge03)
            fragment[:, 3:5] = (quad[0, 3:5] + s[:, None] * (quad[1, 3:5] - quad[0, 3:5])
                                + t[:, None] * (quad[3, 3:5] - quad[0, 3:5]))
            projected = quad[0, :3] + s[:, None] * edge01 + t[:, None] * edge03
            max_plane_error = max(max_plane_error, float(np.abs(projected - fragment[:, :3]).max()))
            # Independent bilinear evaluation includes every fragment corner and
            # its centre; the affine map implies equivalence over the full patch.
            probe_s, probe_t = np.append(s, s.mean()), np.append(t, t.mean())
            expected_uv = ((1 - probe_s)[:, None] * (1 - probe_t)[:, None] * quad[0, 3:5]
                           + probe_s[:, None] * (1 - probe_t)[:, None] * quad[1, 3:5]
                           + probe_s[:, None] * probe_t[:, None] * quad[2, 3:5]
                           + (1 - probe_s)[:, None] * probe_t[:, None] * quad[3, 3:5])
            actual_uv = np.concatenate((fragment[:, 3:5], fragment[:, 3:5].mean(0, keepdims=True)))
            max_uv_error = max(max_uv_error, float(np.abs(expected_uv - actual_uv).max()))
            body.append(fragment)
            fragment_sources.append(face_index)
        if not np.array_equal(coverage, mask.astype(int)):
            raise ValueError("Mixed-face clipping changed the original canopy footprint")
    if max_uv_error > 1e-12 or max_plane_error > 1e-12:
        raise ValueError("Mixed-face clipping changed canopy positions or affine UV mapping")
    if not 500 < len(body) < len(quads) - 100:
        raise ValueError("Unexpected canopy pixel partition")
    report = {"unchanged_original_quads": len(retained), "clipped_original_quads": len(mixed),
              "canopy_quads": len(body), "canopy_face_cells": preserved_cells,
              "max_surface_projection_error_item_units": max_plane_error,
              "max_original_affine_uv_error": max_uv_error,
              "unchanged_face_indices": retained, "clipped_face_indices": mixed,
              "fragment_source_face_indices": fragment_sources,
              "footprint_exactly_preserved": True, "source_density_unchanged": True}
    return np.asarray(body), np.array(retained + mixed), pitch, report


def rope_skeleton(points, indices, rope):
    """Collapse each authored thin tube cross-section, retaining its centre path.

Short topology edges join vertices within the same rope ring or knot. Long
edges connect these rings. Coincident endpoints from separate authored parts
are welded within 0.04 item units (1.7 cm), preserving the original branches.
"""
    rings = DisjointSet(len(points))
    long_edges = []
    for a, b in edges(indices[rope]):
        if np.linalg.norm(points[a] - points[b]) <= .1:
            rings.union(a, b)
        else:
            long_edges.append((a, b))
    used = sorted(set(indices[rope].reshape(-1)))
    groups = {}
    for i in used:
        groups.setdefault(rings.find(i), []).append(points[i])
    labels = sorted(groups)
    centroids = np.array([np.mean(groups[label], axis=0) for label in labels])
    remap = {label: i for i, label in enumerate(labels)}
    links = {tuple(sorted((remap[rings.find(a)], remap[rings.find(b)])))
             for a, b in long_edges if rings.find(a) != rings.find(b)}
    coincident = DisjointSet(len(centroids))
    for a in range(len(centroids)):
        for b in range(a):
            if np.linalg.norm(centroids[a] - centroids[b]) < .04:
                coincident.union(a, b)
    groups = {}
    for i, center in enumerate(centroids):
        groups.setdefault(coincident.find(i), []).append(center)
    labels = sorted(groups)
    remap = {label: i for i, label in enumerate(labels)}
    centers = np.array([np.mean(groups[label], axis=0) for label in labels])
    links = sorted({tuple(sorted((remap[coincident.find(a)], remap[coincident.find(b)])))
                    for a, b in links if coincident.find(a) != coincident.find(b)})
    if not 45 <= len(links) <= 65:
        raise ValueError("Unexpected authored rope segment count")
    return centers, links


def rope_levels(centers, links):
    """Select full connected harness-to-canopy paths for balanced rope LODs."""
    adjacency = [[] for _ in centers]
    for i, (a, b) in enumerate(links):
        length = float(np.linalg.norm(centers[a] - centers[b]))
        adjacency[a].append((b, i, length))
        adjacency[b].append((a, i, length))
    roots = [i for i, point in enumerate(centers) if point[1] < 2.6 and adjacency[i]]
    distance = {i: 0. for i in roots}
    previous = {}
    queue = [(0., i) for i in roots]
    heapq.heapify(queue)
    while queue:
        current, a = heapq.heappop(queue)
        if current != distance[a]:
            continue
        for b, edge, length in adjacency[a]:
            value = current + length
            if value < distance.get(b, float("inf")):
                distance[b] = value
                previous[b] = (a, edge)
                heapq.heappush(queue, (value, b))
    leaves = [i for i, point in enumerate(centers)
              if len(adjacency[i]) == 1 and point[1] > 7.9]
    if len(leaves) < 20 or any(i not in distance for i in leaves):
        raise ValueError("A source canopy attachment has no continuous harness path")

    def paths(selected):
        result = set()
        for leaf in selected:
            while leaf in previous:
                leaf, edge = previous[leaf]
                result.add(edge)
        return sorted(result)

    # Four source quadrants: front/back in X, left/right in Z. At medium range
    # retain alternate attachment paths in each, including both outside edges.
    # At long range retain the outermost path in each quadrant.
    quadrants = {}
    for leaf in leaves:
        point = centers[leaf]
        quadrants.setdefault((point[0] >= 8, point[2] >= 8), []).append(leaf)
    medium, far = [], []
    for key in sorted(quadrants):
        ordered = sorted(quadrants[key], key=lambda i: abs(centers[i, 2] - 8))
        medium.extend(ordered[::2])
        if ordered[-1] not in medium:
            medium.append(ordered[-1])
        far.append(ordered[-1])
    levels = [list(range(len(links))), paths(medium), paths(far)]
    if not len(levels[0]) > len(levels[1]) > len(levels[2]) > 0:
        raise ValueError("Rope LODs must strictly decrease in cost")
    return levels, [len(leaves), len(medium), len(far)]


def rope_uv(quads, retained, texture):
    """Reuse a frequent dark rope texel in the unchanged original pixel atlas."""
    removed = quads[np.setdiff1d(np.arange(len(quads)), retained)]
    height, width = texture.shape[:2]
    uv = removed[..., 3:5].mean(1)
    xx, yy = (uv[:, 0] * width).astype(int), (uv[:, 1] * height).astype(int)
    colors = texture[yy, xx]
    visible = colors[:, 3] >= 128
    colors, uv = colors[visible], uv[visible]
    color = Counter(tuple(color) for color in colors).most_common(1)[0][0]
    chosen = uv[np.all(colors == color, axis=1)][0]
    return chosen, list(map(int, color))


def segment_quads(start, end, uv, width=ROPE_WIDTH_METRES):
    """Four unlit square-section sides: fixed geometry, no camera-facing billboards."""
    direction = end - start
    direction /= np.linalg.norm(direction)
    reference = np.eye(3)[np.abs(direction).argmin()]
    side = np.cross(direction, reference)
    side /= np.linalg.norm(side)
    other = np.cross(direction, side)
    offsets = np.array((side + other, -side + other, -side - other, side - other)) * width / 2
    result = []
    for i in range(4):
        j = (i + 1) % 4
        positions = np.array((start + offsets[i], end + offsets[i],
                              end + offsets[j], start + offsets[j]))
        normal = np.cross(positions[1] - positions[0], positions[2] - positions[0])
        normal /= np.linalg.norm(normal)
        result.append(np.concatenate((positions, np.broadcast_to(uv, (4, 2)),
                                      np.broadcast_to(normal, (4, 3))), axis=1))
    return np.array(result)


def geometry_hash(quads):
    return hashlib.sha256(np.asarray(quads, dtype="<f8").tobytes()).hexdigest()
