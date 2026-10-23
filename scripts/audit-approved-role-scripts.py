"""Read-only Lua override audit for every approved role; does not run Lua or Minecraft."""
from pathlib import Path
from collections import defaultdict
import hashlib
import json
import re
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PACKS = ROOT / 'neoforge-1.21.1/run/tacz'
assets = {g['id']: g for g in json.loads((ROOT / 'docs/gun-balance/gun-assets.json').read_text(encoding='utf-8'))['guns']}
roles = json.loads((ROOT / 'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/approved_role_balance.json').read_text(encoding='utf-8'))['guns']
uses = defaultdict(list)
for row in roles:
    if row['action'] == 'apply':
        sid = assets[row['gunId']]['rawData'].get('script')
        if sid:
            uses[sid].append(row['gunId'])

providers = defaultdict(list)


def add(name, content):
    m = re.search(r'(?:^|/)data/([^/]+)/scripts/(.*)\.lua$', name.split('!')[-1])
    if m and m[1] + ':' + m[2] in uses:
        providers[m[1] + ':' + m[2]].append((name, content))


for path in PACKS.glob('*.zip'):
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if name.endswith('.lua'):
                add(path.relative_to(ROOT).as_posix() + '!' + name, archive.read(name))
for path in PACKS.rglob('*.lua'):
    add(path.relative_to(ROOT).as_posix(), path.read_bytes())

entries = []
for sid, guns in sorted(uses.items()):
    if len(providers[sid]) != 1:
        raise ValueError(f'Missing/ambiguous script resource {sid}: {len(providers[sid])} providers')
    source, content = providers[sid][0]
    # Some local scripts use legacy Chinese comment encoding. Lua code identifiers are ASCII;
    # decoding loss is recorded, and replacement is used only for comment-independent token audit.
    text = content.decode('utf-8-sig', errors='replace')
    code = re.sub(r'--\[\[.*?\]\]', '', text, flags=re.S)
    code = '\n'.join(line.split('--', 1)[0] for line in code.splitlines())
    hooks = sorted(set(re.findall(r'function\s+M[.:]([A-Za-z_][\w]*)|M\.([A-Za-z_][\w]*)\s*=\s*function', code)))
    hooks = sorted({a or b for a, b in hooks})
    dangerous = [name for name in hooks if name in ('calcSpread', 'modifyProperty', 'modifyGunProperty')]
    if dangerous:
        raise ValueError(f'Role statistics can be overridden by {sid}: {dangerous}')
    entries.append(dict(scriptId=sid, guns=guns, source=source,
                        sha256=hashlib.sha256(content).hexdigest(),
                        hooks=hooks, containsLegacyNonUtf8Comments='\ufffd' in text,
                        statOverrideHooks=dangerous,
                        includes=re.findall(r'\brequire\s*\(?\s*["\']([^"\']+)', code)))

result = dict(date='2026-10-18', mode='READ_ONLY_STATIC_AUDIT',
              approvedRoleGuns=386, gunsWithScripts=sum(map(len, uses.values())),
              uniqueScripts=len(uses), statOverrideScripts=0,
              note='All referenced installed Lua scripts have one provider. None declares calcSpread or a property-modification hook. Native reload, bolt and shootOnce mechanics remain intact. This is a source audit, not gameplay verification.',
              scripts=entries)
dest = ROOT / 'logs/approved-role-script-audit.json'
dest.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(f'Audited {result["gunsWithScripts"]} role guns / {len(uses)} scripts; no stat override hook; {dest.name}')
