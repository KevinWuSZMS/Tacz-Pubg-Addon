# Voxy 1.21.1 构建与依赖检查

日期：2026-10-17。目标环境：Minecraft 1.21.1、NeoForge 21.1.259、Java 21。

## 源码选择

采用 [M4G4MED/voxy 的固定提交 d005d6713e95252347fe32844ba6db6083529a09](https://github.com/M4G4MED/voxy/tree/d005d6713e95252347fe32844ba6db6083529a09)。源 HMCL 实例中的 `voxy-offline-0.2.14-alpha-M4G4MED-1.21.1.jar` 在自身元数据中记录了同一提交和 `0.2.14-alpha` 版本。

源码保存在 `external/voxy-1.21.1/`，是独立构建项目；主模组的 `neoforge-1.21.1/src/` 不需要加入 Voxy 源码。Git 检出固定提交，避免仓库默认的 Minecraft 1.20.1 分支或新版 Sodium 分支被误用。

这一版 Voxy 是 Fabric 构建，通过 Sinytra Connector 在 NeoForge 运行。其 Iris 集成代码仍保留；另一社区原生 NeoForge 移植在说明中列出 Iris 尚未移植，因而此次选择源实例同源的方案。相同提交不保证产物与源实例的定制 `offline` JAR 二进制完全相同；本次从公开源码自行构建，不直接复制那个已编译文件。

## 必需依赖

| 组件 | 当前使用版本 | 说明 |
| --- | --- | --- |
| Minecraft | 1.21.1 | 与 Voxy 的版本要求一致 |
| NeoForge | 21.1.259 | 满足 Connector ≥21.1.97、FFAPI ≥21.1.169 的要求 |
| Java | Microsoft OpenJDK 21.0.10 | 编译目标 Java 21，游戏使用 Java 21 |
| Voxy | 0.2.14-alpha，固定提交 d005d671 | 从源码构建 |
| Sinytra Connector | 2.0.0-beta.14+1.21.1，full JAR | 负责 Fabric 模组转换与加载 |
| Forgified Fabric API | 0.116.7+2.2.4+1.21.1 | 使用 NeoForge 版 API；提供 Voxy 所需 Fabric API 模块 |
| Fabric Loader | 内嵌 0.18.4 兼容层 | Connector/FFAPI 已提供，不另装独立 Fabric Loader |
| Sodium | 0.6.13+mc1.21.1，NeoForge 版 | Voxy 要求 ≥0.6.13；沿用现有文件 |

保留现有 NeoForge Iris 1.8.12、Lithium 0.15.3 和 Reese's Sodium Options。没有重复安装它们的 Fabric 版。

Mod Menu、Nvidium、Chunky、Vivecraft、Flashback 等出现在上游编译或开发运行依赖中，不是这份 Voxy 元数据声明的必需运行模组，不因此全部安装。Voxy 的 LMDB/Zstd Windows 原生库、RocksDB、Jedis、commons-pool2、lz4 和 xz 由构建产物内嵌；无需逐个放入 `run/mods`。SQLite JDBC 等可选 Distant Horizons 存档导入依赖不属于普通 LOD 渲染前置。

Voxy 还依赖显卡提供所需 OpenGL 功能；源实例日志记录 RTX 5080 Laptop GPU 和 OpenGL 4.6。当前开发客户端的实际渲染初始化结果以本次启动日志为准。

## 构建

在工作区运行：

```powershell
.\scripts\Build-Voxy.ps1
```

该脚本使用本机 JDK 21，调用源码项目自带 Gradle 9.4.1，并将构建日志保存至 `logs/voxy-build.log`。它沿用本机现有下载代理和项目临时目录设置，执行结束后恢复当前进程的 Java 环境变量。脚本给 Loom 设置 `fabric.loom.ci=true`，省略第三方依赖的源码下载和映射，Voxy 自身源码仍正常编译；首次构建在加入此优化前执行了完整的第三方源码映射。

上游声明 Fabric Loom `1.11-SNAPSHOT`，此次实际解析为 `1.11.8`。安装时使用 `build/libs/` 中经过 `remapJar` 处理的产物，不使用开发环境命名的 JAR。对 Voxy 的后续源码修改，应在这个独立项目内完成并重新构建。

本机首次构建结果：**BUILD SUCCESSFUL，耗时 5 分 50 秒**。产物为 `voxy-0.2.14-alpha-d005d67.jar`，12,589,615 字节；已经复制至 `neoforge-1.21.1/run/mods/`，安装文件 SHA-512 与构建产物一致。Voxy 主类为 Java 21 字节码（major 65），11 个内嵌库文件全部存在，Iris 集成类和 Access Widener 完整，源码 Git 工作区保持干净。

## 文件与依赖追溯

- [全量静态依赖审查](../logs/voxy-dependency-audit.json)：最终重读了实际安装的 39 个顶层 JAR、131 份模组元数据和 114 个内嵌 JAR 声明，283 条必需依赖检查全部通过，无缺失内嵌文件或声明冲突。
- [Connector/FFAPI 复制清单](../downloads/voxy/dependency-copy-manifest.json)：复制前后源文件 SHA-256、大小和修改时间，以及 Modrinth SHA-512 查询验证。
- [Voxy/Connector 配置复制清单](../downloads/voxy/config-copy-manifest.json)：原样复制源 `voxy-config.json`、`connector.json`；未写入源实例。
- [光影包补丁审查](../downloads/voxy/shader-patch-audit.json)：各光影包的 Voxy 补丁入口和版本。
- [构建与安装清单](../downloads/voxy/build-install-manifest.json)：完整源码提交、工具版本、产物哈希、内嵌库和安装位置。

NeoForge 的 JarJar 会对重复的内嵌 Fabric API 模块选择版本，无需手工拆包。部分既有模组的 Minecraft 依赖区间以 1.21 为上限，当前 FML 4.0.45 的 `VersionSupportMatrix` 提供 1.21.1 → 1.21 兼容判断；静态审查已记录此例外。声明检查通过仍需实际启动确认 Mixin、原生库与内部 API 的兼容性。

## 光影与使用

现有五个光影包均含 Voxy 补丁入口，包括当前 Iris 选中的 Complementary Unbound r5.8.1。Voxy 要求补丁版本为 1。补丁包含 GLSL include 和预处理指令，由 Iris 预处理后交给 Voxy/Gson 解析；不能用严格 JSON 解析器直接判定原始文件是否有效。

启动方式仍为 `scripts/run-client.bat`，外部模组目录仍为 `neoforge-1.21.1/run/mods/`。Voxy 只会显示已采集或已导入的远景数据；首次进入没有缓存的世界，需要先加载周围区块建立 LOD。没有额外安装自动生成世界的附属。

## 本次运行结果与开发环境修复

历史记录：在用户提出后续不再检查游戏运行状态之前，已完成客户端加载和进入单人世界验证。Voxy 当时创建了 `IrisVoxyRenderPipeline` 和 `MDICSectionRenderer`；RTX 5080 Laptop GPU 提供 OpenGL 4.6，TaCZ 默认枪包、1,475 个配方和当时的 examplemod 同时加载，窗口当时保持打开。当前模组已为 `tacz_pubg`，不保留旧示例注册物品。历史证据见[运行记录](../downloads/voxy/runtime-verification.json)及 `logs/voxy-client-launch.log`；之后新增内容、编辑覆盖层与新物品交互不包含在该次游戏运行验证中。

第一次启动的根因是 Connector 在开发环境找不到 `connector.clean.path`，进而取消模组加载，Carpet 读取自身容器时产生连带异常。已按 [官方 Adapter 插件实现](https://github.com/Sinytra/Adapter/blob/38d859495426fadb5db8b52afdb88deaa7132322/userdev/src/main/java/org/sinytra/adapter/userdev/AdapterUserdevPlugin.java) 的核心配置修复 `neoforge-1.21.1/build.gradle`：

```groovy
def connectorCleanMinecraft = layout.buildDirectory
    .file('moddev/artifacts/minecraft-clean-1.21.1.jar').get().asFile
neoForge {
    additionalMinecraftArtifacts.put('vanillaDeobfuscated', connectorCleanMinecraft)
    runs.configureEach {
        systemProperty 'connector.clean.path', connectorCleanMinecraft.absolutePath
    }
}
```

ModDevGradle 自动生成该干净 Mojang 映射文件，启动无需依赖固定哈希的全局缓存路径。首次失败日志保存在 `logs/voxy-client-first-failed.log`。

日志中仍有以下非致命信息，此次没有为消除日志而更改上游源码或源实例：

- Voxy 使用 ClassLoader 列举包目录时捕获 NPE；它此前已从模组路径收集类，之后正常注册配置类型并创建渲染管线。
- C2ME 的可选 `natives_math` 模块为 Java 22 字节码，在 Java 21 下自动禁用；其余模块继续加载。
- Distant Horizons 旧存档导入缺少可选 SQLite JDBC 等导入支持；普通世界的 Voxy 远景初始化成功。
- 当前光影包对 1.21.4 Pale Garden 的自定义 uniform 存在警告；当前 1.21.1 的 Iris/Voxy 管线仍成功创建。

完整游戏玩法和长期稳定性没有全面测试。**按用户最新要求，此后默认仅做构建、依赖与文件检查，不主动启动或检查游戏运行状态。**

## 一手来源

- [固定提交的构建配置](https://github.com/M4G4MED/voxy/blob/d005d6713e95252347fe32844ba6db6083529a09/build.gradle)
- [固定提交的 Fabric 模组元数据](https://github.com/M4G4MED/voxy/blob/d005d6713e95252347fe32844ba6db6083529a09/src/main/resources/fabric.mod.json)
- [Iris 光影补丁加载实现](https://github.com/M4G4MED/voxy/blob/d005d6713e95252347fe32844ba6db6083529a09/src/main/java/me/cortex/voxy/client/iris/IrisShaderPatch.java)
- [Sinytra Connector](https://modrinth.com/mod/connector)
- [Forgified Fabric API](https://modrinth.com/mod/forgified-fabric-api)
