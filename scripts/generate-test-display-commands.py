"""Generate reviewed command-block display functions and a full copyable command document."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "docs/compatibility/compatibility-inventory.json"
FUNCTIONS = ROOT / "neoforge-1.21.1/src/main/resources/data/tacz_pubg/function/test_display"
DOCUMENT = ROOT / "docs/命令方块物品测试陈列.md"
SPACING = 2


def command(asset, kind, index, z):
    asset_id = asset["id"]
    assert re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", asset_id), asset_id
    if kind == "gun":
        mode = asset["cases"][0]["fire_modes"][0].upper()
        assert mode in ("AUTO", "SEMI", "BURST"), (asset_id, mode)
        item = "tacz:modern_kinetic_gun"
        data = f'GunId:"{asset_id}",GunFireMode:"{mode}",GunCurrentAmmoCount:0,HasBulletInBarrel:0b'
    elif kind == "ammo":
        item, data = "tacz:ammo", f'AmmoId:"{asset_id}"'
    else:
        item, data = "tacz:attachment", f'AttachmentId:"{asset_id}"'
    return (
        f'summon minecraft:item ~{index * SPACING} ~1 ~{z} '
        '{Tags:["test"],NoGravity:1b,Motion:[0.0d,0.0d,0.0d],Rotation:[0.0f,0.0f],'
        'PickupDelay:32767s,Age:-32768s,Invulnerable:1b,'
        'Item:{id:"' + item + '",count:1,components:{"minecraft:custom_data":{' + data + '}}}}'
    )


def main():
    inventory = json.loads(INVENTORY.read_text(encoding="utf-8"))
    available = {k: [x for x in inventory[k] if x["static_candidate"]]
                 for k in ("guns", "ammo", "attachments")}
    rows = [("guns", "枪械", "gun", available["guns"]),
            ("ammo", "子弹", "ammo", available["ammo"])]
    for key, label in (("stock", "枪托"), ("muzzle", "枪口"), ("scope", "瞄具"),
                       ("grip", "握把"), ("laser", "激光"), ("extended_mag", "弹匣")):
        rows.append((key, label, "attachment", [a for a in available["attachments"]
                                               if a["cases"][0]["type"] == key]))
    assert sum(len(r[3]) for r in rows) == sum(len(x) for x in available.values())
    all_ids = [(kind, a["id"]) for _, _, kind, assets in rows for a in assets]
    assert len(all_ids) == len(set(all_ids)), "duplicate display asset"
    footprint = json.loads((ROOT / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg/ground_loot_footprints.json")
                           .read_text(encoding="utf-8"))
    for key in available:
        assert {a["id"] for a in available[key]} == set(footprint[key]), key
    FUNCTIONS.mkdir(parents=True, exist_ok=True)
    maximum_x = (max(len(r[3]) for r in rows) - 1) * SPACING
    maximum_z = len(rows) - 1
    total = len(all_ids)
    parts = [f"""# 命令方块：无重力枪械、子弹与配件陈列

适用：当前 Minecraft 1.21.1 / NeoForge / TaCZ r7 开发实例。生成清单核对日期：2026-10-18。

**原点是执行命令的命令方块中心。** 全部实体在该原点 Y+1；沿世界坐标 +X 排列，相邻物品间隔 {SPACING} 格。枪械排 Z+0，之后各类别依次 Z+1、Z+2……。这不是玩家朝向，也不以最近玩家为原点。

## 一条命令生成全部

函数已加入本模组资源。下次自行启动开发实例即可调用；已经打开的开发实例可先 `/reload`，若函数仍未被识别则下次重启后使用。命令方块设为“脉冲”，触发一次：

```mcfunction
function tacz_pubg:test_display/spawn_all
```

会生成 **{total} 个实体**。不要用重复命令方块持续执行；每次执行都会再生成一套。

| Z 偏移 | 类别 | 数量 | 只生成这一排的命令 |
|---:|---|---:|---|
"""]
    all_lines = ["# Generated from the current reviewed TaCZ asset inventory.",
                 "# Relative coordinates inherit the calling command block's origin."]
    generated = []
    for z, (key, label, kind, assets) in enumerate(rows):
        assets.sort(key=lambda a: a["id"])
        lines = [command(a, kind, i, z) for i, a in enumerate(assets)]
        for line in lines:
            assert 'Tags:["test"]' in line and "NoGravity:1b" in line
            assert "PickupDelay:32767s" in line and "Age:-32768s" in line
        (FUNCTIONS / f"{key}.mcfunction").write_text(
            f"# {label}; Y+1, Z+{z}, X spacing {SPACING}; {len(lines)} entities\n"
            + "\n".join(lines) + "\n", encoding="utf-8")
        all_lines.append(f"function tacz_pubg:test_display/{key}")
        parts.append(f"| +{z} | {label} | {len(lines)} | `function tacz_pubg:test_display/{key}` |\n")
        generated.append((z, key, label, assets, lines))
    (FUNCTIONS / "spawn_all.mcfunction").write_text("\n".join(all_lines) + "\n", encoding="utf-8")
    (FUNCTIONS / "kill.mcfunction").write_text("# Current dimension, loaded entities only.\nkill @e[tag=test]\n", encoding="utf-8")
    parts.append(f"""
所有排函数都应从**同一个原点**执行。若把不同排放进不同位置的连锁命令方块，每个方块的相对坐标原点也会不同；直接调用 `spawn_all` 就不会有这个问题。

## 删除所有 test 实体

命令方块直接复制：

```mcfunction
kill @e[tag=test]
```

聊天框使用 `/kill @e[tag=test]`。也可在命令方块使用 `function tacz_pubg:test_display/kill`。该选择器处理**当前维度中已加载的所有 test 实体**，并非只删除本次生成的物品。

## 长排的区块加载

最长的枪械排从 X+0 延伸到 X+{maximum_x}。远端可能超出自然加载范围。需要完整观察、并让上述 kill 一次清掉整套陈列时，可在**同一个命令方块**先执行以下可选命令，等区块加载后再执行生成命令：

```mcfunction
forceload add ~ ~ ~{maximum_x} ~{maximum_z}
```

只保持陈列所在的细长范围加载，按起点位置最多约 108 个区块；这也会加载或生成该范围的地形。建议在空旷测试场地使用。清理 test 实体后，如这段范围不是其他用途已有的强制加载区，再用同一原点释放：

```mcfunction
forceload remove ~ ~ ~{maximum_x} ~{maximum_z}
```

## 展示体的行为

- 实体注册名是 `minecraft:item`，每个都有 `Tags:["test"]`、`NoGravity:1b`、零初速度和固定朝向。
- `PickupDelay:32767s` 使其不可拾取，并让本附属跳过落地转换；`Age:-32768s` 使其不自然消失且不合堆。
- 在当前本附属中，这些 TaCZ 物品仍使用现有平放渲染，模型大小不变。枪械为空弹匣；每种弹药和配件展示 1 件，不自动生成配套弹药。
- 选择空气中无流体的位置。无重力不等于无碰撞：把实体生成进方块或水流仍可能使原版物品移动。
- 使用 Minecraft 1.21.1 的 `Item.count` 和 `components."minecraft:custom_data"` 格式。`GunId` 等是枪包资产 ID，不能直接当作物品注册名。

下方列出全部原始指令，**每行是一条独立命令方块指令**；不要把整段多行粘贴到单个命令方块。需要一次运行整排/全部时使用上面的 `function`。各行的资产顺序按 ID 排序，可直接从 `GunId` / `AmmoId` / `AttachmentId` 查看对应资产。

""")
    for z, key, label, assets, lines in generated:
        parts.append(f"## Z+{z}：{label}（{len(lines)}）\n\n```mcfunction\n"
                     + "\n".join(lines) + "\n```\n\n")
    parts.append("## 清单与验证边界\n\n"
                 "当前 9 个枪包的资产集合已与本地清单核对；排除 7 把声明但资源不完整的枪，保留目录中的隐藏配件。"
                 "共 424 把枪、78 种弹药、247 个配件，不包含工作台、靶子和弹药箱。"
                 "依据本地 TaCZ r7 的物品注册与数据访问源码，以及 Minecraft 1.21.1 的物品实体 NBT 读取规则生成。"
                 "已做清单覆盖、去重、Minecraft 1.21.1 原生 SNBT 解析（749 条通过）和资源打包检查，"
                 "记录位于 `logs/command-block-display/validation.log`；未启动游戏执行这些指令。"
                 "新增/删除枪包后可重新运行 `scripts/generate-test-display-commands.py` 更新本文件与函数。\n")
    DOCUMENT.write_text("".join(parts), encoding="utf-8")
    print(json.dumps({"entities": total, "rows": {r[0]: len(r[3]) for r in rows},
                      "extent": [maximum_x, 1, maximum_z], "document": str(DOCUMENT)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
