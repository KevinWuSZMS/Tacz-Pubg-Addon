"""Batch-export static vehicle scenes, keeping every source C4D assembled.

This offline agent command never installs assets into the mod or edits source
files. It reuses the common importer, texture archiver, both style converters
and item preview renderer. The Pixel grid is fixed: budget overflow is recorded
instead of reducing the requested resolution.

Example::

    python vehicle_batch.py --source ../20180214152952/VehicleS \
        --output output/vehicles --workers 2
"""
from __future__ import annotations

import argparse
from concurrent.futures import ProcessPoolExecutor, as_completed
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import traceback

from PIL import Image

from batch_extract import preview, stage_textures
from pixel_hardcore import convert
from playful_world import export
from workflow_common import digest, load_scene, normalize_scene, read_source, source_stats, write_json


def vehicle_id(relative: Path) -> str:
    """Source-relative paths define identities, including duplicate leaf names."""
    key = relative.with_suffix("").as_posix()
    slug = re.sub(r"[^a-z0-9]+", "_", key.casefold()).strip("_")
    # Retain a digest because punctuation and non-ASCII names may share a slug.
    suffix = hashlib.sha256(key.encode("utf-8")).hexdigest()[:8]
    return f"vehicle_{slug or 'source'}_{suffix}"


def texture_remaps(entries):
    """Parse explicit filename=bitmap assignments; never infer extensions."""
    result = {}
    names = set()
    for entry in entries:
        name, separator, value = entry.partition("=")
        if not separator or not name or not value or Path(name.replace("\\", "/")).name != name:
            raise ValueError("texture-remap must be ORIGINAL_BASENAME=EXISTING_BITMAP_PATH")
        if name.casefold() in names:
            raise ValueError(f"Duplicate texture-remap filename: {name}")
        path = Path(value).resolve(strict=True)
        if not path.is_file():
            raise ValueError(f"texture-remap destination is not a file: {path}")
        names.add(name.casefold())
        result[name] = str(path)
    return result


def fixed_pixel(scene_path, output, *, name, namespace, resolution, face_budget):
    """An over-budget archive must still use the explicitly chosen grid."""
    report = convert(scene_path, output, name=name, namespace=namespace,
                     resolution=resolution, min_resolution=resolution,
                     max_elements=face_budget)
    if report["actual_resolution"] != resolution:
        raise RuntimeError("Pixel converter changed the fixed vehicle resolution")
    report.update(runtime_ready=bool(report["target_met"]),
                  archive_only=not report["target_met"],
                  resolution_policy="fixed; face-budget overflow never reduces resolution")
    write_json(output / "report.json", report, pretty=True)
    return report


def fingerprint() -> str:
    folder = Path(__file__).resolve().parent
    names = ("vehicle_batch.py", "batch_extract.py", "workflow_common.py",
             "c4d_reader.py", "c4d_materials.py", "playful_world.py",
             "pixel_hardcore.py", "render_preview.py")
    return hashlib.sha256("".join(digest(folder / name) for name in names).encode()).hexdigest()


def warnings_for(scene):
    warnings = []
    used = {int(mesh["material"]) for mesh in scene["meshes"]}
    for index, material in enumerate(scene["materials"]):
        if index not in used:
            continue
        for key in ("warnings", "diagnostics", "extraction_warnings"):
            for warning in material.get(key, []):
                warnings.append({"material": material.get("name", ""), "warning": warning})
        if material.get("texture_fallback"):
            warnings.append({"material": material.get("name", ""),
                             "warning": "Explicit material-color or neutral bitmap fallback"})
    for obj in scene.get("metadata", {}).get("objects", []):
        for warning in obj.get("warnings", []):
            warnings.append({"object": obj.get("name", ""), "warning": warning})
    return warnings


def _portable(scene, canonical):
    scene = json.loads(json.dumps(scene))
    for material in scene["materials"]:
        for key, value in list(material.items()):
            if key.endswith("_texture") and isinstance(value, str) and Path(value).is_file():
                material[key] = os.path.relpath(value, canonical.parent).replace("\\", "/")
    return scene


def _job(source_string, root_string, output_string, ordinal, options):
    source, root, output = Path(source_string), Path(root_string), Path(output_string)
    relative = source.relative_to(root)
    stable_id = vehicle_id(relative)
    directory = f"{ordinal:03d}_{source.stem}"
    folder = output / directory
    receipt = folder / "item.json"
    source_hash = digest(source)
    if options["resume"] and not options.get("only_index") and receipt.is_file():
        previous = json.loads(receipt.read_text(encoding="utf-8"))
        if (previous.get("success") and previous.get("source_sha256") == source_hash
                and previous.get("code_fingerprint") == options["code_fingerprint"]
                and previous.get("pixel_resolution") == options["pixel_resolution"]
                and previous.get("preview_panel_size") == options["preview_size"]
                and previous.get("texture_roots", []) == options.get("texture_root", [])
                and previous.get("texture_overrides", {}) == options.get("texture_overrides", {})):
            required = ("scene", "playful", "pixel", "preview")
            if all(previous.get(key) and (output / previous[key]).is_file() for key in required):
                return {**previous, "reused": True}
    result = {"id": stable_id, "source_index": ordinal, "source": str(source),
              "source_relative": relative.as_posix(), "source_sha256": source_hash,
              "code_fingerprint": options["code_fingerprint"], "directory": directory,
              "assembly_policy": "one complete source file; no splitting wheels or other parts",
              "texture_roots": options.get("texture_root", []),
              "texture_overrides": options.get("texture_overrides", {}),
              "installed_into_mod": False, "success": False, "errors": []}
    try:
        scene = read_source(source, texture_roots=tuple(Path(path) for path in options.get("texture_root", [])),
                            texture_overrides=options.get("texture_overrides", {}))
        stage_textures(scene, output)
        write_json(folder / "scene.raw.json", scene)
        normalized = normalize_scene(scene)
        canonical = folder / "scene.json"
        write_json(canonical, _portable(normalized, canonical))
        normalized = load_scene(canonical)
        stats = source_stats(normalized)
        triangles = stats["triangles"]
        detailed = export(normalized, folder / "playful", stable_id,
                          options["namespace"], max(triangles, options["triangle_budget"]))
        with Image.open(folder / "playful" / "texture.png") as texture:
            texture_size = list(texture.size)
        reasons = []
        if triangles > options["triangle_budget"]:
            reasons.append("more_than_runtime_triangle_budget")
        if texture_size[0] * texture_size[1] > options["texture_budget"]:
            reasons.append("atlas_exceeds_runtime_texture_budget")
        detailed.update(runtime_ready=not reasons, archive_only=bool(reasons),
                        runtime_triangle_budget=options["triangle_budget"],
                        runtime_texture_budget_pixels=options["texture_budget"],
                        runtime_not_ready_reasons=reasons, texture_size=texture_size)
        write_json(folder / "playful" / "report.json", detailed, pretty=True)
        pixel = fixed_pixel(canonical, folder / "pixel", name=stable_id,
                            namespace=options["namespace"],
                            resolution=options["pixel_resolution"], face_budget=options["face_budget"])
        warnings = warnings_for(normalized)
        image_path = folder / "preview.png"
        preview(folder / "playful" / "model.json", folder / "playful" / "texture.png",
                folder / "pixel" / "model.json", folder / "pixel" / "texture.png",
                image_path, f"{ordinal:03d} {source.stem}", size=options["preview_size"], warnings=warnings)
        result.update(triangles=triangles, meshes=stats["meshes"], materials=stats["materials"],
                      source_objects=len(normalized.get("metadata", {}).get("objects", [])),
                      source_object_names=[obj["name"] for obj in normalized.get("metadata", {}).get("objects", [])],
                      runtime_mesh_ready=not reasons, runtime_not_ready_reasons=reasons,
                      texture_size=texture_size, pixel_resolution=pixel["actual_resolution"],
                      pixel_quads=pixel["rendered_quads"], runtime_pixel_ready=pixel["target_met"],
                      pixel_budget_overflow=not pixel["target_met"],
                      preview_panel_size=options["preview_size"], material_warnings=warnings,
                      source_metadata=normalized.get("metadata", {}))
        for key, path in (("scene", canonical), ("playful", folder / "playful" / "model.json"),
                          ("pixel", folder / "pixel" / "model.json"), ("preview", image_path)):
            result[key] = path.relative_to(output).as_posix()
        result["source_unchanged"] = digest(source) == source_hash
        if not result["source_unchanged"]:
            raise RuntimeError("Source file changed during conversion")
        result["success"] = True
    except Exception as error:
        result["errors"].append(f"{type(error).__name__}: {error}")
        result["traceback"] = traceback.format_exc()
    write_json(receipt, result, pretty=True)
    return result


def write_catalog(output, results):
    with (output / "catalog.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["序号", "源文件", "三角形", "像素分辨率", "像素面", "萌趣预算通过", "像素预算通过", "预览", "萌趣模型", "像素模型", "错误"])
        for item in results:
            writer.writerow([item["source_index"], item["source_relative"], item.get("triangles"),
                             item.get("pixel_resolution"), item.get("pixel_quads"),
                             item.get("runtime_mesh_ready"), item.get("runtime_pixel_ready"),
                             item.get("preview"), item.get("playful"), item.get("pixel"),
                             "; ".join(item.get("errors", []))])


def overview(output, results, panel_size):
    """Compose existing two-style previews without another rendering pass."""
    available = [item for item in results if item.get("preview")]
    if not available:
        return None
    columns = min(4, len(available))
    width, height = panel_size * 2, panel_size + 86
    rows = (len(available) + columns - 1) // columns
    canvas = Image.new("RGB", (columns * width, rows * height), "white")
    for index, item in enumerate(available):
        with Image.open(output / item["preview"]) as image:
            canvas.paste(image.convert("RGB"), (index % columns * width, index // columns * height))
    path = output / "overview.png"
    canvas.save(path)
    return path.name


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--pixel-resolution", type=int, default=36,
                        help="Fixed longest-axis voxel count; never automatically decreases")
    parser.add_argument("--face-budget", type=int, default=1800)
    parser.add_argument("--triangle-budget", type=int, default=6000)
    parser.add_argument("--texture-budget", type=int, default=2048 ** 2)
    parser.add_argument("--preview-size", type=int, default=256)
    parser.add_argument("--namespace", default="pubg_vehicles")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--only-index", nargs="+", type=int,
                        help="Regenerate only these one-based source indices; retain all other receipts")
    parser.add_argument("--texture-root", action="append", type=Path, default=[],
                        help="Explicit extra directory for exact-basename missing source bitmap links")
    parser.add_argument("--texture-remap", action="append", default=[], metavar="ORIGINAL=PATH",
                        help="Explicit original basename to actual bitmap path; recorded in source provenance")
    args = parser.parse_args()
    if not 8 <= args.pixel_resolution <= 96 or args.workers < 1 or args.preview_size < 128:
        parser.error("Require 8 <= pixel-resolution <= 96, workers >= 1, preview-size >= 128")
    if args.face_budget < 6 or args.triangle_budget < 1 or args.texture_budget < 1:
        parser.error("Budgets must be positive; face-budget must be at least 6")
    root, output = args.source.resolve(strict=True), args.output.resolve()
    if output == root or root in output.parents:
        parser.error("Output must be outside the read-only source tree")
    sources = sorted((source.resolve() for source in root.rglob("*") if source.suffix.lower() == ".c4d"),
                     key=lambda path: path.relative_to(root).as_posix().casefold())
    if not sources:
        parser.error("No C4D files in source tree")
    output.mkdir(parents=True, exist_ok=True)
    options = vars(args).copy()
    try:
        options["texture_overrides"] = texture_remaps(args.texture_remap)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    options["texture_root"] = [str(path.resolve(strict=True)) for path in args.texture_root]
    if any(not Path(path).is_dir() for path in options["texture_root"]):
        parser.error("Every texture-root must be a directory")
    options["code_fingerprint"] = fingerprint()
    completed = {}
    selected = set(args.only_index or range(1, len(sources) + 1))
    if not selected.issubset(range(1, len(sources) + 1)):
        parser.error("only-index must refer to existing one-based source indices")
    # Selective repair deliberately reuses all unselected successful outputs,
    # even after converter changes, without dispatching those models again.
    for ordinal, source in enumerate(sources, 1):
        if ordinal in selected:
            continue
        receipt = output / f"{ordinal:03d}_{source.stem}" / "item.json"
        if not receipt.is_file():
            parser.error(f"Unselected source {ordinal} has no existing receipt; run the complete batch first")
        previous = json.loads(receipt.read_text(encoding="utf-8"))
        if previous.get("source_sha256") != digest(source):
            parser.error(f"Unselected source {ordinal} changed; explicitly include it in only-index")
        if previous.get("success"):
            if previous.get("pixel_resolution") != args.pixel_resolution:
                parser.error(f"Unselected source {ordinal} has a different Pixel resolution; include it in only-index")
            if any(not previous.get(key) or not (output / previous[key]).is_file()
                   for key in ("scene", "playful", "pixel", "preview")):
                parser.error(f"Unselected source {ordinal} has missing outputs; include it in only-index")
        completed[ordinal] = previous
    with ProcessPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(_job, str(source), str(root), str(output), ordinal, options): ordinal
                   for ordinal, source in enumerate(sources, 1) if ordinal in selected}
        for future in as_completed(futures):
            ordinal = futures[future]
            try:
                result = future.result()
            except Exception as error:
                source = sources[ordinal - 1]
                result = {"source_index": ordinal, "source_relative": source.relative_to(root).as_posix(),
                          "success": False, "errors": [f"Worker failure: {type(error).__name__}: {error}"]}
            completed[ordinal] = result
            print(json.dumps({"completed": len(completed), "total": len(sources),
                              "source": result["source_relative"], "success": result["success"],
                              "pixel_resolution": result.get("pixel_resolution"),
                              "triangles": result.get("triangles"), "pixel_quads": result.get("pixel_quads"),
                              "errors": result.get("errors", [])}, ensure_ascii=False), flush=True)
            write_json(output / "progress.json", {"completed": len(completed), "total": len(sources),
                                                  "items": [completed[key] for key in sorted(completed)]}, pretty=True)
    results = [completed[key] for key in sorted(completed)]
    pack = {"schema": "agent-vehicle-pack/1", "source": str(root),
            "source_count": len(sources), "successful_sources": sum(item["success"] for item in results),
            "item_count": len(results), "preview_count": sum(bool(item.get("preview")) for item in results),
            "pixel_resolution": args.pixel_resolution, "resolution_policy": "fixed; never automatically decreases",
            "assembly_policy": "one complete source file per item", "installed_into_mod": False,
            "runtime_mesh_ready": sum(bool(item.get("runtime_mesh_ready")) for item in results),
            "runtime_pixel_ready": sum(bool(item.get("runtime_pixel_ready")) for item in results),
            "last_run_options": {key: value for key, value in options.items() if key not in ("source", "output")},
            "last_run_source_indices": sorted(selected),
            "retained_source_indices": [index for index in range(1, len(sources) + 1) if index not in selected],
            "texture_root_scope": "last-run source indices only; retained items keep their original provenance",
            "items": results, "catalog": "catalog.csv",
            "overview": overview(output, results, args.preview_size)}
    write_json(output / "pack.json", pack, pretty=True)
    write_catalog(output, results)
    print(json.dumps({key: pack[key] for key in ("source_count", "successful_sources", "preview_count", "pixel_resolution", "runtime_mesh_ready", "runtime_pixel_ready")}, ensure_ascii=False))
    if pack["successful_sources"] != len(sources):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
