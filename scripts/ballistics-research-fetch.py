"""Collect only public factory ammunition external-ballistic specifications."""
from pathlib import Path
from html import unescape
import json, re, urllib.request, urllib.parse, concurrent.futures

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/gun-balance/evidence'
OUT.mkdir(exist_ok=True, parents=True)
BASE = 'https://www.sellier-bellot.cz'
PATHS = {
    'pistol': '/en/products/pistol-and-revolver-ammunition/pistol-and-revolver-cartridges/',
    'rifle': '/en/products/rifle-ammunition/rifle-ammunition-fmj/',
    'tactical': '/en/products/rifle-ammunition/rifle-ammunition-training-fmj/',
    'sp': '/en/products/rifle-ammunition/rifle-ammunition-sp/',
    'match': '/en/products/rifle-ammunition/rifle-ammunition-target-match/',
    'shot': '/en/products/shotgun-shells/hunting-shells/',
    'slug': '/en/products/shotgun-shells/hunting-single-slugs-for-shotguns/',
}
def text(s):
    return re.sub(r'\s+', ' ', unescape(re.sub(r'<[^>]+>', ' ', s))).strip()
def fetch(item):
    kind, path = item
    url = BASE + path
    try:
        raw = urllib.request.urlopen(url, timeout=30).read().decode('utf-8')
        rows=[]
        for row in re.findall(r'<tr\b[^>]*>(.*?)</tr>', raw, re.S|re.I):
            cells = [text(c) for c in re.findall(r'<t[dh]\b[^>]*>(.*?)</t[dh]>', row, re.S|re.I)]
            links=[urllib.parse.urljoin(url,h) for h in re.findall(r'href=["\']([^"\']*/detail/[^"\']*)["\']', row)]
            if cells and links:
                alt=re.findall(r'alt=["\'](.*?)["\']',row,re.S)
                rows.append({'title':text(alt[0]) if alt else None,'cells':cells,'urls':list(dict.fromkeys(links))})
        return kind, {'sourceUrl':url,'retrievedDate':'2026-10-18','rows':rows}
    except Exception as e:
        return kind, {'sourceUrl':url,'error':str(e)}

if __name__ == '__main__':
    results=dict(concurrent.futures.ThreadPoolExecutor(max_workers=4).map(fetch,PATHS.items()))
    (OUT/'ballistics-factory-tables.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    for key,data in results.items():
        print(key,len(data.get('rows',[])),data.get('error',''))
        for row in data.get('rows',[]):
            print(row['title'],row['cells'][1:4],row['urls'][0])
