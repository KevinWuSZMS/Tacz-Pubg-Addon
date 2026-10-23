"""Replace only Pixel exports/previews in an existing pack, from archived scenes.

The accepted density is fixed; face budgets report cost instead of lowering it.
Playful assets, naming and source scenes remain byte-for-byte unchanged.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ProcessPoolExecutor, as_completed
import csv
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import traceback

import numpy as np
from PIL import Image, ImageDraw

from batch_extract import preview
from pixel_hardcore import convert, DEFAULT_RESOLUTION
from render_preview import pixel_triangles, font
from workflow_common import write_json


def file_hash(path):
    hasher=hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda:stream.read(1024*1024),b""):
            hasher.update(block)
    return hasher.hexdigest()


def validate_pixel(folder, report, resolution):
    model=json.loads((folder/"model.json").read_text(encoding="utf-8"))
    triangles=pixel_triangles(model)
    if report["actual_resolution"]!=resolution or report["requested_resolution"]!=resolution:
        raise ValueError("Pixel resolution changed unexpectedly")
    if len(model["elements"])!=report["rendered_quads"] or len(triangles)!=report["rendered_quads"]*2:
        raise ValueError("Pixel geometry/report counts disagree")
    if not len(triangles) or not np.isfinite(triangles).all():
        raise ValueError("Pixel geometry is empty or nonfinite")
    if triangles[:,:,:3].min() < -16 or triangles[:,:,:3].max() > 32:
        raise ValueError("Pixel geometry exceeds Minecraft element bounds")
    if any(len(element["faces"])!=1 for element in model["elements"]):
        raise ValueError("Expected exactly one visible quad per element")
    for element in model["elements"]:
        for face in element["faces"].values():
            uv=np.asarray(face["uv"])
            if not np.isfinite(uv).all() or uv.min()<0 or uv.max()>16:
                raise ValueError("Invalid pixel atlas UV")
    with Image.open(folder/"texture.png") as texture:
        texture.load()
        if list(texture.size)!=report["texture_size"]:
            raise ValueError("Pixel texture/report dimensions disagree")


def _job(root_string, stage_string, backup_string, item, resolution, face_budget):
    root,stage,backup=map(Path,(root_string,stage_string,backup_string))
    result={"id":item["id"],"success":False}
    try:
        scene=root/item["scene"]
        pixel=(root/item["pixel"]).parent
        playful=(root/item["playful"]).parent
        protected=[scene,playful/"model.json",playful/"texture.png",playful/"report.json"]
        original_hashes={str(p.relative_to(root)):file_hash(p) for p in protected}
        old=json.loads((pixel/"model.json").read_text(encoding="utf-8"))
        resource=old["textures"]["0"]
        namespace,name=resource.split(":",1)
        if not name.startswith("item/"):
            raise ValueError("Expected an item texture resource")
        temporary=stage/item["id"]
        report=convert(scene,temporary/"pixel",name=name[5:],namespace=namespace,
                       resolution=resolution,min_resolution=resolution,max_elements=face_budget,
                       display_template=pixel/"model.json")
        validate_pixel(temporary/"pixel",report,resolution)
        # Keep the existing batch preview size/quality; only its Pixel content changes.
        with Image.open(root/item["preview"]) as image:
            size=image.height-86
        preview(playful/"model.json",playful/"texture.png",
                temporary/"pixel/model.json",temporary/"pixel/texture.png",
                temporary/"preview.png",item["id"],size=size,warnings=item.get("material_warnings"))
        pairs=[(temporary/"pixel"/name,pixel/name) for name in ("model.json","texture.png","report.json")]
        pairs.append((temporary/"preview.png",root/item["preview"]))
        if item.get("named_preview"):
            with Image.open(temporary/"preview.png") as source:
                image=source.convert("RGB")
            draw=ImageDraw.Draw(image)
            draw.rectangle((0,0,image.width,28),fill="white")
            draw.text((10,5),f"{item.get('source_name_zh',item['id'])} | {item['id']}",font=font(16),fill=(24,35,43))
            image.save(temporary/"named.png")
            pairs.append((temporary/"named.png",root/item["named_preview"]))
        if any(file_hash(root/relative)!=value for relative,value in original_hashes.items()):
            raise ValueError("Protected Playful/source asset changed")
        for _,destination in pairs:
            saved=backup/destination.relative_to(root)
            saved.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(destination,saved)
        for generated,destination in pairs:
            os.replace(generated,destination)
        (temporary/"pixel").rmdir()
        temporary.rmdir()
        item=dict(item)
        item.update(pixel_quads=report["rendered_quads"],pixel_resolution=resolution,
                    runtime_pixel_ready=report["target_met"],
                    pixel_error=None if report["target_met"] else "Fixed resolution exceeds face budget; archive-only, no automatic downgrade")
        result.update(success=True,item=item,playful_and_scene_unchanged=True,
                      resolution=resolution,quads=report["rendered_quads"],
                      target_met=report["target_met"],protected_sha256=original_hashes)
    except Exception as exc:
        result.update(error=f"{type(exc).__name__}: {exc}",traceback=traceback.format_exc())
    return result


def update_catalog(root, pack):
    with (root/"catalog.csv").open("w",encoding="utf-8-sig",newline="") as stream:
        writer=csv.writer(stream)
        writer.writerow(["编号","源目录名称","物品ID","源对象","三角形","像素面","详细模型预算通过","预览","详细模型","像素模型"])
        for item in pack["items"]:
            writer.writerow([item["source_number"],item.get("source_directory",""),item["id"],
                             "; ".join(item["source_object_names"]),item["triangles"],item["pixel_quads"],
                             item["runtime_mesh_ready"],item.get("named_preview",item["preview"]),item["playful"],item["pixel"]])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack",type=Path,required=True)
    parser.add_argument("--resolution",type=int,default=DEFAULT_RESOLUTION)
    parser.add_argument("--face-budget",type=int,default=1800)
    parser.add_argument("--workers",type=int,default=4)
    args=parser.parse_args()
    if not 1<=args.workers<=8 or not 8<=args.resolution<=96:
        parser.error("workers1..8 and resolution8..96 required")
    root=args.pack.resolve()
    pack=json.loads((root/"pack.json").read_text(encoding="utf-8"))
    if any(not item.get("pixel") for item in pack["items"]):
        parser.error("Every item must already have a Pixel output path")
    timestamp=datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    backup=Path(__file__).resolve().parent/"backups"/f"pixel_rebuild_{timestamp}"
    stage=root/f".pixel_rebuild_{timestamp}"
    backup.mkdir(parents=True,exist_ok=False)
    stage.mkdir()
    for filename in ("pack.json","catalog.csv","verification.json"):
        if (root/filename).is_file():
            shutil.copy2(root/filename,backup/filename)
    for item in pack["items"]:
        path=(root/item["scene"]).parent/"item.json"
        saved=backup/path.relative_to(root)
        saved.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(path,saved)
    for source in pack["sources"]:
        path=root/source.get("directory",f"{source['source_number']:03d}")/"source.json"
        saved=backup/path.relative_to(root)
        saved.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(path,saved)
    results=[]
    with ProcessPoolExecutor(max_workers=args.workers) as pool:
        jobs=[pool.submit(_job,str(root),str(stage),str(backup),item,args.resolution,args.face_budget) for item in pack["items"]]
        for future in as_completed(jobs):
            result=future.result()
            results.append(result)
            print(f"[{len(results)}/{len(jobs)}] {result['id']}: "+
                  (f"resolution{result['resolution']} / {result['quads']} quads" if result["success"] else result["error"]),flush=True)
            write_json(root/"pixel_progress.json",{"completed":len(results),"total":len(jobs),"results":results},pretty=True)
    by_id={result["id"]:result["item"] for result in results if result["success"]}
    pack["items"]=[by_id.get(item["id"],item) for item in pack["items"]]
    for item in pack["items"]:
        write_json((root/item["scene"]).parent/"item.json",item,pretty=True)
    for source in pack["sources"]:
        source["items"]=[by_id.get(item["id"],item) for item in source["items"]]
        source["pixel_resolution_policy"]={"resolution":args.resolution,"minimum_resolution":args.resolution,"automatic_downgrade":False}
        write_json(root/source.get("directory",f"{source['source_number']:03d}")/"source.json",source,pretty=True)
    pack.setdefault("options",{}).update(pixel_resolution=args.resolution,pixel_min_resolution=args.resolution,face_budget=args.face_budget)
    pack["runtime_pixel_ready"]=sum(item["runtime_pixel_ready"] for item in pack["items"])
    pack["pixel_rebuild"]={"resolution":args.resolution,"minimum_resolution":args.resolution,
                           "automatic_downgrade":False,"succeeded":len(by_id),"total":len(results),
                           "backup":str(backup),"playful_assets_unchanged":all(r.get("playful_and_scene_unchanged",False) for r in results)}
    write_json(root/"pack.json",pack,pretty=True)
    update_catalog(root,pack)
    verification_path=root/"verification.json"
    verification=json.loads(verification_path.read_text(encoding="utf-8")) if verification_path.exists() else {}
    verification.update(pixel_rebuild=pack["pixel_rebuild"],runtime_pixel_ready=pack["runtime_pixel_ready"],
                        pixel_quads_min=min(item["pixel_quads"] for item in pack["items"]),
                        pixel_quads_max=max(item["pixel_quads"] for item in pack["items"]))
    write_json(verification_path,verification,pretty=True)
    if len(by_id)==len(results):
        stage.rmdir()
    print(f"DONE Pixel {len(by_id)}/{len(results)}; fixed resolution {args.resolution}; within face budget {pack['runtime_pixel_ready']}/{len(results)}; backup {backup}",flush=True)
    if len(by_id)!=len(results):
        raise SystemExit(2)


if __name__=="__main__":
    main()
