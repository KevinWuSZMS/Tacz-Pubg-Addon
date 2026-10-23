"""Assemble author-recommended CBRG folders without changing the running instance."""
from pathlib import Path
import hashlib, json, shutil, zipfile
from importlib.util import spec_from_file_location, module_from_spec

ROOT = Path(__file__).resolve().parents[1]
DOWNLOADS = ROOT/'downloads/pubg-gunpacks'
STAGE = DOWNLOADS/'staging'
spec = spec_from_file_location('download_gunpacks', ROOT/'scripts/download-pubg-gunpacks.py')
module = module_from_spec(spec)
spec.loader.exec_module(module)

def extract_root(archive_path, target):
    with zipfile.ZipFile(archive_path) as archive:
        metas = [x for x in archive.namelist() if x.endswith('gunpack.meta.json')]
        prefix = min(metas, key=len).removesuffix('gunpack.meta.json')
        for entry in archive.infolist():
            if entry.is_dir() or not entry.filename.startswith(prefix):
                continue
            relative = Path(entry.filename[len(prefix):])
            dest = (target/relative).resolve()
            if not dest.is_relative_to(target.resolve()):
                raise ValueError('Unsafe archive path: '+entry.filename)
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(archive.read(entry))

if __name__ == '__main__':
    STAGE.mkdir(parents=True, exist_ok=True)
    pending = []
    for folder, slug, fid, filename in module.PACKS[1:]:
        archive = DOWNLOADS/filename
        target = STAGE/folder
        marker = DOWNLOADS/(folder+'.staged.json')
        if marker.exists():
            continue
        if not zipfile.is_zipfile(archive):
            pending.append(folder)
            continue
        extract_root(archive, target)
        marker.write_text(json.dumps({'file_id':fid, 'sha256':hashlib.sha256(archive.read_bytes()).hexdigest()}), encoding='utf-8')
        print('Staged '+folder, flush=True)
    default = STAGE/'tacz_default_gun'
    if not default.exists():
        shutil.copytree(ROOT/'neoforge-1.21.1/run/tacz/tacz_default_gun', default)
        print('Copied matching r7 default pack', flush=True)
    if pending:
        print('Pending: '+', '.join(pending), flush=True)
        raise SystemExit(2)
    overlay = DOWNLOADS/'cbrg-release'
    for folder in ['classic_battleroyale_gun', *[x[0] for x in module.PACKS[1:]], 'tacz_default_gun']:
        shutil.copytree(overlay/folder, STAGE/folder, dirs_exist_ok=True)
    for name in ['unify_constraint_in_animations.py','unify_geo_model_constraint_pos.py']:
        shutil.copy2(overlay/name, STAGE/name)
    print('Applied CBRG 0.4.8 overlay (optional stripping omitted)', flush=True)
