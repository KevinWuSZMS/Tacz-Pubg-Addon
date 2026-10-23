"""Merge the downloaded, commit-pinned official TaCZ Chinese Wiki for offline reading.

Only the anthology is written. The downloaded upstream archive is left unchanged.
Run with Python 3 from any directory; the script has no third-party dependencies.
"""

from __future__ import annotations

import hashlib
import posixpath
import re
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "5e69a3f25f93acd10c481b69c19ca7578d49d08c"
UPSTREAM = ROOT / "docs" / "TaCZ官方Wiki源码" / f"tacwiki-{COMMIT}"
DOCS = UPSTREAM / "docs"
CHINESE = DOCS / "zh"
PUBLIC = DOCS / ".vuepress" / "public"
OUTPUT = ROOT / "docs" / "TaCZ-官方附属制作教程.md"
REPO_URL = "https://github.com/MCModderAnchor/tacwiki"
OFFICIAL_SOURCE_COMMIT = "b482eff8c94a733ac8d0910193fca3893954027c"
PORT_SOURCE_COMMIT = "c471821c771a62cc8850694bbea579c2ba0b9e3a"


def split_frontmatter(text: str) -> tuple[str, str]:
    match = re.match(r"\A---\n(.*?)\n---(?:\n|$)", text, re.S)
    return (match.group(1), text[match.end():]) if match else ("", text)


def natural_key(text: str) -> tuple:
    return tuple((0, int(p)) if p.isdigit() else (1, p.casefold())
                 for p in re.split(r"(\d+)", text))


def article_order(path: Path) -> tuple:
    metadata_path = path / "README.md" if path.is_dir() else path
    metadata, _ = split_frontmatter(metadata_path.read_text("utf-8-sig")) if metadata_path.exists() else ("", "")
    if path.is_dir():
        match = re.search(r"(?m)^dir:\s*\n(?:[ \t]+[^\n]*\n)*?[ \t]+order:\s*(-?\d+)", metadata)
    else:
        match = re.search(r"(?m)^order:\s*(-?\d+)", metadata)
    order = int(match.group(1)) if match else 0
    # VuePress places explicitly positive orders first and negative orders last.
    weight = (0, order) if order > 0 else (2, order) if order < 0 else (1, 0)
    return (*weight, natural_key(path.name))


def walk_section(directory: Path):
    index = directory / "README.md"
    if index.exists():
        yield index
    entries = [path for path in directory.iterdir()
               if path.is_dir() or (path.suffix == ".md" and path.name != "README.md")]
    for path in sorted(entries, key=article_order):
        if path.is_dir():
            yield from walk_section(path)
        else:
            yield path


def local_link(path: Path) -> str:
    return path.relative_to(OUTPUT.parent).as_posix()


def title_of(path: Path) -> str:
    _, body = split_frontmatter(path.read_text("utf-8-sig"))
    match = re.search(r"(?m)^#\s+(.+?)\s*$", body)
    return match.group(1).strip() if match else path.stem


def page_url(path: Path) -> str:
    relative = path.relative_to(DOCS).as_posix()
    if relative.endswith("README.md"):
        relative = relative[:-9]
    else:
        relative = relative[:-3] + ".html"
    return "https://tacwiki.mcma.club/" + relative


pages = [CHINESE / "README.md"]
# The official zh-cn.ts sidebar has these sections in this order.
for section in ("user_guide", "gunpack", "model_guide"):
    pages.extend(walk_section(CHINESE / section))
for path in sorted(CHINESE.rglob("*.md")):
    if path not in pages:
        pages.append(path)

anchors = {path.resolve(): f"tacz-{number:03d}" for number, path in enumerate(pages, 1)}
legacy_count = sum("legacy" in path.parts for path in pages)
all_chinese = set(CHINESE.rglob("*.md"))
if set(pages) != all_chinese or len(pages) != len(set(pages)):
    raise RuntimeError("The anthology must include every Chinese Markdown exactly once.")

missing_local_images: set[str] = set()
rebased_images = 0


def rebase_target(raw: str, source: Path, is_image: bool = False) -> str:
    global rebased_images
    url = raw.strip("<>")
    split = urlsplit(url)
    if split.scheme or split.netloc or url.startswith("//"):
        return raw
    if not split.path:
        return local_link(source) + ("#" + split.fragment if split.fragment else "")
    decoded = unquote(split.path)
    if decoded.startswith("/"):
        public_candidate = PUBLIC / decoded.lstrip("/")
        candidate = public_candidate if public_candidate.exists() else DOCS / decoded.lstrip("/")
    else:
        candidate = source.parent / decoded
    candidate = candidate.resolve()
    if not candidate.is_file():
        alternatives = [candidate / "README.md", candidate.with_suffix(".md")]
        if candidate.suffix == ".html":
            alternatives.insert(0, candidate.with_suffix(".md"))
        candidate = next((item.resolve() for item in alternatives if item.is_file()), candidate)
    if candidate.is_file():
        if is_image:
            rebased_images += 1
        if not is_image and not split.fragment and candidate in anchors:
            return "#" + anchors[candidate]
        suffix = ("?" + split.query if split.query else "") + ("#" + split.fragment if split.fragment else "")
        return local_link(candidate) + suffix
    if is_image:
        missing_local_images.add(f"{source.relative_to(CHINESE).as_posix()}: {url}")
    if decoded.startswith("/"):
        return "https://tacwiki.mcma.club" + url
    parent = source.parent.relative_to(DOCS).as_posix()
    return "https://tacwiki.mcma.club/" + posixpath.normpath(parent + "/" + url)


INLINE_LINK = re.compile(r"(?P<prefix>!?\[[^\]\n]*\]\()(?P<url><[^>\n]+>|[^\s)]+)(?P<suffix>[^)\n]*\))")


def adapt_body(text: str, source: Path) -> str:
    result = []
    fence_char = ""
    fence_length = 0
    for line in text.splitlines(keepends=True):
        fence = re.match(r"^\s{0,3}(`{3,}|~{3,})", line)
        if fence:
            token = fence.group(1)
            if not fence_char:
                fence_char, fence_length = token[0], len(token)
            elif token[0] == fence_char and len(token) >= fence_length:
                fence_char = ""
            result.append(line)
            continue
        if fence_char:
            result.append(line)
            continue

        def replace_link(match):
            image = match.group("prefix").startswith("!")
            return match.group("prefix") + rebase_target(match.group("url"), source, image) + match.group("suffix")

        line = INLINE_LINK.sub(replace_link, line)
        line = re.sub(r'(?P<prefix><img\b[^>]*\bsrc=["\'])(?P<url>[^"\']+)',
                      lambda match: match.group("prefix") + rebase_target(match.group("url"), source, True), line)
        line = re.sub(r"^(?P<prefix>\s*\[[^\]]+\]:\s*)(?P<url><[^>]+>|\S+)",
                      lambda match: match.group("prefix") + rebase_target(match.group("url"), source), line)
        # Nest the source page below the anthology chapter without changing fenced examples.
        line = re.sub(r"^(#{1,6})(?=\s)", lambda match: "#" * min(6, len(match.group(1)) + 2), line)
        result.append(line)
    return "".join(result)


intro = f"""# TaCZ 官方附属制作教程：中文离线全集与 1.21.1 使用导读

整理日期：2026-10-17（Asia/Shanghai）。目标开发环境：Minecraft 1.21.1 / NeoForge；本次安装的 TaCZ 为 MUKSC 的非官方移植版 `1.1.8-hotfix-r7`，Modrinth 版本 ID 为 `62q6mB5Q`。下文前置导读由本项目整理，后半部分是官方 Wiki 中文原文的本地合并版，两者已明确分开。

## 先分清你要制作哪一种附属

| 目标 | 文件形式与位置 | 应该先读什么 |
| --- | --- | --- |
| 添加枪械、弹药、配件、模型、声音、动画、枪械配方 | TaCZ 枪包，以文件夹、ZIP 等形式放入开发实例的 `neoforge-1.21.1/run/tacz/`；主要编辑 JSON、模型、贴图、动画，有需要时编写 Lua | 本文收录的官方「枪包制作指南」和「模型建造指南」 |
| 增加 Java 行为、监听开火/伤害事件、让实体使用枪、与其他模组联动 | NeoForge Java 附属模组；自己的代码仍在 `src/main/java/`，发布后是放入 `mods/` 的 JAR | NeoForge 开发教程、TaCZ 官方 API 源码的注释，以及与实际安装版本匹配的移植版 API 源码 |

枪包和 Java 附属模组可以结合：Java 模组也可能携带枪包，但这不意味着任意 `.jar` 都属于同一种内容。移植作者的 [TaCZ Pack Upgrader 说明](https://modrinth.com/mod/tacz-pack-upgrader) 专门区分 `.jar` 枪包与 `.jar` Java 附属；前者按该说明放 `tacz/`，真正的 Java 附属放 `mods/`。

本项目里的 `examplemod` 是你正在编写的 Java 模组。把 TaCZ JAR 放进 `run/mods/`，即可让两者在开发游戏中一起加载；如果自己的 Java 代码要 `import com.tacz.guns...` 调用 API，还需要另外配置匹配的编译依赖。具体启动与兼容性操作见 [模组兼容测试与启动说明](模组兼容测试与启动说明.md) 和工作区 [README](../README.md)。

## 为什么这些资料属于官方来源

1. [TaCZ 官方源码仓库 MCModderAnchor/TACZ](https://github.com/MCModderAnchor/TACZ) 的 README 直接提供 Wiki 链接，指向 [tacwiki.mcma.club](https://tacwiki.mcma.club/)。同一 README 说明原版项目面向 Minecraft Forge 1.20.1。
2. [官方 Wiki 中文主页](https://tacwiki.mcma.club/zh/) 的「编辑此页」指向 [MCModderAnchor/tacwiki](https://github.com/MCModderAnchor/tacwiki)。该仓库 README 说明它提供使用说明和附属制作指南，并有中文与英文文档。
3. 本次下载固定到官方 Wiki 提交 [`{COMMIT}`]({REPO_URL}/tree/{COMMIT})，而非第三方教程站。原始 ZIP 保存在 [official-tacwiki.zip](../downloads/tacz/official-tacwiki.zip)，原始仓库完整解压在 [TaCZ官方Wiki源码/tacwiki-{COMMIT}](TaCZ官方Wiki源码/tacwiki-{COMMIT}/README.md)。

文档、图像、示例的作者与版权仍属于各自原作者。原始仓库内容完整保留；本整理不改变其许可。模组官方源码 README 分别列出代码 GPL-3.0 和资产 CC BY-NC-ND-4.0；使用具体文档与资源时仍应保留其原始作者和许可信息。

## 1.21.1 NeoForge 与官方教程有哪些差异

- **官方教程的版本基线不同。** 官方中文主页写的是 TaCZ `v1.1.4`；新版 [在开始之前](https://tacwiki.mcma.club/zh/gunpack/01_before_start.html) 同时提示文档仍在建设，部分内容可能不完整或有误。某些最新中文章节还涉及更新版本或内测版字段，所以文档字段应以本次 `1.1.8-hotfix-r7` 实际源码和默认枪包为准。
- **当前使用的是非官方移植版。** [Modrinth 项目](https://modrinth.com/mod/tacz-1.21.1) 的源码链接指向 [MUKSC/TACZ-1.21.1](https://github.com/MUKSC/TACZ-1.21.1)，使用 `neoforge/1.21.1` 分支；它不是原作者发布的 Forge 1.20.1 JAR。游戏操作接近，不代表数据、Java API 或其他附属可以直接跨版本使用。
- **世界和 Java 附属不要按同版本处理。** 移植项目明确说明 1.20.1 TaCZ 世界物品数据与该版本不兼容；Forge 1.20.1 Java 附属也不能仅靠复制 JAR 就变成 NeoForge 1.21.1 附属。要选择作者为 1.21.1 NeoForge 发布的文件，或真正移植源码。
- **枪包的两种迁移要分开。** 官方文档的 [从旧版本迁移](https://tacwiki.mcma.club/zh/gunpack/-2_convert_from_legacy.html) 处理 TaCZ 自身 1.1.4 前后的目录格式升级；旧布局与现代布局不能混用。现代布局含 `assets/`、`data/`、`gunpack.meta.json`，放在实例 `tacz/`；旧文档里的 `config/tacz/custom/` 不能作为本实例的新枪包目录。
- **1.20.1 到 1.21.1 的枪包适配是另一件事。** 移植项目说明第三方枪包某些功能和配方需要 [TaCZ Pack Upgrader](https://modrinth.com/mod/tacz-pack-upgrader)。它自动处理枪包的跨 Minecraft 版本适配，不能移植 Java 附属。本次只安装 TaCZ 主模组，**没有额外安装 Pack Upgrader 或其他附属**；以后使用旧枪包时再根据需求安装。
- **不要直接照抄官方 Forge 构建配置。** 官方 README 中 `fg.deobf(...)` 的示例属于 Forge 1.20.1 的构建体系。当前工程的 NeoForge 依赖配置和事件包名应使用本工程版本；移植仓库 README 还记录内置 KubeJS 配方集成关闭，不能把官方联动支持视为自动完整继承。

## Java 附属的依赖说明与 API 源码阅读入口

本次检索官方 Wiki 与官方 README 后，找到的官方制作教程以**枪包内容制作**为主；没有找到一套独立、完整的「Java 附属模组从零制作」教程。Java 附属可从 [官方 README 的 Maven 依赖说明](https://github.com/MCModderAnchor/TACZ/tree/{OFFICIAL_SOURCE_COMMIT}#maven) 和下列官方 API 源码注释入手，再对照本地匹配移植版源码。官方 Maven 示例面向 Forge 1.20.1，不可原样套进当前 NeoForge 工程。

| 用途 | 官方 API 源码入口 | 本地匹配当前移植版本的源码 |
| --- | --- | --- |
| 查询枪械、弹药、配件的索引与显示资源 | [TimelessAPI.java](https://github.com/MCModderAnchor/TACZ/blob/{OFFICIAL_SOURCE_COMMIT}/src/main/java/com/tacz/guns/api/TimelessAPI.java) | [TimelessAPI.java](TaCZ-1.21.1-API源码/com/tacz/guns/api/TimelessAPI.java) |
| 识别枪械物品，访问枪械 ID、弹药、配件等状态 | [IGun.java](https://github.com/MCModderAnchor/TACZ/blob/{OFFICIAL_SOURCE_COMMIT}/src/main/java/com/tacz/guns/api/item/IGun.java) | [IGun.java](TaCZ-1.21.1-API源码/com/tacz/guns/api/item/IGun.java) |
| 实体使用枪械、瞄准、换弹、开火 | [IGunOperator.java](https://github.com/MCModderAnchor/TACZ/blob/{OFFICIAL_SOURCE_COMMIT}/src/main/java/com/tacz/guns/api/entity/IGunOperator.java) | [IGunOperator.java](TaCZ-1.21.1-API源码/com/tacz/guns/api/entity/IGunOperator.java) |
| 监听开火、换弹、枪械伤害、击杀等事件 | [官方 api/event 目录](https://github.com/MCModderAnchor/TACZ/tree/{OFFICIAL_SOURCE_COMMIT}/src/main/java/com/tacz/guns/api/event) | [GunShootEvent.java](TaCZ-1.21.1-API源码/com/tacz/guns/api/event/common/GunShootEvent.java)、[EntityHurtByGunEvent.java](TaCZ-1.21.1-API源码/com/tacz/guns/api/event/common/EntityHurtByGunEvent.java) |
| 构造枪械、弹药、配件物品 | [官方 api/item/builder 目录](https://github.com/MCModderAnchor/TACZ/tree/{OFFICIAL_SOURCE_COMMIT}/src/main/java/com/tacz/guns/api/item/builder) | [GunItemBuilder.java](TaCZ-1.21.1-API源码/com/tacz/guns/api/item/builder/GunItemBuilder.java) |

右列是从与已安装 JAR 匹配的 sources JAR 中提取的**非官方 1.21.1 移植版 API 源码**。若两边方法、参数、事件基类或数据存取方式不一致，开发时优先对照右列。该目录用于阅读，编译时应通过 Gradle 引用匹配的 TaCZ 依赖，来源信息见 [API 源码存档说明](TaCZ-1.21.1-API源码/SOURCE.md)。在线移植仓库源码在整理时的分支提交为 `{PORT_SOURCE_COMMIT}`；本地匹配 sources JAR 是本次安装版本的直接参考。

## 建议的阅读与练习顺序

1. 先读「在开始之前」「创建第一个枪包」「添加描述信息」「使用语言文件」，了解现代枪包布局和独立命名空间。
2. 从默认枪包中观察一把简单枪械的 index、data、display 与模型引用，结合「准备素材」「创建第一把枪」，再修改伤害、射速、弹药和配方。保留默认枪包，练习内容使用自己的命名空间。
3. 需要新模型、贴图与骨骼时读「模型建造指南」；需要复杂表现时再读枪械显示配置、动画、状态机与 Lua。
4. 需要 Java 事件和其他模组联动时，回到本工程 NeoForge 教程，从最小事件监听开始，再查上表 API。
5. 「旧版文档」作为排查旧枪包时的参考；它被完整收录，但官方明确表示不再维护。不要混用新旧路径。

## 离线收录范围与阅读方式

本文件完整收录该提交 `docs/zh/` 下全部 **{len(pages)} 篇 Markdown**，其中包含 **{legacy_count} 篇旧版文档**；包含首页、模组使用说明、现代枪包教程及字段参考、旧版枪包教程、模型与贴图教程。原始仓库还保留英文文档、配图与 `resource/` 示例。外链视频、外部软件文档和第三方站点未包含在离线存档中。

章节顺序按官方中文侧栏「模组使用文档 → 枪包制作指南 → 模型建造指南」排列；每组遵循原文 frontmatter 的 `order` / `dir.order`，无指定顺序的文件按名称排列。每篇附在线页、固定提交源码、本地原稿及原文 SHA-256；frontmatter 保留在独立 YAML 代码块中。以下原文仅调整了合并后的标题层级和链接位置，代码围栏内容保持不变。图片链接指向本地原始 Wiki；VuePress 专用容器、徽章和代码标签保留原样，普通 Markdown 阅读器可能不显示其网站交互样式。

## 官方原文索引

"""

chunks = [intro]
for number, source in enumerate(pages, 1):
    relative = source.relative_to(CHINESE).as_posix()
    label = "【旧版】" if "legacy" in source.parts else ""
    chunks.append(f"{number}. [{label}{title_of(source)}](#{anchors[source.resolve()]}) — `{relative}`\n")
chunks.append("\n---\n\n以下为官方中文原文全文。\n\n")

for number, source in enumerate(pages, 1):
    original = source.read_bytes()
    metadata, body = split_frontmatter(original.decode("utf-8-sig").replace("\r\n", "\n"))
    source_relative = source.relative_to(UPSTREAM).as_posix()
    label = "【旧版】" if "legacy" in source.parts else ""
    chunks.append(f'<a id="{anchors[source.resolve()]}"></a>\n\n')
    chunks.append(f"## {number:02d}. {label}{title_of(source)}\n\n")
    chunks.append(f"[在线原文]({page_url(source)}) · [固定提交源码]({REPO_URL}/blob/{COMMIT}/{source_relative}) · [本地未修改原稿]({local_link(source)})\n\n")
    chunks.append(f"原文 SHA-256：`{hashlib.sha256(original).hexdigest()}`\n\n")
    if metadata:
        chunks.append("原始页面元数据：\n\n```yaml\n" + metadata + "\n```\n\n")
    chunks.append(adapt_body(body, source).strip() + "\n\n---\n\n")

chunks.append("## 存档边界\n\n")
chunks.append(f"收录 {len(pages)} / {len(all_chinese)} 篇中文 Markdown，其中旧版 {legacy_count} 篇。合并正文中 {rebased_images} 处图片引用已指向本地原始文件；外部超链接仍需联网。原始资料未修改。重新生成方法：在工作区使用 Python 3 运行 `scripts/build-tacz-tutorial.py`。\n")
if missing_local_images:
    chunks.append("\n下列图片在原始仓库中没有找到对应本地文件，保留为在线地址；这属于上游存档本身的边界：\n\n")
    chunks.extend(f"- `{item}`\n" for item in sorted(missing_local_images))

OUTPUT.write_text("".join(chunks), encoding="utf-8", newline="\n")
print(f"Wrote {OUTPUT}: {len(pages)} Chinese pages, {legacy_count} legacy pages, "
      f"{rebased_images} local image references, {len(missing_local_images)} upstream image gaps, "
      f"{OUTPUT.stat().st_size} bytes")
