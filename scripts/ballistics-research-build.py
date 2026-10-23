"""Reproducible, evidence-labelled game ballistics proposals; no game launch."""
from pathlib import Path
from collections import Counter
import json, math, hashlib

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/gun-balance'
assets=json.loads((OUT/'gun-assets.json').read_text(encoding='utf-8'))
previous=json.loads((ROOT/'docs/compatibility/ammo-unification.json').read_text(encoding='utf-8'))
prior={g['gunId']:g for g in previous['guns']}
tables=json.loads((OUT/'evidence/ballistics-factory-tables.json').read_text(encoding='utf-8'))
sources={}
families={}
DATE='2026-10-18'
def source(key,title,url,supports,**kwargs):
    sources[key]={'id':key,'title':title,'url':url,'retrievedDate':DATE,'supports':supports,**kwargs}
    return key
source('nist_g','NIST CODATA: standard acceleration of gravity','https://physics.nist.gov/cgi-bin/cuu/Value?gn','标准重力9.80665 m/s²；不是每款枪各自有不同重力。')
source('nist_units','NIST SI unit conversion factors','https://www.nist.gov/pml/special-publication-811/nist-guide-si-appendix-b-conversion-factors/nist-guide-si-appendix-b9','1 ft=0.3048 m；1 yd=0.9144 m。')
source('nasa_drag','NASA Glenn: Flight equations with drag','https://www1.grc.nasa.gov/beginners-guide-to-aeronautics/flight-equations-with-drag/','阻力随速度、空气密度、迎风面积及形状而变；D=Cd rho A v²/2；常系数只是近似。')
source('nasa_sphere','NASA Glenn: Drag of a sphere','https://www1.grc.nasa.gov/beginners-guide-to-aeronautics/drag-of-a-sphere/','球体阻力随雷诺数/表面/流态变化；本项目的Cd=0.47是显式游戏近似，非任意霰弹丸的测量值。')
source('fn57','FN Herstal: 5.7x28mm technical datasheet','https://fnherstal.com/app/uploads/technical-data-fn-57x28mm.pdf','SS190参考：P90约715 m/s、Five-seveN约615 m/s；同一弹种不意味着所有枪管同一初速。',referenceValues={'p90Mps':715,'fiveSevenMps':615})
source('hk_mp5','Heckler & Koch: MP5 technical specifications','https://www.heckler-koch.com/en/Products/Military%20and%20Law%20Enforcement/Submachine%20guns/MP5','MP5 DM51距枪口5m参考速度400 m/s；MP5SD将常规超音速弹减至<340m/s。',referenceValues={'v5Mps':400,'sdLessThanMps':340})
source('hornady_barrel','Hornady LE: 5.56 NATO 53 gr TAP Patrol #81275','https://www.hornadyle.com/rifle-ammunition/53-gr-tap-patrol-nato','同一工厂弹实测参考：11.5英寸2888、14.5英寸3096、16英寸3157、20英寸3310 fps。本项目只借相对比例近似其他5.56枪管，不宣称不同弹头同初速。',referenceValues={'barrelInches':[11.5,14.5,16,20],'muzzleFps':[2888,3096,3157,3310]})
source('aks74u','Tula State Museum of Weapons: AKS-74U','https://heroes.museum-arms.ru/en/aks.html','AKS-74U公开参考初速735m/s；只直接用于AKS-74U对应原型。',referenceValues={'muzzleVelocityMps':735})
source('nammo_m995','Nammo Ammunition Handbook edition 7, August 2024','https://www.nammo.com/wp-content/uploads/2020/08/nammo-handbook-2023.pdf','M995/AP3 3.4g弹头公开参考初速1030m/s。M855/M995特殊弹头身份保留。',referenceValues={'m995Mps':1030})
source('saami_fury','SAAMI: 277 SIG FURY acceptance announcement','https://saami.org/tag/sig-fury/','135gr商业参考初速3000fps=914.4m/s；不声称这是所有M7军用弹/枪管的实测值。',referenceValues={'muzzleFps':3000,'muzzleVelocityMps':914.4})
source('norinco_58','南京理工大学公开教学资料：95式5.8mm自动步枪','https://dgxg.njust.edu.cn/_upload/article/files/11/65/15acc788430d9864c2bc27ddaccf/a99fc12a-58ca-4db3-bcad-fe6a01b94930.pdf','DBP87/95式步枪公开参考初速930m/s；不是191/192/88等每款枪的实测。',referenceValues={'muzzleVelocityMps':930})
source('58_paper','中国工程科学1999年第1卷第2期：新5.8mm班用枪族研究','https://devp-service.oss-cn-beijing.aliyuncs.com/c1bef5bc349340ef8ae8faa19e39d47e/file_1562043548723.pdf','原研发研究公开表中步枪930m/s、班用机枪970m/s；现代变体仅作同家族设计参考。',referenceValues={'rifleMps':930,'lmgMps':970})
source('rosguard_vss','Росгвардия: ВИНТОВКА СНАЙПЕРСКАЯ СПЕЦИАЛЬНАЯ ВИНТОРЕЗ','https://rosguard.gov.ru/page/index/vintovka-snajperskaya-specialnaya-vintorez','VSS 9x39公开初速范围282–292m/s；本项目以290作同弹种参考，不声称ASVAL/SR3M同枪管实测。',referenceValues={'rangeMps':[282,292]})
source('utyos_127','ZKMK manufacturer: NSV 12.7 UTYOS','https://utyos.zkmk.kz/nsv-12-7-utyos/','12.7x108公开初速820–860m/s；本项目取840作为代表，非中国各款平台独立实测。',referenceValues={'rangeMps':[820,860]})
source('barrett50','Barrett 2026 product catalog p.29: .50 BMG Precision','https://barrett.net/wp-content/uploads/2026/01/BARRETT-2026-Product-Catalog-Digital-LR.pdf','660gr .50BMG Precision公开参考2800fps（32英寸枪管）=853.44m/s；不同弹头/枪管不能据此视为完全一致。',referenceValues={'referenceFps':2800,'referenceMps':853.44,'testBarrelInches':32})
source('barrett416','Barrett 2026 product catalog p.29: .416 Barrett Precision','https://barrett.net/wp-content/uploads/2026/01/BARRETT-2026-Product-Catalog-Digital-LR.pdf','.416 Barrett Precision 500gr A-Tip公开参考2850fps（36英寸枪管）=868.68m/s；非本地MRAD ELR逐枪实测。',referenceValues={'referenceFps':2850,'referenceMps':868.68,'testBarrelInches':36})
source('cheytac408','CheyTac USA 2023/2024 catalog, .408/419gr','https://cheytac.com/wp-content/uploads/2024/05/CHEYTAC-USA-CATALOG.pdf','同一页规格框2850fps、正文2950fps存在冲突；本项目取较低2850fps=868.68m/s并保留冲突说明。',referenceValues={'conflictingMuzzleFps':[2850,2950]})
source('cci22pistol','CCI 2025 catalog, Uppercut 22 WMR','https://www.cci-ammunition.com/on/demandware.static/-/Library-Sites-VistaCCISharedLibrary/default/v91e820dd9e99e25799a98c3df74baf9b44b130a9/pdfDocuments/catalog/CC307_2025_CCI_Catalog_FL_WEB_singles.pdf','Uppercut 22WMR 40gr手枪参考1050fps=320.04m/s；不用1875fps长枪管Maxi-Mag冒充LifeCard小枪管实测。',referenceValues={'muzzleFps':1050,'muzzleVelocityMps':320.04})
source('ppu_nagant','Prvi Partizan: 7.62 Nagant FPJ 98gr A-470','https://www.prvipartizan.com/search_hb.php?id=A-470','0/50m速度225/214m/s，代表现代工厂弹；不冒充历史军用强装药。',referenceValues={'distanceM':[0,50],'velocityMps':[225,214]})
source('ppu_lebel','Prvi Partizan 2013 catalog: 8x50R Lebel A-417','https://www.prvipartizan.com/download/ppu2013.pdf','8x50R Lebel 200gr、610mm参考V0=700/V100=646m/s。',referenceValues={'distanceM':[0,100],'velocityMps':[700,646]})
source('fiocchi455','Fiocchi USA 2022 catalog: .455 Webley MkII 455A','https://fiocchiusa.com/assets/catalog/2022_FiocchiCatalog_Web.pdf','262gr .455 Webley MkII工厂参考700fps=213.36m/s；目录未给远端射表。',referenceValues={'muzzleFps':700,'muzzleVelocityMps':213.36})
source('local_identity','本地只读枪械/弹药身份核对','../compatibility/ammo-unification.json','逐枪原型/共享游戏弹种/改型不确定性；不是现实测速来源。')

rows={r['cells'][1]:(kind,r) for kind,table in tables.items() for r in table.get('rows',[])}
def sb(key,label,code,kind=None,notes=''):
    actual,row=rows[code]
    sid='sb_'+code.lower()
    cells=row['cells']
    offset=8 if actual=='pistol' else 9
    v=float(cells[offset].replace(',','').replace(' ',''))
    retained=float(cells[11 if actual=='pistol' else 10].replace(',','').replace(' ',''))
    source(sid,'Sellier & Bellot: '+row['title']+' '+code,row['urls'][0],'工厂弹代表V0/V100及测试枪管；用于弹种参考，不是每个本地枪模型的测速。',referenceValues={'distanceM':[0,100],'velocityMps':[v,retained],'testBarrelMm':float(cells[16 if actual=='pistol' else 19].replace(',','').replace(' ',''))})
    profile(key,label,v,retained,100,[sid],notes=notes)

def profile(key,label,v,retained=None,d=100,src=None,drag=None,notes='',quality='factory_reference',**kwargs):
    k=math.log(v/retained)/d if retained else drag
    families[key]={'caliber':label,'referenceVelocityMps':v,'referenceDistanceM':d if retained else None,'referenceRetainedVelocityMps':retained,'effectiveDragPerMeter':k,'dragBasis':'factory_two_point_fit' if retained else 'explicit_game_estimate','sourceIds':src or [],'referenceQuality':quality,'notes':notes,**kwargs}

sb('9x19','9×19mm 124gr FMJ','V310492')
sb('9sub','9×19mm subsonic 140gr','V310552')
sb('45acp','.45 ACP 230gr FMJ','V311252')
sb('32acp','.32 ACP 73gr FMJ','V310232')
sb('380','.380 ACP 92gr FMJ','V310332')
sb('9mak','9×18mm Makarov 95gr','V310912')
sb('357','.357 Magnum 158gr FMJ','V311392')
sb('10auto','10mm Auto 180gr FMJ','V312092')
sb('76225','7.62×25mm Tokarev 85gr FMJ','V310112',notes='测试枪管287mm；用作有公开证据的弹种参考，并非TT33短枪管实测。')
sb('45colt','.45 Colt 230gr FMJ','V311762')
sb('38special','.38 Special 158gr FMJ','V311412')
sb('556','5.56×45mm近似 / .223 Rem 55gr FMJ参考','V341862',notes='以同直径/同弹形商业.223射表作5.56游戏外弹道代理；不表示现实弹药规格互换。枪管组比例另列。')
sb('545','5.45×39mm 60gr FMJ','V343252')
sb('76239','7.62×39mm 124gr FMJ','V340832')
sb('76251','7.62×51mm / .308 Win 147gr FMJ参考','V332212')
sb('76251match','7.62×51mm / .308 Win 175gr HPBT参考','V341812')
sb('76254r','7.62×54mmR 180gr FMJ','V332442')
sb('3006','.30-06 Springfield 147gr FMJ','V331652')
sb('3006m2','.30-06 M2参考 150gr FMJ','V341332')
sb('792','7.92×57mm / 8×57JS 196gr FMJ','V331842')
sb('303','.303 British 180gr FMJ','V331342')
sb('3030','.30-30 Winchester 150gr SP','V330352')
sb('65cm','6.5 Creedmoor 140gr FMJBT','V341602')
sb('300blk','.300 AAC Blackout 147gr FMJ','V341292')
sb('300blk_sub','.300 AAC Blackout 200gr亚音速','V341302')
sb('4570','.45-70 Government 405gr SP','V341892')
sb('338','.338 Lapua Magnum 250gr HPBT','V340402')
sb('300win','.300 Winchester Magnum 190gr HPBT','V340422')
profile('57','5.7×28mm SS190',715,src=['fn57'],drag=.0022,notes='初速为FN P90公开数据；远端阻力缺少同弹射表，k为PDW弹显式游戏估计。')
profile('46','4.6×30mm 40gr FMJ',635,src=[source('sb46','Sellier & Bellot: 4.6x30','https://www.sellier-bellot.cz/en/products/law-enforcement-products/4-6x30/list/','40gr FMJ参考635m/s；钢芯31gr为685m/s，不能混成相同弹头。')],drag=.0024,notes='k为小型PDW弹游戏估计。')
profile('58','5.8×42mm DBP87参考',930,src=['norinco_58','58_paper'],drag=families['556']['effectiveDragPerMeter'],notes='现代191/192各枪管未逐枪测速；阻力借5.56 FMJ家族代理，不是5.8实测射表。',quality='family_reference_drag_proxy')
profile('58pistol','5.8×21mm',480,src=['local_identity'],drag=.0025,notes='未取得可核验原厂具体弹头与测试枪管射表；480m/s与k均为显式游戏估计，非测量数据。',quality='game_estimate')
profile('127108','12.7×108mm',840,src=['utyos_127'],drag=.0005,notes='820–860范围取中值；k为大口径弹显式游戏估计，不借同直径就声称与.50BMG等价。')
profile('50bmg','12.7×99mm .50 BMG 660gr Precision参考',853.44,src=['barrett50'],drag=.0005,notes='k缺少同弹射表，为大口径弹显式游戏估计。')
profile('68fury','.277 SIG Fury / 6.8×51mm',914.4,src=['saami_fury'],drag=.00075,notes='商业135gr代表值；军用变体/短枪管及阻力未逐枪验证。')
profile('68tvcm','6.8×51mm TVCM',900,src=['local_identity','saami_fury'],drag=.0008,notes='独立TVCM身份；900m/s/k是游戏估计，SIG来源仅给邻近尺寸平台参照，绝不声称TVCM原厂值。',quality='game_estimate')
profile('9x39','9×39mm亚音速',290,src=['rosguard_vss'],drag=.0006,notes='公开VSS区间取近上端；k为重型亚音速弹游戏估计。')
profile('12755','12.7×55mm亚音速设定',300,src=['local_identity'],drag=.0008,notes='本地ASH12具体PS12弹头未知；300m/s/k为明确选择的重弹亚音速游戏方案，不是所有12.7×55都亚音速。',quality='game_estimate')
profile('6pdw','6×35mm PDW',740,src=['local_identity'],drag=.0017,notes='未取得可核验原厂射表，初速/k为中间威力PDW游戏估计。',quality='game_estimate')
profile('765long','7.65×20mm Longue',350,src=['local_identity'],drag=.0018,notes='完整弹种独立；初速/k为历史冲锋枪游戏估计，非.32ACP实测。',quality='game_estimate')
profile('763','7.63×25mm Mauser',440,src=['local_identity'],drag=.0024,notes='C96平台参考游戏估计，不借用7.62Tokarev工厂表冒充完全相同弹种。',quality='game_estimate')
profile('8nambu','8×22mm Nambu',290,src=['local_identity'],drag=.0015,notes='缺少当前可核验原厂射表，保留历史低速手枪量级的显式游戏估计。',quality='game_estimate')
profile('65arisaka','6.5×50mmSR Arisaka',760,src=['local_identity'],drag=.001,notes='Type38/Type11平台的显式游戏参考；不是6.5Creedmoor/6.5Swedish弹药互换或逐枪实测。',quality='game_estimate')
profile('77arisaka','7.7×58mm Arisaka',730,src=['local_identity'],drag=.001,notes='Type99常见原型设计参考；本地具体弹头/改型未核验，低置信游戏估计。',quality='game_estimate')
profile('nagant','7.62×38mmR Nagant工厂参考',225,214,50,['ppu_nagant'])
profile('455','.455 Webley MkII',213.36,src=['fiocchi455'],drag=.0008,notes='工厂目录不提供远端射表，k为亚音速大口径游戏估计。')
profile('455auto','.455 Webley Auto',230,src=['local_identity'],drag=.0009,notes='Webley1913自动手枪与.455转轮弹保持不同身份；初速/k是显式游戏估计。',quality='game_estimate')
profile('22wmr','.22 WMR手枪参考',320.04,src=['cci22pistol'],drag=.0028,notes='采用公开手枪弹数据作为短枪管参照；LifeCard极短枪管仍可能更慢，k为游戏估计。')
profile('500','.500 S&W Magnum',440,src=['local_identity'],drag=.0024,notes='缺少本地选用弹头/枪管证据；440m/s/k为显式游戏设计估计。',quality='game_estimate')
profile('50ae','.50 Action Express',440,src=['local_identity'],drag=.0025,notes='自定义Timeless/通称Deagle具体弹头未知；440m/s/k是已明确标记的游戏估计。',quality='game_estimate')
profile('38longcolt','.38 Long Colt / Colt转换型候选',260,src=['local_identity','sb_v311412'],drag=families['38special']['effectiveDragPerMeter'],notes='明确选择的历史游戏口径候选；以邻近.38手枪工厂弹的速度量级/阻力作代理，绝不表示.38Special与.38LongColt互换或本地转换型已确认。',quality='game_estimate')
profile('4440','.44-40 Winchester（M1873候选）',360,src=['local_identity','sb_v311762'],drag=.0012,notes='M1873有多种历史与现代口径；明确选择.44-40候选作游戏设计，初速/k为估计；.45Colt来源仅比较低速杠杆/手枪弹量级，不声称同弹或已核验。',quality='game_estimate')
profile('416','.416 Barrett 500gr A-Tip工厂参考',868.68,src=['barrett416'],drag=.00042,notes='原厂2026目录36英寸枪管代表值；本地MRAD具体长度未知；k为低阻大口径游戏估计。')
profile('408','.408 CheyTac 419gr',868.68,src=['cheytac408'],drag=.0004,notes='原厂目录2850/2950fps冲突取较低值；k为低阻大口径游戏估计。')
profile('8lebel','8×50mmR Lebel 200gr',700,646,100,['ppu_lebel'])
rho=1.225; density=11340.; cd=.47
def pellet_drag(diameter_m): return 3*rho*cd/(4*density*diameter_m)
source('sb_buck','Sellier & Bellot: BUCK SHOT V2117X2','https://www.sellier-bellot.cz/en/products/shotgun-shells/hunting-shells/detail/35','12/70铅鹿弹参考360m/s；提供5.1/7.6/8.4mm弹丸选项，模型用8.4mm代表。',referenceValues={'muzzleVelocityMps':360,'representativePelletDiameterM':.0084})
source('sb_slug','Sellier & Bellot: SPECIAL SLUG V051732','https://www.sellier-bellot.cz/en/products/shotgun-shells/hunting-single-slugs-for-shotguns/detail/1','12/70独头弹参考420m/s。',referenceValues={'muzzleVelocityMps':420})
source('sb410','Sellier & Bellot: .410 spherical bullet V051562','https://www.sellier-bellot.cz/en/products/shotgun-shells/hunting-shells/detail/549','.410/76 五枚8.4mm弹丸参考360m/s；不是12Gauge弹种。',referenceValues={'muzzleVelocityMps':360,'pellets':5})
profile('12g','12 Gauge鹿弹（8.4mm铅丸代表）',360,src=['sb_buck','nasa_drag','nasa_sphere'],drag=pellet_drag(.0084),notes='k=3ρCd/(4ρ铅d)，ρ=1.225kg/m³、ρ铅=11340kg/m³、Cd=0.47。恒定Cd忽略跨音速变化；这是明确模型，不是G1/实测射表。')
profile('12slug','12 Gauge独头弹',420,src=['sb_slug'],drag=.0025,notes='仅用于bullet_amount=1且无爆破/燃烧的普通独头弹；k为游戏估计。')
profile('410','.410 bore五弹丸',360,src=['sb410','nasa_drag'],drag=pellet_drag(.0084),notes='球丸近似与12g相同直径仅影响外弹道，不统一弹药ID。')
profile('184shot','18.4mm游戏霰弹（具体壳型未证）',360,src=['local_identity','sb_buck','nasa_drag'],drag=pellet_drag(.0084),notes='只借常规鹿弹外弹道量级作为低置信估计，不冒充钨丸原厂数据，不改注册弹种。',quality='game_estimate')
profile('23shot','23mm游戏霰弹',300,src=['local_identity','nasa_drag'],drag=.004,notes='KS23本地弹丸类型/直径不完整；300m/s/k显式游戏估计，不自动套12Gauge射表。',quality='game_estimate')

special_ids={
    'cib:ar2':'Half-Life AR2能量弹，不适用普通火药枪弹射表。',
    'ccrp:mp9_thunder':'雷电能量弹，不用常规重力/空气阻力覆盖特殊脚本。',
    'origin:cove-a':'作者14.5mm APFSDS特殊平台；无匹配射表，不把普通大口径弹代理当实测。',
    'cib:error':'调试占位枪。',
    'hamster:flaregun':'信号弹，需要独立燃烧/照明轨迹模型。',
    'suffuse:trapper50cal':'前装.50枪；弹丸/弹头身份未确定，避免与现代.50弹混用。',
    'cib:origin12db':'龙息燃烧霰弹，保留原特殊弹行为，未冒充普通鹿弹。',
    'cib:qba221_burst':'爆破独头弹，未取得匹配射表。',
    'cib:widow':'TF2 Widowmaker特殊平台/特效，普通射表不适用。',
    'hamster:madsen':'麦德森历史多口径变体，本地只声明长子弹，弹种未证。',
    'hamster:luger1906':'历史实验步枪具体弹种与变体未证。',
    'hamster:gras1874':'11mm Gras与后期改膛型未由当前资产区分。',
    'hamster:m1879revolver':'历史专用弹具体工厂参考未取得。',
    'hamster:martinihenry':'.577/450及后期改膛身份未由当前资产区分。',
    'hamster:krag':'Krag-Jørgensen家族存在6.5×55、.30-40等不同版本，本地只声明长子弹，具体弹种未证。',
}
inferred_overrides={
    'hamster:coltm1851':'38longcolt','hamster:coltm1892':'38longcolt','hamster:coltm1892pair':'38longcolt',
    'hamster:colt1873':'45colt','hamster:sw_mk2':'455','hamster:sw_mk2_41':'455','hamster:webley':'455',
    'hamster:nagantcarbine':'nagant','hamster:mg1417':'792','hamster:berthier':'8lebel','hamster:lebel1886':'8lebel',
    'hamster:smle_mk3':'303','hamster:type99':'77arisaka','hamster:sharps':'4570',
    'hamster:win1894':'3030','hamster:win1873':'4440',
    'tacz:deagle':'50ae','tacz:timeless50':'50ae','ccrp:timeless_50':'50ae',
    'ccrp:shield_ots33':'9mak','rainforest:vz64':'380','rainforest:vz68':'9x19','suffuse:gepardpdw':'9x19',
    'ccrp:camg_krait':'68tvcm','ccrp:aug_a3s':'300blk_sub','ccrp:scar_sc':'300blk','suffuse:xm7':'76251',
    'ccrp:mcx_spear_tombstone':'68fury','gz:t81s':'556','gz:tactical_t81s':'556','gz:m700_carbon':'3006',
    'rainforest:em2':'76251','cib:qbu202':'338','cibs:qbu202_lingche':'338','suffuse:axmc':'338','suffuse:axsr':'338',
    'classicr:mrad':'338','ccrp:mk18_mjolnir':'338','tacz:ai_awp':'300win',
}

short_556={'ccrp:camg_mk18','ccrp:cslr_42a','ccrp:fightlite_scr_hg','ccrp:hk416_sopmod','ccrp:hk416c','ccrp:m4_cqbr','cib:mk18','cib:sig552','cibs:mk18_jianjiu','classicr:mk18_mod1','tacz:g36k','suffuse:mpdr'}
long_556={'ccrp:aug_a3_dmr','ccrp:aug_hbar','ccrp:ddm4_v7_pro','ccrp:m249_saw','ccrp:m38_spr','cib:galil','cib:m16a4','cib:negev','classicr:spr_15','rainforest:l85a1','rainforest:l86a2','rainforest:famas','tacz:m16a1','tacz:m16a4','tacz:m249','tacz:spr15hb','tacz:fn_evolys','ccrp:m14_hbar'}

guns=[]
for gun in assets['guns']:
    gid=gun['id']; p=prior[gid]; raw=gun['rawData']['bullet']; family=p.get('family')
    reason='依据本地原型/弹种核对，采用该弹种代表外弹道；不是此枪具体弹头与枪管实测。'
    confidence='中'; inference=p.get('basis','')
    if gid in inferred_overrides:
        family=inferred_overrides[gid];confidence='低'
        reason='明确选择的低置信游戏原型方案；原资产存在变体/改膛歧义，不据此改ammo ID。'+p.get('basis','')
    if family is None and gun['type']=='shotgun': family='184shot'
    if family=='12g' and raw.get('bullet_amount',1)==1: family='12slug'
    if family=='9x19' and 'mp5_sd' in gid: family='9sub'
    if family=='300blk' and ('honeybadger' in gid or 'silence_meteor' in gid):family='300blk_sub'
    if family=='76251' and gun['type']=='sniper':family='76251match'
    if gid=='hamster:m1garand': family='3006m2'
    entry={'gunId':gid,'dataId':gun['rawIndex']['data'],'name':gun['displayName'],'type':gun['type'],'icon':gun['icon']['path'],'currentAmmoId':gun['rawData']['ammo'],'firearmInference':inference,'confidence':confidence,'rawBullet':{k:raw.get(k) for k in ('speed','gravity','friction','life','bullet_amount')},'sourceData':gun['chosenCandidate']['dataSource']}
    excluded=special_ids.get(gid)
    if gun['type']=='rpg':excluded='火箭/榴弹发射器存在推进、低速榴弹与特殊脚本差别，暂保留原包轨迹；不是普通枪弹。'
    if family not in families and not excluded:excluded='完整弹种或原型尚未确认；不凭共享补给ID伪造现实初速。'
    if excluded:
        entry.update(action='preserve',caliberFamily=family or 'special_or_unknown',proposedMuzzleVelocityMps=None,gravityMps2=None,effectiveDragPerMeter=None,sourceIds=['local_identity'],reason=excluded,referenceQuality='preserve_original')
    else:
        f=families[family]; v=f['referenceVelocityMps']; src=f['sourceIds'][:]; barrel='弹种参考；本地枪管长度未逐项验证'
        if family=='556':
            if gid in short_556:ratio=2888/3310;barrel='推断短枪管组，约10–12英寸量级；借Hornady同弹相对比例'
            elif gid in long_556:ratio=1.;barrel='推断长枪管/班机/SPR组；采用公开长测试枪管代表值'
            else:ratio=3096/3310;barrel='默认卡宾枪组；借Hornady14.5/20英寸相对比例'
            v=round(v*ratio,2);src.append('hornady_barrel')
        if family=='545' and ('aks74u' in gid):v=735;src.append('aks74u');barrel='AKS-74U原型公开参考735m/s'
        if family=='58':
            if gun['type']=='mg' or gun['type']=='sniper':v=970;barrel='长枪管/班机/射手组，借1999枪族970m/s参考'
            elif '192' in gid or '95b' in gid:v=837;barrel='短枪管推断：930×0.90；0.90为显式游戏系数，非实测'
        if gid=='ccrp:mp5_sd':v=305;barrel='一体消声MP5SD：用亚音速工厂9mm代表，不把原普通弹速度保持超音速';src.append('hk_mp5')
        if gun['rawData']['ammo']=='ccrp:556x45_m995':v=1030;src.append('nammo_m995');barrel='M995特殊工厂弹参考，未对本地不同枪管另作精确编造'
        if f['referenceQuality']=='game_estimate':confidence='低'
        entry.update(action='apply',caliberFamily=family,cartridge=f['caliber'],proposedMuzzleVelocityMps=v,gravityMps2=9.80665,effectiveDragPerMeter=f['effectiveDragPerMeter'],sourceIds=list(dict.fromkeys(src+['nist_g','nasa_drag'])),reason=reason,barrelAssumption=barrel,confidence=confidence,referenceQuality=f['referenceQuality'],dragBasis=f['dragBasis'])
    guns.append(entry)

assert len(guns)==424 and len({g['gunId'] for g in guns})==424
assert all(g['effectiveDragPerMeter']>0 and g['proposedMuzzleVelocityMps']>0 for g in guns if g['action']=='apply')
result={'schemaVersion':1,'generatedDate':DATE,'unitConvention':{'blockMeters':1,'ticksPerSecond':20,'standardGravityMps2':9.80665,'gravityBlocksPerTickSquared':9.80665/400},'scope':'全部424把结构完整的本地枪械候选；静态资源分析，不等同游戏实际加载/实测。','notAmmoUnification':True,'noRuntimeLaunch':True,'method':{'velocity':'公开工厂/官方平台代表值；枪管明确者按对应平台，缺少证据者弹种代表或显式低置信估计。','drag':'同弹V0/Vref有证据时k=ln(V0/Vref)/distance；k只为短程有效空间阻力，不等于G1/G7 BC或完整跨音速拟合。','pellets':'铅鹿弹球丸模型k=3*rho_air*Cd/(4*rho_lead*diameter)，模型常量显式保存；独头弹单列。','unsupported':'能量/火箭/信号/未知改型保留原值，逐枪列出原因。','identities':'不更改ammo ID，不将PUBG补给桶当现实完整弹种。','barrels':'5.56相对比例借单一Hornady工厂弹的公开多枪管测量；只作估计，不宣称不同枪型号已测速。'},'caveats':['所有枪使用同一标准重力；下坠差别来自初速、飞行时间、阻力与瞄具归零，不来自重弹有更大重力加速度。','未建模风、海拔、温度、湿度、旋偏、科里奥利、马赫数相关Cd、逐枪真实归零。','低置信字段是可调整的游戏数值方案，不是现实实测值；待核验型号在每枪reason中列明。','原包动画/脚本/附件弹速修正仍可能影响体验；本次未启动游戏。'], 'summary':{'guns':len(guns),'apply':sum(g['action']=='apply' for g in guns),'preserve':sum(g['action']=='preserve' for g in guns),'families':len(families),'confidence':dict(Counter(g['confidence'] for g in guns)),'referenceQuality':dict(Counter(g['referenceQuality'] for g in guns))},'inputSha256':{'gunAssets':hashlib.sha256((OUT/'gun-assets.json').read_bytes()).hexdigest(),'ammoUnification':hashlib.sha256((ROOT/'docs/compatibility/ammo-unification.json').read_bytes()).hexdigest()},'sources':list(sources.values()),'families':families,'guns':guns}
(OUT/'ballistics-research.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
(OUT/'evidence/ballistics-sources.json').write_text(json.dumps(list(sources.values()),ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result['summary'],ensure_ascii=False))
print('PRESERVE:',', '.join(g['gunId'] for g in guns if g['action']=='preserve'))
