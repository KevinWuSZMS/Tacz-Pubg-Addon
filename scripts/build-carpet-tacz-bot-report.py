"""Build an offline, source-linked feasibility report; never loads Minecraft or changes mods."""
from pathlib import Path
import hashlib
import html
import json

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs/research/carpet-tacz-bots.html"
EVIDENCE = ROOT / "logs/carpet-tacz-bot-research"
CARPET = "downloads/carpet/research-20261018/carpet-runtime/source/carpet/src/main/java/"
TACZ = "logs/carpet-tacz-bot-research/tacz-source/com/tacz/guns/"
ADDON = "neoforge-1.21.1/src/main/java/com/example/examplemod/"

SOURCES = {
    "carpet": ("Carpet NeoForged 1.0.8 固定提交", "https://github.com/chililisoup/neoforge-carpet/tree/44360a01d00b4c781cb07d7ab65cbf7357f000cd"),
    "ams": ("AMS NeoForged 版本关联提交", "https://github.com/ProstoCheI/Carpet-AMS-Addition-NeoForged/tree/3ef53eea81553cec7f20b6d940ff5981b9d20d81"),
    "fear": ("F.E.A.R. 原开发者 GOAP 论文", "https://pages.cs.wisc.edu/~dyer/cs540/handouts/gdc2006_orkin_jeff_fear.pdf"),
    "halo": ("Halo 2 原开发者 GDC 文章", "https://www.gamedeveloper.com/programming/gdc-2005-proceeding-handling-complexity-in-the-i-halo-2-i-ai"),
    "quake": ("id Software 原版 Quake III 源码", "https://github.com/id-Software/Quake-III-Arena/tree/dbe4ddb10315479fc00086f08e25d968b4b43c49"),
    "epic": ("Epic 官方 EQS 文档", "https://dev.epicgames.com/documentation/unreal-engine/environment-query-system-quick-start-in-unreal-engine?lang=en-US"),
    "rl": ("DeepMind Quake CTF 原研究", "https://deepmind.google/blog/capture-the-flag-the-emergence-of-complex-cooperative-agents/"),
    "ally": ("NVIDIA 对 KRAFTON 团队的技术访谈", "https://developer.nvidia.com/blog/how-krafton-built-pubg-ally-a-co-playable-character-powered-by-nvidia-ace/"),
    "paper": ("PUBG Ally 团队论文 v2（预印本）", "https://arxiv.org/html/2609.29837v2"),
    "beta": ("PUBG 官方 42.1 / Ally Duo 测试公告", "https://www.pubg.com/en/news/10179"),
    "ordinary": ("PUBG 2025 官方路线图", "https://www.pubg.com/en/news/8308"),
    "baritone": ("Baritone 1.21.1 / IPlayerContext 固定提交", "https://github.com/cabaletta/baritone/blob/11ea66cd11c3bdf2eb7a8dfdbdec97b964fda6c7/src/api/java/baritone/api/utils/IPlayerContext.java"),
}

LOCAL = [
    ("C1", "Carpet 假人类型、异步加入与死亡", CARPET + "carpet/patches/EntityPlayerMPFake.java", "class EntityPlayerMPFake"),
    ("C2", "Carpet 移动、转向、原版 USE / ATTACK", CARPET + "carpet/helpers/EntityPlayerActionPack.java", "public EntityPlayerActionPack setForward"),
    ("C3", "ActionPack 创建与 tick Mixin", CARPET + "carpet/mixins/ServerPlayer_actionPackMixin.java", "public EntityPlayerActionPack getActionPack"),
    ("C4", "假人的 send 空实现", CARPET + "carpet/patches/NetHandlerPlayServerFake.java", "void send"),
    ("C5", "原生冻结豁免 Player", "downloads/carpet/research-20261018/carpet-runtime/source/neoforge-21.1.259/net/minecraft/world/TickRateManager.java", "public boolean isEntityFrozen"),
    ("C6", "冻结前照常发布服务器 Pre 事件", "downloads/carpet/research-20261018/carpet-runtime/source/neoforge-21.1.259/net/minecraft/server/MinecraftServer.java", "EventHooks.fireServerTickPre"),
    ("T1", "TaCZ r7 操作接口", TACZ + "api/entity/IGunOperator.java", "static IGunOperator fromLivingEntity"),
    ("T2", "LivingEntity Mixin 与默认时间戳", TACZ + "mixin/common/LivingEntityMixin.java", "System.currentTimeMillis()"),
    ("T3", "服务器射击条件与 ShootResult", TACZ + "entity/shooter/LivingEntityShoot.java", "ShootResult shoot"),
    ("T4", "服务器装填与原生换弹状态", TACZ + "entity/shooter/LivingEntityReload.java", "public void reload"),
    ("T5", "客户端镜头后坐力", TACZ + "client/event/CameraSetupEvent.java", "initialCameraRecoil"),
    ("T6", "共用 GunRecoil 曲线", TACZ + "resource/pojo/data/gun/GunRecoil.java", "genPitchSplineFunction"),
    ("T7", "一次触发调度原生连发", TACZ + "item/ModernKineticGunScriptAPI.java", "int cycles = modifyProperty"),
    ("T8", "连发周期任务的真实毫秒调度", TACZ + "util/CycleTaskHelper.java", "public static void addCycleTask"),
    ("A1", "当前 Addon 报名入口", ADDON + "zones/match/MatchService.java", "List<MatchEngine.Participant> roster"),
    ("A2", "当前 Addon 名单伤害隔离", ADDON + "zones/match/MatchEngine.java", "public boolean blocksPlayerDamage"),
    ("A3", "地面物资距离 / 视线 / 空主手检查", ADDON + "lootspawn/GroundLootPickup.java", "public static boolean canPickup"),
    ("A4", "地面物资库存与数量事务", ADDON + "lootspawn/GroundLootEntity.java", "public InteractionResult pickup"),
    ("P1", "仅编译的接口兼容探针", "logs/carpet-tacz-bot-research/TaCZCarpetSignatureProbe.java", "public final class TaCZCarpetSignatureProbe"),
]

def esc(value):
    return html.escape(str(value), quote=True)

def ref(key):
    title, url = SOURCES[key]
    return f'<a href="{esc(url)}" target="_blank" rel="noreferrer">{esc(title)}</a>'

def local(key):
    return f'<a class="eref" href="#e-{key}">[{key}]</a>'

def code(text):
    return "<pre><code>" + esc(text.strip()) + "</code></pre>"

def section(key, title, body):
    return f'<section id="{key}"><h2>{title}</h2>{body}</section>'

def main():
    probe = json.loads((EVIDENCE / "probe-compile-receipt.json").read_text(encoding="utf-8"))
    assert probe["success"] and not probe["probe_executed"] and not probe["game_launched"]
    baseline = json.loads((EVIDENCE / "baseline.json").read_text(encoding="utf-8"))
    sections = []
    sections.append(section("decision", "01　结论与可信度", f"""
<p class="lead">可以把 Carpet 假人接成 TaCZ 持枪人机。现有代码给出了服务端动作入口，实际版本的类型与接口编译检查也已通过；完整枪战仍需最小游戏试验确认。</p>
<div class="cards"><article><b>身体</b><strong>Carpet ServerPlayer</strong><p>玩家身份、背包、移动与原版动作。</p></article><article><b>枪械</b><strong>TaCZ 服务端接口</strong><p>沿用弹药、冷却、装填、弹丸与事件。</p></article><article><b>大脑</b><strong>先用代码构建</strong><p>感知、记忆、战术、寻路与拟人瞄准。</p></article></div>
<table><thead><tr><th>判断</th><th>证据等级</th><th>当前能得出的结论</th></tr></thead><tbody>
<tr><td>Carpet 假人是 ServerPlayer 子类</td><td><span class="good">源码＋实际字节码</span></td><td>可以进入玩家名单、库存与比赛逻辑。{local('C1')}</td></tr>
<tr><td>假人类型可传给 TaCZ 操作者 API</td><td><span class="good">隔离 javac 探针通过</span></td><td>draw / aim / shoot / reload / bolt 等签名可编译。未执行探针。{local('P1')}</td></tr>
<tr><td>没有真实客户端也有服务端射击路径</td><td><span class="inference">调用链判断</span></td><td>服务端核心不等待假人的客户端 ACK；Mixin 初始化、库存能力、事件与观察者效果仍待游戏验证。{local('T2')}{local('T3')}{local('C4')}</td></tr>
<tr><td>已经完成 PUBG Addon 人机</td><td><span class="pending">尚未实现</span></td><td>本次交付研究报告、来源和编译证据，没有安装新的 Bot 控制器。</td></tr>
</tbody></table>
<p>我的建议：先做 <b>Carpet 身体＋TaCZ 原生枪械桥＋代码战斗脑</b>，跑通有限弹药与完整淘汰；随后补寻路、搜集、进圈和小队战术。语言模型作为后续的可选协作层。</p>
"""))
    sections.append(section("versions", "02　核对范围与实际版本", f"""
<table><thead><tr><th>组件</th><th>本机核对对象</th><th>来源锁定</th></tr></thead><tbody>
<tr><td>Minecraft / NeoForge</td><td>1.21.1 / 21.1.259</td><td>当前开发环境及 mapped JAR。</td></tr>
<tr><td>Carpet: NeoForged</td><td>1.0.8+v251027；内部 1.4.147+v1.0.8-251027</td><td>标签 1.0.8；提交 <code>44360a01d00b4c781cb07d7ab65cbf7357f000cd</code>。{ref('carpet')}</td></tr>
<tr><td>Carpet AMS Addition NeoForged</td><td>1.0.1 / MC1.21.1</td><td>无标签；以版本、日期与关键字节码关联提交 <code>3ef53eea81553cec7f20b6d940ff5981b9d20d81</code>，未做可复现构建。{ref('ams')}</td></tr>
<tr><td>TaCZ 非官方 NeoForge 移植</td><td>1.1.8-hotfix-r7</td><td>安装 JAR、配套源码 JAR及逐类 javap 比对。</td></tr>
<tr><td>PUBG Addon</td><td>当前本地源码与已部署构建</td><td>已有比赛名单、蓝圈阶段、胜负、物资和弹匣实验。</td></tr>
</tbody></table>
<p>核对截至 <b>2026-10-18</b>。本报告的 API 结论针对上述组合，不把 Fabric Carpet 主线或其他 TaCZ 版本当成完全相同的实现。完整 SHA-256、来源提交和字节码目录见文末证据。</p>
"""))
    sections.append(section("carpet", "03　Carpet 能提供什么", f"""
<table><thead><tr><th>代码入口</th><th>可以复用</th><th>需要额外实现</th></tr></thead><tbody>
<tr><td><code>EntityPlayerMPFake extends ServerPlayer</code></td><td>真实服务器玩家实体、库存、生命、队伍、玩家列表。{local('C1')}</td><td>战术、目标选择、感知与导航。</td></tr>
<tr><td><code>EntityPlayerMPFake.createFake(...)</code></td><td>创建假人并走加入服务器路径。</td><td>它返回 true 只说明异步创建已排队；等待实际注册后，再读 UUID、装备及开局。</td></tr>
<tr><td><code>ServerPlayerInterface.getActionPack()</code></td><td>通过 Carpet Mixin 获取动作包，每 tick 更新。{local('C3')}</td><td>明确哪些 UUID 归本控制器管理，避免和其他脚本抢控制权。</td></tr>
<tr><td><code>setForward / setStrafing / setSneaking / setSprinting</code></td><td>前进、横移、蹲下与疾跑输入。{local('C2')}</td><td>绕墙、楼梯、门、跳跃时机与脱困。</td></tr>
<tr><td><code>look(yaw, pitch) / lookAt / turn</code></td><td>服务端转头与身体朝向。</td><td>平滑追踪、反应延迟、误差、弹道提前量和枪械反冲。</td></tr>
<tr><td><code>setSlot(1..9)</code></td><td>切快捷栏；它内部减1，原版 inventory.selected 是0起始。</td><td>切枪后主动调用 TaCZ draw，而不只换持有物。</td></tr>
<tr><td><code>stopAll / stopMovement / kill</code></td><td>停止输入及移除本次假人。</td><td>对局结束、死亡、卸载时的控制器清理与所有权检查。</td></tr>
</tbody></table>
<h3>原版动作不会自动变成枪械操作</h3>
<p><code>USE</code> 走原版物品使用/交互；<code>ATTACK</code> 走近战与挖掘分支，实体分支还会跳过 continuous 近战。它们都没有调用 TaCZ 的 shoot/reload/aim。<code>/player Bot attack continuous</code> 因此不能作为 TaCZ 连射接口。{local('C2')}</p>
<h3>四个容易误判的地方</h3>
<ul><li>假网络连接丢弃发给自己的数据包，并没有创建 LocalPlayer、镜头、按键或真实客户端。其余真实玩家的观察效果需要实测。{local('C4')}</li>
<li>这个 Carpet 类不是 NeoForge 的 <code>FakePlayer</code>，也没有把其扩展 <code>isFakePlayer()</code> 改成 true。识别应使用实际类＋本服务持有的 UUID。</li>
<li>Carpet 假人死亡后会进入重置生命再安排退出的路径；不能把中间的满生命看成 PUBG 允许复活。当前比赛死亡记录必须继续生效。</li>
<li>AMS 的 <code>fancyFakePlayerName</code> 会改变 scoreboard 分队；离线 UUID 规则也不能替代“读取实际注册 UUID”。这些规则要与对局队伍策略一起核对。</li></ul>
<p>AMS 的拾物扩展只挂在 <code>ItemEntity.playerTouch</code>，提供拾取限制，不是寻物 AI；它不会自动接管本附属的 GroundLootEntity。独立 bot 应用 createFake，不用会断开真人的 createShadow；直接调用创建 API 还需自行验证重名、准入、位置和人数，显式指定生存模式及非飞行。</p>
"""))
    sections.append(section("tacz", "04　TaCZ 开火、瞄准与换弹接口", f"""
<p>在服务器线程，用 <code>IGunOperator.fromLivingEntity(fake)</code> 获取操作者。TaCZ 的 LivingEntity Mixin 提供实现，参数中的假人也是 LivingEntity。以下签名已按 r7 核对。{local('T1')}{local('T2')}</p>
<table><thead><tr><th>动作 / 查询</th><th>r7 实际签名</th><th>使用原则</th></tr></thead><tbody>
<tr><td>切枪初始化</td><td><code>draw(Supplier&lt;ItemStack&gt;)</code></td><td>换主手枪或切槽时调用一次，例如 <code>draw(fake::getMainHandItem)</code>；等待切枪冷却。</td></tr>
<tr><td>瞄准</td><td><code>aim(boolean)</code></td><td>同步朝向并等待 ADS 进度；并非传目标实体。</td></tr>
<tr><td>开火</td><td><code>shoot(Supplier&lt;Float&gt; pitch, Supplier&lt;Float&gt; yaw)</code></td><td>返回 ShootResult；有 +long 时间戳、再 +float 蓄力重载。默认两 Supplier 版本自行生成相对时间戳。</td></tr>
<tr><td>换弹 / 中断</td><td><code>reload() / cancelReload()</code></td><td>沿用原枪脚本、库存检查与进弹/收尾阶段，不直接填满枪内 NBT。{local('T4')}</td></tr>
<tr><td>拉栓 / 射击模式</td><td><code>bolt() / fireSelect()</code></td><td>根据需求触发一次，随后等待原生状态。</td></tr>
<tr><td>冷却</td><td><code>getSynShootCoolDown() / getSynDrawCoolDown()</code></td><td>返回 long；还有 getSynMeleeCoolDown。没有 getSynShootTimestamp。</td></tr>
<tr><td>换弹与拉栓状态</td><td><code>getSynReloadState() / getSynIsBolting()</code></td><td>换弹可检查 <code>getStateType().isReloading()</code>；没有 getSynBoltCoolDown。</td></tr>
<tr><td>瞄准 / 疾跑</td><td><code>getSynIsAiming() / getSynAimingProgress() / getSynSprintTime()</code></td><td>这些是原生状态；开火前协调疾跑、ADS和姿势，不并行发互相冲突的动作。</td></tr>
</tbody></table>
<p class="callout">参数顺序需要分清：Carpet <code>look(yaw, pitch)</code>；TaCZ <code>shoot(pitchSupplier, yawSupplier)</code>。r7 没有 <code>shoot(float, float)</code> 这个签名。</p>
<h3>推荐调度顺序</h3>
{code('''新假人实际加入 → 取得 UUID / 操作者 / 库存能力
装备枪和有限备弹 → draw（只在切枪时）→ 等待原生冷却
更新感知 → 选择目标与掩体 → 更新身体朝向与 bot 瞄准状态
aim → 等待 ADS / 结束疾跑 → 请求一次 shoot
  SUCCESS       → 记录接受触发；按实际每发事件计消耗、反冲与统计
  COOL_DOWN     → 等待，不用循环补发
  NEED_BOLT     → 请求一次 bolt，等待完成
  NO_AMMO       → 有备弹则 reload；否则寻找补给或撤退
  IS_RELOADING  → 等原生进弹与收尾，按战术需要中断
  其他失败       → 记录原因，修复状态或暂停此 bot
死亡 / 淘汰 / 对局结束 → 停止移动与触发器 → 清理自有假人''')}
<p>这是未来控制器的调度设计，尚未实现。编译探针只用于核验类型和签名；探针里把若干接口放在同一方法中，不代表可照抄为实战调用顺序。</p>
"""))
    sections.append(section("pitfalls", "05　联动中必须解决的限制", f"""
<div class="issues">
<article><h3>1. 时间域与原生冻结不统一</h3><p>TaCZ 开火、draw、reload、bolt、ADS 等大量使用现实毫秒；比赛按服务器 tick。原生 /tick freeze 仍发布 Pre 事件，且 Player 豁免冻结：从源码推断，假人和已排 BURST 可继续，非玩家子弹却暂停，尚未实测。首轮保持正常20 TPS，不能把 freeze 当完整枪战暂停。{local('T2')}{local('C5')}{local('C6')}</p></article>
<article><h3>2. BURST 由原生脚本发整轮</h3><p>一次触发调度整轮连发；AI 每弹重复触发会发出多轮。只停止新触发不能取消已排队回调，需专门设计结束/暂停边界。实际服务器暂停或进程停顿恢复后，周期任务也可能在同 tick 追补。AUTO 首版每 tick 最多一次触发，高射速枪单独测。{local('T7')}{local('T8')}</p></article>
<article><h3>3. 假人不会自动吃到客户端镜头反冲</h3><p>原生镜头及本附属持久后坐力作用于本地客户端；直接 server shoot 保留服务端散布和弹道，但不会自动改变假人镜头角度。共用 GunData/GunRecoil 可在服务器读取，应另建 bot 角度反冲、恢复与补偿能力模型，叠加在瞄准控制上。{local('T5')}{local('T6')}</p></article>
<article><h3>4. 真实射击仍有原生检查</h3><p>库存 ItemHandler、弹种、膛内弹、切枪、疾跑、事件取消、热量及 NETWORK_FAIL 都可能阻止射击。两 Supplier 调用走服务端完整检查；没有客户端 ACK 依赖，不等于这些条件全部已满足。{local('T3')}</p></article>
<article><h3>5. 拾取地面物资需要本附属桥</h3><p>复用 GroundLootPickup.canPickup → loot.pickup：主手空、可交互距离、最近射线命中、墙体与库存容量都继续检查。Bot 先把枪收回槽位并看向物资；只调用内部 pickup 会跳过外层距离/射线规则。{local('A3')}{local('A4')}</p></article>
<article><h3>6. ServerPlayer 没有 Mob 导航控制器</h3><p>不能直接给它调用 getNavigation，也不能把需要 Mob 的 PathNavigation 当玩家寻路。Carpet 只提供移动输入；需要独立路径规划和玩家步行执行器。</p></article>
<article><h3>7. 当前比赛先登记后冻结名单</h3><p>现有 MatchService 按在线玩家列表收集范围内的生存/冒险玩家，不排除 Carpet 类。Bot 必须实际加入、装备、分队后再开局；中途补一个假人不会自动获得资格，名单伤害隔离也会挡住其战斗。{local('A1')}{local('A2')}</p></article>
<article><h3>8. 无真实客户端不等于零成本</h3><p>假人仍有实体 tick、碰撞、玩家区块加载、库存及同步成本。大量假人分散到远处可能让区块成为主要开销；容量必须用实际场地和战斗负载测量。</p></article>
</div>
"""))
    sections.append(section("fps", "06　FPS 人机由代码还是模型驱动", f"""
<p><b>两种都存在，常见的是分层组合。</b>状态机、行为树、目标规划、感知与寻路本身就能构成完整人机；神经策略要经过训练；语言模型也仍需要动作执行层。下面是可查的一手案例，不用于推断所有商业 FPS 的占比。</p>
<table><thead><tr><th>案例</th><th>公开的驱动方式</th><th>对本项目的启发</th></tr></thead><tbody>
<tr><td>F.E.A.R.</td><td>GOAP 规划＋Goto/Animate/UseSmartObject 执行态。{ref('fear')}</td><td>把“去掩体装弹”等行为拆成有前提与结果的动作。</td></tr>
<tr><td>Halo 2</td><td>层次状态/行为 DAG、优先级、事件刺激和世界记忆。{ref('halo')}</td><td>用共享记忆和抢占处理受击、失去视线与战术变化。</td></tr>
<tr><td>原版 Quake III</td><td>AI 状态节点、模糊目标权重、AAS 导航，反应和瞄准参数按武器调整。{ref('quake')}</td><td>“像人”可用反应时间、武器策略及控制参数表达。</td></tr>
<tr><td>DeepMind Quake CTF 研究</td><td>训练出来的深度强化学习协作策略；不是原版 Quake bot。{ref('rl')}</td><td>RL 是另一条路线，需要环境、奖励、训练和评估管线。</td></tr>
<tr><td>PUBG Ally</td><td>行为树执行即时动作，语言模型处理意图与高层协作。{ref('ally')}</td><td>语言/高层目标与快速战斗执行可以独立演进。</td></tr>
</tbody></table>
<h3>PUBG Ally 与普通敌方人机需要分开</h3>
<p>2026年6月访谈描述本地2B模型和 tick 速率行为树。9月团队预印本进一步列出英文 Mistral-NeMo-Minitron-2B、韩文 Kanana 2.1B、中文 Qwen3-1.7B；语言模型不承担逐 tick 键鼠控制，所述训练也不另加任务奖励 RL 目标。{ref('paper')}</p>
<p>已确认的 Ally Duo 是 PC Arcade 限时测试：6月17日维护后至7月1日07:00 UTC。没有据此声称截至10月18日全部普通匹配敌方 bot 都使用 SLM。普通 bot 的完整底层算法未在所查官方路线图公开，不能凭行为效果反推为某一种模型。{ref('beta')}、{ref('ordinary')}</p>
<p>所以本项目不必先部署小模型才能出现战斗人机。若将来要听懂“跟我走、把弹药给我、掩护我”，再给高层增加可选语言模型；瞄准、躲避、枪械状态和合法动作继续由快速代码处理。</p>
"""))
    sections.append(section("navigation", "07　哪些现成能力能与假人联动", f"""
<table><thead><tr><th>选项</th><th>适配判断</th><th>建议</th></tr></thead><tbody>
<tr><td>Carpet 动作包</td><td>可以服务端驱动玩家身体；没有现成战斗脑。</td><td><span class="good">第一版执行层</span>，通过小型适配器封装，避免业务代码处处依赖 Carpet。</td></tr>
<tr><td>Baritone 1.21.1</td><td>IPlayerContext 绑定 Minecraft/LocalPlayer，相关世界查询使用 ClientLevel。{ref('baritone')}</td><td>不能直接挂到服务端 Carpet 假人；可研究算法移植，或走真正客户端方案。</td></tr>
<tr><td>原版 Mob Goals / Navigation</td><td>寻路与移动控制器依赖 Mob。</td><td>可参考行为组织；给 ServerPlayer 用时需要专门适配，不是加几行 Goal 即可。</td></tr>
<tr><td>UE 行为树 / EQS / NavMesh</td><td>是引擎内组件，不能直接装入 Minecraft。{ref('epic')}</td><td>借候选掩体评分、黑板和导航分层思想，重新实现小范围版本。</td></tr>
<tr><td>自定义持枪 Mob</td><td>TaCZ 接口面向 LivingEntity，可能走另一条载体路线。</td><td>有原版 Mob 导航优势，但玩家库存、队伍、比赛名单、拾取与伤害归因都需改造；留作替代后端。</td></tr>
<tr><td>多个真实客户端</td><td>有真人 LocalPlayer、镜头与客户端输入链。</td><td>控制与渲染成本较高；适合兼容性对照，不作为首版多人机承载方案。</td></tr>
</tbody></table>
<p>导航建议从<b>经过人工确认的可走点图＋局部避障</b>开始；随后升级为只读取已加载区块的步行体素 A*。路径代价纳入墙、净空、台阶/门、掉落风险、蓝圈伤害与转移时限。以 Carpet 前进/横移/跳跃执行路径；卡住时重规划、让路或回退，先不靠瞬移解围。</p>
"""))
    sections.append(section("design", "08　我建议的 PUBG Addon 人机结构", f"""
<div class="architecture" aria-label="建议架构"><div class="layer"><b>比赛与规则</b><span>名单 · 队伍 · 当前/目标圈 · 胜负 · 生命周期</span></div><div class="arrow">↓</div><div class="layer"><b>感知与短期记忆</b><span>视野 / 射线 · 可听事件 · 最后看见位置 · 自身库存</span></div><div class="arrow">↓</div><div class="layer accent"><b>目标评分＋浅层行为树</b><span>进圈 · 找枪弹 · 找掩体 · 战斗 · 装填 · 小队跟随</span></div><div class="arrow">↓</div><div class="layer"><b>动作控制器</b><span>路径跟随 · 有限转速瞄准 · 反冲/误差 · 单次触发与状态等待</span></div><div class="arrow">↓</div><div class="split"><div>CarpetBodyAdapter<br><small>移动 / 视角 / 姿势</small></div><div>TaCZWeaponAdapter<br><small>draw / aim / shoot / reload / bolt</small></div><div>AddonLootAdapter<br><small>合法拾取 / 装备 / 有限库存</small></div></div></div>
<h3>决策：先简单、可解释</h3><p>用目标评分比较“进圈、补给、交战、掩体、跟队”，再用浅层行为树执行。评分考虑预计进圈时间、HP、备弹、敌人可信度、掩体和绕路成本；加入保持目标时间与抢占规则，减少来回抖动。无需首版就实现完整 GOAP。</p>
{code('''停止 / 淘汰 / 对局未运行 → 取消动作
蓝圈或入圈时限紧迫       → 优先进圈
空仓且可安全装填         → 掩体 → reload → 等待完成
需要拉栓                 → bolt → 等待完成
已感知敌人               → 找射击位置 → aim → 点射/短连射 → 换位
缺枪弹且补给可达         → 移动 → 收枪腾空主手 → 合法拾取 → 再 draw
其余                     → 跟队 / 巡逻 / 守位''')}
<h3>感知：代码可读世界，设计上仍要限制信息</h3><p>从本局敌对名单筛候选，经过距离、视野角与墙体射线后才形成“看见”。失去视线只保留带时间戳的最后位置；声音给模糊方向/区域。模型和战术层只拿这份受限黑板，避免隔墙定位、全图知道装备或提前读取尚未公布的未来圈。</p>
<h3>瞄准：难度主要由控制过程表达</h3><p>使用反应延迟、最大转速/加速度、相关而平滑的瞄准误差、目标遗忘、短连射长度和换位倾向。读取共用枪械反冲与附件修正形成服务器角度扰动，按难度提供不同补偿程度；保留 TaCZ 自己的姿态/ADS散布与真实弹丸碰撞，不直接改命中结果。</p>
<h3>可选语言模型：只接高层</h3><p>将来为语音队友增加“跟随某人、转移某处、共享弹药、掩护”等受限意图。异步推理只读不可变观察快照，返回后由服务器验证目标是否仍存在和动作是否可用；超时就沿用代码策略。不会让模型等待阻塞服务器 tick，也不让它直接执行任意命令或覆盖枪械计时。</p>
"""))
    sections.append(section("integration", "09　如何接入现有比赛与 config", f"""
<p>以下是<b>下一步实现建议</b>，当前 config 还没有这些人机设置。</p>
<table><thead><tr><th>现有部分</th><th>接入方式</th></tr></thead><tbody>
<tr><td>MatchConfigModel / MatchConfigSavedData</td><td>给每地图模板增加独立 BotSettings：启用、数量/补人数、难度档、队伍策略、生成点与装备策略；明确迁移及总人数上限。先不接模型字段。</td></tr>
<tr><td>开局</td><td>先预留名字与数量 → 异步创建 → 等实际玩家注册并核对所有权 → 生存模式/安全站位/装备/分队 → 再调用现有 start。超时回滚本次自有 bot，避免半完成开局。{local('A1')}</td></tr>
<tr><td>参赛筛选</td><td>当前会登记场地内所有合格 ServerPlayer。未来明确“真人＋本局受管 bot”策略，避免把其他 Carpet 工作假人拉入枪战。</td></tr>
<tr><td>队伍</td><td>使用已保存赛制和现有 /team；不要依靠 AMS 名称规则分队。先分队再冻结名单，计算真人与人机总数不超过 maxPlayers。</td></tr>
<tr><td>圈与战斗</td><td>读取已公开的本局 current/target circle；保留现有 blue damage、名单隔离和胜负。淘汰/结束停止脑、移动及新枪械触发。暂停另设协调策略并验证已排 BURST 的终止条件；原生 freeze 无法替代它。{local('A2')}{local('T7')}</td></tr>
<tr><td>搜集与配件</td><td>复用 GroundLootPickup / pickup 事务和当前弹药映射。弹匣/配件通过原生许可与装配路径处理，遵守实验开关；不伪造兼容 NBT。{local('A3')}{local('A4')}</td></tr>
<tr><td>死亡与结束</td><td>Carpet die/退出、Addon 淘汰和控制器清理只执行一次；复活或重新创建不自动获得本局资格。只移除本服务拥有的假人，结束后保留战斗统计。</td></tr>
<tr><td>模式恢复</td><td>沿用已有旁观恢复标记，处理死亡后 Carpet 异步退出；可选择直接退出本局 bot，避免长期留下无脑玩家。</td></tr>
</tbody></table>
<p>后续配置宜先暴露玩家能理解的项目：数量、单人/双人/四人队、难度、有限弹药、搜集开关、反应档、瞄准档和对局统计。导航预算、日志细节等放作者高级页，避免日常开局页面充满实现参数。</p>
"""))
    sections.append(section("roadmap", "10　实施路线与验收", """
<table><thead><tr><th>阶段</th><th>交付范围</th><th>通过标准</th></tr></thead><tbody>
<tr><td>P0：实际联动试验</td><td>一名受管假人、平地固定靶、有限枪弹；覆盖 draw/ADS/单发/AUTO/BURST/拉栓/空仓与战术换弹。</td><td>同时核 ShootResult、实际弹丸、目标伤害、消耗、库存及换弹时刻；真玩家观察枪声/动作；死亡、退出、结束与冻结单独核排队连发。SUCCESS 不单独代表命中或生成弹丸。</td></tr>
<tr><td>P1：小场地对抗</td><td>2–4 bot，有限感知、代码战斗树、角度反冲、短连射、简易掩体点。</td><td>不隔墙追踪、不无限备弹、不重复拉栓/换弹；当前比赛可正常淘汰、友伤与结算。</td></tr>
<tr><td>P2：搜集与缩圈</td><td>已加载区块内的导航、地面拾取、门/台阶、进圈与脱困。</td><td>能从无枪/少弹状态补给，选择可达路线；墙后物资与满背包正确失败；不靠瞬移脱困。</td></tr>
<tr><td>P3：对局配置与小队</td><td>每地图人数/补位/难度、管理生成点、组队跟随、共享目标与掩护装填。</td><td>先 bot 就绪再冻结名单；人数和队伍上限正确；跨局隔离、晚加入与死亡重建不越权。</td></tr>
<tr><td>P4：规模测试</td><td>先8–16，再按实际负载扩大到32/64；是否做到100由测量决定。</td><td>测全服 tick p50/p95/p99、寻路/射线/区块开销、卡住率、进圈成功率、弹药合规和比赛完成率。</td></tr>
<tr><td>P5：可选语言队友</td><td>语音/文字意图＋受限高层动作，模型与代码脑可分别关闭。</td><td>模型慢/断连不影响战斗 tick；指令可执行、有过期检测，有代码回退。</td></tr>
</tbody></table>
<h3>初始性能策略</h3><p>动作每 tick 执行；感知与战术按事件及错峰频率更新，路径按需重算。为射线、候选掩体与 A* 扩展设置全服预算；异步任务只读快照，世界修改回服务器线程。复用地图空间索引和路径段缓存，限制活动范围与区块加载。以上是待测设计，尚无“本机可跑多少人机”的性能结论。</p>
<h3>我会优先做哪一步</h3><p><b>先完成 P0 与 P1</b>：证明假人真实开火、消耗、换弹、承受反冲并进入比赛淘汰。这比先接一个聊天模型更能验证项目最难的底层联动。之后再让它会搜集、进圈和协作。</p>
"""))

    evidence_rows, evidence_index = [], []
    for key, title, relative, needle in LOCAL:
        path = ROOT / relative
        assert path.is_file(), path
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        line = next((i for i, text in enumerate(lines, 1) if needle in text), None)
        assert line is not None, (relative, needle)
        sha = hashlib.sha256(path.read_bytes()).hexdigest()
        evidence_index.append({"id": key, "title": title, "path": relative, "line": line, "sha256": sha})
        evidence_rows.append(f'<tr id="e-{key}"><td><b>{key}</b></td><td>{esc(title)}</td><td><a href="../../{esc(relative)}">{esc(relative)}:{line}</a></td></tr>')
    artifact_links = [
        ("Carpet/AMS 来源、字节码与安装一致性", "downloads/carpet/research-20261018/carpet-runtime/evidence.json"),
        ("TaCZ 每类源码/字节码 SHA 索引", "logs/carpet-tacz-bot-research/tacz-evidence-index.json"),
        ("TaCZ 调用链、状态机与验证标准", "logs/carpet-tacz-bot-research/tacz-research-findings.json"),
        ("编译探针与依赖 SHA 回执", "logs/carpet-tacz-bot-research/probe-compile-receipt.json"),
        ("FPS 一手来源及可支持的论断", "logs/carpet-tacz-bot-research/fps-ai-primary-evidence-20261018.json"),
        ("当前比赛用法", "docs/蓝圈对局命令.md"),
        ("Carpet 安装与原有命令指南", "docs/Carpet与TaCZ伪多人枪战研究.md"),
    ]
    link_list = "".join(f'<li><a href="../../{esc(path)}">{esc(label)}</a></li>' for label, path in artifact_links)
    binary_rows = "".join(f'<tr><td><code>{esc(path)}</code></td><td><code class="hash">{value}</code></td></tr>'
                          for path, value in baseline["artifacts_sha256"].items() if path.endswith(".jar"))
    sections.append(section("evidence", "11　证据与验证边界", f"""
<p>本次阅读了实际安装 JAR、锁定版本的源码与 javap 调用链，并编译隔离探针。<b>没有执行探针，没有生成假人，没有启动 Minecraft，没有改动生产模组代码或部署新的 Bot。</b>源码可达和类型兼容仍不能替代真实游戏中的 Mixin、事件、库存、声音、动画、死亡与性能验收。</p>
<ul>{link_list}</ul>
<div class="scroll"><table class="evidence"><thead><tr><th>ID</th><th>支撑内容</th><th>本地文件与定位行</th></tr></thead><tbody>{''.join(evidence_rows)}</tbody></table></div>
<details><summary>本机实际 JAR 的 SHA-256</summary><table><thead><tr><th>文件</th><th>SHA-256</th></tr></thead><tbody>{binary_rows}</tbody></table></details>
<p class="muted">本报告的“已核实”指源码、字节码和签名证据；“建议”指尚未实现的 PUBG Addon 设计。FPS 商业游戏未公开的内部机制与未测服务器容量保持未知。</p>
"""))

    toc = "".join(f'<a href="#{key}">{esc(title)}</a>' for key, title in [
        ("decision", "结论与可信度"), ("versions", "版本范围"), ("carpet", "Carpet 能力"),
        ("tacz", "TaCZ 接口"), ("pitfalls", "联动限制"), ("fps", "FPS：代码与模型"),
        ("navigation", "现成能力与导航"), ("design", "Addon 人机结构"),
        ("integration", "比赛与 config 接入"), ("roadmap", "路线与验收"), ("evidence", "源码证据")])
    style = """
:root{--ink:#192b3c;--muted:#5d6e7e;--line:#dce4ea;--paper:#fff;--bg:#eef2f5;--accent:#006d77;--blue:#235e98}
*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:22px}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.85 'Segoe UI','Microsoft YaHei',sans-serif}a{color:var(--blue);text-underline-offset:3px;overflow-wrap:anywhere}code{font:13px/1.65 Consolas,'Microsoft YaHei',monospace;overflow-wrap:anywhere;background:#edf2f6;border-radius:4px;padding:2px 5px}pre{overflow:auto;background:#152737;color:#e3edf6;border-radius:10px;padding:20px;line-height:1.8}pre code{background:none;color:inherit;padding:0;white-space:pre}
.layout{max-width:1550px;margin:auto;display:grid;grid-template-columns:220px minmax(0,1fr);gap:26px;padding:30px}.toc{position:sticky;top:28px;align-self:start;padding:18px 14px}.toc b{font-size:12px;letter-spacing:2px;color:var(--accent)}.toc a{display:block;font-size:13px;text-decoration:none;padding:7px 0;border-bottom:1px solid var(--line)}main{min-width:0}.hero{padding:42px 44px;background:#142c3d;color:white;border-radius:16px}.eyebrow{font-size:12px;letter-spacing:3px;color:#95c9d0}h1{font-size:clamp(27px,3vw,42px);line-height:1.35;margin:12px 0 18px;letter-spacing:-1px}.hero p{color:#c7d8e7;max-width:800px;margin:0}.chips{display:flex;flex-wrap:wrap;gap:8px;margin-top:24px}.chips span{font-size:12px;border:1px solid #517084;color:#d8e9ef;border-radius:40px;padding:3px 12px}section{background:var(--paper);padding:32px 40px;margin-top:22px;border-radius:14px;border:1px solid var(--line);scroll-margin-top:22px}h2{font-size:24px;line-height:1.4;margin:0 0 22px;padding-bottom:16px;border-bottom:2px solid var(--line)}h3{font-size:18px;margin:26px 0 10px}p{margin:14px 0}.lead{font-size:19px;line-height:1.9}.muted{color:var(--muted);font-size:13px}table{width:100%;border-collapse:collapse;font-size:14px;margin:20px 0;table-layout:auto}th,td{border-bottom:1px solid var(--line);padding:12px 14px;text-align:left;vertical-align:top}th{background:#edf3f7;color:#375169;font-size:13px}tbody tr:nth-child(even){background:#fbfcfd}td:first-child{min-width:90px}.good{color:#06724d;font-weight:600}.inference{color:#936512;font-weight:600}.pending{color:#925222;font-weight:600}.cards{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin:26px 0}.cards article{padding:20px;background:#f0f7f7;border:1px solid #c7e0df;border-radius:10px}.cards b{display:block;color:var(--accent);font-size:12px;letter-spacing:2px}.cards strong{display:block;font-size:17px;margin-top:6px}.cards p{font-size:14px;color:var(--muted);margin:8px 0 0}.callout{border-left:4px solid #ce9e43;background:#fff7e9;padding:14px 20px}.issues{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.issues article{border:1px solid var(--line);border-radius:9px;padding:18px}.issues h3{font-size:16px;margin:0 0 8px}.issues p{font-size:14px;margin:0}.architecture{margin:26px auto;max-width:760px;text-align:center}.layer{padding:16px 24px;background:#edf3f8;border:1px solid #c7d8e8;border-radius:9px}.layer b{display:block;font-size:17px}.layer span{font-size:13px;color:var(--muted)}.layer.accent{background:#e4f4f0;border-color:#aad4c9}.arrow{color:#4c8194;font-size:23px;line-height:1.5}.split{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:9px}.split div{background:#e5edf3;border:1px solid #bdcfde;padding:15px 6px;border-radius:8px;font-size:13px}.split small{color:var(--muted);font-size:12px}.eref{font-size:11px;white-space:nowrap;text-decoration:none;margin-left:4px}.scroll{overflow:auto}.evidence{font-size:12px}.evidence td:last-child{max-width:650px;overflow-wrap:anywhere}.hash{word-break:break-all;font-size:11px}details{background:#f5f8fa;padding:16px;border-radius:8px;margin-top:20px}summary{cursor:pointer;font-weight:600}footer{font-size:12px;color:var(--muted);text-align:center;padding:30px}li{margin:7px 0}
@media(max-width:1000px){.layout{grid-template-columns:minmax(0,1fr);padding:18px}.toc{position:static;display:flex;gap:8px;flex-wrap:wrap;padding:4px}.toc b{width:100%}.toc a{border:1px solid var(--line);border-radius:30px;padding:3px 10px}.hero{padding:30px}section{padding:26px}.cards{gap:9px}}@media(max-width:640px){body{font-size:15px}.layout{padding:10px}.hero{padding:26px 22px}section{padding:22px 18px}h2{font-size:21px}.cards,.issues{grid-template-columns:1fr}table{display:block;overflow:auto;max-width:100%}th,td{padding:9px;min-width:115px}.split{grid-template-columns:1fr}.evidence td:last-child{min-width:250px}}
@media print{body{background:white;font-size:10pt}.layout{display:block;padding:0}.toc{display:none}.hero{background:white;color:var(--ink);padding:0;border-radius:0}.hero p,.chips span{color:var(--muted)}section{border:0;border-top:1px solid var(--line);border-radius:0;padding:18px 0;margin:15px 0;break-inside:auto}h2,h3{break-after:avoid}.cards article,.issues article,tr{break-inside:avoid}.hero h1{font-size:26pt}a{color:var(--ink)}pre{white-space:pre-wrap;font-size:8pt}.issues{display:block}.issues article{margin:8px 0}footer{padding:10px}}
"""
    document = f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Carpet × TaCZ：PUBG Addon 人机可行性与方案</title><style>{style}</style></head><body><div class="layout"><nav class="toc" aria-label="报告目录"><b>研究目录</b>{toc}</nav><main><header class="hero"><div class="eyebrow">PUBG ADDON · SOURCE REVIEW</div><h1>Carpet × TaCZ<br>PUBG Addon 人机可行性与方案</h1><p>从实际安装版本的代码出发，区分假人身体、枪械动作与 FPS 决策，给出可验证的接入路线。</p><div class="chips"><span>2026-10-18</span><span>MC 1.21.1 / NeoForge</span><span>r7＋Carpet 1.0.8</span><span>源码 / 字节码 / 编译证据</span></div></header>{''.join(sections)}<footer>本次为研究交付 · 未启动游戏或部署人机控制器 · 原有安装与生产代码保持</footer></main></div></body></html>'''
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(document, encoding="utf-8")
    data = {"as_of": "2026-10-18", "report": OUT.relative_to(ROOT).as_posix(),
            "report_sha256": hashlib.sha256(OUT.read_bytes()).hexdigest(), "game_launched": False,
            "bot_implemented": False, "signature_probe_compiled": True, "signature_probe_executed": False,
            "recommendation": "Carpet player body + native TaCZ server weapon adapter + code perception/navigation/tactics; optional later language-model cooperation",
            "local_evidence": evidence_index, "sources": {key: {"title": title, "url": url} for key, (title, url) in SOURCES.items()}}
    (EVIDENCE / "report-index.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"report": data["report"], "sections": len(sections), "local_evidence": len(evidence_index), "external_sources": len(SOURCES), "signature_probe_compiled": True, "game_launched": False}, ensure_ascii=False))

if __name__ == "__main__":
    main()
