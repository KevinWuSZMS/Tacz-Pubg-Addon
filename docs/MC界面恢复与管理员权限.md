# MC 界面恢复、下圈预告与 OP 权限

本轮按 2026-10-19 的五项反馈调整游戏内界面与权限。

## 界面与地图

- 恢复 Minecraft 原生按钮、输入框、列表及背景。三步建局、五档物资、独立保存等功能保留。
- 此前的自绘风格源文件备份在 `logs/source-backups/mc-ui-op-20261019/current-style/`；其中 `manifest.json` 记录相对路径与 SHA-256，恢复说明解释如何仅恢复样式，避免覆盖本轮逻辑修复。
- “创建地图”从地图列表进入；取消编辑或确认放弃后直接返回地图列表，保留“继续编辑”选项；移除普通流程中的单地图中转页。
- 基础设置的物资档位按钮与类别概率合计分行显示，按当前字体宽度居中合计文字。
- 下一个安全区只在 Xaero 大地图、小地图显示白色虚线。当前蓝圈边界为蓝色实线，等待和收缩期间，下圈中心与半径固定到当前阶段的服务端目标；蓝圈向目标移动。世界中的白色预告墙／线已移除。

### 下圈虚线稳定性修正（2026-10-19）

- 当前圈、下圈和运行状态放在同一份有序地图快照中同步，下圈不再依赖 80 tick 后会过期的 HUD 数据。暂停或暂时收不到 HUD 时，继续显示最近一次服务端确认的下圈；结束、切图或进入无下一圈阶段时随地图快照清除。
- 运行中的当前圈颜色不再由“是否取到下圈”决定，避免丢失预告时变成白色实线。大地图编辑草稿仍独立显示；打开编辑器不会隐藏小地图上的对局预告。
- 虚线按投影后的长度生成短线和间隙，只计算可见圆弧，取消整圈最多 256 段造成的巨大空隙。缩小到小圆时同步减少线段数量和线宽，保持间隙；轮廓最后覆盖在区域填色、当前圈和航线上方。
- 大地图和小地图共用上述实现。下一圈边界在视口之外时仍正常裁剪，不另造屏幕内的假边界。
- 修改前文件备份：`logs/source-backups/map-circle-only-20261019-231842/`。地图同步协议版本由 4 升为 5，联机两端需同时更新。

验证命令：`gradlew.bat --console=plain jar mapOverlayChecks xaeroOverlayChecks blueZoneVisualGeometryChecks matchGameChecks matchRuntimeChecks`。结果见 `logs/map-circle-only-checks.log`，全部通过。

几何检查图：`neoforge-1.21.1/build/reports/xaero-circle-preview.png`，直接使用共用虚线算法离线绘制，覆盖等待、接近重合的收缩末段、旋转小地图、大倍率缩放和极大圆弧；它不是 Minecraft 游戏截图，尚未进行游戏内渲染验收。

## 管理员权限

入口为游戏内 `config → 基础设置 → 管理员权限`。额外权限仅按服务端权限等级 2 判断，不为普通玩家提权；关闭额外权限仍保留正常参赛者的合法战斗行为。

| 开关 | 默认 | 对应 gamerule |
| --- | --- | --- |
| 局外伤害对局假人 | 开 | `pubgOpDamageBots` |
| 局外伤害参赛真人 | 关 | `pubgOpDamagePlayers` |
| 绕过友伤保护 | 关 | `pubgOpBypassFriendlyFire` |
| 绕过准备期保护 | 开 | `pubgOpBypassPreparationProtection` |
| 免疫蓝圈伤害 | 关 | `pubgOpImmuneBlueZone` |
| 保留原游戏模式 | 关 | `pubgOpKeepGameMode` |

伤害假人的默认开关允许未参赛或已淘汰的 OP 对本附属受管假人造成伤害，近战及有玩家伤害来源的弹丸均由服务端判断。友伤和准备期另受各自开关控制；暂停阶段继续保护。

例如：

```mcfunction
/gamerule pubgOpDamageBots true
/gamerule pubgOpImmuneBlueZone true
```

配置页与 gamerule 编辑的是同一世界的有效设置。命令修改写入同一份存档配置并更新版本；配置保存反映回 gamerule。发生写入失败时回滚 gamerule，不显示虚假的保存成功。

“保留原游戏模式”按本局开局时的设置生效：关闭时沿用开局切冒险模式；开启后 OP 保留原模式并正常计入对局。局中关闭不会追溯淘汰已获准保留模式的 OP，下一局按新值决定。创造、旁观本身的原版伤害免疫仍然存在。

## 构建与验证边界

构建产物：`neoforge-1.21.1/build/libs/tacz_pubg-1.0.0.jar`。本轮日志：`logs/mc-ui-op-build.log`。

不主动启动或关闭客户端。构建、布局、几何、持久化与权限逻辑检查不等同于游戏内操作和射击验收；Java 改动需重启开发客户端后加载。
