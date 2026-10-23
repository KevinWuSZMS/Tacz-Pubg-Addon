"""Render existing gun-balance analysis to scientific SVG and a local viewer.

Only writes plots/, report.html and plot-manifest.json under the analysis
directory. Never changes gunpacks, configuration, models or balance-data.json.
SVG icons remain external resource references rather than repeated base64.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import unicodedata
import xml.etree.ElementTree as ET

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.offsetbox import AnnotationBbox, OffsetImage
from matplotlib.ticker import MaxNLocator
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SVG_NS = "http://www.w3.org/2000/svg"
XLINK_NS = "http://www.w3.org/1999/xlink"
ET.register_namespace("", SVG_NS)
ET.register_namespace("xlink", XLINK_NS)
PHASES = ("current", "adjusted")
PHASE_NAMES = {"current": "当前值", "adjusted": "候选调整值"}
COLORS = {"current": "#356B9B", "adjusted": "#C47724"}
TYPE_NAMES = {"rifle": "步枪", "pistol": "手枪", "sniper": "狙击枪",
              "smg": "冲锋枪", "mg": "机枪", "shotgun": "霰弹枪", "rpg": "发射器"}
TYPE_ORDER = ["rifle", "smg", "pistol", "shotgun", "sniper", "mg", "rpg"]


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def number(value):
    if not finite(value):
        return "N/A"
    if abs(value) >= 1e6 or (0 < abs(value) < .001):
        return f"{value:.3g}"
    if value == int(value):
        return str(int(value))
    return f"{value:.4f}".rstrip("0").rstrip(".")


def slug(value):
    name = re.sub(r"[^A-Za-z0-9_.-]+", "_", str(value)).strip("._") or "unnamed"
    return name if name == value else name + "_" + hashlib.sha256(str(value).encode()).hexdigest()[:8]


def choose_font():
    # The installed Noto variable font exposes its thin master to FreeType;
    # Microsoft YaHei's regular face gives these small row labels better weight.
    for path in ("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/NotoSansSC-VF.ttf",
                 "C:/Windows/Fonts/simhei.ttf"):
        if Path(path).is_file():
            font_manager.fontManager.addfont(path)
            prop = font_manager.FontProperties(fname=path)
            plt.rcParams["font.family"] = prop.get_name()
            plt.rcParams["axes.unicode_minus"] = False
            plt.rcParams["svg.fonttype"] = "none"
            return prop, prop.get_name(), path
    raise FileNotFoundError("No requested Chinese font was found")


def sort_guns(guns, metric_id, phase):
    valid = sorted((g for g in guns if finite(g.get(phase, {}).get(metric_id))),
                   key=lambda g: (g[phase][metric_id], g["id"]))
    missing = sorted((g for g in guns if not finite(g.get(phase, {}).get(metric_id))),
                     key=lambda g: g["id"])
    return valid + missing, len(valid)


def domain(guns, metric_id):
    values = [g.get(phase, {}).get(metric_id) for g in guns for phase in PHASES
              if finite(g.get(phase, {}).get(metric_id))]
    if not values:
        return None
    lo, hi = min(values), max(values)
    span = hi - lo
    margin = span * .07 if span > 0 else max(abs(lo) * .07, .5)
    return [lo - margin, hi + margin]


def display_name(gun):
    return str(gun.get("name") or gun.get("name_zh") or gun["id"])


def icon_file(gun, analysis_dir):
    value = gun.get("iconPath")
    if not value:
        return None
    value = Path(value)
    if value.is_absolute():
        path = value
    elif (analysis_dir / value).is_file():
        path = analysis_dir / value
    elif (ROOT / value).is_file():
        path = ROOT / value
    else:
        return None
    return path.resolve()


def text_width_units(value):
    # Used only to fit a full label; characters are never omitted/truncated.
    return sum(1.0 if unicodedata.east_asian_width(c) in "WF" else .56 for c in value)


def add_svg_text(parent, x, y, value, size, fill, max_width=None, **attrs):
    attributes = {"x": f"{x:.3f}", "y": f"{y:.3f}", "font-size": f"{size:.3f}",
                  "fill": fill, **attrs}
    if max_width and text_width_units(value) * size > max_width:
        attributes["textLength"] = f"{max_width:.3f}"
        attributes["lengthAdjust"] = "spacingAndGlyphs"
    node = ET.SubElement(parent, f"{{{SVG_NS}}}text", attributes)
    node.text = value
    title = ET.SubElement(node, f"{{{SVG_NS}}}title")
    title.text = value
    return node


def render_svg(path, guns, category, metric, phase, axis_domain, font_family,
               font_prop, analysis_dir):
    ordered, valid_count = sort_guns(guns, metric["id"], phase)
    count = len(ordered)
    width, row_height = 1100, 24
    head, foot = 94, 70
    height = head + foot + row_height * count
    fig, ax = plt.subplots(figsize=(width / 96, height / 96), dpi=96)
    fig.patch.set_facecolor("#FFFFFF")
    ax.set_position([.555, foot / height, .365, row_height * count / height])
    ax.set_ylim(count - .5, -.5)
    ax.set_xlim(axis_domain)
    ax.set_yticks([])
    ax.xaxis.set_major_locator(MaxNLocator(nbins=6))
    ax.tick_params(axis="x", labelsize=9, colors="#45515C", length=3)
    ax.set_xlabel(metric["label"] + (" (" + metric["unit"] + ")" if metric.get("unit") else ""),
                  fontproperties=font_prop, fontsize=10, labelpad=11)
    ax.grid(axis="x", color="#DDE3E9", linewidth=.55, zorder=0)
    for spine in ("left", "top", "right"):
        ax.spines[spine].set_visible(False)
    ax.spines["bottom"].set_color("#A6B3BF")
    for row in range(count):
        if row % 2 == 0:
            ax.axhspan(row - .5, row + .5, facecolor="#F6F8FA", zorder=-1, edgecolor="none")
    if valid_count:
        ax.scatter([g[phase][metric["id"]] for g in ordered[:valid_count]],
                   np.arange(valid_count), s=22, color=COLORS[phase], linewidths=0, zorder=3)
    if valid_count < count:
        ax.axhline(valid_count - .5, color="#B6BEC7", linewidth=.7, linestyle="--", zorder=2)
    fig.text(.017, 1 - 27 / height,
             f"{TYPE_NAMES.get(category, category)} [{category}] · {metric['label']} · {PHASE_NAMES[phase]}",
             fontproperties=font_prop, fontsize=15, color="#1D2B38", va="center")
    fig.text(.017, 1 - 53 / height,
             f"低 → 高排序；{valid_count} 个有效值 / {count - valid_count} 个 N/A；当前与候选共用刻度。候选方案尚未部署。",
             fontproperties=font_prop, fontsize=9, color="#536273", va="center")
    fig.text(.017, 1 - 78 / height, "枪包原图标 · 完整名称 / 物品 ID",
             fontproperties=font_prop, fontsize=9, color="#536273", va="center")
    fig.text(.937, 1 - 78 / height, "数值", fontproperties=font_prop, fontsize=9,
             color="#536273", va="center")
    fig.canvas.draw()
    row_y = [ax.transData.transform((axis_domain[0], i))[1] for i in range(count)]
    fig.savefig(path, format="svg", metadata={"Title": f"{category}/{metric['id']}/{phase}",
                                             "Description": metric.get("description", "")})
    plt.close(fig)
    tree = ET.parse(path)
    root = tree.getroot()
    svg_width, svg_height = [float(v) for v in root.get("viewBox").split()[2:]]
    scale = svg_width / width
    labels = ET.SubElement(root, f"{{{SVG_NS}}}g", {
        "id": "gun-labels", "font-family": font_family + ", Microsoft YaHei, sans-serif"})
    for row, gun in enumerate(ordered):
        y = svg_height - row_y[row] * scale
        icon = icon_file(gun, analysis_dir)
        if icon:
            ref = os.path.relpath(icon, path.parent).replace("\\", "/")
            ET.SubElement(labels, f"{{{SVG_NS}}}image", {
                "x": f"{18 * scale:.3f}", "y": f"{y - 11 * scale:.3f}",
                "width": f"{23 * scale:.3f}", "height": f"{23 * scale:.3f}",
                "preserveAspectRatio": "xMidYMid meet", f"{{{XLINK_NS}}}href": ref,
                "href": ref})
        add_svg_text(labels, 49 * scale, y - 1 * scale, display_name(gun), 11 * scale,
                     "#1D2935", max_width=535 * scale)
        add_svg_text(labels, 49 * scale, y + 9 * scale, gun["id"], 8.5 * scale,
                     "#697684", max_width=535 * scale)
        add_svg_text(labels, 1030 * scale, y + 3 * scale,
                     number(gun.get(phase, {}).get(metric["id"])), 10 * scale,
                     COLORS[phase] if row < valid_count else "#737D88")
    tree.write(path, encoding="utf-8", xml_declaration=True)
    return {"category": category, "metric": metric["id"], "phase": phase,
            "path": path.relative_to(analysis_dir).as_posix(),
            "validCount": valid_count, "missingCount": count - valid_count,
            "rowCount": count, "width": width, "height": height,
            "axisDomain": axis_domain, "orderIDs": [g["id"] for g in ordered]}


def difficulty_metric(metrics):
    return next((m for m in metrics if m["id"] == "composite_difficulty"),
                next((m for m in metrics if "difficulty" in m["id"]), metrics[0]))


def render_summary(path, guns, types, metric, font_prop):
    fig, ax = plt.subplots(figsize=(12, 5.6), dpi=150)
    xs, width = np.arange(len(types)), .35
    summary = []
    for category in types:
        row = {"category": category}
        for phase in PHASES:
            values = [g.get(phase, {}).get(metric["id"]) for g in guns if g["type"] == category
                      and finite(g.get(phase, {}).get(metric["id"]))]
            row[phase] = {"validCount": len(values),
                          "populationSD": statistics.pstdev(values) if values else None}
        summary.append(row)
    for phase, offset in (("current", -.5), ("adjusted", .5)):
        values = [row[phase]["populationSD"] for row in summary]
        bars = ax.bar(xs + offset * width, [v if v is not None else 0 for v in values],
                      width, color=COLORS[phase], label=PHASE_NAMES[phase])
        for bar, value in zip(bars, values):
            if value is None:
                bar.set_facecolor("none")
            ax.annotate("N/A" if value is None else f"{value:.3f}",
                        (bar.get_x() + bar.get_width() / 2, bar.get_height()),
                        xytext=(0, 5), textcoords="offset points", ha="center", fontsize=9,
                        fontproperties=font_prop)
    ax.set_xticks(xs, [TYPE_NAMES.get(t, t) + "\n" + t for t in types], fontproperties=font_prop)
    ax.set_ylabel("类内总体标准差 SD", fontproperties=font_prop, fontsize=11)
    ax.set_title(f"{metric['label']}：当前与候选的类内离散程度", fontproperties=font_prop, fontsize=15, pad=18)
    ax.set_axisbelow(True)
    ax.grid(axis="y", color="#DEE4EA", linewidth=.7)
    ax.legend(prop=font_prop, loc="upper right", frameon=False)
    for edge in ("top", "right"):
        ax.spines[edge].set_visible(False)
    ax.margins(y=.22)
    fig.text(.075, .035, "候选方案尚未部署。SD 基于有效分值、总体定义（ddof=0）；降低离散度不等于实战平衡已验证。",
             fontproperties=font_prop, fontsize=9, color="#536273")
    fig.subplots_adjust(left=.075, right=.975, bottom=.17, top=.84)
    fig.savefig(path, facecolor="white")
    plt.close(fig)
    return summary


def render_preview(path, guns, metric, axis_domain, font_prop, analysis_dir):
    count = min(16, len(guns))
    height = 1.6 + .32 * count
    fig, axes = plt.subplots(1, 2, figsize=(16, height), dpi=125)
    for ax, phase in zip(axes, PHASES):
        ordered, valid_count = sort_guns(guns, metric["id"], phase)
        ordered = ordered[:count]
        ax.set_xlim(axis_domain)
        ax.set_ylim(count - .5, -.5)
        ax.set_yticks([])
        ax.xaxis.set_major_locator(MaxNLocator(nbins=4))
        ax.grid(axis="x", color="#DDE3E9", linewidth=.7)
        ax.set_axisbelow(True)
        for spine in ("top", "left", "right"):
            ax.spines[spine].set_visible(False)
        ax.set_title(PHASE_NAMES[phase] + " · 升序前 " + str(count) + " 项",
                     fontproperties=font_prop, fontsize=12, pad=18)
        ax.set_xlabel(metric["label"] + (" (" + metric["unit"] + ")" if metric.get("unit") else ""),
                      fontproperties=font_prop, fontsize=10)
        for row, gun in enumerate(ordered):
            value = gun.get(phase, {}).get(metric["id"])
            if finite(value):
                ax.scatter(value, row, s=25, color=COLORS[phase], zorder=3)
            ax.text(-1.13, row - .05, display_name(gun), transform=ax.get_yaxis_transform(),
                    fontproperties=font_prop, fontsize=7.7, va="bottom", ha="left", clip_on=False)
            ax.text(-1.13, row + .25, gun["id"], transform=ax.get_yaxis_transform(),
                    fontproperties=font_prop, fontsize=6.8, color="#697684", va="bottom", ha="left", clip_on=False)
            ax.text(1.03, row, number(value), transform=ax.get_yaxis_transform(),
                    fontproperties=font_prop, fontsize=8, va="center", clip_on=False)
            icon = icon_file(gun, analysis_dir)
            if icon:
                with Image.open(icon) as source:
                    rgba = np.asarray(source.convert("RGBA"))
                zoom = 21 / max(rgba.shape[:2])
                box = AnnotationBbox(OffsetImage(rgba, zoom=zoom), (-1.25, row),
                                     xycoords=ax.get_yaxis_transform(), frameon=False,
                                     pad=0, annotation_clip=False)
                ax.add_artist(box)
    fig.suptitle("SMG 图标与姓名预览 · " + metric["label"] + " · 候选方案尚未部署",
                 fontproperties=font_prop, fontsize=15, y=.97)
    # Wide left gutters belong to the row labels. Both data panels retain the
    # same numeric domain even though their own ascending orders are independent.
    axes[0].set_position([.245, .12, .18, .75])
    axes[1].set_position([.745, .12, .18, .75])
    fig.text(.025, .028, "仅展示每栏升序前 16 项；完整 424 把及所有指标见 HTML。图标为枪包原图，N/A 不作零值。",
             fontproperties=font_prop, fontsize=9, color="#536273")
    fig.savefig(path, facecolor="white")
    plt.close(fig)


def write_html(path, manifest, guns):
    payload = {"types": manifest["types"], "metrics": manifest["metrics"],
               "plots": [{key: p[key] for key in ("category", "metric", "phase", "path", "width", "height", "validCount", "missingCount")}
                         for p in manifest["plots"]],
               "defaultMetric": manifest["summaryMetric"],
               "renderedAt": manifest["generatedAt"], "dataSha256": manifest["dataSha256"],
               "guns": [{"id": g["id"], "name": display_name(g), "type": g["type"]} for g in guns]}
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c")
    body = r'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>TaCZ 枪械参数排序图 · 候选方案</title>
<style>
:root{color-scheme:light;--ink:#183042;--muted:#526675;--line:#d8e2e8;--blue:#356b9b;--orange:#c47724}
*{box-sizing:border-box}body{margin:0;font-family:"Noto Sans SC","Microsoft YaHei",sans-serif;color:var(--ink);background:#eef3f6}
header{padding:26px 28px 18px;background:#fff;border-bottom:1px solid var(--line)}h1{font-size:25px;margin:0 0 10px}
p{margin:7px 0;line-height:1.6}.badge{display:inline-block;background:#fff0d9;color:#845013;border:1px solid #e1c18b;border-radius:6px;padding:3px 9px;font-size:13px;vertical-align:middle}
.muted{color:var(--muted);font-size:13px}a{color:var(--blue)}.controls{display:flex;flex-wrap:wrap;gap:12px;padding:16px 28px;background:#fff;position:sticky;top:0;z-index:3;border-bottom:1px solid var(--line)}
label{display:flex;flex-direction:column;gap:5px;font-size:12px;color:var(--muted)}select,input{font:inherit;font-size:14px;color:var(--ink);background:white;border:1px solid #aabcc9;border-radius:6px;padding:9px;min-width:155px}
#search{width:280px}#metric{min-width:260px;max-width:420px}.info{padding:12px 28px;color:var(--muted);font-size:13px;min-height:44px}
.panels{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;padding:0 20px 20px}.panels.single{grid-template-columns:minmax(0,1fr)}
.panel{background:#fff;border:1px solid var(--line);border-radius:8px;min-width:0;overflow:hidden}.panel h2{margin:0;padding:12px 16px;font-size:16px;border-bottom:1px solid var(--line)}
.panel h2 a{font-size:12px;float:right}.viewport{height:74vh;overflow:auto;background:#fff}.viewport object{display:block;max-width:none;border:0;background:#fff}
.empty{padding:30px;color:#6b7681}.overview{margin:4px 20px 24px;background:#fff;border:1px solid var(--line);padding:18px;border-radius:8px}.overview summary{cursor:pointer;font-weight:600}.overview img{width:min(100%,1200px);height:auto;display:block;margin:14px auto 0}
footer{padding:0 28px 24px;font-size:12px;color:var(--muted)}@media(max-width:950px){.panels{grid-template-columns:1fr}.controls{position:static}.viewport{height:65vh}header{padding:20px}.controls{padding:14px 20px}}
</style></head><body>
<header><h1>TaCZ 枪械参数排序图 <span class="badge">未部署的候选方案</span></h1>
<p>每张图按该参数从低到高排列；当前与候选分别排序并共用刻度。每行显示枪包图标、完整名称和 ID。</p>
<p class="muted">缺失值列在底部标为 N/A；搜索与选择仅切换现有整图，不改变图内顺序或移除枪械。类别沿用枪包的 index.type。</p>
<p class="muted">方案保留的是综合模型分值顺序；纯机械负担（mechanical_burden）并非保序目标。零伤害的 cib:error 不参与综合难度计算，在该指标中显示 N/A。</p>
<p class="muted"><a href="balance-data.json">分析数据</a> · <a href="plot-manifest.json">图表清单与排序 ID</a> · <a href="属性字段静态审查.json">原始字段审查</a></p></header>
<div class="controls"><label>分类<select id="type"></select></label><label>指标（按组分类）<select id="metric"></select></label>
<label>展示<select id="mode"><option value="compare">当前 / 候选双栏比较</option><option value="single">单栏</option></select></label>
<label>单栏阶段<select id="phase"><option value="current">当前值</option><option value="adjusted">候选调整值</option></select></label>
<label>搜索指标、分类、枪名或 ID<input id="search" type="search" placeholder="例如：后坐力 / smg / UMP"></label></div>
<div id="info" class="info"></div><main id="panels" class="panels"></main>
<details class="overview"><summary>七类综合难度标准差概览</summary><p class="muted">总体标准差（ddof=0），只统计有效分值；分值离散度不能替代实战检验。</p><img src="plots/summary.png" alt="七类当前与候选综合难度标准差"></details>
<footer>离线静态分析；未启动游戏验证，也未修改任何运行中的枪包或配置。SVG 通过 object 读取本地相对图标，保留独立打开与下载能力。</footer>
<script type="application/json" id="data">__PAYLOAD__</script><script>
const data=JSON.parse(document.querySelector('#data').textContent),$=s=>document.querySelector(s);
const stamp=document.createElement('p');stamp.className='muted';stamp.textContent='图表快照：'+data.renderedAt+' · 数据 SHA-256：'+data.dataSha256.slice(0,16)+'…';stamp.title=data.dataSha256;document.querySelector('header').append(stamp);
const type=$('#type'),metric=$('#metric'),mode=$('#mode'),phase=$('#phase'),search=$('#search');
const phaseName={current:'当前值',adjusted:'候选调整值'};
for(const t of data.types){const o=document.createElement('option');o.value=t.id;o.textContent=t.label+' ('+t.id+') · '+t.count;type.append(o)}
type.value=data.types.some(t=>t.id==='smg')?'smg':data.types[0].id;
function candidates(){const q=search.value.trim().toLocaleLowerCase(),t=data.types.find(v=>v.id===type.value);
 const gunMatch=data.guns.filter(g=>g.type===type.value&&(g.id+' '+g.name).toLocaleLowerCase().includes(q));
 const generalMatch=!q||(t.label+' '+t.id).toLocaleLowerCase().includes(q)||gunMatch.length>0;
 return {metrics:data.metrics.filter(m=>data.plots.some(p=>p.category===type.value&&p.metric===m.id)&&(generalMatch||(m.id+' '+m.label+' '+(m.description||'')+' '+(m.group||'')).toLocaleLowerCase().includes(q))),gunMatch,q};}
function fillMetrics(){const previous=metric.value||data.defaultMetric;metric.replaceChildren();const found=candidates(),groups=new Map();
 for(const m of found.metrics){const key=m.group||'其他';if(!groups.has(key)){const group=document.createElement('optgroup');group.label=key;groups.set(key,group);metric.append(group)}const o=document.createElement('option');o.value=m.id;o.textContent=m.label+(m.unit?' ['+m.unit+']':'');groups.get(key).append(o)}
 if([...metric.options].some(o=>o.value===previous))metric.value=previous;else if([...metric.options].some(o=>o.value===data.defaultMetric))metric.value=data.defaultMetric;
 render();}
function render(){const selected=data.metrics.find(m=>m.id===metric.value),panels=$('#panels'),found=candidates();panels.replaceChildren();phase.disabled=mode.value==='compare';panels.className='panels'+(mode.value==='single'?' single':'');
 if(!selected){$('#info').textContent='当前分类没有匹配指标。清空搜索可显示全部现有图表。';const e=document.createElement('div');e.className='empty';e.textContent='没有匹配的图表';panels.append(e);return;}
 let text=selected.label+'：'+(selected.description||'')+' · 指标 ID：'+selected.id;
 if(found.q&&found.gunMatch.length)text+=' · 此分类匹配 '+found.gunMatch.length+' 把枪，图表仍保留分类全部行。';
 $('#info').textContent=text;
 for(const p of mode.value==='compare'?['current','adjusted']:[phase.value]){const entry=data.plots.find(v=>v.category===type.value&&v.metric===selected.id&&v.phase===p),panel=document.createElement('section');panel.className='panel';
 const h=document.createElement('h2');h.textContent=phaseName[p];panel.append(h);
 if(entry){const link=document.createElement('a');link.href=entry.path;link.target='_blank';link.rel='noopener';link.textContent='打开 / 保存 SVG';h.append(link);
 const viewport=document.createElement('div');viewport.className='viewport';const object=document.createElement('object');object.type='image/svg+xml';object.data=entry.path;object.width=entry.width;object.height=entry.height;
 object.setAttribute('aria-label',phaseName[p]+' '+selected.label+' 排序图');const fallback=document.createElement('a');fallback.href=entry.path;fallback.textContent='直接打开图表';object.append(fallback);viewport.append(object);panel.append(viewport);
 }else{const empty=document.createElement('p');empty.className='empty';empty.textContent='没有此阶段图表';panel.append(empty)}panels.append(panel)}
}
type.addEventListener('change',fillMetrics);metric.addEventListener('change',render);mode.addEventListener('change',render);phase.addEventListener('change',render);search.addEventListener('input',fillMetrics);fillMetrics();
</script></body></html>'''
    path.write_text(body.replace("__PAYLOAD__", encoded), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=ROOT / "docs/gun-balance/balance-data.json")
    parser.add_argument("--output", type=Path, default=ROOT / "docs/gun-balance")
    args = parser.parse_args()
    analysis_dir = args.output.resolve()
    if not args.data.is_file():
        raise FileNotFoundError("balance-data.json must exist before rendering; no polling is performed")
    if (ROOT / "neoforge-1.21.1/run") == analysis_dir or (ROOT / "neoforge-1.21.1/run") in analysis_dir.parents:
        raise ValueError("output must not be inside the runtime directory")
    data_bytes = args.data.read_bytes()
    data = json.loads(data_bytes)
    guns, metrics = data["guns"], data["metrics"]
    assert len({g["id"] for g in guns}) == len(guns), "gun IDs must be unique"
    assert len({m["id"] for m in metrics}) == len(metrics), "metric IDs must be unique"
    missing_icons = [g["id"] for g in guns if icon_file(g, analysis_dir) is None]
    if missing_icons:
        raise ValueError("missing iconPath for guns: " + ", ".join(missing_icons))
    font_prop, font_family, font_path = choose_font()
    plt.rcParams.update({"font.size": 10, "axes.labelcolor": "#45515C", "text.color": "#1D2B38"})
    plots_dir = analysis_dir / "plots"
    plots_dir.mkdir(parents=True, exist_ok=True)
    type_counts = Counter(g["type"] for g in guns)
    types = [t for t in TYPE_ORDER if t in type_counts] + sorted(set(type_counts) - set(TYPE_ORDER))
    plots, skipped = [], []
    for category in types:
        category_dir = plots_dir / slug(category)
        category_dir.mkdir(exist_ok=True)
        category_guns = [g for g in guns if g["type"] == category]
        for metric in metrics:
            axis_domain = domain(category_guns, metric["id"])
            if axis_domain is None:
                skipped.append({"category": category, "metric": metric["id"], "reason": "no_finite_values_in_either_phase"})
                continue
            for phase in PHASES:
                path = category_dir / (slug(metric["id"]) + "-" + phase + ".svg")
                plots.append(render_svg(path, category_guns, category, metric, phase, axis_domain,
                                        font_family, font_prop, analysis_dir))
        print(f"Rendered {category}: {len(category_guns)} guns", flush=True)
    summary_metric = difficulty_metric(metrics)
    summary = render_summary(plots_dir / "summary.png", guns, types, summary_metric, font_prop)
    preview_guns = [g for g in guns if g["type"] == "smg"] or [g for g in guns if g["type"] == types[0]]
    preview_domain = domain(preview_guns, summary_metric["id"])
    if preview_domain:
        render_preview(plots_dir / "preview.png", preview_guns, summary_metric, preview_domain, font_prop, analysis_dir)
    manifest = {"schemaVersion": 1, "generatedAt": datetime.now(timezone.utc).isoformat(),
        "dataSource": str(args.data.resolve()), "dataSha256": hashlib.sha256(data_bytes).hexdigest(),
        "candidateStatus": "未部署的候选方案", "gunCount": len(guns),
        "metricCount": len(metrics), "plotCount": len(plots),
        "font": {"family": font_family, "path": font_path, "svgTextIsSelectable": True},
        "types": [{"id": t, "label": TYPE_NAMES.get(t, t), "count": type_counts[t]} for t in types],
        "metrics": metrics, "phases": PHASE_NAMES,
        "sorting": "Finite values ascending, ties by ID; missing/non-finite values at bottom by ID, labeled N/A.",
        "axes": "Each category/metric uses the same linear domain for current and adjusted, calculated from their union.",
        "summaryMetric": summary_metric["id"], "summaryStatistic": "population standard deviation, ddof=0, finite values only",
        "summary": summary, "preview": {"path": "plots/preview.png", "category": preview_guns[0]["type"],
                                          "metric": summary_metric["id"], "rowsPerPhase": min(16, len(preview_guns))},
        "skipped": skipped, "plots": plots}
    (analysis_dir / "plot-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    write_html(analysis_dir / "report.html", manifest, guns)
    print(json.dumps({"gunCount": len(guns), "metricCount": len(metrics), "plotCount": len(plots),
                      "skippedCategoryMetrics": len(skipped), "report": str(analysis_dir / "report.html")},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
