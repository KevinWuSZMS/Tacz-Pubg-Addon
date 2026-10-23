/** Explicit offline LOD for item_042_01. Source exports and bitmaps remain unchanged.
 * Dependency: meshoptimizer 1.3.0 in .tools/meshoptimizer/node_modules (MIT).
 * Install with pnpm --dir model-workflows/.tools/meshoptimizer add meshoptimizer@1.3.0.
 * Run from the workspace root: node model-workflows/export_parachute_canopy.mjs
 */
import { readFileSync, writeFileSync, mkdirSync, copyFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { MeshoptSimplifier } from './.tools/meshoptimizer/node_modules/meshoptimizer/index.js';

const workflow = dirname(fileURLToPath(import.meta.url));
const workspace = resolve(workflow, '..');
const root = join(workflow, 'output/pack_82');
const output = join(workflow, 'output/parachute_canopy');
const assets = join(workspace, 'neoforge-1.21.1/src/main/resources/assets/tacz_pubg');
const pack = JSON.parse(readFileSync(join(root, 'pack.json'), 'utf8'));
const entry = pack.items.find(item => item.id === 'item_042_01');
if (!entry) throw new Error('Missing source item_042_01');
const inputPath = join(root, entry.playful);
const source = JSON.parse(readFileSync(inputPath, 'utf8'));
const sourceTexture = join(dirname(inputPath), 'texture.png');
const hash = file => createHash('sha256').update(readFileSync(file)).digest('hex');

await MeshoptSimplifier.ready;
const vertices = [], indices = [], uniqueVertices = new Map(), uniqueTriangles = new Set();
for (let triangle = 0; triangle < source.vertices.length; triangle += 24) {
    const rows = [0, 1, 2].map(i => source.vertices.slice(triangle + i * 8, triangle + i * 8 + 8));
    // The source contains coincident mirrored faces. The runtime is already two-sided.
    // Match both position and UV, independent of winding, with a 0.0001-item-unit tolerance.
    const key = rows.map(row => row.slice(0, 5).map(n => n.toFixed(4)).join(',')).sort().join('|');
    if (uniqueTriangles.has(key)) continue;
    uniqueTriangles.add(key);
    for (const row of rows) {
        for (let i = 0; i < 3; i++) row[i] = +row[i].toFixed(4);
        const key = row.map(n => n.toFixed(6)).join(',');
        if (!uniqueVertices.has(key)) {
            uniqueVertices.set(key, vertices.length / 8);
            vertices.push(...row);
        }
        indices.push(uniqueVertices.get(key));
    }
}
const data = Float32Array.from(vertices), positions = new Float32Array(vertices.length / 8 * 3);
const attributes = new Float32Array(vertices.length / 8 * 5);
for (let vertex = 0; vertex < vertices.length / 8; vertex++) {
    positions.set(data.subarray(vertex * 8, vertex * 8 + 3), vertex * 3);
    attributes.set(data.subarray(vertex * 8 + 3, vertex * 8 + 8), vertex * 5);
}
// The index-only simplifier retains original vertices, UVs and normals, and protects their seams.
// No permissive seams or component pruning: small suspension lines remain attached.
const [reduced, error] = MeshoptSimplifier.simplifyWithAttributes(Uint32Array.from(indices), positions, 3,
        attributes, 5, [16, 16, .2, .2, .2], null, 6_000 * 3, .01, []);
if (reduced.length > 6_000 * 3) throw new Error('Canopy could not reach its explicit 6000-triangle budget');
const preview = { ...source, vertices: Array.from(reduced).flatMap(i => Array.from(data.subarray(i * 8, i * 8 + 8))) };
const runtime = { format_version: 1, vertex_format: source.vertex_format, coordinate_units: 'blocks',
    texture: 'tacz_pubg:textures/entity/parachute_canopy.png', vertices: [] };
// Original longest dimension is Z; rotate +90 degrees so a 6-metre span crosses the player's X axis.
// The bottom of the authored harness becomes the origin, positioned at shoulder height by Java.
const scale = 6 / 14;
for (const i of reduced) {
    const at = i * 8, normalLength = Math.hypot(data[at + 5], data[at + 6], data[at + 7]);
    const row = [(data[at + 2] - 8) * scale, (data[at + 1] - 1) * scale, -(data[at] - 8) * scale,
        data[at + 3], data[at + 4], data[at + 7] / normalLength,
        data[at + 6] / normalLength, -data[at + 5] / normalLength];
    if (!row.every(Number.isFinite)) throw new Error('Invalid canopy vertex');
    runtime.vertices.push(...row.map(value => +value.toFixed(6)));
}
mkdirSync(output, { recursive: true });
for (const folder of ['meshes/entity', 'textures/entity']) mkdirSync(join(assets, folder), { recursive: true });
writeFileSync(join(output, 'model.json'), JSON.stringify(runtime) + '\n');
writeFileSync(join(output, 'preview_model.json'), JSON.stringify(preview) + '\n');
copyFileSync(sourceTexture, join(output, 'texture.png'));
copyFileSync(join(output, 'model.json'), join(assets, 'meshes/entity/parachute_canopy.json'));
copyFileSync(sourceTexture, join(assets, 'textures/entity/parachute_canopy.png'));
const report = { source_item: entry.id, source_mesh: inputPath, source_sha256: hash(inputPath),
    source_texture_sha256: hash(sourceTexture), source_triangles: source.vertices.length / 24,
    deduplicated_triangles: indices.length / 3, exported_triangles: reduced.length / 3,
    method: 'meshoptimizer 1.3.0 simplifyWithAttributes; original UV and normal seams retained',
    simplifier_relative_error: error, maximum_position_quantization_metres: Math.sqrt(3) * .00005 * scale,
    target_triangles: 6000, original_bitmap_pixels_preserved: true,
    span_metres: 6, harness_origin: [0, 0, 0], rotation_degrees_y: 90,
    model_sha256: hash(join(output, 'model.json')), texture_sha256: hash(join(output, 'texture.png')),
    validation: 'Offline mesh/resource checks and software preview; no in-game validation' };
writeFileSync(join(output, 'export.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
