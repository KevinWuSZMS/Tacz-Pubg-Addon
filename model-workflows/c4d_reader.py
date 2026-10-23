"""Read static polygon Cinema 4D HyperFile scenes into agent-model-scene/1.

This is a deliberately limited, dependency-free reader, not a Cinema 4D evaluator.
It reads stored polygon/UVW data, never thumbnails. Unsupported generators,
deformers, animation and material projections are rejected. The source stays read-only.
The file constants and layouts were checked against Maxon's Cineware 22.008 SDK.
SDK: https://developers.maxon.net/downloads/ (Cineware S22.008).
Matrix convention: https://developers.maxon.net/docs/py/2023_2/manuals/data_algorithms/classic_api/matrix.html
UVW assignment: https://help.maxon.net/c4d/s26/en-us/Content/html/TUVW.html
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from dataclasses import dataclass, field
import hashlib
import json
import math
from pathlib import Path
import struct
from typing import Any


class C4DReadError(ValueError):
    """The input uses a layout or scene feature this reader cannot faithfully read."""


class C4DTextureAmbiguityError(C4DReadError):
    """An exact bitmap reference has several different candidate contents."""


@dataclass
class Value:
    kind: int
    data: Any
    offset: int


@dataclass
class Chunk:
    ident: int
    level: int
    offset: int
    items: list[Any] = field(default_factory=list)

    def children(self, ident: int | None = None):
        return [v for v in self.items if isinstance(v, Chunk)
                and (ident is None or v.ident == ident)]

    def values(self, kind: int | None = None):
        return [v for v in self.items if isinstance(v, Value)
                and (kind is None or v.kind == kind)]

    def walk(self, ident: int | None = None):
        if ident is None or self.ident == ident:
            yield self
        for child in self.children():
            yield from child.walk(ident)


_FORMATS = {11: 'b', 12: 'B', 13: 'h', 14: 'H', 15: 'i', 16: 'I',
            17: 'q', 18: 'Q', 19: 'd', 20: 'd', 21: '?', 22: '2d',
            23: '3d', 24: '3d', 25: '12d', 26: '12d', 27: '3f',
            28: '12f', 29: 'f'}

# The source packs use the same native token/typed-array layouts. The R14
# VehicleS pack additionally uses BaseObject record level 9 (R19 uses 10).
# R19.024 is verified against the supplied SM_FirstAidPackMilitary_01 scene:
# 825 points, 998 triangles, two stored UV channels, and linked D/N/RM bitmaps.
# Keep this an explicit verified set rather than accepting arbitrary C4D files.
_VERIFIED_VERSIONS = {14042: 'R14.042', 19024: 'R19.024', 19068: 'R19.068'}


def _read_document(data: bytes) -> Chunk:
    if data[:8] != b'QC4DC4D6':
        raise C4DReadError('Only uncompressed C4D6 HyperFile documents are supported.')
    version = struct.unpack_from('>I', data, 8)[0]
    if version not in _VERIFIED_VERSIONS:
        names = ', '.join(_VERIFIED_VERSIONS.values())
        raise C4DReadError(f'Unsupported file version {version}; verified versions are {names}.')
    if data[12] != 0:
        raise C4DReadError('Unsupported HyperFile header flags.')
    root = Chunk(-1, version, 0)
    stack = [root]
    pos = 13
    while pos < len(data):
        start = pos
        kind = data[pos]
        pos += 1
        try:
            if kind == 1:
                ident, level = struct.unpack_from('>Ii', data, pos)
                pos += 8
                child = Chunk(ident, level, start)
                stack[-1].items.append(child)
                stack.append(child)
            elif kind == 2:
                if len(stack) == 1:
                    raise C4DReadError(f'Unmatched chunk end at {start:#x}.')
                stack.pop()
            elif kind in _FORMATS:
                fmt = '>' + _FORMATS[kind]
                value = struct.unpack_from(fmt, data, pos)
                pos += struct.calcsize(fmt)
                stack[-1].items.append(Value(kind, value[0] if len(value) == 1 else value, start))
            elif kind in (128, 129, 130, 131, 140):
                size = struct.unpack_from('>I', data, pos)[0]
                pos += 4
                if size > len(data) - pos:
                    raise C4DReadError(f'Truncated data at {start:#x}.')
                value = data[pos:pos + size]
                pos += size
                if kind in (130, 131):
                    value = value.decode('utf-16-be', errors='strict')
                stack[-1].items.append(Value(kind, value, start))
            else:
                raise C4DReadError(f'Unsupported HyperFile value {kind} at {start:#x}.')
        except (struct.error, UnicodeDecodeError) as error:
            raise C4DReadError(f'Malformed HyperFile at {start:#x}: {error}') from error
    if len(stack) != 1:
        raise C4DReadError('Unclosed HyperFile chunks.')
    return root


def _one(items, label):
    if len(items) != 1:
        raise C4DReadError(f'Expected one {label}; found {len(items)}.')
    return items[0]


def _base(node: Chunk) -> Chunk:
    return _one(node.children(110050), 'base list record')


def _name(node: Chunk) -> str:
    names = _base(node).values(130)
    return names[0].data if names else f'object_{node.offset:x}'


def _parameters(node: Chunk) -> dict[int, Value | Chunk]:
    containers = _base(node).children(132)
    if not containers:
        return {}
    seq = containers[0].items
    if not seq or not isinstance(seq[0], Value) or seq[0].data != -1:
        raise C4DReadError('Unsupported BaseContainer layout.')
    result = {}
    index = 1
    while index < len(seq):
        if index + 1 >= len(seq):
            raise C4DReadError('Incomplete parameter entry.')
        key, datatype = seq[index:index + 2]
        if not isinstance(key, Value) or key.kind != 15 or not isinstance(datatype, Value) or datatype.kind != 15:
            raise C4DReadError('Unsupported parameter container encoding.')
        # DTYPE_NONE has no payload, including a trailing empty field found in
        # standard-material containers. It is not a malformed value triple.
        if datatype.data == 0:
            index += 2
            continue
        if index + 2 >= len(seq):
            raise C4DReadError('Incomplete parameter triple.')
        value = seq[index + 2]
        result[key.data] = value
        index += 3
    return result


def _param(params, key, default=None):
    value = params.get(key)
    return value.data if isinstance(value, Value) else default


def _identity(node: Chunk) -> bytes:
    ids = [v.data[6:] for v in _base(node).values(128) if v.data.startswith(b'agoal1')]
    return _one(ids, 'object alias identity')


def _alias(value) -> bytes | None:
    if not isinstance(value, Value) or value.kind != 128 or not value.data.startswith(b'alias2'):
        return None
    return value.data[6:]


def _array(value: Value, subtype: int, count: int, fmt: str):
    if value.kind != 140 or not value.data or value.data[0] != subtype:
        raise C4DReadError('Unexpected native typed array.')
    if len(value.data) != 1 + count * struct.calcsize('>' + fmt):
        raise C4DReadError('Native array length disagrees with geometry count.')
    return struct.unpack_from('>' + str(count) + fmt, value.data, 1)


def _selection(node: Chunk, maximum: int) -> set[int]:
    record = _one(node.children(110011), 'selection record')
    values = [v.data for v in record.values(15)]
    if values == [0, -1]:
        return set()
    if record.level != 1 or not values or values[0] < 0 or len(values) != 2 + 2 * values[0]:
        raise C4DReadError('Unsupported selection ranges.')
    # The final LONG is lookup/cache state, not another range endpoint. The
    # source material selections store 0/1 here while edge selections commonly
    # cache the final endpoint. Only the preceding inclusive pairs select faces.
    if not -1 <= values[-1] < maximum:
        raise C4DReadError('Selection cache is outside the mesh.')
    result = set()
    previous_end = -1
    for start, end in zip(values[1:-1:2], values[2:-1:2]):
        if start <= previous_end or end < start or end >= maximum:
            raise C4DReadError('Selection range is outside the mesh.')
        result.update(range(start, end + 1))
        previous_end = end
    return result


def _dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def _sub(a, b):
    return tuple(x - y for x, y in zip(a, b))


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _unit(v):
    length = math.sqrt(_dot(v, v))
    if length <= 1e-14:
        raise C4DReadError('Degenerate polygon or zero normal.')
    return tuple(x / length for x in v)


def _matmul(a, b):
    return tuple(tuple(sum(a[r][k] * b[k][c] for k in range(4)) for c in range(4)) for r in range(4))


_IDENTITY = ((1., 0., 0., 0.), (0., 1., 0., 0.), (0., 0., 1., 0.), (0., 0., 0., 1.))


def _object_matrix(node: Chunk):
    record = _one(node.children(110052), 'object transform')
    if record.level not in (9, 10):
        raise C4DReadError(f'Unsupported object transform level {record.level}.')
    values = record.values()
    expected_kinds = [23, 23, 23, 15, 15, 15, 21, 23, 15, 23, 15, 25, 21, 21, 16, 15]
    if [v.kind for v in values] != expected_kinds:
        raise C4DReadError('Unsupported BaseObject transform record field layout.')
    pos, scale, rotation = [v.data for v in values[:3]]
    # C4D's HPB convention is Ry(heading) Rx(pitch) Rz(bank), with left-handed angles.
    h, p, bank = rotation
    ch, sh, cp, sp, cb, sb = math.cos(h), math.sin(h), math.cos(p), math.sin(p), math.cos(bank), math.sin(bank)
    ry = ((ch, 0., -sh, 0.), (0., 1., 0., 0.), (sh, 0., ch, 0.), (0., 0., 0., 1.))
    rx = ((1., 0., 0., 0.), (0., cp, sp, 0.), (0., -sp, cp, 0.), (0., 0., 0., 1.))
    rz = ((cb, sb, 0., 0.), (-sb, cb, 0., 0.), (0., 0., 1., 0.), (0., 0., 0., 1.))
    matrix = _matmul(_matmul(ry, rx), rz)
    matrix = tuple(tuple(matrix[r][c] * scale[c] if c < 3 else pos[r] if r < 3 else 1. for c in range(4)) for r in range(4))
    # The intervening vectors are object display/shading colors, and the Matrix
    # is the modeling-axis matrix, not a frozen object transform. All supplied
    # objects have boflags=0. Fail closed when actual frozen-coordinate flags
    # occur; their additional storage is not decoded by this reader.
    if len(values) != 16 or values[14].kind != 16:
        raise C4DReadError('Unsupported BaseObject transform record.')
    if values[14].data & 14:
        raise C4DReadError('Frozen-coordinate flags require the official Cinema 4D exporter.')
    if values[-1].kind != 15 or values[-1].data != 6:
        raise C4DReadError('Only the native HPB rotation order is supported.')
    if any(abs(s) < 1e-12 for s in scale):
        raise C4DReadError('Zero scale is unsupported.')
    return matrix, {'position': pos, 'scale': scale, 'rotation_hpb': rotation}


def _transform(matrix, point):
    return tuple(sum(matrix[r][c] * point[c] for c in range(3)) + matrix[r][3] for r in range(3))


def _resolve_texture(source: Path, filename: str, texture_roots=(), *, texture_overrides=None,
                     resolution_info=None) -> Path:
    """Resolve the stored filename; explicit roots are a missing-local fallback.

    A basename match never changes the extension or guesses channel suffixes.
    Multiple copies are interchangeable only when their complete SHA256 agrees.
    """
    source = Path(source).resolve()
    normalized = filename.replace('\\', '/')
    original = Path(normalized)
    matching_overrides = [(name, Path(path).resolve(strict=True))
                          for name, path in (texture_overrides or {}).items()
                          if name.casefold() == original.name.casefold()]
    if len(matching_overrides) > 1:
        raise C4DTextureAmbiguityError(f'Multiple explicit overrides for texture {filename!r}.')
    if matching_overrides:
        override_name, replacement = matching_overrides[0]
        if not replacement.is_file():
            raise C4DReadError(f'Explicit texture override must be a file: {replacement}')
        if resolution_info is not None:
            resolution_info.update({'resolution_source': 'explicit_override',
                                    'resolution_method': 'explicit_override',
                                    'resolution_root': str(replacement.parent),
                                    'texture_override_key': override_name,
                                    'texture_sha256': hashlib.sha256(replacement.read_bytes()).hexdigest()})
        return replacement
    candidates = ([original] if original.is_absolute() else []) + [source.parent / original, source.parent / 'tex' / original.name]
    for candidate in candidates:
        if candidate.is_file():
            if resolution_info is not None:
                resolution_info.update({'resolution_source': 'original_reference',
                                        'resolution_root': str(source.parent)})
            return candidate.resolve()

    def unique(matches, *, external):
        paths = sorted({p.resolve() for p in matches}, key=lambda p: str(p).casefold())
        if not paths:
            return None
        if len(paths) > 1 and len({hashlib.sha256(p.read_bytes()).digest() for p in paths}) != 1:
            location = 'explicit texture roots' if external else 'source folder'
            raise C4DTextureAmbiguityError(
                f'Texture {filename!r} has {len(paths)} different candidates in {location}; supply an unambiguous root.')
        if resolution_info is not None and len(paths) > 1:
            resolution_info['identical_texture_copies'] = [str(p) for p in paths]
        return paths[0]

    local = unique((p for p in source.parent.rglob('*')
                    if p.is_file() and p.name.casefold() == original.name.casefold()), external=False)
    if local is not None:
        if resolution_info is not None:
            resolution_info.update({'resolution_source': 'source_folder_basename',
                                    'resolution_root': str(source.parent)})
        return local
    roots = tuple(Path(root).resolve(strict=True) for root in texture_roots)
    for root in roots:
        if not root.is_dir():
            raise C4DReadError(f'Texture search root must be a directory: {root}')
    external_matches = [(root, p) for root in roots for p in root.rglob('*')
                        if p.is_file() and p.name.casefold() == original.name.casefold()]
    external = unique((p for _, p in external_matches), external=True)
    if external is None:
        raise C4DReadError(f'Texture {filename!r} must resolve uniquely; found 0 files.')
    if resolution_info is not None:
        resolution_info.update({'resolution_source': 'explicit_texture_root',
                                'resolution_root': str(next(root for root, p in external_matches if p.resolve() == external)),
                                'texture_sha256': hashlib.sha256(external.read_bytes()).hexdigest(),
                                'local_texture_status': 'missing'})
    return external


def _materials(root: Chunk, source: Path, texture_roots=(), texture_overrides=None):
    from c4d_materials import read_materials
    return read_materials(root, source, texture_roots=texture_roots, texture_overrides=texture_overrides)


def _polygon_objects(root: Chunk, parent=_IDENTITY, scene_notes=None):
    for listing in root.children(110100):
        for node in listing.children():
            if node.ident == 110069:
                continue
            layers = node.children(110064)
            type_id = layers[0].values(15)[0].data if layers else node.ident
            # Sky/environment nodes do not belong to an extracted inventory item.
            # Joints are transform-only here: the stored polygon mesh is exported
            # in its saved static pose; skinning is deliberately not evaluated.
            if type_id in (5105, 1011146, 1019363):
                if scene_notes is not None:
                    scene_notes.append({'object': _name(node), 'type': type_id,
                                        'action': 'skinning deformer not evaluated; stored polygon pose exported'
                                        if type_id == 1019363 else 'excluded scene sky/environment'})
                continue
            if type_id not in (5100, 5140, 1019362):
                raise C4DReadError(f'Unsupported object {_name(node)!r} type {type_id}; bake/evaluate it first.')
            matrix, transform = _object_matrix(node)
            matrix = _matmul(parent, matrix)
            if list(node.walk(110109)):
                raise C4DReadError('Animated objects require an explicit frame baked in Cinema 4D.')
            if type_id == 5100:
                yield node, matrix, transform
            elif type_id == 1019362 and scene_notes is not None:
                scene_notes.append({'object': _name(node), 'type': type_id,
                                    'action': 'joint transform retained; skinning not evaluated'})
            for child_root in node.children(110107):
                yield from _polygon_objects(child_root, matrix, scene_notes)
            # A saved object's ordinary child list is CHUNK_LIST (110100)
            # directly on the object, rather than another CHUNK_ROOT_OBJECT.
            yield from _polygon_objects(node, matrix, scene_notes)


def _normal_fans(points, polygons, hard_edges, angle: float | None, source_indices=None):
    """Reconstruct smooth vertex normals from source topology and Phong edge breaks.

    Corner-angle weighting avoids triangulation-density bias. It intentionally does
    not weld positions: coincident, separately indexed vertices remain separate.
    """
    source_indices = list(range(len(polygons))) if source_indices is None else source_indices
    face_normals = [_unit(_cross(_sub(points[p[1]], points[p[0]]), _sub(points[p[2]], points[p[0]]))) for p in polygons]
    parent = list(range(len(polygons) * 3))
    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    def union(a, b):
        parent[find(a)] = find(b)
    edges = defaultdict(list)
    for fi, poly in enumerate(polygons):
        for ci in range(3):
            a, b = poly[ci], poly[(ci + 1) % 3]
            # CPolygon triangle's boundary edges are 0:a-b, 1:b-c, 3:d-a (d=c).
            native_edge = (0, 1, 3)[ci]
            edges[tuple(sorted((a, b)))].append((fi, ci, native_edge))
    if angle is not None:
        limit = math.cos(angle)
        for edge, uses in edges.items():
            if len(uses) != 2:
                continue
            (fa, ca, ea), (fb, cb, eb) = uses
            if source_indices[fa] * 4 + ea in hard_edges or source_indices[fb] * 4 + eb in hard_edges:
                continue
            if _dot(face_normals[fa], face_normals[fb]) < limit - 1e-8:
                continue
            for point_index in edge:
                ia = polygons[fa].index(point_index)
                ib = polygons[fb].index(point_index)
                union(fa * 3 + ia, fb * 3 + ib)
    accumulated = defaultdict(lambda: [0., 0., 0.])
    for fi, poly in enumerate(polygons):
        for ci, pi in enumerate(poly):
            a = _unit(_sub(points[poly[(ci + 1) % 3]], points[pi]))
            b = _unit(_sub(points[poly[(ci - 1) % 3]], points[pi]))
            weight = math.acos(max(-1., min(1., _dot(a, b))))
            target = accumulated[find(fi * 3 + ci)]
            for k in range(3):
                target[k] += face_normals[fi][k] * weight
    return [[_unit(accumulated[find(fi * 3 + ci)]) for ci in range(3)] for fi in range(len(polygons))]


def _mesh(node, matrix, aliases, transforms, source_object_index=0):
    name = _name(node)
    tag_root = _one(node.children(110108), 'tag root')
    listing = _one(tag_root.children(110100), 'tag list')
    tags = [n for n in listing.children() if n.ident != 110069]
    supported_tags = {5600, 5604, 5612, 5616, 5671, 5673, 5682}
    ignored_tags = []
    for tag in tags:
        if tag.ident in supported_tags:
            continue
        layers = tag.children(110064)
        plugin_type = layers[0].values(15)[0].data if layers and layers[0].values(15) else None
        if tag.ident == 5691 and plugin_type == 1019365:
            ignored_tags.append({'type': plugin_type, 'name': _name(tag),
                                 'reason': 'weight tag retained in original; static saved polygon pose only, no skinning evaluation'})
        else:
            raise C4DReadError(f'{name}: unsupported tag {tag.ident}, plugin type {plugin_type}.')
    point_tag = _one([n for n in tags if n.ident == 5600], 'point tag')
    point_data = _one(point_tag.children(5600), 'point data')
    point_count = _one(point_data.values(15), 'point count').data
    point_arrays = point_data.values(140)
    if len(point_arrays) != 3:
        raise C4DReadError('Expected three point coordinate arrays.')
    components = [_array(v, 6, point_count, 'd') for v in point_arrays]
    points = [tuple(v) for v in zip(*components)]
    if not all(math.isfinite(x) for point in points for x in point):
        raise C4DReadError('Non-finite source coordinates.')
    poly_tag = _one([n for n in tags if n.ident == 5604], 'polygon tag')
    poly_data = _one(poly_tag.children(5604), 'polygon data')
    count = _one(poly_data.values(15), 'polygon count').data
    arrays = poly_data.values(140)
    if len(arrays) != 4:
        raise C4DReadError('Expected four polygon corner index arrays.')
    components = [_array(v, 3, count, 'i') for v in arrays]
    quads = list(zip(*components))
    if any(p[2] != p[3] for p in quads):
        raise C4DReadError('This reader requires stored triangulated geometry; triangulate quads/n-gons in Cinema 4D.')
    polygons = [tuple(p[:3]) for p in quads]
    if any(pi < 0 or pi >= point_count for p in polygons for pi in p):
        raise C4DReadError('Polygon index outside point array.')
    # Zero-area source records cannot draw a surface or produce a valid normal.
    # Preserve their native indices in metadata; never renumber Phong edge breaks
    # or UV/material selections as a consequence of omitting them from rendering.
    retained_indices = []
    degenerate_indices = []
    for fi, poly in enumerate(polygons):
        cross = _cross(_sub(points[poly[1]], points[poly[0]]), _sub(points[poly[2]], points[poly[0]]))
        (retained_indices if _dot(cross, cross) > 1e-28 else degenerate_indices).append(fi)
    retained_polygons = [polygons[fi] for fi in retained_indices]
    if not retained_polygons:
        raise C4DReadError(f'{name}: every source polygon has zero area.')
    texture_tags = [n for n in tags if n.ident == 5616]
    uv_tags = [n for n in tags if n.ident == 5671]
    if not uv_tags:
        raise C4DReadError('No stored UVW coordinates.')
    uv_arrays = {}
    for uv_tag in uv_tags:
        uv_data = _one(uv_tag.children(5671), 'UVW data')
        if uv_data.level != 4 or _one(uv_data.values(15), 'UVW count').data != count:
            raise C4DReadError('Unsupported UVW encoding or polygon count mismatch.')
        # UVWTag level 4 stores IEEE float bit patterns in a LONG array.
        uvs = _array(_one(uv_data.values(140), 'UVW array'), 3, count * 8, 'f')
        if any(not math.isfinite(v) for v in uvs):
            raise C4DReadError('Non-finite source UVs.')
        uv_arrays[id(uv_tag)] = uvs
    # Texture tags are layered from left to right. For these opaque material
    # assignments the last tag covering a polygon wins; all original polygons
    # remain exactly once, including polygons outside any material selection.
    assignments = [None] * count
    warnings = []
    for texture_tag in texture_tags:
        params = _parameters(texture_tag)
        if _param(params, 1004, 6) != 6:
            raise C4DReadError('Only stored UVW texture projection is supported.')
        for key, expected in ((1002, 1.), (1003, 1.), (1201, 0.), (1202, 0.)):
            if abs(_param(params, key, expected) - expected) > 1e-9:
                raise C4DReadError('Transformed/tiled texture coordinates require baking.')
        # Integer offsets are exact whole-tile shifts when repeat is enabled.
        for key in (1000, 1001):
            offset = _param(params, key, 0.)
            if abs(offset - round(offset)) > 1e-9 or (offset != 0 and not _param(params, 1007, True)):
                raise C4DReadError('Non-integral/nonrepeating texture offsets require baking.')
        material_alias = _alias(params.get(1010))
        material = aliases.get(material_alias)
        if material is None:
            if None not in aliases:
                raise C4DReadError('Material link does not resolve to a supported material.')
            material = aliases[None]
            warnings.append({'texture_tag': _name(texture_tag), 'reason': 'unresolved material link; explicit neutral placeholder',
                             'alias': material_alias.hex() if material_alias is not None else None})
        following = [n for n in uv_tags if tags.index(n) > tags.index(texture_tag)]
        selected_uv = following[0] if following else uv_tags[0]
        restriction = _param(params, 1006, '')
        if restriction:
            selection = _one([n for n in tags if n.ident == 5673 and _name(n) == restriction], 'texture polygon selection')
            selected = _selection(selection, count)
        else:
            selected = range(count)
        for fi in selected:
            assignments[fi] = (material, id(selected_uv))
    unassigned_count = sum(assignments[fi] is None for fi in retained_indices)
    if unassigned_count:
        if None not in aliases:
            raise C4DReadError(f'{name}: {unassigned_count} polygons have no assigned material.')
        warnings.append({'reason': 'polygons without material assignment use explicit neutral placeholder',
                         'triangles': unassigned_count})
        for fi in retained_indices:
            if assignments[fi] is None:
                assignments[fi] = (aliases[None], id(uv_tags[0]))
    phong = [n for n in tags if n.ident == 5612]
    angle = None
    hard_edges = set()
    if phong:
        phong_params = _parameters(_one(phong, 'Phong tag'))
        angle = _param(phong_params, 1002, math.pi) if _param(phong_params, 1001, True) else math.pi
        if _param(phong_params, 1003, True):
            for selection in node.children(110132):
                hard_edges.update(_selection(selection, count * 4))
    normals = _normal_fans(points, retained_polygons, hard_edges, angle, retained_indices)
    world = [_transform(matrix, p) for p in points]
    # A normal uses inverse-transpose. Calculate each transformed face's corner
    # normal with the adjugate, preserving reflection by flipping winding below.
    a, b, c = [tuple(matrix[r][col] for r in range(3)) for col in range(3)]
    cofactors = (_cross(b, c), _cross(c, a), _cross(a, b))
    determinant = _dot(a, cofactors[0])
    if abs(determinant) < 1e-12:
        raise C4DReadError('Singular object transform.')
    groups = {}
    for normal_fi, fi in enumerate(retained_indices):
        poly = polygons[fi]
        material, uv_key = assignments[fi]
        vertices = groups.setdefault((material, uv_key), [])
        uvs = uv_arrays[uv_key]
        order = (0, 2, 1) if determinant < 0 else (0, 1, 2)
        for ci in order:
            normal = normals[normal_fi][ci]
            transformed_normal = _unit(tuple(sum(cofactors[k][r] * normal[k] for k in range(3)) / determinant for r in range(3)))
            uv = uvs[fi * 8 + ci * 2:fi * 8 + ci * 2 + 2]
            vertices.extend((*world[poly[ci]], *uv, *transformed_normal))
    transforms.append({'name': name, **transforms_info(node), 'world_matrix': matrix,
                       'source_object_index': source_object_index,
                       'points': point_count, 'source_triangles': count,
                       'triangles': len(retained_indices), 'zero_area_triangles_omitted': degenerate_indices,
                       'uv_channels': len(uv_tags), 'material_submeshes': len(groups),
                       'selected_uv_channels': sorted({_name(t) for t in uv_tags if any(key[1] == id(t) for key in groups)}),
                       'hard_edge_count': len(hard_edges), 'normals': 'topology and Phong breaks; corner-angle weighted',
                       'ignored_tags': ignored_tags, 'warnings': warnings})
    return [{'name': name if len(groups) == 1 else f'{name}::material_{material}_{part}',
             'object_name': name, 'source_object_index': source_object_index,
             'material': material, 'vertices': vertices}
            for part, ((material, _), vertices) in enumerate(groups.items())]


def transforms_info(node):
    return _object_matrix(node)[1]


def read_scene(path: Path, texture_roots=(), texture_overrides=None) -> dict:
    """Extract a verified R14/R19 triangulated static scene; fail closed otherwise."""
    source = Path(path).resolve(strict=True)
    texture_roots = tuple(Path(root).resolve(strict=True) for root in texture_roots)
    if any(not root.is_dir() for root in texture_roots):
        raise C4DReadError('Every texture search root must be a directory.')
    texture_overrides = {str(name): str(Path(path).resolve(strict=True))
                         for name, path in (texture_overrides or {}).items()}
    if any(Path(name).name != name or '\\' in name for name in texture_overrides):
        raise C4DReadError('Texture override keys must be exact original basenames.')
    if len({name.casefold() for name in texture_overrides}) != len(texture_overrides):
        raise C4DTextureAmbiguityError('Texture override keys must be unique ignoring case.')
    if any(not Path(path).is_file() for path in texture_overrides.values()):
        raise C4DReadError('Every explicit texture override must resolve to a file.')
    data = source.read_bytes()
    root = _read_document(data)
    def top_object_roots(node):
        for child in node.children():
            if child.ident == 110107:
                yield child
            else:
                yield from top_object_roots(child)
    object_roots = list(top_object_roots(root))
    if not object_roots:
        raise C4DReadError('No scene object root found.')
    materials, aliases = _materials(root, source, texture_roots=texture_roots,
                                   texture_overrides=texture_overrides)
    transforms = []
    scene_notes = []
    objects = [obj for object_root in object_roots for obj in _polygon_objects(object_root, scene_notes=scene_notes)]
    meshes = [mesh for index, (node, matrix, _) in enumerate(objects)
              for mesh in _mesh(node, matrix, aliases, transforms, index)]
    if not meshes:
        raise C4DReadError('No stored static polygon meshes found.')
    return {'schema': 'agent-model-scene/1', 'uv_origin': 'top_left',
            'materials': materials, 'meshes': meshes,
            'metadata': {'source': str(source), 'source_sha256': hashlib.sha256(data).hexdigest(),
                         'source_file_version': root.level,
                         'texture_roots': [str(path) for path in texture_roots],
                         'texture_overrides': texture_overrides,
                         'external_texture_resolutions': [
                             {'material': material['name'], 'channel': channel,
                              'filename': record['filename'], 'texture': record['texture'],
                              'resolution_root': record['resolution_root'],
                              'resolution_method': record.get('resolution_method', record['resolution_source']),
                              'texture_sha256': record['texture_sha256']}
                             for material in materials for channel, record in material.get('source_channels', {}).items()
                             if record.get('resolution_source') in ('explicit_texture_root', 'explicit_override')],
                         'extraction_method': f'strict Python C4D6 HyperFile {_VERIFIED_VERSIONS[root.level]} static polygon reader',
                         'object_transforms_applied': True, 'objects': transforms,
                         'scene_notes': scene_notes,
                         'handedness': 'left',
                         'coordinate_system': 'Cinema 4D left handed, Y up; source HPB transforms applied',
                         'uv_source': 'UVW tag selected by material tag order, original per-polygon corner association, V=0 image top',
                         'limitations': 'static saved triangulated polygon geometry; material-selection submeshes; sky excluded; weight tags and skinning not evaluated; no generators, animation, shader baking or non-identity frozen transforms'}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        scene = read_scene(args.source)
    except (C4DReadError, OSError) as error:
        parser.exit(2, f'c4d_reader: {error}\n')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(scene, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    print(json.dumps({'output': str(args.output.resolve()), 'meshes': len(scene['meshes']),
                      'triangles': sum(len(m['vertices']) // 24 for m in scene['meshes']),
                      'source_sha256': scene['metadata']['source_sha256']}, ensure_ascii=False))


if __name__ == '__main__':
    # Material helpers import this module for Chunk/Value checks. Reuse the
    # executing module so a CLI invocation does not create duplicate classes.
    import sys
    sys.modules.setdefault('c4d_reader', sys.modules[__name__])
    main()
