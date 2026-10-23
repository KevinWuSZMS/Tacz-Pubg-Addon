"""Build the approved supply/role comparison from installed packs and experimental balance.

Writes only new documentation artifacts. Never writes a pack, world, or mod resource.
"""
from __future__ import annotations

import csv
import hashlib
import html
import importlib.util
import json
import math
import re
from pathlib import Path
import struct
import zipfile
from collections import Counter

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/compatibility'
PREFIX = 'ammo-performance-proposal'
_audit_spec = importlib.util.spec_from_file_location('ammo_source_parser', ROOT / 'scripts/audit-ammo-attachment-compatibility.py')
_audit = importlib.util.module_from_spec(_audit_spec)
_audit_spec.loader.exec_module(_audit)
_role_spec = importlib.util.spec_from_file_location('ammo_role_targets', ROOT / 'scripts/ammo-role-balance.py')
_role = importlib.util.module_from_spec(_role_spec)
_role_spec.loader.exec_module(_role)


def read(path):
    return json.loads((ROOT / path).read_text(encoding='utf-8'))


BALANCE = read('docs/gun-balance/balance-data.json')
ASSETS = read('docs/gun-balance/gun-assets.json')
GUNS = {g['id']: g for g in ASSETS['guns']}
AMMO = {a['id']: a for a in read('docs/compatibility/ammo-model-inventory.json')['ammo']}
LIVE = read('neoforge-1.21.1/src/main/resources/data/tacz_pubg/gun_balance/researched.json')
FACTORS = {g['gunId']: g for g in LIVE['guns']}
PHYSICS = read('docs/gun-balance/ballistics-data.json')

CORE = [
    ('762', '7.62mm', 'cbrg:ammo_762', '常规', '偏慢、单发较重的步枪/机枪；普通栓狙与精确射手步枪。'),
    ('556', '5.56mm', 'cbrg:ammo_556', '常规', '偏快、单发较轻的步枪/机枪。'),
    ('9', '9mm', 'cbrg:ammo_9mm', '常规', '低单发、高射速的普通冲锋枪，以及普通手枪。'),
    ('45', '.45 ACP', 'cbrg:ammo_45acp', '常规', '偏慢、单发较重的冲锋枪/手枪。'),
    ('12g', '12 Gauge Bullet', 'tacz:12g', '常规', '普通霰弹枪统一；短时全弹丸DPS高不自动判为稀有。'),
    ('57', '5.7mm', 'cbrg:ammo_57mm', '稀有', '高速且高爆发，或大弹匣、高持续火力的PDW/冲锋枪。'),
    ('50', '.50 BMG', 'tacz:50bmg', '稀有', '高单发/高性能狙击枪和反器材武器；普通栓狙仍使用7.62。'),
    ('rocket', 'RPG-7 Rocket', 'tacz:rpg_rocket', '特殊来源', '7把火箭发射器共用；各枪自身爆伤、弹道保留。'),
    ('35', '35mm 榴弹', 'cib:35mm', '特殊来源', '两个QLU-11版本统一到现有立体35mm。'),
    ('40', '40mm 榴弹', 'tacz:40mm', '特殊来源', 'M320、M203、M79、MGL共用。'),
]
CORE_IDS = {k: aid for k, _, aid, _, _ in CORE}
NAMES = {aid: label for _, label, aid, _, _ in CORE}
TYPES = {'rifle': '步枪', 'smg': '冲锋枪', 'mg': '机枪', 'pistol': '手枪',
         'sniper': '狙击/精确射手', 'shotgun': '霰弹枪', 'rpg': '发射器'}

# These approved supply IDs are exported separately; source gunpack assets remain unchanged.
SPECIAL = {
    'cib:ar2': ('cib:battery', '保留能量电池', '能量身份不能只按直伤DPS当普通步枪弹。'),
    'ccrp:mp9_thunder': ('tacz:thunder_cell', '保留雷电单元', '雷电MP9保持功能身份；不自动改9mm或5.7。'),
    'hamster:flaregun': ('hamster:flares_ammo', '保留信号弹', '功能弹不进入普通战斗弹池。'),
    'suffuse:trapper50cal': ('suffuse:boomstickshot', '保留前装纸壳弹', '名称50Cal不代表.50BMG；先保留前装身份。'),
    'suffuse:ash12': ('suffuse:12.7x55', '保留重型突击专用弹', '实验方案仍为约12HP/发、127纸面DPS；不让普通7.62无限供应它。'),
    'cib:mini': ('tacz:308', '独立稀有机枪补给', 'M134使用已有.308独立供给，普通7.62池不兼容；不硬改成.50BMG。'),
    'classicr:minigun': ('tacz:308', '独立稀有机枪补给', '500发转管机枪，持续火力高；.308作为稀有机枪供给例外。'),
    'tacz:minigun': ('tacz:308', '独立稀有机枪补给', '从背包供弹且持续火力高，需隔离普通7.62供应。'),
    'cib:qba221_burst': ('cib:18.4', '保留爆炸霰弹专用弹', '原爆伤80、半径3格；实验直伤缩放不消除爆炸，不能归普通12G。'),
    'cib:origin12db': (None, '龙息12G待定', '拟使用专用龙息12G或先不参与自然刷新；不与普通12G无条件共用。'),
    'gz:cargo_terminal': (None, '燃烧12G待定', '有点燃效果，拟与龙息共用一个专用12G；不另为每把枪造弹种。'),
    'ccrp:ddm4_v7_pro': (None, '燃烧5.56待定', '有点燃效果；需决定专用燃烧补给或取消点燃后并入普通5.56，本轮不改效果。'),
    'ccrp:shield_ots33': ('cbrg:ammo_57mm', '盾牌枪人工确认', '约104DPS且带盾牌扩展；候选共用稀有5.7，不归普通9mm。'),
    'gz:patriots': ('cbrg:ammo_57mm', '大容量手枪人工确认', '注册为手枪但100发、约83DPS；候选共用稀有5.7，保留步枪式手枪的争议标记。'),
    'tacz:taurus500': ('tacz:50ae', '重手炮稀有弹人工确认', '约13.42HP/发；候选复用已有立体.50AE，单独于.45及.50BMG。'),
    'cib:error': (None, '排除调试枪', '零直伤调试资产，不生成战斗补给。'),
}

RULES = [
    '优先识别能量、信号、爆炸/燃烧、转管机枪等特殊身份，先于性能阈值处理。',
    '主指标为实验方案10米无甲、全弹丸直伤D；R为有效节奏代理RPM；P=D×R/60；持续值按D10/D0折算旧装填代理。',
    '普通步枪：D≥6.2→7.62；D<5.8→5.56；其余边界保留已用CBRG 7.62/5.56身份，否则R≤700→7.62，R>700→5.56。',
    '普通机枪：D≥5.8或R≤700→7.62，否则5.56。慢速机枪不套步枪的高单发阈值。',
    '稀有冲锋/手枪：R≥1000且P≥80，或R≥900、弹匣≥40、持续P≥35→5.7；盾牌枪和爱国者另行人工确认。',
    '普通冲锋枪：D≥4.7且R≤800→.45，否则9mm。普通手枪：D≥6.5且R<650→.45，否则9mm；AF2011双管特判.45。',
    '普通狙/DMR→7.62；D≥18，或D≥15且P≥75，或原为重型反器材弹身份→.50BMG。GM6保留重型身份；COVE-A单列穿甲抽象复核。',
    '普通霰弹→默认tacz:12g；爆炸/燃烧变体不直接并入。火箭→默认RPG7；榴弹保留35/40区分。',
    '这些阈值是可修改的设计草案，不是论文推导的平衡定律。每枪最终固定一个供弹ID，不随切模式或临时配件切换弹种。',
]


def f32(value):
    return struct.unpack('f', struct.pack('f', float(value)))[0]


def modes_at_10(raw, factor):
    """Same documented r7 cadence proxy as the existing report; no Lua simulation."""
    b = raw.get('bullet', {})
    ex = b.get('extra_damage') or {}
    table = ex.get('damage_adjust') or []
    if table:
        base = next((p['damage'] for p in table if p['distance'] == 'infinite' or 10 < float(p['distance'])), 0)
    else:
        base = b.get('damage', 5)
    results = []
    for mode in raw.get('fire_mode', ['unknown']):
        adj = (raw.get('fire_mode_adjust') or {}).get(mode) or {}
        d = max(0, f32(f32(float(base) * factor) + f32(float(adj.get('damage', 0)) * factor)))
        rpm = float(raw.get('rpm', 300)) + float(adj.get('rpm', 0))
        rpm = 300 if rpm <= 0 else max(1, min(1200, rpm))
        interval = math.floor(60000 / rpm) / 1000
        if raw.get('bolt') == 'manual_action':
            interval = max(interval, float(raw.get('bolt_action_time', 0)))
        if mode == 'burst':
            burst = raw.get('burst_data') or {}
            count = max(1, int(burst.get('count', 3)))
            bpm = float(burst.get('bpm', 200))
            step = math.floor(60000 / bpm) / 1000 if bpm > 0 else .3
            period = max(float(burst.get('min_interval', 1)), (count - 1) * step + interval)
            rate = 60 * count / period
        else:
            rate = 60 / interval
        results.append(dict(mode=mode, damage10=d, rpm=rate, dps10=d * rate / 60))
    return results


def ordinary(g, a, raw):
    d, r = a['damage_10'], a['rpm_effective']
    p = d * r / 60
    sustained = a['sustained_dps'] * d / a['damage_0'] if a['damage_0'] else 0
    old = raw['ammo']
    kind = g['type']
    if kind == 'rpg':
        key = '35' if g['id'] in ('cib:qlu11', 'suffuse:qlu11') else ('40' if old == 'tacz:40mm' else 'rocket')
        return key, '特殊来源', '按实际火箭/35mm/40mm功能归类，保留各枪爆炸与弹道。'
    if kind == 'shotgun':
        return '12g', '常规', '普通霰弹统一默认12G；总伤害已含全部弹丸。'
    if kind == 'sniper':
        heavy = old in {'tacz:50bmg', 'tacz:416barrett', 'suffuse:.408ct', 'cib:127x108',
                        'suffuse:12.7x108mm', 'suffuse:14.5x114mm', 'origin:apfsds'}
        if heavy or d >= 18 or (d >= 15 and p >= 75):
            return '50', '稀有', '重型反器材身份或高单发/高性能狙击；统一稀有.50供给。'
        return '762', '常规', '实验性能未达到稀有重狙档，普通栓狙/DMR统一7.62。'
    if kind == 'mg' and old in {'cib:127x108', 'suffuse:12.7x108mm', 'suffuse:14.5x114mm'}:
        return '50', '稀有', '大口径重机枪，复用稀有重型.50供给。'
    if kind in ('smg', 'pistol'):
        if (r >= 1000 and p >= 80) or (r >= 900 and (a['magazine'] or 0) >= 40 and sustained >= 35):
            return '57', '稀有', '高爆发或大容量高持续火力，使用稀有5.7补给。'
        if g['id'] == 'ccrp:af2011':
            return '45', '常规', '双管AF2011保留.45；总伤害9.95并非每颗弹丸9.95。'
        large = d >= 4.7 and r <= 800 if kind == 'smg' else d >= 6.5 and r < 650
        return ('45' if large else '9'), '常规', '普通枪按单发/射速分成.45与9mm；手枪独立阈值。'
    if kind == 'mg':
        key = '762' if d >= 5.8 or r <= 700 else '556'
        return key, '常规', '普通机枪按偏慢/较重与偏快/较轻分配，使用机枪独立阈值。'
    if d >= 6.2:
        key = '762'
    elif d < 5.8:
        key = '556'
    elif old in ('cbrg:ammo_762', 'cbrg:ammo_556'):
        key = '762' if old == 'cbrg:ammo_762' else '556'
    else:
        key = '762' if r <= 700 else '556'
    return key, '常规', '普通步枪按伤害/射速分配；临界区保留既有PUBG主弹种身份。'


def main():
    assert len(GUNS) == len(BALANCE['guns']) == 424
    assert len(AMMO) == 78 and len(FACTORS) == 423
    runtime_physics = {p['gunId']:p for p in read('neoforge-1.21.1/src/main/resources/assets/tacz_pubg/realistic_ballistics.json')['guns']}
    for p in PHYSICS['guns']:
        live = runtime_physics[p['gunId']]
        assert live['action'] == p['action']
        if p['action'] == 'apply':
            for field in ('proposedMuzzleVelocityMps','gravityMps2','effectiveDragPerMeter'):
                assert live[field] == p[field], (p['gunId'], field)
    config = (ROOT/'neoforge-1.21.1/run/config/tacz-common.toml').read_text(encoding='utf-8')
    global_speed = float(re.search(r'^\s*GlobalBulletSpeedModifier\s*=\s*([\d.eE+-]+)',config,re.M)[1])
    assert global_speed == PHYSICS['globalSpeedSnapshot'], 'Global speed sample changed; refresh physics documentation first.'
    handles = {}
    rows = []
    try:
        for g in BALANCE['guns']:
            asset = GUNS[g['id']]
            raw = asset['rawData']
            source = asset['chosenCandidate']['dataSource']
            if '!' in source:
                package, entry = source.split('!', 1)
                package = ROOT / 'neoforge-1.21.1' / package
                handle = handles.setdefault(str(package), zipfile.ZipFile(package)) if str(package) not in handles else handles[str(package)]
                current = _audit.gson_json(handle.read(entry).decode('utf-8-sig'))
            else:
                current = _audit.gson_json((ROOT / 'neoforge-1.21.1' / source).read_text(encoding='utf-8-sig'))
            assert current == raw, f'Installed source changed: {g["id"]}'
            factor = FACTORS[g['id']]['damageFactor'] if g['id'] in FACTORS else 1
            assert factor == g['damageFactor'] or g['id'] == 'cib:error'
            a, original = g['adjusted'], g['current']
            modes = modes_at_10(raw, factor)
            assert math.isclose(modes[0]['damage10'], a['damage_10'], abs_tol=2e-6)
            assert math.isclose(modes[0]['rpm'], a['rpm_effective'], abs_tol=1e-7)
            key, pool, reason = ordinary(g, a, raw)
            target = CORE_IDS[key]
            special = SPECIAL.get(g['id'])
            status = '候选'
            if special:
                target, reason, detail = special
                reason += '：' + detail
                pool = '排除' if g['id'] == 'cib:error' else '特殊待确认'
                status = '排除' if g['id'] == 'cib:error' else '人工确认'
            maximum = max(modes, key=lambda m: m['dps10'])
            p = a['damage_10'] * a['rpm_effective'] / 60
            sustained = a['sustained_dps'] * a['damage_10'] / a['damage_0'] if a['damage_0'] else 0
            notes = []
            if len(modes) > 1 and maximum['dps10'] > p * 1.15:
                notes.append('其他模式明显更强：需按最高模式核验自然刷新与稀有身份；弹种不随切模式变动。')
                if status == '候选': status = '模式复核'
            if g['type'] in ('rifle', 'mg') and pool == '常规' and maximum['dps10'] >= 100:
                notes.append('任一模式直伤DPS≥100，先不自动列为普通自然刷新；稀有供给需人工决定。')
                status = '模式复核'
            if g['id'] == 'origin:cove-a':
                notes.append('原14.5×114 APFSDS并入.50仅为供弹抽象；特殊穿甲身份需确认，保持枪自身参数。')
                status = '人工确认'
            if g['id'] == 'tacz:ai_awp':
                notes.append('AWM实验伤害仅11.71；本草案按性能归7.62，未因名称强制稀有。')
            if g['id'] == 'suffuse:gm6':
                notes.append('Lynx实验伤害13.43，因反器材身份保留.50；可由你改判。')
            if g['id'] == 'cib:evo3':
                notes.append('DPS79.74接近80阈值，当前仍9mm；可以手动改稀有5.7。')
            if raw.get('script') or 'scripted_gun_logic' in g['flags']:
                notes.append('含脚本：纸面DPS未模拟Lua节奏/特殊消耗。')
            if g['selectedMode'] == 'semi':
                notes.append('半自动节奏是理论触发上限，未套玩家实际点击速度。')
            if a['pellets'] > 1:
                notes.append('单发数值已含全部弹丸，不能再乘弹丸数。')
            old = AMMO[raw['ammo']]
            new = AMMO.get(target) if target else None
            rows.append(dict(gunId=g['id'], name=g['name'], type=g['type'], typeName=TYPES[g['type']],
                gunIcon='../gun-balance/' + g['iconPath'], currentAmmo=old['id'], currentAmmoName=old['name'],
                proposedAmmo=target, proposedAmmoName=NAMES.get(target, new['name'] if new else '待定 / 不分配'),
                target3d=new['modelClassification'] if new else None, targetIcon=new['slotIcon'] if new else None,
                targetModel=new['modelPreview'] if new else None, pool=pool, status=status, reason=reason,
                originalDamage10=original['damage_10'], damage10=a['damage_10'], rpm=a['rpm_effective'],
                dps10=p, originalDps10=original['damage_10'] * original['rpm_effective'] / 60,
                sustainedDps10=sustained, magazine=a['magazine'], mode=g['selectedMode'],
                allModes=modes, maxMode=maximum['mode'], maxModeDps10=maximum['dps10'],
                notes=notes, source=source, applied=target is not None))
    finally:
        for handle in handles.values(): handle.close()
    rows.sort(key=lambda r: (list(TYPES).index(r['type']), -r['dps10'], r['gunId']))
    role_balance = _role.enrich(rows, GUNS, BALANCE['guns'], PHYSICS['guns'], FACTORS)
    pools = Counter(r['pool'] for r in rows)
    targets = Counter(r['proposedAmmo'] for r in rows if r['proposedAmmo'])
    payload = dict(date='2026-10-18', status='IMPLEMENTED_OPTIONAL_NOT_GAME_TESTED', basis='experimental_balance',
        allInstalledGunDataMatched=True, runtimeDamageFactorsMatched=423, gunCount=len(rows),
        registeredAmmoCount=len(AMMO), originalAmmoUsedByTheseGuns=len({r['currentAmmo'] for r in rows}),
        proposedConcreteIds=len(targets), unresolvedCount=sum(r['proposedAmmo'] is None and r['pool'] != '排除' for r in rows),
        pools=dict(pools), targetCounts=dict(targets), rules=RULES, exclusions=ASSETS['excludedInventoryIds'],
        sourceHashes={p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in [
            'docs/gun-balance/balance-data.json', 'docs/gun-balance/gun-assets.json',
            'docs/compatibility/ammo-model-inventory.json',
            'neoforge-1.21.1/src/main/resources/data/tacz_pubg/gun_balance/researched.json',
            'docs/gun-balance/ballistics-data.json',
            'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/realistic_ballistics.json']},
        coreAmmo=[dict(key=k, name=n, id=aid, pool=pool, description=desc, count=targets[aid],
                       icon=AMMO[aid]['slotIcon'], model=AMMO[aid]['modelPreview']) for k,n,aid,pool,desc in CORE],
        roleBalance=role_balance, guns=rows)
    mark_implemented(payload)
    (OUT / (PREFIX + '.json')).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
    columns=['gunId','name','typeName','currentAmmo','proposedAmmo','proposedAmmoName','pool','status',
             'originalDamage10','damage10','rpm','originalDps10','dps10','sustainedDps10','maxMode','maxModeDps10','magazine','reason','notes','applied',
             'roleName','roleBasis','velocityMps','dragPerMeter','currentAim','candidateAim','candidateHip','candidateAdsSeconds',
             'candidateDamage10','candidateHeadMultiplier','candidateRpm','candidateDps10',
             'originalReviewStatus','roleApplied','inGameVerified','activation']
    for distance in _role.DISTANCES:
        columns += [f'{phase}_{metric}_{distance}m' for phase in ('current','candidate') for metric in ('damage','headDamage','bodyShots','headShots')]
    with (OUT / (PREFIX + '.csv')).open('w', encoding='utf-8-sig', newline='') as f:
        w=csv.DictWriter(f,fieldnames=columns); w.writeheader()
        for row in rows:
            current=row['roleBalance']['current']; candidate=row['roleBalance']['candidate'] or {}
            flat={**row,'roleApplied':row['roleBalance']['applied'],'velocityMps':current['velocity'],'dragPerMeter':current['dragPerMeter'],'currentAim':current['aim'],
                  'candidateAim':candidate.get('aim'),'candidateHip':candidate.get('hip'),'candidateAdsSeconds':candidate.get('ads'),
                  'candidateDamage10':candidate.get('damage10'),'candidateHeadMultiplier':candidate.get('head'),
                  'candidateRpm':candidate.get('rpm'),'candidateDps10':candidate.get('dps10')}
            for distance in _role.DISTANCES:
                for phase, data in (('current',current),('candidate',candidate)):
                    sample=data.get('distances',{}).get(str(distance),{})
                    for metric in ('damage','headDamage','bodyShots','headShots'):
                        flat[f'{phase}_{metric}_{distance}m']=sample.get(metric)
            w.writerow({k: (' / '.join(flat[k]) if k=='notes' else flat.get(k)) for k in columns})
    render(payload)
    print(json.dumps({k:payload[k] for k in ['status','gunCount','registeredAmmoCount','originalAmmoUsedByTheseGuns',
          'proposedConcreteIds','unresolvedCount','pools','targetCounts']},ensure_ascii=False,indent=2))
    print('Role candidates:', role_balance['candidateGuns'], 'role counts:', role_balance['roleCounts'])


def mark_implemented(data):
    """Update availability metadata only; preserve every approved numeric target and source comparison."""
    data['status'] = 'IMPLEMENTED_OPTIONAL_NOT_GAME_TESTED'
    data['implementationDate'] = '2026-10-18'
    data['inGameVerified'] = False
    data['activation'] = 'Two independent saved-world settings: gunSettings.ammunition and gunSettings.balance; experimental selection plus save/reload confirmation required.'
    data['runtimeCoverage'] = dict(ammoMappings=420, ammoIdChanges=340, ammoKinds=18,
                                   roleProfiles=386, rolePreserved=38)
    data['comparisonMeaning'] = 'current: prior experimental reference; candidate: user-approved target. Original numbers preserved; native role damage uses 5m steps and RPM rounds conservatively to integer milliseconds.'
    data['roleBalance']['status'] = 'ROLE_BALANCE_IMPLEMENTED_OPTIONAL_NOT_GAME_TESTED'
    data['roleBalance']['inGameVerified'] = False
    for row in data['guns']:
        row.setdefault('originalReviewStatus', row['status'])
        row['applied'] = row['proposedAmmo'] is not None
        row['status'] = '已实装可选' if row['applied'] else '保留原弹药'
        row['activation'] = '需选择实验性调整并保存'
        row['inGameVerified'] = False
        rb = row['roleBalance']
        rb['applied'] = rb['candidate'] is not None
        rb['inGameVerified'] = False
        if rb['candidate']:
            rb['candidate']['implementation'] = '已实装为5米阶梯damage_adjust；七个里程点命中批准包络，模式伤害/头倍率/散布增量归零，RPM及burst保守量化限速；未进行游戏实测。'
    data['roleBalance']['notes'] = _role.IMPLEMENTATION_NOTES.copy()
    return data


def role_section(data):
    h=html.escape; role=data['roleBalance']; audit=[]; targets=[]
    for s in role['currentStats']:
        def median(k, digits=2, percent=False):
            v=s[k]['median']
            return '—' if v is None else (f'{v*100:.1f}%' if percent else f'{v:.{digits}f}')
        audit.append(f'<tr><td>{h(TYPES[s["type"]])}<small>真实弹道 {s["realCount"]} / 有效枪 {s["count"]}</small></td>'
             f'<td>{median("velocity",0)}<small>P10–P90：{s["velocity"]["p10"]:.0f}–{s["velocity"]["p90"]:.0f}</small></td><td>{median("drag",6)}</td><td>{median("speedRetention100",percent=True)}</td><td>{median("flight100",3)}</td>'
             f'<td>{median("drop300",3)}</td><td>{median("retained100",percent=True)} / {median("retained300",percent=True)}</td><td>{median("aim",3)}</td></tr>')
    counts=Counter(r['roleKey'] for r in data['guns'])
    for key,s in role['roles'].items():
        def interval(k): return '–'.join(f'{x:g}' for x in s[k])
        retained=s['retained']
        targets.append(f'<tr><td><b>{h(s["title"])}</b><small>{counts[key]} 把已批准角色 · {h(s["effectiveDistance"])}</small></td>'
            f'<td>{interval("damage")}<small>头倍率 {interval("head")}</small><small>RPM上限 {s["rpmCap"]}</small></td>'
            f'<td>{retained[3]*100:g}% / {retained[4]*100:g}% / {retained[5]*100:g}%</td>'
            f'<td>{interval("aim")}<small>站立腰射 {interval("hip")}</small><small>ADS {interval("ads")}s</small></td>'
            f'<td>{h(s["tradeoff"])}</td></tr>')
    notes=''.join('<li>'+h(n)+'</li>' for n in role['notes'])
    return f'''<section id="role-balance"><h2>新增：枪类距离定位与角色平衡</h2>
<div class="notice"><b>普通供弹颗粒度足够；需要更细的枪械角色参数。</b>同一7.62可供步枪、DMR、栓狙使用，每枪仍有独立弹道与距离伤害。现有真实弹道已区分口径；当前衰减曲线却没形成稳定枪类分工。下列角色目标已实装到“平衡调整 → 实验性调整”；子弹合并另由“子弹 → 实验性调整”独立启用。未进行游戏实测。</div>
<p>改动前旧实验方案300m伤害保留中位：冲锋枪约71%、步枪约69%；按manual_action粗分的狙击枪约58%。AWM旧方案300m理论头伤已约28.45HP，低近距DPS不代表弱；Kar98约13.91HP，才显示同类角色优势不稳定。统计按原注册分类，具体候选另识别SKS、Mini14、重型半自动和杠杆平台。</p>
<h3>旧实验方案对照：中位数核对</h3><p>弹速、阻力和飞行只统计396把已应用现实参考的枪；28把原弹道保留另列。伤害/散布为各类有效枪的旧实验对照数值。伤害保留按10m归一化；下坠相对水平枪管线。</p>
<div class="wrap"><table><thead><tr><th>原注册枪类</th><th>初速 m/s</th><th>阻力 k /m</th><th>100m横向保速</th><th>100m飞行 s</th><th>300m下坠 m</th><th>100m / 300m伤害保留</th><th>ADS散布参数</th></tr></thead><tbody>{''.join(audit)}</tbody></table></div>
<h3>新的角色目标：DPS只作辅助</h3><p>保留每枪现有现实参考初速、阻力和统一重力9.80665m/s²。步枪初速可能高于狙击；狙击依靠更好的保速、稳定精度、远距伤害和单发击杀阈值建立价值。步枪与机枪弹道重叠合理，机枪用持续火力、慢ADS、差腰射区分。</p>
<div class="wrap"><table><thead><tr><th>已批准角色 / 主距离</th><th>10m整发直伤 HP</th><th>100 / 200 / 300m伤害保留</th><th>精度与操控目标</th><th>优势与代价</th></tr></thead><tbody>{''.join(targets)}</tbody></table></div>
<p>普通精确慢射狙击的批准下限300m头伤：13.5×0.8×2.2=23.76HP；无甲全命中时一枪头、两枪身。普通DMR近距最高头伤9.5×2=19HP，通过更快连续射击弥补。稀有重型慢射狙允许近距一枪身，已经批准；其半自动分支单发上限19.5HP，避免两项优势同时拉满。护甲与实际命中仍需游戏验证；已静态核对角色枪的30份Lua，未发现散布/属性覆写。</p>
<details><summary>实装规则、角色边界与验证范围</summary><ul>{notes}</ul></details>
<p>全部424行下方增加旧实验/批准目标精度、射速、100m/300m伤害及身体/头部发数，另列初速、保速、下坠与飞行时间。完整10/25/50/100/200/300/500m数据可下载CSV/JSON。已实装角色共{role['candidateGuns']}把；其余38把保留先前实验逻辑。</p></section>'''


def render(data):
    h=html.escape
    def pic(path, css='ammo', alt='弹药原模型离线投影'):
        if not path: return '<span class="muted">暂无三维预览</span>'
        assert (OUT / path).resolve().is_file(), path
        return f'<img loading="lazy" class="{css}" src="{h(path)}" alt="{h(alt)}">'
    cards=[]
    for a in data['coreAmmo']:
        pending = sum(r.get('originalReviewStatus') == '人工确认' and r['proposedAmmo'] == a['id'] for r in data['guns'])
        review_note = f'<small>其中 {pending} 把曾列人工复核；本次按批准固定供弹映射，历史标记保留。</small>' if pending else ''
        cards.append(f'<article>{pic(a["model"])}<h3>{h(a["name"])}</h3><code>{h(a["id"])}</code>'
            f'<p>{h(a["description"])}</p><span class="tag">{h(a["pool"])} · {a["count"]} 把固定映射</span>{review_note}</article>')
    table=[]
    for r in data['guns']:
        search=' '.join(str(r[k]) for k in ['gunId','name','typeName','currentAmmo','proposedAmmo','proposedAmmoName','reason','pool','status','roleName'])
        m=' · '.join(f'{a["mode"]}: {a["dps10"]:.2f}' for a in r['allModes'])
        rb=r['roleBalance']; current=rb['current']; candidate=rb['candidate']
        def shot(v): return str(v) if v is not None else '—'
        distance_lines=[]
        for d in (100,300):
            old=current['distances'][str(d)]; new=candidate['distances'][str(d)] if candidate else None
            distance_lines.append(f'<b>{d}m 躯干 {old["damage"]:.2f}'+(f' → {new["damage"]:.2f}' if new else '')+' HP</b>'
                +f'<small>身体 {shot(old["bodyShots"])}'+(f' → {shot(new["bodyShots"])}' if new else '')+' 发；头 '
                +shot(old['headShots'])+(f' → {shot(new["headShots"])}' if new else '')+' 发</small>'
                +f'<small>头伤 {old["headDamage"]:.2f}'+(f' → {new["headDamage"]:.2f}' if new else '')+' HP</small>')
        s100=current['distances']['100']['flight']; s300=current['distances']['300']['flight']
        exterior=f'<b>初速 {current["velocity"]:.0f} m/s</b>'
        if current['physicsApplied']: exterior+=f'<small>k={current["dragPerMeter"]:.6f} /m · 现实参考</small>'
        else: exterior+='<small>特殊/未证原弹道保留</small>'
        if s100: exterior+=f'<small>100m横向保速 {s100["horizontalRetention"]*100:.1f}% · {s100["horizontalSpeed"]:.0f} m/s</small>'
        if s300: exterior+=f'<small>300m飞行 {s300["time"]:.3f}s · 下坠 {s300["drop"]:.3f}m</small>'
        else: exterior+='<small>300m：寿命内未到达</small>'
        target=f'<b>{h(r["roleName"])}</b>'
        if candidate:
            target+=f'<small>ADS散布 {current["aim"]:.3f} → {candidate["aim"]:.3f}</small>'
            target+=f'<small>站立腰射 {current["hip"]:.2f} → {candidate["hip"]:.2f}</small>'
            target+=f'<small>开镜时间 {current["ads"]:.2f} → {candidate["ads"]:.2f}s</small>'
            target+=f'<small>RPM {current["rpm"]:.1f} → {candidate["rpm"]:.1f}</small>'
            target+=f'<small>10m伤害 {r["damage10"]:.2f} → {candidate["damage10"]:.2f} HP</small>'
            target+=f'<small>头倍率 {current["head"]:.2f} → {candidate["head"]:.2f}</small>'
            target+=f'<small>批准目标近距DPS {candidate["dps10"]:.2f}（辅助指标）</small>'
            target+='<small>初速、阻力、重力保持当前现实参考值</small>'
        else: target+='<small>本轮角色数值保留；供弹映射是否实装见左栏</small>'
        if rb['scriptedSpreadPossible']: target+='<small class="warn">带Lua；本次静态审计未发现角色属性覆写</small>'
        sort_head=candidate['distances']['300']['headDamage'] if candidate else -1
        sort_body=candidate['distances']['300']['bodyShots'] if candidate else None
        table.append(f'<tr data-type="{r["type"]}" data-pool="{h(r["pool"])}" data-status="{h(r["status"])}" data-role="{r["roleKey"]}" '
            f'data-search="{h(search.lower())}" data-dps="{r["dps10"]}" data-name="{h(r["name"])}" data-head300="{sort_head}" data-body300="{sort_body or 99999}">'
            f'<td>{pic(r["gunIcon"],"gun",r["name"])}<b>{h(r["name"])}</b><code>{h(r["gunId"])}</code><small>{h(r["typeName"])}</small></td>'
            f'<td>{pic(r["targetModel"])}<b>{h(r["proposedAmmoName"])}</b><code>{h(r["proposedAmmo"] or "尚未决定")}</code>'
            f'<small>原弹药：{h(r["currentAmmo"])}</small><span class="tag">{h(r["pool"])} · {h(r["status"])}</span></td>'
            f'<td><b>{r["damage10"]:.2f} HP / 发</b><small>初始：{r["originalDamage10"]:.2f} HP</small>'
            f'<b>{r["rpm"]:.1f} RPM</b><small>首模式 {h(r["mode"])} · 弹匣 {r["magazine"] if r["magazine"] is not None else "背包供弹"}</small>'
            f'<b>{r["dps10"]:.2f} DPS</b><small>初始：{r["originalDps10"]:.2f} · 持续代理：{r["sustainedDps10"]:.2f}</small></td>'
            f'<td class="candidate">{target}</td><td>{"".join(distance_lines)}<small>旧实验 → 批准目标；20HP无甲全命中</small></td><td>{exterior}</td>'
            f'<td><b>最高模式 {r["maxModeDps10"]:.2f} DPS</b><small>{h(m)}</small><p>{h(r["reason"])}</p>'
            f'<ul>{"".join("<li>"+h(n)+"</li>" for n in r["notes"])}</ul></td></tr>')
    exception_rows=[]
    for r in data['guns']:
        if r['status']=='人工确认' or r['pool'] in ('特殊待确认','排除'):
            exception_rows.append(f'<tr><td>{pic(r["gunIcon"],"gun",r["name"])}<b>{h(r["name"])}</b><code>{h(r["gunId"])}</code></td>'
                f'<td>{h(r["proposedAmmoName"])}<code>{h(r["proposedAmmo"] or "待定")}</code><p>{h(r["reason"])}</p></td></tr>')
    rules=''.join('<li>'+h(rule)+'</li>' for rule in data['rules'])
    pools=''.join(f'<span>{h(k)} {v} 把</span>' for k,v in data['pools'].items())
    typeopts=''.join(f'<option value="{k}">{h(v)}</option>' for k,v in TYPES.items())
    poolopts=''.join(f'<option>{h(k)}</option>' for k in data['pools'])
    statusopts=''.join(f'<option>{h(k)}</option>' for k in dict.fromkeys(r['status'] for r in data['guns']))
    roleopts=''.join(f'<option value="{h(k)}">{h(v["title"])}</option>' for k,v in data['roleBalance']['roles'].items())+'<option value="special">特殊/原参数保留</option>'
    doc='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>弹药统一与枪类距离平衡 · 已实装对照表</title><style>
:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#101720;color:#e5edf7;font:15px/1.65 "Microsoft YaHei",sans-serif;padding:25px}main{max-width:1650px;margin:auto}h1{font-size:29px;margin:0 0 9px}h2{margin-top:32px}a{color:#94caff}p{max-width:1450px}.notice{border-left:4px solid #ffcc6a;background:#2c2830;padding:15px 20px}.muted,small{color:#aabbd0}code{color:#b4d7ff;word-break:break-all}code,b,small{display:block}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:14px}article{background:#1b293b;padding:17px;border:1px solid #3b506a;border-radius:10px}article h3{margin:5px 0}article p{min-height:53px}.tag{display:inline-block;background:#294968;border-radius:5px;padding:3px 9px;font-size:13px}.stats{display:flex;gap:12px;flex-wrap:wrap;margin:17px 0}.stats span{background:#203149;padding:10px 18px;border-radius:8px}.controls{position:sticky;top:0;z-index:2;display:flex;gap:9px;flex-wrap:wrap;background:#1a2637fa;border:1px solid #445a77;padding:12px}input,select,button{font:inherit;padding:8px;color:#fff;background:#101d2d;border:1px solid #526b8b;border-radius:5px}input{min-width:240px;flex:1}button{cursor:pointer}.wrap{overflow:auto}table{width:100%;border-collapse:collapse;min-width:1050px}th{background:#23354d;text-align:left;padding:13px}td{padding:13px;border-bottom:1px solid #32455e;vertical-align:top}td:first-child{width:240px}td:nth-child(2){width:270px}td:nth-child(3){width:230px}tr:nth-child(even){background:#152131}tr:hover{background:#21354b}img{object-fit:contain;background:#213148;border-radius:8px}.gun{width:190px;height:110px}.ammo{width:160px;height:118px}ul{padding-left:20px}li{margin:6px 0}#exceptions td{width:50%}.jump{display:flex;gap:18px;flex-wrap:wrap;margin:16px 0}[hidden]{display:none!important}@media(max-width:650px){body{padding:12px}h1{font-size:24px}}</style>
<main><h1>弹药统一与枪类距离平衡：已实装可选方案</h1><p>2026-10-18 · Minecraft 1.21.1 / TaCZ PUBG Addon · 实验性平衡参数为主要基准</p>
<div class="notice"><b>已按批准表实装为两套独立实验选项，尚未进行游戏实测。</b>在配置中选择对应“实验性调整”并保存后生效；源枪包与旧背包弹药不改写。普通补给统一为 <strong>5 种</strong>，再加 2 种稀有弹、3 种发射器弹。420把固定供弹映射、340把改ID、18种实际供弹；386把应用角色目标，38把保留先前实验逻辑。</div>
<p>这是游戏供弹分类：弹药外观和供弹 ID 可以统一，伤害、射速、初速、下坠和特殊效果仍属于每一把枪。7.62、5.56等名称在此表示游戏补给桶，性能换口径不等同于现实互换。实装不删除旧注册，也不自动转换旧背包弹药或改写已装弹数量。</p>
<div class="stats">__POOLS__<span>核心弹种 10</span><span>含特殊保留共 __IDS__ 个现有ID</span><span>__UNRESOLVED__ 把燃烧变体弹药待定</span></div>
<p>数据覆盖424把枪，包含1把排除的调试枪；其当前使用68个ammo ID，全部已注册ammo有78个。已逐枪比对本机源枪包JSON，并核对423条实验伤害倍率。7个此前已排除的隐藏/非正常枪资产不纳入此次普通刷新判断：<code>__EXCLUDED__</code></p>
<nav class="jump"><a href="#role-balance">已实装：距离与角色平衡</a><a href="#core">10种核心补给</a><a href="#rules">供弹分类规则</a><a href="#exceptions">特殊枪与保留项</a><a href="#all">全部424把逐项表</a><a href="ammo-performance-proposal.csv">CSV</a><a href="ammo-performance-proposal.json">数据与来源哈希</a></nav>
<p class="muted">图片：枪图为现有枪包图标；弹药大图为现有模型的离线几何投影，不是游戏截图。核心10种均有已存在的三维模型；未制作新模型。</p>
__ROLESECTION__
<h2 id="core">普通5种 + 稀有2种 + 发射器3种</h2><div class="grid">__CARDS__</div>
<h2 id="rules">已批准固定供弹映射的分类依据</h2><p>本次固定采用批准的逐枪供弹ID；角色数值另由平衡开关控制。普通栓狙即使提高远距头伤也继续共用7.62，不因新单发伤害跨阈值就自动换成.50。稀有身份单独决定。实装按批准的逐枪表固定映射，DPS不会自动决定整枪强弱或自然刷新资格。</p><ol>__RULES__</ol>
<p>这些是统一的10米无甲纸面比较，未模拟命中率、压枪、玩家点击极限、Lua、热量、爆炸及燃烧追加伤害。霰弹总伤害已含全部弹丸。首模式作为主列，同时计算所有已声明模式；模式可能改判的枪会标记复核，暂不声称已证明适合普通自然刷新。</p>
<p>稀有弹的平衡作用来自独立补给与较低供应，弹药ID本身不会降低枪的DPS。稀有枪/弹的投放权重仍由物品概率列表控制，本次没有自动改稀有权重；itemRespawn读取当前启用的供弹ID自动生成随枪弹药。普通DPS高的双管霰弹不因此升档，须结合小弹匣和装填时间。</p>
<h2 id="exceptions">特殊枪与保留项：沿用批准决定</h2><div class="wrap"><table><thead><tr><th>枪械</th><th>供弹决定与原始依据</th></tr></thead><tbody id="exceptions">__EXCEPTIONS__</tbody></table></div>
<h2 id="all">每把枪 → 已批准供弹 → 距离角色</h2><p>“常规/稀有/特殊来源”是建议供给身份，尚未改变实际自然刷新权重。旧人工确认/模式复核标记保留在数据中；本次420个具体供弹目标均已批准，4个无具体目标保持原弹药。按“距离角色”筛选DMR、慢射精确狙等；数值箭头是旧实验对照 → 已批准目标。实装伤害使用5米阶梯，射速按整数毫秒保守量化，因此实际值可能略低于连续目标。全模式及引用Lua已离线核对；旧综合难度排名不再承诺。</p>
<div class="controls"><input id="q" placeholder="搜索枪名 / ID / 弹药 / 距离角色"><select id="type"><option value="">全部注册枪类</option>__TYPEOPTS__</select><select id="role"><option value="">全部距离角色</option>__ROLEOPTS__</select><select id="pool"><option value="">全部补给身份</option>__POOLOPTS__</select><select id="status"><option value="">全部审阅状态</option>__STATUSOPTS__</select><select id="sort"><option value="default">按注册枪类</option><option value="head300">300m批准头伤从高到低</option><option value="body300">300m批准躯干发数从少到多</option><option value="desc">旧实验DPS从高到低</option><option value="asc">旧实验DPS从低到高</option><option value="name">按名字</option></select><button id="reset">重置</button><span id="count"></span></div>
<div class="wrap"><table id="guns"><thead><tr><th>枪械与图片</th><th>批准供弹与原弹药</th><th>旧实验对照 · 10米</th><th>已批准角色 / 精度操控</th><th>100m / 300m击杀阈值</th><th>弹速 / 衰减 / 下坠</th><th>全部模式、理由与复核项</th></tr></thead><tbody>__TABLE__</tbody></table></div>
<p>来源：<a href="../gun-balance/balance-data.json">平衡参数</a> · <a href="../gun-balance/gun-assets.json">原枪包数据和图标</a> · <a href="ammo-model-inventory.json">弹药模型清单</a> · <a href="ammo-unification.html">此前现实口径研究</a>（与本次性能供弹方案分别保留）</p></main>
<script>
const ids=['q','type','role','pool','status','sort'],els=Object.fromEntries(ids.map(id=>[id,document.getElementById(id)]));
const body=document.querySelector('#guns tbody'),rows=[...body.rows];rows.forEach((r,i)=>r.dataset.order=i);
function update(){const q=els.q.value.trim().toLocaleLowerCase();let n=0;for(const r of rows){r.hidden=!(r.dataset.search.includes(q)&&(!els.type.value||r.dataset.type===els.type.value)&&(!els.role.value||r.dataset.role===els.role.value)&&(!els.pool.value||r.dataset.pool===els.pool.value)&&(!els.status.value||r.dataset.status===els.status.value));if(!r.hidden)n++;}
const sorted=[...rows].sort((a,b)=>els.sort.value==='head300'?+b.dataset.head300-a.dataset.head300:els.sort.value==='body300'?+a.dataset.body300-b.dataset.body300:els.sort.value==='asc'?+a.dataset.dps-b.dataset.dps:els.sort.value==='desc'?+b.dataset.dps-a.dataset.dps:els.sort.value==='name'?a.dataset.name.localeCompare(b.dataset.name,'zh'):a.dataset.order-b.dataset.order);body.append(...sorted);document.getElementById('count').textContent=n+' / '+rows.length+' 把';}
ids.forEach(id=>els[id].addEventListener(id==='q'?'input':'change',update));document.getElementById('reset').onclick=()=>{ids.forEach(id=>els[id].value=id==='sort'?'default':'');update();};update();
</script></html>'''
    values={'POOLS':pools,'IDS':str(data['proposedConcreteIds']),'UNRESOLVED':str(data['unresolvedCount']),
            'EXCLUDED':h('、'.join(data['exclusions'])),'CARDS':''.join(cards),'RULES':rules,
            'EXCEPTIONS':''.join(exception_rows),'TYPEOPTS':typeopts,'POOLOPTS':poolopts,'STATUSOPTS':statusopts,'TABLE':''.join(table),
            'ROLEOPTS':roleopts,'ROLESECTION':role_section(data)}
    for k,v in values.items(): doc=doc.replace('__'+k+'__',v)
    doc=doc.replace('</style>','#guns{min-width:1900px}#guns td{min-width:230px}#guns td.candidate{background:#17352f}.warn{color:#ffd17e}details{background:#1b293b;padding:12px 18px;margin:15px 0}summary{cursor:pointer}</style>')
    assert '__TABLE__' not in doc
    (OUT / (PREFIX + '.html')).write_text(doc,encoding='utf-8')


if __name__ == '__main__':
    main()
