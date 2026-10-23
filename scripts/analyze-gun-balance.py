"""Static, reproducible game-weapon analysis; writes review artifacts, never a live pack.
Run with the bundled scientific Python and logs/analysis-python on PYTHONPATH.
"""
from pathlib import Path
import collections, copy, csv, hashlib, json, math, sys
import numpy as np
from scipy.interpolate import CubicSpline
from scipy.optimize import linprog
from scipy.stats import kendalltau, spearmanr

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/gun-balance'
HEALTH = 20.0
GLOBAL_SPEED = 2.0  # Current run/config/tacz-common.toml sample; not a live-server claim.
TYPES = ['rifle','smg','mg','pistol','sniper','shotgun','rpg']
ROLE_RANGE = dict(rifle=50.,smg=20.,mg=50.,pistol=15.,sniper=100.,shotgun=10.,rpg=35.)
TARGET_DAMAGE = dict(rifle=6.,smg=4.5,mg=5.5,pistol=6.,sniper=14.,shotgun=24.)
INC_DEFAULT = dict(stand=5.,move=5.75,sneak=3.5,lie=2.5,aim=.15)
WEIGHTS = np.array([.26,.16,.10,.10,.08,.06,.08,-.16])
FEATURES = ['control','aim','hip','handling','ammunition','mobility','trajectory','power']
METRICS = []
def metric(id,label,unit,description,group):
    METRICS.append(dict(id=id,label=label,unit=unit,description=description,group=group))

for d in [0,10,50,100]:
    metric(f'damage_{d}',f'{d}格躯干直击总伤害','HP/发','当前选定模式、全弹丸命中、按r7阶梯距离表；排除爆炸溅射和燃烧','伤害')
for id,label,unit,desc in [
 ('raw_damage','JSON原始damage','HP/发','可能被damage_adjust覆盖，不用于直接判定BTK'),
 ('pellet_damage_10','10格每颗弹丸伤害','HP/颗','总伤害按弹丸数分摊；全命中不一定成立'),
 ('head_damage_10','10格全命中爆头伤害','HP/发','无甲、理论总量'),
 ('pellets','每发弹丸数','颗/发','不是连发扳机的发数'),
 ('head_multiplier','爆头倍率','倍','选定模式后的基础倍率'),
 ('armor_ignore','穿甲比例','比例','0..1伤害拆分，不是减掉护甲点'),
 ('btk_10','10格躯干直击击杀发数','发','20生命无甲、全命中，排除爆炸和燃烧，0伤害为N/A'),
 ('btk_diamond_10','10格钻石甲躯干击杀发数','发','护甲20/韧性8，无附魔/抗弹属性；逐弹丸非线性减伤'),
 ('ttk_10','10格直击击杀时间代理','秒','首发到致死发，排除爆炸和燃烧；含分析排程的换弹/栓动约束，不是实测'),
 ('ideal_dps','近距潜在DPS','HP/秒','全命中、不计换弹的纸面值'),
 ('sustained_dps','近距持续DPS代理','HP/秒','按整匣与空仓冷却估计；Lua/热量/蓄力另有不确定性')]:
    metric(id,label,unit,desc,'伤害')
for id,label,unit,desc in [
 ('rpm_raw','JSON射速','RPM','原始字段，未套模式和缓存限幅'),
 ('rpm_effective','有效节奏代理','RPM','模式增量、缓存限幅、栓动/连发约束的分析代理'),
 ('magazine','基础弹容','发','物品栏直接装填为N/A'),
 ('extended_1','扩容槽等级1容量','发','可能是快换而非扩容'),
 ('extended_2','扩容槽等级2容量','发','由枪自身容量表定义'),
 ('extended_3','扩容槽等级3容量','发','不保证容量最多'),
 ('burst_count','连发数量','发/组','无连发模式为N/A'),
 ('burst_bpm','组内连发射速','RPM','无连发模式为N/A'),
 ('burst_interval','连发组最小间隔','秒','无连发模式为N/A')]:
    metric(id,label,unit,desc,'射速与供弹')
for pose in INC_DEFAULT:
    metric('spread_'+pose,'散布参数 · '+pose,'参数','Projectile三角扰动输入，近似角度尺度，不是固定锥角','散布')
for id,label in [('pitch_rms','垂直后坐力期望RMS'),('yaw_rms','水平后坐力期望RMS'),('recoil_peak','后坐力RMS包络峰值')]:
    metric(id,label,'度','独立均匀关键帧+自然三次样条，时间积分二阶矩；不是实测压枪难度','后坐力')
metric('recoil_duration','后坐力最长曲线时长','秒','包含r7的30毫秒起点偏移','后坐力')
for id,label in [('ads','开镜时间'),('draw','拔枪时间'),('holster','收枪时间'),('sprint','疾跑转换时间'),('bolt_time','手动拉栓时间'),('reload_empty','空仓换弹冷却'),('reload_tactical','战术换弹冷却'),('feed_empty','空仓装入时间'),('feed_tactical','战术装入时间')]:
    metric(id,label,'秒','JSON及r7默认值；复杂Lua装填实际排程不等于此数','操作时间')
for id,label,unit in [('velocity_raw','基础缓存弹速','格/秒'),('velocity','初始弹速 · 公共系数×2','格/秒'),('gravity','重力参数','格/tick²'),('drag','阻力参数','比例/tick'),('life','弹丸生命期','秒'),('pierce','贯穿次数','次'),('knockback','击退参数','参数'),('weight','重量','kg参数'),('move_base','常态速度修正','比例'),('move_aim','瞄准速度修正','比例'),('move_reload','装填速度修正','比例')]:
    metric(id,label,unit,'基础数据含选定模式增量（适用时）；单位以固定r7实现为准','其他数值')
for id,label,unit in [('explosion_damage','爆炸伤害功率','HP'),('explosion_radius','爆炸半径','格'),('explosion_delay','爆炸延迟原始值','秒')]:
    metric(id,label,unit,'仅explode=true；不是任意目标实际承受伤害','爆炸')
metric('mechanical_burden','操作负担代理','相对分','不扣除杀伤效率的机械负担，未经玩家实验校准','模型')
metric('composite_difficulty','综合使用难度代理','相对分','冻结参照的操作负担减杀伤效率；仅本模型排名，不是玩家实测排名','模型')
for id,label,unit in [('flight_time_role','角色距离水平弹道飞行时间','秒'),('drop_role','角色距离水平弹道下坠','格'),('horizontal_reach','寿命内水平前向距离','格')]:
    metric(id,label,unit,'按20tick/秒、先移动后阻力后重力的无遮挡静止水平发射分析；未到目标为N/A','弹道任务')

def num(d,k,default):
    v=d.get(k,default)
    return float(v) if isinstance(v,(int,float)) and math.isfinite(v) else float(default)

def recoil(axis):
    if not axis: return 0.,0.,0.
    axis=sorted(axis,key=lambda p:p['time'])
    t=np.array([0.]+[float(p['time'])+.03 for p in axis])
    if np.any(np.diff(t)<=0): raise ValueError('non-increasing recoil keyframe times')
    lo=np.array([0.]+[float(p['value'][0]) for p in axis]); hi=np.array([0.]+[float(p['value'][1]) for p in axis])
    mean=(lo+hi)/2; var=(hi-lo)**2/12
    q=np.linspace(0,t[-1],601)
    # Cubic spline is linear in knot ordinates, hence its second moment can be computed without RNG.
    basis=CubicSpline(t,np.eye(len(t)),axis=0,bc_type='natural')(q)
    second=(basis@mean)**2+(basis**2)@var
    return float(np.sqrt(np.trapezoid(second,q)/t[-1])),float(np.sqrt(second.max())),float(t[-1])

def setup(raw):
    modes=raw.get('fire_mode',['unknown']); mode=modes[0]
    adj=raw.get('fire_mode_adjust',{}).get(mode,{}) or {}
    bullet=raw.get('bullet',{}); extra=bullet.get('extra_damage',{}) or {}
    rpm=num(raw,'rpm',300)+num(adj,'rpm',0)
    rpm=300 if rpm<=0 else max(1,min(1200,rpm))
    interval=math.floor(60000/rpm)/1000
    if raw.get('bolt')=='manual_action': interval=max(interval,num(raw,'bolt_action_time',0))
    burst=raw.get('burst_data',{}) or {}
    burst_count=max(1,int(num(burst,'count',3)))
    bpm=num(burst,'bpm',200)
    bstep=math.floor(60000/bpm)/1000 if bpm>0 else .3
    period=max(num(burst,'min_interval',1),(burst_count-1)*bstep+interval)
    if mode=='burst': effective=60*burst_count/period
    else: effective=60/interval
    reload=raw.get('reload',{}) or {}
    empty=num(reload.get('cooldown',{}) or {},'empty',2.5)
    tactical=num(reload.get('cooldown',{}) or {},'tactical',2.)
    return dict(mode=mode,adj=adj,bullet=bullet,extra=extra,interval=interval,burst_count=burst_count,
                burst_step=bstep,burst_period=period,effective=effective,empty=empty,tactical=tactical,
                inventory=reload.get('type','magazine')=='inventory')

def damage_at(raw,distance):
    s=setup(raw); b=s['bullet']; table=s['extra'].get('damage_adjust',[]) or []
    if table:
        damage=None
        for p in table:
            threshold=math.inf if p['distance']=='infinite' else float(p['distance'])
            if distance < threshold: damage=float(p['damage']); break
        if damage is None:return 0.
    else: damage=num(b,'damage',5)
    return max(0.,float(np.float32(damage+num(s['adj'],'damage',0))))

def trajectory(velocity,gravity,drag,life,distance):
    vx=velocity/20;vy=0.;x=0.;y=0.;reach=0.;flight=None;drop=None
    ticks=max(1,int(life*20))
    for tick in range(ticks):
        nx=x+vx;ny=y+vy;reach=max(reach,nx)
        if flight is None and x<=distance<=nx and vx>0:
            fraction=(distance-x)/vx;flight=(tick+fraction)/20;drop=-(y+fraction*vy)
        x,y=nx,ny;vx*=1-drag;vy=vy*(1-drag)-gravity
    # Explicit engineering burden, not a predicted human aiming time.
    cost=(flight if flight is not None else ticks/20+2*max(0.,distance-reach)/distance)
    cost+=.1*abs(drop if drop is not None else y)/.9
    return flight,drop,reach,cost

def reduced_pellet(damage,armor_ignore=0.,armor=0.,toughness=0.):
    regular=damage*(1-armor_ignore)
    reduction=min(20.,max(.2*armor,armor-regular/(2+toughness/4)))
    return regular*(1-reduction/25)+damage*armor_ignore

def btk(damage,pellets,armor_ignore,armor=0.,toughness=0.,head=1.):
    if damage<=0: return None
    per=float(np.float32(np.float32(damage)*np.float32(1./pellets))*np.float32(head))
    h=np.float32(HEALTH)
    for n in range(1,10001):
        for _ in range(pellets):
            h=np.float32(h-np.float32(reduced_pellet(per,armor_ignore,armor,toughness)))
            if h<=0: return n
    return None

def scheduled_ttk(count,s,mag):
    if count is None: return None
    n=count-1
    if s['mode']=='burst':
        t=(n//s['burst_count'])*s['burst_period']+(n%s['burst_count'])*s['burst_step']
    else: t=n*s['interval']
    if mag and count>mag: t+=((count-1)//mag)*s['empty']
    return float(t)

def measure(raw,kind):
    s=setup(raw); b=s['bullet']; ex=s['extra']; adj=s['adj']
    m={f'damage_{d}':damage_at(raw,d) for d in [0,10,50,100]}
    pellets=max(1,int(num(b,'bullet_amount',1)))
    head=max(0.,num(ex,'head_shot_multiplier',1 if b.get('extra_damage') is not None else 0)+num(adj,'head_shot_multiplier',0))
    armor_ignore=max(0.,min(1.,num(ex,'armor_ignore',0)+num(adj,'armor_ignore',0)))
    mag=None if s['inventory'] else max(1,int(num(raw,'ammo_amount',30)))
    pitch,pp,pt=recoil(raw.get('recoil',{}).get('pitch'))
    yaw,yp,yt=recoil(raw.get('recoil',{}).get('yaw'))
    inc=raw.get('inaccuracy',{}) or {}
    inc={p:max(0.,num(inc,p,v)+num(adj,'aim_inaccuracy' if p=='aim' else 'other_inaccuracy',0)) for p,v in INC_DEFAULT.items()}
    m.update(raw_damage=num(b,'damage',5),pellet_damage_10=m['damage_10']/pellets,
             head_damage_10=m['damage_10']*head,pellets=pellets,head_multiplier=head,armor_ignore=armor_ignore,
             rpm_raw=num(raw,'rpm',300),rpm_effective=s['effective'],magazine=mag,
             pitch_rms=pitch,yaw_rms=yaw,recoil_peak=math.hypot(pp,yp),recoil_duration=max(pt,yt),
             ads=num(raw,'aim_time',.2),draw=num(raw,'draw_time',.4),holster=num(raw,'put_away_time',.4),
             sprint=num(raw,'sprint_time',.2),bolt_time=num(raw,'bolt_action_time',0),
             reload_empty=s['empty'],reload_tactical=s['tactical'],
             feed_empty=num((raw.get('reload',{}).get('feed',{}) or {}),'empty',2.5),
             feed_tactical=num((raw.get('reload',{}).get('feed',{}) or {}),'tactical',2),
             velocity_raw=max(.001,num(b,'speed',5)+num(adj,'speed',0)),
             velocity=GLOBAL_SPEED*max(.001,num(b,'speed',5)+num(adj,'speed',0)),gravity=max(0.,num(b,'gravity',0)),
             drag=num(b,'friction',.01),life=num(b,'life',10),pierce=num(b,'pierce',1),
             knockback=num(b,'knockback',0)+num(adj,'knockback',0),weight=num(raw,'weight',0))
    for p,v in inc.items(): m['spread_'+p]=v
    for p in ['base','aim','reload']: m['move_'+p]=num(raw.get('movement_speed',{}) or {},p,0)
    for i in range(3):
        arr=raw.get('extended_mag_ammo_amount',[]) or []
        m['extended_'+str(i+1)]=float(arr[i]) if len(arr)>i and not s['inventory'] else None
    burst=raw.get('burst_data',{}) or {}
    for key,bkey,default in [('burst_count','count',3),('burst_bpm','bpm',200),('burst_interval','min_interval',1)]:
        m[key]=num(burst,bkey,default) if 'burst' in raw.get('fire_mode',[]) else None
    explosion=b.get('explosion',{}) or {}; enabled=explosion.get('explode',False)
    for key,bkey,default in [('explosion_damage','damage',0),('explosion_radius','radius',0),('explosion_delay','delay',0)]:
        m[key]=num(explosion,bkey,default) if enabled else None
    m['btk_10']=btk(m['damage_10'],pellets,armor_ignore)
    m['btk_diamond_10']=btk(m['damage_10'],pellets,armor_ignore,20,8)
    m['ttk_10']=scheduled_ttk(m['btk_10'],s,mag)
    m['ideal_dps']=m['damage_0']*s['effective']/60
    m['sustained_dps']=m['ideal_dps'] if mag is None else m['damage_0']*mag/(mag/(s['effective']/60)+s['empty'])
    # Role-specific task proxies, not a human fit or hit-probability simulation.
    distance=ROLE_RANGE[kind]; d=damage_at(raw,distance)
    per=d/pellets
    armored=pellets*reduced_pellet(per,armor_ignore,20,8)
    mixed=.8*min(HEALTH,d)+.1*min(HEALTH,d*head)+.1*min(HEALTH,armored)
    explosion_bonus=0.
    if enabled:
        radius=m['explosion_radius']; power=m['explosion_damage']
        # Supplemental explicit open-space splash task at 1 block from explosion centre.
        explosion_bonus=.25*min(HEALTH,max(0.,1-.7/max(radius,.001))*power)
    speed_factor=max(.05,(1-.015*m['weight'])*(1+.4*m['move_base']+.4*m['move_aim']+.2*m['move_reload']))
    flight,drop,reach,trajectory_cost=trajectory(m['velocity'],m['gravity'],m['drag'],m['life'],distance)
    m.update(flight_time_role=flight,drop_role=drop,horizontal_reach=reach)
    vals=np.array([
        math.hypot(pitch,yaw)*s['effective']/60,
        inc['aim']*(.0172275/math.sqrt(6))*distance/.3, # per-axis small-angle noise sigma in radians
        (.4*inc['stand']+.4*inc['move']+.1*inc['sneak']+.1*inc['lie'])*(.0172275/math.sqrt(6))*distance/.3,
        m['ads']+.5*m['draw']+.25*m['sprint']+.1*m['holster'],
        0. if mag is None else (.6*s['empty']+.4*s['tactical'])*s['effective']/60/mag,
        1/speed_factor,
        trajectory_cost,
        (mixed+explosion_bonus)*s['effective']/60,
    ],dtype=float)
    return m,vals,s

def scaled_data(raw,damage_factor=1.,control_factor=1.):
    d=copy.deepcopy(raw); b=d.setdefault('bullet',{})
    b['damage']=num(b,'damage',5)*damage_factor
    for p in (b.get('extra_damage',{}) or {}).get('damage_adjust',[]) or []: p['damage']*=damage_factor
    for axis in ['pitch','yaw']:
        for p in d.get('recoil',{}).get(axis,[]) or []: p['value']=[v*control_factor for v in p['value']]
    original_inc=d.get('inaccuracy',{}) or {}
    d['inaccuracy']={p:num(original_inc,p,v)*control_factor for p,v in INC_DEFAULT.items()}
    for adj in (d.get('fire_mode_adjust',{}) or {}).values():
        if 'damage' in adj: adj['damage']*=damage_factor
        for k in ['aim_inaccuracy','other_inaccuracy']:
            if k in adj: adj[k]*=control_factor
    return d

def main():
    assets=json.loads((OUT/'gun-assets.json').read_text(encoding='utf-8'))
    records=[]; refs={}; summaries=[]; candidate=[]; diagnostics=[]
    for g in assets['guns']:
        m,x,s=measure(g['rawData'],g['type'])
        flags=list(g['specialFlagCodes'])
        if s['mode']=='burst': flags.append('burst_schedule_is_analytical_proxy')
        if g['rawData'].get('fire_mode_adjust'): flags.append('first_listed_mode_selected')
        if m['damage_0']<=0: flags.append('no_finite_damage_model')
        records.append(dict(id=g['id'],name=g['displayName'],type=g['type'],iconPath=g['icon']['path'],
                            current=m,adjusted=None,flags=flags,selectedMode=s['mode'],_x=x,_raw=g['rawData'],
                            dataRef=g['rawIndex'].get('data')))
    for kind in TYPES:
        group=[r for r in records if r['type']==kind]
        modeled=[r for r in group if r['current']['damage_0']>0]
        matrix=np.array([r['_x'] for r in modeled])
        reference=np.maximum(np.median(matrix,axis=0),np.array([.01,.01,.01,.01,.01,.01,.001,.01]))
        before_features=np.log1p(matrix/reference)
        raw_scores=before_features@WEIGHTS; centre=float(np.mean(raw_scores))
        scores=50+20*(raw_scores-centre); sd=float(np.std(scores))
        target_alpha=max(.05,min(.5,3/max(sd,1e-12)))
        refs[kind]=dict(featureNames=FEATURES,reference=reference.tolist(),rawCentre=centre,weights=WEIGHTS.tolist(),
                        roleRange=ROLE_RANGE[kind],damageTarget=TARGET_DAMAGE.get(kind),damagePower=.5)
        near_median=float(np.median([r['current']['damage_0'] for r in modeled]))
        factors=np.array([TARGET_DAMAGE[kind]*(r['current']['damage_0']/near_median)**.5/r['current']['damage_0']
                          if kind in TARGET_DAMAGE else 1. for r in modeled])
        provisional=[scaled_data(r['_raw'],float(f),1.) for r,f in zip(modeled,factors)]
        pmetrics=[]; pvalues=[]
        for raw in provisional:
            m,x,_=measure(raw,kind); pmetrics.append(m);pvalues.append(x)
        pvalues=np.array(pvalues)
        def feature_at(i,t):
            x=pvalues[i].copy(); x[:3]*=math.exp(t)
            return np.log1p(x/reference)
        def score_at(i,t): return 50+20*(float(feature_at(i,t)@WEIGHTS)-centre)
        def feasible(gain_min,gain_max):
            low=np.array([score_at(i,math.log(gain_min)) for i in range(len(modeled))])
            high=np.array([score_at(i,math.log(gain_max)) for i in range(len(modeled))])
            delta=scores-50
            A=np.vstack([np.column_stack([delta,np.ones(len(delta))]),np.column_stack([-delta,-np.ones(len(delta))])])
            b=np.r_[high,-low]
            sol=linprog([1.,0.],A_ub=A,b_ub=b,bounds=[(target_alpha,1.),(None,None)],method='highs')
            return sol,low,high
        gain_min,gain_max=.2,5.
        sol,low,high=feasible(gain_min,gain_max)
        if not sol.success:
            gain_min,gain_max=.1,10.
            sol,low,high=feasible(gain_min,gain_max)
        if not sol.success: raise RuntimeError(f'No feasible rank-preserving parameter adjustment: {kind}: {sol.message}')
        alpha=float(sol.x[0]);delta=scores-50
        c_low=float(np.max(low-alpha*delta));c_high=float(np.min(high-alpha*delta))
        intercept=float(np.clip(50.,c_low,c_high))
        targets=intercept+alpha*delta
        after_scores=[]; after_features=[]; gains=[]
        for i,(r,d_raw,d_factor,target) in enumerate(zip(modeled,provisional,factors,targets)):
            lo_t,hi_t=math.log(gain_min),math.log(gain_max)
            for _ in range(85):
                mid=(lo_t+hi_t)/2
                if score_at(i,mid)<target:lo_t=mid
                else:hi_t=mid
            gain=math.exp((lo_t+hi_t)/2); gains.append(gain)
            final=scaled_data(d_raw,1.,gain)
            new_m,new_x,_=measure(final,kind) # recompute from the changed JSON parameters, not the target score
            features=np.log1p(new_x/reference)
            new_score=50+20*(float(features@WEIGHTS)-centre)
            # Canonical ranking precision keeps mathematically tied copies tied despite IEEE-754 roundoff.
            r['current']['composite_difficulty']=round(float(scores[i]),9);r['current']['mechanical_burden']=float(20*(before_features[i,:7]@WEIGHTS[:7]))
            new_m['composite_difficulty']=round(new_score,9);new_m['mechanical_burden']=float(20*(features[:7]@WEIGHTS[:7]))
            r['scoreFullPrecisionCurrent']=float(scores[i]);r['scoreFullPrecisionAdjusted']=new_score
            r['adjusted']=new_m;r['damageFactor']=float(d_factor);r['controlFactor']=gain
            r['modelTarget']=float(target)
            r['featuresCurrent']=dict(zip(FEATURES,before_features[i].tolist()))
            r['featuresAdjusted']=dict(zip(FEATURES,features.tolist()))
            if gain>2 or gain<.5:r['flags'].append('large_control_change_review_required')
            after_scores.append(new_score);after_features.append(features)
            candidate.append(dict(gunId=r['id'],originalDataRef=r['dataRef'],damageFactor=float(d_factor),controlFactor=gain,
                                  selectedMode=r['selectedMode'],proposedData=final))
        after_scores=np.array(after_scores);after_features=np.array(after_features)
        max_error=float(np.max(np.abs(after_scores-targets)))
        # Exhaustive pair check preserves strict order and ties, allowing only numerical calculation noise.
        reversals=0;ties_broken=0
        for i in range(len(scores)):
            for j in range(i):
                b=scores[i]-scores[j];a=after_scores[i]-after_scores[j]
                if abs(b)>1e-7 and a*b<=0:reversals+=1
                if abs(b)<=1e-7 and abs(a)>1e-6:ties_broken+=1
        assert max_error<1e-6 and reversals==ties_broken==0,(kind,max_error,reversals,ties_broken)
        new_sd=float(np.std(after_scores)); assert abs(new_sd-alpha*sd)<1e-6
        canonical_before=np.round(scores,9);canonical_after=np.round(after_scores,9)
        before_order=sorted(range(len(modeled)),key=lambda i:(canonical_before[i],modeled[i]['id']))
        after_order=sorted(range(len(modeled)),key=lambda i:(canonical_after[i],modeled[i]['id']))
        assert before_order==after_order,(kind,'canonical rank changed')
        variations=[]
        for k in range(len(WEIGHTS)):
            for factor in [.75,1.25]:
                w=WEIGHTS.copy();w[k]*=factor
                old=before_features@w;new=after_features@w
                tau=float(kendalltau(old,new).statistic)
                variations.append(dict(feature=FEATURES[k],factor=factor,kendallTau=tau))
        summary=dict(type=kind,count=len(group),modeledCount=len(modeled),excludedIds=[r['id'] for r in group if r not in modeled],
                     currentMean=float(np.mean(scores)),adjustedMean=float(np.mean(after_scores)),
                     currentStd=sd,adjustedStd=new_sd,targetStd=3.,alpha=alpha,requestedAlpha=target_alpha,
                     targetAchieved=new_sd<=3.+1e-7,controlBounds=[gain_min,gain_max],
                     controlFactorMin=min(gains),controlFactorMax=max(gains),
                     damageFactorMin=float(min(factors)),damageFactorMax=float(max(factors)),
                     orderReversals=reversals,tiesBroken=ties_broken,maxScoreTargetError=max_error,
                     spearman=float(spearmanr(canonical_before,canonical_after).statistic),kendall=float(kendalltau(canonical_before,canonical_after).statistic),
                     canonicalRankIds=[modeled[i]['id'] for i in before_order],rankingPrecisionDecimals=9,
                     sensitivityMinKendall=min(v['kendallTau'] for v in variations),sensitivity=variations,
                     largeControlChanges=sum(g>2 or g<.5 for g in gains))
        summaries.append(summary)
        print(json.dumps({k:v for k,v in summary.items() if k not in ['sensitivity']},ensure_ascii=True),flush=True)
        for r in group:
            if r['adjusted'] is None:
                r['adjusted']=copy.deepcopy(r['current']);r['current']['composite_difficulty']=None;r['adjusted']['composite_difficulty']=None
                r['current']['mechanical_burden']=None;r['adjusted']['mechanical_burden']=None
                r['damageFactor']=r['controlFactor']=1.
    # Explicitly detect shared source data; proposed output is per index, never ambiguous in-place overwrites.
    shared=collections.defaultdict(list)
    for c in candidate:shared[c['originalDataRef']].append(c)
    shared_conflicts=[]
    for ref,items in shared.items():
        if len({json.dumps(c['proposedData'],sort_keys=True) for c in items})>1:
            shared_conflicts.append(dict(dataRef=ref,gunIds=[c['gunId'] for c in items],requiresClonedDataRef=True))
    for r in records:
        for k in ['_raw','_x']:r.pop(k,None)
    report=dict(schemaVersion=1,date='2026-10-18',health=20,baseline='unarmored + secondary plain diamond armor A20/T8',
                runtimeLaunched=False,deployed=False,globalSpeedSample=GLOBAL_SPEED,gunCount=len(records),modeledCount=sum(s['modeledCount'] for s in summaries),
                source='gun-assets.json',metrics=METRICS,guns=records,classSummary=summaries,references=refs,
                sharedDataConflicts=shared_conflicts,weights=dict(zip(FEATURES,WEIGHTS.tolist())),
                assumptions=['First listed fire mode; base gun, no attachment/event/Lua property simulation',
                    'Analytical firing/reload/burst/bolt proxies; not measured runtime TTK',
                    '80% unarmored body + 10% unarmored head + 10% plain diamond body for power proxy',
                    'Role distances are design choices; recoil/inaccuracy are mechanical proxies, not a fitted player model',
                    'Explosives: supplementary open-space 1-block splash task, no occlusion/self-damage/collateral simulation',
                    'pierce/knockback/ignition/heat/charge/custom extras are charted or flagged, not falsely claimed fully modeled',
                    'Class damage medians are an explicit 20HP design proposal, not recommendations derived from papers',
                    'Scores are relative and unbounded; references/weights frozen before adjustment',
                    'A zero-damage debug gun remains in every source table/chart but has no finite kill-difficulty score'])
    (OUT/'balance-data.json').write_text(json.dumps(report,ensure_ascii=False,indent=2,allow_nan=False),encoding='utf-8')
    candidate_report=dict(schemaVersion=1,deployed=False,requiresReview=True,sourceInventory='gun-assets.json',
        changes='Damage and full distance curve scaled; recoil ordinates and all stance inaccuracy scaled; mode damage/inaccuracy addends scaled; other fields retained',
        sharedDataConflicts=shared_conflicts,guns=candidate)
    (OUT/'proposed-gun-data.json').write_text(json.dumps(candidate_report,ensure_ascii=False,indent=2,allow_nan=False),encoding='utf-8')
    for phase in ['current','adjusted']:
        with (OUT/f'{phase}-attributes.csv').open('w',encoding='utf-8-sig',newline='') as f:
            keys=['id','name','type','selectedMode','damageFactor','controlFactor']+[m['id'] for m in METRICS]+['flags']
            w=csv.DictWriter(f,fieldnames=keys);w.writeheader()
            for r in records:
                row={k:r[k] for k in keys[:6]};row.update(r[phase]);row['flags']=';'.join(r['flags']);w.writerow(row)
    damage_summary=[]
    for kind in TYPES:
        rows=[r for r in records if r['type']==kind]
        entry=dict(type=kind,count=len(rows))
        for phase in ['current','adjusted']:
            arr=[r[phase] for r in rows]; vals=[m['damage_10'] for m in arr]
            one=sum(m['btk_10']==1 for m in arr);head=sum(m['head_damage_10']>=20 for m in arr)
            entry[phase]=dict(damageMin=min(vals),damageMedian=float(np.median(vals)),damageMax=max(vals),
                             bodyOneShot=one,headOneShot=head,noFiniteBTK=sum(m['btk_10'] is None for m in arr),
                             btkMedian=float(np.median([m['btk_10'] for m in arr if m['btk_10'] is not None])),
                             theoreticalTTKMedian=float(np.median([m['ttk_10'] for m in arr if m['ttk_10'] is not None])))
        damage_summary.append(entry)
    (OUT/'damage-summary.json').write_text(json.dumps(damage_summary,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'model-validation.json').write_text(json.dumps(dict(guns=424,modeled=report['modeledCount'],
        allRanksAndTiesPreserved=True,maxTargetError=max(s['maxScoreTargetError'] for s in summaries),
        sharedDataConflicts=shared_conflicts,allClassesTargetStdAchieved=all(s['targetAchieved'] for s in summaries),
        schemaNumericFinite=True,runtimeLaunched=False,deployed=False),ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__':main()
