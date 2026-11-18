# PUBG 护甲实装依据

六件原生装备：`helmet_1/2/3` 使用 HEAD，`vest_1/2/3` 使用 CHEST。头盔健康为 80/150/230，背心为 200/220/250；完整装备分别减伤 30%/40%/55%。这些通用 PUBG 装备值可交叉核对 [装备资料](https://pubg.wiki.gg/wiki/Vests) 和 [Google Play 的 PUBG 官方游戏编辑资料](https://play.google.com/store/apps/editorial?hl=en_GB&id=mc_editorialevergreen_post_install_pubg_mobile_equipment_fcp)。

三档完好护甲的伤害倍率分别为 0.7、0.6、0.45；以护甲吸收的伤害乘 5 换算耐久损耗。本实现保留 0.01 护甲健康精度，避免 TaCZ 的普通/穿甲分段伤害反复取整。

[PUBG PC 7.2 官方公告](https://store.steampowered.com/news/posts/?appids=578080&enddate=1589963679&feed=steam_community_announcements) 确认：背心耗尽耐久后仍存在，保留背包容量且提供 20% 保护。头盔耗尽则损坏移除。最后一丝完整耐久仍保护整颗子弹，两段 TaCZ 伤害使用同一个受击瞬间快照。

[PUBG PC 6.2 官方公告](https://store.steampowered.com/news/posts/?appids=578080&enddate=1584075649&feed=steam_community_announcements) 确认：破片手雷由背心减伤但不消耗背心耐久。本实现只对明确识别的本项目 FRAG 应用此规则；C4、蓝圈、火焰和跌落不被错误套用枪击减伤。

TaCZ 1.1.8 hotfix-r7 的 `EntityHurtByGunEvent.Pre` 提供真实 `isHeadShot()` 和两段 DamageSource。本实现沿用 TaCZ 的爆头倍率、射程衰减和弹道，只决定对应头/胸甲的防护。原生弓箭和近战没有 TaCZ 的命中部位信息，按躯干命中处理，这是明确的适配简化。

`PubgArmorItem` 是真正的 ArmorItem，故原生装备槽、右键穿戴和自动同步生效；材料不提供第二套 vanilla 护甲数值，也不绘制重复的平面原版护甲。默认自动耐久扣除由 item hook 禁止，仅在被实际接受的伤害结束后扣选中部位的耐久。游戏外普通原版护甲不会被此模块改动；特殊 PUBG 伤害规则仅在已开局参赛者上应用。

装备层使用每件物品自己的三维模型，随玩家头部或躯干骨骼旋转；两种风格称为「萌趣世界」与「像素硬核」。左下统一装备面板按头盔、防弹衣、背包排列，显示物品图标、等级、健康/容量数值及进度条；空槽灰显。装备和容量直接使用原生 ItemStack/服务端同步状态，界面不自行决定数值。320 GUI 像素宽时面板收窄，保持在快捷栏和中央生命条左边；更小的窗口将面板上移。降落伞飞行系统临时保存完整胸部 ItemStack，包括护甲损伤，并在飞行结束后还原，未另建会覆盖原物品的存储。

必要回归覆盖：甲等级/破甲状态、PUBG→Minecraft 健康换算、TaCZ AP 分段不会双扣、无效伤害及耐久溢出边界。`ArmorHudLayoutChecks` 另覆盖 240–854 像素 GUI 宽度与 180–480 高度组合，检查面板不超出屏幕、不遮挡中央生命条及原版快捷栏。两项独立 Java 检查均已通过；真实客户端视觉/对枪验证须与整包游戏验收区分。
