# PUBG 瞄具允许装配表

核对日期：2026-10-18。主体规则采用 PUBG: BATTLEGROUNDS PC 43.2；按用户选择保留移除前的历史侧瞄许可。

[打开全部 424 把枪的带图筛选表](pubg-scope-policy.html) · [逐枪 CSV](pubg-scope-policy.csv) · [机器政策](pubg-scope-policy.json)

覆盖 **424 把枪**，其中 **353 把原有 scope 槽**，**71 把无原始槽，硬性禁止新增瞄具**。实验性瞄具方案允许 346 把枪、2280 个枪械×瞄具组合。

本表只管 CBRG 的红点、全息、2、3、4、6、8、15 倍及侧瞄；初始模式恢复枪包自身规则。没有给无原始瞄具槽的枪创建新槽，也不改源枪包。

## 规则与图片举例

| 枪类／特例 | 本地示例图片 | 允许的主瞄具 | 说明 |
|---|---|---|---|
| 普通突击步枪 | ![M416](../gun-balance/icons/tacz__hk416d__c1343819.png)<br>M416<br>`tacz:hk416d` | 红点、全息、2、3、4、6 倍 | AR 不一概允许 8、15 倍 |
| M16A4／Mk47 | ![M16A4](../gun-balance/icons/tacz__m16a4__9efd9a67.png)<br>M16A4<br>`tacz:m16a4` | 红点、全息、2、3、4、6、8、15 倍 | 20.1 新增的长倍率特例 |
| 精确射手步枪 | ![Mk14](../gun-balance/icons/tacz__mk14__5541280c.png)<br>Mk14<br>`tacz:mk14` | 红点、全息、2、3、4、6、8、15 倍 | TaCZ 可能把 DMR 注册为 rifle，按型号角色识别 |
| 狙击步枪 | ![AWM](../gun-balance/icons/tacz__ai_awp__b7195fb6.png)<br>AWM<br>`tacz:ai_awp` | 红点、全息、2、3、4、6、8、15 倍 | Win94 固定镜例外 |
| 普通冲锋枪 | ![UMP](../gun-balance/icons/tacz__ump45__bd5368af.png)<br>UMP<br>`tacz:ump45` | 红点、全息、2、3、4、6 倍 | P90 固定镜；短型 SMG 特例见下 |
| UZI／汤姆逊／MP9 | ![微型UZI](../gun-balance/icons/tacz__uzi__dcf5ac5f.png)<br>微型UZI<br>`tacz:uzi` | 红点、全息 | 不放开倍率镜 |
| 机枪 | ![M249](../gun-balance/icons/tacz__m249__5a743088.png)<br>M249<br>`tacz:m249` | 红点、全息、2、3、4、6 倍 | 仍需原始 scope 槽 |
| S12K／O12 等 | ![S12K](../gun-balance/icons/gz__tactical_sasg_12k__dadb98c5.png)<br>S12K<br>`gz:tactical_sasg_12k` | 红点、全息、2、3、4、6 倍 | O12 在 20.1 扩展至 2–6 倍 |
| 可装镜手枪 | ![沙漠之鹰](../gun-balance/icons/tacz__deagle__9a4f94e8.png)<br>沙漠之鹰<br>`tacz:deagle` | 红点、全息 | R1895 无外装镜；无槽手枪不新增 |
| 固定镜／无镜武器 | ![P90](../gun-balance/icons/origin__p90__0b453267.png)<br>P90<br>`origin:p90` | 不添加通用外装镜 | P90、Win94、VSS；S1897、S686、Sawed-Off、R1895 |
| 发射器 | ![铁拳火箭筒](../gun-balance/icons/tacz__rpg7__949314a8.png)<br>铁拳火箭筒<br>`tacz:rpg7` | 不添加 CBRG 镜 | 无普通 PUBG 可换镜对应，保守保留原能力 |

侧瞄另按历史白名单开放，表中最后一列单独显示。不是所有枪都有侧瞄，也不自动实现双镜独立安装或切换。Groza、DP-28、MG3、Lynx AMR 不添加侧瞄；RPD 无移除前的已确认历史许可，保守不添加。JS9 / Dragunov 仅在源包原本已允许侧瞄时保留，标 `●*`，不宣传为已获官方逐枪确认。非 PUBG 的 AR / DMR / SR 侧瞄是项目类推，非 PUBG 机枪不一概推广。

## 映射可信度与限制

CBRG 名称意图映射引用本地固定 0.4.8 对照，不把替代模型说成精确 PUBG 复刻。例如 P92 使用 P320 模型、Skorpion 使用 CZ75；这里服从作者想对应的玩法型号。其他枪包按同族型号、DMR 角色或 TaCZ 枪类保守类推，HTML 的每一行都显示依据。非 PUBG、科幻和历史枪没有 PUBG 官方逐枪许可。

机器资源与 HTML / CSV 出自同一生成器。规则最终计算是：原始 `allow_attachment_types` 含 `scope` **并且** 该镜在规则白名单中。原始无槽是硬条件，不能被映射、研究方案或后备模型挂点覆盖。未知新增 gunId 不自动扩大适配。

全部图标使用已有本地纹理。完成的是静态数据核对；没有启动游戏，模型挂点贴合和开镜显示仍以实际枪包能力为限。

## 来源

- [PUBG 官方：步枪、机枪的高倍率镜限制](https://www.pubg.com/en/news/1733)
- [PUBG 官方 20.1：M16A4 / Mk47 的 8、15 倍镜；O12 的 2–6 倍镜](https://www.pubg.com/en/news/1730)
- [PUBG 官方 20.2：O12 加入侧瞄](https://www.pubg.com/en/news/1724)
- [PUBG 官方 15.2：Win94 / VSS 固定镜及 VSS 历史侧瞄](https://pubg.com/en-ca/news/1725)
- [PUBG 官方 13.2：P90 固定预装瞄具](https://pubg.com/en/news/1720)
- [PUBG 官方 19.1：MP9 仅红点、全息](https://pubg.com/en-asia/news/1364)
- [PUBG 官方 22.2：FAMAS 至多 6 倍镜](https://pubg.com/en/news/4555)
- [PUBG 官方 17.1：ACE32 至多 6 倍镜](https://www.pubg.com/en/news/1715)
- [PUBG 官方 37.1：移除侧瞄及其槽位](https://www.pubg.com/en/news/9002)
- [PUBG 官方 25.2：Dragunov 可装至 15 倍镜](https://pubg.com/en/news/6616)
- [PUBG 官方 41.1：新增 Hybrid Scope，不等于侧瞄](https://pubg.com/en/news/9926)
- [PUBG 官方：最初的侧瞄允许枪械清单](https://www.pubg.com/en/news/1728)
- [PUBG 官方 Steam 更新 #27：手枪全息、M16A4 侧瞄、MP5K 瞄具](https://store.steampowered.com/news/posts/?appids=578080%2C1046180&enddate=1553760176&feed=steam_community_announcements)
- [PUBG 官方 Steam 更新 6.3：汤姆逊可用红点、全息](https://store.steampowered.com/news/posts/?appids=578080&enddate=1584516543)
- [2023 历史原始 Blueprint 导出索引（社区归档）](https://battlegrounds.party/weapons/raw/)
- [2023 原始 Blueprint：WeapACE32.json](https://battlegrounds.party/weapons/raw/WeapACE32.json)
- [2023 原始 Blueprint：WeapMP5K.json](https://battlegrounds.party/weapons/raw/WeapMP5K.json)
- [2023 原始 Blueprint：WeapFamasG2.json](https://battlegrounds.party/weapons/raw/WeapFamasG2.json)
- [2023 原始 Blueprint：WeapK2.json](https://battlegrounds.party/weapons/raw/WeapK2.json)
- [2023 原始 Blueprint：WeapDP12.json](https://battlegrounds.party/weapons/raw/WeapDP12.json)
- [2023 原始 Blueprint：WeapMk12.json](https://battlegrounds.party/weapons/raw/WeapMk12.json)
- [2023 原始 Blueprint：WeapGroza.json](https://battlegrounds.party/weapons/raw/WeapGroza.json)
- [2023 原始 Blueprint：WeapGroza_WeaponGunData.json](https://battlegrounds.party/weapons/raw/WeapGroza_WeaponGunData.json)
- [2023 原始 Blueprint：WeapDP12_WeaponGunData.json](https://battlegrounds.party/weapons/raw/WeapDP12_WeaponGunData.json)
- [2023 原始 Blueprint：WeapL6_WeaponGunData.json](https://battlegrounds.party/weapons/raw/WeapL6_WeaponGunData.json)
- [2023 原始 Blueprint：WeapDP28.json](https://battlegrounds.party/weapons/raw/WeapDP28.json)
- [2023 原始 Blueprint：WeapMG3.json](https://battlegrounds.party/weapons/raw/WeapMG3.json)
- [2023 原始 Blueprint：WeapMosinNagant.json](https://battlegrounds.party/weapons/raw/WeapMosinNagant.json)
- [2023 原始 Blueprint：WeapL6.json](https://battlegrounds.party/weapons/raw/WeapL6.json)
- [2023 原始 Blueprint：WeapCrossbow_1.json](https://battlegrounds.party/weapons/raw/WeapCrossbow_1.json)
- [2023 原始 Blueprint：WeapMP9.json](https://battlegrounds.party/weapons/raw/WeapMP9.json)
- [2023 原始 Blueprint：WeapP90.json](https://battlegrounds.party/weapons/raw/WeapP90.json)
- [Classic BattleRoyale Gun 0.4.8 固定发行档](https://www.curseforge.com/minecraft/customization/tacz-classic-battleroyale-gun/files/7589555)
- [本地 CBRG 逐项对照](../PUBG枪械逐项对照.md)
- [本地枪械图鉴与源数据](../gun-balance/gun-assets.json)

逐枪完整名单、图片及 9 列允许矩阵见 HTML，避免在 Markdown 中重复 424 行造成阅读困难。
