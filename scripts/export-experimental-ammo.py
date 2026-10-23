"""Export the user-approved fixed supply IDs, verifying installed gunpack assets read-only.

No gunpack, world, inventory, or mod registration is edited. Only our runtime catalog is written.
"""
from pathlib import Path
from collections import Counter
import hashlib
import importlib.util
import json
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'docs/compatibility/ammo-performance-proposal.json'
INVENTORY = ROOT / 'docs/compatibility/compatibility-inventory.json'
DESTINATION = ROOT / 'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/experimental_ammo.json'
EVIDENCE = ROOT / 'logs/approved-ammo-export-evidence.json'
spec = importlib.util.spec_from_file_location('ammo_asset_parser', ROOT / 'scripts/audit-ammo-attachment-compatibility.py')
parser = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parser)


def read_source(source):
    if '!' in source:
        archive, member = source.split('!', 1)
        with zipfile.ZipFile(ROOT / 'neoforge-1.21.1' / archive) as package:
            return parser.gson_json(package.read(member).decode('utf-8-sig'))
    return parser.gson_json((ROOT / 'neoforge-1.21.1' / source).read_text(encoding='utf-8-sig'))


def main():
    proposal = json.loads(SOURCE.read_text(encoding='utf-8'))
    inventory = json.loads(INVENTORY.read_text(encoding='utf-8'))
    installed_guns = {entry['id']: entry for entry in inventory['guns']}
    installed_ammo = {entry['id']: entry for entry in inventory['ammo']}
    rows, data_targets, seen, targets = [], {}, set(), set()
    evidence = []
    for gun in proposal['guns']:
        gun_id, target = gun['gunId'], gun['proposedAmmo']
        if gun_id in seen:
            raise ValueError('Duplicate approved gun: ' + gun_id)
        seen.add(gun_id)
        asset = installed_guns[gun_id]
        if asset['ambiguous'] or len(asset['cases']) != 1:
            raise ValueError('Ambiguous installed gun: ' + gun_id)
        case = asset['cases'][0]
        data_id = case['data_ref']
        for source in asset['sources']:
            index = read_source(source)
            if index['data'] != data_id:
                raise ValueError('Installed gun index changed: ' + source)
        raw_values = [read_source(source) for source in case['data_sources']]
        raw = raw_values[0]
        if any(value != raw for value in raw_values):
            raise ValueError('Conflicting installed gun data: ' + data_id)
        if raw != case['raw_data'] or raw['ammo'] != gun['currentAmmo']:
            raise ValueError('Proposal no longer matches fresh gunpack: ' + gun_id)
        if target is not None:
            if target not in installed_ammo or installed_ammo[target]['ambiguous']:
                raise ValueError('Missing/ambiguous target ammo: ' + str(target))
            if not re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9/._-]+', target):
                raise ValueError('Invalid ammo ID: ' + target)
            for source in installed_ammo[target]['sources']:
                if read_source(source) not in [v['value'] for v in installed_ammo[target]['index_variants']]:
                    raise ValueError('Installed target ammo changed: ' + source)
            targets.add(target)
        # Include preserve rows in conflict detection, so sharing cannot silently apply a target to them.
        pair = (raw['ammo'], target)
        if data_id in data_targets and data_targets[data_id] != pair:
            raise ValueError('Conflicting approved shared gun data: ' + data_id)
        data_targets[data_id] = pair
        row = dict(gunId=gun_id, dataId=data_id, originalAmmo=raw['ammo'], targetAmmo=target,
                   action='apply' if target is not None else 'preserve')
        rows.append(row)
        evidence.append(dict(**row, gunIndexSources=asset['sources'], dataSources=case['data_sources'],
                             sourceSemanticSha256=hashlib.sha256(parser.canonical(raw).encode()).hexdigest()))
    if len(rows) != 424 or sum(row['action'] == 'apply' for row in rows) != 420:
        raise ValueError('Incomplete approved catalog; expected 424 guns, 420 concrete targets')
    output = dict(schemaVersion=1, source='docs/compatibility/ammo-performance-proposal.json',
                  sourceSha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
                  note='Approved 2026-10-18. Fixed supply IDs only; bullet physics/effects and existing stacks are untouched.',
                  targetAmmoIds=sorted(targets), guns=rows)
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    DESTINATION.write_text(json.dumps(output, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')
    EVIDENCE.write_text(json.dumps(dict(gunCount=len(rows), applied=420, preserved=4,
                                      changed=sum(row['action'] == 'apply' and row['originalAmmo'] != row['targetAmmo'] for row in rows),
                                      targetCounts=dict(Counter(row['targetAmmo'] for row in rows if row['targetAmmo'])),
                                      guns=evidence), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Exported {len(rows)} guns, 420 approved targets, 4 preserved, {len(targets)} supply IDs; installed sources verified.')


if __name__ == '__main__':
    main()
