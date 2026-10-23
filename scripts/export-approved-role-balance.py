"""Export the user-approved role targets; never edit installed gunpack archives."""
from pathlib import Path
import hashlib
import json
import math

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'docs/compatibility/ammo-performance-proposal.json'
ASSETS = ROOT / 'docs/gun-balance/gun-assets.json'
DEST = ROOT / 'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/approved_role_balance.json'


def main():
    proposal = json.loads(SOURCE.read_text(encoding='utf-8'))
    assets = {g['id']: g for g in json.loads(ASSETS.read_text(encoding='utf-8'))['guns']}
    guns = []
    seen = {}
    gun_ids = set()
    for row in proposal['guns']:
        gid = row['gunId']
        if gid in gun_ids:
            raise ValueError(f'Duplicate gun ID: {gid}')
        gun_ids.add(gid)
        asset = assets[gid]
        data_id = asset['rawIndex']['data']
        candidate = row['roleBalance']['candidate']
        profile = dict(gunId=gid, dataId=data_id, role=row['roleKey'],
                       action='apply' if candidate else 'preserve')
        if candidate:
            # Proposal damage is whole-shot damage, including all shotgun pellets.
            pellets = asset['rawData'].get('bullet', {}).get('bullet_amount', 1)
            if not isinstance(pellets, int) or pellets < 1:
                raise ValueError(f'Invalid pellet count: {gid}: {pellets}')
            target = {k: candidate[k] for k in ('damage10', 'head', 'aim', 'hip', 'ads', 'rpm', 'rpmCap')}
            target['pellets'] = pellets
            target['damageEnvelope'] = candidate['damageEnvelope']
            for key in ('damage10', 'head', 'aim', 'hip', 'ads', 'rpm', 'rpmCap'):
                if not math.isfinite(target[key]) or target[key] <= 0:
                    raise ValueError(f'Invalid role target {gid}/{key}')
            profile['target'] = target
        # Preserve/apply collisions must also be rejected: one data JSON cannot carry two policies.
        semantic = {k: v for k, v in profile.items() if k != 'gunId'}
        if data_id in seen and seen[data_id] != semantic:
            raise ValueError(f'Conflicting shared gun data: {data_id}')
        seen[data_id] = semantic
        guns.append(profile)
    applied = sum(g['action'] == 'apply' for g in guns)
    if len(guns) != 424 or applied != 386:
        raise ValueError(f'Approval population changed: {len(guns)} guns, {applied} roles')
    out = dict(schemaVersion=1, approvalDate='2026-10-18',
               source='docs/compatibility/ammo-performance-proposal.json',
               sourceSha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
               gunAssetsSha256=hashlib.sha256(ASSETS.read_bytes()).hexdigest(),
               appliedGuns=applied, preservedGuns=len(guns)-applied, stepMeters=5,
               note='User-approved role balance. Apply after existing recoil/control factors; native external ballistics remain independent. Damage targets are whole-shot values; firing-mode additive damage/head/spread offsets are normalized. RPM only decreases.',
               guns=guns)
    DEST.parent.mkdir(parents=True, exist_ok=True)
    DEST.write_text(json.dumps(out, ensure_ascii=False, separators=(',', ':'))+'\n', encoding='utf-8')
    print(f'Exported {applied} role profiles; {len(guns)-applied} preserved; {len(seen)} data IDs; {DEST.stat().st_size} bytes')


if __name__ == '__main__':
    main()
