"""Build the local, version-pinned NeoForge documentation anthology.

Run from any directory: python scripts/build-tutorial.py
Only the downloaded MIT-licensed documentation is merged. Fenced code is kept
unchanged; Docusaurus UI markup and relative documentation links are adapted.
"""

from __future__ import annotations

import hashlib
import json
import posixpath
import re
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

WORKSPACE = Path(__file__).resolve().parents[1]
UPSTREAM = WORKSPACE / "downloads/docs-extracted/Documentation-main"
SOURCE = UPSTREAM / "versioned_docs/version-1.21.1"
OUTPUT = WORKSPACE / "docs/NeoForge-1.21.1-开发教程全集.md"
COMMIT = "7bc4d2f6a4a84df7d9106fec3ba8d11fa49bf060"
BASE = "https://docs.neoforged.net/docs/1.21.1/"
REFERENCE_REPAIRS = {
    # Upstream sounds.md uses [block entity][be] but omits its definition.
    "resources/client/sounds.md": {"be": "../../blockentities/index.md"},
}

# Explicit pedagogical order; the final assertion checks all upstream pages.
CHAPTERS = [
    ("gettingstarted/index.md", "开始开发", "JDK、IDE、MDK、源码、构建与运行"),
    ("gettingstarted/modfiles.md", "模组文件", "gradle.properties、neoforge.mods.toml、@Mod"),
    ("gettingstarted/structuring.md", "代码组织", "包名、类名、客户端代码隔离"),
    ("gettingstarted/versioning.md", "版本号", "Minecraft、NeoForge、模组与 Maven 版本"),
    ("misc/resourcelocation.md", "资源标识符", "命名空间、路径、ResourceLocation"),
    ("concepts/registries.md", "注册系统", "DeferredRegister、Holder、动态注册表"),
    ("concepts/sides.md", "客户端与服务端", "物理端、逻辑端与代码边界"),
    ("concepts/events.md", "事件", "总线、监听器、生命周期与线程"),
    ("items/index.md", "物品", "注册、ItemStack、属性与创造模式标签页"),
    ("items/interactionpipeline.md", "交互处理流程", "左右键、物品与方块交互回调顺序"),
    ("items/datacomponents.md", "物品数据组件", "DataComponentType、持久化与同步"),
    ("items/tools.md", "工具与盔甲", "工具等级、挖掘标签、属性与材料"),
    ("items/mobeffects.md", "状态效果与药水", "MobEffect、Potion、酿造与效果实例"),
    ("blocks/index.md", "方块", "方块和方块物品、属性、行为与渲染"),
    ("blocks/states.md", "方块状态", "Property、状态定义与不可变状态"),
    ("resources/index.md", "资源与数据生成", "assets/data、资源包、数据包、DataProvider"),
    ("resources/client/textures.md", "纹理", "纹理路径、图集、动画与渲染用纹理"),
    ("resources/client/i18n.md", "国际化与本地化", "语言文件、翻译键与 Component"),
    ("resources/client/models/index.md", "模型", "模型 JSON、父模型与方块状态 JSON"),
    ("resources/client/models/datagen.md", "模型数据生成", "ItemModelProvider、BlockStateProvider"),
    ("resources/server/tags.md", "标签", "物品/方块标签、可选条目与数据生成"),
    ("resources/server/recipes/index.md", "配方", "配方管理、数据生成与自定义配方"),
    ("resources/server/recipes/builtin.md", "内置配方类型", "合成、烧炼、锻造、切石等类型"),
    ("resources/server/recipes/ingredients.md", "配方原料", "Ingredient 与 NeoForge 扩展原料"),
    ("resources/server/loottables/index.md", "战利品表", "条目、池、上下文、掉落与数据生成"),
    ("resources/server/loottables/lootconditions.md", "战利品条件", "原版和 NeoForge 内置条件参考"),
    ("resources/server/loottables/lootfunctions.md", "战利品函数", "物品数量、组件、附魔等变换"),
    ("resources/server/loottables/glm.md", "全局战利品修饰器", "在保留兼容性的前提下修改掉落"),
    ("resources/server/loottables/custom.md", "自定义战利品对象", "自定义条件、函数、条目与序列化"),
    ("datastorage/nbt.md", "NBT", "标签类型、读取与写入"),
    ("datastorage/codecs.md", "Codec", "结构化编解码、DataResult、RecordCodecBuilder"),
    ("datastorage/attachments.md", "数据附件", "实体、区块、方块实体的附加数据"),
    ("datastorage/saveddata.md", "世界保存数据", "SavedData、维度存储与脏标记"),
    ("blockentities/index.md", "方块实体", "额外状态、存盘、tick 与同步"),
    ("inventories/container.md", "物品容器", "Container 与原版容器实现参考"),
    ("inventories/capabilities.md", "能力系统", "物品、流体、能量接口与能力查询"),
    ("gui/menus.md", "菜单数据逻辑", "槽位、菜单类型、同步与快捷移动"),
    ("gui/screens.md", "界面渲染", "Screen、控件、输入与 GUI 绘制"),
    ("networking/index.md", "网络概览", "客户端与服务端为何需要通信"),
    ("networking/payload.md", "自定义载荷", "CustomPacketPayload、注册、发送与处理"),
    ("networking/streamcodecs.md", "网络流编解码", "StreamCodec 与 ByteBufCodecs"),
    ("networking/configuration-tasks.md", "连接配置任务", "进入游戏前的配置阶段同步"),
    ("networking/entities.md", "实体同步", "实体生成数据与自定义同步"),
    ("misc/config.md", "模组配置", "ModConfigSpec、配置类型与配置事件"),
    ("misc/keymappings.md", "按键映射", "客户端按键、冲突上下文与处理"),
    ("resources/client/sounds.md", "声音", "声音资源、SoundEvent 与播放"),
    ("resources/client/particles.md", "粒子", "粒子类型、客户端提供器与生成"),
    ("blockentities/ber.md", "方块实体渲染器", "BER、注册与动态渲染"),
    ("resources/client/models/bakedmodel.md", "烘焙模型", "BakedModel 与渲染模型数据"),
    ("resources/client/models/modelloaders.md", "自定义模型加载器", "模型几何、加载流程与扩展加载器"),
    ("resources/server/conditions.md", "数据加载条件", "条件化加载配方、战利品等数据"),
    ("resources/server/advancements.md", "进度", "进度、触发器、奖励与数据生成"),
    ("resources/server/damagetypes.md", "伤害类型与来源", "数据驱动 DamageType、DamageSource"),
    ("resources/server/datamaps/index.md", "数据映射", "为注册表条目附加数据与重载"),
    ("resources/server/datamaps/builtin.md", "内置数据映射", "燃料、堆肥等 NeoForge 内置映射"),
    ("resources/server/enchantments/index.md", "附魔", "1.21 数据驱动附魔与效果组件"),
    ("resources/server/enchantments/builtin.md", "内置附魔效果组件", "内置效果类型、JSON 与数据生成参考"),
    ("worldgen/biomemodifier.md", "生物群系修饰器", "群系特征、生成、刷怪与修改顺序"),
    ("misc/gametest.md", "游戏测试", "GameTest、测试结构与自动化验证"),
    ("misc/debugprofiler.md", "性能分析器", "Profiler 标记与调试分析"),
    ("misc/updatechecker.md", "更新检查", "更新 JSON 与版本公告"),
    ("advanced/accesstransformers.md", "访问变换器", "访问级别、final 与 JVM 方法描述符"),
    ("advanced/extensibleenums.md", "可扩展枚举", "枚举扩展声明、参数与兼容限制"),
]


def page_url(relative: str) -> str:
    route = re.sub(r"\.mdx?$", "", relative)
    if route == "index":
        route = ""
    elif route.endswith("/index"):
        route = route[:-5]
    return BASE + route.rstrip("/") + ("/" if route else "")


def absolute_url(value: str, relative: str) -> str:
    """Pin documentation links to 1.21.1, retaining query strings and anchors."""
    value = value.strip()
    wrapped = value.startswith("<") and value.endswith(">")
    if wrapped:
        value = value[1:-1]
    parts = urlsplit(value)
    if parts.netloc == "docs.neoforged.net" and parts.path.startswith("/docs/"):
        route = parts.path[len("/docs/"):]
        if not re.match(r"(?:\d+\.\d+|\d{2}\.\d+)(?:/|$)", route):
            return urlunsplit(("https", parts.netloc, "/docs/1.21.1/" + route, parts.query, parts.fragment))
    if parts.scheme or value.startswith("//"):
        return value
    if value.startswith("#"):
        return page_url(relative) + value
    path = parts.path
    if path.startswith("/docs/"):
        route = path[len("/docs/"):]
        if not re.match(r"(?:\d+\.\d+|\d{2}\.\d+)(?:/|$)", route):
            path = "/docs/1.21.1/" + route
        return urlunsplit(("https", "docs.neoforged.net", path, parts.query, parts.fragment))
    if path.startswith("/"):
        return urlunsplit(("https", "docs.neoforged.net", path, parts.query, parts.fragment))
    normalized = posixpath.normpath(posixpath.join(posixpath.dirname(relative), path))
    if "/static/" in "/" + normalized:
        static_path = ("/" + normalized).split("/static/", 1)[1]
        return urlunsplit(("https", "docs.neoforged.net", "/" + static_path, parts.query, parts.fragment))
    if re.search(r"\.(?:png|jpe?g|gif|svg|webp|avif|pdf)$", normalized, re.I):
        url = BASE + normalized
        return urlunsplit((*urlsplit(url)[:3], parts.query, parts.fragment))
    url = page_url(normalized)
    return urlunsplit((*urlsplit(url)[:3], parts.query, parts.fragment))


def strip_frontmatter(text: str) -> str:
    return re.sub(r"\A---\s*\n.*?\n---\s*\n", "", text, count=1, flags=re.S)


def adapt_page(text: str, relative: str, number: int) -> tuple[str, dict]:
    """Normalize MDX without changing fenced example code or deleting prose."""
    text = strip_frontmatter(text)
    for reference, target in REFERENCE_REPAIRS.get(relative, {}).items():
        text += f"\n[{reference}]: {target}\n"
    references = {}
    in_fence = False
    fence = ""
    for line in text.splitlines():
        fm = re.match(r"^\s*(`{3,}|~{3,})", line)
        if fm:
            if not in_fence:
                in_fence, fence = True, fm[1][0]
            elif fm[1][0] == fence:
                in_fence = False
            continue
        if not in_fence:
            rm = re.match(r"^\[([^\]]+)\]:\s*(\S+)(.*)$", line)
            if rm:
                references[rm[1].lower()] = f"chapter-{number:02d}-{rm[1]}"

    def adapt_prose(prose: str) -> str:
        def explicit_reference(match: re.Match) -> str:
            label, ref = match[1], match[2]
            key = (ref or label.lstrip("!")[1:-1]).lower()
            if key in references:
                return f"{label}[{references[key]}]"
            return match[0]

        prose = re.sub(r"(!?\[[^\]\n]+\])\[([^\]\n]*)\]", explicit_reference, prose)
        prose = re.sub(
            r"(!?\[[^\]\n]+\])\((<[^>]+>|[^\s)]+)([^)]*)\)",
            lambda m: f"{m[1]}({absolute_url(m[2], relative)}{m[3]})",
            prose,
        )
        # Preserve shortcut references such as `[registered]`, but isolate their
        # definitions from identically named definitions in other chapters.
        prose = re.sub(
            r"(?<!!)\[([^\]\n]+)\](?![\[(])",
            lambda m: f"[{m[1]}][{references[m[1].lower()]}]" if m[1].lower() in references else m[0],
            prose,
        )
        return prose

    result = []
    in_fence = False
    quote_depth = 0
    removed_title = False
    removed_imports = 0
    input_fences = []
    current_code = []
    for line in text.splitlines():
        fm = re.match(r"^\s*(`{3,}|~{3,})", line)
        if fm:
            if not in_fence:
                in_fence, fence = True, fm[1][0]
                current_code = [line]
            elif fm[1][0] == fence:
                in_fence = False
                current_code.append(line)
                input_fences.append("\n".join(current_code))
                current_code = []
            else:
                current_code.append(line)
            result.append("> " * quote_depth + line)
            continue
        if in_fence:
            current_code.append(line)
            result.append("> " * quote_depth + line)
            continue
        if re.match(r"^import (?:Tabs|TabItem) from ", line):
            removed_imports += 1
            continue
        if re.match(r"^</?Tabs(?:\s|>)", line) or line.strip() == "</TabItem>":
            continue
        tm = re.match(r'^<TabItem\b.*?label="([^"]+)"', line)
        if tm:
            result.extend(["", f"**原文选项卡：{tm[1]}**", ""])
            continue
        if line.strip() == "<details>" or line.strip() == "</details>":
            continue
        sm = re.match(r"^<summary>(.*?)</summary>$", line)
        if sm:
            result.extend(["", f"**展开说明：{sm[1]}**", ""])
            continue
        am = re.match(r"^:::(\w+)(?:\s+(.*))?$", line)
        if am:
            quote_depth += 1
            label = am[1].upper() + (f" — {am[2]}" if am[2] else "")
            result.extend(["> " * quote_depth + f"**{label}**", "> " * quote_depth])
            continue
        if line.strip() == ":::" and quote_depth:
            quote_depth -= 1
            result.append("> " * quote_depth)
            continue
        rm = re.match(r"^\[([^\]]+)\]:\s*(\S+)(.*)$", line)
        if rm:
            line = f"[{references[rm[1].lower()]}]: {absolute_url(rm[2], relative)}{rm[3]}"
        else:
            hm = re.match(r"^(#{1,6})\s+(.*)$", line)
            if hm:
                if len(hm[1]) == 1 and not removed_title:
                    removed_title = True
                    continue
                line = "#" * min(6, len(hm[1]) + 1) + " " + hm[2]
            # A Markdown link label can itself contain inline code, so process
            # the complete line rather than splitting its label at backticks.
            line = adapt_prose(line)
        result.append("> " * quote_depth + line)
    assert not in_fence, f"Unclosed code fence: {relative}"
    assert quote_depth == 0, f"Unclosed admonition: {relative}"
    return "\n".join(result).strip() + "\n", {"code_blocks": len(input_fences), "removed_mdx_imports": removed_imports}


INTRO = r'''# NeoForge 1.21.1 开发教程全集与实践路线

本文件把 **Minecraft 1.21.1 对应的 NeoForge 官方开发文档全部 63 篇**合并到一个可离线阅读、可全文搜索的 Markdown 文档。前半部分是中文学习路线、版本提醒和实操任务；后半部分保留官方英文完整正文及代码示例。它覆盖该版本文档目录的全部页面，不宣称覆盖 Minecraft 的全部功能或取代源码。

- 快照日期：**2026-10-17**；官方页面版本标签：**1.21 – 1.21.1**。
- 上游仓库：[NeoForged/Documentation](https://github.com/NeoForged/Documentation)。固定提交：[`7bc4d2f6a4a84df7d9106fec3ba8d11fa49bf060`](https://github.com/NeoForged/Documentation/tree/7bc4d2f6a4a84df7d9106fec3ba8d11fa49bf060)。
- 收录范围：上游 `versioned_docs/version-1.21.1/**/*.md`，共 **63 篇**；每章给出原始路径、该提交的源码链接和在线版本页面。
- 官方入口：[1.21.1 开发文档](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)。动态网页以后可能更新；本文件的正文固定在上述提交。
- 配套实践项目：工作区 `neoforge-1.21.1`，保留官方 `examplemod` 示例；固定 Minecraft **1.21.1**、NeoForge **21.1.259**、ModDevGradle **2.0.148**、Gradle **9.2.1**、JDK **21**、Parchment **2024.11.17**。
- 处理说明：去除仅供网站使用的 frontmatter 和 Tabs 导入；选项卡的**所有分支均保留**并显示标签；提示框转为引用；各章引用链接加独立前缀，防止合并后覆盖；相对链接改成版本固定的官方网址；补上声音一章原文漏写的 `[be]` 方块实体链接定义。代码块内部保持原文。正文原始文件仍完整保存在下载目录。
- 授权：上游文档使用 **MIT**，完整版权及许可证附在文件末尾。中文导读和练习为本工作区补充说明。文档许可证不替代 Minecraft 或其映射的许可证。

## 如何学习这份文档

先完成一个能构建、能启动、能在专用服务器加载的小模组，再扩展功能。不要一开始顺读全部参考章节；遇到 API 的职责不清楚时回到概念页，再用 IDE 跳到原版类查看实际调用链。

需要的 Java 基础是类与接口、继承、多态、泛型、Lambda、方法引用、record 和集合；同时应能读懂 Gradle 的依赖、配置项和任务。官方文档是模组 API 文档，不是 Java 入门教程。

| 阶段 | 先读哪些主题 | 做出什么成果 |
| --- | --- | --- |
| 1. 运行骨架 | 01–08：环境、元信息、代码结构、标识符、注册、端、事件 | 原始 examplemod 构建成功，客户端加载，找到原版源码 |
| 2. 第一件物品和方块 | 09–20：物品、交互、数据组件、方块、状态、资源、模型 | 做出带中英文名称、模型、贴图的物品与方块 |
| 3. 完整生存获取链 | 21–29：标签、配方、掉落与 GLM | 方块可合成、能正确挖掘并掉落，生成对应 JSON |
| 4. 保存状态的机器 | 30–38：NBT、Codec、附件、SavedData、方块实体、能力、菜单 | 一台带库存、进度和界面的简单机器，重进世界不丢数据 |
| 5. 多人和客户端功能 | 39–50：Payload、StreamCodec、配置、按键、音效、粒子、渲染 | 按键请求由服务端验证执行，另一客户端能看到结果 |
| 6. 数据驱动和质量 | 51–63：加载条件、进度、伤害、数据映射、附魔、群系、测试、分析 | 可重载的数据功能、专用服务器验证与可复现测试 |

入门路线建议先按 **01 → 02 → 05 → 06 → 07 → 08 → 09 → 14 → 16 → 18 → 19 → 21 → 22 → 25** 阅读。数据组件的复杂自定义编码、GUI、网络和高级渲染可在第一个物品/方块闭环完成后再深入。

## 实践项目的阅读入口

用 IntelliJ IDEA 打开 `neoforge-1.21.1` 目录，等待 Gradle 导入。构建完成后，原版与 NeoForge 源码出现在 **External Libraries**；ModDevGradle 的原版依赖通常显示为 `minecraft-patched-<NeoForge版本>-merged.jar`。这是一套经过映射、反编译和 NeoForge 补丁处理的开发源码视图，适合跳转阅读并在模组中调用。

先查看 `src/main/java/com/example/examplemod/ExampleMod.java`、`Config.java`、`gradle.properties` 和 `src/main/templates/META-INF/neoforge.mods.toml`。如果模板的文件名或路径更新，以实际项目文件为准。不要直接修改 Gradle 缓存里的反编译类；自己的功能写入 `src/main/java` 和 `src/main/resources`。

在项目目录可执行：

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runData
.\gradlew.bat runServer
```

`build` 的模组 JAR 位于 `build/libs`；`runClient` 启动开发客户端；`runData` 执行已注册的数据生成器（仅运行任务不会自动为任意新对象生成资源）；`runServer` 用于检查专用服务器加载与逻辑。优先使用工作区提供的启动脚本，它会选用本项目准备好的 JDK 和缓存设置。Minecraft EULA 已授权部署的处理以工作区部署记录为准。

## 锁定 1.21.1 时最容易踩的坑

1. **Minecraft 版本与 NeoForge 版本是两套编号。** 1.21.1 使用 NeoForge `21.1.x`。教程网页有时默认跳到最新版，示例仓库链接有时写 1.21；操作前确认网址中的 `/docs/1.21.1/` 和项目的 `minecraft_version=1.21.1`。本项目使用 1.21.1 的 MDK。[版本说明](https://docs.neoforged.net/docs/1.21.1/gettingstarted/versioning/)
2. **JDK 必须是 64 位 Java 21。** IDE 的 Gradle JVM、终端 `JAVA_HOME` 和 Minecraft 开发运行配置应一致；“电脑装过 Java”不意味着 Gradle 正在用正确版本。[环境要求](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
3. **同一 Minecraft 版本的 NeoForge 小版本也会更新 API。** 本文 Events 的 `[21.1.0,21.1.180]` 是旧分支，当前 `21.1.259` 应读 `Latest`：`@EventBusSubscriber` 自动选择正确总线；不要机械照抄旧例子的 `bus = Bus.MOD`。Data Components 的 `[21.0.0,21.1.48]` 也是旧分支；当前示例使用带注册表参数的 `DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID)`。本文保留旧分支仅供理解演进。[事件](https://docs.neoforged.net/docs/1.21.1/concepts/events/)、[数据组件](https://docs.neoforged.net/docs/1.21.1/items/datacomponents/)
4. **注册对象通常是单例，不是某个坐标或某个物品堆。** 不要把单个玩家/方块的位置数据存在 `Item` 或 `Block` 的实例字段里。物品堆数据放 `ItemStack` 的组件，少量离散方块状态放 `BlockState`，复杂方块数据放 `BlockEntity`。[注册表](https://docs.neoforged.net/docs/1.21.1/concepts/registries/)
5. **延迟注册要连接到 mod 事件总线。** 只声明 `DeferredRegister` 不够，须在模组构造器里调用 `REGISTER.register(modBus)`；不要在注册完成前随意调用 `DeferredHolder#get()`。[注册表](https://docs.neoforged.net/docs/1.21.1/concepts/registries/)
6. **区分物理客户端与逻辑服务端。** 单人游戏也有逻辑服务端；修改世界、扣物品、造成伤害等以服务端为准。通用类引用 `net.minecraft.client` 可能导致专用服务器崩溃，客户端渲染与按键代码单独隔离。[端的概念](https://docs.neoforged.net/docs/1.21.1/concepts/sides/)
7. **1.21.1 的物品堆数据以 Data Components 为核心。** 旧教程围绕 `ItemStack#getOrCreateTag()` 的写法不能直接照搬。自定义组件值应有正确的 `equals/hashCode` 并按不可变对象使用；明确区分磁盘 `Codec` 和网络 `StreamCodec`。[数据组件](https://docs.neoforged.net/docs/1.21.1/items/datacomponents/)
8. **1.21 的数据目录发生了单数化。** 此版本使用 `data/<modid>/recipe/`、`loot_table/`、`advancement/`、`tags/item/`、`tags/block/` 等路径；不要沿用旧版本的 `recipes`、`loot_tables`、`tags/items`。模型和贴图仍在 `assets` 下，应按本版正文例子生成路径。[资源](https://docs.neoforged.net/docs/1.21.1/resources/)
9. **模型、语言、配方、掉落都要有一致的命名空间。** `ResourceLocation.fromNamespaceAndPath(MOD_ID, "name")` 是当前常用构造方式；注册了 `Block` 不会自动注册其 `BlockItem`，也不会自动产生可见模型和掉落 JSON。[标识符](https://docs.neoforged.net/docs/1.21.1/misc/resourcelocation/)、[方块](https://docs.neoforged.net/docs/1.21.1/blocks/)
10. **工具能否挖掘与实际掉落是不同问题。** 正确设置方块标签、工具等级与掉落表。`BlockState` 不可变，`setValue` 返回新状态，修改后还要写回世界。[工具](https://docs.neoforged.net/docs/1.21.1/items/tools/)、[方块状态](https://docs.neoforged.net/docs/1.21.1/blocks/states/)
11. **1.21 网络教程看 Payload/StreamCodec。** 本版使用 `RegisterPayloadHandlersEvent`、`CustomPacketPayload` 和 `PayloadRegistrar`。处理器默认在主线程执行；切到网络线程后，读写世界等工作需要按官方说明调度回合适线程。来自客户端的请求仍需服务端验证。[载荷](https://docs.neoforged.net/docs/1.21.1/networking/payload/)
12. **1.21 附魔采用数据驱动结构。** 需要理解数据包注册表、效果组件与 Codec；不要把更早版本仅继承 `Enchantment` 的教程当作可直接编译的方案。[附魔](https://docs.neoforged.net/docs/1.21.1/resources/server/enchantments/)
13. **Container、能力和数据附件各有职责。** NeoForge 推荐可行时用 `ItemStackHandler` 管理物品存储，通过能力暴露物品/能量/流体交互；数据附件用于附加数据，不等同于所有旧 Forge capability 教程的用法。[容器](https://docs.neoforged.net/docs/1.21.1/inventories/container/)、[能力](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/)、[附件](https://docs.neoforged.net/docs/1.21.1/datastorage/attachments/)
14. **Parchment 改善参数名和注释，不改变 Minecraft 版本。** 阅读时以本工程实际源码签名为准；网络片段中的参数名不同，未必意味着 API 不同。跨版本移植时再对照 [1.21 → 1.21.1 官方迁移说明](https://docs.neoforged.net/primer/docs/1.21.1/)。

## 六个可以逐步完成的实操任务

每完成一项，先构建，再在开发客户端检查行为；涉及世界状态或网络时，再跑专用服务器。建议在每次可运行的成果上做一次 Git 提交，便于回退和比较。

1. **观察官方 examplemod。** 不改功能先启动；找到日志、创造模式示例物品与方块；用 IDE 跳到 `ItemStack`、`Block`、`Blocks`、`MinecraftServer`。验收：能说明模组构造器、注册、资源加载分别发生在哪里。
2. **增加练习物品和方块。** 在 `examplemod` 下增加 `practice_ingot` 和 `practice_block`，保留原示例；写 `en_us.json`/`zh_cn.json`、模型和贴图。验收：`/give @s examplemod:practice_ingot` 能拿到物品，方块放置和显示正确，日志无缺失资源警告。
3. **补全生存闭环。** 让九个练习锭合成一个方块并可拆回；增加挖掘工具标签与方块掉落表，用数据生成器产生 JSON。验收：生存模式按预期挖掘和掉落；`runData` 后 `build` 仍成功，生成文件位于配置的 generated resources 目录。
4. **实现有状态的机器。** 做一个一输入、一输出槽位的简化加工方块；用 `BlockEntity` 保存库存/进度，用 `ItemStackHandler` 暴露物品能力，正确设置脏标记；先完成不带 GUI 的逻辑，再加菜单。验收：重进世界库存保留，漏斗交互正常，不发生客户端重复加工。
5. **增加一个服务端权威的按键动作。** 客户端注册按键，发送精简 Payload；服务端检查玩家、持有物、冷却或权限后执行动作，再通知客户端显示结果。验收：另一客户端看到同一结果，快速重复按键不会复制物品或越过冷却，专用服务器不加载客户端类。
6. **为功能建立回归检查。** 为机器或配方写 GameTest，保存测试结构；用 `runGameTestServer`（以工程实际任务列表为准）运行，再观察 profiler 找到不必要的每 tick 工作。验收：测试可重复运行，失败能指出具体条件；记录一次构建结果与专用服务器验证结果。

## 完整主题索引（63 篇）

表中的“正文”可在本文件内跳转；“在线”固定为 1.21.1 文档路径。官方正文的章节顺序已按上述学习路线排列。

| 编号 | 中文主题 | 主要内容 | 链接 |
| --- | --- | --- | --- |
'''


def main() -> None:
    actual = {p.relative_to(SOURCE).as_posix() for p in SOURCE.rglob("*.md")}
    listed = {chapter[0] for chapter in CHAPTERS}
    assert len(CHAPTERS) == len(listed) == 63
    assert listed == actual, f"Chapter mismatch: missing={actual-listed}; extra={listed-actual}"
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    sections = [INTRO]
    manifest = []
    chapter_bodies = []
    for number, (relative, chinese, summary) in enumerate(CHAPTERS, 1):
        source_file = SOURCE / relative
        raw_bytes = source_file.read_bytes()
        original = raw_bytes.decode("utf-8")
        title = re.search(r"^# (.+)$", original, re.M)[1]
        online = page_url(relative)
        source_path = "versioned_docs/version-1.21.1/" + relative
        source_url = f"https://github.com/NeoForged/Documentation/blob/{COMMIT}/{source_path}"
        adapted, statistics = adapt_page(original, relative, number)
        sections.append(f"| {number:02d} | {chinese} | {summary} | [正文](#official-{number:02d}) · [在线]({online}) |\n")
        chapter_bodies.append(
            f"\n---\n\n<a id=\"official-{number:02d}\"></a>\n\n"
            f"## 官方正文 {number:02d}｜{chinese} — {title}\n\n"
            f"原始路径：`{source_path}`  \n"
            f"来源：[固定提交源码]({source_url}) · [1.21.1 在线页面]({online})\n\n"
            + adapted
        )
        manifest.append({
            "number": number,
            "title": title,
            "chinese_title": chinese,
            "source_path": source_path,
            "online_url": online,
            "source_url": source_url,
            "source_bytes": len(raw_bytes),
            "source_sha256": hashlib.sha256(raw_bytes).hexdigest(),
            "reference_repairs": REFERENCE_REPAIRS.get(relative, {}),
            **statistics,
        })
    sections.append("\n## 官方完整正文\n\n以下收录所有 63 篇原文。`Latest` 指文档的该 Minecraft 版本下较新 NeoForge 小版本分支，不是切换到新版 Minecraft；当前工程 21.1.259 优先使用 Latest。\n")
    sections.extend(chapter_bodies)
    license_text = (UPSTREAM / "LICENSE").read_text(encoding="utf-8").strip()
    sections.append(
        "\n---\n\n## 上游版权与 MIT 许可证\n\n"
        f"以下许可证来自 [NeoForged/Documentation 的 LICENSE](https://github.com/NeoForged/Documentation/blob/{COMMIT}/LICENSE)，原样保留。\n\n"
        "```text\n" + license_text + "\n```\n"
    )
    combined = "".join(sections)
    OUTPUT.write_text(combined, encoding="utf-8", newline="\n")
    manifest_path = OUTPUT.parent / "NeoForge-1.21.1-教程来源清单.json"
    manifest_path.write_text(json.dumps({
        "snapshot_date": "2026-10-17",
        "upstream_commit": COMMIT,
        "license": "MIT",
        "chapter_count": len(manifest),
        "source_bytes": sum(item["source_bytes"] for item in manifest),
        "code_blocks": sum(item["code_blocks"] for item in manifest),
        "output_bytes": OUTPUT.stat().st_size,
        "output_sha256": hashlib.sha256(OUTPUT.read_bytes()).hexdigest(),
        "chapters": manifest,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({
        "document": str(OUTPUT),
        "chapter_count": len(manifest),
        "source_bytes": sum(item["source_bytes"] for item in manifest),
        "preserved_code_blocks": sum(item["code_blocks"] for item in manifest),
        "output_bytes": OUTPUT.stat().st_size,
        "output_lines": len(combined.splitlines()),
        "manifest": str(manifest_path),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
