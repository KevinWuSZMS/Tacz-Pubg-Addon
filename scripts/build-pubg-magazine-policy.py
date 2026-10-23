"""Generate the additive nine-magazine policy from the reviewed 424-gun inventory.

All source packs, native capacities and model files are read-only. Category
inferences reuse the existing reviewed per-gun PUBG scope category table.
"""
from __future__ import annotations
import collections
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
INVENTORY=ROOT/'docs/compatibility/compatibility-inventory.json'
CATEGORIES=ROOT/'docs/compatibility/pubg-scope-policy.json'
RESOURCE=ROOT/'neoforge-1.21.1/src/main/resources/data/tacz_pubg/gun_balance/pubg_magazine_policy.json'
REPORT=ROOT/'docs/compatibility/pubg-magazine-policy.json'
FAMILIES={'AR':['rifle'],'LMG':['rifle'],'SMG':['smg'],'PISTOL':['smg'],'SR':['sniper'],'DMR':['rifle','sniper']}
VARIANTS=['quickdraw','extended','extended_quickdraw']
# These local variants retain fixed/clip-fed receivers or fixed tubes despite
# exposing TaCZ's generic extended_mag slot. Old attachments remain untouched.
# Do not reject a detachable conversion solely because its historical parent
# gun had a fixed magazine (e.g. the local C96 Schnellfeuer and Type56 models).
FEED_EXCEPTIONS={
    'ccrp:marlin_1895':'fixed_tube_magazine',
    'ccrp:springfield1873_tube_mag':'fixed_tube_magazine',
    'hamster:win1873':'fixed_tube_magazine',
    'hamster:gew98':'fixed_clip_loaded_internal_magazine',
    'hamster:krag':'fixed_side_loading_internal_magazine',
    'hamster:m1903':'fixed_clip_loaded_internal_magazine',
    'hamster:type99':'fixed_clip_loaded_internal_magazine',
    'hamster:smle_mk3':'clip_loading_script_not_detachable_reload',
    'tacz:kar98':'fixed_clip_loaded_internal_magazine',
    'tacz:springfield1873':'single_shot_trapdoor',
    'tacz:lonetrail':'single_shot_fixed_chamber',
    'cib:type11':'fixed_clip_fed_hopper',
}
REVOLVERS={
    'cib:r8','classicr:colt_python','hamster:colt1873','hamster:coltm1851',
    'hamster:coltm1892','hamster:coltm1892pair','hamster:m1879revolver',
    'hamster:nagantm1895','hamster:sw_mk2','hamster:sw_mk2_41','hamster:webley',
    'tacz:rhino357','tacz:taurus500','tacz:taurus943',
}

def main():
    inv=json.loads(INVENTORY.read_text(encoding='utf-8-sig'))
    categories={g['gunId']:g for g in json.loads(CATEGORIES.read_text(encoding='utf-8-sig'))['guns']}
    rows=[]
    for gun in inv['guns']:
        if not gun['static_candidate']:continue
        gid=gun['id'];cases=gun['cases'];cat=categories[gid]['category']
        reasons=[];slot=all('extended_mag' in c['raw_data'].get('allow_attachment_types',[]) for c in cases)
        if not slot:reasons.append('original_extended_mag_slot_closed')
        if cat not in FAMILIES:reasons.append('outside_three_approved_magazine_families')
        if gid in REVOLVERS:reasons.append('fixed_revolver_cylinder')
        if gid in FEED_EXCEPTIONS:reasons.append(FEED_EXCEPTIONS[gid])
        for case in cases:
            data=case['raw_data'];levels=data.get('extended_mag_ammo_amount')
            if not isinstance(levels,list) or len(levels)<3 or any(type(v) is not int or v<=0 for v in levels[:3]):
                reasons.append('missing_or_invalid_native_level1_2_3_capacities')
            if data.get('reload',{}).get('type','magazine')!='magazine':
                reasons.append('non_magazine_reload_type')
            if 'extended_mag' in (data.get('builtin_attachments') or {}):
                reasons.append('builtin_nonreplaceable_extended_mag')
        if gun.get('ambiguous'):reasons.append('ambiguous_source_gun_data')
        reasons=list(dict.fromkeys(reasons));eligible=not reasons
        families=FAMILIES.get(cat,[]) if eligible else []
        ids=[f'tacz_pubg_workbench:{variant}_mag_{family}' for family in families for variant in VARIANTS]
        rows.append({'gunId':gid,'category':cat,'taczCategory':cases[0]['type'],
            'originalMagazineSlot':slot,'eligible':eligible,'candidateFamilies':FAMILIES.get(cat,[]),
            'families':families,'allowedMagazines':ids,'exclusionReasons':reasons,
            'nativeLevelCapacities':[c['raw_data'].get('extended_mag_ammo_amount') for c in cases],
            'baseCapacities':[c['raw_data'].get('ammo_amount') for c in cases],
            'dataIds':list(dict.fromkeys(c['data_ref'] for c in cases)),
            'reloadTypes':[c['raw_data'].get('reload',{}).get('type','magazine') for c in cases],
            'scripts':[c['raw_data'].get('script') for c in cases],
            'sourceResources':gun['sources'],
            'classificationBasis':categories[gid].get('basis','reviewed_local_category'),
            'physicalScope':'Native existing extended-mag level appearance and feed rules; no new gun mount or gun model replacement.'})
    assert len(rows)==424 and len({r['gunId'] for r in rows})==424
    eligible=[r for r in rows if r['eligible']]
    family_counts={f:sum(f in r['families'] for r in rows) for f in ['rifle','smg','sniper']}
    result={'schemaVersion':1,'checkedAt':'2026-10-18','gunCount':len(rows),
        'scope':'Approved local TaCZ native experimental attachment policy; not a claim of exact PUBG physical interchangeability.',
        'sourceInventory':str(INVENTORY.relative_to(ROOT)).replace('\\','/'),
        'sourceInventorySha256':hashlib.sha256(INVENTORY.read_bytes()).hexdigest(),
        'categoryPolicy':str(CATEGORIES.relative_to(ROOT)).replace('\\','/'),
        'rules':{'rifle_lmg':['rifle'],'pistol_smg':['smg'],'sr':['sniper'],'dmr':['rifle','sniper'],
            'extendedMagLevels':{'quickdraw':1,'extended':2,'extended_quickdraw':3},
            'capacity':'Preserve each original extended_mag_ammo_amount and original base ammo_amount byte-for-byte; no universal capacities or sorting.',
            'initial':'Remove only these nine resolved attachment IDs and their experimental marker; preserve every original attachment leaf.',
            'experimental':'Add reviewed families only where the original slot and native level1/2/3 capacities remain valid; no slot creation.',
            'unknownGuns':'No new magazine authorization until reviewed; other original attachment leaves remain intact.',
            'dmrSlotRule':'Both rifle and sniper families may be allowed, but they share one EXTENDED_MAG slot and native capacity level.'},
        'summary':{'eligible':len(eligible),'excluded':len(rows)-len(eligible),'familyGunCounts':family_counts,
            'eligibleCategoryCounts':dict(collections.Counter(r['category'] for r in eligible)),
            'exclusionReasonCounts':dict(collections.Counter(x for r in rows for x in r['exclusionReasons'])),
            'newAttachmentEdges':sum(len(r['allowedMagazines']) for r in rows)},'guns':rows}
    for path in [RESOURCE,REPORT]:
        path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result['summary'],ensure_ascii=True,indent=2))

if __name__=='__main__':main()
