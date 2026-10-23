"""Explicit source-object -> independent item -> two exports -> preview mapping.

Reads C4D/OBJ once and reuses the existing exporters, validator and image renderer.
No mod resources are installed. Objects are not split by topology islands: an
object's disconnected screws and other small components stay with that item.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re

from workflow_common import (read_source, load_scene, select_meshes, normalize_scene,
                             source_stats, write_json, digest)
from playful_world import export
from pixel_hardcore import convert
from render_preview import comparison, item_sheet
from validate_outputs import validate


def item_id(name, index):
    value = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
    return value or f"mesh_{index}"


def run(*, source=None, scene_path=None, output: Path, all_objects=False, items=None,
        namespace="tacz_pubg", max_size=14., rotate=(0,0,0), triangle_budget=6000,
        pixel_resolution=36, face_budget=1800, preview_size=384, inspect_only=False):
    if (source is None) == (scene_path is None):
        raise ValueError("Supply --source or --scene")
    scene = read_source(Path(source)) if source is not None else load_scene(Path(scene_path))
    inventory = [{"index": i, "name": m.get("name", ""), "material_index": m["material"],
                  **source_stats({"meshes":[m], "materials":scene["materials"]})}
                 for i,m in enumerate(scene["meshes"])]
    output.mkdir(parents=True, exist_ok=True)
    write_json(output / "inventory.json", {"source":scene.get("metadata",{}), "objects":inventory}, pretty=True)
    if inspect_only:
        return {"objects": inventory}
    if bool(all_objects) == bool(items):
        raise ValueError("Choose --all-objects or explicit --item ID=SELECTOR[,SELECTOR...]")
    selections = []
    if all_objects:
        selections = [(item_id(m.get("name", ""),i),[i]) for i,m in enumerate(scene["meshes"])]
    else:
        for spec in items:
            name, sep, selectors = spec.partition("=")
            if not sep or not selectors:
                raise ValueError("Expected --item ID=SELECTOR[,SELECTOR...]")
            selections.append((name,selectors.split(",")))
    ids = [name for name,_ in selections]
    if len(set(ids)) != len(ids):
        raise ValueError("Duplicate item IDs; use explicit --item aliases")
    selected = []
    used = set()
    for name, selectors in selections:
        if not re.fullmatch(r"[a-z0-9_]+",name):
            raise ValueError(f"Invalid item ID: {name}")
        part = select_meshes(scene, selectors)
        indices = set(part["metadata"]["selection"]["mesh_indices"])
        if used & indices:
            raise ValueError("Source mesh selected for more than one independent item")
        used.update(indices)
        selected.append((name, part))
    if all_objects and used != set(range(len(scene["meshes"]))):
        raise ValueError("Source-object partition is incomplete")
    manifest = {"schema":"agent-item-exports/1", "source":scene.get("metadata",{}),
                "split_policy":"explicit whole-source-object groups, then independently normalized",
                "source_triangles":sum(x["triangles"] for x in inventory),
                "exporter_code": {"mesh":"playful_world.export", "pixel":"pixel_hardcore.convert",
                                  "preview":"render_preview.comparison", "sheet":"render_preview.item_sheet",
                                  "validation":"validate_outputs.validate"},
                "all_objects":all_objects, "complete_partition": used == set(range(len(scene["meshes"]))),
                "selected_source_triangles":sum(source_stats(part)["triangles"] for _,part in selected),
                "items":[], "installed_into_mod":False}
    sheet = []
    for name, part in selected:
        folder = output / name
        canonical = folder / "source/scene.json"
        write_json(canonical,normalize_scene(part,max_size=max_size,rotate=rotate))
        normalized = load_scene(canonical)
        detail = export(normalized,folder/"playful",name,namespace,triangle_budget)
        pixel = convert(canonical,folder/"pixel",name=name,namespace=namespace,
                        resolution=pixel_resolution,max_elements=face_budget)
        if not pixel["target_met"]:
            raise ValueError(f"Pixel face budget exceeded for {name}")
        paths = {"mesh":folder/"playful/model.json", "mesh_texture":folder/"playful/texture.png",
                 "pixel":folder/"pixel/model.json", "pixel_texture":folder/"pixel/texture.png"}
        validation = validate(paths["mesh"],paths["mesh_texture"],paths["pixel"],paths["pixel_texture"],
                              source_tga=Path(normalized["materials"][0]["base_color_texture"]) if len(normalized["materials"])==1 else None,
                              triangle_budget=triangle_budget,face_budget=face_budget)
        write_json(folder/"validation.json",validation,pretty=True)
        preview = folder/"comparison.png"
        comparison(paths["mesh"],paths["mesh_texture"],paths["pixel"],paths["pixel_texture"],preview,size=preview_size)
        sheet.append({"id":name,**paths})
        manifest["items"].append({"id":name,"selection":part["metadata"]["selection"],
                                  "source_bounds":source_stats(part)["bounds"],
                                  "model_bounds":detail["geometry"]["bounds"],
                                  "triangles":detail["exported_triangles"],"pixel_quads":pixel["rendered_quads"],
                                  "pixel_resolution":pixel["actual_resolution"],
                                  "files":{k:str(p.relative_to(output)) for k,p in paths.items()},
                                  "preview":str(preview.relative_to(output)),
                                  "scene":str(canonical.relative_to(output)),
                                  "valid":validation["valid"]})
    manifest["exported_triangles"] = sum(item["triangles"] for item in manifest["items"])
    if manifest["exported_triangles"] != manifest["selected_source_triangles"]:
        raise ValueError("Source triangles were lost or duplicated")
    item_sheet(sheet,output/"overview.png",size=preview_size)
    manifest["overview"] = "overview.png"
    if source is not None and digest(Path(source)) != scene["metadata"]["source_sha256"]:
        raise ValueError("Source file changed during export")
    write_json(output/"items.json",manifest,pretty=True)
    return manifest


def main():
    p = argparse.ArgumentParser(description=__doc__)
    inputs = p.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--source",type=Path)
    inputs.add_argument("--scene",type=Path)
    p.add_argument("--output",type=Path,required=True)
    mode = p.add_mutually_exclusive_group()
    mode.add_argument("--all-objects",action="store_true",help="Explicitly treat each source object as one item")
    mode.add_argument("--item",action="append",help="Item ID=mesh selector[,selector...] for a whole item")
    p.add_argument("--inspect-only",action="store_true",help="Only list source objects and bounds, no exports")
    p.add_argument("--namespace",default="tacz_pubg")
    p.add_argument("--max-size",type=float,default=14)
    p.add_argument("--rotate",nargs=3,type=float,default=(0,0,0))
    p.add_argument("--triangle-budget",type=int,default=6000)
    p.add_argument("--pixel-resolution",type=int,default=36)
    p.add_argument("--face-budget",type=int,default=1800)
    p.add_argument("--preview-size",type=int,default=384)
    a=p.parse_args()
    if not 128 <= a.preview_size <= 1024:
        p.error("preview-size must be 128..1024")
    result=run(source=a.source,scene_path=a.scene,output=a.output,all_objects=a.all_objects,items=a.item,
               namespace=a.namespace,max_size=a.max_size,rotate=a.rotate,triangle_budget=a.triangle_budget,
               pixel_resolution=a.pixel_resolution,face_budget=a.face_budget,preview_size=a.preview_size,
               inspect_only=a.inspect_only)
    if a.inspect_only:
        for obj in result["objects"]:
            print(f"{obj['index']}: {obj['name']} / {obj['triangles']} triangles / material {obj['material_index']}")
    else:
        for item in result["items"]:
            print(f"{item['id']}: {item['triangles']} triangles, {item['pixel_quads']} pixel quads -> {item['preview']}")
        print(f"Item-to-image manifest: {a.output/'items.json'}")


if __name__ == "__main__":
    main()
