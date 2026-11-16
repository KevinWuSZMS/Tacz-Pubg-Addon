"""Extract existing HUD symbols from the supplied screenshot; never draw their shapes.

The screenshot has a translucent white HUD on a varying game background. A local
opening identifies each symbol, then background interpolation removes that game
image. All foreground coverage comes from source pixels. The script requires
Pillow and numpy and deliberately keeps the original crops for source review.
"""

from __future__ import annotations

import argparse
from collections import deque
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter


def largest_component(binary: np.ndarray) -> np.ndarray:
    height, width = binary.shape
    unseen = binary.copy()
    largest: list[tuple[int, int]] = []
    for y, x in zip(*np.nonzero(binary)):
        if not unseen[y, x]:
            continue
        pending = deque([(y, x)])
        unseen[y, x] = False
        pixels = []
        while pending:
            cy, cx = pending.popleft()
            pixels.append((cy, cx))
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < height and 0 <= nx < width and unseen[ny, nx]:
                        unseen[ny, nx] = False
                        pending.append((ny, nx))
        if len(pixels) > len(largest):
            largest = pixels
    result = np.zeros_like(binary)
    for y, x in largest:
        result[y, x] = True
    return result


def extract(crop: Image.Image) -> Image.Image:
    gray = crop.convert("L")
    source = np.asarray(gray, dtype=np.float64)
    opened = gray.filter(ImageFilter.MinFilter(13)).filter(ImageFilter.MaxFilter(13))
    difference = source - np.asarray(opened, dtype=np.float64)
    core = largest_component(difference > 12)
    mask_image = Image.fromarray(core.astype(np.uint8) * 255).filter(ImageFilter.MaxFilter(5))
    region = np.asarray(mask_image) > 0
    # Keep only the one-pixel source fringe around the connected symbol. The
    # wider two-pixel region below is used solely to estimate the background.
    coverage_region = np.asarray(Image.fromarray(core.astype(np.uint8) * 255)
                                 .filter(ImageFilter.MaxFilter(3))) > 0
    background = source.copy()
    background[region] = np.asarray(opened)[region]
    for _ in range(500):
        padded = np.pad(background, 1, mode="edge")
        interpolated = (padded[1:-1, :-2] + padded[1:-1, 2:]
                        + padded[:-2, 1:-1] + padded[2:, 1:-1]) / 4
        background[region] = interpolated[region]
    residual = np.maximum(0, source - background)
    white_level = np.percentile(residual[core], 55)
    alpha = np.where(coverage_region, np.clip((residual - 4) / (white_level - 4), 0, 1), 0)
    alpha[alpha < .12] = 0
    foreground = Image.new("RGBA", crop.size, (255, 255, 255, 0))
    foreground.putalpha(Image.fromarray(np.round(alpha * 255).astype(np.uint8)))
    return pack(foreground)


def pack(foreground: Image.Image, symmetric: bool = False) -> Image.Image:
    foreground = foreground.crop(foreground.getbbox())
    foreground.thumbnail((108, 108), Image.Resampling.LANCZOS)
    # thumbnail does not upscale. Resample the extracted coverage, never redraw.
    scale = 108 / max(foreground.size)
    foreground = foreground.resize(tuple(round(v * scale) for v in foreground.size), Image.Resampling.BICUBIC)
    coverage = np.asarray(foreground.getchannel("A")).copy()
    coverage[coverage < 12] = 0
    foreground.putalpha(Image.fromarray(coverage))
    result = Image.new("RGBA", (128, 128), (255, 255, 255, 0))
    result.alpha_composite(foreground, ((128 - foreground.width) // 2, (128 - foreground.height) // 2))
    if symmetric:
        # Average only extracted coverage; this corrects background-dependent edge
        # opacity without replacing the screenshot's cross with drawn geometry.
        coverage = np.asarray(result.getchannel("A"), dtype=np.float64)
        coverage = (coverage + coverage[::-1] + coverage[:, ::-1] + coverage[::-1, ::-1]) / 4
        result.putalpha(Image.fromarray(np.round(coverage).astype(np.uint8)))
    return result


def extract_solid(crop: Image.Image, symmetric: bool = False) -> Image.Image:
    """Recover filled screenshot symbols, whose broad interiors defeat an opening.

    Local pixel edges define the silhouette. Flooding from the crop's border
    preserves its open notches while filling the enclosed foreground. Interpolate
    background only for the antialiased fringe; solid interiors receive uniform
    opacity rather than copying terrain shadows into the white icon.
    """
    source = np.asarray(crop.convert("L"), dtype=np.float64)
    gradient = np.zeros_like(source)
    for axis in (0, 1):
        difference = np.abs(np.diff(source, axis=axis))
        if axis == 0:
            gradient[1:] = np.maximum(gradient[1:], difference)
            gradient[:-1] = np.maximum(gradient[:-1], difference)
        else:
            gradient[:, 1:] = np.maximum(gradient[:, 1:], difference)
            gradient[:, :-1] = np.maximum(gradient[:, :-1], difference)
    edges = gradient > 6
    height, width = source.shape
    outside = np.zeros_like(edges)
    pending = deque((y, x) for y in range(height) for x in range(width)
                    if y in (0, height - 1) or x in (0, width - 1))
    while pending:
        y, x = pending.popleft()
        if not (0 <= y < height and 0 <= x < width) or outside[y, x] or edges[y, x]:
            continue
        outside[y, x] = True
        pending.extend(((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)))
    region = largest_component(~outside)
    interior = np.asarray(Image.fromarray(region.astype(np.uint8) * 255)
                          .filter(ImageFilter.MinFilter(3))) > 0
    background = source.copy()
    background[region] = source[~region].mean()
    for _ in range(1200):
        padded = np.pad(background, 1, mode="edge")
        interpolated = (padded[1:-1, :-2] + padded[1:-1, 2:]
                        + padded[:-2, 1:-1] + padded[2:, 1:-1]) / 4
        background[region] = interpolated[region]
    residual = np.maximum(0, source - background)
    white_level = max(1, np.percentile(residual[interior], 75))
    alpha = np.where(region, np.clip(residual / white_level, 0, 1), 0)
    alpha[interior] = 1
    alpha[alpha < .15] = 0
    foreground = Image.new("RGBA", crop.size, (255, 255, 255, 0))
    foreground.putalpha(Image.fromarray(np.round(alpha * 255).astype(np.uint8)))
    return pack(foreground, symmetric)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, nargs="?", default=Path(__file__).resolve().parents[1]
                        / "docs/repository/status-bar-assets/status-reference.png")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    source = Image.open(args.source).convert("RGB")
    workbench = args.root / "assets-workbench/status-bar-ui"
    destination = args.root / "neoforge-1.21.1/src/main/resources/assets/tacz_pubg/textures/gui/hud/status"
    workbench.mkdir(parents=True, exist_ok=True)
    destination.mkdir(parents=True, exist_ok=True)
    crops = {"sprint": (451, 10, 496, 61), "heal": (508, 15, 548, 57), "lungs": (558, 7, 613, 63)}
    preview = Image.new("RGB", (256 * len(crops), 256), (42, 46, 50))
    entries = []
    for index, (name, rectangle) in enumerate(crops.items()):
        crop = source.crop(rectangle)
        crop.save(workbench / f"{name}-source-crop.png")
        symbol = extract(crop) if name == "sprint" else extract_solid(crop, symmetric=name == "heal")
        symbol.save(destination / f"{name}.png")
        (destination / f"{name}.png.mcmeta").write_text(
            json.dumps({"texture": {"blur": True, "clamp": True}}, indent=2) + "\n", encoding="utf-8")
        enlarged = symbol.resize((256, 256), Image.Resampling.BICUBIC)
        preview.paste(enlarged, (index * 256, 0), enlarged)
        entries.append({"file": f"{name}.png", "source_crop_xyxy": rectangle,
                        "method": "local opening" if name == "sprint" else "pixel-edge silhouette and filled interior",
                        "symmetric_alpha": name == "heal",
                        "sha256": hashlib.sha256((destination / f"{name}.png").read_bytes()).hexdigest()})
    preview.save(workbench / "status-symbols-preview.png")
    manifest = json.dumps({
        "source": str(args.source), "source_sha256": hashlib.sha256(args.source.read_bytes()).hexdigest(),
        "method": "Source-pixel silhouette extraction with local background interpolation; no redrawn geometry.",
        "source_resolution_limitation": "Original symbols are approximately 30-40 pixels tall; resampling cannot recover absent details.",
        "outputs": entries}, ensure_ascii=False, indent=2) + "\n"
    (workbench / "status-symbols-source.json").write_text(manifest, encoding="utf-8")
    (destination / "status-symbols-source.json").write_text(manifest, encoding="utf-8")
    print(json.dumps(entries, indent=2))


if __name__ == "__main__":
    main()
