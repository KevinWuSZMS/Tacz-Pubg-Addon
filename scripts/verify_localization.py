"""Validate the two editable Minecraft language JSON files.

python scripts/verify_localization.py
python scripts/verify_localization.py --update-index  # after adding keys used by Texts.tr
python scripts/verify_localization.py --write-usage   # regenerate the readable key-to-code index
"""
from pathlib import Path
import argparse
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'neoforge-1.21.1/src/main'
ASSETS = MAIN / 'resources/assets/tacz_pubg'

def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key: ' + key)
        result[key] = value
    return result

def read(path):
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique)

def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--update-index', action='store_true')
    parser.add_argument('--write-usage', action='store_true')
    args = parser.parse_args()
    zh, en = [read(ASSETS / 'lang' / (locale + '.json')) for locale in ('zh_cn', 'en_us')]
    assert zh.keys() == en.keys(), 'Chinese and English key sets differ'
    pattern = re.compile(r'%(?:(\d+)\$)?([a-zA-Z%])')
    for key in en:
        assert isinstance(en[key], str) and isinstance(zh[key], str), key
        assert not re.search('[\u3400-\u9fff]', en[key]), 'Chinese in English value: ' + key
        formats = []
        for catalog in (zh, en):
            found = pattern.findall(catalog[key])
            assert all(kind in ('s', '%') for _, kind in found), 'Only %s placeholders are supported: ' + key
            formats.append(sorted(p for p in found if p[1] != '%'))
        assert formats[0] == formats[1], 'Placeholder mismatch: ' + key
    index_path = ASSETS / 'localization-index.json'
    index = read(index_path)
    if args.update_index:
        known = set(index.values())
        for key in en:
            if key not in known and key.startswith(('screen.tacz_pubg.', 'message.tacz_pubg.', 'error.tacz_pubg.', 'command.tacz_pubg.')):
                identity = hashlib.sha256(key.encode()).hexdigest()[:12]
                assert identity not in index, 'Transport ID collision; assign another unused ID'
                index[identity] = key
        write(index_path, index)
    assert len(index.values()) == len(set(index.values())), 'Duplicate translation in transport index'
    assert all(re.fullmatch('[a-f0-9]{12}', identity) for identity in index), 'Invalid internal ID'
    assert set(index.values()) <= en.keys(), 'An indexed key is missing from the language files'
    usage = {key: [] for key in en}
    for path in (MAIN / 'java').rglob('*.java'):
        source = path.read_text(encoding='utf-8')
        for match in re.finditer(r'Texts\.(tr|of|matches)\("([^"]+)"', source):
            kind, key = match.groups()
            # Native components may reuse vanilla or another mod's language catalog.
            # String transport keys still require our own stable index below.
            if kind == 'of' and '.tacz_pubg.' not in key:
                continue
            assert key in en, 'Unknown key: ' + key
            assert kind != 'tr' or key in index.values(), 'Run --update-index for: ' + key
            usage[key].append(str(path.relative_to(ROOT)).replace('\\', '/') + ':' + str(source[:match.start()].count('\n') + 1))
    if args.write_usage:
        write(ROOT / 'docs/localization-key-usage.json', {k: v for k, v in sorted(usage.items()) if v})
    print(f'Localization JSON verified: {len(en)} bilingual keys; {len(index)} stable transport mappings; '
          f'{sum(len(v) for v in usage.values())} checked source references.')

if __name__ == '__main__':
    main()
