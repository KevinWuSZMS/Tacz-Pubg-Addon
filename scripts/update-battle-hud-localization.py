"""Merge semantic battle HUD labels and agent fragments into the existing bilingual catalog."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'neoforge-1.21.1/src/main/java/com/example/examplemod/pubgsettings/PubgSettings.java'
LANG = ROOT / 'neoforge-1.21.1/src/main/resources/assets/tacz_pubg/lang'
fields = re.findall(r'\b[nbc]\(f,"([^"]+)"', JAVA.read_text(encoding='utf-8'))
fields += [f'team.color_{n:03}' for n in range(1, 101)]
zh_fields = {}
for line in '''
hud.bar_width|状态栏宽度
hud.align_hotbar|血条和状态栏与快捷栏等宽
hud.equipment_icon_size|装备图标尺寸
hud.row_height|状态行高度
hud.equipment_gap|装备图标间距
hud.health_height|生命条高度
hud.boost_height|能量条高度
hud.bar_gap|生命与能量条间距
hud.bottom_margin|状态栏距底部
hud.health_red_threshold|红色生命阈值（比例）
hud.health_apricot_threshold|杏色生命阈值（比例）
hud.health_red|低生命红色
hud.health_apricot|中低生命杏色
hud.health_white|未满生命白色
hud.health_full|满生命灰色
hud.health_reference|75%斜纹颜色
hud.health_treatment|治疗目标覆盖颜色
hud.health_next_step|下一步恢复预告颜色
hud.boost_color|能量条颜色
hud.panel_color|信息底板颜色
hud.icon_inactive_alpha|未生效图标透明度
hud.weapon_stack_width|侧边物品栈宽度
hud.weapon_stack_row_height|侧边物品行高
hud.weapon_stack_gap|侧边物品行间距
hud.fuse_radius|引信弧半径
hud.fuse_offset_x|引信弧水平偏移
hud.fuse_line_width|引信弧线宽
hud.fuse_warning_fraction|引信警戒剩余比例
hud.hide_hotbar|对局中隐藏原版快捷栏
hud.fuse_color|引信弧正常颜色
hud.fuse_warning_color|引信弧警戒颜色
hud.fuse_track_color|引信弧轨道颜色
medicine.recovery_step_ticks|治疗步进间隔（刻）
medicine.bandage_recovery_ticks|绷带完成后的恢复总时长（刻）
medicine.first_aid_step_percent|急救包每步恢复生命百分点
medicine.circle_radius|动作圆环半径
medicine.circle_line_width|动作圆环线宽
medicine.circle_offset_y|动作圆环垂直偏移
medicine.circle_background|动作圆盘背景颜色
medicine.circle_track|动作圆环轨道颜色
medicine.circle_progress|动作圆环进度颜色
medicine.bandage_use_ticks|绷带使用时长（刻）
medicine.first_aid_use_ticks|急救包使用时长（刻）
medicine.med_kit_use_ticks|医疗箱使用时长（刻）
medicine.energy_drink_use_ticks|能量饮料使用时长（刻）
medicine.painkiller_use_ticks|止痛药使用时长（刻）
medicine.adrenaline_use_ticks|肾上腺素使用时长（刻）
dbno.enabled|启用队伍倒地
dbno.health|独立倒地生命上限
dbno.protection_ticks|倒地初始保护（刻）
dbno.stationary_seconds|静止倒地生命耗尽时间（秒）
dbno.crawl_multiplier|倒地爬行速度倍率
dbno.swim_multiplier|倒地游泳速度倍率
dbno.damage_multiplier|倒地普通枪伤倍率
dbno.frag_multiplier|倒地破片手雷额外倍率
dbno.sniper_multiplier|倒地狙击枪伤害倍率
dbno.crossbow_multiplier|倒地弩伤害倍率
dbno.underwater_seconds|倒地水下存活时间（秒）
rescue.distance|救援最大距离（格）
rescue.ticks|救援耗时（刻）
rescue.health_percent|救援完成生命百分比
rescue.move_tolerance|救援移动容许距离（格）
rescue.aim_dot|救援朝向点积阈值
rescue.pause_bleeding|救援期间暂停倒地流血
combat.assist_window_ticks|伤害助攻追溯窗口（刻）
combat.assist_min_damage|伤害助攻最少伤害（百分生命）
combat.message_rows|中下战斗消息条数
combat.message_seconds|中下战斗消息持续时间（秒）
combat.fade_in_seconds|消息淡入时间（秒）
combat.fade_out_seconds|消息淡出时间（秒）
combat.move_seconds|消息移位时间（秒）
combat.counter_seconds|淘汰助攻统计保留时间（秒）
combat.feed_rows|右上播报条数
combat.feed_seconds|右上播报持续时间（秒）
combat.center_y|中下消息垂直位置（比例）
combat.message_scale|战斗消息文字倍率
combat.counter_scale|累计淘汰助攻文字倍率
combat.feed_scale|右上播报倍率
combat.feed_width|右上播报宽度
combat.feed_top|右上播报距顶部
combat.feed_row_height|右上播报行高
combat.right_margin|右上信息距右边缘
combat.top_number_scale|右上统计数字倍率
combat.top_height|右上统计高度
combat.center_row_height|中下消息行高
combat.kill_color|淘汰累计颜色
combat.assist_color|助攻累计颜色
combat.own_color|本人播报强调颜色
combat.text_color|战斗播报文字颜色
combat.older_color|较旧中下消息颜色
map.headerGap|圈信息与小地图间距
map.headerHeight|圈信息高度
map.gaugeHeight|圈距离条高度
map.headerBackgroundOpacity|圈信息背景不透明度
map.blueColor|蓝圈进度颜色
map.safeColor|白圈端点颜色
map.playerColor|圈条玩家标记颜色
map.dangerColor|圈外标记颜色
map.warningColor|缩圈警报颜色
map.arrowColor|缩圈移动箭头颜色
map.arrowSpacing|缩圈箭头间隔
map.arrowSpeed|缩圈箭头速度（像素/秒）
map.arrowOpacity|缩圈箭头不透明度
map.navigationEnabled|显示圈外安全区连线
map.navigationWidth|安全区连线宽度
map.navigationColor|安全区连线颜色
map.navigationOpacity|安全区连线不透明度
map.showSafeDistance|显示距安全区距离
map.defaultMargin|默认小地图边距
'''.strip().splitlines():
    key, value = line.split('|', 1)
    zh_fields[key] = value
for i, word in enumerate(['first','second','third','fourth','fifth','sixth','seventh','eighth','ninth'], 1):
    zh_fields[f'dbno.bleed_{word}_seconds'] = f'第{i}次倒地移动耗尽时间（秒）'
for i in range(1, 101):
    zh_fields[f'team.color_{i:03}'] = f'第{i}队颜色'
missing = set(fields) - set(zh_fields)
if missing:
    raise SystemExit(f'Missing Chinese field labels: {missing}')

prefix = 'screen.tacz_pubg.battle_settings.'
entries = {
    prefix+'title': ['战斗 HUD 与倒地救援', 'Battle HUD and rescue'],
    prefix+'search': ['搜索设置', 'Search settings'],
    prefix+'save': ['保存到服务器', 'Save to server'],
    prefix+'restore': ['本组恢复默认', 'Reset this group'],
    prefix+'reload': ['重新载入', 'Reload'],
    prefix+'discard': ['放弃尚未保存的修改？', 'Discard unsaved changes?'],
    prefix+'discard_hint': ['当前草稿尚未保存到服务器。', 'The current draft has not been saved to the server.'],
    prefix+'loading': ['正在载入服务器设置…', 'Loading server settings…'],
    prefix+'draft': ['草稿尚未保存', 'Unsaved draft'],
    prefix+'saving': ['正在保存…', 'Saving…'],
    prefix+'saved': ['已保存并同步给所有玩家', 'Saved and synchronized to all players'],
    prefix+'saved_draft': ['服务器已保存，当前仍有新草稿', 'Server saved; newer draft edits are retained'],
    prefix+'hint': ['设置随当前存档保存；20刻=1秒。颜色为ARGB。', 'Saved with this world; 20 ticks = 1 second. Colors use ARGB.'],
    prefix+'invalid': ['输入无效：%s', 'Invalid input: %s'],
    prefix+'conflict': ['设置版本已变化或存档不可读；草稿已保留，请重新载入。', 'Settings changed or the save is unreadable. Draft retained; reload to resolve.'],
    prefix+'denied': ['需要服务器管理员权限', 'Server operator permission is required'],
    prefix+'timeout': ['未收到保存回复；草稿已保留。', 'No save response received; draft retained.'],
    prefix+'color_hint': ['输入 #AARRGGBB 或 #RRGGBB；AA为不透明度。', 'Enter #AARRGGBB or #RRGGBB; AA is opacity.'],
    prefix+'range': ['范围 %s～%s；默认 %s', 'Range %s to %s; default %s'],
    prefix+'default': ['默认：%s', 'Default: %s'],
    prefix+'on': ['开启', 'On'], prefix+'off': ['关闭', 'Off'],
    'screen.tacz_pubg.hud_layout.component.combat_messages': ['中下战斗消息', 'Combat messages'],
    'screen.tacz_pubg.hud_layout.component.combat_feed': ['右上淘汰播报', 'Combat feed'],
}
for key, zh, en in [('all','全部','All'),('hud','状态栏','Status HUD'),('medicine','用药与动作','Medicine/actions'),('dbno','倒地','Knockdown'),('rescue','救援','Rescue'),('combat','战斗播报','Combat feed'),('map','地图与圈','Map/circle'),('team','队伍颜色','Team colors')]:
    entries[prefix+'group.'+key] = [zh, en]
for field in fields:
    english = re.sub(r'([a-z])([A-Z])', r'\1 \2', field.split('.',1)[1]).replace('_',' ').capitalize()
    if field.startswith('team.'):
        english = f'Team {int(field[-3:])} color'
    entries[prefix+'field.'+field] = [zh_fields[field], english]
for key, zh, en in [
    ('knock','你用 %s 击倒了 %s','You used %s to knock %s'),
    ('elimination','你用 %s 淘汰了 %s','You used %s to eliminate %s'),
    ('headshot','你用 %s 命中头部淘汰了 %s','You used %s to eliminate %s with a headshot'),
    ('assist','你协助淘汰了 %s','You assisted in eliminating %s'),
    ('revive','你救起了 %s','You revived %s'),
    ('kills','%s 淘汰数','%s eliminations'), ('assists','%s 助攻','%s assists'),
    ('top_kills','%s 淘汰','%s kills'), ('top_assists','%s 助攻','%s assists'),
    ('kill_label','淘汰','kills'), ('assist_label','助攻','assists'),
    ('environment','环境','environment'),
]:
    entries['screen.tacz_pubg.combat.'+key] = [zh, en]
for fragment in ['combat-status-hud-localization.json', 'combat-server-localization.json']:
    path = ROOT / 'logs' / fragment
    if path.exists():
        entries.update(json.loads(path.read_text(encoding='utf-8-sig')))
for index, locale in enumerate(['zh_cn','en_us']):
    path = LANG / (locale+'.json')
    content = json.loads(path.read_text(encoding='utf-8-sig'))
    content.update({key: value[index] for key,value in entries.items()})
    path.write_text(json.dumps(content,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f'Merged {len(entries)} battle HUD keys, including {len(fields)} administrator fields.')
