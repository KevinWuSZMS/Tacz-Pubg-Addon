# Agent model workflows

Headless, offline Python tools for **C4D + bitmap textures → existing Minecraft item renderers**. This folder is outside `neoforge-1.21.1`. The mod never imports these scripts, exposes no conversion GUI/command, and needs no Python/C4D runtime. Only the finished model JSON and PNG files enter the addon resources.

## Entire PUBG82 pack in one command

```powershell
$py = 'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $py model-workflows/batch_extract.py --source-dir '82个绝地求生PUBG吃鸡游戏模型' --output model-workflows/output/pack_82 --workers 4
```

One batch reads all 82 source scenes, stages referenced textures, separates whole items, exports both styles, and renders every item using the existing `render_preview` functions. It does not call an image model during extraction. The verified source-object grouping profile yields **178 items**; sources 3,14,15,47,57,79 retain their assembled parts. Dense source21/43 layouts stay separate. `--retry-failed --force-source 43` can reuse successful outputs while repairing selected failures; keep extraction parameters unchanged when using that repair option.

Only after every extraction and preview is complete, ask one `gpt-6-luna` agent to identify the **82 original JPGs**, using small numbered contact sheets in one simple pass. Save its result as `{"model":"gpt-6-luna","names":[{"source_number":1,"name_zh":"..."},...]}`. Then apply those 82 source-folder names while retaining numerical prefixes:

```powershell
& $py model-workflows/batch_extract.py --output model-workflows/output/pack_82 --apply-names model-workflows/output/pack_82/source_names_luna.json
```

Final output: **82 named source folders**, e.g. `017_医疗装备箱`, **178 independent item exports/previews**, `previews/` with numbered Chinese filenames, `catalog.csv` and `pack.json`. Source C4D/JPG/TGA files remain unchanged. The two model/PNG pairs are self-contained; canonical item scenes reference archived `_textures/` relatively. Stable internal item IDs remain unchanged by folder naming. Reruns reuse the named source directories and may reapply the existing names file without any new model call.

Source geometry is archived in full. Meshes above 6,000 triangles or whose atlas exceeds 2048² pixels are marked **archive-only / requires optimization**; this is extraction, not a mass installation into Minecraft. Pixel output now uses **fixed density36**, with a 1,800-visible-face runtime target. Exceeding that target is marked archive-only and **never silently reduces resolution**. Some original source materials lack assignments; source43 also has four broken color-bitmap links, and source65 has a missing normal map. Declared/neutral material-color fallbacks and unapplied material effects are explicit in each source/item report; they are not invented replacement textures.

## Replace existing Pixel exports only

```powershell
& $py model-workflows/regenerate_pixels.py --pack model-workflows/output/pack_82 --resolution 36 --face-budget 1800 --workers 4
```

Reuses all 178 archived normalized scenes, regenerates only Pixel models/atlases and their previews, and updates item/source/pack catalogs. Existing names and Playful assets remain intact, with hashes checked for every scene and Playful output. Prior Pixel assets, previews and metadata are backed up under `backups/pixel_rebuild_<timestamp>/`. Every output report records requested, minimum and actual resolution; all three are36. Budget overflow remains explicit.

## VehicleS: both styles and previews

```powershell
& $py model-workflows/vehicle_batch.py --source 20180214152952/VehicleS --output model-workflows/output/vehicles --pixel-resolution 36 --workers 2 --resume
```

Recursively processes all **27 C4D scenes in 20 source folders**. Each source scene stays assembled as one complete vehicle, including its material parts and wheels. Each numbered output folder contains a canonical scene, `playful/`, `pixel/`, reports and a two-style `preview.png`; `overview.png` and `catalog.csv` index the entire pack. Pixel resolution is fixed36 and cannot fall to a lower value to meet the face target. Large source meshes remain complete offline archives and are marked against the 6,000-triangle / 2048²-texture runtime budget. The command never installs into the mod or modifies source C4D/bitmaps.

For the supplied `C130_Set`, the stored interior color/normal filenames end in `.jpg`, while the companion PUBG82 pack contains the corresponding original `.tga` maps. The completed output uses explicit mappings, preserved with filenames, paths and hashes in its material provenance. No automatic extension guessing is enabled. To repair only that plane and the two trucks in an existing batch:

```powershell
& $py model-workflows/vehicle_batch.py --source 20180214152952/VehicleS --output model-workflows/output/vehicles --pixel-resolution 36 --workers 2 --only-index 8 17 18 --texture-root '82个绝地求生PUBG吃鸡游戏模型/tex' --texture-remap 'C130_interior_D.jpg=82个绝地求生PUBG吃鸡游戏模型/tex/C130_interior_D.tga' --texture-remap 'C130_interior_N.jpg=82个绝地求生PUBG吃鸡游戏模型/tex/C130_interior_N.tga'
```

`--only-index` dispatches only the listed one-based source indices, retains the other completed outputs and rebuilds the complete catalog/overview. For a fresh full conversion with these texture mappings, use the same command without `--only-index 8 17 18`. Optional `--texture-root` searches only missing local filenames, requires exact basenames and rejects conflicting matches; explicit `--texture-remap` takes priority and records the intentional replacement.

## Agent quick reference: item to image

Use **`export_items.py`** to read a source once, select complete source objects, independently center/scale each item, export both styles, validate, and render. It calls the existing **`playful_world.export` → `pixel_hardcore.convert` → `render_preview.comparison` / `item_sheet`** functions. Do not create another C4D parser or image renderer.

```powershell
$py = 'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
# Inventory only: mesh index, name, material and bounds. No conversion.
& $py model-workflows/export_items.py --source '82个绝地求生PUBG吃鸡游戏模型/绝地求生 (25).c4d' --output model-workflows/output/grips_25 --inspect-only
# This file contains three separate whole-item objects.
& $py model-workflows/export_items.py --source '82个绝地求生PUBG吃鸡游戏模型/绝地求生 (25).c4d' --output model-workflows/output/grips_25 --item vector_vertical_grip=0 --item vertical_grip=1 --item angled_foregrip=2
```

`items.json` maps each **item ID → selected source objects → normalized scene → both model/texture pairs → comparison PNG**. `overview.png` is the compact two-style sheet. `inventory.json` records raw scene bounds. No mod resource is installed. After confirming that each source object is a complete item, `--all-objects` generates one item per object using sanitized source names. To group multiple object parts into one item, use `--item item_id=0,1`. Whole-object selection keeps disconnected screws or rings with their parent item; topology islands are not inventory items.

Both single-item workflows also accept repeated **`--mesh INDEX_OR_EXACT_NAME`**. Selection happens **before** normalization; unused materials are removed and material indices remapped. Without selection these original entry points retain their documented single combined-item behavior. A multi-item source therefore needs explicit selection or the batch entry above.

25 verification: `Vector_VerGrip_Setting` = 1,110 triangles / 343 pixel quads, `Grip_01` = 898 / 245, `AngledForeGrip` = 2,128 / 220 (pixel resolution 28). All **4,136** source triangles occur exactly once across the three items. This case has three distinct named source objects with clear world-space gaps. Geometry packed into one source object is not automatically split by empty space; it needs explicit source grouping before this whole-object workflow.

## Runtime

Python 3.10+ with NumPy and Pillow (`requirements.txt`). The workspace's bundled interpreter is currently:

```powershell
$py = 'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
```

Do not assume the Windows `python` alias has those packages. Dependencies can also be installed in a separate virtual environment with `python -m pip install -r model-workflows/requirements.txt`.

## Two workflows

### Playful World / 萌趣世界

```powershell
& $py model-workflows/playful_world.py --source '82个绝地求生PUBG吃鸡游戏模型/绝地求生 (17).c4d' --output model-workflows/output/medical_case_17/playful --name med_kit --namespace tacz_pubg --triangle-budget 6000
```

- Reads real source geometry and preserves its triangle topology, UVs, and Phong edge breaks/smooth normals.
- Uniformly fits the model into a 14-unit maximum dimension, centered at X/Z=8 with Y minimum=1. `--rotate X Y Z` is an optional explicit orientation correction.
- Writes the existing `x,y,z,u,v,nx,ny,nz` triangle JSON contract and lossless PNG. A single original bitmap is neither resized nor relit. Multiple materials use a padded non-resampled atlas that preserves continuous repeated UVs. If whole-repeat tiles would exceed the atlas limit, an exact used-texel crop retains the original pixels and periodic filtering gutters without changing topology or sampling density.
- Default **6,000 triangle** shipping budget. Over-budget sources fail with an actionable message; they are not silently simplified or shipped at cinematic density. Protect UV seams when explicitly simplifying in a source application, then inspect and re-export.
- Existing runtime supports base color and vertex normals, **not** normal-map or roughness/metalness shading. Those source channels are recorded with hashes and remain in the original pack. No fake PBR baking is applied.

### Pixel Hardcore / 像素硬核

Use the *same normalized scene* to keep the two styles aligned:

```powershell
& $py model-workflows/pixel_hardcore.py --scene model-workflows/output/medical_case_17/playful/source/scene.json --output model-workflows/output/medical_case_17/pixel --name med_kit --namespace tacz_pubg --resolution 36 --max-elements 1800
```

Or run independently with `--source '...c4d'` instead of `--scene`. Both entry points accept `--max-size` and `--rotate` for source inputs. `--scene` means an already prepared canonical scene and does not normalize a second time.

- Conservatively samples the real source triangles onto a voxel shell. It does **not** fill the open medical box or replace it with a manually authored approximation.
- Removes unreachable hidden internal shell faces and greedily merges exposed coplanar faces. Each emitted vanilla `elements` entry is one explicitly textured quad, not a six-face miniature cube. Source UV colors are retained in atlas patches.
- Defaults to **36 voxels on the longest dimension**, fixed throughout conversion. The **1,800 exposed-quad** target reports cost; it does not silently lower detail. Only an explicit `--min-resolution` below the requested resolution enables adaptive downsampling. This is a deliberately stylized approximation; thin features below the selected grid resolution can change.
- Retains the exact standard `elements` and adds a `tacz_pubg:seamless_pixel` baked-geometry payload. The loader caches conforming quads once on resource loading; no conversion or tessellation runs per frame. A single PNG atlas and flat face lighting are retained. Generic Minecraft clients without this addon's loader cannot load the new payload.
- Greedy surfaces can otherwise form T junctions: one long face edge meets another face's intermediate vertex. Their floating-point raster cracks become bright specks in entity-outline postprocessing. Only affected faces are triangulated with all existing boundary points, and adjacent triangles are paired back into quads. No shape, coordinate, original UV map, sampled pixels, density or display transform is changed. The cutout render type also avoids the inappropriate fallback alpha-blended layer.
- Reports distinguish original authoring quads from actual GPU quads, valid triangles, degenerate triangle padding and repair overhead. Original face budgets remain explicit; they are not silently raised or met by lowering resolution.
- `--display-template existing-model.json` retains an existing display transform; otherwise a built-in item display preset is used.

For medical case 17 the selected resolution is **36**, yielding **736 original greedy quads**, **1,114 conforming GPU quads** after seam repair and an unchanged **256×256** atlas. Resolution 28 yields 421 original greedy quads but closes several small tool holes; 40 yields 927 original greedy quads without enough extra benefit to justify the increase. The 5,742-triangle Playful export is 46.67% smaller in triangle count than the replaced 10,766-triangle model; its 512×512 color bitmap is preserved pixel-for-pixel. These counts describe geometry, not measured FPS.

The installed 20 converted item models can be audited without modifying them, or repaired with byte-verified backups:

```powershell
& $py model-workflows/repair_pixel_seams.py
& $py model-workflows/repair_pixel_seams.py --install
& $py -m unittest discover -s model-workflows -p 'test_pixel_surface_seams.py'
```

`output/pixel_seam_repair/installation.json` records the actual per-item cost, source element/display hashes and protected PNG/detailed/canopy assets. For the current 20 items, the repair changes 26,527 legacy quads into 37,599 GPU quads (+41.739%), representing 69,784 valid triangles plus 5,414 zero-area triangle pads. The offline audit checks every rendered edge, including new internal diagonals, for remaining T junctions and proves source area, positions, coverage, normals and affine UV equivalence. This is offline evidence; in-game moving-camera and shader validation remain separate.

## Review and installation

The selected existing supplies are now installed in both styles. See `../docs/物资源模型接入.md` and `output/supplies_integration/installation.json` for the exact source-to-item mapping, explicit vest face allowances, backups and validation. `install_supplies.py` rebinds only resource names before reusing `install_assets.install`; it never changes the extracted pack. The recovered local level-two vest is in `output/armor_integration/vest_2`. The deployed canopy has separate mesh and density36 quad exports, installed by `export_parachute_canopy.mjs` and `export_parachute_canopy_pixel.py`.

Both outputs contain `model.json`, `texture.png`, and `report.json`. Playful's `source/scene.json` is a canonical intermediate, not a Minecraft resource. Reports identify the original file/hash, coordinate transform, actual geometry count, material policy, and exported hashes.

Render the **actual exported geometry and textures** without opening any GUI:

```powershell
& $py model-workflows/render_preview.py --mesh model-workflows/output/medical_case_17/playful/model.json --mesh-texture model-workflows/output/medical_case_17/playful/texture.png --pixel model-workflows/output/medical_case_17/pixel/model.json --pixel-texture model-workflows/output/medical_case_17/pixel/texture.png --output model-workflows/output/medical_case_17/comparison.png
& $py model-workflows/validate_outputs.py --mesh model-workflows/output/medical_case_17/playful/model.json --mesh-texture model-workflows/output/medical_case_17/playful/texture.png --pixel model-workflows/output/medical_case_17/pixel/model.json --pixel-texture model-workflows/output/medical_case_17/pixel/texture.png --source-tga '82个绝地求生PUBG吃鸡游戏模型/tex/T_SM_DoctorSCase_D.tga' --triangle-budget 6000 --face-budget 1800 --output model-workflows/output/medical_case_17/validation.json
```

These are software previews and offline resource checks, not evidence of in-game performance or shader-pack equivalence.

Only after checking the result, explicitly copy the two finished exports into the selected existing addon source resources:

```powershell
& $py model-workflows/install_assets.py --playful model-workflows/output/medical_case_17/playful --pixel model-workflows/output/medical_case_17/pixel --assets-root neoforge-1.21.1/src/main/resources/assets/tacz_pubg --name med_kit
```

This installer preserves existing display transforms, backs up previous resources under this folder's `backups/`, and verifies copied hashes. It changes exactly these four asset paths:

```text
meshes/item/med_kit.json
textures/item/detail/med_kit.png
models/item/med_kit.json
textures/item/med_kit.png
```

The normal addon build packages those resources. Do not package this workflow directory, source `.c4d`, shared `tex/`, canonical scenes, reports, or backups in the mod JAR. The old procedural model generators predate this imported asset; rerunning them for `med_kit` would recreate the previous model. Use this workflow as the source of truth for the replacement.

## Input scope and agent rules

- `c4d_reader.py` is a strict reader for the static triangulated legacy `C4D6` HyperFile structures verified in these supplied packs: **R19.068 PUBG82**, **R19.024 first-aid pack**, and **R14.042 VehicleS/CharacterS**. The supplied `急救包/绝地求生 (22).c4d` proves the R19.024 variant with 825 points, 998 triangles, two UV channels and linked original D/N/RM bitmaps; `test_c4d_first_aid.py` guards those exact source records. It reads the real polygon/point/UVW arrays, material selections and object transforms. It is **not** a universal Cinema 4D scene evaluator.
- The supplied pack now extracts **82/82** scenes: **191 stored polygon objects**, grouped into **178 independent items**, and **1,208,161 drawable triangles**. The reader supports actual material-channel links, hierarchy, per-polygon material selections and multiple UV assignments. Seven zero-area records are omitted and documented; sky/environment objects are excluded, and static saved meshes are retained without evaluating skinning. `output/geometry_82_audit.json` records the geometry audit. The old `output/c4d-pack-audit.json` is the historical strict-reader baseline, not current coverage.
- Unsupported generators, animation/deformation, material selections, projections, frozen transforms, or unknown stream features must fail rather than produce a plausible but incorrect model. Follow the exact error to export an evaluated, triangulated OBJ + MTL + bitmaps from an appropriate source application. `--source exported.obj` uses the same two workflows.
- `obj_reader.py` supports textured static triangles/quads and explicit normals; triangulate complex n-gons in the source tool. MTL diffuse textures must be real bitmaps, not procedural shaders.
- Never reconstruct the requested source mesh from the JPG preview and call it a conversion. Do not change source C4D/TGA files. Do not silently discard a required material or UV mapping.
- Check silhouette, open cavities, tool positions, front/back texture orientation, surface normals, and placement for both styles. Record actual quads/triangles; file size alone is not a render-cost measurement.
- Prefer retaining already modest source geometry over unnecessary decimation. A budget is a guardrail, not a command to use every available triangle.

Canonical scene schema:

```text
schema: "agent-model-scene/1"
uv_origin: "top_left" (default) or "bottom_left"
materials: [{ name, base_color_texture, normal_texture?, roughness_metallic_texture? }]
meshes: [{ name, material: material_index, vertices: flat [x,y,z,u,v,nx,ny,nz,...] }]
metadata: { source, source_sha256, extraction_method, ... }
```

Each consecutive three vertices form one triangle. Paths are resolved relative to the canonical file when not absolute. Ordinary model space is Y-up, texture V=0 at the image top.

Raw C4D scenes declare `metadata.handedness="left"`. Normalization reflects Z for both points and normals and reverses triangle winding (with the matching UV corners), then marks the output `"right"`. This prevents mirrored tool layouts and repeated conversion. OBJ input assumes the usual right-handed export coordinates.

An official Cineware SDK was downloaded only to verify the legacy file constants. It is retained under `.tools/` because cleanup was automatically rejected with `blocked by policy`. The Python reader is independent of that SDK; it is never packaged into the addon or loaded by Minecraft.
