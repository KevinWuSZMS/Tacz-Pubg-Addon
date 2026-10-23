# Jade 准心信息安装说明

记录时间：2026-10-18。适用工作区：Minecraft 1.21.1 / NeoForge 21.1.259。

## 安装结果

已安装 **Jade 15.10.6+neoforge**。Jade 是常见的 Waila / Hwyla 系列信息提示模组，用于显示准心指向的方块或实体名称及其他信息。

- 官方 Modrinth 项目：[Jade](https://modrinth.com/mod/jade)。
- 本次兼容正式版：[15.10.6+neoforge](https://modrinth.com/mod/jade/version/eYz2YBGT)。
- 原始下载：`downloads/jade/Jade-1.21.1-NeoForge-15.10.6.jar`。
- 安装位置：`neoforge-1.21.1/run/mods/Jade-1.21.1-NeoForge-15.10.6.jar`。
- 下载 URL：<https://cdn.modrinth.com/data/nvQzSEkH/versions/eYz2YBGT/Jade-1.21.1-NeoForge-15.10.6.jar>。
- 大小：726,853 字节。
- SHA-256：`276f1f65a20d04f536989c4f311949d770b52c440c23c2b904a08860f3c93810`。
- SHA-512：`dad9755dce8d85d914fc4df2baa0211f13e5839a71c1925fdd01f69081a95e30a2934e6273f8b01f0169adebe7a9dae57a8d904de0c4cb36dc17369bb474f0f2`。

Modrinth API 在本次检查时列出的 Minecraft 1.21.1 / NeoForge 最新正式版本是 15.10.6+neoforge。安装前未发现 Jade、Waila、Hwyla、WTHIT 或 The One Probe 的重复信息提示模组。

## 依赖核对

JAR 内的 `META-INF/neoforge.mods.toml` 只有一项硬依赖：NeoForge `[21.0.143, )`。当前项目的 `neo_version=21.1.259` 满足要求。

Modrinth 标记 **JEI 为可选依赖**；工作区已安装 `jei-1.21.1-neoforge-19.51.0.418.jar`。不需要额外下载前置库，也没有为此安装 Jade Addons。

原始 API 响应、JAR 元数据、哈希和安装前模组列表保存在 `downloads/jade/installation-manifest.json` 及同目录的证据文件中。安装复制前后的 JAR 完全一致。本次没有启动游戏、客户端或服务器，也没有执行构建；验证范围是下载来源、版本、元数据、依赖与文件完整性。

## 查看名称和注册 ID

正常开启 Jade 提示后，准心指向方块或实体即可查看名称。可在 Minecraft 的键位绑定页面搜索 Jade，找到“打开配置”“显示提示”等操作并调整按键。

**注册 ID 默认不显示。** 本次安装的 JAR 字节码确认，其调试插件将“注册名”默认设为 `OFF`。需要考察注册 ID 时，在 Jade 配置内找到“调试 / 注册名”，改为 `ON`。也可以改为 `ADVANCED_TOOLTIPS`，再通过 Minecraft 的 F3+H 控制高级提示。

- 对方块显示方块注册 ID，例如 `minecraft:stone`。
- 对实体显示实体类型注册 ID，例如本模组地面物资的 `tacz_pubg:ground_loot`。
- 实体类型 ID 与 TaCZ 的具体逻辑枪械、弹药、配件 ID 是两种不同的信息。所有地面物资都可以属于同一个实体类型，内部再携带不同 ItemStack。

## 对本模组地面物资的支持边界

Jade 默认的 `ObjectNameProvider` 对原版 `ItemEntity` 使用物品 `ItemStack.getHoverName()`，对普通自定义实体则使用 `Entity.getName()`。本轮已经覆写 `GroundLootEntity.getName()` 返回内部物品名称，因此 Jade 能取得具体枪械、弹药或配件名称；无需加入 Jade 编译依赖。功能与构建记录见[掉落物落地转换方案](TaCZ掉落物落地转换方案.md)。

若需要同时展示具体 TaCZ 逻辑 ID（`GunId`、`AmmoId`、`AttachmentId`）、数量、所属区域、生成来源等调试信息，则应继续加入可选的 Jade 实体信息提供器。该提供器应仅在 Jade 已安装时注册，保持本模组不安装 Jade 也能使用。本安装任务没有擅自修改本模组 Java 源码或加入这样的提供器。

Jade 有独立的实体射线检测：实体碰撞盒过小时会扩展约 0.3 格，以便选中小物体；与本模组基于地面模型范围的选中算法可能出现边缘上的差异。需要精确一致时，可在后续集成中让 Jade 使用本模组已经确定的目标实体。

## 依据

- [Jade 官方仓库](https://github.com/Snownee/Jade)。
- 本次核对的官方 `1.21-neoforge` 分支提交：`6701b1eea41143b2e4713ed9c6e7d57e2595a820`。
- 源码证据：`downloads/jade/source-evidence/ObjectNameProvider.java`、`RegistryNameProvider.java`、`CorePlugin.java`、`RayTracing.java`。
- 已安装二进制的字节码证据：同目录 `ObjectNameProvider.javap.txt`、`RegistryNameProvider-ForEntity.javap.txt`、`DebugPlugin.javap.txt`。这些核对确认上述名称显示逻辑和注册名默认关闭，与下载的安装版本一致。

