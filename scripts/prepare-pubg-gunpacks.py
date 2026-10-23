"""Local r7 compatibility changes in staging; original author archives remain intact.

Recipe conversion follows MUKSC/TaCZPackUpgrader 2.1.3 behavior (LGPL-3.0-or-later).
https://github.com/MUKSC/TaCZPackUpgrader
This script handles staged JSON only, never launches Minecraft or executes gun Lua.
"""
from pathlib import Path
from collections import defaultdict, Counter
import hashlib, json, re, zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT/'downloads/pubg-gunpacks/staging'
OVERLAY = ROOT/'downloads/pubg-gunpacks/cbrg-release'
log_path = ROOT/'docs/pubg-weapons/local-compatibility-changes.json'
LOG = json.loads(log_path.read_text(encoding='utf-8')) if log_path.exists() else []

def parse(path):
    text = path.read_text(encoding='utf-8-sig')
    text = re.sub(r'"(?:\\.|[^"\\])*"|//[^\r\n]*|/\*[\s\S]*?\*/',lambda m:m[0] if m[0].startswith('"') else '',text)
    text = re.sub(r',\s*([}\]])',r'\1',text)
    return json.loads(text)

def write(path, obj, reason):
    before = path.read_bytes() if path.exists() else b''
    content = (json.dumps(obj,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(content)
    LOG.append(dict(file=path.relative_to(ROOT).as_posix(),reason=reason,
        before_sha256=hashlib.sha256(before).hexdigest(),after_sha256=hashlib.sha256(content).hexdigest()))

with zipfile.ZipFile(ROOT/'downloads/pubg-gunpacks/tacz-pack-upgrader-2.1.3.jar') as archive:
    conversions = json.loads(archive.read('conversion.json'))

def ingredient(obj):
    if isinstance(obj,list):
        for entry in obj: ingredient(entry)
        return
    if obj.get('type') in ('forge:nbt','forge:partial_nbt') and 'item' in obj:
        obj['partial'] = obj['type'] == 'forge:partial_nbt'
        obj['type'] = 'tacz:nbt'
        obj['items'] = obj.pop('item')
    if 'tag' in obj:
        change = conversions.get(obj['tag'], {'type':'tag','value':obj['tag'].replace('forge:','c:')})
        obj[change['type']] = change['value']
        if change['type'] != 'tag': obj.pop('tag',None)

def stack(obj):
    if 'item' in obj: obj['id'] = obj.pop('item')
    if 'nbt' in obj: obj.setdefault('components',{})['minecraft:custom_data'] = obj.pop('nbt')

def recipe(obj):
    kind = obj.get('type')
    if kind == 'tacz:gun_smith_table_crafting':
        for material in obj.get('materials',[]): ingredient(material['item'])
        result = obj.get('result',{})
        if result.get('type') == 'custom' and isinstance(result.get('item'),dict): stack(result['item'])
    elif kind == 'minecraft:crafting_shaped':
        for entry in obj.get('key',{}).values(): ingredient(entry)
        if isinstance(obj.get('result'),dict): stack(obj['result'])
    elif kind == 'minecraft:crafting_shapeless':
        for entry in obj.get('ingredients',[]): ingredient(entry)
        if isinstance(obj.get('result'),dict): stack(obj['result'])

packs = [x for x in sorted(BASE.iterdir()) if x.is_dir() and (x/'gunpack.meta.json').exists()]
for pack in packs:
    for old in pack.glob('data/*/recipes/**/*.json'):
        obj = parse(old)
        recipe(obj)
        parts = list(old.relative_to(pack).parts)
        parts[2] = 'recipe'
        new = pack.joinpath(*parts)
        write(new,obj,'1.21.1 recipe conversion; author overlay takes precedence over existing recipe')
        old.unlink()
    for path in pack.glob('data/*/data/blocks/*.json'):
        obj = parse(path)
        before = json.dumps(obj,sort_keys=True)
        for tab in obj.get('tabs',[]):
            if isinstance(tab.get('icon'),dict): stack(tab['icon'])
        if json.dumps(obj,sort_keys=True) != before: write(path,obj,'1.21.1 workbench icon components')
    for path in pack.glob('data/*/tacz_loot_injectors/**/*.json'):
        obj = parse(path)
        changed = False
        for pool in obj.get('pools',[]):
            for entry in pool.get('entries',[]):
                for function in entry.get('functions',[]):
                    if function.get('function') == 'minecraft:set_nbt':
                        function['function'] = 'minecraft:set_components'
                        function['components'] = {'minecraft:custom_data':function.pop('tag')}
                        changed = True
        if changed: write(path,obj,'1.21.1 loot injector components')

files = defaultdict(list)
for pack in packs:
    for path in pack.rglob('*'):
        if path.is_file(): files[path.relative_to(pack).as_posix()].append(path)

def exists(rid, directory, suffix):
    if ':' not in rid: return False
    ns,name = rid.split(':',1)
    return f'assets/{ns}/{directory}/{name}{suffix}' in files

display_targets = set()
for pack in OVERLAY.iterdir():
    if not pack.is_dir() or pack.name == 'remove_unused_content': continue
    for index in pack.glob('data/*/index/guns/**/*.json'):
        rid = parse(index).get('display')
        if rid:
            ns,name = rid.split(':',1)
            display_targets.update(files.get(f'assets/{ns}/display/guns/{name}.json',[]))
for pack in OVERLAY.iterdir():
    if not pack.is_dir() or pack.name == 'remove_unused_content': continue
    for author_file in pack.glob('assets/*/display/guns/**/*.json'):
        display_targets.add(BASE/pack.name/author_file.relative_to(pack))
for path in sorted(display_targets):
        obj = parse(path)
        changes = []
        if obj.get('model') == 'gz:gun/tactical_sasg_12k':
            obj['model'] = 'gz:gun/tactical_sasg_12k_geo'
            obj['texture'] = 'gz:gun/uv/tactical_sasg_12k'
            changes.append('correct S12K model/UV paths')
        lod = obj.get('lod')
        if lod and (not exists(lod.get('model',''),'geo_models','.json') or not exists(lod.get('texture',''),'textures','.png')):
            obj.pop('lod')
            changes.append('remove absent optional LOD; r7 falls back to main model')
        for key,rid in list(obj.get('sounds',{}).items()):
            if not isinstance(rid,str) or exists(rid,'tacz_sounds','.ogg'): continue
            replacement = None
            if rid == 'cbrg:melee_bayonet/melee_bayonet': replacement = 'tacz:melee_bayonet/melee_bayonet_01'
            elif rid == 'cbrg:melee_stock/melee_stock': replacement = 'tacz:melee_stock/melee_stock_01'
            elif rid.startswith('ccbrg:'): replacement = rid.replace('ccbrg:','cbrg:',1)
            elif rid in ('cbrg:head_hit','cbrg:flesh_hit','cbrg:kill'): replacement = rid.replace('cbrg:','tacz:',1)
            elif key == 'silence_3p' and exists(obj['sounds'].get('silence',''),'tacz_sounds','.ogg'):
                replacement = obj['sounds']['silence']
            if replacement and exists(replacement,'tacz_sounds','.ogg'):
                obj['sounds'][key] = replacement
                changes.append('sound fallback '+key+': '+rid+' -> '+replacement)
            else:
                obj['sounds'].pop(key)
                changes.append('remove missing optional sound '+key+': '+rid+' (already silent in r7)')
        if changes: write(path,obj,'; '.join(changes))

# r7 merges custom attachment tags. Other duplicated resources are single-value
# lookups, so make their bytes identical rather than depending on folder order.
for key, paths in files.items():
    if len(paths)<2 or '/tacz_tags/' in key: continue
    if not key.startswith(('assets/','data/')): continue
    if len({p.read_bytes() for p in paths}) == 1: continue
    choices = [p for p in paths if (OVERLAY/p.relative_to(BASE)).is_file()]
    chosen = next((p for p in choices if 'tacz_default_gun' in p.parts),None)
    chosen = chosen or next(iter(choices),None)
    chosen = chosen or next((p for p in paths if 'tacz_default_gun' in p.parts),paths[0])
    canonical = chosen.read_bytes()
    for target in paths:
        if target.read_bytes() == canonical: continue
        before = target.read_bytes()
        target.write_bytes(canonical)
        LOG.append(dict(file=target.relative_to(ROOT).as_posix(),reason='make shared single-value resource deterministic',
            canonical_source=chosen.relative_to(ROOT).as_posix(),before_sha256=hashlib.sha256(before).hexdigest(),
            after_sha256=hashlib.sha256(canonical).hexdigest()))

output = ROOT/'docs/pubg-weapons/local-compatibility-changes.json'
output.write_text(json.dumps(LOG,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(dict(changedFiles=len(LOG),recipeConversions=sum('recipe conversion' in x['reason'] for x in LOG),
    displayFixes=sum('/display/guns/' in x['file'] for x in LOG), sharedResourceFixes=sum('deterministic' in x['reason'] for x in LOG)),indent=2))
