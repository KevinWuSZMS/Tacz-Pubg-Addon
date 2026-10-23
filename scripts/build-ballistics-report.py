"""Export reviewed runtime profiles and an offline external-ballistics report.
Use bundled Python 3.12 with logs/analysis-python on PYTHONPATH for the existing plot dependencies.
"""
from pathlib import Path
from collections import Counter
import csv
import hashlib
import html
import json
import math
import re
import struct
from datetime import datetime

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/gun-balance"
RESEARCH = OUT / "ballistics-research.json"
PY_RESOURCE = ROOT / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg/realistic_ballistics.json"
G = 9.80665
DISTANCES = (50, 100, 200, 300, 500)
TYPES = {"rifle": "步枪", "smg": "冲锋枪", "pistol": "手枪", "shotgun": "霰弹枪",
         "sniper": "狙击枪", "mg": "机枪", "rpg": "发射器"}


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def simulate(speed, gravity, friction, life, spatial_drag=None):
    """Interpolate the same straight swept chord used by TaCZ for collision in each tick."""
    x = y = 0.0
    vx, vy = speed / 20.0, 0.0
    samples = {}
    points = [{"x": 0, "drop": 0, "time": 0}]
    motion_ticks = native_motion_ticks(life)
    for tick in range(motion_ticks):
        if spatial_drag is None:
            dx, dy = vx, vy
            next_vx, next_vy = vx * (1-friction), vy * (1-friction)-gravity
        else:
            uy = vy - gravity / 2
            s = math.hypot(vx, uy)
            z = spatial_drag * s
            retain = 1 / (1+z)
            drift = math.log1p(z) / z if z else 1.0
            dx, dy = vx * drift, uy * drift
            next_vx, next_vy = vx * retain, uy * retain - gravity / 2
        nx, ny = x+dx, y+dy
        for distance in DISTANCES:
            if distance not in samples and x <= distance <= nx and dx > 0:
                f = (distance-x) / dx
                samples[distance] = {"time": (tick+f)/20, "drop": -(y+f*dy)}
        if tick % 2 == 0 and nx <= 550:
            points.append({"x": nx, "drop": -ny, "time": (tick+1)/20})
        x, y, vx, vy = nx, ny, next_vx, next_vy
        if x > 550:
            break
    return {"samples": {str(d): samples.get(d) for d in DISTANCES},
            "computedThroughX": x, "traceLimitedAt550m": x > 550,
            "motionTicks": motion_ticks, "points": points}


def native_motion_ticks(life):
    """TaCZ casts float seconds*20 to int and discards at the end of tickCount >= life-1."""
    def f32(value):
        return struct.unpack("f", struct.pack("f", value))[0]
    lifetime_ticks = min(2_147_483_647, max(1, int(f32(f32(life)*20))))
    return max(1, lifetime_ticks-1)


def source_global():
    text = (ROOT / "neoforge-1.21.1/run/config/tacz-common.toml").read_text(encoding="utf-8")
    match = re.search(r"^\s*GlobalBulletSpeedModifier\s*=\s*([\d.eE+-]+)", text, re.M)
    if not match:
        raise ValueError("GlobalBulletSpeedModifier not found; do not invent current velocity")
    return float(match.group(1))


def main():
    research, assets, balance = read(RESEARCH), read(OUT / "gun-assets.json"), read(OUT / "balance-data.json")
    assert len(research["guns"]) == len(assets["guns"]) == 424
    assert research["inputSha256"]["gunAssets"] == sha(OUT / "gun-assets.json")
    asset_by_id = {g["id"]: g for g in assets["guns"]}
    balance_by_id = {g["id"]: g for g in balance["guns"]}
    global_speed = source_global()
    rows = []
    data_profiles = {}
    for gun in research["guns"]:
        aid = gun["gunId"]
        asset, old = asset_by_id[aid], balance_by_id[aid]
        raw = asset["rawData"]
        bullet = raw.get("bullet", {})
        mode = old["selectedMode"]
        mode_speed = (raw.get("fire_mode_adjust", {}).get(mode, {}) or {}).get("speed", 0)
        original_speed = global_speed * max(.001, bullet.get("speed", 5)+mode_speed)
        original_gravity, friction = max(0, bullet.get("gravity", 0)), bullet.get("friction", .01)
        life = bullet.get("life", 10)
        current = simulate(original_speed, original_gravity, friction, life)
        apply = gun["action"] == "apply"
        if apply:
            triple = (gun["proposedMuzzleVelocityMps"], gun["gravityMps2"], gun["effectiveDragPerMeter"])
            if gun["dataId"] in data_profiles:
                assert data_profiles[gun["dataId"]] == triple, "Conflicting shared gun data"
            data_profiles[gun["dataId"]] = triple
            # Same chamber/barrel/ammunition does not change launch speed when selecting semi/auto.
            new_speed = gun["proposedMuzzleVelocityMps"]
            new_life = max(10, life)
            adjusted = simulate(new_speed, gun["gravityMps2"]/400, 0, new_life, gun["effectiveDragPerMeter"])
        else:
            new_speed, new_life, adjusted = original_speed, life, current
        icon = gun["icon"]
        assert (OUT / icon).is_file(), icon
        rows.append({**gun, "selectedMode": mode, "selectedModeSpeedAdjustment": mode_speed,
                     "experimentalModeSpeedAdjustment": 0 if apply else mode_speed,
                     "originalEffectiveVelocityMps": original_speed, "experimentalEffectiveVelocityMps": new_speed,
                     "originalLifeSeconds": life, "experimentalLifeSeconds": new_life,
                     "current": current, "experimental": adjusted})
    assert {r["gunId"] for r in rows} == set(asset_by_id)
    # Minimal runtime catalog is mechanically derived, never hand-maintained independently of the report.
    fields = ("gunId", "dataId", "action", "proposedMuzzleVelocityMps", "gravityMps2", "effectiveDragPerMeter")
    runtime = {"schemaVersion": 1, "source": "docs/gun-balance/ballistics-research.json",
               "sourceSha256": sha(RESEARCH), "guns": [{k:g[k] for k in fields if k in g} for g in rows]}
    write_json(PY_RESOURCE, runtime)
    payload = {"schemaVersion": 1, "generatedAt": datetime.now().isoformat(timespec="seconds"),
               "researchSha256": sha(RESEARCH), "globalSpeedSnapshot": global_speed,
               "assumptions": "1 block=1m; 20Hz; horizontal bore line; no optic zeroing; selected first listed fire mode; no attachments, wind or spread; per-tick swept-chord interpolation.",
               "summary": research["summary"], "sources": research["sources"], "guns": rows}
    write_json(OUT / "ballistics-data.json", payload)
    csv_fields = ["gunId", "name", "type", "action", "cartridge", "confidence", "referenceQuality",
                  "selectedMode", "selectedModeSpeedAdjustment", "experimentalModeSpeedAdjustment", "originalEffectiveVelocityMps",
                  "proposedMuzzleVelocityMps", "experimentalEffectiveVelocityMps", "gravityMps2",
                  "effectiveDragPerMeter", "originalLifeSeconds", "experimentalLifeSeconds"]
    for phase in ("current", "experimental"):
        for d in DISTANCES:
            csv_fields += [f"{phase}_drop_{d}m", f"{phase}_flight_{d}s"]
    csv_fields += ["reason", "barrelAssumption", "sourceUrls"]
    sources = {s["id"]: s for s in research["sources"]}
    with (OUT / "ballistics-attributes.csv").open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, csv_fields)
        writer.writeheader()
        for r in rows:
            out = {k:r.get(k, "") for k in csv_fields}
            for phase in ("current", "experimental"):
                for d in DISTANCES:
                    sample = r[phase]["samples"][str(d)]
                    out[f"{phase}_drop_{d}m"] = sample["drop"] if sample else ""
                    out[f"{phase}_flight_{d}s"] = sample["time"] if sample else ""
            out["sourceUrls"] = " | ".join(sources[s]["url"] for s in r["sourceIds"])
            writer.writerow(out)
    plots = plot(rows)
    render_html(payload, research, plots)
    # Independent invariants: horizontal vacuum is exactly parabolic at tick endpoints.
    for speed in (100, 300, 800, 1500):
        result = simulate(speed, G/400, 0, 10, 0)
        for p in result["points"]:
            assert abs(p["drop"]-G*p["time"]**2/2) < 1e-8
    assert native_motion_ticks(.8) == 15 and native_motion_ticks(.3) == 5
    assert native_motion_ticks(.79) == 14 and native_motion_ticks(10) == 199
    mpx = next(r for r in rows if r["gunId"] == "ccrp:mpx")
    widow = next(r for r in rows if r["gunId"] == "cib:widow")
    assert mpx["current"]["samples"]["200"] is None
    assert widow["current"]["samples"]["300"] is None
    validation = {"guns": len(rows), "actions": dict(Counter(r["action"] for r in rows)),
                  "runtimeResearchSha256": sha(RESEARCH), "runtimeFileSha256": sha(PY_RESOURCE),
                  "allIconsExist": True, "allSharedDataConsistent": True, "vacuumTickEndpointChecks": True,
                  "nativeLifetimeBoundaryChecks": True,
                  "plotCount": len(plots), "noGameLaunch": True}
    write_json(OUT / "ballistics-report-validation.json", validation)
    print(json.dumps(validation, ensure_ascii=False))


def plot(rows):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.font_manager import FontProperties
    from matplotlib.offsetbox import AnnotationBbox, OffsetImage
    from PIL import Image
    font = FontProperties(fname="C:/Windows/Fonts/msyh.ttc", size=8)
    plt.rcParams.update({"font.family": font.get_name(), "svg.fonttype": "none", "axes.unicode_minus": False})
    target = OUT / "ballistics-plots"
    target.mkdir(exist_ok=True)
    manifest = []
    for gun_type, label in TYPES.items():
        subset = [r for r in rows if r["type"] == gun_type]
        for metric in ("velocity", "drop100"):
            def value(row, phase):
                if metric == "velocity":
                    return row["originalEffectiveVelocityMps" if phase == "current" else "experimentalEffectiveVelocityMps"]
                sample = row[phase]["samples"]["100"]
                return sample["drop"] if sample else None
            ordered = sorted(subset, key=lambda r: (value(r, "experimental") is None,
                                                   value(r, "experimental") or 0, r["gunId"]))
            height = max(4, len(ordered)*.34+1.5)
            fig, ax = plt.subplots(figsize=(13, height))
            fig.subplots_adjust(left=.45, right=.975, top=1-.6/height, bottom=.6/height)
            ax.invert_yaxis()
            ax.set_ylim(len(ordered)-.5, -.5)
            for i, r in enumerate(ordered):
                before, after = value(r, "current"), value(r, "experimental")
                if before is not None and after is not None:
                    ax.plot([before, after], [i,i], color="#bccbd5", linewidth=1.0, zorder=1)
                if before is not None: ax.scatter([before], [i], s=18, color="#75899a", zorder=2)
                if after is not None: ax.scatter([after], [i], s=20, color="#147a92" if r["action"] == "apply" else "#bd843f", zorder=3)
                if after is None: ax.text(.99, i, "N/A（寿命内未到达）", transform=ax.get_yaxis_transform(), ha="right", fontproperties=font)
                ax.text(-.16, i, r["name"]+"\n"+r["gunId"], transform=ax.get_yaxis_transform(),
                        va="center", ha="right", fontproperties=font, fontsize=7)
                im = Image.open(OUT / r["icon"]).convert("RGBA")
                im.thumbnail((58, 28))
                ax.add_artist(AnnotationBbox(OffsetImage(im, zoom=.65), (-.09, i), xycoords=ax.get_yaxis_transform(), frameon=False))
            ax.set_yticks([])
            ax.grid(axis="x", color="#e1e7eb", linewidth=.7)
            ax.set_axisbelow(True)
            title = "初速" if metric == "velocity" else "100米水平发射下坠"
            ax.set_title(f"{label} · {title} · 按实验值从低到高\n灰：初始  蓝：实验  金：原值保留", fontproperties=font, fontsize=11, pad=13)
            ax.set_xlabel("m/s" if metric == "velocity" else "m（未归零，含每tick直线扫掠插值）", fontproperties=font)
            path = target / f"{gun_type}-{metric}.svg"
            fig.savefig(path, format="svg", facecolor="white")
            if gun_type == "smg" and metric == "velocity":
                fig.savefig(target / "smg-velocity-preview.png", dpi=100, facecolor="white")
            plt.close(fig)
            manifest.append({"type": gun_type, "metric": metric, "path": path.relative_to(OUT).as_posix(),
                             "gunIds": [r["gunId"] for r in ordered]})
    write_json(OUT / "ballistics-plot-manifest.json", manifest)
    return manifest


def render_html(payload, research, plots):
    apply, preserve = research["summary"]["apply"], research["summary"]["preserve"]
    data = json.dumps({**payload, "plots": plots, "types": TYPES}, ensure_ascii=False, allow_nan=False).replace("</", "<\\/")
    sources = "".join(f'<li id="source-{html.escape(s["id"])}"><a href="{html.escape(s["url"], quote=True)}">{html.escape(s["title"])}</a> — {html.escape(s.get("supports", ""))}</li>' for s in research["sources"])
    page = r'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>TaCZ 逐枪初速、下坠与持久后坐力</title>
<style>
:root{color-scheme:light;--ink:#183344;--muted:#587080;--line:#d7e1e7;--blue:#147a92}*{box-sizing:border-box}body{margin:0;background:#edf3f6;color:var(--ink);font:15px/1.7 "Microsoft YaHei",sans-serif}header,section{padding:22px 28px;background:#fff;margin-bottom:12px}h1{font-size:25px;margin:0 0 12px}h2{font-size:19px;margin:0 0 12px}h3{font-size:16px}p{margin:8px 0}a{color:var(--blue)}.muted{color:var(--muted);font-size:13px}.tag{padding:3px 8px;background:#e5f3f6;border-radius:5px;white-space:nowrap;font-size:12px}.keep{background:#fff1dc;color:#80511c}.notice{border-left:4px solid #bd843f;padding:10px 14px;background:#fff6e8;margin:14px 0}code{font-size:13px;background:#edf3f6;padding:2px 5px}pre{white-space:pre-wrap;background:#edf3f6;padding:14px;font-size:13px}.controls{display:flex;gap:12px;flex-wrap:wrap;align-items:center;margin-bottom:12px}label{display:flex;gap:6px;align-items:center;font-size:13px}select,input{padding:8px;border:1px solid #9cafba;border-radius:5px;font:inherit;background:#fff}input{min-width:290px}.table-wrap{max-height:65vh;overflow:auto;border:1px solid var(--line)}table{border-collapse:collapse;width:100%;font-size:13px}th,td{padding:10px;border-bottom:1px solid var(--line);text-align:left;vertical-align:middle}th{position:sticky;top:0;background:#eef5f7;z-index:1;white-space:nowrap}tr[data-id]{cursor:pointer}tr[data-id]:hover{background:#eaf5f8}.gun{display:flex;align-items:center;gap:9px;min-width:240px}.gun img{width:75px;height:42px;object-fit:contain;image-rendering:auto}.gun small{display:block;color:var(--muted)}.num{white-space:nowrap;font-variant-numeric:tabular-nums}#detail{padding:14px;border:1px solid var(--line);margin-top:14px;background:#f5f9fb}#detail table th{position:static}.plot{max-height:72vh;overflow:auto;border:1px solid var(--line);background:white}.plot img{display:block;width:1250px;max-width:none}.refs{font-size:12px;overflow-wrap:anywhere}.refs li{margin-bottom:8px}summary{cursor:pointer;font-weight:bold}.grid{display:grid;grid-template-columns:1fr 1fr;gap:18px}.grid>div{padding:14px;background:#f2f7fa}footer{padding:18px 28px;color:var(--muted);font-size:12px}@media(max-width:850px){header,section{padding:17px}.grid{grid-template-columns:1fr}}
</style></head><body><header><h1>逐枪初速、下坠与持久后坐力</h1>
<p>全部 <b>424</b> 把枪已逐项登记：<b>__APPLY__</b> 把采用现实弹种参考或明确标注的估计，<b>__PRESERVE__</b> 把特殊／待核型号保留原弹道。点击任意枪械行查看证据、推断和各距离结果。</p>
<p><span class="tag">2026-10-18</span> <span class="tag">TaCZ 1.1.8-hotfix-r7</span> <span class="tag">仅平衡调整 → 实验性调整启用</span></p>
<p class="muted">1 格按 1 米、20 tick/s；初始列读取枪包与当前公共弹速系数 __GLOBAL__。未启动 Minecraft；表格是静态参数与离线轨迹计算。</p>
<p><a href="ballistics-attributes.csv">复制／下载全部 CSV</a> · <a href="ballistics-data.json">轨迹与逐枪数据</a> · <a href="ballistics-research.json">完整研究及证据</a> · <a href="report.html">旧难度模型与属性图</a></p></header>
<section><h2>怎样启用，以及这次改变什么</h2><p><code>/pubgdebug config → 枪械设置 → 平衡调整 → 实验性调整 → 保存全部</code>，等待服务器“已生效并同步客户端”的通知。所有玩家接收同一方案，普通玩家也接收；管理与保存仍需 OP 权限。选择“初始”恢复枪包原值与 TaCZ 原回正行为。</p>
<div class="grid"><div><h3>每发后坐力不再自动撤回</h3><p>TaCZ 原本会把实际随机样条的增量加到玩家朝向；样条归零时又撤回。现在保留这一发实际最高上抬和带正负方向的水平绝对峰值，含关键帧之间的样条超调。用短暂平滑上冲到峰值，随后保持；连射各发相加。</p><p>只累计新位移，不保存并强拉回某个“初始视角”，因此主动压枪始终有效。客户端最终取消的击发、空仓和服务器回声不重复累加；切枪、死亡、换维度、切回初始会清除未完成冲量，已产生的朝向不回拉。</p></div><div><h3>弹道按发射时方案锁定</h3><p>初速填写 m/s，重力采用 9.80665 m/s²。实验弹丸使用按距离拟合的空气阻力，保留现有逐段方块／实体碰撞检查。公共弹速乘数在实验发射路径抵消一次，不修改用户的 TaCZ 配置。</p><p>每颗弹丸携带发射时方案，切换配置不改变已在空中的弹丸。现实弹道的寿命至少 10 秒，原来更长的寿命保留；水中保留 TaCZ 的水阻力。实验条目的单发／连发模式弹速增量归零，附件／脚本弹速倍率仍生效；后续已批准角色平衡另规范386把枪的模式伤害、头倍率、散布与射速。</p></div></div>
<div class="notice">旧报告“类内综合难度排名不变、标准差减半”只针对旧回正与旧弹道模型。这次增加持久后坐力和新弹道后，旧证明不能直接沿用。后坐力控制缩放仍保留；386把角色枪已由批准目标覆盖旧伤害与散布。子弹固定供弹映射另有独立开关，未改变本报告的逐枪现实初速/阻力。详见<a href="../弹药合并与枪类平衡实装.md">本次实装说明</a>与<a href="../compatibility/ammo-performance-proposal.html">角色对照表</a>；需要体验数据重新标定综合难度。</div></section>
<section><h2>逐枪对照</h2><p class="muted">速度列是所选模式的无配件初速。下坠相对水平枪膛线计算，未做 100 米归零；寿命按 r7 的 float→int 与提前一 tick 清除规则计算；N/A 表示寿命内未到达，不能当作零下坠。金色行保留原弹道。</p>
<div class="controls"><label>类别<select id="type"><option value="">全部</option></select></label><label>处理<select id="action"><option value="">全部</option><option value="apply">应用新弹道</option><option value="preserve">原值保留</option></select></label><input id="search" placeholder="枪名、ID、弹种或原因" type="search"><label>排序<select id="sort"><option value="name">名称</option><option value="speed">实验初速从低到高</option><option value="drop100">实验100米下坠从低到高</option><option value="drop300">实验300米下坠从低到高</option></select></label></div>
<p id="count" class="muted"></p><div class="table-wrap"><table><thead><tr><th>枪械／ID</th><th>现实弹种参考／处理</th><th>初始初速<br>m/s</th><th>实验初速<br>m/s</th><th>100m 下坠<br>初始 → 实验 · m</th><th>300m 下坠<br>初始 → 实验 · m</th><th>100m 飞行<br>初始 → 实验 · s</th></tr></thead><tbody id="rows"></tbody></table></div><div id="detail">点击一把枪查看完整信息。</div></section>
<section><h2>带图标的排序点图</h2><div class="controls"><label>类别<select id="plotType"></select></label><label>指标<select id="plotMetric"><option value="velocity">初速</option><option value="drop100">100米下坠</option></select></label><a id="plotLink">单独打开 SVG</a></div><p class="muted">每类枪按实验值从低到高排列；灰点为初始，蓝点为实验，金点为原值保留。未到达者不画相应点；实验值 N/A 列在末尾。</p><div class="plot"><img id="plot" alt="逐枪初速或下坠排序图"></div></section>
<section><h2>现实资料怎样变成游戏参数</h2><p>同一弹种选取一款公开工厂装药作为代表，并尽可能按枪型或明确枪管长度调整。它不是每把枪的实测速，也不是把“PUBG 7.62mm”视为一个真实完整弹种。5.56mm 的部分枪管修正借用 Hornady 同一种弹药的多枪管测速比例，逐枪标明推断；没有公开精确射表的项目明确标为游戏估计。</p>
<p>若有同款弹药的出膛与 100 米速度，用 <code>k = ln(V0 / V100) / 100</code> 拟合短程有效阻力。若缺资料，表中标出所借代理；霰弹球丸与独头弹分开。这个 k 不是 G1/G7 弹道系数，不表示已经拟合完整跨音速阻力曲线。参考：<a href="https://www1.grc.nasa.gov/beginners-guide-to-aeronautics/drag-equation/">NASA 阻力方程</a>、<a href="https://physics.nist.gov/cgi-bin/cuu/Value?gn">NIST 标准重力</a>。</p>
<p>所有现实普通弹丸使用同一重力加速度。质量不同不会让自由落体重力加速度不同；下坠差异来自飞行时间、初速、阻力和归零条件。没有添加风、天气、旋偏、科里奥利和逐枪真实归零；能量、火箭、信号及待核型号按各行原因保留。</p>
<details><summary>可复现的积分、碰撞与后坐力算法</summary><pre>单位：位置 m；速度 m/tick；g = 9.80665 / 400；k 单位 1/m；h = 1 tick。
u = v + (0, -g*h/2, 0)
z = k * |u| * h
本tick位移 D = u*h*log(1+z)/z；z=0 时取 u*h
下一tick真实速度 v' = u/(1+z) + (0, -g*h/2, 0)

先用 D 执行 TaCZ 原有整段碰撞射线，再保存真实 v'；不把 D 误当后续速度。
这对应 dv/dt = -k*|v|*v + g 的分裂积分。
无阻力时 tick 端点严格为抛物线；无重力时严格满足 V(x)=V0*exp(-kx)。
报告命中距离在本tick扫掠直线内插值；并非假装20Hz折线就是连续曲线。
原生寿命：lifeTicks = max(1, int(float32(float32(life秒)*20)))；
最多运动 max(1, lifeTicks-1) tick，按原生末尾清除时机判断是否到达。

每发后坐力：对实际随机三次样条的每段端点和导数根求值。
pitch 取最大向上峰；yaw 取绝对值最大峰并保留其正负方向。
每轴 smoothstep(0→1) 到各自峰值时刻，后续保持。
本帧只施加所有未完成冲量的增量；不覆盖玩家已有朝向或鼠标输入。
结算上一发剩余位移 → 同连接发送朝向 → 发送下一发射击包。
客户端最终被取消的 GunFireEvent 不增加冲量。
沿用 TaCZ 本地预测；服务器独立拒发不等于已收到逐发确认，本轮未新增拒发回滚。</pre></details>
<p class="muted">新增开关确认依照 <a href="https://docs.neoforged.net/docs/1.21.1/networking/payload/">NeoForge 1.21.1 payload 文档</a>注册、编码并在客户端主线程处理；成功应用后才确认，断线清空，旧连接延迟包不会影响新连接。源枪包文件、TaCZ 公共配置和已有存档设置不被报告脚本改写。</p></section>
<section><details><summary>逐条来源与可审计边界</summary><p>下列均为工厂、厂商、标准机构或官方物理资料。具体枪行列出采用哪些来源与推断。公开资料检索于 2026-10-18。</p><ol class="refs">__SOURCES__</ol></details></section>
<footer>构建与离线数学／协议检查不等于实机体验验证；未启动客户端或服务器。CSV 空值表示 N/A。报告图标来自当前本地枪包。</footer>
<script id="data" type="application/json">__DATA__</script><script>
const D=JSON.parse(document.getElementById('data').textContent), $=id=>document.getElementById(id), S=Object.fromEntries(D.sources.map(s=>[s.id,s]));
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const n=(v,d=3)=>v==null?'N/A':Number(v).toLocaleString('en-US',{maximumFractionDigits:d,minimumFractionDigits:0});
const v=(r,p,d,k)=>r[p].samples[String(d)]?.[k]??null;
for(const [key,label] of Object.entries(D.types)){for(const id of ['type','plotType']){let o=document.createElement('option');o.value=key;o.textContent=label;$(id).append(o)}}
function render(){let list=D.guns.filter(r=>(!$('type').value||r.type===$('type').value)&&(!$('action').value||r.action===$('action').value)&&JSON.stringify([r.gunId,r.name,r.cartridge,r.reason]).toLowerCase().includes($('search').value.toLowerCase()));
const key=$('sort').value;list.sort((a,b)=>{if(key==='name')return a.name.localeCompare(b.name);let av=key==='speed'?a.experimentalEffectiveVelocityMps:v(a,'experimental',key==='drop100'?100:300,'drop'),bv=key==='speed'?b.experimentalEffectiveVelocityMps:v(b,'experimental',key==='drop100'?100:300,'drop');return (av??Infinity)-(bv??Infinity)||a.gunId.localeCompare(b.gunId)});
$('count').textContent=`显示 ${list.length} / ${D.guns.length} 把枪。点击行查看资料。`;
$('rows').innerHTML=list.map(r=>`<tr data-id="${esc(r.gunId)}"><td><div class="gun"><img src="${esc(r.icon)}" alt=""><div>${esc(r.name)}<small>${esc(r.gunId)}</small></div></div></td><td>${esc(r.cartridge||'特殊／待核')}<br><span class="tag ${r.action==='preserve'?'keep':''}">${r.action==='apply'?'新弹道':'原值保留'} · 置信${esc(r.confidence)}</span></td><td class="num">${n(r.originalEffectiveVelocityMps,1)}</td><td class="num">${n(r.experimentalEffectiveVelocityMps,1)}</td><td class="num">${n(v(r,'current',100,'drop'))} → ${n(v(r,'experimental',100,'drop'))}</td><td class="num">${n(v(r,'current',300,'drop'))} → ${n(v(r,'experimental',300,'drop'))}</td><td class="num">${n(v(r,'current',100,'time'))} → ${n(v(r,'experimental',100,'time'))}</td></tr>`).join('');}
function detail(id){const r=D.guns.find(r=>r.gunId===id);if(!r)return;const sources=r.sourceIds.map(id=>S[id]).filter(Boolean);$('detail').innerHTML=`<div class="gun"><img src="${esc(r.icon)}" alt=""><h3>${esc(r.name)} · ${esc(r.gunId)}</h3></div><p>数据 ${esc(r.dataId)}；当前弹药 ${esc(r.currentAmmoId)}；参考 ${esc(r.cartridge||'保留原值')}。</p><p>${esc(r.reason)}</p><p>枪管假设：${esc(r.barrelAssumption)}。资料等级：${esc(r.referenceQuality)}；置信：${esc(r.confidence)}。</p><p>模式 ${esc(r.selectedMode)}；模式弹速增量 ${n(r.selectedModeSpeedAdjustment)} → ${n(r.experimentalModeSpeedAdjustment)} m/s；实验基础初速 ${n(r.proposedMuzzleVelocityMps,1)} m/s；有效阻力 k=${n(r.effectiveDragPerMeter,7)} /m；重力 ${n(r.gravityMps2,5)} m/s²；寿命 ${n(r.originalLifeSeconds)} → ${n(r.experimentalLifeSeconds)} 秒；对应可运动 ${r.current.motionTicks} → ${r.experimental.motionTicks} tick。</p><table><thead><tr><th>距离</th><th>初始下坠 m</th><th>实验下坠 m</th><th>初始飞行 s</th><th>实验飞行 s</th></tr></thead><tbody>${[50,100,200,300,500].map(d=>`<tr><td>${d} m</td><td>${n(v(r,'current',d,'drop'))}</td><td>${n(v(r,'experimental',d,'drop'))}</td><td>${n(v(r,'current',d,'time'))}</td><td>${n(v(r,'experimental',d,'time'))}</td></tr>`).join('')}</tbody></table><p class="refs">证据：${sources.map(s=>`<a href="${esc(s.url)}">${esc(s.title)}</a>`).join(' · ')}</p><p class="muted">${esc(r.firearmInference)}<br>本地原始来源：${esc(r.sourceData)}</p>`;}
function renderPlot(){const p=D.plots.find(p=>p.type===$('plotType').value&&p.metric===$('plotMetric').value);$('plot').src=p.path;$('plotLink').href=p.path;}
for(const id of ['type','action','search','sort'])$(id).addEventListener(id==='search'?'input':'change',render);$('rows').addEventListener('click',e=>{let row=e.target.closest('tr[data-id]');if(row)detail(row.dataset.id)});for(const id of ['plotType','plotMetric'])$(id).addEventListener('change',renderPlot);render();renderPlot();
</script></body></html>'''
    for key, value in {"__APPLY__": str(apply), "__PRESERVE__": str(preserve), "__GLOBAL__": str(payload["globalSpeedSnapshot"]),
                       "__SOURCES__": sources, "__DATA__": data}.items():
        page = page.replace(key, value)
    (OUT / "ballistics-report.html").write_text(page, encoding="utf-8")


if __name__ == "__main__":
    main()
