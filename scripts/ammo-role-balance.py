"""Approved role targets and a prior-experiment vs approved-target audit. No runtime writes."""
from __future__ import annotations

import bisect
import math
import statistics
import struct
from collections import Counter, defaultdict

DISTANCES = (10, 25, 50, 100, 200, 300, 500)
G = 9.80665


def role(title, distance, damage, head, aim, hip, ads, rpm_cap, retained, tradeoff):
    return dict(title=title, effectiveDistance=distance, damage=damage, head=head, aim=aim, hip=hip,
                ads=ads, rpmCap=rpm_cap, retained=retained, tradeoff=tradeoff)


# Supply IDs stay small; individual weapons keep their measured/reference exterior ballistics.
# Ratios define the approved envelope; the runtime exporter supplies targets to a 5m native table.
ROLES = {
 'rifle556': role('5.56通用步枪','25–150m',(5.0,5.9),(1.7,1.9),(.08,.13),(3.2,4.5),(.20,.30),900,
     (1,1,.95,.85,.65,.50,.20),'较快射速；中距通用，远距伤害和精度让位于DMR/栓狙。'),
 'rifle762': role('7.62重单发步枪','25–180m',(6.3,7.2),(1.7,1.9),(.09,.15),(3.8,5.0),(.24,.36),700,
     (1,1,.98,.90,.75,.60,.30),'单发更重、射速上限较低；保留现有持久后坐力，不靠加速补DPS。'),
 'smg9': role('9mm快速冲锋枪','0–50m',(3.5,4.2),(1.4,1.6),(.12,.20),(1.5,2.5),(.10,.18),1100,
     (1,.90,.65,.35,.15,.10,.03),'腰射较稳、瞄准快；50m以后明显掉伤。'),
 'smg45': role('.45重单发冲锋枪','0–50m',(4.3,5.0),(1.4,1.6),(.13,.21),(1.6,2.6),(.12,.20),850,
     (1,.95,.65,.30,.12,.08,.02),'稍慢、单发较重；较低真实弹速及远距掉伤限制射程。'),
 'pdw57': role('稀有5.7 PDW','0–100m',(4.5,5.2),(1.5,1.7),(.10,.17),(1.6,2.6),(.13,.22),1050,
     (1,.98,.80,.50,.25,.12,.04),'比普通SMG远，但不替代步枪；独立稀有补给与随枪弹量仍待决定。'),
 'pistol9': role('9mm普通手枪','0–30m',(4.8,6.0),(1.5,1.8),(.16,.26),(1.8,3.0),(.10,.18),480,
     (1,.90,.55,.25,.10,.05,0),'补枪与操控；保留真实弹匣/换弹，纸面点击上限不等于玩家射速。'),
 'pistol45': role('.45重单发手枪','0–35m',(6.0,7.2),(1.5,1.8),(.17,.27),(2.0,3.2),(.12,.21),360,
     (1,.95,.60,.30,.10,.05,0),'较高单发，射速上限更低；不会随弹药ID自动获得重狙能力。'),
 'machine_pistol': role('自动/连发手枪','0–40m',(3.6,4.5),(1.4,1.6),(.15,.24),(1.8,2.8),(.10,.18),1100,
     (1,.90,.60,.30,.12,.06,.01),'按近距连射角色而非普通半自动手枪处理；脚本节奏仍需复核。'),
 'buckshot': role('普通多弹丸霰弹','0–20m',(24,30),(1.1,1.3),(2.5,4.0),(3.0,4.5),(.18,.28),300,
     (1,.45,.08,.01,0,0,0),'数值为整发全弹丸命中上限；散布与掉伤共同限制远距，保留实际弹丸数。'),
 'slug': role('单弹丸霰弹/独头弹候选','15–100m',(14,18),(1.4,1.6),(.15,.30),(3.0,4.5),(.22,.34),260,
     (1,1,.90,.65,.30,.10,0),'目前只有普通O12进入此分支；单弹丸不自动证明弹型，需核验。'),
 'dmr': role('半自动DMR','75–250m',(7.5,9.5),(1.8,2.0),(.04,.08),(4.0,6.0),(.30,.45),360,
     (1,1,1,.95,.85,.75,.45),'普通无甲近距不普遍一枪头；远距保值、连续修正，腰射较差。'),
 'bolt': role('普通精确慢射狙击','100–350m',(13.5,16.5),(2.2,2.6),(.02,.05),(5.0,8.0),(.45,.70),70,
     (1,1,1,1,.90,.80,.60),'300m无甲理论一枪头、两枪身；低射速、慢ADS、腰射差，失误代价高。'),
 'heavy_bolt': role('稀有重型慢射狙击','150–500m',(20,24),(1.8,2.2),(.025,.055),(6.0,9.0),(.65,.95),45,
     (1,1,1,1,.98,.95,.85),'已批准允许近距无甲一枪身；稀有弹、慢射与慢ADS制约。'),
 'heavy_semi': role('稀有重型半自动狙击','150–400m',(16,19.5),(1.8,2.2),(.03,.06),(6.0,9.0),(.55,.85),110,
     (1,1,1,1,.97,.92,.80),'避免同时获得一枪身和高连射；不因源码manual_action就误判为低射速栓狙。'),
 'lever': role('杠杆/早期单发精确枪','30–150m',(10,13),(2.0,2.3),(.055,.10),(3.5,5.0),(.25,.42),120,
     (1,1,.95,.80,.50,.30,.10),'低速历史平台保留近中距身份，不套现代300m栓狙保证。'),
 'mg': role('普通支援机枪','50–250m',(5.3,6.5),(1.6,1.9),(.10,.18),(4.5,6.5),(.35,.55),900,
     (1,1,1,.90,.75,.65,.40),'保留真实弹匣/换弹的持续优势；慢ADS、站姿腰射差；未新增架枪或移动ADS逻辑。'),
 'heavy_mg': role('稀有大口径重机枪','75–300m',(9,12),(1.5,1.8),(.16,.28),(5.0,7.5),(.55,.85),550,
     (1,1,1,.95,.85,.75,.50),'高单发、大口径供给；慢操控和射速上限制约，M134另列人工例外。'),
}

DMR_IDS = {
 'tacz:sks_tactical','tacz:spr15hb','ccrp:m14_hbar','ccrp:m38_spr','ccrp:qbu_191',
 'ccrp:aug_a3_dmr','ccrp:cslr_44','ccrp:camg_krait','cib:qbu191','cib:qbu88',
 'suffuse:svd','hamster:sks','rainforest:hk_spg1',
}
EARLY_IDS = {'hamster:win1873','hamster:win1894','hamster:sharps',
             'tacz:springfield1873','ccrp:springfield1873_tube_mag'}
HEAVY_SEMI = {'tacz:m107','cib:m99','cib:qbu10','classicr:m82a2','suffuse:gm6','ccrp:mk18_mjolnir'}


def f32(x):
    return struct.unpack('f',struct.pack('f',float(x)))[0]


def quantile(values, p):
    values=sorted(x for x in values if x is not None and math.isfinite(x))
    if not values: return None
    q=(len(values)-1)*p; i=int(q); j=min(i+1,len(values)-1)
    return values[i]*(j-q)+values[j]*(q-i) if j!=i else values[i]


def summary(values):
    values=[v for v in values if v is not None and math.isfinite(v)]
    return dict(n=len(values), p10=quantile(values,.1), median=quantile(values,.5), p90=quantile(values,.9))


def pick_role(row, raw, physics, balance):
    if row['pool'] in ('特殊待确认','排除') or physics['action']!='apply':
        return None, '特殊/身份未证或原弹道保留，本轮不套普通角色数值。'
    if row['type']=='rpg': return None,'火箭/榴弹另列，不套普通枪弹角色。'
    if row['type']=='pistol' and balance['adjusted']['pellets']>1:
        return None,'双弹丸手枪另行核验，不套普通单弹手枪伤害区间。'
    aid=row['proposedAmmo']; gid=row['gunId']; kind=row['type']
    if kind=='shotgun': return ('slug' if balance['adjusted']['pellets']==1 else 'buckshot'),'按实际弹丸数拆分，独头弹身份需核验。'
    if kind=='mg': return ('heavy_mg' if aid=='tacz:50bmg' else 'mg'),'注册机枪加稀有大口径身份。'
    if gid in EARLY_IDS: return 'lever','已列明的杠杆/早期单发平台，不从注册type自动推断现代狙。'
    if kind=='sniper':
        if aid=='tacz:50bmg':
            return ('heavy_semi' if gid in HEAVY_SEMI else 'heavy_bolt'),'按已列明的重型半自动原型例外拆分，保留机械核验要求。'
        return ('bolt' if raw.get('bolt')=='manual_action' else 'dmr'),'栓型加已列明的平台例外，属于候选角色。'
    if kind=='rifle' and gid in DMR_IDS: return 'dmr','显式DMR候选清单，涵盖注册为步枪的SKS/Mini14；不是单看注册类型。'
    if kind in ('pistol','smg'):
        if aid=='cbrg:ammo_57mm': return 'pdw57','稀有5.7供给对应PDW角色。'
        if kind=='smg': return ('smg45' if aid=='cbrg:ammo_45acp' else 'smg9'),'供给身份下的普通冲锋角色。'
        if not gid.startswith('hamster:') and ('auto' in raw.get('fire_mode',[]) or
                'burst' in raw.get('fire_mode',[]) and row['maxModeDps10']>=60):
            return 'machine_pistol','现代自动/连发手枪，按近距连射子角色。'
        return ('pistol45' if aid=='cbrg:ammo_45acp' else 'pistol9'),'普通半自动手枪角色；实际点击/历史脚本仍需核验。'
    return ('rifle762' if aid=='cbrg:ammo_762' else 'rifle556'),'普通步枪按已选供弹身份拆分。'


def damage_current(raw, factor, mode, distance):
    b=raw.get('bullet',{}); ex=b.get('extra_damage') or {}; table=ex.get('damage_adjust') or []
    if table:
        d=next((p['damage'] for p in table if p['distance']=='infinite' or distance<float(p['distance'])),0)
    else: d=b.get('damage',5)
    adj=(raw.get('fire_mode_adjust') or {}).get(mode) or {}
    return max(0.,f32(f32(float(d)*factor)+f32(float(adj.get('damage',0))*factor)))


def interpolate(role_def, distance):
    ratios=role_def['retained']
    if distance<=DISTANCES[0]: return ratios[0]
    for i in range(1,len(DISTANCES)):
        if distance<=DISTANCES[i]:
            q=(distance-DISTANCES[i-1])/(DISTANCES[i]-DISTANCES[i-1])
            return ratios[i-1]*(1-q)+ratios[i]*q
    return ratios[-1]


def shots(damage):
    return math.ceil(20/damage) if damage>0 else None


def flight(physics):
    """Horizontal-bore free-space trajectory, same 20Hz swept chord as existing mod.
    Velocity at an intermediate collision is a linear tick interpolation for reporting.
    """
    v0=physics['experimentalEffectiveVelocityMps']; vx=v0/20; vy=x=y=0.
    real=physics['action']=='apply'
    gravity=(physics['gravityMps2']/400 if real else physics['rawBullet'].get('gravity',0))
    k=physics['effectiveDragPerMeter'] if real else None
    friction=physics['rawBullet'].get('friction',.01)
    ticks=physics['experimental']['motionTicks']; out={}
    for tick in range(ticks):
        if real:
            uy=vy-gravity/2; z=k*math.hypot(vx,uy); keep=1/(1+z)
            drift=math.log1p(z)/z if z else 1.
            dx,dy=vx*drift,uy*drift
            nxv,nyv=vx*keep,uy*keep-gravity/2
        else:
            dx,dy=vx,vy; nxv,nyv=vx*(1-friction),vy*(1-friction)-gravity
        nx,ny=x+dx,y+dy
        for distance in DISTANCES:
            if str(distance) not in out and x<=distance<=nx and dx>0:
                q=(distance-x)/dx; drop=-(y+q*dy)
                svx=vx+q*(nxv-vx); svy=vy+q*(nyv-vy)
                out[str(distance)]=dict(time=(tick+q)/20,drop=drop,
                    horizontalSpeed=svx*20, speed=math.hypot(svx,svy)*20,
                    horizontalRetention=svx*20/v0,
                    damageDistance=math.hypot(distance,drop))
        x,y,vx,vy=nx,ny,nxv,nyv
        if x>=max(DISTANCES): break
    return {str(d):out.get(str(d)) for d in DISTANCES}


def remap(value, values, lo, hi):
    vals=sorted(values)
    if len(vals)<2: return (lo+hi)/2
    rank=(bisect.bisect_left(vals,value)+bisect.bisect_right(vals,value)-1)/2
    return lo+(hi-lo)*rank/(len(vals)-1)


def enrich(rows, assets, balances, physics_rows, factor_map):
    physics={g['gunId']:g for g in physics_rows}
    balances={g['id']:g for g in balances}
    groups=defaultdict(list)
    for row in rows:
        gid=row['gunId']; raw=assets[gid]['rawData']; b=balances[gid]; p=physics[gid]
        rid,basis=pick_role(row,raw,p,b)
        row['roleKey']=rid or 'special'; row['roleName']=ROLES[rid]['title'] if rid else '特殊/原参数保留'
        row['roleBasis']=basis
        if rid: groups[rid].append(row)
    for row in rows:
        gid=row['gunId']; raw=assets[gid]['rawData']; b=balances[gid]; a=b['adjusted']; p=physics[gid]
        factor=factor_map[gid]['damageFactor'] if gid in factor_map else 1.
        trace=flight(p)
        current=dict(velocity=p['experimentalEffectiveVelocityMps'],dragPerMeter=p.get('effectiveDragPerMeter'),
                     physicsApplied=p['action']=='apply', head=a['head_multiplier'],aim=a['spread_aim'],
                     hip=a['spread_stand'],ads=a['ads'],rpm=a['rpm_effective'],distances={})
        for d in DISTANCES:
            impact=trace[str(d)]
            dd=impact['damageDistance'] if impact else float(d)
            damage=damage_current(raw,factor,row['mode'],dd)
            current['distances'][str(d)]=dict(damage=damage,headDamage=damage*a['head_multiplier'],
                retention=damage/a['damage_10'] if a['damage_10'] else None,
                bodyShots=shots(damage),headShots=shots(damage*a['head_multiplier']),flight=impact)
        rid=row['roleKey']; candidate=None
        if rid!='special':
            spec=ROLES[rid]; group=groups[rid]
            def pick(metric, interval):
                return remap(a[metric],[balances[r['gunId']]['adjusted'][metric] for r in group],*interval)
            candidate=dict(damage10=pick('damage_10',spec['damage']), head=pick('head_multiplier',spec['head']),
                aim=pick('spread_aim',spec['aim']),hip=pick('spread_stand',spec['hip']),ads=pick('ads',spec['ads']),
                rpm=min(a['rpm_effective'],spec['rpmCap']),rpmCap=spec['rpmCap'],
                velocity=current['velocity'],dragPerMeter=current['dragPerMeter'],gravityMps2=G,
                exteriorBallisticsChanged=False,distances={})
            candidate['dps10']=candidate['damage10']*candidate['rpm']/60
            for d in DISTANCES:
                impact=trace[str(d)]; dd=impact['damageDistance'] if impact else float(d)
                damage=candidate['damage10']*interpolate(spec,dd)
                candidate['distances'][str(d)]=dict(damage=damage,headDamage=damage*candidate['head'],
                    retention=interpolate(spec,dd),bodyShots=shots(damage),headShots=shots(damage*candidate['head']),flight=impact)
            candidate['damageEnvelope']=[dict(distance=d,retained=v) for d,v in zip(DISTANCES,spec['retained'])]
            candidate['implementation']='已实装为5米阶梯damage_adjust，规范全部模式；保留批准连续目标用于对照，尚未进行游戏实测。'
        row['roleBalance']=dict(current=current,candidate=candidate,physicsConfidence=p['confidence'],
             exteriorReason=p['reason'],spreadUnit='TaCZ无量纲参数；非固定角度/MOA',
             scriptedSpreadPossible='scripted_gun_logic' in b['flags'],applied=rid!='special')
    current_stats=[]
    for ty in ('rifle','smg','pistol','shotgun','sniper','mg'):
        rr=[r for r in rows if r['type']==ty and r['damage10']>0]
        real=[r for r in rr if r['roleBalance']['current']['physicsApplied']]
        current_stats.append(dict(type=ty,count=len(rr),realCount=len(real),
            velocity=summary([r['roleBalance']['current']['velocity'] for r in real]),
            drag=summary([r['roleBalance']['current']['dragPerMeter'] for r in real]),
            speedRetention100=summary([r['roleBalance']['current']['distances']['100']['flight']['horizontalRetention'] for r in real if r['roleBalance']['current']['distances']['100']['flight']]),
            flight100=summary([r['roleBalance']['current']['distances']['100']['flight']['time'] for r in real if r['roleBalance']['current']['distances']['100']['flight']]),
            drop300=summary([r['roleBalance']['current']['distances']['300']['flight']['drop'] for r in real if r['roleBalance']['current']['distances']['300']['flight']]),
            retained100=summary([r['roleBalance']['current']['distances']['100']['retention'] for r in rr]),
            retained300=summary([r['roleBalance']['current']['distances']['300']['retention'] for r in rr]),
            aim=summary([r['roleBalance']['current']['aim'] for r in rr]),
            headKill100=sum(r['roleBalance']['current']['distances']['100']['headShots']==1 for r in rr)))
    return dict(status='ROLE_BALANCE_IMPLEMENTED_OPTIONAL_NOT_GAME_TESTED',ammoKindsUnchanged=True,
        candidateGuns=sum(r['roleKey']!='special' for r in rows),
        roleCounts=dict(Counter(r['roleName'] for r in rows)),roles=ROLES,currentStats=current_stats,
        notes=IMPLEMENTATION_NOTES.copy())


IMPLEMENTATION_NOTES = [
    '供弹粒度和弹道粒度分别处理：420把固定供弹映射、340把改ID、18种供弹；子弹与枪械平衡分别选择实验性调整并保存，旧背包弹药不自动转换。',
    '386把采用已批准的角色伤害、散布、头倍率、ADS与射速上限；38把保留先前实验逻辑。既有396把现实初速/阻力/统一重力及28把特殊原弹道分支保持。',
    'current字段与旧实验列是改动前的对照；candidate字段是已批准目标。实装使用5米左保持阶梯，准确通过10/25/50/100/200/300/500米包络节点，首段距离仍表示完整伤害射程。',
    'TaCZ JSON damage是整发伤害，原生按bullet_amount均分给弹丸；实装不会再除一次。命中发数仍为20HP无甲全命中直伤上限。',
    '所有fire_mode_adjust的伤害、头倍率、瞄准及其他散布增量归零，防止切换模式突破角色目标；逐模式只降射速，burst的BPM及整轮延迟也限速。',
    '射速定时器为整数毫秒，保守量化后实际值可能略低于批准RPM上限；例如700上限使用697字段、86毫秒间隔，即约697.67RPM。',
    '108把带脚本角色枪涉及30份唯一Lua，静态核对均无calcSpread或属性修改覆写；保留原装填、拉栓与shootOnce机械逻辑。未来更换枪包需重新核对。',
    '飞行/下坠沿既有20Hz碰撞扫线插值；按出生点到命中的3D直线距离查伤害。图表未模拟地形、归零、玩家命中率、护甲、爆炸或燃烧追加效果。',
    '不声称保持旧综合难度排名：原研究文件保留，但角色伤害与散布覆盖旧目标，新的运行方案须用体验数据重新校准。',
    '只有完全开镜进入AIM；未新增移动ADS、架枪或支架系统。散布是TaCZ无量纲输入，不是固定角度或MOA。',
    '稀有重型慢射狙允许近距无甲一枪身；重型半自动保持低于20HP单发并限制射速。普通DMR与慢射狙的全部模式边界已做离线检查。',
    '本次仅完成实装和离线检查；未启动客户端、服务器或世界，未进行游戏实测。',
]
