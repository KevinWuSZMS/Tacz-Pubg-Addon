# 玩家、标靶可见距离与 Voxy

2026-10-18。本次不启动 Minecraft；下面的旧距离依据当前配置和安装版本源码／字节码核对。

## 改动前的实际限制

当前 `run/options.txt` 为地形距离 `renderDistance=12`、模拟距离 `simulationDistance=12`、实体倍率 `entityDistanceScaling=1.0`；`run/config/tacz-client.toml` 为 `TargetRenderDistance=128`。

| 对象 | 注册名／实际类型 | 客户端距离条件 | 服务端条件 | 当前通常先遇到的限制 |
|---|---|---|---|---|
| 其他玩家 | `minecraft:player`，客户端 `RemotePlayer` | 站立约 960 格；蹲姿等姿态影响包围盒公式 | 玩家注册追踪范围 32 区块＝512 格，但还被观测者的 12 区块≈192 格和已发送区块范围截断 | 约 192 格，区块边缘和姿态／相机视锥还会影响实际观察 |
| 圆形方块标靶 | `tacz:target` 方块／方块实体 | `TargetRenderer.getViewDistance()` 为 128 格 | 方块实体随原生区块数据发送 | 约 128 格；更远原生区块不可用时也无动态方块实体可画 |
| 人形矿车标靶 | `tacz:target_minecart` 实体 | 尺寸约 1.3 × 128 × 1.5＝249.6 格 | 注册追踪范围 8 区块＝128 格；仍要求目标区块已发送 | 约 128 格 |

**其他玩家不是 96 格。** `Entity.shouldRenderAtSqrDistance` 的基类公式会给站立玩家尺寸约 96 格，但实际远玩家使用 `RemotePlayer` 的覆盖实现，另有十倍系数，约 960 格。必须检查最终调用的覆盖方法，不能直接把基类公式作为玩家消失距离。

原生服务端同时检查距离和目标区块是否已发送给观测者；把客户端实体倍率或 TaCZ 标靶设置单独改成 512，无法解除这层限制。Sodium 还根据原生地形渲染区段判断实体遮挡，未发送区块的远实体会受到额外剔除。

Voxy 当前版本提供地形 LOD，不自动发送远玩家、矿车或方块实体。其 `SoftwareModelTextureBakery` 的方块实体烘焙仍是 TODO，`tacz:target` 又是 `ENTITYBLOCK_ANIMATED`，因此本次只额外绘制 TaCZ 原来的方块实体模型，不重新绘制地形或方块外壳。

## 现在怎样设置

在 `/pubgdebug config` 的 **通用设置 → 玩家与标靶可见距离** 中分别调整：

- 玩家距离：默认 **512 格**。
- 标靶距离：默认 **512 格**，同时控制圆形方块标靶和人形矿车标靶。
- 有效范围：16–1024 格整数。

点击此界面的“保存距离”独立保存，不要求丢弃父配置界面的其他草稿。设置按存档保存在主世界 `data/tacz_pubg_visibility.dat`。修改需要权限等级 2；客户端不能自行扩大服务器追踪范围。保存采用版本比较，另一位管理员已修改时会拒绝旧草稿覆盖。

保存后客户端距离立即同步；原生追踪配对及远方块靶快照每秒复查一次。不修改 `options.txt`、原生地形距离、模拟距离或 Voxy 远景距离。

## 512 格怎样实现

### 玩家及矿车标靶

针对玩家、`TargetMinecart` 及当前承载它们的根／中间载具，在原生 `ChunkMap.TrackedEntity.updatePlayer` 的配对资格判断中采用设置的三维球面距离，保留同维度、对象未移除、`broadcastToPlayer` 等条件，不再受观测者已发送地形区块和 12 区块距离上限截断。载具按递归乘客树中的管理目标继承最大配置距离；空船、无人马匹、普通空矿车不全局扩大范围。下车后会按原生规则重新判定，并在下一次每秒复查时取消不再符合条件的远追踪。

仍由同一个原生 `ServerEntity.addPairing`／`removePairing` 发送创建、装备、元数据、属性、移动、乘客信息、移除包和 NeoForge 跟踪事件；不创建第二套实体追踪器，也不创建复制玩家。

客户端分别覆盖 `RemotePlayer` 和 TaCZ `TargetMinecart` 的距离判断，并在 `EntityRenderer.shouldRender` 内仅包装管理目标的距离条件，使载具自身的距离覆盖方法也不会再次截断。对管理对象，显示使用本次设置的距离和实体自身的相机视锥，放行 Sodium 的地形区段遮挡优化门，以及原生渲染循环随后进行的 `isSectionCompiled`／Sodium `isSectionReady` 优化门。即使原生区块已经缓存／发送，本地地形距离较低时仍可能没有可绘制区段，所以不能只依据 `hasChunk` 判断是否需要放行。相机视锥、正常深度测试仍保留，物体不是无条件透视显示；不在管理乘客链中的其他生物及物品不改变这些优化。

原生客户端 `TransientEntitySectionManager` 会把网络创建的对象加入跟踪和渲染集合，即使该原生地形区块未收到。玩家原本就是 `isAlwaysTicking=true`。矿车标靶及管理中的载具补充**仅客户端**的 `isAlwaysTicking=true`，让未收到地形区块时的位置插值继续运行，避免只出现创建位置、随后冻结。新载具可能在加入客户端世界之后才收到乘客包，因而加入时尚未被原生 tick 列表纳入；本次在原生 `tickEntities` 之前把当前管理目标补入同一 `EntityTickList`，并对管理中的中间载具放行原生 `tickPassenger` 的活动列表门。离开管理关系后，按普通区块已加载／原生 `isAlwaysTicking` 条件恢复列表资格，避免下车后的空载具冻结或继续额外活动。所有坐标、插值、冻结时间检查、骑乘更新仍完全由原生 tick 流程运行，不手动调用第二次 tick，不额外发送复制位置。服务端模拟／实体活动范围不因此扩大。

远投影平面至少保留到所设最大距离加 64 格，避免低地形距离先裁掉远对象。这不增加地形加载量。Voxy 已自行移除空气中的原版远雾；光影自身的距离雾仍取决于光影设置。

### 方块标靶

服务端记录已经加载的区块，每秒通过 `getChunkNow` 读取其中现存的 `TargetBlockEntity`。该接口只取已有区块，不请求加载或生成。

向每位玩家发送同维度、设置距离内最近的最多 128 个方块靶，内容包括方块状态、位置、原方块实体外观信息和服务端光照。客户端创建只用于显示的 `TargetBlockEntity` 对象，直接调用安装的 TaCZ `TargetRenderer`；这些对象不会写入客户端世界，不能伪造碰撞、交互或伤害。

是否跳过快照以**本帧实际原生绘制**为准：从 `AFTER_ENTITIES` 开始记录，通过原生 `BlockEntityRenderDispatcher.setupAndRender` 正常返回确认已绘制的标靶位置，到 `AFTER_BLOCK_ENTITIES` 结束记录。快照只跳过该集合中的位置，其他范围内且通过视锥的快照补画。即使客户端已经缓存区块和方块实体，但 Sodium 没有编译对应渲染区段，仍可补画；已经原生绘制的靶不会重画。每帧清集合，退出世界清集合；不抑制或接管原生渲染。Iris 当前阴影绘制在 `renderSky` 调用前执行，早于上述记录窗口，不会把阴影通道误认为主画面已画。击倒／复位状态通过每秒快照更新，客户端沿用原本的标靶旋转插值。

每个外观 NBT 最多 1 KiB；过大的头颅／自定义名字数据仅在远景退回标准外观，源方块实体保持完整。每包最多 128 个，最坏包体不足 135 KiB，每秒最多一批；数量溢出时界面会提示。快照收到后原子替换，超过 3 秒未更新、切换维度或退出连接就清除。过期连接的排队数据不会作用于新连接。

## 范围边界

- 512 是**已加载、可同步目标**的显示和追踪距离，不是强制加载半径。
- 其他真实／模拟玩家所在区域本身通常因这些玩家而已加载，因此可在原生观测者地形距离外同步他们。
- 远处无人、未被其他来源保持加载的方块靶和矿车靶不会凭 Voxy 的地形缓存重新出现。需要实地射击测试时，应保证目标区域本来就在服务端加载范围内，例如由另一位玩家在那里活动。
- 这次不额外创建常加载区块，也不自动把全部地形距离提升到 32 区块。
- 显示距离和子弹的真实碰撞、存活时间、模拟区块覆盖是不同条件。能看到远靶并不等于未加载的中间地形可以可靠参与真实射击；本次不伪造命中。
- 范围内超过 128 个方块靶时，远景快照只保留最近 128 个；原生加载区块内的正常标靶不受这个快照数量限制。

## 离线核对

- `VisibilityChecks`：边界距离、16–1024 设置范围、128 个／1 KiB 包预算、包编解码、外观 NBT 防御性复制、保存版本冲突及损坏数据保护。
- `VisibilityAbiChecks`：直接读取当前 MC 1.21.1、TaCZ r7、Sodium 0.6.13 的 class 文件，确认本次 mixin 目标字段／方法，包括容易漏掉的 `RemotePlayer` 覆盖方法。该检查不初始化这些游戏类。
- [Sodium 实际字节码记录](../logs/visibility-sodium-renderer-javap.txt)。
- [原生渲染循环与局部变量记录](../logs/visibility-native-level-renderer-javap.txt)：`renderLevel` 的唯一 `isSectionCompiled(BlockPos)` 调用位于偏移 934；该调用的作用域内只有一个 `Entity` 局部变量，槽 27，范围 874–1207。
- [Sodium 覆盖方法注解记录](../logs/visibility-sodium-level-mixin-annotations.txt)：Sodium 以默认 mixin 优先级覆盖 `isSectionCompiled` 的方法体，转向 `isSectionReady`；没有覆盖或移除 `renderLevel` 内调用点。本次在调用者局部使用可组合的 `WrapOperation`，无需覆盖 Sodium 方法。当前 Iris jar 内没有 `isSectionCompiled` 引用；Voxy 当前 mixin 也不改这个调用点。
- 同一 Sodium 记录确认其方块实体最终调用原生 `BlockEntityRenderDispatcher.render`；[Iris 阴影注入记录](../logs/visibility-iris-main-level-mixin-annotations.txt)确认主世界阴影调用发生在 `renderSky` 前。`TargetRenderLedger` 的纯检查覆盖“缓存存在但本帧未画”“本帧已原生画”“下一帧编译状态变化”“记录窗口外的阴影／物品绘制”“退出清理”及 128 个候选上限。
- 核心源码位于 `neoforge-1.21.1/src/main/java/com/example/examplemod/visibility/`；独立 mixin 配置为 `tacz_pubg.visibility.mixins.json`。

没有通过启动游戏进行视觉验证，遵守“不再检查游戏是否正常打开”的约定。
