"""Slice the imagegen atlas and clean its background, as approved by the user.

No equipment geometry is drawn here. Alpha is extracted from the neutral gray
generated silhouettes, background speckles are removed, and the mask is padded
and resampled for the HUD. The accepted original and prompt remain in docs.
"""
from collections import deque
from pathlib import Path
import hashlib
import json

import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/repository/status-bar-assets/equipment-generated.png"
OUTPUT = ROOT / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg/textures/gui/hud/status"


def remove_speckles(alpha):
    core = alpha > 96
    unseen = core.copy()
    keep = np.zeros_like(core)
    height, width = core.shape
    for y, x in zip(*np.nonzero(core)):
        if not unseen[y, x]:
            continue
        queue = deque([(y, x)])
        unseen[y, x] = False
        component = []
        while queue:
            cy, cx = queue.popleft()
            component.append((cy, cx))
            for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                ny, nx = cy + dy, cx + dx
                if 0 <= ny < height and 0 <= nx < width and unseen[ny, nx]:
                    unseen[ny, nx] = False
                    queue.append((ny, nx))
        if len(component) >= 35:
            for cy, cx in component:
                keep[cy, cx] = True
    # Keep antialiased edges belonging to the generated glyph, not background dots.
    fringe = np.array(Image.fromarray(keep.astype(np.uint8) * 255).filter(ImageFilter.MaxFilter(3))) > 0
    return np.where(fringe, alpha, 0).astype(np.uint8)


def main():
    image = Image.open(SOURCE).convert("RGBA")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    entries = []
    for tier in range(4):
        for col, kind in enumerate(("backpack", "helmet", "armor")):
            box = (col * image.width // 3, tier * image.height // 4,
                   (col + 1) * image.width // 3, (tier + 1) * image.height // 4)
            pixels = np.array(image.crop(box), dtype=np.float32)
            rgb = pixels[:, :, :3]
            light = rgb.mean(axis=2)
            neutral = 1 - np.clip((rgb.max(axis=2) - rgb.min(axis=2) - 8) / 24, 0, 1)
            # Generated icons are neutral gray; white/pink halos belong to the background.
            coverage = (1 - np.clip((light - 175) / 55, 0, 1)) * neutral
            alpha = remove_speckles(np.round(pixels[:, :, 3] * coverage))
            mask = Image.fromarray(alpha)
            bounds = mask.getbbox()
            if bounds is None:
                raise ValueError(f"Missing generated glyph: {kind}_{tier}")
            mask = mask.crop(bounds)
            extent = 27 if kind == "backpack" and tier == 0 else 108
            scale = extent / max(mask.size)
            mask = mask.resize(tuple(max(1, round(value * scale)) for value in mask.size), Image.Resampling.LANCZOS)
            # A white RGB mask lets the existing active/inactive HUD tint control color.
            glyph = Image.new("RGBA", mask.size, (255, 255, 255, 0))
            glyph.putalpha(mask)
            texture = Image.new("RGBA", (128, 128), (255, 255, 255, 0))
            texture.alpha_composite(glyph, ((128 - glyph.width) // 2, (128 - glyph.height) // 2))
            name = f"{kind}_{tier}.png"
            texture.save(OUTPUT / name)
            (OUTPUT / (name + ".mcmeta")).write_text(
                json.dumps({"texture": {"blur": True, "clamp": True}}, indent=2) + "\n", encoding="utf-8")
            entries.append({"file": name, "atlas_cell_xyxy": box, "alpha_bounds": texture.getbbox(),
                            "sha256": hashlib.sha256((OUTPUT / name).read_bytes()).hexdigest()})
    manifest = {
        "source": "docs/repository/status-bar-assets/equipment-generated.png",
        "source_sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        "reference": "docs/repository/status-bar-assets/equipment-reference-grid.png",
        "prompt": "docs/repository/status-bar-assets/imagegen-final-prompt.txt",
        "generator": "Built-in image_gen; no API fallback",
        "postprocess": "User-approved neutral-color alpha cleanup, speckle removal, white mask normalization, crop and resample; no redrawn geometry.",
        "script": "scripts/prepare-status-equipment.py", "outputs": entries,
    }
    (OUTPUT / "equipment-sources.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(entries)} equipment textures")


if __name__ == "__main__":
    main()
