# 仓库边界与首次基线

本仓库以工作区顶层为根，主模组工程位于 `neoforge-1.21.1/`。本次建立版本管理边界，不移动现有文件，不重构构建或启动方式。根 `.gitignore` 是实际追踪范围的依据；忽略只影响 Git，不删除本地文件。

## 本次保存的内容

- 主模组 Java 源码、测试源码和测试结构，Gradle Wrapper 与工程声明，模组元数据及 Mixin 配置。
- 自写玩法数据、翻译、着色器和经筛选的模型定义、来源索引；保留脚本、转换工具源码及维护文档。
- 现有源码检查需要的三个大型输入快照本次仅记录路径、大小和哈希；原快照因含第三方原始资源数据继续本地保留，见依赖记录的 `testInputs`。
- [依赖记录](dependencies.json)和[资源来源记录](asset-provenance.json)。记录只包含版本、来源、相对路径和哈希，不包含第三方 JAR、枪包、纹理或原始模型。

## 保留在本地的内容

运行实例、模组二进制、枪包、存档、用户配置、地图缓存、客户端冻结快照、构建缓存、下载及解包目录、日志和备份均继续留在原位置。原始 C4D/TGA 素材、模型转换输出、工作台预览、地图导出及第三方工程镜像也不随本次基线发布。

生产资源中的纹理、网格及来源范围尚未确认的模型定义本次保守排除；来源索引仍保留。游戏中的现有资源文件未因此改变。此次 push 保存的是代码及必要维护资料，并非完整的当前游戏实例或完整资源分发。

`docs/gun-balance/gun-assets.json`、`docs/gun-balance/proposed-gun-data.json`、`docs/compatibility/compatibility-inventory.json` 包含第三方原始数据及代码片段，本次不上传。相关 Java 检查仍会读取这些路径，因此干净 clone 后还缺这三个检查输入；本次没有改检查逻辑来掩盖缺失。

## 构建与运行的实际依赖

工程声明使用 Java 21、Minecraft 1.21.1、NeoForge 21.1.259、ModDevGradle 2.0.148 和 Gradle Wrapper 9.2.1。元数据要求 TaCZ >= 1.1.8、LR Tactical >= 0.4.3 且 < 0.5；Carpet 是可选运行集成，Xaero/Voxy 相关功能还取决于对应客户端环境。

`build.gradle` 以本地相对路径声明了 9 个 `compileOnly` JAR：TaCZ、LR Tactical、Carpet、Commons Math、LuaJ、SimpleBedrockModel、Xaero Minimap、Xaero World Map、Xaerolib。部分库从所属模组内嵌 JAR 提取；`compileOnly` 不会把它们打入附属模组。准确文件位置、下载来源及指纹见依赖记录。

普通模组运行不需要 Python、C4D 或 `model-workflows/` 工具；这些工具负责离线制作资源。当前玩法所用的完整枪库来自 `neoforge-1.21.1/run/tacz/` 等本地部署内容。依赖记录保存的是历史下载证据，不能将其等同于全部已修改枪包的自动恢复方案。

干净 clone **不能直接恢复当前完整实例**：需要补齐本地 JAR 和被排除的游戏资产、枪包及运行配置。本次未运行构建、测试或游戏，不承诺从干净 clone 已可构建或可游玩。

## 本机配置与后续整理

既有 Gradle 配置、脚本和文档中仍有本机 JDK 位置、工作区路径及代理设置；本次没有自动重写这些设置。换电脑使用前需要逐项调整。先保存基线有助于随后分批重构和迁移，但 Git 的文件移动记录不会自动更新外部路径引用。

## 许可现状

远程已有 Apache 2.0 `LICENSE`，而本地模组 `gradle.properties` 的 `mod_license` 当前仍是 `All Rights Reserved`。本次保留现状，未替用户重新选择源码许可，也未把第三方资产统一改为 Apache 2.0。

本地来源证据明确记录 PUBGMC 发布页为 All Rights Reserved；PUBG 官方图标、用户提供模型及衍生资源的来源应分别核实。来源与哈希说明文件的身份，不能作为再分发授权。此次对未确认资产的排除是提交边界，不是删除现有素材或对原作者许可作新判断。
