"""Bake server-safe upper bounds for current TaCZ assembled gun ground meshes.

Reads only installed source packs. Reuses the established drawable-quad/GROUND
coordinate code, without calling its main() or changing its default profiles.
No Minecraft, client renderer or GPU is started. Attachments are bounded once
per type and transformed through real mount bones; no Cartesian product is used.
"""
from __future__ import annotations

import importlib.util
import itertools
import hashlib
import json
import math
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/ground_loot_assembly_envelopes.json'
AUDIT = ROOT / 'logs/ground-loot-physics-envelope-audit.json'
CACHE = ROOT / 'logs/ground-loot-assembly-generation-cache.json'
spec = importlib.util.spec_from_file_location('ground_mesh_source', ROOT / 'scripts/generate-ground-loot-footprints.py')
mesh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mesh)

ALWAYS_HIDDEN = {'lefthand', 'righthand', 'lefthand_pos', 'righthand_pos', 'additional_magazine', 'muzzle_flash'}
CONDITIONAL = {'mount', 'carry', 'sight', 'sight_folded', 'mag_standard', 'mag_extended_1', 'mag_extended_2',
               'mag_extended_3', 'handguard_default', 'handguard_tactical', 'bullet_in_barrel', 'bullet_in_mag',
               'bullet_chain', 'muzzle_default', 'stock_default', 'grip_default', 'laser_default', 'extended_mag_default'}
TYPES = {'scope', 'muzzle', 'stock', 'grip', 'laser', 'extended_mag'}
MARGINS = (.04, .015, .04)


def visibility(name, parent, invariant=False):
    name = name.lower()
    if name in ALWAYS_HIDDEN or name.startswith('laser_beam'):
        return False
    if invariant and (name in CONDITIONAL or parent == 'attachment_adapter'):
        return False
    return True


def bounds(vertices):
    if not vertices:
        return None
    return ([min(p[i] for p in vertices) for i in range(3)],
            [max(p[i] for p in vertices) for i in range(3)])


def corners(box):
    return [tuple(values) for values in itertools.product(*zip(*box))]


def union(boxes):
    return bounds([p for box in boxes if box for p in corners(box)])


def dims(box):
    return [max(0., box[1][i] - box[0][i]) for i in range(3)] if box else [0., 0., 0.]


def possible_flat_dimensions(lower, upper):
    """Intervals contain every appearance's extent; only exclude provably impossible axes.

    GroundLootFlatPose keeps Y when Y <= 1.05 * min(X,Y,Z); otherwise
    it rotates Z90 if X <= Z, or X90. Every actual mesh contains the
    invariant body and is contained in the all-variants union. After
    recentering, each permitted extent permutation is therefore an upper bound.
    Every appearance keeps the item's authored native GROUND scale.
    """
    choices = []
    axes = []
    tolerance = 1e-9
    if lower[1] <= 1.05 * min(upper[0], upper[2]) + tolerance:
        choices.append(upper)
        axes.append('none')
    if lower[0] <= upper[2] + tolerance:
        choices.append([upper[1], upper[0], upper[2]])
        axes.append('z90')
    if upper[0] + tolerance >= lower[2]:
        choices.append([upper[0], upper[2], upper[1]])
        axes.append('x90')
    if not choices:
        choices = [upper, [upper[1], upper[0], upper[2]], [upper[0], upper[2], upper[1]]]
        axes = ['none', 'z90', 'x90']
    return [max(v[i] for v in choices) for i in range(3)], axes, 1.


def attachment_boxes():
    by_type = {kind: [] for kind in TYPES}
    refs = {kind: [] for kind in TYPES}
    failures = []
    for path in sorted(mesh.resources):
        if not path.startswith('data/') or '/index/attachments/' not in path:
            continue
        try:
            index = mesh.read(path)
            kind = index.get('type', '').lower()
            if kind not in TYPES:
                continue
            display = mesh.read(mesh.locate(index['display'], 'display/attachments'))
            # Mounted GroundGunModelRenderer uses the high-poly attachment model.
            model_ref = display.get('model')
            if not model_ref or mesh.locate(model_ref, 'geo_models') not in mesh.resources:
                continue
            model = mesh.read(mesh.locate(model_ref, 'geo_models'))
            parts = path.split('/')
            asset_id = parts[1] + ':' + '/'.join(parts[4:])[:-5]
            vertices, _, _ = mesh.vertices(display, model, 'attachments', prefix=mesh.identity(), asset_id=asset_id)
            box = bounds(vertices)
            if box:
                by_type[kind].append(box)
                refs[kind].append(path)
        except (KeyError, ValueError, TypeError, RecursionError) as error:
            failures.append({'source': path, 'error': str(error)})
    return {kind: union(boxes) for kind, boxes in by_type.items()}, refs, failures


def profile():
    return {'scope': False, 'scope_mount': False, 'mag': 0, 'tactical': False, 'installed': set(),
            'adapters': set(), 'show_muzzle': False, 'attachments': {}}


def gun_variant_boxes(display, model):
    """Measure all/invariant body vertices together; deduplicate each cube's face corners.

    The old generator needs full triangles for its historical comparison. Here
    the two hulls only need drawable corner sets, so shared face vertices are
    transformed once and the physical rules remain unchanged.
    """
    bones, matrix = mesh.model_parts(model)
    ground = mesh.ground_matrix(display, bones, 'guns')
    all_vertices, invariant_vertices = [], []
    positions = {kind + '_pos' for kind in TYPES}
    for name, bone in bones.items():
        current = name
        drawable = invariant = True
        while current in bones:
            parent = bones[current].get('parent')
            drawable &= visibility(current, parent) and current not in positions
            invariant &= visibility(current, parent, invariant=True) and current not in positions
            current = parent
        if not drawable:
            continue
        base_matrix = mesh.mul(ground, matrix(name))
        for cube in bone.get('cubes', []):
            transform = base_matrix
            pivot = bone.get('pivot', [0, 0, 0])
            if 'rotation' in cube:
                new_pivot = cube.get('pivot', [0, 0, 0])
                transform = mesh.mul(transform, mesh.trs([new_pivot[0]-pivot[0], pivot[1]-new_pivot[1], new_pivot[2]-pivot[2]], cube['rotation']))
                pivot = new_pivot
            origin, size = cube['origin'], cube['size']
            local = [origin[0]-pivot[0], pivot[1]-origin[1]-size[1], origin[2]-pivot[2]]
            inflate = cube.get('inflate', 0)
            uv = cube.get('uv', [])
            faces = [bits for face, bits in mesh.FACE_BITS.items() if not isinstance(uv, dict) or face in uv]
            point_map = {bits: mesh.point(transform, [(local[i]+(size[i]+inflate if bits[i] else -inflate))/16 for i in range(3)]+[1])
                         for bits in {bits for face in faces for bits in face}}
            drawn = {bits for face in faces if mesh.area([point_map[bits] for bits in face]) for bits in face}
            points = [point_map[bits] for bits in drawn]
            all_vertices.extend(points)
            if invariant:
                invariant_vertices.extend(points)
    return bones, matrix, ground, bounds(all_vertices), bounds(invariant_vertices)


def gun_model_envelope(display, model, attachment_by_type):
    bones, matrix, ground, body_box, invariant_box = gun_variant_boxes(display, model)
    if body_box is None:
        raise ValueError('no_drawable_geometry')
    combined = [body_box]
    mounts = []
    for kind in sorted(TYPES):
        name = kind + '_pos'
        source_box = attachment_by_type[kind]
        if name not in bones or source_box is None:
            continue
        # This is the same mount path and -1.5 translation as collectGun().
        mount = mesh.mul(mesh.mul(ground, matrix(name)), mesh.translate([0, -1.5, 0]))
        combined.append(bounds([mesh.point(mount, list(p) + [1]) for p in corners(source_box)]))
        mounts.append(kind)
    upper = dims(union(combined))
    lower = dims(invariant_box)
    result, axes, thin_scale = possible_flat_dimensions(lower, upper)
    return result, {'mountTypes': mounts, 'candidateFlatAxes': axes, 'guaranteedExtraScale': thin_scale,
                    'invariantDimensions': [round(v, 6) for v in lower],
                    'unionDimensions': [round(v, 6) for v in upper]}


def main():
    started = time.monotonic()
    source = json.loads((ROOT / 'docs/gun-balance/gun-assets.json').read_text(encoding='utf-8'))
    expected = {g['id'] for g in source['guns']}
    defaults = json.loads((ROOT / 'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/ground_loot_footprints.json').read_text(encoding='utf-8'))['guns']
    attachments, refs, errors = attachment_boxes()
    source_hash = hashlib.sha256(Path(__file__).read_bytes())
    source_hash.update((ROOT / 'scripts/generate-ground-loot-footprints.py').read_bytes())
    for path, content in sorted(mesh.resources.items()):
        source_hash.update(path.encode('utf-8'))
        source_hash.update(content)
    source_digest = source_hash.hexdigest()
    cached = json.loads(CACHE.read_text(encoding='utf-8')) if CACHE.is_file() else {}
    cached_rows = cached.get('rows', {}) if cached.get('sourceSha256') == source_digest else {}
    result, rows, missing_lod = {}, [], []
    for identifier in sorted(expected):
        path = mesh.locate(identifier, 'index/guns', 'data')
        try:
            index = mesh.read(path)
            display = mesh.read(mesh.locate(index['display'], 'display/guns'))
            models = [display['model']]
            lod_model = (display.get('lod') or {}).get('model')
            if lod_model and mesh.locate(lod_model, 'geo_models') not in mesh.resources:
                missing_lod.append({'id': identifier, 'lodModel': lod_model})
                lod_model = None
            if lod_model and lod_model not in models:
                models.append(lod_model)
            if identifier in cached_rows:
                row = dict(cached_rows[identifier])
                if not row.get('iconFallback'):
                    size = [max(defaults[identifier][i], row['rawSize'][i] + MARGINS[i]) for i in range(3)]
                    row['size'] = [round(math.ceil(v * 1e6) / 1e6, 6) for v in size]
                result[identifier] = row['size']
                rows.append(row)
                continue
            measurements = [gun_model_envelope(display, mesh.read(mesh.locate(ref, 'geo_models')), attachments) for ref in models]
            # Preserve the current default/builtin clearances as another lower requirement.
            raw_size = [max(v[0][i] for v in measurements) for i in range(3)]
            size = [max(defaults[identifier][i], raw_size[i] + MARGINS[i]) for i in range(3)]
            assert all(math.isfinite(v) and v > 0 for v in size)
            result[identifier] = [round(math.ceil(v * 1e6) / 1e6, 6) for v in size]
            rows.append({'id': identifier, 'models': models, 'rawSize': raw_size, 'size': result[identifier],
                         'measurements': [v[1] for v in measurements]})
            cached_rows[identifier] = rows[-1]
            if len(cached_rows) % 20 == 0:
                CACHE.write_text(json.dumps({'sourceSha256': source_digest, 'rows': cached_rows}), encoding='utf-8')
        except (KeyError, ValueError, TypeError, RecursionError, AssertionError) as error:
            # Current icon-only fallback guns have no usable physical model at runtime.
            if str(error) == 'no_drawable_geometry':
                result[identifier] = defaults[identifier]
                rows.append({'id': identifier, 'iconFallback': True, 'size': result[identifier]})
                cached_rows[identifier] = rows[-1]
            else:
                errors.append({'id': identifier, 'source': path, 'error': str(error)})
    CACHE.write_text(json.dumps({'sourceSha256': source_digest, 'rows': cached_rows}), encoding='utf-8')
    assert not errors, errors
    assert set(result) == expected, f'Incomplete coverage: {expected-set(result)}'
    # The independent legacy footprint bake may finish while this geometry pass runs.
    # Read its latest numeric defaults now; cached geometry is independent of margins.
    defaults = json.loads((ROOT / 'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/ground_loot_footprints.json').read_text(encoding='utf-8'))['guns']
    for row in rows:
        identifier = row['id']
        size = defaults[identifier] if row.get('iconFallback') else [max(defaults[identifier][i], row['rawSize'][i] + MARGINS[i]) for i in range(3)]
        row['size'] = result[identifier] = [round(math.ceil(v * 1e6) / 1e6, 6) for v in size]
    metadata = {'gunCount': len(result), 'attachmentSourceCount': sum(len(v) for v in refs.values()),
                'attachmentTypes': {k: len(v) for k, v in refs.items()},
                'placementMargins': list(MARGINS), 'floorLiftIncluded': False,
                'gunsWithAvailableLod': [row['id'] for row in rows if len(row.get('models', [])) > 1],
                'ignoredMissingLodReferenceCount': len(missing_lod),
                'scope': 'Current installed main and LOD gun models, all physical gun-bone variants, and all currently installed attachment meshes by mount type.',
                'method': 'Drawable quad static GROUND vertices; invariant/all-variants extent intervals prove possible flatten axes; mounted attachment type-union AABBs transformed through actual mount bones.',
                'limitations': ['Conservative bounds can prevent fitting an otherwise empty tight gap.',
                               'Unknown future gun packs, custom display overrides and dynamic third-party renderers require fresh profiles.',
                               'This is an offline geometry guarantee for the audited source set, not gameplay or shader validation.']}
    OUT.write_text(json.dumps({'schemaVersion': 2, 'sizePolicy': 'native_ground_no_extra_scale', 'units': 'blocks', 'guns': result, 'metadata': metadata},
                              ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    AUDIT.write_text(json.dumps({'schemaVersion': 1, 'elapsedSeconds': round(time.monotonic()-started, 3),
                                'metadata': metadata, 'errors': errors, 'missingOptionalLod': missing_lod, 'rows': rows},
                               ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'guns': len(result), 'attachments': metadata['attachmentSourceCount'],
                      'errors': len(errors), 'elapsedSeconds': round(time.monotonic()-started, 3)}))


if __name__ == '__main__':
    main()
