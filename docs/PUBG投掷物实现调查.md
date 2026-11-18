# PUBG 投掷物实现调查

核对日期：2026-10-17。目标环境：Minecraft 1.21.1、NeoForge、TaCZ 非官方移植版 1.1.8-hotfix-r7。本次只调查发布信息、依赖和源码，没有安装新投掷物模组，也没有启动游戏。

目前最合适的基础是 **LesRaisins Tactical Equipements 的 1.21.1 NeoForge 非官方移植版**。你记得的 **Counter Strike Grenades** 确实存在，但作者当前公开版本是 **Forge 1.20.1**，不能直接装进本项目。没有找到同时满足“PUBG 专用、TaCZ 附属、NeoForge 1.21.1、完整投掷物复刻”的现成发布版。

## 候选与版本边界

| 项目 | 核实到的版本 | 内容与定位 | 对本项目的判断 |
|---|---|---|---|
| [UNOFFICIAL LesRaisins Tactical Equipements](https://modrinth.com/mod/lr-tactical-1.21.1) | 0.4.3，2026-08-27；MC 1.21.1 / NeoForge | TaCZ 战术装备附属，支持可配置投掷物 | 版本和方向匹配；优先研究接入，尚未验证运行 |
| [Counter Strike Grenades](https://modrinth.com/mod/counterstrikegrenade) | 作者项目支持 MC 1.20.1 / Forge；[CF 最新 1.5.2](https://www.curseforge.com/minecraft/mc-mods/counter-strike-grenades) | CS2 高爆、闪光、烟雾、燃烧、诱饵；可与 TaCZ 互动 | 功能参考价值高；没有核实到作者发布的 1.21.1 NeoForge 版本 |
| [TaCZ Throwable Thing](https://www.curseforge.com/minecraft/customization/tacz-throwable-thing) | `throwable_thing_v1.4.0-1.1.5.zip`，2025-05-11；页面标 1.18.2 / 1.19.2 / 1.20.1 | 旧 TaCZ 枪包，含手雷、M18 烟雾等；作者已停更并推荐 LR Tactical | 需要单独转换审计；不是 1.21.1 即装即用方案，优先级低 |
| [Counter-Strike Guns / Strike Arsenal](https://www.curseforge.com/minecraft/mc-mods/cs-guns) | 作者当前支持 1.21.8 至 26.3 的多个加载器 | 整套 CS2 武器和投掷物 | 没有本项目的 1.21.1 文件，不作为当前安装候选 |

这里要区分 **JAR 模组**与 **ZIP 枪包**。TaCZ Pack Upgrader 可以转换其支持的旧枪包资源数据，例如配方目录、材料和物品组件；它不迁移 Lua API，也不能把 Forge 1.20.1 的任意 JAR 自动变成 NeoForge 1.21.1 模组。LR 移植作者也明确要求旧枪包额外处理。见 [LR 发布说明](https://www.curseforge.com/minecraft/mc-mods/lr-tactical-1-21-1)、[Upgrader 项目](https://modrinth.com/mod/tacz-pack-upgrader)。

## LR Tactical 0.4.3：依赖与实际内容

### 已核实的发布信息

- Modrinth 项目 ID：`zSKr1DYY`，版本 ID：`fcwJFQFG`。
- 发布文件：`LesRaisins-Tactical-Equipements-1.21.1-0.4.3.jar`，6,612,829 字节。
- 声明加载器：`neoforge`；游戏版本：`1.21.1`；客户端和服务器均需对应安装。
- [作者版本记录](https://modrinth.com/mod/lr-tactical-1.21.1/version/0.4.3)、[发布 API](https://api.modrinth.com/v2/version/fcwJFQFG)。

Modrinth API 的 `dependencies` 是空列表，**不代表不需要 TaCZ**。本次固定的源码版本声明 `mod_version=0.4.3`，提交为 `6adba2d17caa43e0ccfbd0dfb6dfb17fd44a92d2`。源码元数据实际要求 TaCZ；该源码快照尚未与发布 JAR 逐项比对。

| 依赖 | 作者/源码声明 | 本项目含义 |
|---|---|---|
| Minecraft | 开发目标 1.21.1；源码范围 `[1.21.1,1.22)` | 按实际发布支持的 1.21.1 使用 |
| NeoForge | 源码最低 `[21.1.93,)` | 当前项目 21.1.259 满足版本数要求 |
| TaCZ | 网页说明至少 1.1.5；源码 TOML 写 `[1.0.4,)` | 现有 1.1.8-hotfix-r7 满足两者版本数，仍需实际 API/加载检查 |
| playerAnimator | 可选，客户端 `[2.0.1,)` | 非必需前置；不应为了基础投掷物盲目安装 |
| Kotlin For Forge | LR 没有声明为必需 | 它是 Counter Strike Grenades 的前置，不能混为 LR 前置 |

依据：[固定版本 gradle.properties](https://github.com/Nahiyus512/LesRaisins-Tactical-Equipements-1.21.1/blob/6adba2d17caa43e0ccfbd0dfb6dfb17fd44a92d2/gradle.properties)、[neoforge.mods.toml](https://github.com/Nahiyus512/LesRaisins-Tactical-Equipements-1.21.1/blob/6adba2d17caa43e0ccfbd0dfb6dfb17fd44a92d2/src/main/resources/META-INF/neoforge.mods.toml)。

### 主页列表与源码列表不同

发布主页仍主要展示 M67、RGN、烟雾弹和闪光弹。固定的 0.4.3 源码 `data/lrtactical/index/throwable/` 实际有以下六项；因此不能仅照主页旧列表说“没有燃烧弹或 C4”。

| 投掷物资产 ID | 源码实现 | 与 PUBG 的关系 |
|---|---|---|
| `lrtactical:m67` | 爆炸型；可温雷、反弹；可配置生命期、重力、速度、半径、伤害 | 破片手雷的合适基础；参数和表现未核实为 PUBG 复刻 |
| `lrtactical:rgn` | 第二种手雷定义 | 额外通用手雷，不能直接计成一种独立 PUBG 投掷物 |
| `lrtactical:smoke_grenade` | 烟雾型实体及客户端烟雾粒子 | 可做基础掩护；不是已核实的 PUBG 互动烟雾实现 |
| `lrtactical:flash_grenade` | 闪光/失聪；按距离和视角调整效果 | 可做震爆弹基础；需按目标版本校准 |
| `lrtactical:molotov` | 落地破碎、区域效果云、点燃实体；默认允许烟雾熄灭 | 有燃烧弹功能基础，但规则不能直接等同 PUBG |
| `lrtactical:c4` | 黏附、遥控起爆；默认无限等待，且会破坏方块 | 与当前 PUBG C4 的部署/倒计时流程明显不同 |

源码证据：[M67](https://github.com/Nahiyus512/LesRaisins-Tactical-Equipements-1.21.1/blob/6adba2d17caa43e0ccfbd0dfb6dfb17fd44a92d2/src/main/resources/data/lrtactical/index/throwable/m67.json)、[烟雾弹](https://github.com/Nahiyus512/LesRaisins-Tactical-Equipements-1.21.1/blob/6adba2d17caa43e0ccfbd0dfb6dfb17fd44a92d2/src/main/resources/data/lrtactical/index/throwable/smoke_grenade.json)、[闪光弹](https://github.com/Nahiyus512/LesRaisins-Tactical-Equipements-1.21.1/blob/6adba2d17caa43e0ccfbd0dfb6dfb17fd44a92d2/src/main/resources/data/lrtactical/index/throwable/flash_grenade.json)、[燃烧弹](https://github.com/Nahiyus512/LesRaisins-Tactical-Equipements-1.21.1/blob/6adba2d17caa43e0ccfbd0dfb6dfb17fd44a92d2/src/main/resources/data/lrtactical/index/throwable/molotov.json)、[C4](https://github.com/Nahiyus512/LesRaisins-Tactical-Equipements-1.21.1/blob/6adba2d17caa43e0ccfbd0dfb6dfb17fd44a92d2/src/main/resources/data/lrtactical/index/throwable/c4.json)。

默认 M67 的 `destroy_blocks=false`，C4 为 `true`。这要由比赛规则统一控制：保留建筑的实验局应关闭破坏；后续地形破坏实验再接入允许破坏的材料、区域与地图复原系统，不能把默认 C4 直接投入保护地图的比赛。

LR 的烟雾粒子继承 `TextureSheetParticle`，使用透明粒子贴图且关闭粒子物理。由此只能确认其视觉实现路径，不能宣称具有 CS2 体素烟雾模拟、子弹开孔，或已经覆盖 PUBG 42.1 的全部互动。见 [SmokeCloudParticle](https://github.com/Nahiyus512/LesRaisins-Tactical-Equipements-1.21.1/blob/6adba2d17caa43e0ccfbd0dfb6dfb17fd44a92d2/src/main/java/me/xjqsh/lrtactical/client/particle/SmokeCloudParticle.java)。

## 与当前 PUBG 比较时，需要避免旧知识误判

[PUBG 官网投掷物目录](https://pubg.com/en/game-info/weapons/throwable)展示震爆弹、破片手雷、燃烧瓶、黏性炸弹、烟雾弹、C4、蓝圈手榴弹（BZ Grenade）。这是类型目录，不能推定每张地图和每种模式都刷新全部项目；C4 的游戏内分类后来也有变化。

**诱饵弹（Decoy Grenade）不是“PUBG 从未有过的 CS 专属物品”。** PUBG 历史上确实有过，但 35.1 已将它从全部刷新位置移除，并从自定义比赛 Spawn 页签移除；42.3 又删除了已移除内容对应的诱饵弹快捷键。因此本次当前 PC 规则的七类对照不加入诱饵弹；如果制作历史规则集，可以另行纳入。见 [PUBG 35.1](https://pubg.com/en/news/8476)、[42.3 键位清理](https://pubg.com/en/news/10885)。

| PUBG 功能 | LR 基础的覆盖情况 | 需要自己补的内容 |
|---|---|---|
| 破片手雷 | M67 有温雷、弹道、反弹、范围伤害 | 引信、投掷力度/姿态、遮挡衰减、护甲、伤害与视觉校准 |
| 烟雾弹 | 有烟雾实体与粒子 | 烟雾体积/持续时间/公平可见性、局部驱散与回填、AI 视线规则 |
| 震爆弹 | 有致盲、失聪与距离/视角参数 | 遮挡、效果时长、视角阈值、表现校准 |
| 燃烧瓶 | 有落地破碎与燃烧效果区域 | 火势与地板适配、伤害周期、穿透/墙体限制、烟雾交互的目标规则 |
| 黏性炸弹 | 有底层黏附实体；没有独立 PUBG Sticky Bomb 默认定义 | 独立资产、定时触发、指定材质破坏及地图规则 |
| C4 | 有名为 C4 的遥控黏附物 | 当前 PUBG 的直接部署、激活、倒计时提示与被爆炸停用流程 |
| BZ Grenade | 未发现内置对应定义 | 局部蓝圈体积、持续时间、伤害与比赛圈系统连接 |

**当前 PUBG 已有互动烟雾，不能简单说“互动烟只有 CS 才有”。** 官方 42.1 说明：爆炸和车辆等碰撞会局部驱散烟雾；在烟雾弹持续期内，散开的部分会重新填充。该公告列出手雷、C4、迫击炮、红区/黑区、车辆等情况，未据此证明 TaCZ 子弹打孔应采用 CS2 规则。见 [PUBG 42.1 Interactive Smoke](https://www.pubg.com/en/news/10179?category=patch_notes)。

**C4 也不能照 2020 年的规则直接实现。** PUBG 35.2 改为直接部署而非投掷黏附，部署约 4 秒、放置后激活；37.2 又允许用爆炸伤害停用正在计时的 C4。LR 默认配置的 `life_time=-1`、`remote_detonation=true` 不是这个流程。见 [35.2 C4 改动](https://pubg.com/en/news/8616?category=patch_notes)、[37.2 C4 改动](https://pubg.com/en-asia/news/9087)。

## Counter Strike Grenades 可以借鉴什么

作者项目明确以 CS2 为目标，支持高爆、闪光、燃烧/燃烧瓶、烟雾与诱饵；包含烟雾互动、烟雾向下填充以及 TaCZ 相关效果。TaCZ 集成为可选，Kotlin For Forge 是其依赖。见 [作者 README](https://github.com/ThePiSquad/CounterStrikeGrenades)、[作者 Modrinth 页面](https://modrinth.com/mod/counterstrikegrenade)。

这些系统可供我们研究烟雾与爆炸交互、诱饵声音、燃烧区域和客户端效果，但不能因为物品名称相同就沿用其伤害、引信、半径、队伍燃烧弹变体或投掷手感。尤其 CS 的诱饵、队伍区分和完整竞技规则，不应自动算作 PUBG 复刻的一部分。

如果坚持使用该模组，需要独立进行 1.20.1 Forge → 1.21.1 NeoForge 代码移植、TaCZ API 适配与渲染兼容工作；这比基于已经有对应版本的 LR Tactical 调整数据和补充规则更大。其项目许可为 MIT，但仍应逐项核实实际复用文件的声明。LR 源码仓库则区分 GPLv3 代码与保留所有权利的美术资产，不能把代码许可套到模型和音效上。

## 接入我们自己的物资系统时，不能只填一个普通物品 ID

LR 与 TaCZ 枪械类似，使用一个通用 Minecraft 物品承载多个资源定义：

```text
Minecraft 物品：lrtactical:throwable
CustomData.ThrowableId：lrtactical:m67 / lrtactical:smoke_grenade / …
```

对应类与接口：

| 类/接口 | 用途 |
|---|---|
| `ModItems.THROWABLE` | 注册通用物品 `lrtactical:throwable` |
| `IThrowable` | 从 ItemStack 读写具体投掷物 ID；`setId` 写入 1.21.1 的 CUSTOM_DATA |
| `LrTacticalAPI.getThrowableIndexes()` | 枚举服务端当前实际加载的投掷物目录 |
| `ThrowableIndex#createItemStack()` | 创建带正确 ThrowableId 的可用物品堆 |
| `ThrowableIndex#createEntity(...)` | 按类型和配置创建投出后的实体 |
| `ThrowableData` / `EntityData` | 存储准备时间、温雷、速度、寿命、重力、反弹等数据 |
| `ExplodeThrowableData` / `StunThrowableData` / `EffectCloudThrowableData` | 描述爆炸、震爆和区域效果的专用参数 |

依据：[ModItems](https://github.com/Nahiyus512/LesRaisins-Tactical-Equipements-1.21.1/blob/6adba2d17caa43e0ccfbd0dfb6dfb17fd44a92d2/src/main/java/me/xjqsh/lrtactical/init/ModItems.java)、[IThrowable](https://github.com/Nahiyus512/LesRaisins-Tactical-Equipements-1.21.1/blob/6adba2d17caa43e0ccfbd0dfb6dfb17fd44a92d2/src/main/java/me/xjqsh/lrtactical/api/item/IThrowable.java)、[LrTacticalAPI](https://github.com/Nahiyus512/LesRaisins-Tactical-Equipements-1.21.1/blob/6adba2d17caa43e0ccfbd0dfb6dfb17fd44a92d2/src/main/java/me/xjqsh/lrtactical/api/LrTacticalAPI.java)、[ThrowableIndex](https://github.com/Nahiyus512/LesRaisins-Tactical-Equipements-1.21.1/blob/6adba2d17caa43e0ccfbd0dfb6dfb17fd44a92d2/src/main/java/me/xjqsh/lrtactical/item/index/ThrowableIndex.java)。

我们当前 `LootCatalog` 的普通 `item` 路径按注册 ID 新建物品，不能表达上述 ThrowableId。后续要新增投掷物目录类型，像枪械一样展示真实预览和资产 ID，并通过 `ThrowableIndex#createItemStack()` 生成。单独填写 `lrtactical:throwable` 会丢失具体手雷身份。这是接入建议，本次没有改实现。

## 建议实施顺序

1. 先选择 LR 0.4.3 作为候选依赖，完成精确 JAR 元数据、API、资源和脚本静态检查；继续遵守不自动启动游戏的约定。
2. 在自己的附属中添加可选 LR 适配层和投掷物目录，让配置界面、唯一物品条目和地面预览保留真实 ThrowableId。当前已取消组合模板；LR 专用投掷物适配仍待实现，不应把普通 Item ID 当作具体投掷物资产 ID。
3. 先完成手雷、烟雾、震爆、燃烧瓶四个基础类型；将数值放入服务端比赛规则，区分历史 PUBG 与当前版本。
4. 单独实现 C4、黏性炸弹与 BZ Grenade 的比赛逻辑，避免被通用遥控炸弹默认行为限制。
5. 让投掷物伤害、地形破坏权限、实体清理和地图复原受同一局游戏控制。对伪多人/机器人实验，烟雾还要影响服务端感知，不能只有玩家客户端看见粒子。

这条路线可复用投掷、碰撞、动画和基础效果，把主要开发量集中在 PUBG 规则、配置与比赛系统。当前尚未完成安装、加载或画面验证；不能把“有匹配发布版”报告成“已验证兼容”。
