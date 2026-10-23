# PUBG 端游枪械比较基准与来源

检索日期：**2026-10-17**。比较范围已经由用户确认为 **PUBG: BATTLEGROUNDS 端游普通／排位**；包括相关世界刷新与空投枪，不包含手游、限时特殊模式、近战和投掷物。此表是目录并集，不等于任何一张地图的刷新白名单。

机器可读清单：[pc-weapons-baseline.json](pubg-weapons/pc-weapons-baseline.json)。以官方当前武器目录为主，用更新公告校正增删。最新查到的 [43.2 公告](https://pubg.com/en/news/11136?category=patch_notes)发布于 2026-10-13，PC 更新日为 2026-10-14，早于本次检索日；该公告没有公布新增或移除常规枪械。

## 统计口径

**现役常规枪械 47 把**；特殊武器另列 6 项，经典但已从世界刷新移除的常规枪械另列 6 把。不要把这些分母混在一起。每个 JSON 条目均有状态、分类、名称别名、已核实的 PUBG 内部 itemId 和来源。

| 分类 | 数量 | 官方目录中的现役名称 |
| --- | ---: | --- |
| AR 突击步枪 | 13 | M16A4、M416、SCAR-L、G36C、QBZ、K2、AUG、FAMAS、AKM、Beryl M762、Mk47 Mutant、Groza、ACE32 |
| DMR 精确射手步枪 | 7 | Mini14、Mk12、SKS、SLR、Mk14、VSS、Dragunov |
| SMG 冲锋枪 | 8 | Tommy Gun、UMP、Micro UZI、Vector、MP5K、MP9、P90、JS9 |
| SR 狙击步枪 | 5 | Kar98k、M24、AWM、Win94、Lynx AMR |
| SG 霰弹枪 | 6 | S12K、S1897、S686、DBS、Sawed-Off、O12 |
| 手枪 | 5 | P18C、P92、Skorpion、Deagle、R1895 |
| LMG 轻机枪 | 3 | M249、MG3、RPD |

来源分别是官方 [AR](https://pubg.com/en/game-info/weapons/ar)、[DMR](https://pubg.com/en/game-info/weapons/dmr)、[SMG](https://pubg.com/en/game-info/weapons/smg)、[SR](https://pubg.com/en/game-info/weapons/sr)、[SG](https://pubg.com/en/game-info/weapons/sg)、[手枪](https://pubg.com/en/game-info/weapons/pistol)、[ETC](https://pubg.com/en/game-info/weapons/etc)。官网将机枪列在 ETC，本表为方便比较单独归入 LMG。

## 不能沿用旧清单的变更

[42.1 官方公告](https://www.pubg.com/en/news/10179?category=patch_notes)确认六把枪从世界刷新移除：**Mosin Nagant、R45、DP-28、PP-19 Bizon、P1911、QBU**。PC 更新日为 2026-06-17。JSON 的 `retiredConventionalFirearms` 保留这些经典枪供以后复刻选用，但不计进当前 47 把分母。这里按公告的“移除世界刷新”表述，不擅自推断训练场或每一种自定义模式都彻底不存在。

[42.3 官方公告](https://www.pubg.com/en/news/10885)加入 **RPD**，7.62mm、基础 50 发弹匣、扩容 110 发。当前官网 ETC 已列出；[43.1 官方公告](https://pubg.com/en/news/11057)仍调整其与 M249 的刷新率。不要将旧清单中的 DP-28 留作现役、同时漏掉 RPD。

[30.1 官方公告](https://pubg.com/en/news/7424)将世界刷新的 SMG 统一为 9mm，并把 UMP45 更名 **UMP**；Tommy Gun 也从 .45 ACP 改为 9mm。因此旧 TaCZ 包里的 UMP45、.45 ACP Thompson 可以是外观候选，不能直接认定当前端游机制一致。

## 特殊武器与工具

| 名称 | 单列原因 |
| --- | --- |
| Crossbow／十字弩 | 弩而非火器；官网 ETC 列出 |
| M79 | PUBG 使用 40mm 烟雾弹；杀伤榴弹版本不能直接替代机制 |
| Mortar／迫击炮 | 部署与曲射流程；不是常规手持枪 |
| Panzerfaust／铁拳 | 一次性火箭武器 |
| Stun Gun／电击枪 | 特殊控制效果 |
| Flare Gun／信号枪 | 召唤补给用途；42.1 公告仍提及其补给箱，当前武器目录未单列，最后直接确认来源早于本次检索 |

前五项见官方 [ETC](https://pubg.com/en/game-info/weapons/etc)。Flare Gun 见 [42.1 公告](https://www.pubg.com/en/news/10179?category=patch_notes)与官方 API 字典，不能因为它没出现在 ETC 列表就默认为删去。**它的逐地图／排位可用性尚未逐项核验**。

Zipline Gun 和 Emergency Cover Flare 另归工具，不进入枪械数量分母。[35.1 官方公告](https://pubg.com/en/news/8476)说明其刷新调整，以及电击枪和应急掩体信号物加入排位；这不等于它们的全部机制都由 TaCZ 枪械 JSON 支持。

## 比较时如何处理别名

“名称覆盖”与“机制还原”分开检查。英文大小写、空格和连字符可以归一化，确认为同一 PUBG 条目的名称变体也可以合并；以下情况不能凭相近名称算精确覆盖：

- **AKM ≠ AK47**。PUBG 内部类名恰好叫 `Item_Weapon_AK47_C`，官方字典把它映射为 AKM；这不证明 TaCZ 的 AK47 就是 AKM。
- **M416 与任意 HK416／M4A1 模组枪不能直接算同款精确复刻**。PUBG 内部 `Item_Weapon_HK416_C` 对应 M416，但模型变体、配件槽与游戏数值仍须核验。
- **SCAR-L ≠ SCAR-H**、**MP5K ≠ MP5**、**当前 UMP ≠ .45 ACP UMP45**。
- **M249／FN Minimi** 属型号族候选，不能仅凭族名推断 PUBG 的容量、操作与平衡。
- **Kar98k／Kar 98k／98k** 可作为名称别名；**Mk14／Mk14 EBR**、**Mini14／Mini 14**同理。
- 所有名称匹配之后，再比较口径、弹匣容量、射击模式、射速、伤害、后坐力、可装配件、枪身模型与动画。默认 TaCZ 手感接近不代表 PUBG 数值一致。

JSON 的 `aliases` 用于名称匹配，`explicitNonEquivalentPairs` 防止常见误合并。PUBG 内部 itemId 是溯源字段，不是 TaCZ 枪 ID；特别不能跨两个项目按字符串强行对齐。

## API 字典的使用边界

官方 [pubg/api-assets itemId.json 固定版本](https://github.com/pubg/api-assets/blob/32b13b51128b8d8909ae5e77f3b833e01230b24d/dictionaries/telemetry/item/itemId.json)，提交 `32b13b51128b8d8909ae5e77f3b833e01230b24d`，提交日期 **2024-10-28**。本次联网读取了该固定版本，发现它保留经典枪及工具，也缺少已在当前官网列出的部分新枪 ID。

所以：**字典只用于确认已经有映射的内部 ID，不能用于判定 2026 现役列表**。FAMAS、RPD 在该快照没有已核实的对应 itemId，本 JSON 保留空数组并说明原因，没有猜造 ID。目录和公告才决定现役状态。

## 时效与未覆盖项

此清单仅固定到上述检索日。没有逐张地图、逐种普通／排位规则审核生成矩阵；后续版本更改需要重新检查官网与公告。当前工作区也没有通过启动 PUBG 或 Minecraft 进行实机复核。

