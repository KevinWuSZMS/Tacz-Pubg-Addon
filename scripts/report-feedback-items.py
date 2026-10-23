"""Record source/artifact verification without starting Minecraft."""
from pathlib import Path
import hashlib, json, re, zipfile

ROOT = Path(__file__).resolve().parents[1]
BEFORE = ROOT/'logs/source-backups/feedback-items-20261017-230727'
ARTIFACT = ROOT/'neoforge-1.21.1/build/libs/tacz_pubg-1.0.0.jar'
log = (ROOT/'logs/feedback-items-build.log').read_text(encoding='utf-8',errors='replace')
checks = [{'name':m.group(1),'assertions':int(m.group(2))} for m in re.finditer(r'([^\r\n]+?) checks passed: (\d+)',log)]
ammo = re.search(r'AmmoGroupingChecks: (\d+) assertions passed',log)
if ammo: checks.append({'name':'AmmoGroupingChecks','assertions':int(ammo[1])})
assert 'BUILD SUCCESSFUL' in log
with zipfile.ZipFile(ARTIFACT) as jar:
    names = jar.namelist()
    removed = [n for n in names if 'example_block' in n or 'example_item' in n]
    assert not removed
    assert 'assets/tacz_pubg/ground_loot_footprints.json' in names
    for language in ['zh_cn','en_us']:
        translations = json.loads(jar.read(f'assets/tacz_pubg/lang/{language}.json'))
        assert 'key.tacz_pubg.pickup_loot' in translations
        assert 'key.tacz_pubg.area_save' in translations
        assert not any('example_block' in key or 'example_item' in key for key in translations)

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
changes = []
for before, after, label in [(BEFORE/'src',ROOT/'neoforge-1.21.1/src','src'),(BEFORE/'docs',ROOT/'docs','docs')]:
    old = {p.relative_to(before).as_posix():p for p in before.rglob('*') if p.is_file()}
    new = {p.relative_to(after).as_posix():p for p in after.rglob('*') if p.is_file()}
    for name in sorted(old.keys() | new.keys()):
        if name not in old: state='added'
        elif name not in new: state='deleted'
        elif sha(old[name]) != sha(new[name]): state='modified'
        else: continue
        changes.append({'file':label+'/'+name,'state':state})

# Check project-authored Markdown only; preserve upstream snapshots as source evidence.
broken = []
for path in [ROOT/'README.md',*list((ROOT/'docs').glob('*.md'))]:
    if path.name in ('NeoForge-1.21.1-开发教程全集.md','TaCZ-官方附属制作教程.md'): continue
    for target in re.findall(r'\]\(([^)]+)\)',path.read_text(encoding='utf-8-sig')):
        target = target.strip('<>').split('#',1)[0]
        if not target or re.match(r'^[a-zA-Z]+:',target) or target.startswith('/'): continue
        if not (path.parent/target).exists(): broken.append({'file':str(path.relative_to(ROOT)),'target':target})

footprints = json.loads((ROOT/'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/ground_loot_footprints.json').read_text(encoding='utf-8'))
report = {'date':'2026-10-17','build':'success','gameStarted':False,
    'worldConfigMigration':'automatic on next world load; no live world was migrated in this task',
    'artifact':str(ARTIFACT),'artifactSha256':sha(ARTIFACT),'exampleResourcesRemaining':removed,
    'checks':checks,'assertions':sum(x['assertions'] for x in checks),'brokenLocalLinks':broken,
    'modelFootprints':{key:len(footprints[key]) for key in ['guns','ammo','attachments']},
    'backup':str(BEFORE),'changes':changes}
(ROOT/'logs/feedback-items-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k not in ['changes','checks']},ensure_ascii=False,indent=2))
raise SystemExit(bool(broken))
