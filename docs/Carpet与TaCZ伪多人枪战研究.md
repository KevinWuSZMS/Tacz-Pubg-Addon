# Carpet 与 TaCZ 伪多人枪战研究

研究日期：2026-10-17。适用环境：Minecraft **1.21.1**、NeoForge **21.1.259**、TaCZ 非官方移植版 **1.1.8-hotfix-r7**。

2026-10-18 补充：[Carpet × TaCZ 人机可行性与 PUBG Addon 方案](research/carpet-tacz-bots.html)。该报告进一步核对实际 JAR、源码、字节码、FPS 一手研究与 TaCZ 开火/装填接口，隔离签名编译探针已通过；没有执行探针、启动游戏或实现人机控制器。本页保留安装和原有命令用法。

## 现在可以用它做什么

Carpet 的假人是 `ServerPlayer` 的子类，会加入服务器玩家列表，并非只有玩家外观的普通生物。一个客户端就能生成多个假人，进行移动靶、玩家受击、装备、队伍和服务器逻辑测试。

**生成假人不等于获得会用 TaCZ 枪械自动战斗的 AI。** Carpet 可以控制移动、视角和原版动作；自动寻敌、用枪瞄准、开火与装填需要另写联动逻辑。本次仅准备模组与阅读材料，没有实现该功能。后续 Voxy 安装阶段已验证这一批模组共同加载并进入世界，尚未实测假人枪战行为；记录见 [Voxy 构建与依赖检查](Voxy构建与依赖检查.md)。

开发客户端通过 [run-client.bat](D:/HMCL-3.9.1/neoforge-mod-development/scripts/run-client.bat) 启动；外部模组的位置是 [run/mods](D:/HMCL-3.9.1/neoforge-mod-development/neoforge-1.21.1/run/mods)。自己的 `tacz_pubg` 由开发环境自动加载；旧模板 example 注册内容已移除。

## 选定的模组和依赖

| 模组 | 选定版本 | 要求与用途 |
| --- | --- | --- |
| [Carpet: NeoForged](https://modrinth.com/mod/neoforge-carpet/version/lnOeoKcQ) | **1.0.8，Beta**；文件 `neoforge-carpet-1.21.1-1.0.8+v251027.jar` | NeoForge 原生移植；MC 1.21.1、NeoForge ≥21.1.0、FML ≥4；无需第三方前置。提供 `/player`、规则系统与 Scarpet。 |
| [Carpet AMS Addition: NeoForged](https://modrinth.com/mod/carpet-ams-addition-neoforged/version/ARWsw4f2) | **1.0.1，Alpha**；文件 `carpet-ams-addition-neoforge-v1.0.1-mc1.21.1.jar` | 需要 Carpet ≥1.4.147、NeoForge ≥21.1.233。补充假人离线 UUID、拾取、名称、默认游戏模式等可选规则；大部分功能默认关闭。 |

Carpet 的发布版本号为 1.0.8，内部模组版本为 `1.4.147+v1.0.8-…`，满足 AMS 的 Carpet 依赖。目标 NeoForge 21.1.259 满足二者要求。**这两个版本不需要 Fabric API 或 Sinytra Connector。**

Carpet: NeoForged 的最新发布和仓库提交均为 2025-10-27，仓库未归档，但不能据此保证持续维护；许可证为 MIT。AMS 此版本发布于 2026-07-28；Modrinth 标为 LGPL-3.0-only，源码配置标为 LGPL-3.0-or-later，两处声明存在差异。

## 在允许作弊的世界生成两个假人

创建世界时开启“允许命令/作弊”；已有单人世界可在暂停菜单“对局域网开放”中临时允许作弊。独立服务器使用拥有命令权限的玩家或服务器控制台。Carpet 的 `commandPlayer` 默认是 `ops`。

聊天框逐条输入以下命令。`BotA` 与 `BotB` 必须是当前未在线的名字：

```mcfunction
/player BotA spawn
/gamemode survival BotA
/player BotB spawn
/gamemode survival BotB
```

`spawn` 默认继承执行玩家的位置、朝向和游戏模式，所以补一条 `gamemode survival`，避免创造模式假人不适合作为普通受击靶。

如果希望一次指定位置、朝向、维度和模式，已按该移植版 `PlayerCommand.java` 核对的完整语法是：

```mcfunction
/player BotA spawn at ~ ~ ~ facing ~ ~ in minecraft:overworld in survival
```

不要在已生成同名假人后再次执行这条生成命令。`facing` 后的两个值依次为偏航角和俯仰角；这是命令语法的顺序，和后文 TaCZ `shoot(pitch, yaw)` 的参数顺序不同。

如遇不存在的账号名不能生成，可检查并设置 `/carpet allowSpawningOfflinePlayers true`；此移植版本默认已为 `true`。生成会经过玩家资料查询，命令返回后角色可能稍后才出现。

## 移动、转向、停止与移除

| 命令 | 作用 |
| --- | --- |
| `/player BotA look north` | 面向北方；也可使用 `south/east/west/up/down`。 |
| `/player BotA look at ~ ~ ~` | 看向执行命令的位置。 |
| `/player BotA move forward` | 持续前进；方向也可选 `backward/left/right`。 |
| `/player BotA move` | 只停止移动。 |
| `/player BotA sprint`、`/player BotA unsprint` | 开始或取消疾跑状态。 |
| `/player BotA jump interval 20` | 每 20 游戏刻尝试跳跃一次。 |
| `/player BotA hotbar 1` | 切到快捷栏第 1 格，范围为 1–9。 |
| `/player BotA stop` | 停止所有动作和移动。 |
| `/player BotA kill` | 让假人退出服务器、从当前世界移除。 |

删除本次生成的两名假人：

```mcfunction
/player BotA kill
/player BotB kill
```

假人的前进是按当前朝向移动，并没有自动寻路或避障。先在平坦场地尝试，移动靶测试结束后执行 `stop`。

## 受击、装备与队伍测试

先让自己的角色持枪向生存模式假人射击，观察命中部位、伤害、护甲、死亡和事件是否正确。需要重复测试时可重新生成假人。枪械和弹药通过当前 TaCZ 创造物品栏取得；不在此硬编码不同枪包的枪械 ID。

可使用原版玩家选择器、`/give` 和 `/item replace` 给假人装备。给它一把枪只能改变持有物，并不会让它自动操作枪械。

下面可把自己和 BotA 分到对立队伍：

```mcfunction
/team add red
/team add blue
/team join red @s
/team join blue BotA
/team join red BotB
/team modify red friendlyFire false
/team modify blue friendlyFire false
```

队伍已存在时无需重复 `team add`。这里便于比较对敌方 BotA、友方 BotB 的伤害行为；**原版 `friendlyFire` 设置是否被当前 TaCZ 伤害流程或你自己的附属正确遵守，需要实际验证**。

## 为什么 `/player … attack` 不是 TaCZ 开火

该版本 Carpet 的 `EntityPlayerActionPack` 中，`ATTACK` 调用原版近战攻击或挖方块，`USE` 调用原版物品使用与交互。它们没有调用 TaCZ 的开火、装填、瞄准接口，也没有模拟 TaCZ 客户端专用网络消息。

因此 `/player BotA attack continuous` 不能当作 TaCZ 连射命令；`use continuous` 也不能代替 TaCZ 装填或瞄准。AMS 的 `fakePlayerInteractLikeClient` 只改善原版交互，不是 TaCZ 枪械适配器。

## 接下来怎样阅读 TaCZ 的服务端接口

本地 API 与当前 r7 模组版本匹配，来源见 [SOURCE.md](D:/HMCL-3.9.1/neoforge-mod-development/docs/TaCZ-1.21.1-API源码/SOURCE.md)。这些文档旁的 Java 文件是只读阅读材料，不直接编译。当前 `tacz_pubg` 工程已配置匹配 r7 JAR 的编译依赖，运行时沿用 `run/mods` 的单份 TaCZ；另开工程仍需自己配置对应依赖。

建议按以下顺序阅读：

1. [IGunOperator.java](D:/HMCL-3.9.1/neoforge-mod-development/docs/TaCZ-1.21.1-API源码/com/tacz/guns/api/entity/IGunOperator.java)：TaCZ 通过 Mixin 让 `LivingEntity` 实现该接口，可用 `fromLivingEntity` 获取操作者。重点理解服务端 `draw`、`aim`、`shoot`、`reload`、`bolt`，以及冷却、弹药、换弹状态。
2. [ShootResult.java](D:/HMCL-3.9.1/neoforge-mod-development/docs/TaCZ-1.21.1-API源码/com/tacz/guns/api/entity/ShootResult.java)：关注 `SUCCESS`、`NOT_DRAW`、`NOT_GUN`、`NO_AMMO`、`COOL_DOWN`、`NEED_BOLT` 等结果，判断假人为什么未能射击。
3. [IGun.java](D:/HMCL-3.9.1/neoforge-mod-development/docs/TaCZ-1.21.1-API源码/com/tacz/guns/api/item/IGun.java)：理解枪械物品及枪械数据。
4. [GunShootEvent.java](D:/HMCL-3.9.1/neoforge-mod-development/docs/TaCZ-1.21.1-API源码/com/tacz/guns/api/event/common/GunShootEvent.java)、[EntityHurtByGunEvent.java](D:/HMCL-3.9.1/neoforge-mod-development/docs/TaCZ-1.21.1-API源码/com/tacz/guns/api/event/common/EntityHurtByGunEvent.java)、[EntityKillByGunEvent.java](D:/HMCL-3.9.1/neoforge-mod-development/docs/TaCZ-1.21.1-API源码/com/tacz/guns/api/event/common/EntityKillByGunEvent.java)：寻找开火、受伤和击杀的记录或规则入口。

后续附属的最小目标可以是“识别指定假人 → 装备枪械 → 正确切枪初始化 → 瞄准 → 服务端按节奏开火 → 处理装填与射击结果”。`shoot` 的参数依次是 **pitch（俯仰角）、yaw（偏航角）**。不要从假人的服务端逻辑调用只适用于本机客户端的 `IClientPlayerGunOperator`。

## 未安装的相关候选

| 项目 | 本次不加入的原因 |
| --- | --- |
| [Carpet TIS Addition](https://modrinth.com/mod/carpet-tis-addition) | 官方 MC1.21.1 构建仅 Fabric；未找到匹配的完整 NeoForge 移植。引入 Connector 也不能直接保证其大量 Mixin 与 NeoForged Carpet 兼容。 |
| [Carpet Extra](https://modrinth.com/mod/carpet-extra) | 官方 MC1.21/1.21.1 构建仅 Fabric，不是可直接放入当前原生 NeoForge 环境的附属。 |
| [Qualia Carpet Addition](https://modrinth.com/mod/qca) | NeoForge1.21.1 版本依赖另一套 [RollingGate](https://modrinth.com/mod/rolling-gate)，不是 Carpet: NeoForged 的直接附属。 |
| [SiliconeDolls](https://modrinth.com/mod/silicone-dolls) | 依赖 RollingGate，提供重叠的 `/player` 假人系统；本次避免同时加入两套。 |
| [Fake Players](https://modrinth.com/mod/fake-players) | 以玩家外观 AI 实体为主，还添加采矿、农夫等功能，不是此处的真实服务器玩家替身。 |
| [Advanced Fake Player](https://modrinth.com/mod/advanced-fake-player) | 有原生 NeoForge1.21.1 **1.0.0**，可生成并附身操控替身，是单客户端多人操作的候选。**未安装、未验证 TaCZ 自定义开火/装填消息兼容性**，作者页的输入转发说明不能替代实测。 |

## 一手来源

- [Carpet: NeoForged 作者仓库](https://github.com/chililisoup/neoforge-carpet)、[版本与依赖配置](https://github.com/chililisoup/neoforge-carpet/blob/master/gradle.properties)。
- [PlayerCommand.java：本指南命令语法](https://github.com/chililisoup/neoforge-carpet/blob/master/src/main/java/carpet/commands/PlayerCommand.java)。
- [EntityPlayerMPFake.java：ServerPlayer 假人实现](https://github.com/chililisoup/neoforge-carpet/blob/master/src/main/java/carpet/patches/EntityPlayerMPFake.java)。
- [EntityPlayerActionPack.java：原版 attack/use 行为](https://github.com/chililisoup/neoforge-carpet/blob/master/src/main/java/carpet/helpers/EntityPlayerActionPack.java)。
- [AMS NeoForged 作者仓库](https://github.com/ProstoCheI/Carpet-AMS-Addition-NeoForged)、[版本与依赖配置](https://github.com/ProstoCheI/Carpet-AMS-Addition-NeoForged/blob/master/gradle.properties)。
- [当前 TaCZ r7 发布页](https://modrinth.com/mod/tacz-1.21.1/version/62q6mB5Q)、[TaCZ 移植仓库](https://github.com/MUKSC/TACZ-1.21.1/tree/neoforge/1.21.1)。

本次资料工作不启动游戏、不修改原 HMCL 实例。


## 2026-10-19：20人房间与 `/pubgplayer` 首版

已按上述接口接入受管 Carpet 假人与 TaCZ 原生枪械操作。`/pubgdebug config → 创建游戏` 提供房间编队和对局规则两页，真人与假人共用20席；房间保存计划身份，开局先完成假人登记，再固定实际 UUID、队伍与规则。全服在线 Carpet 假人与待生成席位共用20个创建名额。

人机采用感知、反应、瞄准误差、后坐力、有限弹药、换弹及步行寻路代码；`/pubgplayer <实际人机名>` 可设置自动作战、寻找敌人、搜集、交战、规避、巡逻、守位、待命、移动、跟队、进圈、停火和换弹。管理操作使用 OP 2 权限，淘汰与结束会收尾对应的自有假人。

完整用法见 [PUBG人机与20人房间](PUBG人机与20人房间.md)。本次完成离线构建、真实模组签名、配置迁移、编队、协议和代码行为检查；实际枪战、动作效果与20人性能列入后续游戏内验收。此前章节保持研究时点记录。
