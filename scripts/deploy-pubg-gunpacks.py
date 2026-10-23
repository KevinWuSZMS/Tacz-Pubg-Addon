"""Deploy the audited staged CBRG pack, preserving the previous instance files."""
from pathlib import Path
from datetime import datetime
import hashlib, json, shutil, zipfile, re

ROOT = Path(__file__).resolve().parents[1]
STAGE = ROOT/'downloads/pubg-gunpacks/staging'
RUN = ROOT/'neoforge-1.21.1/run'
audit = json.loads((ROOT/'docs/pubg-weapons/gunpack-static-audit.json').read_text(encoding='utf-8'))
assert not audit['errors'] and not audit['conflictingPaths'], 'Resolve audit failures first'
stamp = datetime.now().strftime('%Y%m%d-%H%M%S')
BACKUP = ROOT/'logs/source-backups'/('pubg-gunpacks-'+stamp)
BACKUP.mkdir(parents=True)
shutil.copytree(RUN/'tacz',BACKUP/'tacz')
shutil.copy2(RUN/'config/tacz-pre.toml',BACKUP/'tacz-pre.toml')

manifest = dict(installedAt=datetime.now().isoformat(),backup=str(BACKUP),scope='workspace instance only',
                minecraft='1.21.1',tacz='1.1.8-hotfix-r7',gameStarted=False,files=[])
for pack in sorted(STAGE.iterdir()):
    if not pack.is_dir() or not (pack/'gunpack.meta.json').exists(): continue
    if pack.name == 'tacz_default_gun':
        shutil.copytree(pack,RUN/'tacz'/pack.name,dirs_exist_ok=True)
        continue
    filename = pack.name+'+1.21.1.zip'
    output = ROOT/'downloads/pubg-gunpacks'/filename
    with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for path in sorted(pack.rglob('*')):
            if path.is_file(): archive.write(path,path.relative_to(pack).as_posix())
    with zipfile.ZipFile(output) as archive:
        assert archive.getinfo('gunpack.meta.json')
        assert archive.testzip() is None
        assert not any('/recipes/' in x for x in archive.namelist())
    target = RUN/'tacz'/filename
    if target.exists(): shutil.copy2(target,BACKUP/filename)
    shutil.copy2(output,target)
    manifest['files'].append(dict(path=str(target),sha256=hashlib.sha256(target.read_bytes()).hexdigest(),size=target.stat().st_size))

version = json.loads((ROOT/'downloads/pubg-gunpacks/pack-upgrader-version.json').read_text(encoding='utf-8-sig'))
file = version['files'][0]
source = ROOT/'downloads/pubg-gunpacks'/file['filename']
assert hashlib.sha512(source.read_bytes()).hexdigest() == file['hashes']['sha512']
target = RUN/'mods'/file['filename']
if target.exists(): shutil.copy2(target,BACKUP/file['filename'])
shutil.copy2(source,target)
manifest['files'].append(dict(path=str(target),sha256=hashlib.sha256(target.read_bytes()).hexdigest(),size=target.stat().st_size))

config = RUN/'config/tacz-pre.toml'
text = config.read_text(encoding='utf-8-sig')
assert 'DefaultPackDebug' in text
text = re.sub(r'(DefaultPackDebug\s*=\s*)false\b',r'\g<1>true',text)
config.write_text(text,encoding='utf-8')
manifest['defaultPackPolicy']='DefaultPackDebug=true; preserve current r7 default plus author overlay'
manifest['lootConfigPolicy']='Existing world loot configurations are not changed; add or adjust groups through pubgdebug config'
(ROOT/'logs/pubg-gunpack-deployment.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(dict(backup=str(BACKUP),deployedGunpackZips=len(manifest['files'])-1,
    defaultPackUpdated=True,upgrader=version['version_number'],gameStarted=False),ensure_ascii=False,indent=2))
