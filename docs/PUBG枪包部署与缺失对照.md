# PUBG 枪包部署、缺失与后续制作

核对及部署日期：2026-10-17。环境为 Minecraft 1.21.1、NeoForge 21.1.259、TaCZ 非官方移植版 1.1.8-hotfix-r7。比较范围按用户确认：PUBG 端游普通／排位相关武器目录并集，含空投武器，排除手游与限时玩法专属装备。不同地图与模式不保证刷新全部武器。

## 结果

找到并已部署 **Classic BattleRoyale Gun，CBRG 0.4.8**。它统一多个枪包中的 PUBG 武器配置、弹药和配件；不是独立提供全部模型的包，也不是完整的 PUBG 战斗系统。

- [作者发行文件 7589555](https://www.curseforge.com/minecraft/customization/tacz-classic-battleroyale-gun/files/7589555)
- [作者仓库](https://github.com/XColorful/Classic-BattleRoyale-Gun)及[安装 Wiki](https://github.com/XColorful/Classic-BattleRoyale-Gun/wiki)
- 本次按实际下载的 **CF 0.4.8 发行版**部署；未混用 GitHub main 的后续枪型替换。
- 原始文件、下载来源、体积、SHA256 见 [下载清单](../downloads/pubg-gunpacks/download-manifest.json)。八个原始压缩包合计约 231.8 MiB。

截至官网 43.2 的 **47 把现役常规枪械**，CBRG 有 **44 把名称意图候选**：26 把同枪型候选，18 把变体或替代。**完全缺失的现役枪型为 VSS、DBS、RPD。** 同枪型候选仍没有证明模型、操作和数值精确一致。

作者 README 的“缺 DBS、R45、VSS”是另一时间点的结论；R45 已在 PUBG 42.1 移出世界刷新，RPD 则在 42.3 加入。不要把旧清单当当前端游结论。完整证据见 [PUBG 基准来源](PUBG枪械基准来源.md)、[逐枪对照](PUBG枪械逐项对照.md)和[机器可读对照](pubg-weapons/cbrg-0.4.8-comparison.json)。

## 实际安装了什么

枪包放在 **`neoforge-1.21.1/run/tacz/`**，不是 `run/mods/`。本轮使用作者指定的八个基础枪包，其中默认包沿用当前 r7 自带资源；其余七包与 CBRG 主包均离线转换后压缩为根目录含 `gunpack.meta.json` 的 ZIP。

| 基础内容 | 固定版本／文件 ID | 安装文件或目录 |
| --- | --- | --- |
| CBRG 主包 | 0.4.8／7589555 | `classic_battleroyale_gun+1.21.1.zip` |
| CIBR | v0.3_1.1.7／7280896 | `cibr_gunspack+1.21.1.zip` |
| Classics Reborn | ClassicR1.1.3 + CCRP1.1.4 hotfix3／7529068 | `classic_rccrp+1.21.1.zip` |
| Cold War | 0.51／7042787 | `cold_war+1.21.1.zip` |
| Gunpowder Revolution | 1.2.7／6982844 | `gunpowder_revolution_gunpack+1.21.1.zip` |
| Infinite Origin | 1.0.0／6768903 | `infinite_origin+1.21.1.zip` |
| Suffuse GunSmoke | 1.0.7 hotfix／6563620 | `suffuse_gunsmoke_pack+1.21.1.zip` |
| The Division | 0.3.3／6130805 | `the_division_pack+1.21.1.zip` |
| TaCZ 默认资源 | 当前 r7 自带包 + CBRG 覆盖 | `tacz_default_gun/` |

没有安装作者可选的 `remove_unused_content`：该目录含空文件删除标记，会移除额外枪械。当前保留基础包的其它内容，因此创造物品栏／选择器中的全部枪数会多于 PUBG 枪数；对照只统计 CBRG 的 53 个目标枪型定义，并按实际武器去重。

另在 `run/mods/` 安装 **[TaCZ Pack Upgrader 2.1.3](https://modrinth.com/mod/tacz-pack-upgrader/version/KBxw9pI3)**，已核对 Modrinth SHA512、Java21、NeoForge／MC 元数据及内嵌 Kotlin。没有额外安装 Kotlin For Forge，也没有给这些基础 PUBG 配置安装 Arcana。

此次枪包已经离线处理，并以 `+1.21.1.zip` 命名；升级器会跳过这些 ZIP 和默认包。以后放入未转换的旧枪包时，升级器才会在用户启动游戏时处理，原版备份通常进入实例的 `tacz+1.20.1/`。升级器不转换 Java 附属或任意 Lua API。

## 为当前版本做了哪些修正

所有修正只作用于工作区的安装副本。下载原包不改，原 HMCL 实例不改。完整记录见 [本地兼容修改清单](pubg-weapons/local-compatibility-changes.json)。

1. 按 [升级器作者规则](https://github.com/MUKSC/TaCZPackUpgrader/blob/main/src/main/kotlin/me/muksc/taczpackupgrader/Upgrader.kt)离线转换 **721 个旧配方**：`recipes` → `recipe`，材料标签、NBT Ingredient、ItemStack 组件及工作台图标等。存在新旧配方并存时，将作者覆盖应用到新目录；没有通过游戏启动触发迁移。
2. 在 staging 执行作者的两个模型／动画校正脚本，统一 constraint 位置与射击偏移。没有执行可选的伤害、开栓、装甲穿透、趴姿后坐批量重写脚本。
3. 修正 S12K 的两个实际错误引用：主模型改为已存在的 `gz:gun/tactical_sasg_12k_geo`，贴图改为 `gz:gun/uv/tactical_sasg_12k`。
4. 删除 **11 个缺失的可选 LOD 定义**，由 r7 回退到主模型。代价是这些枪在远处仍可能使用较复杂的模型。
5. 修复 **158 处声音引用**：缺失的近战声音改用 r7 实际存在的具体声音，修正 `ccbrg:` 拼写，命中提示回用 TaCZ 资源等。保留可用的 PUBG 射击音效。
6. 移除 **43 处本来就不会播放的旧／缺失声音映射**，主要是整段换弹／检视声音及不存在的消音声。没有伪造新音效；已有动画关键帧声音保留。并非每把枪都有完整的 PUBG 换弹音效。
7. 对同路径的单值资源统一副本，避免资源读取顺序决定工作台、默认音效或弹药外观。TaCZ 自定义配件标签按 r7 的合并机制保留，不当作普通冲突删除。

这些是兼容和无效引用修正，不构成对 PUBG 参数的完整重标定。

## 本轮检查范围

- 原始下载 ZIP 可读、CRC 完整，并记录 SHA256。
- CBRG **53 个目标枪型**共检查 **1526 处引用**：index、data、主模型、贴图、图标、动画、状态机、服务端脚本、弹药表现与显示音效等；当前检查范围内无缺失引用、无未解决的单值资源路径冲突。
- SVD／GM6 的动画为 **GLTF**，r7 支持该格式，不能因为没有 `.animation.json` 就判断缺失。
- 静态审计 staging **173 个非空 Lua**：未发现 Arcana／Java 反射依赖，require 模块存在，context／api 调用可在当前 r7 对应类与继承链中找到。
- [审计 JSON](pubg-weapons/gunpack-static-audit.json)、[部署清单](../logs/pubg-gunpack-deployment.json)及 `logs/pubg-gunpack-*.log` 保留核对证据。

**未启动客户端或服务端。** 本轮没有验证实际开火、瞄准、换弹时序、渲染、光影、性能或多人同步；静态资源齐全不能替代这些验证。所有基础包的额外非 PUBG 枪械也不在这 53 把的逐枪资源审计范围内。

## 相近型号与还原缺口

| PUBG 名称 | 实际枪型 ID | 已知差异 |
| --- | --- | --- |
| AKM | `tacz:ak47` | 仍使用 AK47 模型，不能只靠名称当成准确 AKM。 |
| M416 | `tacz:hk416d` | 实际 display 指向 HK416A5，需比对 PUBG 外观与机构。 |
| Mini14 | `ccrp:m14_hbar` | M14 HBAR 替代；不是完成了一把准确 Mini14。 |
| P92 | `tacz:p320` | P320 替代 P92。 |
| Skorpion | `tacz:cz75` | CZ75 替代。 |
| S1897 | `tacz:m870` | M870 替代。 |
| M24 | `tacz:m700` | M700 同族替代，不能默认配置完全相同。 |
| G36C | `tacz:g36k` | G36K 变体。 |
| MP5K | `tacz:hk_mp5a5` | 较长的 MP5A5 变体。 |
| VSS／DBS／RPD | 无 | 需要补枪型数据、准确模型、动画与音效。 |

JS9、Mk12、Dragunov **有定义**，分别为 `cib:js9`、`tacz:spr15hb`、`suffuse:svd`；但 Mk12 的实际模型、以及其它枪的口径、弹容、射速、后坐力与配件仍应按逐枪表判断。

此外，还缺对整个 PUBG 战斗体系的统一验收：护甲／头盔、距离伤害、命中部位、换弹、开火模式、屏息、偏侧瞄具、配件增益和服务端权威结算。TaCZ 参数数值相同也不代表 PUBG 体感相同。

## 与我们的物资模组怎样连接

`LootCatalog` 通过 TaCZ 的实际枪型目录枚举武器，不靠给每把枪注册 NeoForge Item。新枪包在用户下次启动并加载后，可在 `/pubgdebug config` 的枪械选择器中按真实 ID 选择。

- 一把枪仍是 `tacz:modern_kinetic_gun`，由 `minecraft:custom_data.GunId` 区分。
- CBRG 改变了一些原枪的弹药引用。例如 AKM 仍用 `tacz:ak47`，其配套弹药已是 `cbrg:ammo_762`；M416 用 `tacz:hk416d`，弹药是 `cbrg:ammo_556`。
- **当前配置已更新为唯一物品独立权重，旧配置先备份迁移。** 每次服务器运行首次初始化存档配置时，自动补入当前全部有效 TaCZ 枪械、弹药和配件，新枪/配件权重 1、独立弹药权重 0，已有权重不重置，没有 128 条业务上限。以前的重复条目迁移为一项；旧弹药 ID 仍应在 config 中核对。
- 地面模型按普通掉落物的真实 GROUND 三维大小平放；薄片/二维后备图标额外缩放 0.38。先生成主物品，再按基础弹匣容量 `ceil(/30)` 份、每份 30 发自动配弹，默认半径 1～1.5 格可配；配弹不占密度，全部物品独立连续 0～360° 水平随机朝向。CBRG 弹容不代表官方 PUBG 弹容已经验收。
- LR 等投掷物通常也有独立资产 ID；不能只用一个通用 Item ID 生成，另见 [投掷物调查](PUBG投掷物实现调查.md)。

## 源文件、继续制作与撤回

编辑用的组装副本在 `downloads/pubg-gunpacks/staging/<包名>/`，原始 ZIP 在同级下载目录。运行实例加载的是转换后的 ZIP 和默认包文件夹，修改 staging 不会自动影响运行 ZIP。脚本与流程证据保存在 `scripts/*pubg-gunpacks*.py`。

部署前备份：`logs/source-backups/pubg-gunpacks-20261017-211626/`，含完整旧 `tacz/` 与旧 `tacz-pre.toml`。安装时将 `DefaultPackDebug` 改为 true，防止下一次启动重新导出默认包覆盖 CBRG 修改；其他配置不整体替换。

如要撤回，关闭实例后移出本轮八个 ZIP 和升级器 JAR，恢复备份中的默认包及配置。备份还包含部署前 `.export-state.json`。不要在另一个实例的源目录执行撤回。

批量制作仍然可行，建议利用现有覆盖做“补缺与纠正型号”：先补 VSS、DBS、RPD，再处理 Mini14、P92 等显著替代模型。枪械表、index、数值、弹药、配件标签、语言和独立物品配置可以批量生成；模型、骨骼、UV、换弹动画、定位和音效不能因生成 JSON 就自动完成。VSS 的内置消音与专用瞄具、DBS 双管装填、RPD 的供弹动作应分别处理。

完整制作流程见 [TaCZ 枪械注册与制作说明](TaCZ枪械注册与制作说明.md)。保留原包署名及素材来源；本地组装安装不等于得到重新分发所有素材的授权。
