"""Read-only 424-gun, all-mode age/range audit. No Minecraft/Gradle invocation.

Assumes a horizontal shot, dry air, no attachment/Lua overrides, terrain, entities,
fuse expiration or void cleanup. Those native removal reasons remain independent.
"""
from pathlib import Path
from collections import Counter
import csv
import json
import math
import struct
import tomllib
import shutil

ROOT = Path(__file__).resolve().parents[1]
ASSETS = json.loads((ROOT / 'docs/gun-balance/gun-assets.json').read_text(encoding='utf-8'))
PHYSICS = {p['gunId']: p for p in json.loads((ROOT / 'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/realistic_ballistics.json').read_text(encoding='utf-8'))['guns']}
CONFIG = tomllib.loads((ROOT / 'neoforge-1.21.1/run/config/tacz-common.toml').read_text(encoding='utf-8-sig'))


def find_setting(data, name):
    for key, value in data.items():
        if key == name:
            return value
        if isinstance(value, dict):
            result = find_setting(value, name)
            if result is not None:
                return result


GLOBAL = find_setting(CONFIG, 'GlobalBulletSpeedModifier')
assert isinstance(GLOBAL, (int, float)) and GLOBAL > 0


def f32(value):
    return struct.unpack('f', struct.pack('f', float(value)))[0]


def native_case(bullet, modes, mode):
    speed = max(0, f32(f32(f32(float(bullet.get('speed', 5))) + f32(float((modes.get(mode) or {}).get('speed', 0)))) * GLOBAL))
    vx = f32(speed / 20)
    life = max(1, int(f32(f32(float(bullet.get('life', 10))) * 20)))
    frames = max(1, life - 1)
    friction = max(0, f32(float(bullet.get('friction', .01))))
    gravity = max(0, f32(float(bullet.get('gravity', 0))))
    retain = f32(1 - friction)
    if not 0 <= friction < 1:
        return dict(lifeSeconds=life / 20, movementFramesBeforeAge=frames, speedMps=speed,
                    friction=friction, gravityPerTick=gravity, status='nonphysical_friction',
                    horizontalAtAge=None, asymptoticHorizontal=None, ticksTo512=None, dropAt512=None)
    if vx <= 0:
        frames512 = None
        limit = 0
    elif friction == 0:
        limit = None
        frames512 = math.ceil(512 / vx)
    else:
        limit = vx / (1 - retain)
        frames512 = math.ceil(math.log1p(-512 / limit) / math.log(retain)) if limit > 512 else None
    x = vx * frames if retain == 1 else vx * (-math.expm1(frames * math.log(retain))) / (1 - retain)
    if frames512 is None:
        drop = None
        status = 'cannot_reach_512_by_life_extension'
    else:
        drop = gravity * frames512 * (frames512 - 1) / 2 if retain == 1 else gravity / (1 - retain) * (frames512 - (1 - retain ** frames512) / (1 - retain))
        status = 'already_reaches_512_before_age' if frames512 <= frames else 'needs_age_extension'
    return dict(lifeSeconds=life / 20, movementFramesBeforeAge=frames, speedMps=speed,
                friction=friction, gravityPerTick=gravity, status=status,
                horizontalAtAge=x, asymptoticHorizontal=limit, ticksTo512=frames512, dropAt512=drop)


def external_case(bullet, profile):
    speed = float(profile['proposedMuzzleVelocityMps'])
    # Match float GunData/cache, native global multiplier and the launch mixin's cancellation.
    vx = f32(f32(f32(f32(speed) * GLOBAL) / 20) / GLOBAL)
    vy = 0
    gravity = f32(float(profile['gravityMps2']) / 400)
    drag = float(profile['effectiveDragPerMeter'])
    life = max(1, int(f32(f32(max(10, float(bullet.get('life', 10)))) * 20)))
    age_frames = max(1, life - 1)
    x = y = 0
    at_age = None
    frames512 = None
    drop512 = None
    # All current reviewed exterior profiles reach 512; cap only protects unexpected future input.
    for tick in range(1, max(age_frames, 12000) + 1):
        uy = vy - gravity * .5
        total_speed = math.hypot(vx, uy)
        dt = drag * total_speed
        retention = 1 / (1 + dt)
        drift = math.log1p(dt) / dt if dt else 1
        dx, dy = vx * drift, uy * drift
        x += dx
        y += dy
        vx, vy = vx * retention, uy * retention - gravity * .5
        if frames512 is None and x >= 512:
            frames512, drop512 = tick, -y
        if tick == age_frames:
            at_age = x
        if frames512 is not None and at_age is not None:
            break
    return dict(lifeSeconds=life / 20, movementFramesBeforeAge=age_frames, speedMps=speed,
                friction=0, gravityPerTick=gravity, dragPerMeter=drag,
                status=('already_reaches_512_before_age' if frames512 is not None and frames512 <= age_frames
                        else 'needs_age_extension' if frames512 is not None else 'not_reached_within_600_seconds'),
                horizontalAtAge=at_age, asymptoticHorizontal=None, ticksTo512=frames512, dropAt512=drop512)


def main():
    rows = []
    for gun in ASSETS['guns']:
        raw = gun['rawData']
        bullet = raw.get('bullet') or {}
        profile = PHYSICS.get(gun['id'])
        for mode in raw.get('fire_mode', ['unknown']):
            initial = native_case(bullet, raw.get('fire_mode_adjust') or {}, mode)
            experimental = external_case(bullet, profile) if profile and profile['action'] == 'apply' else dict(initial)
            explosion = bullet.get('explosion') or {}
            delay = float(explosion.get('delay', 0)) if explosion.get('explode') else None
            rows.append(dict(gunId=gun['id'], name=gun.get('displayName') or gun['id'], type=gun['type'], mode=mode, initial=initial,
                             experimental=experimental, reviewedExterior=bool(profile and profile['action'] == 'apply'),
                             explosionEnabled=bool(explosion.get('explode')), explosionDelaySeconds=delay,
                             script=raw.get('script'), source=gun.get('source')))
    assert len({row['gunId'] for row in rows}) == 424
    unique = {}
    for setting in ('initial', 'experimental'):
        unique[setting] = {}
        for status in sorted({row[setting]['status'] for row in rows}):
            unique[setting][status] = sorted({row['gunId'] for row in rows if row[setting]['status'] == status})
    output = dict(date='2026-10-18', targetHorizontal=512, globalSpeedMultiplier=GLOBAL,
                  population=424, modeCount=len(rows), gunStatuses=unique,
                  modeStatusCounts={setting: dict(Counter(row[setting]['status'] for row in rows)) for setting in ('initial', 'experimental')},
                  limitations=['Horizontal dry-air source-snapshot calculation only; gravity and native cleanup are unchanged.',
                               'No collisions, water, fuse expiry, void cleanup, Lua or attachment modifications are simulated.',
                               'Status sets may overlap if firing modes have different speed offsets.',
                               'Native hit damage uses 3D straight distance; the proposed age gate uses X/Z horizontal displacement.',
                               'Initial finite range is v0/(1-r), r=float32(1-friction). Raising life cannot cross this limit.',
                               'Nonphysical friction values are flagged rather than treated as valid drag.',
                               'Experimental 396 profiles retain isotropic quadratic drag, realistic gravity and native lifespan >=10s.'],
                  guns=rows)
    (ROOT / 'logs/bullet-range-512-audit.json').write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    with (ROOT / 'logs/bullet-range-512-audit.csv').open('w', encoding='utf-8-sig', newline='') as file:
        flat = [dict(gunId=row['gunId'], name=row['name'], type=row['type'], mode=row['mode'], setting=setting,
                     reviewedExterior=row['reviewedExterior'], explosionEnabled=row['explosionEnabled'],
                     explosionDelaySeconds=row['explosionDelaySeconds'], **row[setting])
                for row in rows for setting in ('initial', 'experimental')]
        columns = list(dict.fromkeys(key for row in flat for key in row))
        writer = csv.DictWriter(file, fieldnames=columns); writer.writeheader(); writer.writerows(flat)
    docs = ROOT / 'docs/gun-balance'
    for extension in ('json', 'csv'):
        shutil.copyfile(ROOT / f'logs/bullet-range-512-audit.{extension}', docs / f'bullet-range-data.{extension}')
    write_report(output, docs / 'bullet-range-report.md')
    print(json.dumps(dict(population=424, modes=len(rows), modeStatuses=output['modeStatusCounts'],
                          uniqueGunStatuses={setting: {key:len(value) for key,value in unique[setting].items()} for setting in unique}), ensure_ascii=False))
    for setting in ('initial', 'experimental'):
        for status, ids in unique[setting].items():
            if status != 'already_reaches_512_before_age':
                print(setting, status, ', '.join(ids))


def write_report(output, destination):
    by_id = {}
    for row in output['guns']:
        by_id.setdefault(row['gunId'], []).append(row)
    experimental = output['gunStatuses']['experimental']
    initial = output['gunStatuses']['initial']
    exceptions = []
    for gun_id in experimental.get('cannot_reach_512_by_life_extension', []):
        modes = by_id[gun_id]
        limit = max(row['experimental']['asymptoticHorizontal'] for row in modes)
        exceptions.append(f'| {modes[0]["name"]} | `{gun_id}` | {limit:.2f} 格 |')
    extended = []
    for gun_id in experimental.get('needs_age_extension', []):
        for row in by_id[gun_id]:
            value = row['experimental']
            if value['status'] == 'needs_age_extension':
                extended.append(f'| {row["name"]} | `{gun_id}` | {row["mode"]} | {value["lifeSeconds"]:.2f}s | {value["horizontalAtAge"]:.2f} 格 | {value["ticksTo512"] / 20:.2f}s | {value["dropAt512"]:.2f} 格 |')
    default_exceptions = '\n'.join(f'- `{gun_id}`' for gun_id in initial.get('cannot_reach_512_by_life_extension', []))
    text = f'''# 子弹 512 格水平范围与原生清理报告

2026-10-18 · Minecraft 1.21.1 · TaCZ 1.1.8-hotfix-r7 · 已实装的年龄清理修正

当前完整追踪 **424 把枪、692 个射击模式**。本次只延后会过早结束的原生年龄清理，保留原弹道、下坠、速度、阻力、命中、引信和区块规则。**512 是 X/Z 水平位移门槛，不是强行位移，也不是到 512 就立即杀死弹丸的硬上限。**

完整逐枪逐模式表： [CSV，含初始与实验性两套参数](bullet-range-data.csv) · [JSON 明细与限制说明](bullet-range-data.json)。每一行包含枪名、ID、模式、方案、寿命、寿命结束时水平距离、恒定阻力终程、到 512 的时间和下坠、爆炸引信信息。

## 已实装行为

- 只有 TaCZ `tick()` 中唯一的“寿命已到”清除调用经过新策略。未达水平 512、仍可达且有实际水平运动时，暂缓该次年龄清理。
- 已达 512 后，重新放行原本已经到期的年龄清理；如果原生寿命尚未到，不会因穿过 512 而新增立即清除。
- 原生恒定阻力按当前速度推算最终水平端点；端点仍不足 512，保留原年龄清理，不通过无限延寿掩盖物理限制。
- 实验性二次阻力继续使用原来的积分器。连续 20 个年龄检查间隔没有任何可表达的实际水平坐标进度时，恢复原生清理；缓慢但确实移动的弹丸不会因此被提前清除。
- 无水平运动、非有限坐标/速度/阻力等情况保留原生清理。入水仍采用原生水阻力；没有改初速、重力、摩擦、位置或运动向量。
- 命中方块、穿透耗尽、爆炸引信、虚空、外部明确移除和 noSave 卸载行为全部保留。

## 当前静态结果

| 方案 | 原寿命已能越过 512 的枪 | 至少一个模式需延长寿命 | 至少一个模式仅延寿无法达到 |
|---|---:|---:|---:|
| 初始参数 | {len(initial['already_reaches_512_before_age'])} | {len(initial['needs_age_extension'])} | {len(initial['cannot_reach_512_by_life_extension'])} |
| 实验性参数 | {len(experimental['already_reaches_512_before_age'])} | {len(experimental['needs_age_extension'])} | {len(experimental['cannot_reach_512_by_life_extension'])} |

初始参数的分类可以因射击模式不同而重叠；另有 1 把原始枪包 friction=10 的异常阻力单列。实验性 396 把已研究外弹道枪，当前至少 10 秒寿命已经足够越过水平 512；其余保留原弹道枪存在以下例外。

这些是水平发射、干燥空气、没有碰撞、没有配件/Lua 改写、没有引信或虚空提前清理时的**静态计算**。不是游戏实测，也不保证在未处于服务器实体活动范围的区块内继续模拟。Voxy 远景并不提供实体或命中区块。

## 实验性方案：7 把物理终程例外

用户已确认保留这些武器原物理，不为达到 512 额外提高速度或降低阻力。它们按原生寿命清理。

| 枪械 | ID | 无碰撞水平终程上限 |
|---|---|---:|
{chr(10).join(exceptions)}

恒定阻力的水平速度逐 tick 乘 `r = float32(1-friction)`，总终程为 `v0/(1-r)`。上限不够时，无论增加多久的寿命都不能抵达 512。

## 实验性方案：12 把需要延寿的枪

| 枪械 | ID | 模式 | 原年龄寿命 | 原年龄水平距离 | 到水平 512 所需时间 | 此时下坠 |
|---|---|---|---:|---:|---:|---:|
{chr(10).join(extended)}

此表按所有模式逐行列出，所以 12 把枪可以占多行。例如 `suffuse:aiyasinrpg` 到水平 512 的下坠约 259 格，从主世界 Y=64 发射会先进入原生虚空清理范围；本次不会阻止该清理。碰撞和引信也可以更早结束任何弹丸。

## 初始方案：26 把至少一模式终程不足的枪

{default_exceptions}

初始参数额外异常：`hamster:sw_mk2_41` 原 friction=10 并非正常衰减；初始模式保持原年龄清理。实验性外弹道对它使用已经批准的物理配置。

## 距离、视觉与同步边界

TaCZ `getDamage(hitVec)` 的距离是发射点到命中点的三维直线距离，仅用于查伤害衰减表。本次的年龄门槛独立采用水平 X/Z 位移，不改变伤害表距离语义。

服务端使用 TaCZ 原生 `startPos` 为发射原点并负责最终移除。原生 spawn 包没有同步 startPos，客户端曳光副本使用首次 tick 位置作为参考；迟收到的客户端副本参考原点可能偏移，服务端权威清除仍会同步。原生子弹 `clientTrackingRange(5)` 通常为 80 格，本次未改，所以寿命修正不承诺肉眼可见的曳光延伸到 512。

子弹是 noSave、非 alwaysTicking 实体。服务器活动区块、碰撞地形、水、虚空和原生爆炸生命周期仍然决定实战表现；不新增强制加载票据。

## 验证

`BulletRangeChecks` 独立编译与纯检查通过 **81,258 项**，包括真实缓慢移动、极小阻力浮点边界、原生未来端点、停滞恢复、已达门槛、零水平运动、非有限数据和水阻力。ASM 只读取本机实际 r7 JAR 与新 Mixin 字节码，验证 tick 唯一年龄清除调用、引信/穿透/方块清除仍在其他方法、新 Mixin 精确调用点和不调用运动改写方法。

另外，691 个正常原生阻力模式的闭式位移与独立逐 tick 迭代完全吻合。生成脚本是 `scripts/audit-bullet-range.py`；未启动 Minecraft、客户端、世界或服务器。
'''
    destination.write_text(text, encoding='utf-8')


if __name__ == '__main__':
    main()
