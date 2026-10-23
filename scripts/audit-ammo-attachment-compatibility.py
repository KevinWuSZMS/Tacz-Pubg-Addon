"""Read-only TaCZ r7 asset audit. Writes only the named compatibility reports.
No Minecraft/Gradle launch, no changes to gun packs, mods, worlds or configuration.
"""
from pathlib import Path
import collections, csv, hashlib, json, math, re, zipfile

ROOT = Path(__file__).resolve().parents[1]
PACK_ROOT = ROOT / "neoforge-1.21.1/run/tacz"
MOD_ROOT = ROOT / "neoforge-1.21.1/run/mods"
OUT = ROOT / "docs/compatibility"
KINDS = ("guns", "ammo", "attachments")
TYPES = ("scope", "muzzle", "stock", "grip", "laser", "extended_mag")
WARNINGS = []


def gson_json(text):
    """Tokenize comments/strings before normalizing Gson-style lenient JSON.
    Supports BOM, comments, single quoted strings, unquoted keys, leading +,
    decimal .5/5., and trailing commas. Does not edit string contents.
    """
    text = text.lstrip("\ufeff")
    tokens = []
    i = 0
    while i < len(text):
        c = text[i]
        if c.isspace(): i += 1; continue
        if text.startswith("//", i):
            j = text.find("\n", i + 2); i = len(text) if j < 0 else j + 1; continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            if j < 0: raise ValueError("unterminated comment")
            i = j + 2; continue
        if c in "\"'":
            quote = c; i += 1; value = []
            while i < len(text):
                c = text[i]; i += 1
                if c == quote: break
                if c == "\\":
                    if i >= len(text): raise ValueError("unfinished escape")
                    e = text[i]; i += 1
                    if e == "u": value.append(chr(int(text[i:i+4], 16))); i += 4
                    else: value.append({"n":"\n", "r":"\r", "t":"\t", "b":"\b", "f":"\f"}.get(e, e))
                else: value.append(c)
            else: raise ValueError("unterminated string")
            tokens.append(json.dumps("".join(value), ensure_ascii=True)); continue
        if c in "{}[]: ,": tokens.append(c); i += 1; continue
        j = i
        while j < len(text) and not text[j].isspace() and text[j] not in "{}[]:," and not text.startswith("//", j) and not text.startswith("/*", j): j += 1
        if j == i: raise ValueError("unexpected token at " + str(i))
        tokens.append(text[i:j]); i = j
    normalized = []
    for n, token in enumerate(tokens):
        following = tokens[n+1] if n+1 < len(tokens) else None
        if token == "," and following in ("}", "]"): continue
        if following == ":" and not token.startswith('"'): token = json.dumps(token)
        elif re.fullmatch(r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?", token):
            token = token.lstrip("+")
            token = re.sub(r"^(-?)\.", r"\g<1>0.", token)
            token = re.sub(r"\.(?=[eE]|$)", ".0", token)
        normalized.append(token)
    return json.loads(" ".join(normalized))


def canonical(value): return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False)
def rid(value):
    if not isinstance(value, str): return None
    value = value if ":" in value else "minecraft:" + value
    return value if re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9/._-]+", value) else None

def resource(reference, folder, suffix=".json", side="data"):
    reference = rid(reference)
    if reference is None: return None
    ns, path = reference.split(":", 1)
    return f"{side}/{ns}/{folder}/{path}{suffix}"


def main():
    resources = collections.defaultdict(list)
    files = collections.defaultdict(list)
    packages = []
    declarations = collections.Counter()
    jar_declarations = []
    parse_errors = []

    def include(name, source, read):
        if name.endswith("/"): return
        files[name].append(source)
        pattern = re.fullmatch(r"data/([^/]+)/index/(guns|ammo|attachments)/(.+)\.json", name)
        if pattern:
            declarations[pattern[2]] += 1
            if source.startswith("run/mods/"): jar_declarations.append({"path":name, "source":source})
        relevant = name.endswith(".json") and (re.match(r"data/[^/]+/(index/(guns|ammo|attachments)|data/(guns|attachments)|tacz_tags/attachments)/", name)
            or re.match(r"assets/[^/]+/(display/(guns|ammo|attachments)/|lang/(en_us|zh_cn)\.json$)", name))
        if not relevant: return
        try:
            value = gson_json(read().decode("utf-8-sig"))
            digest = hashlib.sha256(canonical(value).encode()).hexdigest()
            for variant in resources[name]:
                if variant["semantic_sha256"] == digest:
                    variant["sources"].append(source); return
            resources[name].append({"semantic_sha256":digest, "sources":[source], "value":value})
        except Exception as error:
            parse_errors.append({"resource":name, "source":source, "error":str(error)})

    for pack in sorted(PACK_ROOT.iterdir()):
        if pack.is_dir() and (pack / "gunpack.meta.json").is_file():
            packages.append({"path":str(pack.relative_to(ROOT)), "kind":"directory"})
            for p in sorted(pack.rglob("*")):
                if p.is_file(): include(p.relative_to(pack).as_posix(), "run/tacz/"+pack.name+"/"+p.relative_to(pack).as_posix(), p.read_bytes)
        elif pack.suffix.lower() == ".zip":
            with zipfile.ZipFile(pack) as archive:
                if "gunpack.meta.json" not in archive.namelist(): continue
                packages.append({"path":str(pack.relative_to(ROOT)), "kind":"zip"})
                for name in archive.namelist(): include(name, "run/tacz/"+pack.name+"!"+name, lambda n=name: archive.read(n))
    for jar in sorted(MOD_ROOT.glob("*.jar")):
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith(("data/", "assets/")):
                    include(name, "run/mods/"+jar.name+"!"+name, lambda n=name: archive.read(n))

    multiple_definitions = [{"resource":path, "resolution": "tag_union" if "/tacz_tags/attachments/" in path else
        "language_key_union_or_name_variants" if "/lang/" in path else "ambiguous_runtime_winner", "variants":values}
        for path, values in sorted(resources.items()) if len(values)>1]
    conflicts = [x for x in multiple_definitions if x["resolution"] == "ambiguous_runtime_winner"]
    translations = {"zh_cn":collections.defaultdict(set), "en_us":collections.defaultdict(set)}
    for path, variants in resources.items():
        m = re.fullmatch(r"assets/[^/]+/lang/(zh_cn|en_us)\.json", path)
        if m:
            for variant in variants:
                if isinstance(variant["value"], dict):
                    for key, text in variant["value"].items(): translations[m[1]][key].add(str(text))

    def names(key):
        return {"name_key":key, "name_zh_cn":sorted(translations["zh_cn"].get(key, [])), "name_en_us":sorted(translations["en_us"].get(key, []))}
    def display_info(kind, reference):
        path = resource(reference, "display/"+kind, side="assets")
        result = {"id":rid(reference), "resource":path, "exists":path in resources, "variants":[]}
        for variant in resources.get(path, []):
            value = variant["value"]
            if not isinstance(value, dict): continue
            model = resource(value.get("model"), "geo_models", side="assets")
            texture = resource(value.get("texture"), "textures", ".png", "assets")
            slot = resource(value.get("slot"), "textures", ".png", "assets")
            result["variants"].append({"sources":variant["sources"], "model_ref":rid(value.get("model")), "model_resource":model,
                "model_resource_exists":model in files, "texture_ref":rid(value.get("texture")), "texture_resource_exists":texture in files,
                "slot_ref":rid(value.get("slot")), "slot_resource_exists":slot in files,
                "adapter":value.get("adapter"), "show_mount":value.get("show_mount", True)})
        return result

    records = {kind:[] for kind in KINDS}
    for path, variants in sorted(resources.items()):
        m = re.fullmatch(r"data/([^/]+)/index/(guns|ammo|attachments)/(.+)\.json", path)
        if not m: continue
        ns, kind, local = m.groups(); identifier = rid(ns+":"+local)
        item = {"id":identifier, "kind":kind, "resource":path, "sources":sorted(s for v in variants for s in v["sources"]),
            "index_variants":variants, "cases":[], "errors":[], "ambiguous":len(variants)>1, "displays":[], "data_references":[]}
        namekeys = set()
        for variant in variants:
            index = variant["value"]
            if not isinstance(index, dict): item["errors"].append("index is not an object"); continue
            key = index.get("name", "")
            namekeys.add(key if isinstance(key,str) else "")
            item["displays"].append(display_info(kind, index.get("display")))
            hidden = index.get("hidden", False)
            if kind == "ammo":
                try: stack = max(int(index.get("stack_size", 0)),1)
                except Exception: item["errors"].append("invalid stack_size"); continue
                item["cases"].append({"stack_size_raw":index.get("stack_size",0), "stack_size":stack, "hidden":hidden}); continue
            data_ref = rid(index.get("data")); data_path = resource(data_ref, "data/"+kind)
            item["data_references"].append({"id":data_ref, "raw":index.get("data"), "resource":data_path,
                "exists":data_path in resources, "variants":resources.get(data_path,[])})
            if not data_ref or data_path not in resources:
                item["errors"].append("missing data: "+str(index.get("data"))+" -> "+str(data_path)); continue
            if len(resources[data_path]) > 1: item["ambiguous"] = True
            for data_variant in resources[data_path]:
                data = data_variant["value"]
                errors = []
                case = {"data_ref":data_ref, "data_resource":data_path, "data_sources":data_variant["sources"], "raw_data":data, "hidden":hidden}
                if not isinstance(data,dict): item["errors"].append("data is not an object"); continue
                if kind == "attachments":
                    type_ = str(index.get("type", "")).lower()
                    if type_ not in TYPES+("none",): errors.append("invalid attachment type: "+str(index.get("type")))
                    case.update(type=type_, extended_mag_level_raw=data.get("extended_mag_level",0), extras=data.get("extras"))
                else:
                    type_ = index.get("type")
                    if not isinstance(type_,str) or not type_.strip(): errors.append("empty gun type")
                    try:
                        capacity = int(data.get("ammo_amount",30)); rpm = int(data.get("rpm",300))
                        if capacity < 1: errors.append("ammo_amount < 1")
                        if rpm < 1: errors.append("rpm < 1")
                    except Exception: capacity = None; errors.append("invalid ammo_amount/rpm")
                    ammo = rid(data.get("ammo"))
                    if not ammo: errors.append("invalid/missing ammo ID")
                    fire = data.get("fire_mode",["unknown"])
                    if not isinstance(fire,list) or not fire or any(str(x).lower() not in ("auto","semi","burst") for x in fire): errors.append("invalid/default unknown fire_mode")
                    if str(data.get("bolt","open_bolt")).lower() not in ("open_bolt","closed_bolt","manual_action"): errors.append("invalid bolt")
                    reload = data.get("reload",{})
                    if not isinstance(reload,dict) or str(reload.get("type","magazine")).lower() not in ("magazine","manual","fuel","inventory"): errors.append("invalid reload type")
                    extended = data.get("extended_mag_ammo_amount")
                    if extended is not None and (not isinstance(extended,list) or len(extended)<3): errors.append("extended_mag_ammo_amount length < 3")
                    recoil = data.get("recoil",{})
                    if isinstance(recoil,dict):
                        for axis in ("pitch","yaw"):
                            for frame in recoil.get(axis,[]) or []:
                                values = frame.get("value",[]) if isinstance(frame,dict) else []
                                if len(values)!=2 or values[0]>values[1] or frame.get("time",0)<0: errors.append("invalid recoil frame "+axis)
                    allowed = data.get("allow_attachment_types",[])
                    if allowed is None: allowed=[]
                    case.update(type=type_, ammo=ammo, base_magazine_capacity=capacity, fire_modes=fire,
                        allowed_attachment_types=[str(x).lower() for x in allowed], builtin_attachments=data.get("builtin_attachments",{}),
                        exclusive_attachments=data.get("exclusive_attachments",{}), extended_mag_ammo_amount=extended)
                if errors: item["errors"].extend(errors)
                else: item["cases"].append(case)
        item["names"] = [names(key) for key in sorted(namekeys)]
        item["name_keys"] = sorted(namekeys)
        item["name_zh_cn"] = sorted({value for n in item["names"] for value in n["name_zh_cn"]})
        item["name_en_us"] = sorted({value for n in item["names"] for value in n["name_en_us"]})
        if kind == "attachments":
            item["flags"] = {"hidden": sorted({bool(c["hidden"]) for c in item["cases"]}),
                "extras": [c.get("extras") for c in item["cases"]],
                "extended_mag_level": [c["extended_mag_level_raw"] for c in item["cases"]],
                "global_data_fields": sorted({key for c in item["cases"] for key in c["raw_data"]}),
                "nonstandard_semantics": "ammo_mod" in (identifier or "") or identifier in
                    ("cib:change","ccrp:weapon_m203","ccrp:flash","ccrp:stock_191_default","hamster:whip")}

        item["static_candidate"] = bool(item["cases"])
        item["status"] = "ambiguous" if item["ambiguous"] else ("static_available_candidate" if item["static_candidate"] else "declared_but_unavailable")
        item["errors"] = sorted(set(item["errors"]))
        if identifier is None: item["errors"].append("invalid index resource ID"); item["static_candidate"]=False
        records[kind].append(item)

    tags = collections.defaultdict(set); allow = collections.defaultdict(set)
    tag_sources = collections.defaultdict(list)
    for path, variants in sorted(resources.items()):
        m = re.fullmatch(r"data/([^/]+)/tacz_tags/attachments/(.+)\.json",path)
        if not m: continue
        ns, local = m.groups(); identifier=ns+":"+local
        target = allow if local.startswith("allow_attachments/") else tags
        key = ns+":"+local[len("allow_attachments/"):] if target is allow else identifier
        for variant in variants:
            tag_sources[identifier].extend(variant["sources"])
            if not isinstance(variant["value"],list): WARNINGS.append("标签不是字符串数组: "+path); continue
            for entry in variant["value"]:
                if not isinstance(entry,str): WARNINGS.append("标签含非字符串: "+path); continue
                is_tag=entry.startswith("#"); normalized=rid(entry[1:] if is_tag else entry)
                if normalized: target[key].add(("#" if is_tag else "")+normalized)
                else: WARNINGS.append("无效标签条目: "+path+" -> "+entry)
    cycles=set(); missing=set()
    def expand(entries, trail=()):
        matched=set(); issues=set()
        for entry in sorted(entries):
            if not entry.startswith("#"): matched.add(entry); continue
            tag=entry[1:]
            if tag in trail:
                cycle=trail[trail.index(tag):]+(tag,); rotations=[cycle[i:-1]+cycle[:i]+(cycle[i],) for i in range(len(cycle)-1)]
                cycles.add(min(rotations)); issues.add("cycle:"+" -> ".join(cycle)); continue
            if tag not in tags: missing.add(tag); issues.add("missing_tag:"+tag); continue
            ids, errors=expand(tags[tag],trail+(tag,)); matched.update(ids); issues.update(errors)
        return matched,issues
    for tag,entries in sorted(tags.items()): expand(entries,(tag,))
    ammo_by_id={x["id"]:x for x in records["ammo"] if x["static_candidate"]}
    attachments_by_id={x["id"]:x for x in records["attachments"] if x["static_candidate"]}
    guns_by_id={x["id"]:x for x in records["guns"]}
    edges=[]
    for gun in records["guns"]:
        tag_ids,issues=expand(allow.get(gun["id"],set()))
        gun["allow_tag_entries"]=sorted(allow.get(gun["id"],set())); gun["expanded_tag_ids"]=sorted(tag_ids)
        gun["tag_issues"]=sorted(issues)
        gun["missing_attachment_ids"]=sorted(tag_ids-set(attachments_by_id))
        gun["ammo_ids"]=sorted({case["ammo"] for case in gun["cases"]})
        gun["available_ammo_ids"]=[x for x in gun["ammo_ids"] if x in ammo_by_id]
        gun["missing_ammo_ids"]=sorted(set(gun["ammo_ids"])-set(ammo_by_id))
        gun["compatible_attachments"]={type_:[] for type_ in TYPES}; gun["tag_allowed_but_closed"]=[]
        gun["ambiguous_attachments"]=[]
        for aid in sorted(tag_ids & set(attachments_by_id)):
            attachment=attachments_by_id[aid]
            outcomes=[]
            for gc in gun["cases"]:
                for ac in attachment["cases"]: outcomes.append(ac["type"] in gc["allowed_attachment_types"])
            if not outcomes: continue
            type_choices=sorted({ac["type"] for ac in attachment["cases"]})
            ambiguous=gun["ambiguous"] or attachment["ambiguous"] or any(x.startswith("cycle:") for x in issues) or len(set(outcomes))>1
            status="ambiguous" if ambiguous else ("compatible" if all(outcomes) else "tag_allowed_but_slot_closed")
            if status=="compatible":
                for type_ in type_choices:
                    if type_ in gun["compatible_attachments"]: gun["compatible_attachments"][type_].append(aid)
                attachment.setdefault("compatible_guns",[]).append(gun["id"])
            elif status=="tag_allowed_but_slot_closed": gun["tag_allowed_but_closed"].append(aid)
            else: gun["ambiguous_attachments"].append(aid)
            edges.append({"gun_id":gun["id"],"attachment_id":aid,"attachment_types":";".join(type_choices),"status":status})
        for aid in gun["available_ammo_ids"]: ammo_by_id[aid].setdefault("compatible_guns",[]).append(gun["id"])
    for kind in ("ammo","attachments"):
        for record in records[kind]: record.setdefault("compatible_guns",[])

    summary={"gunpack_count":len(packages), "mod_jars_scanned":len(list(MOD_ROOT.glob("*.jar"))), "direct_mod_index_declarations":len(jar_declarations),
        "raw_index_declarations":dict(declarations), "unique_declared_ids":{k:len(records[k]) for k in KINDS},
        "static_available_candidates":{k:sum(x["static_candidate"] and not x["ambiguous"] for x in records[k]) for k in KINDS},
        "declared_unavailable":{k:[x["id"] for x in records[k] if not x["static_candidate"]] for k in KINDS},
        "resource_semantic_conflicts":len(conflicts),
        "same_path_tag_union_variants":sum(x["resolution"]=="tag_union" for x in multiple_definitions),
        "same_path_language_variants":sum(x["resolution"]=="language_key_union_or_name_variants" for x in multiple_definitions),
        "gun_ammo_edges":sum(len(g["available_ammo_ids"]) for g in records["guns"]),
        "ammo_ids_referenced_by_guns":len({aid for g in records["guns"] for aid in g["available_ammo_ids"]}),
        "parse_errors":len(parse_errors), "missing_attachment_tags":len(missing),
        "tag_cycles":len(cycles), "gun_attachment_edges":len(edges), "compatible_edges":sum(e["status"]=="compatible" for e in edges),
        "tag_allowed_closed_edges":sum(e["status"]=="tag_allowed_but_slot_closed" for e in edges)}
    limitations=["固定TaCZ NeoForge r7源码规则的只读文件分析；静态候选不是游戏实测注册或模型正确显示。",
        "所有同路径不同语义资源保留variants，不能按排序声称runtime winner；run/tacz扫描本身不保证顺序。",
        "附件标签同路径全部并集，普通gun/data/index同路径运行时存在优先级；本报告对冲突关系标ambiguous。",
        "兼容=静态有效枪/附件+开放实际类型槽+标签允许；判定排除占用槽/枪械锁等当前装备状态，未实测挂点；其他模组Mixin可能进一步改变规则。",
        "model_resource_exists只说明文件存在，不证明模型/挂点/尺寸/开镜画面正确；扩容可由枪内部模型组呈现。",
        "r7无ammo等价标签、多ammo字段、附件黑名单/extension或Wiki内测slot_adapter能力。",
        "builtin固定附件、exclusive专属属性单独列出，不等同安装允许列表。",
        "弹药严格单ID匹配；名称口径相同不等同ID互通；伤害来自枪械bullet数据。"]
    sources=["logs/analysis-src/tacz-r7/com/tacz/guns/api/item/gun/AbstractGunItem.java:284 allowAttachment; :299 allowAttachmentType",
        "logs/analysis-src/tacz-r7/com/tacz/guns/api/item/nbt/AmmoItemDataAccessor.java:51 isAmmoOfGun",
        "logs/analysis-src/tacz-r7/com/tacz/guns/resource/manager/AttachmentsTagManager.java:45 prepare/apply",
        "logs/analysis-src/tacz-r7/com/tacz/guns/util/AllowAttachmentTagMatcher.java:25 match/treeSearch",
        "logs/analysis-src/tacz-r7/com/tacz/guns/resource/index/CommonGunIndex.java:40 checkIndex/checkData",
        "logs/analysis-src/tacz-r7/com/tacz/guns/resource/index/CommonAttachmentIndex.java:26 checkIndex/checkData",
        "logs/analysis-src/tacz-r7/com/tacz/guns/resource/index/CommonAmmoIndex.java:21 checkIndex",
        "logs/analysis-src/tacz-r7/com/tacz/guns/resource/GunPackLoader.java:205 scanExtensions; DelegatingPackResources.java:52/77"]
    inventory={"schema_version":1,"basis":"static-r7-files-not-runtime","summary":summary,"limitations":limitations,"source_methods":sources,
        "packages":packages,"direct_mod_index_declarations":jar_declarations,"parse_errors":parse_errors,"warnings":sorted(set(WARNINGS)),
        "resource_conflicts":conflicts,"same_path_variants":multiple_definitions,"missing_tags":sorted(missing),"tag_cycles":[list(x) for x in sorted(cycles)],
        "tag_union":{k:sorted(v) for k,v in sorted(tags.items())}, "tag_sources":dict(sorted(tag_sources.items())),
        "guns":records["guns"],"ammo":records["ammo"],"attachments":records["attachments"],"gun_attachment_edges":edges}
    assert len(guns_by_id)==len(records["guns"]), "duplicate normalized gun IDs"
    for kind in KINDS:
        assert len({x["id"] for x in records[kind]})==len(records[kind]), "duplicate asset ID"
        for item in records[kind]:
            for case in item["cases"]:
                if kind != "ammo": assert case["data_resource"] in resources, "candidate data reference missing"
    for gun in records["guns"]:
        assert all(aid in ammo_by_id for aid in gun["available_ammo_ids"])
        for type_, identifiers in gun["compatible_attachments"].items():
            for aid in identifiers:
                assert aid in attachments_by_id and gun["id"] in attachments_by_id[aid]["compatible_guns"]
    for edge in edges:
        assert edge["gun_id"] in guns_by_id and edge["attachment_id"] in attachments_by_id
    inventory["summary"]["reference_consistency_checks"] = "passed"
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/"compatibility-inventory.json").write_text(json.dumps(inventory,ensure_ascii=False,indent=2,allow_nan=False)+"\n",encoding="utf-8")

    def caption(item):
        for n in item["names"]:
            labels=n["name_zh_cn"] or n["name_en_us"]
            if labels:return " / ".join(labels)
        return item["id"] or "INVALID"
    def desc(identifier, collection):
        record=collection.get(identifier)
        return f"{caption(record)} (`{identifier}`)" if record else f"`{identifier}`（无静态可用候选）"
    def csv_write(filename, rows, fields):
        with (OUT/filename).open("w",encoding="utf-8-sig",newline="") as stream:
            writer=csv.DictWriter(stream,fieldnames=fields);writer.writeheader();writer.writerows(rows)
    for kind,filename in (("guns","guns.csv"),("ammo","ammo.csv"),("attachments","attachments.csv")):
        rows=[]
        for item in records[kind]:
            rows.append({"id":item["id"],"name_zh_cn":";".join(sorted({v for n in item["names"] for v in n["name_zh_cn"]})),
                "name_en_us":";".join(sorted({v for n in item["names"] for v in n["name_en_us"]})),
                "name_key":";".join(n["name_key"] for n in item["names"]), "status":item["status"],
                "type":";".join(sorted({str(c.get("type","")) for c in item["cases"]})),
                "data_refs":";".join(sorted({str(c.get("id") or c.get("raw")) for c in item["data_references"]})),
                "display_refs":";".join(str(d["id"]) for d in item["displays"]),
                "model_resource_exists":any(v["model_resource_exists"] for d in item["displays"] for v in d["variants"]),
                "ammo_ids":";".join(item.get("ammo_ids",[])),
                "extended_mag_level_raw":";".join(str(c.get("extended_mag_level_raw","")) for c in item["cases"]),
                "compatible_guns":";".join(item.get("compatible_guns",[])),
                "sources":";".join(item["sources"]),"errors":";".join(item["errors"]),
                "hidden":";".join(str(c["hidden"]) for c in item["cases"]),
                "global_data_fields":";".join(item.get("flags",{}).get("global_data_fields",[])),
                "extras":json.dumps(item.get("flags",{}).get("extras",[]),ensure_ascii=False)})
        csv_write(filename,rows,list(rows[0]) if rows else ["id"])
    csv_write("gun-attachment-edges.csv",[{**e,"gun_name":caption(guns_by_id[e["gun_id"]]),
        "attachment_name":caption(attachments_by_id[e["attachment_id"]])} for e in edges],
        ["gun_id","attachment_id","attachment_types","status","gun_name","attachment_name"])

    header=["# 按当前文件规则的 TaCZ r7 静态兼容清点", "", "本报告未启动游戏；下文‘可用候选’表示源码规定的服务端 index/data 静态条件通过，不是已在游戏注册的实测结论。", "",
        "来源说明：条目中的 `run/...` 相对于 `"+str(ROOT/"neoforge-1.21.1")+"`；`包.zip!entry` 或 `模组.jar!entry` 的 `!` 后是压缩包内部路径，不是单独的磁盘文件。源码方法中的 `logs/...` 相对于工作区 `"+str(ROOT)+"`。", "", "```json",json.dumps(summary,ensure_ascii=False,indent=2),"```", "", "## 边界与来源", ""]
    header += ["- "+x for x in limitations]+["", "源码方法：", ""]+["- `"+x+"`" for x in sources]+[""]
    header += ["## 诊断", "", "不同语义资源冲突："+str(len(conflicts))+"；解析失败："+str(len(parse_errors))+"；缺失标签："+str(len(missing))+"；标签环："+str(len(cycles))+"。", ""]
    for kind in KINDS:
        for item in records[kind]:
            if item["errors"]: header.append("- `"+item["id"]+"`："+"；".join(item["errors"]))
    for tag in sorted(missing):header.append("- 缺少标签 `#"+tag+"`")
    for cycle in sorted(cycles):header.append("- 标签环："+" → ".join(cycle))
    header.append("- 同路径不同标签数组："+str(sum(x["resolution"]=="tag_union" for x in multiple_definitions))+" 组，依 r7 并集合并；语言资源变体："+str(sum(x["resolution"]=="language_key_union_or_name_variants" for x in multiple_definitions))+" 组，翻译名称列保留各值。")
    for conflict in conflicts:header.append("- 不同语义资源冲突 `"+conflict["resource"]+"`；详见 JSON variants，未选择胜出定义。")
    for error in parse_errors:header.append("- 解析失败 `"+error["source"]+"`："+error["error"])
    lines=header+["", "## 全部枪械逐项", ""]
    for gun in records["guns"]:
        lines += ["### "+caption(gun)+" · `"+gun["id"]+"`", "", "- 状态："+gun["status"],
            "- 弹药："+("；".join(desc(x,ammo_by_id) for x in gun["ammo_ids"]) or "无有效枪械数据"),
            "- 英文名："+" / ".join(gun["name_en_us"]),
            "- 基础弹匣："+" / ".join(str(c.get("base_magazine_capacity")) for c in gun["cases"]),
            "- 数据引用："+"；".join(sorted({c["data_ref"] for c in gun["cases"]})),
            "- 声明来源："+"；".join("`"+s+"`" for s in gun["sources"])]
        for type_ in TYPES:lines.append("- "+type_+"："+("；".join(desc(x,attachments_by_id) for x in gun["compatible_attachments"][type_]) or "无"))
        lines.append("- 标签允许但槽位关闭："+("；".join(desc(x,attachments_by_id) for x in gun["tag_allowed_but_closed"]) or "无"))
        lines.append("- 不确定关系："+("；".join(gun["ambiguous_attachments"]+gun["tag_issues"]) or "无"))
        for c in gun["cases"]:
            lines.append("- 原厂固定附件（单独记录）：`"+json.dumps(c["builtin_attachments"],ensure_ascii=False,separators=(",",":"))+"`")
            lines.append("- 专属属性附件 ID（单独记录）："+("；".join("`"+x+"`" for x in c["exclusive_attachments"]) or "无"))
        lines.append("")
    (OUT/"全枪械当前兼容逐项.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    lines=header+["", "## 全弹药声明与反向兼容", ""]
    for kind in ("ammo","attachments"):
        if kind=="attachments":lines += ["## 全配件声明与反向兼容", ""]
        for item in records[kind]:
            lines += ["### "+caption(item)+" · `"+item["id"]+"`", "", "- 状态："+item["status"],
                "- name_key："+"；".join("`"+n["name_key"]+"`" for n in item["names"]),
                "- 中文："+" / ".join(v for n in item["names"] for v in n["name_zh_cn"]),
                "- 英文："+" / ".join(v for n in item["names"] for v in n["name_en_us"]),
                "- 来源："+"；".join("`"+s+"`" for s in item["sources"]),
                "- 显示引用："+"；".join("`"+str(d["id"])+"`" for d in item["displays"]),
                "- 3D模型资源存在："+str(any(v["model_resource_exists"] for d in item["displays"] for v in d["variants"]))+"（仅文件存在，不代表渲染可用）"]
            if kind=="attachments":
                lines.append("- hidden："+str(item["flags"]["hidden"])+"；全局 data 字段："+", ".join(item["flags"]["global_data_fields"]))
                lines.append("- extras：`"+json.dumps(item["flags"]["extras"],ensure_ascii=False,separators=(",",":"))+"`")
                lines.append("- 数据引用："+"；".join(str(v["id"])+"（存在="+str(v["exists"])+"）" for v in item["data_references"]))
                lines.append("- 类型与扩容等级原始值："+"；".join(str(c["type"])+" / "+str(c["extended_mag_level_raw"]) for c in item["cases"]))
                if "ammo_mod" in item["id"]:lines.append("- 语义提醒：弹药改装件，不能仅凭 extended_mag 类型当成普通扩容弹匣。")
                if item["id"] in ("cib:change","ccrp:weapon_m203","ccrp:flash","ccrp:stock_191_default","hamster:whip"):
                    lines.append("- 语义提醒：特殊转换/下挂/功能或模型件，当前标签兼容不代表对应 PUBG 配件。")
            lines += ["- 可兼容枪械："+("；".join(desc(x,guns_by_id) for x in item["compatible_guns"]) or "无"), ""]
    (OUT/"全弹药与配件注册清单.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    assert len(guns_by_id)==len(records["guns"]), "duplicate gun IDs after resource normalization"
    for edge in edges:
        assert edge["gun_id"] in guns_by_id and edge["attachment_id"] in attachments_by_id
    print(json.dumps(summary,ensure_ascii=False,indent=2))
    for kind in KINDS:
        for item in records[kind]:
            if not item["static_candidate"]:print(kind,item["id"],"; ".join(item["errors"]))

if __name__ == "__main__": main()
