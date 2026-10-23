"""Download pinned author gunpacks; preserve archives and record provenance."""
from pathlib import Path
import concurrent.futures, hashlib, json, urllib.parse, urllib.request, zipfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'downloads/pubg-gunpacks'
PACKS = [
    ('classic_battleroyale_gun', 'tacz-classic-battleroyale-gun', 7589555, 'CBRG-0.4.8.zip'),
    ('cibr_gunspack', 'tacz-charge-into-battle-reboot-pack', 7280896, '[Tacz1.1.7+]CIBR_GunsPack_v0.3_1.1.7.zip'),
    ('classic_rccrp', 'tacz-classics-reborn', 7529068, '[TACZ1.1.5+]ClassicR1.1.3CCRP 1.1.4 hotfix3.zip'),
    ('cold_war', 'coldwarguns', 7042787, 'Cold War from 1947-1991 v0.51.zip'),
    ('gunpowder_revolution_gunpack', 'tacz-gunpowder-revolution-great-war-gunpack', 6982844, 'GunpowderRevolution_gunpack v1.2.7.zip'),
    ('infinite_origin', 'tacz-infinite-origin', 6768903, 'Infinite Origin_v1.0.0.zip'),
    ('suffuse_gunsmoke_pack', 'suffuse-gunsmoke', 6563620, 'Suffuse-GunSmoke-Pack1.0.7--hotfix.zip'),
    ('the_division_pack', 'tacz-the-division-gunpack', 6130805, 'The Division pack_ver.0.3.3.zip'),
]

def download(spec):
    folder, slug, fid, name = spec
    target = DEST / name
    url = f'https://mediafilez.forgecdn.net/files/{fid//1000}/{fid%1000}/{urllib.parse.quote(name)}'
    record = dict(folder=folder, project_url=f'https://www.curseforge.com/minecraft/customization/{slug}',
                  file_url=f'https://www.curseforge.com/minecraft/customization/{slug}/files/{fid}',
                  file_id=fid, filename=name, download_url=url)
    try:
        if not target.exists() or not zipfile.is_zipfile(target):
            req = urllib.request.Request(url, headers={'User-Agent':'NeoForge-Learning-Workspace/1.0'})
            with urllib.request.urlopen(req, timeout=90) as response, target.open('wb') as output:
                while data := response.read(1024*1024):
                    output.write(data)
        with zipfile.ZipFile(target) as archive:
            corrupt = archive.testzip()
            if corrupt:
                raise ValueError(f'corrupt entry: {corrupt}')
            record['entries'] = len(archive.infolist())
        record.update(size=target.stat().st_size, sha256=hashlib.sha256(target.read_bytes()).hexdigest(), status='downloaded')
    except Exception as exc:
        record.update(status='failed', error=str(exc))
    print(json.dumps(record, ensure_ascii=False), flush=True)
    return record

if __name__ == '__main__':
    DEST.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
        records = list(executor.map(download, PACKS))
    (DEST/'download-manifest.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
    raise SystemExit(any(x['status'] != 'downloaded' for x in records))
