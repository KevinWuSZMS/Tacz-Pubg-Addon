"""Static checks of the exact CBRG weapon resource chains, without loading Minecraft."""
from pathlib import Path
from collections import defaultdict
import json, re

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT/'downloads/pubg-gunpacks/staging'
OVERLAY = ROOT/'downloads/pubg-gunpacks/cbrg-release'
OUT = ROOT/'docs/pubg-weapons/gunpack-static-audit.json'

def parse(path):
    text = path.read_text(encoding='utf-8-sig')
    # Preserve JSON strings while removing GSON-style comments.
    text = re.sub(r'"(?:\\.|[^"\\])*"|//[^\r\n]*|/\*[\s\S]*?\*/',
                  lambda m: m[0] if m[0].startswith('"') else '', text)
    text = re.sub(r',\s*([}\]])', r'\1', text)
    return json.loads(text)

files = defaultdict(list)
for pack in sorted(BASE.iterdir()):
    if pack.is_dir() and (pack/'gunpack.meta.json').is_file():
        for path in pack.rglob('*'):
            if path.is_file():
                files[path.relative_to(pack).as_posix()].append(path)

errors, checked = [], []
def locate(rid, side, directory, suffix, owner):
    if not rid:
        return None
    ns, name = rid.split(':',1) if ':' in rid else ('minecraft',rid)
    key = f'{side}/{ns}/{directory}/{name}{suffix}'
    candidates = files.get(key, [])
    if not candidates and directory == 'animations':
        key = f'{side}/{ns}/{directory}/{name}.gltf'
        candidates = files.get(key, [])
    if not candidates:
        errors.append(dict(owner=owner, reference=rid, expected=key))
        return None
    checked.append(key)
    return candidates[-1]

weapons = []
for pack in OVERLAY.iterdir():
    if not pack.is_dir() or pack.name == 'remove_unused_content':
        continue
    for path in pack.glob('data/*/index/guns/**/*.json'):
        relative = path.relative_to(pack).as_posix()
        ns = path.relative_to(pack).parts[1]
        gun = ns+':'+relative.split('/index/guns/',1)[1][:-5]
        try:
            actual = BASE/pack.name/relative
            index = parse(actual)
            data_path = locate(index.get('data'), 'data','data/guns','.json',gun)
            display_path = locate(index.get('display'), 'assets','display/guns','.json',gun)
            data = parse(data_path) if data_path else {}
            display = parse(display_path) if display_path else {}
            ammo_path = locate(data.get('ammo'),'data','index/ammo','.json',gun)
            if ammo_path:
                ammo_index = parse(ammo_path)
                ammo_display_path = locate(ammo_index.get('display'),'assets','display/ammo','.json',gun)
                if ammo_display_path:
                    ammo_display = parse(ammo_display_path)
                    for field, directory, suffix in [('model','geo_models','.json'),('texture','textures','.png'),('slot','textures','.png')]:
                        if ammo_display.get(field):
                            locate(ammo_display[field],'assets',directory,suffix,gun+' ammo')
            locate(data.get('script'),'data','scripts','.lua',gun)
            for field, directory, suffix in [('model','geo_models','.json'),('texture','textures','.png'),
                ('slot','textures','.png'),('hud','textures','.png'),('hud_empty','textures','.png'),
                ('animation','animations','.animation.json'),('state_machine','scripts','.lua')]:
                locate(display.get(field),'assets',directory,suffix,gun)
            if isinstance(display.get('lod'),dict):
                locate(display['lod'].get('model'),'assets','geo_models','.json',gun+' LOD')
                locate(display['lod'].get('texture'),'assets','textures','.png',gun+' LOD')
            for sound in display.get('sounds',{}).values():
                if isinstance(sound,str):
                    locate(sound,'assets','tacz_sounds','.ogg',gun+' sound')
            weapons.append(dict(id=gun,name_key=index.get('name'),ammo=data.get('ammo'),
                capacity=data.get('ammo_amount'),extended_capacities=data.get('extended_mag_ammo_amount'),
                rpm=data.get('rpm'),bolt=data.get('bolt'),fire_modes=data.get('fire_mode'),
                model=display.get('model'),script=data.get('script'),
                index_file=actual.relative_to(ROOT).as_posix()))
        except Exception as exc:
            errors.append(dict(owner=gun,error=str(exc)))

collisions, merged_tags = [], []
for key, paths in files.items():
    if len(paths)>1 and len({p.read_bytes() for p in paths})>1 and key.startswith(('assets/','data/')):
        entry = dict(resource=key,files=[p.relative_to(ROOT).as_posix() for p in paths])
        if '/tacz_tags/' in key:
            merged_tags.append(entry)
        else:
            collisions.append(entry)

report = dict(checkedAt='2026-10-17', method='Filesystem-only; does not execute Minecraft or Lua',
    pack='CBRG 0.4.8',packFolders=[p.name for p in BASE.iterdir() if p.is_dir() and (p/'gunpack.meta.json').exists()],
    gunCount=len(weapons), checkedReferences=len(checked), errors=errors, conflictingPaths=collisions,
    mergedAttachmentTags=merged_tags, weapons=weapons)
OUT.parent.mkdir(parents=True,exist_ok=True)
OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k not in ('weapons','conflictingPaths','mergedAttachmentTags')},ensure_ascii=False,indent=2))
print('Conflicting resource paths:',len(collisions))
raise SystemExit(bool(errors or collisions))
