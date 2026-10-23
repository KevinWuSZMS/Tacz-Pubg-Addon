# PUBG 枪械逐项对照：Classic BattleRoyale Gun 0.4.8

核对日期：2026-10-17。比较对象为 PUBG: BATTLEGROUNDS **PC 43.2** 固定现役基准，以及实际下载的 **CurseForge CBRG 0.4.8** 发行档。

当前 47 把常规枪械中，CBRG 有 **44 把名称意图候选**，其中 **18 把有明确变体或替代关系**、**26 把属于同枪型候选**；**VSS、DBS、RPD 缺少可对应枪械 index**。这不是“44 把已验证可用”，也不是“44 把精确复刻”。

本表只读检查 index → data/display → 模型引用；模型、纹理、脚本、前置版本的资源闭合由部署报告另行检查。没有启动游戏，没有验证实际外观、动作或射击手感。

## 固定来源与判定

- [作者发行档 CBRG 0.4.8 / 文件 7589555](https://www.curseforge.com/minecraft/customization/tacz-classic-battleroyale-gun/files/7589555)；本地原档 `downloads/pubg-gunpacks/CBRG-0.4.8.zip`。
- 原档 SHA-256：`efe77ecf980c50497c76a9b6b979a933e0b395afac79ab7f33065bdde8e02673`。原样解压快照在 `downloads/pubg-gunpacks/cbrg-release/`，本表没有修改该快照。
- [PC 武器基准](pubg-weapons/pc-weapons-baseline.json)按官网目录与更新公告固定，现役、退役及特殊武器分别统计。
- [完整机器可读对照](pubg-weapons/cbrg-0.4.8-comparison.json)保留每把枪的全部候选 ID、index/display/data 路径、模型、弹药、弹匣容量、射速及射击模式。
- **同枪型候选**：名称意图与资产引用同族，没有在当前静态核对中看到明显跨枪型替代；不证明 PUBG 精确还原。
- **变体或替代**：明确使用其他变体、通用模型或不同枪型。即便已经修改口径和数值，也不能算精确外观覆盖。
- **缺少 index**：只有语言名称或完全没有项目；没有可对应的有效注册定义。

## 47 把现役常规枪械

下表模型为 display 内实际引用的资源 ID，不代表该资源已通过依赖完整性检查。有多个候选时，表中选一个主候选，完整 JSON 保留全部候选。

| PUBG 枪械 | 类型 | 实际枪械 ID | 实际模型引用 | 判定 | 差异与核对说明 |
|---|---|---|---|---|---|
| M16A4 | AR | `tacz:m16a4` | `tacz:gun/m16a4_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| M416 | AR | `tacz:hk416d` | `tacz:gun/hk416a5_geo` | 变体或替代 | gunId 为 HK416D，display 实际引用 HK416A5 模型；同族近似，不能当作 PUBG M416 的精确外观。 |
| SCAR-L | AR | `tacz:scar_l` | `tacz:gun/scar_l_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| G36C | AR | `tacz:g36k` | `tacz:gun/g36k_geo` | 变体或替代 | 使用 G36K 模型替代 G36C；枪管、护木及整枪长度的具体差异需补做。 |
| QBZ | AR | `tacz:qbz_95` | `tacz:gun/qbz_95_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| K2 | AR | `cib:k2` | `cib:gun/k2_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| AUG | AR | `tacz:aug` | `tacz:gun/aug_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| FAMAS | AR | `rainforest:famas` | `rainforest:gun/famas_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| AKM | AR | `tacz:ak47` | `tacz:gun/ak47_geo` | 变体或替代 | 名称改为 AKM，但 gunId、display 和模型仍为 AK47；没有证据表明其是 PUBG AKM 的独立模型。 |
| Beryl M762 | AR | `cbrg:beryl_m762` | `cbrg:guns/beryl_m762_geo` | 同枪型候选 | cbrg:beryl_m762 与 cbrg:beryl_m762_2 为同名不同外观；只计一把。 |
| Mk47 Mutant | AR | `classicr:mk47` | `classicr:gun/mk47_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| Groza | AR | `cbrg:groza` | `cbrg:guns/groza_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| ACE32 | AR | `cib:galilace32` | `cib:gun/galilace32_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| Mini14 | DMR | `ccrp:m14_hbar` | `ccrp:gun/m14_hbar_geo` | 变体或替代 | 使用 M14 HBAR 模型；M14 与 Ruger Mini-14 不是同一型号，属于明显替代。 |
| Mk12 | DMR | `tacz:spr15hb` | `tacz:gun/spr15hb_geo` | 变体或替代 | 使用 SPR15HB 模型与动画；不是独立命名的 Mk12 资产，型号与外观需单独核验。 |
| SKS | DMR | `tacz:sks_tactical` | `tacz:gun/sks_tactical_geo` | 变体或替代 | 使用 SKS Tactical 改装外观；同族但并非 PUBG 默认木托外观。 |
| SLR | DMR | `cbrg:slr` | `cbrg:guns/slr_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| Mk14 | DMR | `tacz:mk14` | `tacz:gun/mk14_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| VSS | DMR | `—` | `—` | 缺少 index | 语言文件有名称，但发行包无可对应的有效枪械 index；不算实现。 |
| Dragunov | DMR | `suffuse:svd` | `suffuse:gun/svd_geo` | 同枪型候选 | 已存在 suffuse:svd，显示名称为 Dragunov；SVD 是其枪型候选，资源闭合与 PUBG 机制另行检查。 |
| Tommy Gun | SMG | `classicr:m1a1_smg` | `classicr:gun/m1a1_smg_geo` | 变体或替代 | 使用 M1A1 SMG 模型；Thompson 同族，但不能据此认定与 PUBG 汤姆逊的具体变体及鼓匣外观一致。 |
| UMP | SMG | `tacz:ump45` | `tacz:gun/ump45_geo` | 变体或替代 | 保留 UMP45 模型，但发行版 data 已改用 cbrg:ammo_9mm；不能只看 gunId 判成仍用 .45。 |
| Micro UZI | SMG | `tacz:uzi` | `tacz:gun/uzi_geo` | 变体或替代 | 使用通用 UZI 模型；与 Micro UZI 的尺寸、折叠枪托及细节不能按同名精确认定。 |
| Vector | SMG | `tacz:vector45` | `tacz:gun/vector45_geo` | 变体或替代 | 保留 vector45 模型，但发行版 data 已改用 cbrg:ammo_9mm；口径配置与外观要分开评价。 |
| MP5K | SMG | `tacz:hk_mp5a5` | `tacz:gun/hk_mp5a5_geo` | 变体或替代 | 使用 MP5A5 长型模型与动画替代短型 MP5K。 |
| MP9 | SMG | `classicr:mp9` | `classicr:gun/mp9_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| P90 | SMG | `origin:p90` | `origin:gun/p90_geo` | 同枪型候选 | 发行包同时包含 origin:p90 与 tacz:p90；对现役清单只算一把，优先展示 origin:p90，不重复增加覆盖率。 |
| JS9 | SMG | `cib:js9` | `cib:gun/js9_geo` | 同枪型候选 | 已存在 cib:js9 index、data 与 JS9 display 引用；资源实际闭合另行检查，不能按没有 cbrg:js9 判缺失。 |
| Kar98k | SR | `classicr:kar98` | `classicr:gun/kar98_geo` | 同枪型候选 | CF 0.4.8 使用 classicr:kar98 / classicr:gun/kar98_geo；没有引用 gewehr98。GitHub 当前 main 改为 tacz:kar98，不可混用快照。 |
| M24 | SR | `tacz:m700` | `tacz:gun/m700_geo` | 变体或替代 | 使用 M700 模型；M24 源自该平台，但枪托、枪管、瞄具及具体变体仍不等同。 |
| AWM | SR | `tacz:ai_awp` | `tacz:gun/ai_awp_geo` | 变体或替代 | 使用 ai_awp 模型，data 改用 cbrg:ammo_300m；AWP/AWM 型号与外观不能按重命名视为精确复刻。 |
| Win94 | SR | `hamster:win1894` | `hamster:gun/win1894_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| Lynx AMR | SR | `suffuse:gm6` | `suffuse:gun/gm6_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| S12K | SG | `gz:tactical_sasg_12k` | `gz:gun/tactical_sasg_12k_geo` | 变体或替代 | 使用 Tactical SASG-12K 模型；Saiga 同族的战术改装外观，需核验与 PUBG S12K 的差异。 |
| S1897 | SG | `tacz:m870` | `tacz:gun/m870_geo` | 变体或替代 | 使用 Remington M870 模型替代 S1897，属于不同枪型的明显替代。 |
| S686 | SG | `cib:686` | `cib:gun/686_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| DBS | SG | `—` | `—` | 缺少 index | 语言文件有名称，但发行包无可对应的有效枪械 index；不算实现。 |
| Sawed-Off | SG | `tacz:db_short` | `tacz:gun/db_short_geo` | 变体或替代 | 使用通用 db_short 双管短枪模型；有功能形态候选，未证明与 PUBG 锯短型的具体外观一致。 |
| O12 | SG | `cib:origin12` | `cib:gun/origin12_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| P18C | PISTOL | `cib:g18c` | `cib:gun/g18c_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| P92 | PISTOL | `tacz:p320` | `tacz:gun/p320_geo` | 变体或替代 | 使用 SIG P320 模型替代 P92/Beretta 系列，属于不同枪型的明显替代。 |
| Skorpion | PISTOL | `tacz:cz75` | `tacz:gun/cz75_geo` | 变体或替代 | 使用 CZ75 手枪模型替代 Vz.61 Skorpion，属于不同枪型的明显替代。 |
| Deagle | PISTOL | `tacz:deagle` | `tacz:gun/deagle_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| R1895 | PISTOL | `hamster:nagantm1895` | `hamster:gun/nagantm1895_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| M249 | LMG | `tacz:m249` | `tacz:gun/m249_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| MG3 | LMG | `cib:mg3` | `cib:gun/mg3_geo` | 同枪型候选 | 有同枪型或同族资产候选；未证明模型、动作、伤害、后坐力、配件与 PUBG 完全一致。 |
| RPD | LMG | `—` | `—` | 缺少 index | 现役基准在 42.3 新增的 RPD，CBRG 0.4.8 无对应 index；不应以 DP-28/RPK 替代计数。 |

## 容易造成误判的地方

1. **注册命名空间大多保留原枪包**。例如 JS9 是 `cib:js9`，Dragunov 是 `suffuse:svd`，Mk12 是 `tacz:spr15hb`；只搜索 `cbrg:*` 会漏掉大多数枪。
2. **名称改了，模型可能没改**。Mini14 实际仍是 `ccrp:gun/m14_hbar_geo`；P92 仍是 P320；Skorpion 仍是 CZ75；S1897 仍是 M870。它们是可供玩法试验的替代，不能描述为忠实外观复刻。
3. **gunId 不等于当前口径**。`tacz:ump45`、`tacz:vector45` 的 CBRG data 都已指向 `cbrg:ammo_9mm`。判断弹药、容量及射击模式必须读取最终 data。
4. **语言文件不代表注册成功**。VSS、DBS、R45 都有语言名称，但本发行档没有对应枪械 index。
5. **同枪多外观只算一次**。Beryl 有 `cbrg:beryl_m762` 和 `_2`；P90 有 `origin:p90` 和 `tacz:p90`。53 个 index 不等于 53 把 PUBG 现役枪。
6. **发行档与 GitHub main 有差异**。0.4.8 的 Kar98k 是 `classicr:kar98`，模型是 `classicr:gun/kar98_geo`，本表没有发现 Gewehr98 引用；当前 main 改用 `tacz:kar98`。不得把两个快照的证据混用。
7. **作者“仅缺三把”的旧清单不是当前 PC 清单**。作者 README 提到 DBS、R45、VSS；本次固定 PC 基准中 R45 已退出世界刷新，RPD 在之后新增，因此现役缺项为 DBS、VSS、RPD。

## 退役枪械与特殊武器

以下枪械不计入上述 47 把现役常规枪械分母；如要制作某个历史版本的 PUBG，可自行另建规则集。

| 基准退役枪械 | CBRG 对应枪械 ID |
|---|---|
| Mosin Nagant | `tacz:glock_17` |
| R45 | `tacz:glock_17` |
| DP-28 | `classicr:dp28` |
| PP-19 Bizon | `cib:pp19` |
| P1911 | `tacz:glock_17` |
| QBU | `tacz:glock_17` |

铁拳被映射为 `tacz:rpg7`，实际模型是 RPG-7；这不包含 PUBG 一次性 Panzerfaust 的完整机制。十字弩、M79 烟雾发射器、迫击炮、电击枪和信号枪没有在本发行档找到同名意图的有效枪械 index；特殊武器单列，不混入常规枪械覆盖率。

ASM Abakan 不在本次固定的 PC 43.2 现役基准内，CBRG 内也未检出相关名称。不能把手游或其它模式的武器自动算作 PC 常规枪械缺项。

## 后续补齐顺序

1. 先补 VSS、DBS、RPD 的模型、动画、脚本和数据，形成当前 PC 常规枪械目录的名称意图全集。
2. 优先替换明显跨枪型资产：Mini14、P92、Skorpion、S1897；其次处理 G36C、MP5K、Micro UZI 等变体长度差异。
3. 固定目标 PUBG 版本后逐枪核验基础/扩容容量、口径、射速、模式、装填方式、伤害曲线、后坐力和配件矩阵。现有数值是 CBRG 的实现数据，不能直接标注为当前 PUBG 官方数值。
4. 特殊武器单独实现信号召唤、烟雾、部署、一次性发射等逻辑；普通枪包的模型和 JSON 不能自动产生这些游戏规则。

## 官方基准出处

- [PUBG 官方突击步枪目录](https://pubg.com/en/game-info/weapons/ar)、[DMR](https://pubg.com/en/game-info/weapons/dmr)、[SMG](https://pubg.com/en/game-info/weapons/smg)、[SR](https://pubg.com/en/game-info/weapons/sr)、[霰弹枪](https://pubg.com/en/game-info/weapons/sg)、[手枪](https://pubg.com/en/game-info/weapons/pistol)、[其它武器](https://pubg.com/en/game-info/weapons/etc)。
- [42.1 武器刷新调整](https://www.pubg.com/en/news/10179?category=patch_notes)、[42.3 新增 RPD](https://www.pubg.com/en/news/10885)、[43.2 更新公告](https://pubg.com/en/news/11136?category=patch_notes)。
- [30.1 UMP 与汤姆逊口径调整](https://pubg.com/en/news/7424)。
