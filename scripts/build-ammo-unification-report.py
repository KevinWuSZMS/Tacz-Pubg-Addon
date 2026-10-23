"""Read-only gun/ammo unification proposal. Writes documentation artifacts only."""
from __future__ import annotations

import csv
import html
import json
import re
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs' / 'compatibility'
GUNS = json.loads((ROOT / 'docs/gun-balance/gun-assets.json').read_text(encoding='utf-8'))['guns']
INVENTORY = json.loads((OUT / 'compatibility-inventory.json').read_text(encoding='utf-8'))
POLICY = {g['gunId']: g for g in json.loads((OUT / 'pubg-scope-policy.json').read_text(encoding='utf-8'))['guns']}
MODELS_PATH = OUT / 'ammo-model-inventory.json'
MODELS = {a['id']: a for a in json.loads(MODELS_PATH.read_text(encoding='utf-8'))['ammo']} if MODELS_PATH.exists() else {}
AMMO = {a['id']: a for a in INVENTORY['ammo']}
RESEARCH_PATH = ROOT / 'logs/ammo-unification-research.json'
RESEARCH = json.loads(RESEARCH_PATH.read_text(encoding='utf-8')) if RESEARCH_PATH.exists() else {}
SOURCES = dict(RESEARCH.get('sources',{}))
SOURCES.update({
 'local_gun_data':{'title':'本地全部枪械原始data与原图','url':'../gun-balance/gun-assets.json','supports':'当前实际ammo字段、图标、名称与所引用的原数据。'},
 'local_ammo_index':{'title':'本地78个ammo索引与模型检查','url':'ammo-model-inventory.json','supports':'当前ammo ID、显示名及3D/2D实际模型。'},
 'local_comparison':{'title':'CBRG与PUBG逐项替代资产对照','url':'../PUBG枪械逐项对照.md','supports':'当前名称意图与模型替代不一致的边界。'},
 'vector45':{'title':'KRISS Vector原厂规格表：SMG9与SMG45','url':'https://store.kriss-usa.com/download/KRISS_VECTOR_G2/2022_KV_G2_SMG_DS.pdf','supports':'分别列VectorSMG9=9×19、VectorSMG45=.45ACP。'},
 'm1a1':{'title':'Auto-Ordnance原厂Thompson资料','url':'https://www.auto-ordnance.com/PDF/GAAP-190600.pdf','supports':'M1标.45ACP，叙述M1A1；本地M1A1模型与PUBG9mm玩法区别。'},
 'ump45':{'title':'HK 2000原厂价目手册（原文镜像）','url':'https://s3.amazonaws.com/hk-manuals/files/Catalogs/Price_Lists/2000_HK_LEO_Pricelist.pdf','supports':'页2明确UMP45=.45ACP；镜像域非现行原厂域，不能推现行UMP9也用.45。'},
 'makarov':{'title':'DIA/US Army公开识别手册DST-1110H-394-76（档案镜像）','url':'https://mail.bulletpicker.com/pdf/DST-1110H-394-76.pdf','supports':'SectionI识别MakarovPM为9×18；只引用弹种身份，不引用操作流程。'},
})
SOURCE_ALIAS = {'cartridge_762_distinct':'saami_interchange','glock17':'g17','m14_mini14':'m1a','pubg_301':'pubg30',
                'hk_ump':'ump','ots33_pm63':'pm63','webley1913':'webley','launcher_types':'at4'}

# A family is a game design recommendation, never a real-world loading instruction.
# Named ammunition with distinct projectile effects retains that variant label.
FAMILY = {
    '9x19': ('9×19mm Parabellum', 'cbrg:ammo_9mm', 'cbrg:ammo_9mm'),
    '45acp': ('.45 ACP（11.43×23mm）', 'cbrg:ammo_45acp', 'cbrg:ammo_45acp'),
    '556': ('5.56×45mm', 'cbrg:ammo_556', 'cbrg:ammo_556'),
    '57': ('5.7×28mm', 'cbrg:ammo_57mm', 'cbrg:ammo_57mm'),
    '12g': ('12 Gauge（壳长与弹种另核）', 'cbrg:ammo_12g', 'cbrg:ammo_12g'),
    '76239': ('7.62×39mm', 'tacz:762x39', 'cbrg:ammo_762'),
    '76251': ('7.62×51mm / .308 Winchester（游戏家族）', 'tacz:308', 'cbrg:ammo_762'),
    '76254r': ('7.62×54mmR', 'tacz:762x54r', 'cbrg:ammo_762'),
    '545': ('5.45×39mm', 'tacz:545x39', 'cbrg:ammo_556'),
    '58': ('5.8×42mm', 'tacz:58x42', 'cbrg:ammo_556'),
    '9x39': ('9×39mm', 'tacz:9x39', 'cbrg:ammo_762'),
    '10auto': ('10mm Auto（10×25mm）', 'tacz:10mm', 'cbrg:ammo_45acp'),
    '46': ('4.6×30mm', 'tacz:46x30', 'cbrg:ammo_57mm'),
    '300blk': ('.300 AAC Blackout（7.62×35mm）', 'tacz:300blk', 'cbrg:ammo_762'),
    '3006': ('.30-06 Springfield（7.62×63mm）', 'tacz:30_06', 'cbrg:ammo_762'),
    '792': ('7.92×57mm Mauser', 'tacz:792x57', 'cbrg:ammo_762'),
    '76225': ('7.62×25mm Tokarev', 'tacz:762x25', 'cbrg:ammo_9mm'),
    '763': ('7.63×25mm Mauser', 'tacz:763mauser', 'cbrg:ammo_9mm'),
    '50bmg': ('.50 BMG（12.7×99mm）', 'tacz:50bmg', 'cbrg:ammo_762'),
    '127108': ('12.7×108mm', 'suffuse:12.7x108mm', 'cbrg:ammo_762'),
    '12755': ('12.7×55mm', 'suffuse:12.7x55', 'cbrg:ammo_762'),
    '145': ('14.5×114mm', 'suffuse:14.5x114mm', 'cbrg:ammo_762'),
    '357': ('.357 Magnum（9×33mmR）', 'tacz:357mag', 'cbrg:ammo_45acp'),
    '50ae': ('.50 AE（12.7×33mm）', 'tacz:50ae', 'cbrg:ammo_45acp'),
    '500': ('.500 S&W Magnum（12.7×41mmR）', 'tacz:500mag', 'cbrg:ammo_45acp'),
    '4570': ('.45-70 Government（11.63×53mmR）', 'tacz:45_70', 'cbrg:ammo_762'),
    '22wmr': ('.22 WMR（5.6×27mmR）', 'tacz:22wmr', 'cbrg:ammo_9mm'),
    '22lr': ('.22 LR（5.6×15mmR）', 'suffuse:.22lr', 'cbrg:ammo_9mm'),
    '410': ('.410 Bore（壳长另核）', 'tacz:410_bore', 'cbrg:ammo_12g'),
    '32acp': ('.32 ACP（7.65×17mmSR）', 'cib:32acp', 'cbrg:ammo_9mm'),
    '765long': ('7.65×20mm Longue', 'suffuse:7.65x20mm', 'cbrg:ammo_9mm'),
    '8nambu': ('8×22mm Nambu', 'cib:8x22', 'cbrg:ammo_9mm'),
    '65arisaka': ('6.5×50mmSR Arisaka', 'cib:65x50', 'cbrg:ammo_762'),
    '58pistol': ('5.8×21mm', 'cib:58x21', 'cbrg:ammo_57mm'),
    '65cm': ('6.5 Creedmoor（6.5×48mm）', 'ccrp:65cm', 'cbrg:ammo_762'),
    '68fury': ('.277 SIG Fury / 6.8×51mm（军商标准另核）', 'tacz:68x51fury', 'cbrg:ammo_762'),
    '68tvcm': ('6.8×51mm TVCM 聚合物壳（独立）', 'ccrp:68x51tvcm', 'cbrg:ammo_762'),
    '408': ('.408 CheyTac（10.36×77mm）', 'suffuse:.408ct', 'cbrg:ammo_762'),
    '600': ('.600 Nitro Express（15.7×76mmR）', 'suffuse:.600ne', 'cbrg:ammo_762'),
    '416': ('.416 Barrett（10.57×83mm）', 'tacz:416barrett', 'cbrg:ammo_762'),
    '6pdw': ('6×35mm KAC', 'suffuse:6x35mm', 'cbrg:ammo_556'),
    '23shot': ('23×75mmR KS-23 霰弹（独立）', 'suffuse:23mm', 'cbrg:ammo_12g'),
    '473': ('4.73×33mm 无壳弹（独立）', 'suffuse:4.73x33mm', 'cbrg:ammo_556'),
    '9mak': ('9×18mm Makarov', 'tacz_pubg:ammo_9x18_makarov', 'cbrg:ammo_9mm'),
    '380': ('.380 ACP（9×17mm）', 'tacz_pubg:ammo_380_acp', 'cbrg:ammo_9mm'),
    'nagant': ('7.62×38mmR Nagant', 'tacz_pubg:ammo_762x38r_nagant', 'cbrg:ammo_9mm'),
    '3030': ('.30-30 Winchester（7.8×51.8mmR，候选）', 'tacz_pubg:ammo_30_30_winchester', 'cbrg:ammo_762'),
    '455auto': ('.455 Webley Auto（11.56×23mmSR）', 'tacz_pubg:ammo_455_webley_auto', 'cbrg:ammo_45acp'),
    '280brit': ('.280 British（7×43mm）', 'tacz_pubg:ammo_280_british', 'cbrg:ammo_762'),
    '338lm': ('.338 Lapua Magnum（8.6×70mm）', 'tacz_pubg:ammo_338_lapua', 'cbrg:ammo_762'),
    '300wm': ('.300 Winchester Magnum（7.62×67mmB）', 'tacz_pubg:ammo_300_win_mag', 'cbrg:ammo_762'),
}

RAW_FAMILY = {
    'cbrg:ammo_9mm':'9x19','tacz:9mm':'9x19','cbrg:ammo_45acp':'45acp','tacz:45acp':'45acp',
    'cbrg:ammo_556':'556','tacz:556x45':'556','ccrp:556x45_m855':'556','ccrp:556x45_m995':'556',
    'cbrg:ammo_57mm':'57','tacz:57x28':'57','cbrg:ammo_12g':'12g','tacz:12g':'12g',
    'tacz:762x39':'76239','tacz:762x39copper':'76239','tacz:308':'76251',
    'tacz:762x54':'76254r','tacz:762x54r':'76254r','tacz:545x39':'545','suffuse:545x39':'545','ccrp:545x39_beans':'545',
    'tacz:58x42':'58','cib:9x39mm':'9x39','tacz:9x39':'9x39','tacz:10mm':'10auto',
    'tacz:46x30':'46','tacz:300blk':'300blk','tacz:30_06':'3006','tacz:792x57':'792',
    'tacz:762x25':'76225','tacz:763mauser':'763','tacz:50bmg':'50bmg','cib:127x108':'127108','suffuse:12.7x108mm':'127108',
    'suffuse:12.7x55':'12755','suffuse:14.5x114mm':'145','tacz:357mag':'357','classicr:357mag_snakeshot':'357',
    'tacz:50ae':'50ae','tacz:500mag':'500','tacz:45_70':'4570','tacz:22wmr':'22wmr','suffuse:.22wmr':'22wmr',
    'suffuse:.22lr':'22lr','tacz:410_bore':'410','cib:32acp':'32acp','suffuse:7.65x20mm':'765long',
    'cib:8x22':'8nambu','cib:65x50':'65arisaka','cib:58x21':'58pistol','ccrp:65cm':'65cm',
    'tacz:68x51fury':'68fury','ccrp:68x51tvcm':'68tvcm','suffuse:6.8tvcm':'68tvcm','suffuse:.408ct':'408',
    'suffuse:.600ne':'600','tacz:416barrett':'416','suffuse:6x35mm':'6pdw','suffuse:23mm':'23shot','suffuse:4.73x33mm':'473',
}

# Explicit gun-level review: these rules supersede a current shared ammo label.
# Level means confidence in this proposal for the local asset, not real-world certification.
OVERRIDES = {}
def override(ids, family, judgement, reason, confidence='中', sources=()):
    for gun_id in ids.split():
        OVERRIDES[gun_id] = dict(family=family, judgement=judgement, basis=reason, confidence=confidence, evidence=list(sources))

override('cbrg:beryl_m762 cbrg:beryl_m762_2 cbrg:groza cib:galilace32 classicr:mk47 tacz:ak47 tacz:rpk tacz:sks_tactical tacz:type_81', '76239', 'PUBG抽象需拆分', '当前统一7.62mm是PUBG补给桶；按本地明确型号族，现实合理方案分到7.62×39mm。', '中高', ['cartridge_762_distinct','local_gun_data'])
override('cbrg:slr cib:mg3 tacz:fn_fal tacz:hk_g3 tacz:m700 tacz:minigun tacz:mk14 tacz:scar_h', '76251', 'PUBG抽象需拆分', '当前统一7.62mm是PUBG补给桶；对应FAL/G3/MG3/M14/SCAR-H及M24意图平台，建议独立7.62×51/.308游戏家族；M700多口径需按本地M24意图复核。', '中', ['cartridge_762_distinct','local_gun_data'])
override('classicr:dp28 hamster:mosin9130 suffuse:svd', '76254r', 'PUBG抽象需拆分', 'DP-28、Mosin M91/30和SVD型号族需独立7.62×54R；不能与AK的×39或FAL的×51视为同一种枪弹。', '中高', ['cartridge_762_distinct','local_gun_data'])
override('classicr:kar98', '792', 'PUBG抽象需拆分', 'PUBG的7.62mm是玩法归并；Kar98k标准原型对应7.92×57mm，改膛变体不由名字推断。', '中高', ['cartridge_762_distinct','local_gun_data'])
override('tacz:glock_17 tacz:b93r', '9x19', '原型明显不匹配', 'Glock17和Beretta93R原型均为9×19；当前.45ACP仅是改写的玩法配置，不能称原型合理通用。', '高', ['glock17','beretta93r','local_gun_data'])
override('tacz:deagle tacz:timeless50', '50ae', 'PUBG抽象需拆分', '本地Deagle/Timeless .50的.45ACP来自PUBG玩法桶；建议.50AE，但Deagle存在多口径，仍需核对实际模型/作者意图。', '中', ['deagle','local_gun_data'])
override('tacz:deagle_golden', '357', '原型明显不匹配', '当前原型名称仍明确Golden Deagle 357，.45ACP不符合该标识；建议.357Magnum，不能把所有Deagle一并当.50AE。', '中高', ['deagle','local_gun_data'])
override('ccrp:m14_hbar', '76251', '替代模型需区分', 'CBRG将显示名改成Mini14并用5.56，但模型资源仍为M14 HBAR。按实际M14平台建议7.62×51；若坚持Mini14玩法可保留5.56并明示替代。', '中高', ['m14_mini14','local_comparison'])
override('tacz:qbz_95 tacz:qbz_191 cib:qbu88', '58', 'PUBG抽象需拆分', 'PUBG的QBZ/QBU用5.56属于玩法抽象；本地QBZ95/QBZ191/QBU88原型应单列5.8×42，不能把出口97型也归入。', '中高', ['local_gun_data','local_comparison'])
override('classicr:m1a1_smg tacz:ump45 tacz:vector45', '45acp', '玩法换口径需区分', '当前9mm符合PUBG化意图，但本地资产分别仍为M1A1、UMP45、Vector45；现实原型方案选.45ACP。UMP9/9mmVector是另一个明确变体，不用改名替代验证。', '中高', ['pubg_301','hk_ump','local_comparison'])
override('hamster:nagantm1895', 'nagant', '原型明显不匹配', 'R1895游戏使用统一7.62mm，但NagantM1895应为7.62×38R手枪弹，与7.62×39/51/54R不能合并。', '中高', ['nagant','local_gun_data'])
override('hamster:win1894', '3030', '待核验', 'Win94/M1894多口径，.45ACP不能据当前游戏桶判为原型标准；建议以.30-30为候选设计，模型实际变体未确认，不自动改写。', '低', ['win94','local_gun_data'])
override('gz:classic_rpk74 gz:black_market_rpk74 gz:military_ak74', '545', '原型明显不匹配', '若资产确为名称所示RPK74/AK74标准原型，应为5.45×39；当前7.62×39是Division/枪包玩法选择，未证明本地模型经过改膛。', '中', ['ak74','local_gun_data'])
override('gz:t97nsrg3', '556', '原型明显不匹配', 'T97NSR出口型与QBZ95/191有区别，建议5.56×45；当前5.8×42不应因外形类似而直接沿用。', '中', ['type97','local_gun_data'])
override('ccrp:shield_ots33 rainforest:pm63 hamster:makarov', '9mak', '原型明显不匹配', 'OTS-33、PM-63和Makarov PM原型使用9×18Makarov；与9×19弹完全分开。Makarov原始Short Bullet没有完整弹种，不能宣称原本就是9×19。', '中高', ['ots33_pm63','local_gun_data'])
override('rainforest:em2', '280brit', '待核验', 'EM-2有.280British原型及7.62改型；当前.308未必是错误。建议原型还原时用.280British，未确认本地具体变体前保留待核验。', '低', ['em2','local_gun_data'])
override('suffuse:webley1913', '455auto', '原型明显不匹配', 'Webley Mk.I自装手枪原型的.455WebleyAuto不等于.45ACP；两种弹不能因为接近11mm而直接统一。', '中高', ['webley1913','local_gun_data'])
override('hamster:sks', '76239', '原抽象需拆分', '本地明确SKS平台，Short/Medium/Long只表示原枪包补给类别；现实合理方案单列7.62×39。', '中高', ['cartridge_762_distinct','local_gun_data'])
override('hamster:gew98', '792', '原抽象需拆分', '本地Gewehr98标准原型按7.92×57；原Long Bullet含多个不同弹种，不能整桶合并。', '中', ['local_gun_data'])
override('hamster:m1903 hamster:m1garand', '3006', '原抽象需拆分', '按明确美国M1903/M1Garand原型，建议.30-06；M1改为7.62的具体变体不从通用名称推定。', '中', ['local_gun_data'])
override('hamster:mosin91', '76254r', '原抽象需拆分', 'MosinM1891标准原型单列7.62×54R，原Long Bullet不是完整现实弹种。', '中高', ['cartridge_762_distinct','local_gun_data'])
override('hamster:lugerp08 hamster:mp18', '9x19', '原抽象需拆分', '按P08/MP18标准9×19原型，建议9×19；原Short Bullet不是完整现实弹种。', '中', ['local_gun_data'])
override('hamster:nagantcarbine', 'nagant', '待核验', 'NagantM1895卡宾改型候选可按7.62×38R设计，但须核对作者自定义枪管/改型；不将Short Bullet自动并入9mm。', '低', ['nagant','local_gun_data'])

SPECIAL = {
    'cib:battery':'OSIPR AR2科幻电池/能量弹，不与现实枪弹合并。',
    'tacz:thunder_cell':'雷电/能量弹，不与9mm或其他实体枪弹合并。',
    'cib:error':'零伤害调试弹，不进入常规补给归一。',
    'hamster:flares_ammo':'信号弹，独立于常规杀伤弹。',
    'origin:apfsds':'14.5×114mm APFSDS特殊弹，不能只因为×114相同抹去弹种/弹体设计差异。',
    'cib:18.4':'原包只定义18.4mm钨合金霰弹，壳长/标准身份不充分；不自动当作所有12Gauge。',
    'suffuse:boomstickshot':'原包.50纸壳/前装枪方案，不能变成.50AE或.50BMG；需独立表现与装填逻辑。',
    'cib:35mm':'35mm榴弹未给出完整壳型；不可仅按35mm与35×32自动合并。',
    'suffuse:35x32mm':'35×32mm榴弹，独立；不与40mm榴弹或仅名35mm的资产自动合并。',
    'suffuse:30x29vog':'30×29mm VOG榴弹，独立。',
    'cib:80':'80mm破障弹，独立；不是RPG-7通用火箭。',
    'suffuse:120mm':'PF-98A 120mm弹，独立；不是RPG-7通用火箭。',
    'tacz:40mm':'40mm榴弹游戏池；M203/M79/MGL/M320常按40×46低速设计，但高/低速与特殊壳型不能凭直径合并。',
    'tacz:rpg_rocket':'原包RPG-7游戏火箭池；弹头型号多样，不能由一个图标证明所有火箭互换。',
}

UNCERTAIN = {
    'rainforest:vz64':'Vz.64通常对应9×17/.380ACP，但当前命名/模型未经逐项验证；不把通用9mm自动解释为9×19。',
    'rainforest:vz68':'Vz.68与Vz.61历史型不同，名称可能指9×19或作者替代；待核对本地具体型号。',
    'suffuse:xm7':'军用XM7/M7为6.8×51，而MCX SPEAR另有.308商用型；当前.308合理与否取决于模型/作者意图，暂不强改。',
    'ccrp:timeless_50':'作者自定义Timeless .50L，按原.50AE声明保留；不据.50字样推成.50BMG。',
    'ccrp:camg_krait':'作者CAMG自定义平台，保留已声明TVCM，不把虚构改型当现实制式枪。',
    'ccrp:aug_a3s':'作者AUG-S .300BLK改型，保留明确设定；不能按原AUG名强改5.56。',
    'ccrp:scar_sc':'SCAR-SC有不同口径变体，保留原.300BLK设定，不以SCAR家族强改5.56。',
    'ccrp:mcx_spear_tombstone':'杠杆改型是作者实验设定，保留6.8×51声明，需按自定义平台评估。',
    'gz:m700_carbon':'M700平台多口径，原.30-06不因其他M700/M24用.308就判错。',
    'gz:t81s':'T81S外观来自游戏资产/改型，原5.56声明不能只因Type81家族就强改7.62。',
    'gz:tactical_t81s':'游戏资产/改型保留原5.56；型号与改膛未核验。',
    'suffuse:gepardpdw':'Gepard PDW有不同口径型；当前只写9mm不足以在9×18与9×19之间确认，暂时保留原ID。',
    'ccrp:shield_ots33':'OTs-33原型常对应9×18Makarov，但当前仅写9mm，且盾牌组合为作者自定义；本地具体变体缺少足够证据，暂不自动改写，可候选新9×18身份。',
    'tacz:deagle':'PUBG Deagle用.45ACP是游戏桶；Magnum Research原厂列.357/.44/.429DE/.50AE，未列.45ACP。可选.50AE设计，但当前模型具体变体尚未确认，不从外观通称强定唯一口径。',
    'tacz:timeless50':'Timeless .50 Z是作者自定义平台，现.45ACP来自PUBG化桶；.50AE是可能设计候选，不能只凭.50通称确认，暂保留独立待核验。',
}
for gid in ['cib:qbu202','cibs:qbu202_lingche']:
    UNCERTAIN[gid] = 'QBU-202常以8.6×70mm识别，但现有tacz:338显示覆写成.300Magnum；需确认枪包具体原型与弹药资源语义后才能单列.338Lapua。'
for gid in ['ccrp:mk18_mjolnir','classicr:mrad','suffuse:axmc','suffuse:axsr']:
    UNCERTAIN[gid] = '当前共享tacz:338被CBRG改名.300Magnum；这些平台有不同口径变体，不能由ID或通用枪名确定.338Lapua/.300WinMag/.338Norma。需逐资产核验后拆分。'
UNCERTAIN['tacz:ai_awp'] = 'CBRG以AWP资产替代PUBG AWM，.300Magnum是游戏意图；现实AWP/AWM不能仅重命名视为同型。PUBG方案可选独立.300WinMag，现实方案待模型核验。'

PUBG_BUCKET = {
 'AR': '由枪型逐项确定5.56mm或7.62mm',
 'DMR': '由枪型逐项确定5.56mm或7.62mm（VSS例外）',
 'SR': '7.62mm / .300Magnum / .50BMG，逐枪',
 'SMG': '9mm，P90为5.7mm；不表示现实互换',
 'SG': '12Gauge游戏池',
 'PISTOL': '9mm / .45ACP / 7.62mm，逐枪',
}

def clean(s):
    return re.sub(r'§.', '', str(s)).replace('\ufffd','').strip()

def ammo_name(aid):
    m = MODELS.get(aid,{})
    n = m.get('name') or m.get('displayName')
    if n: return clean(n)
    a = AMMO.get(aid,{})
    for key in ('name_zh_cn','name_en_us'):
        names = a.get(key, [])
        if names: return clean(names[0])
    return aid

def gunrow(g):
    gid = g['id']; raw = g['rawData'].get('ammo'); p = POLICY.get(gid,{})
    family = RAW_FAMILY.get(raw)
    r = dict(gunId=gid,name=g['displayName'],nameEn=g['name_en'],gunType=g['type'],gunIcon='../gun-balance/'+g['icon']['path'],
             currentAmmo=raw,currentAmmoName=ammo_name(raw),currentAmmoModelClassification=MODELS.get(raw,{}).get('modelClassification','pending'),
             originalAmmoResource=AMMO.get(raw,{}).get('resource'),currentGunDataSource=g['chosenCandidate'],
             pubgWeapon=p.get('pubgWeapon'),pubgBasis=p.get('basis'),pubgSupplyOptional=None,
             judgement='同弹种可共享',confidence='中：原文件声明，未逐把验证现实变体',
             basis='完整弹种按原gun data声明归类；同完整弹种跨包可统一为游戏补给家族，不证明现实武器任意互换。',
             evidence=['local_gun_data','local_ammo_index'],family=family,
             preserveProjectileVariant=raw if raw in {'ccrp:556x45_m855','ccrp:556x45_m995','ccrp:545x39_beans','classicr:357mag_snakeshot','origin:apfsds'} else None)
    if gid in OVERRIDES:
        r.update(OVERRIDES[gid]); family = r['family']
    if raw in SPECIAL:
        r.update(judgement='特殊不并',confidence='高：独立身份；完整现实标准可能待核',basis=SPECIAL[raw],evidence=['local_gun_data','local_ammo_index'])
    if raw in {'hamster:compact_ammo','hamster:medium_ammo','hamster:long_ammo'} and gid not in OVERRIDES:
        r.update(judgement='待核验',confidence='低',basis='原枪包只声明Short/Medium/Long。历史平台可能有多种原型口径、改膛或转换型号，现阶段保留原ID独立；不用枪名硬猜一个默认弹种。',evidence=['local_gun_data'])
    if gid in UNCERTAIN:
        r.update(judgement='待核验',confidence='低',basis=UNCERTAIN[gid],evidence=['local_gun_data','local_comparison'])
    if raw=='tacz:338':
        family=None
        r.update(judgement='待核验',confidence='低',family=None,
                 basis=UNCERTAIN.get(gid,'共享tacz:338当前显示为.300Magnum；索引被覆写，不能从ID推断.338Lapua。'),
                 proposedCartridge='待核验：.300 Winchester Magnum / .338 Lapua / 其他明确变体',
                 proposedAmmoId=raw)
    elif family:
        label, target, template = FAMILY[family]
        r.update(proposedCartridge=label,proposedAmmoId=target,family=family,preferred3dTemplate=template)
    else:
        r.update(proposedCartridge=ammo_name(raw)+'（完整弹种待核，保持独立）',proposedAmmoId=raw)
    if gid in UNCERTAIN and raw!='tacz:338':
        r['candidateCartridge']=r.get('proposedCartridge')
        r['candidateAmmoId']=r.get('proposedAmmoId')
        r['proposedAmmoId']=raw
        r['proposedCartridge']='保留原声明，具体变体待核：'+ammo_name(raw)
        r['family']=None
    if r['judgement']=='待核验' and gid in OVERRIDES:
        r['candidateCartridge']=r.get('candidateCartridge') or r.get('proposedCartridge')
        r['candidateAmmoId']=r.get('candidateAmmoId') or r.get('proposedAmmoId')
        r['proposedAmmoId']=raw
        r['proposedCartridge']='保留原声明，具体变体待核：'+ammo_name(raw)
        r['family']=None
    r['pendingVerification']=r['judgement']=='待核验'
    if gid=='tacz:ai_awp':
        r['pubgSupplyOptional']={'label':'.300 Magnum（PUBG AWM补给意图）','ammoId':'tacz_pubg:ammo_300_win_mag','note':'建议新独立ID，仅方案；不能继续污染所有tacz:338的语义。'}
    elif p.get('pubgWeapon'):
        # Current data is the authoritative local PUBG intent; do not infer an official ammo just from class.
        r['pubgSupplyOptional']={'label':ammo_name(raw),'ammoId':raw,'note':'当前本地PUBG候选的玩法意图，具体替代见依据；不是全平台现实通用。'}
    if gid in {'rainforest:at4','rainforest:m72','tacz:rpg7'}:
        names={'rainforest:at4':('AT4 84mm专用弹（一次性发射器系统）','tacz_pubg:ammo_at4_84mm'),
               'rainforest:m72':('M72 66mm专用弹（一次性发射器系统）','tacz_pubg:ammo_m72_66mm'),
               'tacz:rpg7':('Panzerfaust玩法替代 / RPG-7原资产：型号待核','tacz:rpg_rocket')}
        lab,aid=names[gid]
        r.update(judgement='特殊不并' if gid=='tacz:rpg7' else '原型明显不匹配',confidence='中高' if gid!='tacz:rpg7' else '低',
                 proposedCartridge=lab,proposedAmmoId=aid,family=None,
                 basis='RPG-7火箭不能与AT4/M72/Panzerfaust按火箭外观统一。当前游戏共用只是抽象；一次性发射器的道具/消耗机制也应独立。' if gid!='tacz:rpg7' else 'CBRG把RPG7模型改名Panzerfaust；在替代资产未换之前不要声称二者弹药原型相同。',
                 evidence=['launcher_types','local_comparison'])
    target=r['proposedAmmoId']; current_model=MODELS.get(raw,{}); target_model=MODELS.get(target,{})
    r['targetAlreadyRegistered']=target in AMMO
    r['pendingRegistration']=target not in AMMO
    r['registrationAction']='保留/复用现有ID（提案，未实施）' if target in AMMO else '建议新ID，未注册、未制作、未安装'
    r['currentAmmoIcon']=current_model.get('slotIcon')
    r['currentAmmoModelPreview']=current_model.get('modelPreview')
    r['proposedAmmoIcon']=target_model.get('slotIcon')
    r['proposedAmmoModelPreview']=target_model.get('modelPreview')
    r['proposedAmmoModelClassification']=target_model.get('modelClassification','new_model_required')
    if target_model.get('modelClassification')=='solid3d':
        r['preferred3dModel']=target
        r['preferred3dModelStatus']='现有资产有立体几何；不等于已在本次游戏内验证'
    else:
        template=r.get('preferred3dTemplate')
        if gid in {'rainforest:at4','rainforest:m72'}:
            template=None
        elif not template:
            template='cbrg:ammo_9mm' if g['type'] in {'pistol','smg'} else 'cbrg:ammo_762'
        r['preferred3dTemplate']=template
        r['preferred3dModel']=None
        r['preferred3dModelStatus']='需要独立的发射器/专用弹体模型，不能套用步枪弹盒；尚未制作' if gid in {'rainforest:at4','rainforest:m72'} else '仅借用包装/几何风格作参考模板，须调整弹体比例、纹理及完整弹种标识；不是拟新增弹的成品'
    r['templateIcon']=MODELS.get(r.get('preferred3dTemplate'),{}).get('slotIcon')
    r['templateModelPreview']=MODELS.get(r.get('preferred3dTemplate'),{}).get('modelPreview')
    r['applied']=False
    r['automaticApplyEligible']=False
    r['evidence']=list(dict.fromkeys(SOURCE_ALIAS.get(s,s) for s in r['evidence']))
    if gid in {'classicr:m1a1_smg','tacz:ump45','tacz:vector45'}:
        r['evidence']=[{'classicr:m1a1_smg':'m1a1','tacz:ump45':'ump45','tacz:vector45':'vector45'}[gid],'pubg30','local_comparison']
    if gid=='ccrp:shield_ots33':r['evidence']=['local_gun_data']
    if gid=='hamster:makarov':
        r['judgement']='原抽象需拆分';r['confidence']='中：原型名称候选，需核对作者具体变体'
        r['basis']='原Short Bullet是游戏类别，没有完整现实口径；按Makarov PM原型可建议9×18Makarov，但不说原ID必为9×19，也不对Short桶整体改写。'
        r['evidence']=['local_gun_data','makarov']
    r['evidenceDetails']=[dict(id=s,**SOURCES[s]) for s in r['evidence'] if s in SOURCES]
    if gid in {'tacz:deagle','tacz:timeless50'}:
        r['candidateCartridge']='.50 AE，或经具体变体确认的.357Magnum/.44Magnum；自定义平台另核'
        r['candidateAmmoId']='tacz:50ae'
    if gid=='rainforest:vz64':
        r['candidateCartridge']='.380 ACP（9×17mm），本地具体变体待核验'
        r['candidateAmmoId']='tacz_pubg:ammo_380_acp'
    if gid=='rainforest:m72':
        r['evidence']=['m72','local_gun_data']
        r['evidenceDetails']=[dict(id=s,**SOURCES[s]) for s in r['evidence'] if s in SOURCES]
    return r

def img(path, alt, css=''):
    return f'<img class="{css}" loading="lazy" src="{html.escape(path)}" alt="{html.escape(alt)}">' if path else '<span class="missing">待建模/无图</span>'
def esc(s): return html.escape(str(s if s is not None else '—'))
def picture(r):
    if r.get('proposedAmmoModelPreview'):
        return img(r['proposedAmmoModelPreview'],r['proposedCartridge'],'ammo')+f'<small>现有模型：{esc(r["proposedAmmoModelClassification"])}</small>'
    if not (r.get('templateModelPreview') or r.get('templateIcon')):
        return '<span class="missing">需制作专用模型</span><small class="warning">'+esc(r['preferred3dModelStatus'])+'</small>'
    return img(r.get('templateModelPreview') or r.get('templateIcon'),'仅参考模板','ammo')+'<small class="warning">参考模板 · 非新弹成品</small>'

def main():
    rows=[gunrow(g) for g in GUNS]
    rows.sort(key=lambda r:(r['gunType'],r['nameEn'] or r['name'],r['gunId']))
    research=RESEARCH
    summary={'guns':len(rows),'ammoAssets':len(AMMO),'judgements':dict(Counter(r['judgement'] for r in rows)),
             'proposedFamilies':dict(Counter(r.get('family') or '独立/待核验' for r in rows)),
             'newAmmoIds':sorted({r['proposedAmmoId'] for r in rows if not r['targetAlreadyRegistered']}),
             'sourceAmmoModels':dict(Counter(a.get('modelClassification') for a in MODELS.values())),
             'targetChanges':sum(r['currentAmmo']!=r['proposedAmmoId'] for r in rows)}
    summary['newAmmoIdsStrict']=sorted({r['proposedAmmoId'] for r in rows if not r['targetAlreadyRegistered'] and not r['pendingVerification']})
    summary['newAmmoIdsPubgOptional']=sorted({r['pubgSupplyOptional']['ammoId'] for r in rows if r['pubgSupplyOptional'] and r['pubgSupplyOptional']['ammoId'] not in AMMO})
    summary['newAmmoIdsPendingCandidate']=sorted({r['candidateAmmoId'] for r in rows if r.get('candidateAmmoId') and r['candidateAmmoId'] not in AMMO})
    summary['allProposedNewAmmoIds']=sorted(set(summary['newAmmoIdsStrict']+summary['newAmmoIdsPubgOptional']+summary['newAmmoIdsPendingCandidate']))
    output={'schemaVersion':1,'date':'2026-10-18','mode':'documentation_only_no_runtime_changes',
            'scope':'全部424把结构完整枪械候选；不是实际游戏加载注册保证。全部78个ammo资产另列模型目录。',
            'decisionBoundary':'优先完整弹种一致的游戏归一；PUBG补给桶与现实原型分栏；不确定变体不自动改写。',
            'caveats':['本文不包含现实装弹或改装操作。','M855/M995、蛇弹、穿甲弹、燃烧/爆破等特殊弹头身份与效果不得因共享补给桶而抹去。','军民标准、枪膛条件和霰弹壳长未逐枪验证；同游戏家族不表示现实无条件互换。','所有新tacz_pubg:* ID只是建议，尚未注册；新弹图片仅参考模板。','立体模型图是原资产离线几何投影，不是游戏截图；图标不能证明三维建模。'],
            'summary':summary,'families':{k:{'cartridge':v[0],'targetAmmoId':v[1],'visualTemplate':v[2]} for k,v in FAMILY.items()},
            'sources':SOURCES,'research':research,'guns':rows,'ammoModelInventory':'ammo-model-inventory.json'}
    (OUT/'ammo-unification.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf-8')
    keys=['gunId','name','nameEn','gunType','currentAmmo','currentAmmoName','currentAmmoModelClassification','proposedCartridge','proposedAmmoId','targetAlreadyRegistered','judgement','confidence','basis','preferred3dModel','preferred3dTemplate','preferred3dModelStatus','pubgWeapon','applied']
    with (OUT/'ammo-unification.csv').open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=keys,extrasaction='ignore');w.writeheader();w.writerows(rows)
    make_html(rows,summary,research)
    make_md(rows,summary)
    make_preview(rows)
    print(json.dumps(summary,ensure_ascii=False,indent=2))

def make_html(rows,summary,research):
    tr=[]
    for r in rows:
        pubg=r['pubgSupplyOptional']
        hay=' '.join(str(r.get(x) or '') for x in ('gunId','name','nameEn','currentAmmo','proposedCartridge','proposedAmmoId','judgement','pubgWeapon')).lower()
        tr.append(f'''<tr data-search="{esc(hay)}" data-type="{esc(r['gunType'])}" data-judgement="{esc(r['judgement'])}">
<td>{img(r['gunIcon'],r['name'],'gun')}<b>{esc(r['name'])}</b><code>{esc(r['gunId'])}</code><small>{esc(r['gunType'])}</small></td>
<td><div class="pair">{img(r['currentAmmoIcon'],r['currentAmmoName'],'slot')}{img(r['currentAmmoModelPreview'],'原弹离线几何投影','ammo')}</div><b>{esc(r['currentAmmoName'])}</b><code>{esc(r['currentAmmo'])}</code><small>{esc(r['currentAmmoModelClassification'])}</small></td>
<td>{picture(r)}<b>{esc(r['proposedCartridge'])}</b><code>{esc(r['proposedAmmoId'])}</code><small>{esc(r['registrationAction'])}</small></td>
<td><span class="badge">{esc(r['judgement'])}</span><small>{esc(r['confidence'])}</small><p>{esc(r['basis'])}</p><small>证据：{', '.join('<a href="'+esc(s['url'])+'">'+esc(s['title'])+'</a>' for s in r['evidenceDetails'])}</small>{'<small class="warning">候选：'+esc(r.get('candidateCartridge'))+'</small><code>'+esc(r.get('candidateAmmoId'))+'</code>' if r.get('candidateCartridge') else ''}{'<small>弹头差异保留：'+esc(r['preserveProjectileVariant'])+'</small>' if r['preserveProjectileVariant'] else ''}</td>
<td>{('<b>'+esc(pubg['label'])+'</b><code>'+esc(pubg['ammoId'])+'</code><p>'+esc(pubg['note'])+'</p>') if pubg else '无明确端游对应；不强塞PUBG补给桶'}</td></tr>''')
    ammo_tr=[]
    for a in sorted(MODELS.values(),key=lambda a:a['id']):
        aid=a['id']; geometry=a.get('modelGeometrySummary',{})
        ammo_tr.append(f'''<tr><td>{img(a.get('slotIcon'),ammo_name(aid),'slot')}<b>{esc(ammo_name(aid))}</b><code>{esc(aid)}</code></td><td>{img(a.get('modelPreview'),'离线几何投影','catalog')}<small>原资产离线几何投影</small></td><td>{esc(a.get('modelClassification'))}<small>{esc(json.dumps(geometry,ensure_ascii=False))}</small></td><td>{esc(', '.join(AMMO[aid].get('compatible_guns',[]))) or '当前没有候选枪引用'}</td></tr>''')
    sourcehtml='<p>原始资源与映射证据：<a href="../PUBG枪械逐项对照.md">PUBG替代资产对照</a> · <a href="../弹药配件现实依据与兼容建议.md">既有分类依据</a> · <a href="ammo-model-inventory.json">模型检查JSON</a> · <a href="ammo-unification.json">全表JSON</a> · <a href="ammo-unification.csv">CSV</a></p>'
    sourcehtml+='<p><a href="弹药立体模型核对.md">立体建模核对与当前许可证据</a> · <a href="ammo-model-preview.png">CBRG六种立体弹药总图</a> · <a href="ammo-model-all-preview.png">全部78弹药图片目录</a></p>'
    page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>424枪械 · 弹药统一提案与78弹药建模目录</title>
<style>:root{color-scheme:dark}body{font-family:"Microsoft YaHei",sans-serif;background:#10151d;color:#e1e9f4;margin:0;padding:24px}h1{font-size:28px;margin:0 0 14px}h2{margin-top:36px}p{line-height:1.7}a{color:#91c9ff}.intro{max-width:1350px}.warning{color:#ffca80}.controls{position:sticky;top:0;background:#182333f5;padding:14px;display:flex;gap:12px;z-index:2;border:1px solid #34465e;flex-wrap:wrap}input,select{font:inherit;background:#0e1825;border:1px solid #526985;color:#fff;padding:10px;border-radius:5px}input{min-width:400px;flex:1}.wrap{overflow:auto}table{border-collapse:collapse;width:100%;min-width:1400px;font-size:14px}th{background:#223148;text-align:left;padding:12px}td{vertical-align:top;border-bottom:1px solid #304059;padding:13px;min-width:190px}td:first-child{width:230px}td:nth-child(4){width:330px}tr:nth-child(even){background:#151e2b}tr:hover{background:#22364c}b,code,small{display:block;margin:6px 0}code{color:#a9d5ff;overflow-wrap:anywhere;font-size:12px}small{color:#a9b7cb;font-size:12px;line-height:1.5}img{object-fit:contain;image-rendering:auto;background:#202b3a;border-radius:8px}.gun{width:180px;height:100px}.slot{width:64px;height:64px}.ammo{width:150px;height:115px}.catalog{width:210px;height:150px}.pair{display:flex;align-items:center;gap:8px}.badge{display:inline-block;background:#274665;padding:6px 9px;border-radius:5px}.missing{display:block;color:#e9bd8d;padding:10px}.stats{display:flex;gap:20px;flex-wrap:wrap;margin:18px 0}.stats span{background:#1c2b3d;border:1px solid #3a506d;padding:12px 18px;border-radius:8px}button{font:inherit;padding:10px;background:#31557c;color:#fff;border:0;border-radius:5px;cursor:pointer}#ammo table{min-width:900px}#ammo td{max-width:450px;overflow-wrap:anywhere}#count{align-self:center}.legend{background:#1d2939;padding:14px 18px;border-left:4px solid #6cafe6}</style>
<h1>弹药统一提案：全部424枪 × 原弹药 × 建议弹药</h1><div class="intro"><p>日期：2026-10-18。优先保持完整弹种合理，另列PUBG玩法简化。<strong class="warning">研究文档：没有修改任何枪包或运行配置；所有新ID只是提案。</strong></p>
<p>“同弹种可共享”只指模组供弹家族，不宣称现实枪械无条件互换。“PUBG抽象需拆分”不代表原作者写错，而是游戏供弹简化。“待核验”不自动改写。军民标准、壳长、具体变体和弹头效果仍须保留。</p>
<p class="legend">图片说明：枪图与小弹药图取自当前枪包图标；大弹药图是原模型的<strong>离线几何投影</strong>，不是游戏截图。新弹若显示“参考模板”，只表示可借鉴包装/几何风格，<strong>不是已制作的新弹模型</strong>。二维图标不能证明拥有三维建模。</p>__SOURCES__<div class="stats"><span>424 把枪</span><span>78 个 ammo 资产</span><span>__SOLID__ 个立体几何模型</span><span>__NEW__ 个建议新ID，均未注册</span></div></div>
<div class="controls"><input id="q" placeholder="搜索枪名、ID、完整弹种或判定"><select id="type"><option value="">全部枪类</option>__TYPES__</select><select id="judgement"><option value="">全部判断</option>__JUDGEMENTS__</select><button id="reset">重置</button><a href="#ammo">查看全部78个弹药模型</a><span id="count"></span></div>
<div class="wrap"><table id="guns"><thead><tr><th>枪械与ID</th><th>当前原弹药：图标 + 模型</th><th>建议合理弹药：成品或参考模板</th><th>判断、依据与置信度</th><th>PUBG玩法简化，可选</th></tr></thead><tbody>__ROWS__</tbody></table></div>
<section id="ammo"><h2>全部78个弹药资产：三维几何目录</h2><p>solid3d：存在有厚度的几何；thin：仅薄片/近乎平面；2d：图形二维；missing：资源缺失。这里按实际模型几何检查，不能用slot图标假定。</p><div class="wrap"><table><thead><tr><th>当前显示名 / ID / 原图标</th><th>离线几何投影</th><th>分类与几何摘要</th><th>当前枪械引用</th></tr></thead><tbody>__AMMO__</tbody></table></div></section><section><h2>原型与PUBG规则来源</h2>__REFERENCE_LIST__</section>
<script>const q=document.querySelector('#q'),type=document.querySelector('#type'),judgement=document.querySelector('#judgement'),rows=[...document.querySelectorAll('#guns tbody tr')];function apply(){let n=0;const s=q.value.trim().toLowerCase();for(const r of rows){const ok=(!s||r.dataset.search.includes(s))&&(!type.value||r.dataset.type===type.value)&&(!judgement.value||r.dataset.judgement===judgement.value);r.hidden=!ok;if(ok)n++;}document.querySelector('#count').textContent=n+' / '+rows.length+' 把枪';}for(const e of [q,type,judgement])e.addEventListener('input',apply);document.querySelector('#reset').onclick=()=>{q.value=type.value=judgement.value='';apply();};apply();</script></html>'''
    page=page.replace('__SOURCES__',sourcehtml).replace('__SOLID__',str(summary['sourceAmmoModels'].get('solid3d',0))).replace('__NEW__',str(len(summary['allProposedNewAmmoIds'])))
    page=page.replace('__TYPES__',''.join(f'<option>{esc(t)}</option>' for t in sorted({r['gunType'] for r in rows}))).replace('__JUDGEMENTS__',''.join(f'<option>{esc(t)}</option>' for t in summary['judgements']))
    page=page.replace('__ROWS__','\n'.join(tr)).replace('__AMMO__','\n'.join(ammo_tr))
    page=page.replace('__REFERENCE_LIST__','<ul>'+''.join('<li><a href="'+esc(s['url'])+'">'+esc(s['title'])+'</a>：'+esc(s.get('supports',''))+'</li>' for s in SOURCES.values())+'</ul>')
    (OUT/'ammo-unification.html').write_text(page,encoding='utf-8')

def make_md(rows,summary):
    lines=['# 弹药统一提案与立体模型目录','',
           '核对日期：2026-10-18。范围：全部424把结构完整枪械、78个ammo资产。本轮只生成研究文件，没有更改枪包、模组配置、弹药注册、模型或运行数据，也没有启动游戏。','',
           '[带图片、搜索和筛选的全表](ammo-unification.html) · [全表JSON](ammo-unification.json) · [CSV](ammo-unification.csv) · [模型目录JSON](ammo-model-inventory.json)','',
           '## 建议采用两层设计','',
           '第一层按完整弹种共享补给，如9×19、.45ACP、5.56×45、5.7×28、5.45×39、9×39。第二层统一包装/掉落物的PUBG视觉风格，但保留7.62×39、7.62×51、7.62×54R等独立ID。PUBG的7.62mm、9mm等补给桶另作可选玩法方案，不作为现实通用依据。','',
           '军民标准、霰弹壳长、具体型号、弹头变体仍须保留；M855/M995、蛇弹、爆破/燃烧等效果不因共享补给族消失。','',
           '## 明确不应当视为同一种弹','',
           '| 不应合并 | 原因 |','|---|---|',
           '| 7.62×25 / ×38R / ×39 / ×51 / ×54R | 前缀相同不代表同一完整弹种；Nagant手枪尤其不能吃AK/FAL/SVD的步枪弹。 |',
           '| 9×17 / ×18 / ×19 / ×39 | 手枪/冲锋枪、Makarov和VSS类弹药是不同身份。 |',
           '| .300 Blackout / .300 Winchester Magnum / .338 Lapua | 名称数字相近或旧ID338不代表同种弹；当前tacz:338已受CBRG名称覆写影响。 |',
           '| .50AE / .50BMG / 12.7×108 / 12.7×55 | 不能只按“12.7/.50”合并。 |',
           '| 12Gauge / .410 / 23×75R / 未明确壳型18.4mm | 霰弹外观相似并不表示壳型和弹种相同。 |',
           '| RPG-7 / AT4 / M72 / PF-98 / 40mm榴弹 | 发射器系统和弹体身份不同，一次性发射器还涉及道具消耗逻辑。 |','',
           '## 统计与待核验边界','',
           '```json',json.dumps(summary,ensure_ascii=False,indent=2),'```','',
           '同弹种可共享是游戏设计建议，不是现实使用说明。多数现有枪按gun data的完整弹种声明归类，置信度为“原文件声明，未逐把验证现实变体”；这与逐枪现实认证有明确区别。','',
           'Win94、EM-2、多口径MRAD/AXMC/AXSR、部分历史枪、自定义CAMG武器、只有Short/Medium/Long类别的枪均保持待核验，表中候选不能自动应用。当前424不是游戏内已验证加载数量。','',
           '## 图片与建模边界','',
           '枪图和小弹药图为当前枪包的原图标。大弹药图由实际geo_models进行离线投影，证明模型几何情况，但不是游戏截图或渲染兼容性测试。凡建议新ID，图片只能称“参考模板”，不能当作新模型成品。','',
           '立体补给箱可按CBRG现有的包装风格重新制作完整口径标识和独立几何。建议新ID均未注册；公开分发前应核对原素材许可，或从零重绘原创纹理/几何。','',
           '模型风格可以共用，弹药逻辑不必共用：7.62×39、×51、×54R可用相同盒型加不同文字/弹体细节，仍保留三个独立弹药ID。TaCZ r7的IAmmo.isAmmoOfGun按gunData.ammoId与AmmoId相等判断；只换纹理不会自动兼容，多弹种兼容若未来实施需要额外逻辑或逐枪供弹目标。当前伤害、散布、弹速等主要来自枪械gun data的bullet字段，换ammo ID也不会自动得到现实弹道。','',
           '## 制作与落地可行性','',
           '可行：按PUBG补给盒的视觉方向，从零制作低模纸盒、弹托、露出的少量弹体，重绘原创纹理；多种口径可共用基础盒型，但完整弹种标识、露出弹体比例、颜色和独立ID应分别定义。无需把不合理弹种硬塞进六个CBRG补给桶。','',
           'TaCZ资源链至少包含ammo index、display、geo_models、模型UV纹理与slot图标；ground、fixed、thirdperson_hand等位置/缩放分别检查。slot是二维库存图标，不等于物品有立体建模；飞行弹体entity与弹壳shell是另外的资源链，不能拿掉落盒子模型证明它们也已制作。','',
           '未来实施时才将已审定逐枪表转为实验方案的供弹覆写，并生成配套地面弹药；本轮config里的子弹实验选项继续占位，没有修改运行行为。待核验项先保留原ID，新增tacz_pubg:*均是规划，未注册、未制作、未安装。新ID统计分别列现实合理方案、PUBG可选方案（包括独立.300WinMag）与待核验候选，避免把候选误记为完成品。','',
           '[CBRG六种立体模型总图](ammo-model-preview.png) · [全部78弹药图片目录](ammo-model-all-preview.png) · [模型与许可核对说明](弹药立体模型核对.md)','',
           '## 现实与PUBG资料来源','',
           *['- ['+s['title']+']('+s['url']+')：'+s.get('supports','') for s in SOURCES.values()],
           '',
           '## 全部424把枪的文字对应','',
           '| 枪械 | 当前弹药 | 建议完整弹种 | 建议ID | 判断 |','|---|---|---|---|---|']
    for r in rows:
        values=[r['name']+' `'+r['gunId']+'`','`'+r['currentAmmo']+'`',r['proposedCartridge'],'`'+r['proposedAmmoId']+'`',r['judgement']]
        lines.append('| '+' | '.join(str(v).replace('|','\\|').replace('\n',' ') for v in values)+' |')
    (OUT/'ammo-unification.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')

def make_preview(rows):
    from PIL import Image, ImageDraw, ImageFont
    selected=['tacz:glock_17','tacz:ak47','tacz:fn_fal','suffuse:svd','classicr:kar98','hamster:nagantm1895','ccrp:m14_hbar','tacz:qbz_95','tacz:ump45','hamster:win1894','classicr:mrad','rainforest:at4']
    byid={r['gunId']:r for r in rows}
    W=1900; RH=138; TOP=205
    im=Image.new('RGB',(W,TOP+len(selected)*RH+72),'#111a26');d=ImageDraw.Draw(im)
    fontpath=Path('C:/Windows/Fonts/msyh.ttc')
    f=ImageFont.truetype(str(fontpath),24);small=ImageFont.truetype(str(fontpath),19);title=ImageFont.truetype(str(fontpath),37)
    d.text((30,24),'弹药统一提案 · 代表枪械对应',font=title,fill='#f0f5fc')
    d.text((30,82),'全部424枪见HTML全表；此图12例。原图标/原模型离线投影，不是游戏截图。',font=f,fill='#bed0e5')
    d.text((30,122),'立体盒型可以统一风格，7.62×39 / ×51 / ×54R等弹药逻辑保持独立。新ID均未注册。',font=small,fill='#ffc784')
    for x,t in [(30,'枪械与ID'),(390,'当前弹药'),(710,'合理弹种建议 / 待核验候选'),(1285,'建议视觉'),(1510,'判断')]:
        d.text((x,170),t,font=f,fill='#91c8ff')
    def paste_img(p,x,y,w,h):
        if not p:return
        pp=(OUT/p).resolve()
        if not pp.exists():return
        src=Image.open(pp).convert('RGBA');src.thumbnail((w,h),Image.Resampling.LANCZOS)
        im.paste(src,(x+(w-src.width)//2,y+(h-src.height)//2),src)
    def wraptext(s,maxw,font):
        out=[];line=''
        for ch in s:
            if d.textlength(line+ch,font=font)>maxw and line:
                out.append(line);line=ch
            else:line+=ch
        if line:out.append(line)
        return out
    for i,gid in enumerate(selected):
        r=byid[gid];y=TOP+i*RH
        if i%2==0:d.rectangle((15,y-4,W-15,y+RH-6),fill='#1c293a')
        paste_img(r['gunIcon'],24,y+3,130,95)
        for j,l in enumerate(wraptext(r['name'],208,f)[:2]):d.text((165,y+8+j*30),l,font=f,fill='#edf4ff')
        for j,l in enumerate(wraptext(gid,212,small)[:2]):d.text((165,y+75+j*22),l,font=small,fill='#9ebbdc')
        paste_img(r.get('currentAmmoModelPreview') or r.get('currentAmmoIcon'),390,y+3,105,95)
        for j,l in enumerate(wraptext(r['currentAmmoName'],193,f)[:2]):d.text((500,y+8+j*29),l,font=f,fill='#e5eaf1')
        for j,l in enumerate(wraptext(r['currentAmmo'],197,small)[:2]):d.text((500,y+78+j*22),l,font=small,fill='#9ebbdc')
        label=r.get('candidateCartridge') if r['pendingVerification'] and r.get('candidateCartridge') else r['proposedCartridge']
        for j,l in enumerate(wraptext(label,545,f)[:3]):d.text((710,y+8+j*29),l,font=f,fill='#ffd99e' if r['pendingVerification'] else '#b9f1d4')
        for j,l in enumerate(wraptext('候选待核验' if r['pendingVerification'] else r['proposedAmmoId'],545,small)[:2]):d.text((710,y+99+j*22),l,font=small,fill='#9ebbdc')
        candidate=r['pendingVerification'] and r.get('candidateCartridge')
        p=(r.get('templateModelPreview') if candidate else r.get('proposedAmmoModelPreview')) or r.get('templateModelPreview')
        paste_img(p,1275,y,205,100)
        if not p:d.text((1280,y+34),'需制作专用模型',font=small,fill='#ffc784')
        current_visual=bool(r.get('proposedAmmoModelPreview') and not candidate)
        d.text((1280,y+100),'现有模型' if current_visual else '建模参考 · 非成品' if p else '尚未制作',font=small,fill='#a7bcd5' if current_visual else '#ffc784')
        for j,l in enumerate(wraptext(r['judgement'],350,f)[:2]):d.text((1510,y+14+j*30),l,font=f,fill='#edd7ab')
        note='原型与PUBG意图分开' if r['judgement']!='待核验' else '不自动改写'
        d.text((1510,y+78),note,font=small,fill='#a8bdd6')
    d.text((30,TOP+len(selected)*RH+24),'同游戏弹种族 ≠ 现实无条件互换；军民标准、壳长、弹头效果、具体变体另核。未启动游戏。',font=small,fill='#a1b4cb')
    im.save(OUT/'ammo-unification-preview.png')

if __name__=='__main__':main()
