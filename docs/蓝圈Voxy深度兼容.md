# 蓝圈与 Voxy LOD 深度兼容

2026-10-22。针对大地图里蓝幕边缘随原生区块渲染距离出现锯齿、LOD 地形不能正确显示蓝圈范围的问题。

## 备份

修改前已复制原蓝圈 Java、着色器、几何检查、构建配置、模组元数据模板及当时的模组 JAR：

`logs/source-backups/blue-zone-voxy-20261022-222815/`

`manifest.json` 记录原相对路径、大小和 SHA-256；复制后逐个核对哈希。原有 `blue_zone.vsh`、`blue_zone.fsh`、`blue_zone.json` 保持原样，作为无 Voxy、无有效深度或兼容失败时的路径。

## 原因与修改

原蓝圈越过原生远裁剪面后将片元深度限制为 1。Voxy 正常回写 LOD 深度时，上限是 `1 - 1/(2^24-1)`，原蓝圈会被深度测试拒绝。另一方面，本次实际安装并启用的 ComplementaryUnbound_r5.8.1 的 `shaders/program/voxy.json` 设置了 `excludeLodsFromVanillaDepth: true`，主深度不含这些 LOD；此时仅靠主深度又会把近处 LOD 错当作远处背景。这两种情况都不能靠增加圆周细分解决。

Voxy 对应实现：[固定提交的深度转换着色器](https://github.com/M4G4MED/voxy/blob/d005d6713e95252347fe32844ba6db6083529a09/src/main/resources/assets/voxy/shaders/post/blit_texture_depth_cutout.frag)。本机安装 JAR 与本地源码的相关方法、字段已通过 class 文件核对。

新增可选的 `@Pseudo` Mixin，仅在 Voxy 原本的 `runPipeline` 正常完成后观察本帧结果，不取消、不替换原方法。普通管线读取 `fb`，Iris 管线读取 `fbTranslucent`；借用现有深度纹理 ID，复制本帧矩阵与相机参数。每帧开始清空，每次管线运行先让旧捕获失效；阴影、其他相机、错误维度、失效纹理及不支持的管线退回原逻辑。

新增 `blue_zone_lod` 专用着色器：

- 使用 Voxy 本帧的投影矩阵比较真正的 LOD 深度，圈前的山体遮挡蓝幕，圈后的地形显示蓝幕。
- 跳过深度 0、1 哨兵，按纹理尺寸直接取像素，支持 LOD 分辨率缩放。
- 将蓝圈自身的最大深度与 Voxy 的 D24 回写上限对齐，同时保留原生 `LEQUAL`，由原生近景、建筑与实体继续遮挡。
- 只写颜色，恢复借用的纹理单元、采样器和矩阵状态；不改 Voxy 的深度纹理参数、投影、区块缓存或帧缓冲，不增加地形加载。

下一安全区白色虚线、Xaero 地图覆盖层、服务端圈位置及伤害规则均沿用现有实现。

## 回退

在启动参数中添加 `-Dtacz_pubg.disableVoxyBlueZoneDepth=true` 并重新启动客户端，可保留新版模组的其他内容，单独关闭此次兼容，使用原蓝圈渲染。移除该参数并重启即可再次启用。

若需要恢复源码，按备份的原相对路径恢复 `BlueZoneVisuals.java`、`BlueZoneShaders.java` 和 `src/main/templates/META-INF/neoforge.mods.toml`；新文件没有元数据入口后不会参与兼容渲染。备份中的旧 JAR 是修改前的整个模组版本，恢复它也会恢复当时全部模组内容。

## 验证边界

没有启动 Minecraft。编译、GPU 合成场景检查和构建结果记录于 `logs/blue-zone-voxy-*`。

- 全量构建通过，114 个任务，22 秒；原有蓝圈几何检查 13,718 条通过。
- `scripts/Check-BlueZoneLodGpu.ps1` 使用本机 RTX 5080 Laptop 创建隐藏 OpenGL 3.3 上下文，直接编译实际发货的两个 LOD 着色器。17 个场景、13,125 条断言通过，覆盖圈前／圈后 LOD、0／1 哨兵、主深度缺失／饱和、原生近景遮挡和三种分辨率缩放；逐像素核对输出及主深度、LOD 深度不变。
- `logs/blue-zone-voxy-verification.json` 记录备份复核、安装的 Voxy JAR 不变、产物条目及 SHA-256。生产 JAR 不含新增 GPU 测试类。

Iris TAA 的抖动在 Voxy 顶点着色器中额外施加，不包含在捕获的 MVP 中。因此 LOD 轮廓仍可能有亚像素偏移；本次没有解析或执行光影包中的自定义 TAA 代码。实际光影合成、移动缩圈及长时间运行稳定性，需要在原大地图中复测，离线检查不能代替游戏视觉验证。
