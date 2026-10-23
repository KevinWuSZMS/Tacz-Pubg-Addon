# TaCZ 按 Z 装配时闪烁：静态调查与局部修复

日期：2026-10-18。目标版本：当前安装的 TaCZ NeoForge 1.21.1 / 1.1.8-hotfix-r7。

本次未启动游戏。源码和已安装 JAR 字节码能够证实以下缺陷；不能据此声称已实测确认用户看到的“两次闪烁”全部消失，也不能将完整症状确定归因给某一个模组。

## 1. 调用链核对

- `RefitKey.onRefitPress` 只创建或关闭 `GunRefitScreen`。创建界面调用 `RefitTransform.init()`，重置配件观察角度；不发送安装/卸载包，不触发切出/切入动画。
- 真正点击配件后才发送 `ClientMessageRefitGun` / `ClientMessageUnloadAttachment`，这些网络处理不是仅按 Z 的必经路径。
- `FirstPersonRenderGunEvent.applyFirstPersonGunTransform` 对界面进度和瞄准进度分别进行 `SecondOrderDynamics.update`，再执行位置、旋转、动画约束。
- 当前 `options.txt` 中，TaCZ 装配与 Xaero 放大小地图同时使用 Z。这是已确认的键位冲突，但没有实测证据证明它导致枪体闪烁，因此本次不擅自更改用户键位。

## 2. 原 TaCZ 的无效帧采样

`applyJumpingSway` 用以下方法计算垂直速度：

```java
double posY = Mth.lerp(partialTicks, player.yOld, player.getY());
float velocityY = (float) (posY - player.yOld) / partialTicks;
```

`partialTicks == 0` 是 tick 边界合法输入，此时分子、分母均为 0，结果为 `NaN`。如果此帧进入起跳/落地状态分支，非有限值可以继续传给跳跃平滑器和骨骼偏移。骨骼变换非有限时会导致该帧枪体无法正常绘制。此风险和光影开关无关。

修复：`FirstPersonPoseSafetyMixin` 在跳跃采样入口跳过零值、负值和非有限插值参数；只省略该无效帧的跳跃晃动。下一正常帧仍使用原版跳跃动画，没有改射击、换弹、装配、后坐力或服务器数据。

另对**装配界面的打开进度**作局部边界保护：正常平滑值保持不变；非有限值回退当前原始进度，输出限制在 `[0, 1]`。瞄准弹簧仍走原逻辑。这样改装观察位置和约束权重不会使用 NaN、无穷或负权重。这属于防御性修复，未认定原弹簧必定造成双闪。

## 3. 本附属的共享地面模型状态

本轮地面模型修复同时检查到：原 `GroundGunModelRenderer` 调用 TaCZ 的完整 `BedrockGunModel.render`。此函数会写入共享模型的 `currentGunItem`、配件缓存、扩容等级、转接件集合、瞄具底座状态，并触发模板缓冲区等绘制行为；此前的骨骼快照只恢复骨骼和约束，没有覆盖以上全部缓存。

这说明本附属原地面绘制确实存在状态污染风险。地面修复将绘制与测量改为隔离的静态网格路径，避免地面物品修改第一人称装配界面使用的模型缓存。具体实现和模型落地对齐见本轮掉落物方案文档。

## 4. 验证边界

- 已安装 r7 JAR 的 `javap` 字节码核对：目标方法、零帧除法和两次 dynamics 调用均存在。证据：`logs/refit-firstperson-r7-bytecode.txt`。
- 纯 Java 边界回归：独立重现原式 `0/0`，检查无效跳跃样本被排除、正常样本保留、装配进度及 `1-progress` 权重有限，**37 项断言通过**。
- 新 Mixin 要求该方法存在两处指定 dynamics 调用；版本变更导致目标不匹配时直接报告，不静默假装生效。
- 编译和整合检查由主任务统一完成；本文不表示已经在游戏中验证画面。保留用户此前“不启动游戏检查”的要求。

## 对应文件和来源

- `neoforge-1.21.1/src/main/java/com/example/examplemod/gunbalance/client/FirstPersonPoseSafety.java`
- `neoforge-1.21.1/src/main/java/com/example/examplemod/gunbalance/mixin/FirstPersonPoseSafetyMixin.java`
- `neoforge-1.21.1/src/test/java/com/example/examplemod/gunbalance/client/FirstPersonPoseSafetyChecks.java`
- 原 Mixin 配置备份：`logs/source-backups/2026-10-18-loot-catalog-alignment/refit/`。
- 匹配安装版本的本地源码：`logs/analysis-src/tacz-r7/`。
- [移植仓库 FirstPersonRenderGunEvent](https://github.com/MUKSC/TACZ-1.21.1/blob/neoforge/1.21.1/src/main/java/com/tacz/guns/client/event/FirstPersonRenderGunEvent.java)、[RefitTransform](https://github.com/MUKSC/TACZ-1.21.1/blob/neoforge/1.21.1/src/main/java/com/tacz/guns/client/animation/screen/RefitTransform.java)。网页分支可能继续变化，以已安装 r7 字节码和本地固定源码为本次实施依据。
