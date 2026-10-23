# 弹药统一提案与立体模型目录

核对日期：2026-10-18。范围：全部424把结构完整枪械、78个ammo资产。本轮只生成研究文件，没有更改枪包、模组配置、弹药注册、模型或运行数据，也没有启动游戏。

[带图片、搜索和筛选的全表](ammo-unification.html) · [全表JSON](ammo-unification.json) · [CSV](ammo-unification.csv) · [模型目录JSON](ammo-model-inventory.json)

## 建议采用两层设计

第一层按完整弹种共享补给，如9×19、.45ACP、5.56×45、5.7×28、5.45×39、9×39。第二层统一包装/掉落物的PUBG视觉风格，但保留7.62×39、7.62×51、7.62×54R等独立ID。PUBG的7.62mm、9mm等补给桶另作可选玩法方案，不作为现实通用依据。

军民标准、霰弹壳长、具体型号、弹头变体仍须保留；M855/M995、蛇弹、爆破/燃烧等效果不因共享补给族消失。

## 明确不应当视为同一种弹

| 不应合并 | 原因 |
|---|---|
| 7.62×25 / ×38R / ×39 / ×51 / ×54R | 前缀相同不代表同一完整弹种；Nagant手枪尤其不能吃AK/FAL/SVD的步枪弹。 |
| 9×17 / ×18 / ×19 / ×39 | 手枪/冲锋枪、Makarov和VSS类弹药是不同身份。 |
| .300 Blackout / .300 Winchester Magnum / .338 Lapua | 名称数字相近或旧ID338不代表同种弹；当前tacz:338已受CBRG名称覆写影响。 |
| .50AE / .50BMG / 12.7×108 / 12.7×55 | 不能只按“12.7/.50”合并。 |
| 12Gauge / .410 / 23×75R / 未明确壳型18.4mm | 霰弹外观相似并不表示壳型和弹种相同。 |
| RPG-7 / AT4 / M72 / PF-98 / 40mm榴弹 | 发射器系统和弹体身份不同，一次性发射器还涉及道具消耗逻辑。 |

## 统计与待核验边界

```json
{
  "guns": 424,
  "ammoAssets": 78,
  "judgements": {
    "原型明显不匹配": 12,
    "PUBG抽象需拆分": 24,
    "同弹种可共享": 308,
    "待核验": 45,
    "原抽象需拆分": 8,
    "特殊不并": 23,
    "替代模型需区分": 1,
    "玩法换口径需区分": 3
  },
  "proposedFamilies": {
    "545": 12,
    "76254r": 9,
    "556": 76,
    "76251": 39,
    "独立/待核验": 70,
    "58": 26,
    "127108": 4,
    "76239": 30,
    "65arisaka": 2,
    "22wmr": 2,
    "3006": 3,
    "357": 5,
    "45acp": 14,
    "9x19": 52,
    "10auto": 3,
    "57": 8,
    "9mak": 2,
    "763": 1,
    "76225": 5,
    "58pistol": 2,
    "nagant": 1,
    "500": 1,
    "8nambu": 1,
    "32acp": 1,
    "455auto": 1,
    "9x39": 3,
    "300blk": 4,
    "12755": 1,
    "6pdw": 1,
    "68fury": 2,
    "4570": 4,
    "68tvcm": 1,
    "410": 1,
    "12g": 22,
    "23shot": 1,
    "46": 2,
    "765long": 1,
    "65cm": 1,
    "50bmg": 5,
    "416": 1,
    "408": 1,
    "792": 3
  },
  "newAmmoIds": [
    "tacz_pubg:ammo_455_webley_auto",
    "tacz_pubg:ammo_762x38r_nagant",
    "tacz_pubg:ammo_9x18_makarov",
    "tacz_pubg:ammo_at4_84mm",
    "tacz_pubg:ammo_m72_66mm"
  ],
  "sourceAmmoModels": {
    "solid3d": 46,
    "2d": 32
  },
  "targetChanges": 195,
  "newAmmoIdsStrict": [
    "tacz_pubg:ammo_455_webley_auto",
    "tacz_pubg:ammo_762x38r_nagant",
    "tacz_pubg:ammo_9x18_makarov",
    "tacz_pubg:ammo_at4_84mm",
    "tacz_pubg:ammo_m72_66mm"
  ],
  "newAmmoIdsPubgOptional": [
    "tacz_pubg:ammo_300_win_mag"
  ],
  "newAmmoIdsPendingCandidate": [
    "tacz_pubg:ammo_280_british",
    "tacz_pubg:ammo_30_30_winchester",
    "tacz_pubg:ammo_380_acp",
    "tacz_pubg:ammo_762x38r_nagant",
    "tacz_pubg:ammo_9x18_makarov"
  ],
  "allProposedNewAmmoIds": [
    "tacz_pubg:ammo_280_british",
    "tacz_pubg:ammo_300_win_mag",
    "tacz_pubg:ammo_30_30_winchester",
    "tacz_pubg:ammo_380_acp",
    "tacz_pubg:ammo_455_webley_auto",
    "tacz_pubg:ammo_762x38r_nagant",
    "tacz_pubg:ammo_9x18_makarov",
    "tacz_pubg:ammo_at4_84mm",
    "tacz_pubg:ammo_m72_66mm"
  ]
}
```

同弹种可共享是游戏设计建议，不是现实使用说明。多数现有枪按gun data的完整弹种声明归类，置信度为“原文件声明，未逐把验证现实变体”；这与逐枪现实认证有明确区别。

Win94、EM-2、多口径MRAD/AXMC/AXSR、部分历史枪、自定义CAMG武器、只有Short/Medium/Long类别的枪均保持待核验，表中候选不能自动应用。当前424不是游戏内已验证加载数量。

## 图片与建模边界

枪图和小弹药图为当前枪包的原图标。大弹药图由实际geo_models进行离线投影，证明模型几何情况，但不是游戏截图或渲染兼容性测试。凡建议新ID，图片只能称“参考模板”，不能当作新模型成品。

立体补给箱可按CBRG现有的包装风格重新制作完整口径标识和独立几何。建议新ID均未注册；公开分发前应核对原素材许可，或从零重绘原创纹理/几何。

模型风格可以共用，弹药逻辑不必共用：7.62×39、×51、×54R可用相同盒型加不同文字/弹体细节，仍保留三个独立弹药ID。TaCZ r7的IAmmo.isAmmoOfGun按gunData.ammoId与AmmoId相等判断；只换纹理不会自动兼容，多弹种兼容若未来实施需要额外逻辑或逐枪供弹目标。当前伤害、散布、弹速等主要来自枪械gun data的bullet字段，换ammo ID也不会自动得到现实弹道。

## 制作与落地可行性

可行：按PUBG补给盒的视觉方向，从零制作低模纸盒、弹托、露出的少量弹体，重绘原创纹理；多种口径可共用基础盒型，但完整弹种标识、露出弹体比例、颜色和独立ID应分别定义。无需把不合理弹种硬塞进六个CBRG补给桶。

TaCZ资源链至少包含ammo index、display、geo_models、模型UV纹理与slot图标；ground、fixed、thirdperson_hand等位置/缩放分别检查。slot是二维库存图标，不等于物品有立体建模；飞行弹体entity与弹壳shell是另外的资源链，不能拿掉落盒子模型证明它们也已制作。

未来实施时才将已审定逐枪表转为实验方案的供弹覆写，并生成配套地面弹药；本轮config里的子弹实验选项继续占位，没有修改运行行为。待核验项先保留原ID，新增tacz_pubg:*均是规划，未注册、未制作、未安装。新ID统计分别列现实合理方案、PUBG可选方案（包括独立.300WinMag）与待核验候选，避免把候选误记为完成品。

[CBRG六种立体模型总图](ammo-model-preview.png) · [全部78弹药图片目录](ammo-model-all-preview.png) · [模型与许可核对说明](弹药立体模型核对.md)

## 现实与PUBG资料来源

- [SAAMI FAQ / exact cartridge designation and .223–5.56 distinction](https://saami.org/faqs/)：同完整弹种名称是归一前提；.223 Rem 与5.56 NATO不可宣布无条件双向互换。
- [SAAMI Generally Accepted Firearms and Ammunition Interchangeability](https://saami.org/wp-content/uploads/2024/11/SAAMI-Generally-Accepted-Firearms-and-Ammunition-Interchangeability-10-8-2021.pdf)：兼容需匹配枪械具体标称弹种；不能只凭弹头直径或同一个数字判断。
- [C.I.P. TDCC cartridge catalogue](https://bobp.cip-bobp.org/en/tdcc_public)：各完整弹种单独列项；本项目据此保留弹种身份。此处不复制标准尺寸或压力图。
- [FN Herstal 5.7x28mm NATO](https://fnherstal.com/en/defence/ammunition/57x28mm/)：P90/Five-seven使用5.7×28；它不是5.56步枪弹。
- [Heckler & Koch MP7](https://www.heckler-koch.com/en/Products/Military%20and%20Law%20Enforcement/Submachine%20guns/MP7)：MP7标称4.6×30；不能因为用途与P90相近而并成5.7×28或9×19。
- [GLOCK G17 Gen5 specifications](https://gen5.glock.us/g17/)：G17标称9×19。
- [Magnum Research — Desert Eagle platform history](https://www.magnumresearch.com/company-history/)：MarkXIX有.357/.44/.429DE/.50AE具体版本，不是普通.45ACP；不要因外形相同推唯一.50。
- [Heckler & Koch UMP](https://www.heckler-und-koch.com/en/Products/Military%20and%20Law%20Enforcement/Submachine%20guns/UMP)：现行UMP页面标9×19；名称带UMP45的模型应核验具体变体，不能认为.45和9是同弹。
- [FN Herstal official SCAR family release](https://fnherstal.com/app/uploads/20210622-PRel-FN-Herstal-SOFINS-2021-FR.pdf)：SCAR-L/H分别5.56×45与7.62×51；同枪系外观不等于同弹种。
- [Beretta 93R original technical brochure reproduction](https://berettaweb.com/93R/Beretta%2093R%201.htm)：扫描原厂资料列9 mm Parabellum；镜像站并非现行Beretta官网，证据强度低于现行原厂页。
- [Springfield Armory M1A manual — ammunition](https://support.springfield-armory.com/manuals/m1a-manual?section=7oHa87Sn9qeL9voYNBcaHC&topic=5AD0ddXxK5KjmuBV353tSw)：该具体M14衍生型号认可.308/7.62×51。仅作条件兼容例，不推广到全部.308或7.62枪。
- [Winchester Model 94 overview](https://www.winchesterguns.com/products/rifles/model-94/overview.html)：Model94与30-30的历史关系；该枪家族有多口径，名字本身不能确定唯一口径。
- [Winchester 2012 official catalogue](https://www.winchesterguns.com/content/dam/winchester-repeating-arms/support/print-catalogs/2012/2012-winchester-repeatingarms-catalog.pdf)：同一Model94系列列有30-30与450Marlin等不同版本。
- [Polish Army Museum — Nagant wz. Ng30 /1895](https://muzeumwp.pl/mwpedia/rewolwer-wz-ng-30/)：Nagant左轮弹种7.62×38R，与7.62步枪弹分开。
- [Royal Armouries — Arms of the First World War](https://royalarmouries.org/objects-and-stories/stories/arms-of-the-first-world-war)：Nagant特殊7.62×38、Mosin7.62×54R及多种历史枪弹的原馆藏研究。
- [Podlaskie Museum of Technology — PM63 RAK](https://excitapolonia.pl/en/museum-of-technics/department_small_arms/pm-63)：PM63标称9×18Makarov，不是9×19。
- [Royal Museums Greenwich — Webley & Scott Mark I Automatic](https://www.rmg.co.uk/collections/objects/rmgc-object-2436)：馆藏原枪铭文.455 MarkI1913；不要按普通.45ACP合并。
- [Barrett FAQ](https://barrett.net/support/faq/)：.416Barrett是独立弹种，不能与.50BMG当成同弹种。
- [Barrett MRAD specifications](https://barrett.net/products/mrad/)：MRAD多种具体口径，不能只凭枪名判定.338或.300；不同配置不等于同一枪可直接通吃。
- [CheyTac M200 manufacturer history](https://cheytac.com/the-unmatched-legacy-of-the-m200-intervention/)：M200有.408/.375具体版本；不能并入.338/.300/.50统称狙击弹。
- [SIG SAUER 2023 defense catalogue](https://www.sigsauer.com/media/sigsauer/resources/2023_DSG_CATALOG_v1.pdf)：6.8×51Hybrid单独标称，与7.62×51和6.5Creedmoor分别列出。
- [True Velocity first-party announcement](https://www.linkedin.com/posts/truevelocityinc_less-than-nine-months-after-announcing-a-activity-7156111155373965313-sOV2)：6.8TVCM是TrueVelocity独立体系；无证据表明与SIG6.8×51Fury无条件通用。
- [Saab — Carl-Gustaf and AT4](https://www.saab.com/pt-br/markets/brasil/produtos-e-servicos/Terrestre/carl-gustaf-e-at4)：AT4为84mm一次性系统，不应装RPG7弹。
- [Nammo — M72](https://www.nammo.com/story/m72-the-mighty-jack-of-all-trades/)：M72为66mm系统，与AT4和RPG7分别保留。
- [Kalashnikov official AK74/RPK74 50th-anniversary announcement](https://t.me/s/kalashnikovnews?before=1954)：AK74及RPK74系列采用5.45×39；2024年3月18日原厂自有发布。
- [BICC SALW Guide — Norinco Type95/97](https://salw-guide.bicc.de/pdf/weapons/104/norinco-type-95-97.min.en.pdf)：Type95标5.8×42、Type97标5.56×45；是研究机构直接汇编，非原厂确认当前模组变体。
- [ARES original research — .280 British](https://armamentresearch.com/a-cartridge-in-brief-280-british/)：原型EM2与7×43/.280British有关；模组EM2若代表其他试制版本仍须核验。
- [PUBG official Update30.1](https://pubg.com/en/news/7424?category=patch_notes)：2024年6月世界刷新SMG统一9mm，明确TommyGun、UMP从.45ACP改9mm。
- [PUBG official SMG rebalance developer letter](https://pubg.com/en/news/7426)：解释SMG统一9mm是为了弹药补给和玩法便利。
- [PUBG official Update18.2 O12](https://pubg.com/en/news/1741)：O12使用独立12GaugeSlug；即使同12号霰弹枪也应区分弹丸行为。
- [PUBG official Update13.2 P90](https://pubg.com/zh-cn/news/4568)：P90采用独有5.7mm弹药。
- [PUBG official Update12.2 TDM equipment](https://www.pubg.com/en/news/1740)：K2/Mk12给5.56，DesertEagle给45ACP；是玩法口径映射证据。
- [PUBG official Lynx introduction12.1](https://www.pubg.com/en/news/1743)：Lynx为.50且不可装填；该旧文的5发数量不是本报告声称的当前容量。
- [PUBG official Update36.1](https://www.pubg.com/en-asia/news/8725?category=patch_notes)：开发者将VSS称为9mmDMR；这是游戏桶，不是现实9×19。
- [本地全部枪械原始data与原图](../gun-balance/gun-assets.json)：当前实际ammo字段、图标、名称与所引用的原数据。
- [本地78个ammo索引与模型检查](ammo-model-inventory.json)：当前ammo ID、显示名及3D/2D实际模型。
- [CBRG与PUBG逐项替代资产对照](../PUBG枪械逐项对照.md)：当前名称意图与模型替代不一致的边界。
- [KRISS Vector原厂规格表：SMG9与SMG45](https://store.kriss-usa.com/download/KRISS_VECTOR_G2/2022_KV_G2_SMG_DS.pdf)：分别列VectorSMG9=9×19、VectorSMG45=.45ACP。
- [Auto-Ordnance原厂Thompson资料](https://www.auto-ordnance.com/PDF/GAAP-190600.pdf)：M1标.45ACP，叙述M1A1；本地M1A1模型与PUBG9mm玩法区别。
- [HK 2000原厂价目手册（原文镜像）](https://s3.amazonaws.com/hk-manuals/files/Catalogs/Price_Lists/2000_HK_LEO_Pricelist.pdf)：页2明确UMP45=.45ACP；镜像域非现行原厂域，不能推现行UMP9也用.45。
- [DIA/US Army公开识别手册DST-1110H-394-76（档案镜像）](https://mail.bulletpicker.com/pdf/DST-1110H-394-76.pdf)：SectionI识别MakarovPM为9×18；只引用弹种身份，不引用操作流程。

## 全部424把枪的文字对应

| 枪械 | 当前弹药 | 建议完整弹种 | 建议ID | 判断 |
|---|---|---|---|---|
| 黑市RPK74 `gz:black_market_rpk74` | `tacz:762x39` | 5.45×39mm | `tacz:545x39` | 原型明显不匹配 |
| 经典RPK74 `gz:classic_rpk74` | `tacz:762x39` | 5.45×39mm | `tacz:545x39` | 原型明显不匹配 |
| DP-28 `classicr:dp28` | `cbrg:ammo_762` | 7.62×54mmR | `tacz:762x54r` | PUBG抽象需拆分 |
| FN EVOLYS 机枪 `tacz:fn_evolys` | `cbrg:ammo_556` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 通用电气 \| M134 转管机枪 `classicr:minigun` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| HK \| HK21 `ccrp:hk21` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| 内格夫 NG5 轻机枪 `cib:negev` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| L86A2 LSW `rainforest:l86a2` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M134 迷你炮机枪 `cib:mini` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| M134 转管机枪 `tacz:minigun` | `cbrg:ammo_762` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | PUBG抽象需拆分 |
| M249 `tacz:m249` | `cbrg:ammo_556` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M249丨STANAG供弹具 `ccrp:m249_saw` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M27 IAR 班用机枪 `ccrp:m27_iar` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M60 `rainforest:m60` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| M60 通用机枪 `classicr:m60` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| MG3 `cib:mg3` | `cbrg:ammo_762` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | PUBG抽象需拆分 |
| 麦德森轻机枪 `hamster:madsen` | `hamster:long_ammo` | 长子弹（完整弹种待核，保持独立） | `hamster:long_ammo` | 待核验 |
| PKP “佩切涅格” 轻机枪 `suffuse:pkp` | `tacz:762x54` | 7.62×54mmR | `tacz:762x54r` | 同弹种可共享 |
| 建设工业 QJB-201 班用机枪 `cib:qjb201` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| 北方工业 QJB-95-1 班用机枪 `cib:qjb951` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| 建设工业 QJB-201 班用机枪丨SF-2403 `cibs:qjb201_sf2403` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| QJB95-1 轻机枪丨长弓套件 `ccrp:type_95_longbow` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| 建设工业 QJY-201 通用机枪 `cib:qjy201` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| 北方工业 QJY-88 通用机枪 `cib:qjy88` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| 建设工业 QJZ-171 重机枪 `cib:qjz171` | `cib:127x108` | 12.7×108mm | `suffuse:12.7x108mm` | 同弹种可共享 |
| RPD `rainforest:rpd` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| RPD-MS `rainforest:rpdms` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| RPK 轻机枪 `tacz:rpk` | `cbrg:ammo_762` | 7.62×39mm | `tacz:762x39` | PUBG抽象需拆分 |
| 斯泰尔丨AUG HBAR `ccrp:aug_hbar` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 大正十一年式 轻机枪 `cib:type11` | `cib:65x50` | 6.5×50mmSR Arisaka | `cib:65x50` | 同弹种可共享 |
| 朝鲜 73式 机枪 `cib:type73` | `tacz:762x54` | 7.62×54mmR | `tacz:762x54r` | 同弹种可共享 |
| 老兵 `gz:t81lmg` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| PKP “佩切涅格” 机枪 `cib:pkp` | `tacz:762x54` | 7.62×54mmR | `tacz:762x54r` | 同弹种可共享 |
| 帕拉贝鲁姆 MG1914/17机枪 `hamster:mg1417` | `hamster:long_ammo` | 长子弹（完整弹种待核，保持独立） | `hamster:long_ammo` | 待核验 |
|  TAC \| 永恒 .50 L `ccrp:timeless_50` | `tacz:50ae` | 保留原声明，具体变体待核：.50 AE 手枪弹 | `tacz:50ae` | 待核验 |
| .22 943型 左轮手枪 `tacz:taurus943` | `tacz:22wmr` | .22 WMR（5.6×27mmR） | `tacz:22wmr` | 同弹种可共享 |
| .30-06 孤星 手炮 `tacz:lonetrail` | `tacz:30_06` | .30-06 Springfield（7.62×63mm） | `tacz:30_06` | 同弹种可共享 |
| .357 犀牛 左轮手枪 `tacz:rhino357` | `tacz:357mag` | .357 Magnum（9×33mmR） | `tacz:357mag` | 同弹种可共享 |
| Arsenal \| AF2011 `ccrp:af2011` | `tacz:45acp` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 同弹种可共享 |
| B93R 冲锋手枪 `tacz:b93r` | `cbrg:ammo_45acp` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 原型明显不匹配 |
| 伯莱塔 M93 Raffica `classicr:b93r` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 伯莱塔 M92FS `classicr:m92fs` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 柯尔特M1851转换型转轮手枪 `hamster:coltm1851` | `hamster:compact_ammo` | 短子弹（完整弹种待核，保持独立） | `hamster:compact_ammo` | 待核验 |
| 柯尔特M1873“维和者”转轮手枪 `hamster:colt1873` | `hamster:medium_ammo` | 中子弹（完整弹种待核，保持独立） | `hamster:medium_ammo` | 待核验 |
| 柯尔特M1892 “新军”转轮手枪 `hamster:coltm1892` | `hamster:compact_ammo` | 短子弹（完整弹种待核，保持独立） | `hamster:compact_ammo` | 待核验 |
| 柯尔特 蟒蛇 `classicr:colt_python` | `tacz:357mag` | .357 Magnum（9×33mmR） | `tacz:357mag` | 同弹种可共享 |
| 沙漠之鹰 `tacz:deagle` | `cbrg:ammo_45acp` | 保留原声明，具体变体待核：.45 口径子弹 | `cbrg:ammo_45acp` | 待核验 |
| Detonics丨战斗大师1911 `ccrp:chisato_m1911` | `tacz:45acp` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 同弹种可共享 |
| FORT \| VantVM战术突击盾 配装 OTS-33 冲锋手枪 `ccrp:shield_ots33` | `tacz:9mm` | 保留原声明，具体变体待核：9mm 手枪弹 | `tacz:9mm` | 待核验 |
| Fightlite \| SCR掠夺者 手枪 `ccrp:fightlite_scr_hg` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 格洛克 17 手枪 `tacz:glock_17` | `cbrg:ammo_45acp` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 原型明显不匹配 |
| 格洛克 18c 冲锋手枪 `classicr:glock_18` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 格洛克 19 手枪 `cib:g19` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| Glock \| G20 gen5 mos `ccrp:glock20gen5mos` | `tacz:10mm` | 10mm Auto（10×25mm） | `tacz:10mm` | 同弹种可共享 |
| Glock \| G40 gen5 mos `ccrp:glock40gen5mos` | `tacz:10mm` | 10mm Auto（10×25mm） | `tacz:10mm` | 同弹种可共享 |
| Glock17丨波洛克 `ccrp:block_17` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| .357 黄金沙漠之鹰 `tacz:deagle_golden` | `cbrg:ammo_45acp` | .357 Magnum（9×33mmR） | `tacz:357mag` | 原型明显不匹配 |
| HK USP45 手枪 黑色款 `suffuse:usp45_black` | `tacz:45acp` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 同弹种可共享 |
| HK USP45 手枪 沙色款 `suffuse:usp45` | `tacz:45acp` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 同弹种可共享 |
| HK-USP .45ACP 战术型手枪 `cib:usp` | `tacz:45acp` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 同弹种可共享 |
| HK-USP .45ACP 战术型手枪丨不锈钢 `cibs:usps` | `tacz:45acp` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 同弹种可共享 |
| 英格拉姆 M10 冲锋枪 `classicr:mac10` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 英特拉泰克 Tec-9 `classicr:tec_9` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| KelTec丨P50 `ccrp:keltec_p50` | `tacz:57x28` | 5.7×28mm | `cbrg:ammo_57mm` | 同弹种可共享 |
| LifeCard 单发折叠手枪 `suffuse:lifecard` | `suffuse:.22wmr` | .22 WMR（5.6×27mmR） | `tacz:22wmr` | 同弹种可共享 |
| 鲁格P08手枪 `hamster:lugerp08` | `hamster:compact_ammo` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 原抽象需拆分 |
| M1879“帝国”转轮手枪 `hamster:m1879revolver` | `hamster:medium_ammo` | 中子弹（完整弹种待核，保持独立） | `hamster:medium_ammo` | 待核验 |
| M327-TRR8 转轮手枪 `cib:r8` | `tacz:357mag` | .357 Magnum（9×33mmR） | `tacz:357mag` | 同弹种可共享 |
| 伯莱塔 M9A4 手枪 `tacz:m9a4` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| MK23 进攻手枪 `tacz:hk_mk23` | `tacz:45acp` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 同弹种可共享 |
| 马卡洛夫PM手枪 `hamster:makarov` | `hamster:compact_ammo` | 9×18mm Makarov | `tacz_pubg:ammo_9x18_makarov` | 原抽象需拆分 |
| 毛瑟 \| C96 `classicr:mauser_c96` | `tacz:763mauser` | 7.63×25mm Mauser | `tacz:763mauser` | 同弹种可共享 |
| 迈卡的佩枪 `hamster:coltm1892pair` | `hamster:compact_ammo` | 短子弹（完整弹种待核，保持独立） | `hamster:compact_ammo` | 待核验 |
| “致命灵药” `hamster:nagantcarbine` | `hamster:compact_ammo` | 保留原声明，具体变体待核：短子弹 | `hamster:compact_ammo` | 待核验 |
| 北方工业 NP762型 自动手枪 `suffuse:np762` | `tacz:762x25` | 7.62×25mm Tokarev | `tacz:762x25` | 同弹种可共享 |
| P18C `cib:g18c` | `cbrg:ammo_9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| P1911 `tacz:m1911` | `cbrg:ammo_45acp` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 同弹种可共享 |
| P92 `tacz:p320` | `cbrg:ammo_9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| Python Colt “蟒蛇” 左轮手枪 `suffuse:python` | `tacz:357mag` | .357 Magnum（9×33mmR） | `tacz:357mag` | 同弹种可共享 |
| 北方工业 QSZ-92 手枪 `cib:qsz92` | `cib:58x21` | 5.8×21mm | `cib:58x21` | 同弹种可共享 |
| 建设工业 QSZ-193 手枪 `cib:qsz193` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| R1895 `hamster:nagantm1895` | `cbrg:ammo_762` | 7.62×38mmR Nagant | `tacz_pubg:ammo_762x38r_nagant` | 原型明显不匹配 |
| S&W.455 MKII `hamster:sw_mk2` | `hamster:medium_ammo` | 中子弹（完整弹种待核，保持独立） | `hamster:medium_ammo` | 待核验 |
| S&W.455 MKII `hamster:sw_mk2_41` | `hamster:medium_ammo` | 中子弹（完整弹种待核，保持独立） | `hamster:medium_ammo` | 待核验 |
| 西格绍尔 SIG-P250 手枪 `cib:p250` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| STI 2011 `classicr:sti2011` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 蝎式手枪 `tacz:cz75` | `cbrg:ammo_9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| TT-33 手枪 `suffuse:tt33` | `tacz:762x25` | 7.62×25mm Tokarev | `tacz:762x25` | 同弹种可共享 |
| 塔兰战术 TTI G34 `classicr:tti_g34` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| TTI Pit Viper “蝮蛇” 手枪 `suffuse:tti2011` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 金牛座 "狂暴猎手" 手炮 `tacz:taurus500` | `tacz:500mag` | .500 S&W Magnum（12.7×41mmR） | `tacz:500mag` | 同弹种可共享 |
| Tec9 冲锋手枪 `suffuse:tec9` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 永恒 .50 Z型 `tacz:timeless50` | `cbrg:ammo_45acp` | 保留原声明，具体变体待核：.45 口径子弹 | `cbrg:ammo_45acp` | 待核验 |
| Traditions Trapper 50口径 火帽枪 `suffuse:trapper50cal` | `suffuse:boomstickshot` | .50 纸壳定装弹（完整弹种待核，保持独立） | `suffuse:boomstickshot` | 特殊不并 |
| 南部十四手枪 `cib:type14` | `cib:8x22` | 8×22mm Nambu | `cib:8x22` | 同弹种可共享 |
| Viper 2011 “蝰蛇” 手枪 `suffuse:viper2011` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 瓦尔特 PPK 手枪 `cib:ppk` | `cib:32acp` | .32 ACP（7.65×17mmSR） | `cib:32acp` | 同弹种可共享 |
| 韦伯利信号枪 `hamster:flaregun` | `hamster:flares_ammo` | 信号弹（完整弹种待核，保持独立） | `hamster:flares_ammo` | 特殊不并 |
| Webley Mk. 1 自动装填手枪 `suffuse:webley1913` | `tacz:45acp` | .455 Webley Auto（11.56×23mmSR） | `tacz_pubg:ammo_455_webley_auto` | 原型明显不匹配 |
| 韦伯利MK.VI转轮手枪 `hamster:webley` | `hamster:medium_ammo` | 中子弹（完整弹种待核，保持独立） | `hamster:medium_ammo` | 待核验 |
| l爱国者 `gz:patriots` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 9a-91 小型突击步枪 `cib:9a91` | `cib:9x39mm` | 9×39mm | `tacz:9x39` | 同弹种可共享 |
| A.R.M.S&CAMG \| M4 SIR Systems `ccrp:m4_ss` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| AAC丨蜜獾 突击步枪 `ccrp:honeybadger` | `tacz:300blk` | .300 AAC Blackout（7.62×35mm） | `tacz:300blk` | 同弹种可共享 |
| ACE32 `cib:galilace32` | `cbrg:ammo_762` | 7.62×39mm | `tacz:762x39` | PUBG抽象需拆分 |
| AK103 突击步枪 `cib:ak103` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| AK103 突击步枪丨碧蓝航线 联名(拉菲) `cibs:ak103_laffey` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| AK105 短管突击步枪 `cib:ak105` | `tacz:545x39` | 5.45×39mm | `tacz:545x39` | 同弹种可共享 |
| AK105 短管突击步枪丨明日方舟 联名(凯尔希) `cibs:ak105_kaltsit` | `tacz:545x39` | 5.45×39mm | `tacz:545x39` | 同弹种可共享 |
| AK12 突击步枪 `classicr:ak12` | `tacz:545x39` | 5.45×39mm | `tacz:545x39` | 同弹种可共享 |
| 苏联 AK-47 突击步枪 `cib:cs_ak47` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| AK47丨英雄之殁 `ccrp:ak47_spent_bullet` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| AKM `tacz:ak47` | `cbrg:ammo_762` | 7.62×39mm | `tacz:762x39` | PUBG抽象需拆分 |
| AKs74u 突击步枪 `suffuse:aks74u` | `suffuse:545x39` | 5.45×39mm | `tacz:545x39` | 同弹种可共享 |
| 卡拉什尼科夫 \| AM-17 短突击步枪 `ccrp:am17` | `tacz:545x39` | 5.45×39mm | `tacz:545x39` | 同弹种可共享 |
| AN94 突击步枪 `suffuse:an94` | `suffuse:545x39` | 5.45×39mm | `tacz:545x39` | 同弹种可共享 |
| AS Val 特种突击步枪 `cib:asval` | `cib:9x39mm` | 9×39mm | `tacz:9x39` | 同弹种可共享 |
| ASH12 突击步枪 `suffuse:ash12` | `suffuse:12.7x55` | 12.7×55mm | `suffuse:12.7x55` | 同弹种可共享 |
| AUG `tacz:aug` | `cbrg:ammo_556` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| AUG A3-CQC `gz:auga3cqc` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 陨铁之鞴「原初之火{Aestus estus)」 `gz:arx200_nero` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| 甲胄战术 \| QBZ-191突击步枪 轩辕套件 `ccrp:type_192` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| Beryl M762 `cbrg:beryl_m762` | `cbrg:ammo_762` | 7.62×39mm | `tacz:762x39` | PUBG抽象需拆分 |
| Beryl M762 `cbrg:beryl_m762_2` | `cbrg:ammo_762` | 7.62×39mm | `tacz:762x39` | PUBG抽象需拆分 |
| 黑市AKM `gz:black_market_akm` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| 齐玛军械 \| Rättvisa V308 战斗步枪 `ccrp:v308` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| 齐玛军械 \| AUGA3 狂猎套件 `ccrp:aug_camg_kit` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 齐玛军械 \| Krait 重型战斗步枪 `ccrp:camg_krait` | `ccrp:68x51tvcm` | 保留原声明，具体变体待核：6.8x51mm TVCM聚合物弹药 | `ccrp:68x51tvcm` | 待核验 |
| 齐玛军械 \| MK18突击步枪 `ccrp:camg_mk18` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 限定典藏 \|【缄默彗星】 `ccrp:silence_meteor` | `tacz:300blk` | .300 AAC Blackout（7.62×35mm） | `tacz:300blk` | 同弹种可共享 |
| 齐玛军械&斯泰尔 \| AUG-SD `ccrp:aug_a3s` | `tacz:300blk` | 保留原声明，具体变体待核：.300 Blackout 步枪弹 | `tacz:300blk` | 待核验 |
| CS/LR 42A 短突击步枪 `ccrp:cslr_42a` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| CS/LR 43A 短突击步枪 `ccrp:cslr_43a` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| CS/LR 44 精确射手步枪 `ccrp:cslr_44` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| 定制P416 G3 `gz:custom_p416_g3` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| DSA SA58 `classicr:fn_fal_tac` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| 大宇精密 K2C1 突击步枪 `cib:k2c1` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 丹尼尔防务 \| DDM4 PDW个人防卫武器 `ccrp:ddm4_pdw` | `tacz:300blk` | .300 AAC Blackout（7.62×35mm） | `tacz:300blk` | 同弹种可共享 |
| 丹尼尔防务 \| DDM4 V7突击步枪 `ccrp:ddm4` | `ccrp:556x45_m995` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 丹尼尔防务 \| DDM4 V7 Pro突击步枪 `ccrp:ddm4_v7_pro` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 德拉贡诺夫 `suffuse:svd` | `cbrg:ammo_762` | 7.62×54mmR | `tacz:762x54r` | PUBG抽象需拆分 |
| EM-2 `rainforest:em2` | `tacz:308` | 保留原声明，具体变体待核：.308 温彻斯特 步枪弹 | `tacz:308` | 待核验 |
| FAMAS `rainforest:famas` | `cbrg:ammo_556` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| FN赫斯塔尔&X-Product \| SCAR X-17S 战斗步枪 `ccrp:scar_17s` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| FN FAL 战斗步枪 `cib:fal` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| FN FAL `rainforest:fal` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| FN FAL 战斗步枪 `tacz:fn_fal` | `cbrg:ammo_762` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | PUBG抽象需拆分 |
| FN赫斯塔尔&Tangodown \| SCAR-16S 突击步枪 `ccrp:scar_16s` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| FN赫斯塔尔 \| SCAR SC紧凑型卡宾枪 `ccrp:scar_sc` | `tacz:300blk` | 保留原声明，具体变体待核：.300 Blackout 步枪弹 | `tacz:300blk` | 待核验 |
| G36C `tacz:g36k` | `cbrg:ammo_556` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 加利尔 ACE-32 突击步枪丨LESHUAN_20 乐拴 `cibs:galilace_lesh` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| Groza `cbrg:groza` | `cbrg:ammo_762` | 7.62×39mm | `tacz:762x39` | PUBG抽象需拆分 |
| HAENEL \| CR300 `ccrp:cr300` | `tacz:300blk` | .300 AAC Blackout（7.62×35mm） | `tacz:300blk` | 同弹种可共享 |
| HAENEL \| MK556 `ccrp:mk556` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| G3 战斗步枪 `tacz:hk_g3` | `cbrg:ammo_762` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | PUBG抽象需拆分 |
| HK SPG1 `rainforest:hk_spg1` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| HK \| G95A1 突击步枪 `ccrp:g95a1` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| HK \| HK416 `ccrp:hk416` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| HK \| HK416A8 `ccrp:hk416a8` | `ccrp:556x45_m855` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| HK \| HK417 `ccrp:hk417` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| HK \| HK433 突击步枪 `ccrp:hk433` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| HK416 A5 突击步枪 `classicr:hk416_a5` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| HK416C 短突击步枪 `ccrp:hk416c` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| HK433 突击步枪 `cib:hk433` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 丰和工业 20式 突击步枪 `cib:type20` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 丰和工业 20式 突击步枪丨舰队Collection 联名(响) `cibs:type20_hibiki` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 加利尔 ARM 突击步枪 `cib:galil` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 加利尔 ACE-22 突击步枪 `cib:galilace` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| IWI丨X95 `ccrp:x95r` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| K2 `cib:k2` | `cbrg:ammo_556` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| KAC PDW 个人防卫武器 `suffuse:kacpdw` | `suffuse:6x35mm` | 6×35mm KAC | `suffuse:6x35mm` | 同弹种可共享 |
| L85A1 IW `rainforest:l85a1` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| LA-89式 突击步枪 `cib:la89` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| LVOA_C `gz:lvoa_c` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 轻型M4 `gz:lightweight_m4` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 利特高 \| F90卡宾枪 `ccrp:f90` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 利特高 \| F90 MBR 突击步枪 `ccrp:f90_mbr` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M16A1 制式步枪 `tacz:m16a1` | `cbrg:ammo_556` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M16A2 `gz:m16a2` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M16A4 自动步枪 `cib:m16a4` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M16A4 `tacz:m16a4` | `cbrg:ammo_556` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M38 SPR 精确射手步枪 `ccrp:m38_spr` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M4 URGI 突击步枪 `cib:m4` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M4 URGI 突击步枪丨笨笨小狼 `cibs:m4_koei` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M416 `tacz:hk416d` | `cbrg:ammo_556` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M4A1 卡宾枪 `tacz:m4a1` | `cbrg:ammo_556` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| M7 战斗步枪 `classicr:ngsw_r_renewed` | `tacz:68x51fury` | .277 SIG Fury / 6.8×51mm（军商标准另核） | `tacz:68x51fury` | 同弹种可共享 |
| SIG&CAMG \| M7 飞龙套件 `ccrp:sig277` | `tacz:68x51fury` | .277 SIG Fury / 6.8×51mm（军商标准另核） | `tacz:68x51fury` | 同弹种可共享 |
| SIG \| MCX Virtus 突击步枪 `ccrp:mcx_virtus` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| MK18 MOD 1 突击步枪 `cib:mk18` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| MK18 MOD 1 突击步枪丨JIANJIU 建九 `cibs:mk18_jianjiu` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| MK18 Mod1 突击步枪 `classicr:mk18_mod1` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 马盖普 PDR 个人防卫武器 `suffuse:mpdr` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 马林 M1895 杠杆步枪 `suffuse:m1895` | `tacz:45_70` | .45-70 Government（11.63×53mmR） | `tacz:45_70` | 同弹种可共享 |
| 马林 \| Model 1895 `ccrp:marlin_1895` | `tacz:45_70` | .45-70 Government（11.63×53mmR） | `tacz:45_70` | 同弹种可共享 |
| 军规AK74 `gz:military_ak74` | `tacz:762x39` | 5.45×39mm | `tacz:545x39` | 原型明显不匹配 |
| 军规P416 `gz:military_p416` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| Mini14 `ccrp:m14_hbar` | `cbrg:ammo_556` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 替代模型需区分 |
| Mk12 `tacz:spr15hb` | `cbrg:ammo_556` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| Mk14 `tacz:mk14` | `cbrg:ammo_762` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | PUBG抽象需拆分 |
| Mk47 Mutant `classicr:mk47` | `cbrg:ammo_762` | 7.62×39mm | `tacz:762x39` | PUBG抽象需拆分 |
| NSWC \| Mk.18 Mod 0 CQBR `ccrp:m4_cqbr` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 北方工业 \| M305A `ccrp:m305a` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| 北方工业 \| 第二代 97式突击步枪 `ccrp:type_97_gen2` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| Noveske N4 突击步枪 `suffuse:n4` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| OSIPR AR2步枪丨半条命2 `cib:ar2` | `cib:battery` | 暗能量电池弹（完整弹种待核，保持独立） | `cib:battery` | 特殊不并 |
| OTs-14-1A “闪电-1” 突击步枪 `cib:ots14` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| 警用M4 `gz:police_m4` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| QBU `cib:qbu88` | `cbrg:ammo_556` | 5.8×42mm | `tacz:58x42` | PUBG抽象需拆分 |
| 建设工业 QBU-191 精确射手步枪 `cib:qbu191` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| QBU-191精确射手步枪 `ccrp:qbu_191` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| QBU-191 精确射手步枪 `suffuse:qbu191` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| QBZ `tacz:qbz_95` | `cbrg:ammo_556` | 5.8×42mm | `tacz:58x42` | PUBG抽象需拆分 |
| 北方工业 QBZ-95-1 自动步枪 `cib:qbz951` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| 北方工业 QBZ-03 自动步枪 `cib:qbz03` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| 建设工业 QBZ-191 突击步枪 `cib:qbz191` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| QBZ-191 突击步枪 `suffuse:qbz191` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| 191式 突击步枪 `tacz:qbz_191` | `cbrg:ammo_556` | 5.8×42mm | `tacz:58x42` | PUBG抽象需拆分 |
| 建设工业 QBZ-191 突击步枪丨最优质的战士 `cibs:qbz191_warrior` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| QBZ-191突击步枪 `ccrp:qbz_191` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| 建设工业 QBZ-192 短管突击步枪 `cib:qbz192` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| QBZ-192 突击步枪 `classicr:qbz191_renewed` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| QBZ-192 短突击步枪 `suffuse:qbz192` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| QBZ-95-1 突击步枪 `suffuse:qbz951` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| QBZ-95-1 战术改件 突击步枪 `suffuse:qbz951s` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| 北方工业 QBZ-95-1 自动步枪丨二西莫夫 `cibs:qbz951_asiimov` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| 北方工业 QBZ-95B-1 短管自动步枪 `cib:qbz95b1` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| RM277 突击步枪 `suffuse:rm277` | `suffuse:6.8tvcm` | 6.8×51mm TVCM 聚合物壳（独立） | `ccrp:68x51tvcm` | 同弹种可共享 |
| Rifle Dynamics \| RD704 `ccrp:rd704` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| SAI \| GRY 突击步枪 `ccrp:sai_gry_lite_black` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| SAKO AK24 突击步枪 `cib:ak24` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| SAKO AK24 突击步枪丨明日方舟 联名(德克萨斯) `cibs:ak24_texas` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| SCAR-H 战斗步枪 `tacz:scar_h` | `cbrg:ammo_762` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | PUBG抽象需拆分 |
| SCAR-L `tacz:scar_l` | `cbrg:ammo_556` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| SIG XM7 突击步枪 `suffuse:xm7` | `tacz:308` | 保留原声明，具体变体待核：.308 温彻斯特 步枪弹 | `tacz:308` | 待核验 |
| SIG&CAMG丨M7【墓石】套件 `ccrp:mcx_spear_tombstone` | `tacz:68x51fury` | 保留原声明，具体变体待核：6.8x51mm 步枪弹 | `tacz:68x51fury` | 待核验 |
| 西格绍尔 SIG-552 短管突击步枪 `cib:sig552` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 西格绍尔 SIG-556 突击步枪 `cib:sig556` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 西格绍尔 SIG-556 突击步枪丨蔚蓝档案 联名(砂狼白子) `cibs:sig556_shiroko` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| SKS `tacz:sks_tactical` | `cbrg:ammo_762` | 7.62×39mm | `tacz:762x39` | PUBG抽象需拆分 |
| SKS半自动步枪 `hamster:sks` | `hamster:medium_ammo` | 7.62×39mm | `tacz:762x39` | 原抽象需拆分 |
| 自动装填步枪 `cbrg:slr` | `cbrg:ammo_762` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | PUBG抽象需拆分 |
| SOCOM&HK \| HK416 CQB SOPMOD `ccrp:hk416_sopmod` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 射手座 SPR-15 特种用途步枪 `classicr:spr_15` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 萨达姆的黄金AK `suffuse:saddam_golden_ak` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| 圣艾尔摩引擎 `gz:m4_exotic_t2` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| Steyr \| AUGA3 DMR `ccrp:aug_a3_dmr` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| 斯泰尔 \| AUGA3 M2 `ccrp:aug_a3_m2kit` | `ccrp:556x45_m995` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| T81S `gz:t81s` | `tacz:556x45` | 保留原声明，具体变体待核：5.56x45mm 步枪弹 | `tacz:556x45` | 待核验 |
| T91 突击步枪 `cib:t91` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| T97NSR G3 `gz:t97nsrg3` | `tacz:58x42` | 5.56×45mm | `cbrg:ammo_556` | 原型明显不匹配 |
| 战术T81S `gz:tactical_t81s` | `tacz:556x45` | 保留原声明，具体变体待核：5.56x45mm 步枪弹 | `tacz:556x45` | 待核验 |
| Type 56 `rainforest:56` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| 北方工业 56-1式 自动步枪 `cib:type56` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| 北方工业 56-1式 自动步枪丨镀铬 `cibs:type56_commemorate` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| 81-1式 制式步枪 `tacz:type_81` | `cbrg:ammo_762` | 7.62×39mm | `tacz:762x39` | PUBG抽象需拆分 |
| 88-2式 突击步枪 `cib:881` | `tacz:545x39` | 5.45×39mm | `tacz:545x39` | 同弹种可共享 |
| 88-2式 弹筒突击步枪 `cib:882` | `tacz:545x39` | 5.45×39mm | `tacz:545x39` | 同弹种可共享 |
| cib.gun.error.name `cib:error` | `cib:error` | cib:error（完整弹种待核，保持独立） | `cib:error` | 特殊不并 |
| 迅鹰之人 `gz:m4_exotic_t1` | `tacz:556x45` | 5.56×45mm | `cbrg:ammo_556` | 同弹种可共享 |
| AT4发射器 `rainforest:at4` | `tacz:rpg_rocket` | AT4 84mm专用弹（一次性发射器系统） | `tacz_pubg:ammo_at4_84mm` | 原型明显不匹配 |
| 朝鲜火箭榴弹发射器 `cib:dprkrpg` | `tacz:rpg_rocket` | RPG-7 火箭弹（完整弹种待核，保持独立） | `tacz:rpg_rocket` | 特殊不并 |
| 北方工业 DZJ-08 火箭发射器 `cib:dzj08` | `cib:80` | 80mm 多用途攻坚弹（完整弹种待核，保持独立） | `cib:80` | 特殊不并 |
| M203 榴弹发射器 `suffuse:m203` | `tacz:40mm` | 40mm 榴弹（完整弹种待核，保持独立） | `tacz:40mm` | 特殊不并 |
| M320 榴弹发射器 `tacz:m320` | `tacz:40mm` | 40mm 榴弹（完整弹种待核，保持独立） | `tacz:40mm` | 特殊不并 |
| M72 LAW `rainforest:m72` | `tacz:rpg_rocket` | M72 66mm专用弹（一次性发射器系统） | `tacz_pubg:ammo_m72_66mm` | 原型明显不匹配 |
| M79 40毫米 榴弹发射器 `suffuse:m79` | `tacz:40mm` | 40mm 榴弹（完整弹种待核，保持独立） | `tacz:40mm` | 特殊不并 |
| MGL 榴弹发射器 `classicr:mgl_40mm` | `tacz:40mm` | 40mm 榴弹（完整弹种待核，保持独立） | `tacz:40mm` | 特殊不并 |
| PF-98A 120毫米 火箭发射器 `suffuse:pf98a` | `suffuse:120mm` | 120mm PF-98A多用途火箭弹（完整弹种待核，保持独立） | `suffuse:120mm` | 特殊不并 |
| 铁拳火箭筒 `tacz:rpg7` | `tacz:rpg_rocket` | Panzerfaust玩法替代 / RPG-7原资产：型号待核 | `tacz:rpg_rocket` | 特殊不并 |
| QLU-11 狙击榴弹发射器 `suffuse:qlu11` | `suffuse:35x32mm` | 35x32mm 榴弹（完整弹种待核，保持独立） | `suffuse:35x32mm` | 特殊不并 |
| 资江机器 QLU-11丨QLU-131 狙击榴弹发射器 `cib:qlu11` | `cib:35mm` | 35mm 榴弹（完整弹种待核，保持独立） | `cib:35mm` | 特殊不并 |
| RPG-7 亚辛 105毫米 火箭发射器 `suffuse:aiyasinrpg` | `tacz:rpg_rocket` | RPG-7 火箭弹（完整弹种待核，保持独立） | `tacz:rpg_rocket` | 特殊不并 |
| AA-410 突击霰弹枪 `classicr:aa410` | `tacz:410_bore` | .410 Bore（壳长另核） | `tacz:410_bore` | 同弹种可共享 |
| AA12 霰弹枪 `tacz:aa12` | `cbrg:ammo_12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| 伯奈利 NOVA “新星” 霰弹枪 `cib:nova` | `tacz:12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| 伯奈利 \| M4 Super90 & CAMG巨齿鲨套件 `ccrp:camg_m1014` | `tacz:12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| 勃朗宁 AUTO-5自动霰弹枪 `hamster:auto5` | `tacz:12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| DB-4 乌萨斯 `tacz:db_long` | `cbrg:ammo_12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| 弗兰基 SPAS-12 多用途霰弹枪 `suffuse:spas12` | `tacz:12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| 吉尔伯特/大宇精密 USAS-12 自动霰弹枪 `cib:usas12` | `tacz:12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| 雄鹰集团 97-1 霰弹枪 `cib:hawk97_1` | `cib:18.4` | 18.4mm 钨合金霰弹（完整弹种待核，保持独立） | `cib:18.4` | 特殊不并 |
| 雄鹰集团 97-2 霰弹枪 `cib:hawk97_2` | `cib:18.4` | 18.4mm 钨合金霰弹（完整弹种待核，保持独立） | `cib:18.4` | 特殊不并 |
| 雄鹰集团 97-2 霰弹枪丨乌金烈芒 `cibs:hawk97_2_gold` | `cib:18.4` | 18.4mm 钨合金霰弹（完整弹种待核，保持独立） | `cib:18.4` | 特殊不并 |
| 击锤式霰弹枪 `hamster:one_barrel` | `tacz:12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| KS-23M 霰弹枪 `suffuse:ks23m` | `suffuse:23mm` | 23×75mmR KS-23 霰弹（独立） | `suffuse:23mm` | 同弹种可共享 |
| M1014 战斗霰弹枪 `tacz:m1014` | `cbrg:ammo_12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| O12 `cib:origin12` | `cbrg:ammo_12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| Origin-12 半自动霰弹枪（60发全自动龙息弹） `cib:origin12db` | `tacz:12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| 建设工业 QBA-221 自动霰弹枪 `cib:qba221` | `cib:18.4` | 18.4mm 钨合金霰弹（完整弹种待核，保持独立） | `cib:18.4` | 特殊不并 |
| 建设工业 QBA-221 自动霰弹枪（60发爆炸独头弹） `cib:qba221_burst` | `cib:18.4` | 18.4mm 钨合金霰弹（完整弹种待核，保持独立） | `cib:18.4` | 特殊不并 |
| 北方工业 QBS-09 战斗霰弹枪 `cib:qbs09` | `cib:18.4` | 18.4mm 钨合金霰弹（完整弹种待核，保持独立） | `cib:18.4` | 特殊不并 |
| S12K `gz:tactical_sasg_12k` | `cbrg:ammo_12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| S1897 `tacz:m870` | `cbrg:ammo_12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| S686 `cib:686` | `cbrg:ammo_12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| SPAS-12 多功能霰弹枪 `tacz:spas_12` | `cbrg:ammo_12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| Saiga-12 `gz:sasg_12` | `tacz:12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| 锯短型 `tacz:db_short` | `cbrg:ammo_12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| 寡妇制造者丨军团要塞2 `cib:widow` | `tacz:12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| 温彻斯特M1887杠杆霰弹枪 `hamster:m1887` | `tacz:12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| 温彻斯特 \| M1887 `ccrp:m1887_long` | `tacz:12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| 【最后一战】 `ccrp:lastwar` | `tacz:12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| l恶名昭彰 `gz:cargo_terminal` | `tacz:12g` | 12 Gauge（壳长与弹种另核） | `cbrg:ammo_12g` | 同弹种可共享 |
| 57Center丨AR57 `ccrp:ar57` | `tacz:57x28` | 5.7×28mm | `cbrg:ammo_57mm` | 同弹种可共享 |
| B&T \| APC9K Pro G `ccrp:apc_9k_pro_g` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| AR57 ULT 卡宾步枪 `suffuse:ar57` | `tacz:57x28` | 5.7×28mm | `cbrg:ammo_57mm` | 同弹种可共享 |
| AUG 9mm `gz:aug_9mm` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 三连发MP5A4 `gz:mp5a2` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 增强型PP-19 `gz:enhanced_pp19` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| FN赫斯塔尔 P90 \| EFFEN90导轨套件 `ccrp:p90_effen_90` | `tacz:57x28` | 5.7×28mm | `cbrg:ammo_57mm` | 同弹种可共享 |
| FN赫斯塔尔 P90丨PAW延展套件 `ccrp:p90_paw` | `tacz:57x28` | 5.7×28mm | `cbrg:ammo_57mm` | 同弹种可共享 |
| FN赫斯塔尔 P90丨静谧死神系统 `ccrp:p90_shround_s` | `tacz:57x28` | 5.7×28mm | `cbrg:ammo_57mm` | 同弹种可共享 |
| 第一波Vector45 ACP `gz:1st_vector45acp` | `tacz:45acp` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 同弹种可共享 |
| Gepard “猎豹” PDW 个人防卫武器 `suffuse:gepardpdw` | `tacz:9mm` | 保留原声明，具体变体待核：9mm 手枪弹 | `tacz:9mm` | 待核验 |
| HK MP7 冲锋枪 `classicr:mp7` | `tacz:46x30` | 4.6×30mm | `tacz:46x30` | 同弹种可共享 |
| HK \| MP5K 冲锋枪 `ccrp:mp5k` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| HK \| MP5K PDW冲锋枪 `ccrp:mp5k_pdw` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| HK&齐玛军械 \| Cavaliere4.6 个人防卫武器 `ccrp:mp7a3` | `tacz:46x30` | 4.6×30mm | `tacz:46x30` | 同弹种可共享 |
| IWI丨X95 SMG `ccrp:x95_smg` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| JS9 `cib:js9` | `cbrg:ammo_9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| KP9 `gz:pp19_01` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| KRISS \| 维克托 10mm冲锋枪 `ccrp:vector10` | `tacz:10mm` | 10mm Auto（10×25mm） | `tacz:10mm` | 同弹种可共享 |
| 卡拉什尼科夫 \| AKS-74U `ccrp:aks74u` | `tacz:545x39` | 5.45×39mm | `tacz:545x39` | 同弹种可共享 |
| MAS-38型 冲锋枪 `suffuse:mas38` | `suffuse:7.65x20mm` | 7.65×20mm Longue | `suffuse:7.65x20mm` | 同弹种可共享 |
| MP18冲锋枪 `hamster:mp18` | `hamster:compact_ammo` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 原抽象需拆分 |
| MP5K `tacz:hk_mp5a5` | `cbrg:ammo_9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| HK \| MP5SD 微声冲锋枪 `ccrp:mp5_sd` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| MP5ST `gz:mp5st` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| MP9 `classicr:mp9` | `cbrg:ammo_9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| MP9丨雷雲 `ccrp:mp9_thunder` | `tacz:thunder_cell` | 雷雲电池（完整弹种待核，保持独立） | `tacz:thunder_cell` | 特殊不并 |
| B&T \| MP9 `ccrp:mp9_modernrized` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| MSH-41 `ccrp:msh41` | `tacz:762x25` | 7.62×25mm Tokarev | `tacz:762x25` | 同弹种可共享 |
| 微型UZI `tacz:uzi` | `cbrg:ammo_9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 美蓓亚 PM-9 冲锋枪 `cib:pm9` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 海军MP5-N `gz:navy_mp5n` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| P90 `origin:p90` | `cbrg:ammo_57mm` | 5.7×28mm | `cbrg:ammo_57mm` | 同弹种可共享 |
| P90 `tacz:p90` | `cbrg:ammo_57mm` | 5.7×28mm | `cbrg:ammo_57mm` | 同弹种可共享 |
| PM-63 `rainforest:pm63` | `tacz:9mm` | 9×18mm Makarov | `tacz_pubg:ammo_9x18_makarov` | 原型明显不匹配 |
| PM12S `rainforest:pm12s` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| PP-19 `gz:pp19` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| PP-19 Bizon `cib:pp19` | `cbrg:ammo_9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| PP19 “野牛” 冲锋枪 `suffuse:pp19` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| PPSh41 “波波沙冲锋枪” `cib:ppsh41` | `tacz:762x25` | 7.62×25mm Tokarev | `tacz:762x25` | 同弹种可共享 |
| 建设工业 QCQ-171 冲锋枪 `cib:qcq171` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 建设工业 QCQ-171 冲锋枪丨蔚蓝之境 `cibs:qcq171_ocean` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 建设工业 QCW-05 冲锋枪 `cib:qcw05` | `cib:58x21` | 5.8×21mm | `cib:58x21` | 同弹种可共享 |
| 蝎式 EVO3 A1 冲锋枪 `cib:evo3` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 西格绍尔 \| MPX冲锋枪 `ccrp:mpx` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| SR-3M 微声冲锋枪 `ccrp:sr_3m` | `tacz:9x39` | 9×39mm | `tacz:9x39` | 同弹种可共享 |
| Samopal Vzor 1961 `rainforest:vz68` | `tacz:9mm` | 保留原声明，具体变体待核：9mm 手枪弹 | `tacz:9mm` | 待核验 |
| Samopal Vzor 1964 `rainforest:vz64` | `tacz:9mm` | 保留原声明，具体变体待核：9mm 手枪弹 | `tacz:9mm` | 待核验 |
| 斯泰尔丨AUG Para `ccrp:aug_para` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| T5-SMG `gz:t5smg` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 战术KP9 `gz:tactical_pp19_01` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 塔兰战术 \| MPX冲锋枪 `ccrp:tti_mpx` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| 汤姆逊冲锋枪 `classicr:m1a1_smg` | `cbrg:ammo_9mm` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 玩法换口径需区分 |
| 建设工业 79式 冲锋枪 `cib:type79` | `tacz:762x25` | 7.62×25mm Tokarev | `tacz:762x25` | 同弹种可共享 |
| UDP-9 个人防卫武器 `classicr:udp_9` | `tacz:9mm` | 9×19mm Parabellum | `cbrg:ammo_9mm` | 同弹种可共享 |
| UMP `tacz:ump45` | `cbrg:ammo_9mm` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 玩法换口径需区分 |
| UMP45 冲锋枪 `suffuse:ump45` | `tacz:45acp` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 同弹种可共享 |
| UZI 冲锋枪丨.45ACP `ccrp:uzi45` | `tacz:45acp` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 同弹种可共享 |
| Vector `tacz:vector45` | `cbrg:ammo_9mm` | .45 ACP（11.43×23mm） | `cbrg:ammo_45acp` | 玩法换口径需区分 |
| 精密国际&雷明顿 \| AICS R700 `ccrp:aics_m700` | `ccrp:65cm` | 6.5 Creedmoor（6.5×48mm） | `ccrp:65cm` | 同弹种可共享 |
| AWM `tacz:ai_awp` | `tacz:338` | 待核验：.300 Winchester Magnum / .338 Lapua / 其他明确变体 | `tacz:338` | 待核验 |
| 精密国际 AW-50 反器材狙击步枪 `suffuse:aw50` | `tacz:50bmg` | .50 BMG（12.7×99mm） | `tacz:50bmg` | 同弹种可共享 |
| 精密国际 AWP 狙击步枪 `cib:cs_awp` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| 精密国际 AWP 狙击步枪丨绝区零 联名(星见雅) `cibs:awp_hm` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| 精密国际 AXSR 狙击步枪 `suffuse:axsr` | `tacz:338` | 待核验：.300 Winchester Magnum / .338 Lapua / 其他明确变体 | `tacz:338` | 待核验 |
| 精密国际 AXMC 狙击步枪 `suffuse:axmc` | `tacz:338` | 待核验：.300 Winchester Magnum / .338 Lapua / 其他明确变体 | `tacz:338` | 待核验 |
| 巴雷特 M82A1 `classicr:m82a2` | `tacz:50bmg` | .50 BMG（12.7×99mm） | `tacz:50bmg` | 同弹种可共享 |
| 巴雷特 \| MRAD ELR `classicr:mrad_elr` | `tacz:416barrett` | .416 Barrett（10.57×83mm） | `tacz:416barrett` | 同弹种可共享 |
| 巴雷特 \| MRAD `classicr:mrad` | `tacz:338` | 待核验：.300 Winchester Magnum / .338 Lapua / 其他明确变体 | `tacz:338` | 待核验 |
| 贝蒂埃M1892步枪 `hamster:berthier` | `hamster:long_ammo` | 长子弹（完整弹种待核，保持独立） | `hamster:long_ammo` | 待核验 |
| 黑市T81SR `gz:black_market_t81sr` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| COVE-A 重型反器材狙击步枪 “铁拳” `origin:cove-a` | `origin:apfsds` | 14.5×114mm 尾翼稳定脱壳穿甲弹（完整弹种待核，保持独立） | `origin:apfsds` | 特殊不并 |
| 建设工业 CS/LR-3丨QBU-141 狙击步枪 `cib:cslr3` | `tacz:58x42` | 5.8×42mm | `tacz:58x42` | 同弹种可共享 |
| 建设工业 CS/LR-4 狙击步枪 `cib:cslr4` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| CheyTac M200 “干预” 狙击步枪 `suffuse:m200` | `suffuse:.408ct` | .408 CheyTac（10.36×77mm） | `suffuse:.408ct` | 同弹种可共享 |
| 经典M1A `gz:classic_m1a` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| DVL-10 狙击步枪 `suffuse:dvl10` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| FR-F2 `rainforest:frf2` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| G3/SG1 狙击步枪 `cib:g3sg1` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| Gew.98步枪 `hamster:gew98` | `hamster:long_ammo` | 7.92×57mm Mauser | `tacz:792x57` | 原抽象需拆分 |
| 格拉斯1874步枪 `hamster:gras1874` | `hamster:medium_ammo` | 中子弹（完整弹种待核，保持独立） | `hamster:medium_ammo` | 待核验 |
| 骑士军械 \| M110 SASS狙击步枪 `ccrp:m110` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| 骑士军械 \| SR25精确射手步枪 `ccrp:sr25` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| Kar98k `classicr:kar98` | `cbrg:ammo_762` | 7.92×57mm Mauser | `tacz:792x57` | PUBG抽象需拆分 |
| 克拉格-约根森M1894步枪 `hamster:krag` | `hamster:long_ammo` | 长子弹（完整弹种待核，保持独立） | `hamster:long_ammo` | 待核验 |
| 勒贝尔M1886步枪 `hamster:lebel1886` | `hamster:long_ammo` | 长子弹（完整弹种待核，保持独立） | `hamster:long_ammo` | 待核验 |
| 李恩菲尔德NO.1 MKⅢ步枪 `hamster:smle_mk3` | `hamster:long_ammo` | 长子弹（完整弹种待核，保持独立） | `hamster:long_ammo` | 待核验 |
| 鲁格M1906步枪 `hamster:luger1906` | `hamster:long_ammo` | 长子弹（完整弹种待核，保持独立） | `hamster:long_ammo` | 待核验 |
| Lynx AMR `suffuse:gm6` | `tacz:50bmg` | .50 BMG（12.7×99mm） | `tacz:50bmg` | 同弹种可共享 |
| M1加兰德步枪 `hamster:m1garand` | `hamster:long_ammo` | .30-06 Springfield（7.62×63mm） | `tacz:30_06` | 原抽象需拆分 |
| M107 .50口径反器材步枪 `tacz:m107` | `tacz:50bmg` | .50 BMG（12.7×99mm） | `tacz:50bmg` | 同弹种可共享 |
| M24 `tacz:m700` | `cbrg:ammo_762` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | PUBG抽象需拆分 |
| M24 狙击步枪 `classicr:m24_renewed` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| M39 EMR 精确射手步枪 `ccrp:m39_emr` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| M700 碳纤维 `gz:m700_carbon` | `tacz:30_06` | 保留原声明，具体变体待核：.30-06 春田 步枪弹 | `tacz:30_06` | 待核验 |
| M95 .50口径反器材步枪 `tacz:m95` | `tacz:50bmg` | .50 BMG（12.7×99mm） | `tacz:50bmg` | 同弹种可共享 |
| Scar MK20 狙击支援步枪 `classicr:scar_mk20` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| 马提尼-亨利步枪 `hamster:martinihenry` | `hamster:long_ammo` | 长子弹（完整弹种待核，保持独立） | `hamster:long_ammo` | 待核验 |
| 毛瑟 KAR98K 制式步枪 `tacz:kar98` | `tacz:792x57` | 7.92×57mm Mauser | `tacz:792x57` | 同弹种可共享 |
| 莫辛纳甘步枪 `hamster:mosin9130` | `cbrg:ammo_762` | 7.62×54mmR | `tacz:762x54r` | PUBG抽象需拆分 |
| 莫辛-纳甘M1891步枪 `hamster:mosin91` | `hamster:long_ammo` | 7.62×54mmR | `tacz:762x54r` | 原抽象需拆分 |
| 北方工业 QBU-10 反器材半自动狙击步枪 `cib:qbu10` | `cib:127x108` | 12.7×108mm | `suffuse:12.7x108mm` | 同弹种可共享 |
| 建设工业 QBU-201 反器材狙击步枪 `cib:qbu201` | `cib:127x108` | 12.7×108mm | `suffuse:12.7x108mm` | 同弹种可共享 |
| 建设工业 QBU-202 狙击步枪 `cib:qbu202` | `tacz:338` | 待核验：.300 Winchester Magnum / .338 Lapua / 其他明确变体 | `tacz:338` | 待核验 |
| 建设工业 QBU-202 狙击枪丨LINGCHE 凌彻 `cibs:qbu202_lingche` | `tacz:338` | 待核验：.300 Winchester Magnum / .338 Lapua / 其他明确变体 | `tacz:338` | 待核验 |
| 建设工业 QBU-203 狙击步枪 `cib:qbu203` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| 长剑防卫丨MK18 SA-ASR `ccrp:mk18_mjolnir` | `tacz:338` | 待核验：.300 Winchester Magnum / .338 Lapua / 其他明确变体 | `tacz:338` | 待核验 |
| 斯太尔 SSG-08 狙击枪 `cib:ssg08` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| SV98 狙击步枪 `cib:sv98` | `tacz:762x54` | 7.62×54mmR | `tacz:762x54r` | 同弹种可共享 |
| 夏普斯M1874射手步枪 `hamster:sharps` | `hamster:long_ammo` | 长子弹（完整弹种待核，保持独立） | `hamster:long_ammo` | 待核验 |
| 春田 \| Model 1873 弹仓供弹（实验型） `ccrp:springfield1873_tube_mag` | `tacz:45_70` | .45-70 Government（11.63×53mmR） | `tacz:45_70` | 同弹种可共享 |
| 春田 1873 活门步枪 `tacz:springfield1873` | `tacz:45_70` | .45-70 Government（11.63×53mmR） | `tacz:45_70` | 同弹种可共享 |
| 春田M1903A1步枪 `hamster:m1903` | `hamster:long_ammo` | .30-06 Springfield（7.62×63mm） | `tacz:30_06` | 原抽象需拆分 |
| T81SR `gz:t81sr` | `tacz:762x39` | 7.62×39mm | `tacz:762x39` | 同弹种可共享 |
| Troy \| M14 MCS 护木套件 `ccrp:troy_m14_sass` | `tacz:308` | 7.62×51mm / .308 Winchester（游戏家族） | `tacz:308` | 同弹种可共享 |
| 有坂 三八式步枪 `cib:type38` | `cib:65x50` | 6.5×50mmSR Arisaka | `cib:65x50` | 同弹种可共享 |
| 友坂九九式步枪 `hamster:type99` | `hamster:long_ammo` | 长子弹（完整弹种待核，保持独立） | `hamster:long_ammo` | 待核验 |
| Win94 `hamster:win1894` | `cbrg:ammo_45acp` | 保留原声明，具体变体待核：.45 口径子弹 | `cbrg:ammo_45acp` | 待核验 |
| 温彻斯特M1873杠杆步枪 `hamster:win1873` | `hamster:compact_ammo` | 短子弹（完整弹种待核，保持独立） | `hamster:compact_ammo` | 待核验 |
| 资江机器 M99 反器材半自动狙击步枪 `cib:m99` | `cib:127x108` | 12.7×108mm | `suffuse:12.7x108mm` | 同弹种可共享 |
| SVD “德拉戈诺夫” 半自动狙击步枪 `cib:svd` | `tacz:762x54` | 7.62×54mmR | `tacz:762x54r` | 同弹种可共享 |
