"""Resume large CDN files with independent validated HTTP range requests."""
from pathlib import Path
import concurrent.futures, json, urllib.request, urllib.parse, zipfile

ROOT = Path(__file__).resolve().parents[1]/'downloads/pubg-gunpacks'
FILES = [
    (7529068, '[TACZ1.1.5+]ClassicR1.1.3CCRP 1.1.4 hotfix3.zip'),
    (6563620, 'Suffuse-GunSmoke-Pack1.0.7--hotfix.zip'),
]
JOBS = []
PLANS = []
for fid, name in FILES:
    url = f'https://mediafilez.forgecdn.net/files/{fid//1000}/{fid%1000}/{urllib.parse.quote(name)}'
    req = urllib.request.Request(url, headers={'Range':'bytes=0-0'})
    with urllib.request.urlopen(req, timeout=30) as response:
        total = int(response.headers['Content-Range'].split('/')[-1])
    original = ROOT/name
    prefix = ROOT/(name+'.prefix')
    # Read the already downloaded prefix into a separate immutable resume file.
    prefix.write_bytes(original.read_bytes() if original.exists() else b'')
    start = prefix.stat().st_size
    parts = []
    for low in range(start, total, 2*1024*1024):
        high = min(total-1,low+2*1024*1024-1)
        part = ROOT/(name+f'.range-{low}-{high}')
        JOBS.append((url,low,high,part))
        parts.append(part)
    PLANS.append((original, prefix, parts, total))

def fetch(job):
    url, low, high, part = job
    length = high-low+1
    if part.exists() and part.stat().st_size == length:
        return
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={'Range':f'bytes={low}-{high}'})
            with urllib.request.urlopen(req, timeout=45) as response:
                if response.status != 206 or response.headers['Content-Range'].split('/')[0] != f'bytes {low}-{high}':
                    raise ValueError('Invalid Content-Range')
                content = response.read()
            if len(content) != length:
                raise ValueError('Truncated range')
            part.write_bytes(content)
            print('Range complete: '+part.name, flush=True)
            return
        except Exception:
            if attempt == 2:
                raise

with concurrent.futures.ThreadPoolExecutor(max_workers=12) as executor:
    list(executor.map(fetch,JOBS))
for original, prefix, parts, total in PLANS:
    assembled = original.with_name(original.name+'.assembled')
    with assembled.open('wb') as output:
        output.write(prefix.read_bytes())
        for part in parts:
            output.write(part.read_bytes())
    assert assembled.stat().st_size == total
    with zipfile.ZipFile(assembled) as archive:
        assert archive.testzip() is None
    # Final installation renames the assembled archive after original download stops.
    print('Assembled valid archive: '+str(assembled), flush=True)
