"""Rasterize the bundled OFL font, without drawing or approximating any glyphs.

Run with Python and Pillow. The font source and license ship beside the resources;
the resulting small antialiased atlas is sampled linearly by CombatHudNumbers.
"""
from pathlib import Path
import hashlib
import json

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg"
FONT = ASSETS / "font/hud/BebasNeue-Regular.ttf"
OUTPUT = ASSETS / "textures/gui/hud/numbers"
CHARS = "0123456789%\u221e.\u2014-:/"
CELL, COLUMNS, PADDING, SCALE, FONT_SIZE = 64, 8, 4, 4, 67


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    font = ImageFont.truetype(str(FONT), FONT_SIZE * SCALE)
    top = font.getbbox("0123456789")[1]
    cap_height = (font.getbbox("0123456789")[3] - top) / SCALE
    atlas = Image.new("RGBA", (CELL * COLUMNS, CELL * 3), (255, 255, 255, 0))
    advances = []
    for i, char in enumerate(CHARS):
        mask = Image.new("L", (CELL * SCALE, CELL * SCALE))
        ImageDraw.Draw(mask).text((PADDING * SCALE, PADDING * SCALE - top), char, font=font, fill=255)
        mask = mask.resize((CELL, CELL), Image.Resampling.LANCZOS)
        glyph = Image.new("RGBA", (CELL, CELL), (255, 255, 255, 0))
        glyph.putalpha(mask)
        atlas.paste(glyph, (i % COLUMNS * CELL, i // COLUMNS * CELL))
        advances.append(round(font.getlength(char) / SCALE, 6))
    atlas.save(OUTPUT / "bebas_neue.png")
    (OUTPUT / "bebas_neue.png.mcmeta").write_text(
        json.dumps({"texture": {"blur": True, "clamp": True}}, indent=2) + "\n", encoding="utf-8")
    manifest = {
        "font": "Bebas Neue Regular", "license": "SIL Open Font License 1.1",
        "font_source": "https://github.com/google/fonts/blob/main/ofl/bebasneue/BebasNeue-Regular.ttf",
        "license_source": "https://github.com/google/fonts/blob/main/ofl/bebasneue/OFL.txt",
        "font_sha256": hashlib.sha256(FONT.read_bytes()).hexdigest(),
        "characters": CHARS, "cell": CELL, "columns": COLUMNS, "padding": PADDING,
        "cap_height": cap_height, "advances": advances,
        "generator": "scripts/build-hud-number-atlas.py",
        "notes": "All glyphs are rasterized from the bundled font with 4x supersampling. No hand-drawn digits.",
    }
    (OUTPUT / "sources.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"cap_height": cap_height, "advances": advances, "output": str(OUTPUT)}, ensure_ascii=True))


if __name__ == "__main__":
    main()
