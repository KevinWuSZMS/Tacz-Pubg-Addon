"""Extract an entire numbered C4D pack, export both styles and render every item.

One command; no model calls and no mod installation. Naming is a later phase.
The stored source mesh remains a faithful archive. Meshes above the normal 6000
triangle budget are explicitly marked archive-only, never silently installed.
"""
from __future__ import annotations

import argparse
import csv
from concurrent.futures import ProcessPoolExecutor, as_completed
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import traceback

import numpy as np
from PIL import Image, ImageDraw

from workflow_common import read_source, load_scene, select_meshes, normalize_scene, source_stats, write_json, digest
from playful_world import export
from pixel_hardcore import convert, DEFAULT_RESOLUTION
from render_preview import mesh_triangles, pixel_triangles, rasterize, font

ASSEMBLED_SOURCES = {3,14,15,47,57,79}
def pixel_settings(number, requested):
    """All pack items retain the approved density; budgets never lower it."""
    resolution=max(DEFAULT_RESOLUTION,requested)
    return {"resolution":resolution,"min_resolution":resolution}


def pack_item_groups(scene, number):
    """Verified PUBG82 profile: whole source objects, with six real assemblies.

    AABB overlap alone cannot separate the dense magazine/attachment layouts.
    Material submeshes always retain the same source-object identity.
    """
    objects={}
    for mesh_index,mesh in enumerate(scene["meshes"]):
        objects.setdefault(mesh.get("source_object_index",mesh_index),[]).append(mesh_index)
    return [list(range(len(scene["meshes"])))] if number in ASSEMBLED_SOURCES else list(objects.values())


def group_objects(scene, gap_ratio=.01):
    """Keep all material parts/islands of an object; join overlapping nearby objects."""
    objects = {}
    for i, mesh in enumerate(scene["meshes"]):
        key = mesh.get("source_object_index", i)
        objects.setdefault(key, []).append(i)
    groups = list(objects.values())
    bounds = []
    for indices in groups:
        points = np.concatenate([np.asarray(scene["meshes"][i]["vertices"]).reshape(-1,8)[:,:3] for i in indices])
        bounds.append((points.min(0),points.max(0)))
    parents = list(range(len(groups)))
    def find(a):
        while parents[a] != a:
            parents[a] = parents[parents[a]]
            a = parents[a]
        return a
    for i, (a,b) in enumerate(bounds):
        for j in range(i):
            c,d = bounds[j]
            gap = np.maximum(np.maximum(a-d,c-b),0)
            tolerance = min(float(np.max(b-a)),float(np.max(d-c))) * gap_ratio + 1e-8
            if np.linalg.norm(gap) <= tolerance:
                parents[find(i)] = find(j)
    merged = {}
    for i, indices in enumerate(groups):
        merged.setdefault(find(i),[]).extend(indices)
    return sorted((sorted(v) for v in merged.values()),key=lambda v:v[0])


def preview(model, texture, pixel_model, pixel_texture, path, title, *, size=320, warnings=None):
    """Reuse existing geometry decoding and rasterizer, one useful angle per style."""
    detail = mesh_triangles(json.loads(model.read_text(encoding="utf-8")))
    with Image.open(texture) as image:
        dt = np.asarray(image.convert("RGBA"))
    entries = [(detail,dt,False,True,"Playful / 萌趣世界",len(detail))]
    if pixel_model is not None:
        voxel = pixel_triangles(json.loads(pixel_model.read_text(encoding="utf-8")))
        with Image.open(pixel_texture) as image:
            vt = np.asarray(image.convert("RGBA"))
        entries.append((voxel,vt,True,False,"Pixel / 像素硬核",len(voxel)//2))
    points = np.concatenate([a[:,:,:3].reshape(-1,3) for a,*_ in entries])
    center = (points.min(0)+points.max(0))/2
    radius = float(np.linalg.norm(points-center,axis=1).max())
    canvas = Image.new("RGB",(size*len(entries),size+86),"white")
    draw = ImageDraw.Draw(canvas)
    draw.text((12,6),title,font=font(17),fill=(24,35,43))
    for column,(triangles,pixels,cull,wrap,label,count) in enumerate(entries):
        image = rasterize(triangles,pixels,yaw=40,pitch=20,size=size,
                          center=center,scale=size*.43/max(radius,1e-9),cull=cull,wrap=wrap)
        canvas.paste(image,(column*size,56))
        draw.text((column*size+12,31),f"{label}: {count}",font=font(13),fill=(65,75,85))
    caption = "Software preview; base color + normals."
    if warnings:
        caption = "Material approximation / 材质近似; see item.json"
    draw.text((12,canvas.height-24),caption,font=font(12),fill=(125,65,20) if warnings else (65,75,85))
    path.parent.mkdir(parents=True,exist_ok=True)
    canvas.save(path)


def _number(path):
    numbers = re.findall(r"\d+",path.stem)
    return int(numbers[-1]) if numbers else 0


def stage_textures(scene, output):
    """Archive only referenced bitmaps, including normal/RM/AO source channels."""
    def store(value):
        path=Path(value)
        if not path.is_file():
            return value
        sha=digest(path)
        target=output/"_textures"/(sha[:16]+"_"+path.name)
        if not target.is_file():
            target.parent.mkdir(parents=True,exist_ok=True)
            temporary=target.with_name(target.name+f".{os.getpid()}.tmp")
            shutil.copyfile(path,temporary)
            os.replace(temporary,target)
        return str(target.resolve())
    for material in scene["materials"]:
        archived={}
        for key,value in list(material.items()):
            if key.endswith("_texture") and isinstance(value,str):
                material[key]=store(value)
                archived[key]={"original":value,"stored":str(Path(material[key]).relative_to(output)).replace("\\","/")}
        for channel in material.get("source_channels",{}).values():
            if channel.get("texture"):
                channel["original_texture"]=channel["texture"]
                channel["texture"]=store(channel["texture"])
        material["archived_textures"]=archived


def _job(source_string, output_string, options):
    source, output = Path(source_string), Path(output_string)
    number = _number(source)
    folder = output / options.get("source_directories",{}).get(number,f"{number:03d}")
    receipt = folder / "source.json"
    fingerprint = options["code_fingerprint"]
    source_hash = digest(source)
    if options["resume"] and receipt.is_file():
        previous = json.loads(receipt.read_text(encoding="utf-8"))
        reusable_code = previous.get("code_fingerprint")==fingerprint or options.get("retry_failed",False)
        force = number in options.get("force_sources",[])
        if previous.get("success") and previous.get("source_sha256")==source_hash and reusable_code and not force:
            if all((output/item["preview"]).is_file() for item in previous["items"]):
                previous["reused"] = True
                return previous
    result = {"source_number":number,"source":str(source.resolve()),"source_sha256":source_hash,
              "code_fingerprint":fingerprint,"directory":folder.name,
              "success":False,"items":[],"errors":[],"installed_into_mod":False}
    try:
        scene = read_source(source)
        stage_textures(scene,output)
        write_json(folder/"scene.raw.json",scene)
        groups = pack_item_groups(scene,number)
        result["raw_mesh_count"] = len(scene["meshes"])
        result["physical_item_count"] = len(groups)
        result["source_metadata"] = scene.get("metadata",{})
        for ordinal,indices in enumerate(groups,1):
            stable_id = f"item_{number:03d}_{ordinal:02d}"
            item_folder = folder / stable_id
            part = select_meshes(scene,indices)
            normalized = normalize_scene(part)
            canonical = item_folder/"scene.json"
            portable=json.loads(json.dumps(normalized))
            for material in portable["materials"]:
                for key,value in list(material.items()):
                    if key.endswith("_texture") and isinstance(value,str) and Path(value).is_file():
                        material[key]=os.path.relpath(value,canonical.parent).replace("\\","/")
            write_json(canonical,portable)
            normalized=load_scene(canonical)
            triangles = source_stats(normalized)["triangles"]
            detail = export(normalized,item_folder/"playful",stable_id,"pubg_archive",max(100000,triangles))
            detail["archive_only"] = triangles > options["triangle_budget"]
            detail["runtime_triangle_budget"] = options["triangle_budget"]
            detail["runtime_ready"] = triangles <= options["triangle_budget"]
            write_json(item_folder/"playful/report.json",detail,pretty=True)
            pixel, pixel_error = None, None
            try:
                quality=pixel_settings(number,options["pixel_resolution"])
                pixel = convert(canonical,item_folder/"pixel",name=stable_id,namespace="pubg_archive",
                                resolution=quality["resolution"],min_resolution=quality["min_resolution"],
                                max_elements=options["face_budget"])
                if not pixel["target_met"]:
                    pixel_error = "Minimum voxel resolution still exceeds runtime face budget; archive-only"
            except Exception as exc:
                pixel_error = f"{type(exc).__name__}: {exc}"
            warnings = []
            for material in normalized["materials"]:
                warnings.extend(material.get("warnings",[]))
                warnings.extend(material.get("diagnostics",[]))
                warnings.extend(material.get("extraction_warnings",[]))
                if material.get("texture_fallback"):
                    warnings.append("Missing base-color bitmap: explicit material-color fallback")
            image_path = item_folder/"preview.png"
            preview(item_folder/"playful/model.json",item_folder/"playful/texture.png",
                    item_folder/"pixel/model.json" if pixel else None,item_folder/"pixel/texture.png" if pixel else None,
                    image_path,stable_id,size=options["preview_size"],warnings=warnings)
            item = {"id":stable_id,"source_number":number,"item_index":ordinal,
                    "source_mesh_indices":indices,"source_object_names":list(dict.fromkeys(m.get("object_name",m.get("name","")) for m in part["meshes"])),
                    "triangles":triangles,"runtime_mesh_ready":triangles<=options["triangle_budget"],
                    "pixel_quads":pixel["rendered_quads"] if pixel else None,
                    "pixel_resolution":pixel["actual_resolution"] if pixel else None,
                    "runtime_pixel_ready":bool(pixel and pixel["target_met"]),"pixel_error":pixel_error,
                    "material_warnings":warnings,
                    "scene":str(canonical.relative_to(output)).replace("\\","/"),
                    "playful":str((item_folder/"playful/model.json").relative_to(output)).replace("\\","/"),
                    "pixel":str((item_folder/"pixel/model.json").relative_to(output)).replace("\\","/") if pixel else None,
                    "preview":str(image_path.relative_to(output)).replace("\\","/"),
                    "name_zh":None,"naming_status":"pending_luna_after_extraction"}
            write_json(item_folder/"item.json",item,pretty=True)
            result["items"].append(item)
        result["success"] = True
        result["source_unchanged"] = digest(source)==source_hash
        if not result["source_unchanged"]:
            raise ValueError("Source changed during extraction")
    except Exception as exc:
        result["errors"].append(f"{type(exc).__name__}: {exc}")
        result["traceback"] = traceback.format_exc()
    write_json(receipt,result,pretty=True)
    return result


def contact_sheets(items, output, *, per_page=16):
    """Small labeled images for one inexpensive naming pass after extraction."""
    pages=[]
    width,height=320,240
    for start in range(0,len(items),per_page):
        subset=items[start:start+per_page]
        columns=4; rows=math.ceil(len(subset)/columns)
        canvas=Image.new("RGB",(columns*width,rows*height),"white")
        draw=ImageDraw.Draw(canvas)
        for index,item in enumerate(subset):
            with Image.open(output/item["preview"]) as im:
                # The first panel is the original mesh; leave off pixel-style panel.
                panel=im.crop((0,56,im.width//2 if item["pixel"] else im.width,56+im.height-86))
                panel.thumbnail((width,height-30))
                x=index%columns*width; y=index//columns*height
                canvas.paste(panel,(x+(width-panel.width)//2,y+26))
            draw.text((x+8,y+4),item["id"],font=font(16),fill=(24,35,43))
        page=output/"naming_sheets"/f"sheet_{len(pages)+1:02d}.png"
        page.parent.mkdir(parents=True,exist_ok=True);canvas.save(page)
        pages.append(str(page.resolve()))
    return pages


def apply_names(names_path, output):
    """Name the 82 source folders from Luna's original-JPG recognition, last."""
    output=output.resolve()
    pack_path=output/"pack.json"
    pack=json.loads(pack_path.read_text(encoding="utf-8"))
    if pack["successful_sources"]!=pack["source_count"] or pack["preview_count"]!=pack["item_count"]:
        raise ValueError("Finish every extraction and preview before naming")
    data=json.loads(names_path.read_text(encoding="utf-8-sig"))
    if data.get("model")!="gpt-6-luna":
        raise ValueError("Naming was explicitly assigned to gpt-6-luna")
    records=data["names"]
    names={int(entry["source_number"]):entry["name_zh"].strip() for entry in records}
    numbers={source["source_number"] for source in pack["sources"]}
    if len(names)!=len(records) or set(names)!=numbers or any(not name for name in names.values()):
        raise ValueError("Exactly one nonempty name per source folder is required")
    moves=[]
    for source in pack["sources"]:
        number=source["source_number"]
        safe=re.sub(r'[<>:"/\\|?*\x00-\x1f]',"_",names[number]).rstrip(" .")
        directory=f"{number:03d}_{safe}"
        old=output/source.get("directory",f"{number:03d}")
        new=output/directory
        # Directory moves are constrained to these explicitly selected pack roots.
        if old.resolve().parent!=output or new.resolve().parent!=output:
            raise ValueError("Folder rename leaves the extraction root")
        if old!=new and new.exists():
            raise FileExistsError(new)
        moves.append((source,old,new,directory))
    replacements={old.name:new.name for _,old,new,_ in moves if old!=new}
    def rewrite(value):
        if isinstance(value,dict):return {k:rewrite(v) for k,v in value.items()}
        if isinstance(value,list):return [rewrite(v) for v in value]
        if isinstance(value,str):
            for old,new in replacements.items():
                if value.startswith(old+"/") or value.startswith(old+"\\"):
                    return new+value[len(old):]
                absolute=str(output/old)
                if value.startswith(absolute+os.sep):return str(output/new)+value[len(absolute):]
        return value
    for _,old,new,_ in moves:
        if old!=new:old.rename(new)
    pack=rewrite(pack)
    folders={source["source_number"]:directory for source,_,_,directory in moves}
    for source in pack["sources"]:
        number=source["source_number"]
        source.update(name_zh=names[number],directory=folders[number],naming_model=data["model"])
    by_id={}
    for item in pack["items"]:
        number=item["source_number"]
        item.update(source_name_zh=names[number],source_directory=folders[number],
                    naming_status="completed_source_folder",naming_model=data["model"])
        folder=(output/item["scene"]).parent
        with Image.open((output/item["playful"]).with_name("texture.png")) as texture:
            size=texture.size
        reasons=[]
        if item["triangles"]>6000:reasons.append("more_than_6000_triangles")
        if size[0]*size[1]>2048**2:reasons.append("atlas_exceeds_2048_squared_pixels")
        item.update(runtime_mesh_ready=not reasons,runtime_not_ready_reasons=reasons,texture_size=list(size))
        preview_name=f"{number:03d}_{names[number]}__{item['item_index']:02d}.png"
        preview_name=re.sub(r'[<>:"/\\|?*\x00-\x1f]',"_",preview_name)
        named=output/"previews"/preview_name
        named.parent.mkdir(parents=True,exist_ok=True)
        with Image.open(output/item["preview"]) as original:image=original.convert("RGB")
        draw=ImageDraw.Draw(image);draw.rectangle((0,0,image.width,28),fill="white")
        draw.text((10,5),f"{names[number]} | {item['id']}",font=font(16),fill=(24,35,43));image.save(named)
        item["named_preview"]=str(named.relative_to(output)).replace("\\","/")
        write_json(folder/"item.json",item,pretty=True)
        report_path=folder/"pixel/report.json"
        if report_path.exists():
            report=json.loads(report_path.read_text(encoding="utf-8"));report["source_scene"]=str((folder/"scene.json").resolve())
            write_json(report_path,report,pretty=True)
        report_path=folder/"playful/report.json"
        report=json.loads(report_path.read_text(encoding="utf-8"))
        report.update(runtime_ready=not reasons,archive_only=bool(reasons),runtime_not_ready_reasons=reasons,
                      runtime_texture_budget_pixels=2048**2,texture_size=list(size))
        write_json(report_path,report,pretty=True)
        by_id[item["id"]]=item
    for source in pack["sources"]:
        source["items"]=[by_id[item["id"]] for item in source["items"]]
        write_json(output/source["directory"]/"source.json",source,pretty=True)
    pack.update(naming_status="completed_source_folders",naming_model=data["model"],named_source_folders=len(names),
                runtime_mesh_ready=sum(item["runtime_mesh_ready"] for item in pack["items"]),
                source_names_file="source_names_luna.json",catalog="catalog.csv")
    write_json(pack_path,pack,pretty=True)
    write_json(output/"source_names_luna.json",data,pretty=True)
    with (output/"catalog.csv").open("w",encoding="utf-8-sig",newline="") as stream:
        writer=csv.writer(stream)
        writer.writerow(["编号","源目录名称","物品ID","源对象","三角形","像素面","详细模型预算通过","预览","详细模型","像素模型"])
        for item in pack["items"]:
            writer.writerow([item["source_number"],item["source_directory"],item["id"],"; ".join(item["source_object_names"]),
                             item["triangles"],item["pixel_quads"],item["runtime_mesh_ready"],
                             item["named_preview"],item["playful"],item["pixel"]])
    print(f"Named {len(names)} source folders with numeric prefixes; {len(pack['items'])} named previews",flush=True)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--source-dir",type=Path)
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--apply-names",type=Path,help="Apply a final GPT-6 Luna names JSON only after complete extraction")
    p.add_argument("--workers",type=int,default=4)
    p.add_argument("--pixel-resolution",type=int,default=DEFAULT_RESOLUTION,help="Fixed density, minimum36; face budget never silently lowers it")
    p.add_argument("--face-budget",type=int,default=1800)
    p.add_argument("--triangle-budget",type=int,default=6000)
    p.add_argument("--preview-size",type=int,default=256)
    p.add_argument("--group-gap-ratio",type=float,default=.01)
    p.add_argument("--no-resume",action="store_true")
    p.add_argument("--retry-failed",action="store_true",help="Reuse completed source receipts while repairing failed files")
    p.add_argument("--force-source",type=int,nargs="*",default=[],help="Re-extract selected source numbers even when complete")
    a=p.parse_args()
    if a.apply_names:
        apply_names(a.apply_names,a.output)
        return
    if a.source_dir is None:
        p.error("--source-dir is required for extraction")
    if not 1<=a.workers<=8 or not 128<=a.preview_size<=1024 or not 0<=a.group_gap_ratio<=.1:
        p.error("workers1..8, preview-size128..1024, group-gap-ratio0...1 required")
    sources=sorted(a.source_dir.glob("*.c4d"),key=_number)
    if not sources:
        p.error("No .c4d files found")
    a.output.mkdir(parents=True,exist_ok=True)
    source_directories={}
    previous_pack=a.output/"pack.json"
    if previous_pack.exists():
        previous=json.loads(previous_pack.read_text(encoding="utf-8"))
        source_directories={s["source_number"]:s.get("directory",f"{s['source_number']:03d}") for s in previous["sources"]}
    code=Path(__file__).parent
    tracked=["batch_extract.py","c4d_reader.py","c4d_materials.py","workflow_common.py","playful_world.py","pixel_hardcore.py","render_preview.py"]
    signature="".join(digest(code/f) for f in tracked if (code/f).exists())+str((a.pixel_resolution,a.face_budget,a.triangle_budget,a.preview_size,a.group_gap_ratio))
    options={"resume":not a.no_resume,"pixel_resolution":a.pixel_resolution,"face_budget":a.face_budget,
             "triangle_budget":a.triangle_budget,"preview_size":a.preview_size,"group_gap_ratio":a.group_gap_ratio,
             "retry_failed":a.retry_failed,"force_sources":a.force_source,
             "source_directories":source_directories,
             "code_fingerprint":hashlib.sha256(signature.encode()).hexdigest()}
    results=[]
    with ProcessPoolExecutor(max_workers=a.workers) as pool:
        futures={pool.submit(_job,str(src.resolve()),str(a.output.resolve()),options):src for src in sources}
        for future in as_completed(futures):
            try:
                result=future.result()
            except Exception as exc:
                src=futures[future];result={"source_number":_number(src),"source":str(src),"success":False,"items":[],"errors":[repr(exc)]}
            results.append(result)
            print(f"[{len(results)}/{len(sources)}] {result['source_number']:03d}: "+(f"{len(result['items'])} items" if result["success"] else "FAILED "+str(result["errors"])),flush=True)
            write_json(a.output/"progress.json",{"finished":len(results),"total":len(sources),"sources":sorted(results,key=lambda r:r["source_number"])},pretty=True)
    results.sort(key=lambda r:r["source_number"])
    items=[item for result in results for item in result["items"]]
    pages=contact_sheets(items,a.output)
    manifest={"schema":"agent-pack-extraction/1","source_count":len(sources),"successful_sources":sum(r["success"] for r in results),
              "item_count":len(items),"preview_count":sum((a.output/item["preview"]).is_file() for item in items),
              "runtime_mesh_ready":sum(item["runtime_mesh_ready"] for item in items),
              "runtime_pixel_ready":sum(item["runtime_pixel_ready"] for item in items),
              "naming_status":"pending_luna_after_extraction","naming_sheets":pages,
              "installed_into_mod":False,"options":options,"sources":results,"items":items}
    write_json(a.output/"pack.json",manifest,pretty=True)
    print(f"DONE sources={manifest['successful_sources']}/{len(sources)} items={len(items)} previews={manifest['preview_count']}",flush=True)
    if manifest["successful_sources"]!=len(sources):
        raise SystemExit(2)
    prior_names=a.output/"source_names_luna.json"
    if prior_names.exists():
        apply_names(prior_names,a.output)


if __name__=="__main__":
    main()
