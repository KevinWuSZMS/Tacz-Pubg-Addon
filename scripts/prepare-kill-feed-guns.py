"""Build the global feed's right-facing gun atlas from installed TaCZ pack HUD textures.

Requires Pillow. Pass the game's run/tacz directory as the first argument.
"""
from contextlib import ExitStack
from io import BytesIO
import json
from pathlib import Path
import re
import runpy
import sys
from zipfile import ZipFile

from PIL import Image, ImageChops, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg/textures/gui/hud/kill_feed"
parse_pack_json = runpy.run_path(str(ROOT / "scripts/audit-ammo-attachment-compatibility.py"))["gson_json"]
CELL_WIDTH, CELL_HEIGHT, COLUMNS = 128, 48, 16
BRANDED = {"aug_camg_kit", "camg_krait", "mk18", "silence_meteor_hud", "timeless_50_long", "tti_mpx", "chisato_m1911"}


def pack_files(directory, opened):
    files = {}
    for pack in sorted(directory.iterdir()):
        if pack.is_dir():
            for path in pack.rglob("*"):
                if path.suffix in {".json", ".png"}:
                    files[path.relative_to(pack).as_posix()] = path.read_bytes
        elif pack.suffix == ".zip":
            archive = opened.enter_context(ZipFile(pack))
            for path in archive.namelist():
                if path.endswith((".png", ".json")):
                    files[path] = lambda archive=archive, path=path: archive.read(path)
    return files


def asset_path(reference, folder, extension):
    namespace, path = reference.split(":")
    return f"assets/{namespace}/{folder}/{path}.{extension}"


def strip_detached_logo(mask):
    # All seven marked HUDs have their connected weapon body above the separate maker logo.
    shape = mask.point(lambda a: 255 if a else 0)
    y = mask.height // 3
    start, _, end, _ = shape.crop((0, y, mask.width, y + 1)).getbbox()
    seed = next(x for x in range((start + end) // 2, start - 1, -1) if shape.getpixel((x, y)))
    ImageDraw.floodfill(shape, (seed, y), 128)
    return ImageChops.multiply(mask, shape.point(lambda a: 255 if a == 128 else 0))


def oriented_mask(files, path):
    def read_mask(name):
        with Image.open(BytesIO(files[name]())) as image:
            alpha = image.convert("RGBA").getchannel("A")
            return alpha.crop(alpha.getbbox())

    mask = read_mask(path)
    if "/ccrp/" in path and Path(path).stem in BRANDED:
        mask = strip_detached_logo(mask)
    if path.endswith("/coltm1892pair.png"):
        # The paired HUD faces both ways. Stack the pack's upright matching revolvers.
        single = read_mask(path.replace("coltm1892pair", "coltm1892"))
        offset = max(1, single.height // 3)
        mask = Image.new("L", (single.width + offset, single.height + offset))
        mask.paste(single, (0, 0))
        second = Image.new("L", mask.size)
        second.paste(single, (offset, offset))
        mask = ImageChops.lighter(mask, second)
    if path != "assets/gz/textures/gun/hud/t5smg.png":
        mask = ImageOps.mirror(mask)
    return ImageOps.contain(mask.crop(mask.getbbox()), (CELL_WIDTH - 2, CELL_HEIGHT - 2), Image.Resampling.LANCZOS)


def generate(directory):
    with ExitStack() as opened:
        files = pack_files(directory, opened)
        hud_for_gun = {}
        missing = []
        for path, read in sorted(files.items()):
            index = re.fullmatch(r"data/([^/]+)/index/guns/(.+)\.json", path)
            if not index:
                continue
            gun = f"{index[1]}:{index[2]}"
            display = asset_path(parse_pack_json(read().decode("utf-8-sig"))["display"], "display/guns", "json")
            if display in files:
                hud = parse_pack_json(files[display]().decode("utf-8-sig")).get("hud")
                if hud:
                    texture = asset_path(hud, "textures", "png")
                    if texture in files:
                        hud_for_gun[gun] = texture
                        continue
            # Some packs ship the actual HUD art but omit the corresponding display JSON.
            same_name = f"assets/{index[1]}/textures/gun/hud/{index[2]}.png"
            if same_name in files:
                hud_for_gun[gun] = same_name
            else:
                missing.append(gun)

        textures = sorted(set(hud_for_gun.values()))
        if not textures:
            raise ValueError(f"No TaCZ gun HUD textures in {directory}")
        rows = (len(textures) + COLUMNS - 1) // COLUMNS
        alpha = Image.new("L", (COLUMNS * CELL_WIDTH, rows * CELL_HEIGHT))
        rectangles = {}
        for index, path in enumerate(textures):
            mask = oriented_mask(files, path)
            x = index % COLUMNS * CELL_WIDTH + 1
            y = index // COLUMNS * CELL_HEIGHT + 1
            alpha.paste(mask, (x, y))
            rectangles[path] = [x, y, mask.width, mask.height]

    atlas = Image.new("LA", alpha.size, (255, 0))
    atlas.putalpha(alpha)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    atlas.save(OUTPUT / "guns.png", optimize=True)
    entries = [f'    "{gun}": {json.dumps(rectangles[hud])}' for gun, hud in hud_for_gun.items()]
    with (OUTPUT / "guns.json").open("w", encoding="utf8", newline="\n") as index:
        index.write(f'{{\n  "width": {atlas.width},\n  "height": {atlas.height},\n  "guns": {{\n')
        index.write(",\n".join(entries))
        index.write("\n  }\n}\n")
    print(f"{len(hud_for_gun)} guns, {len(textures)} HUD textures, {len(missing)} missing")
    print(*missing, sep="\n")


if __name__ == "__main__":
    generate(Path(sys.argv[1]))
