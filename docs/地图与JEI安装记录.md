# 地图与 JEI 安装记录

日期：2026-10-17。安装位置：`D:\HMCL-3.9.1\neoforge-mod-development\neoforge-1.21.1\run\mods`。

| 模组 | 版本 | Modrinth 版本 |
| --- | --- | --- |
| Xaero's Minimap，小地图 | 26.6.0 | [irjR7dHk](https://modrinth.com/mod/xaeros-minimap/version/irjR7dHk) |
| Xaero's World Map，配套大地图 | 1.47.0 | [SnQ4SoDe](https://modrinth.com/mod/xaeros-world-map/version/SnQ4SoDe) |
| Just Enough Items，物品与配方查询 | 19.51.0.418 | [ufHUqt9b](https://modrinth.com/mod/jei/version/ufHUqt9b) |

三者均选用 Modrinth 标记为 Minecraft 1.21.1、NeoForge 的正式发布版本。下载原件和 API 快照保存在 `downloads/client-utilities/`。

## 前置检查

- 两个 Xaero 地图 JAR 均内嵌 XaeroLib 1.7.3，其共同 JarJar 范围为 `[1.7.0,1.8)`；无需再下载一份外置 XaeroLib。
- 小地图要求大地图（若安装）≥1.44.0；大地图要求小地图（若安装）≥26.4.0，所选版本互相满足。
- 两份地图要求 NeoForge `[21.0.110-beta,21.2.0)`，当前 21.1.259 满足。
- JEI 要求 NeoForge ≥21.1.238，当前 21.1.259 满足；没有额外第三方必需前置。
- JEI 的 Minecraft 声明使用 `[1.21,1.21.1)`，当前 FML 4.0.45 的版本兼容矩阵允许 Minecraft 1.21.1 使用 1.21 兼容判断。
- Open Parties and Claims 属于地图可选联动，并非必需前置。

三个下载文件均通过 Modrinth SHA-512 和文件大小校验，安装副本与下载原件一致；地图内嵌库文件存在。详见 [安装清单](../downloads/client-utilities/installed-manifest.json) 与 [依赖检查](../downloads/client-utilities/dependency-audit.json)。

本次只检查版本、依赖和文件，未启动或检查游戏运行状态。新增模组在下一次启动游戏时加载。源 HMCL 实例没有修改，现有键位和灵敏度也没有改动。
