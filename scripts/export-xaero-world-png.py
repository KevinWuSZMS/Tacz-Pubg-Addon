#!/usr/bin/env python3
"""Export existing Xaero surface pixels at one block per pixel without launching Minecraft."""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import runpy
import struct
import zlib


def sha256(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        while data := stream.read(4 * 1024 * 1024):
            result.update(data)
    return result.hexdigest()


def world_bounds(world: Path) -> tuple[tuple[int, int, int, int], int]:
    min_x = min_z = 2**31
    max_x = max_z = -(2**31)
    count = 0
    for path in sorted((world / "region").glob("r.*.*.mca")):
        match = re.fullmatch(r"r\.(-?\d+)\.(-?\d+)\.mca", path.name)
        if not match:
            continue
        rx, rz = map(int, match.groups())
        with path.open("rb") as stream:
            locations = stream.read(4096)
        if not locations:
            continue  # Minecraft/C2ME can leave empty region placeholders with no stored chunks.
        if len(locations) != 4096:
            raise ValueError(f"Truncated MCA index: {path}")
        for index, location in enumerate(struct.unpack(">1024I", locations)):
            if location == 0:
                continue
            if location >> 8 == 0 or location & 255 == 0:
                raise ValueError(f"Invalid MCA location: {path}: {index}")
            x, z = rx * 32 + index % 32, rz * 32 + index // 32
            min_x, max_x = min(min_x, x), max(max_x, x)
            min_z, max_z = min(min_z, z), max(max_z, z)
            count += 1
    if not count:
        raise ValueError("The overworld contains no stored chunks")
    return (min_x * 16, min_z * 16, (max_x + 1) * 16, (max_z + 1) * 16), count


def chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))


def write_png(path: Path, canvas: bytearray, canvas_bounds: tuple[int, int, int, int],
              bounds: tuple[int, int, int, int]) -> dict:
    x0, z0, x1, z1 = bounds
    cx0, cz0, cx1, cz1 = canvas_bounds
    if not (cx0 <= x0 < x1 <= cx1 and cz0 <= z0 < z1 <= cz1):
        raise ValueError("Export bounds must be within the stored-world canvas")
    width, height = x1 - x0, z1 - z0
    stride = (cx1 - cx0) * 4
    view = memoryview(canvas)
    compressor = zlib.compressobj(level=0)
    raw_hash = hashlib.sha256()
    observed = 0
    with path.open("xb") as output:
        output.write(b"\x89PNG\r\n\x1a\n")
        output.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)))
        metadata = json.dumps({"blocks_per_pixel": 1, "origin_x": x0, "origin_z": z0,
                               "x_max_exclusive": x1, "z_max_exclusive": z1,
                               "north": "top (-Z)", "east": "right (+X)",
                               "source": "existing Xaero surface cache", "deflate_level": 0,
                               "scanline_filter": 0}, separators=(",", ":"))
        output.write(chunk(b"tEXt", b"MinecraftMap\x00" + metadata.encode("ascii")))
        for z in range(z0, z1):
            offset = (z - cz0) * stride + (x0 - cx0) * 4
            row = view[offset:offset + width * 4]
            raw_hash.update(row)
            observed += bytes(row[3::4]).count(255)
            payload = compressor.compress(b"\x00" + row.tobytes())
            if payload:
                output.write(chunk(b"IDAT", payload))
        output.write(chunk(b"IDAT", compressor.flush()))
        output.write(chunk(b"IEND", b""))
    return {"path": str(path.resolve()), "width": width, "height": height,
            "bounds": {"x_min": x0, "z_min": z0, "x_max_exclusive": x1, "z_max_exclusive": z1},
            "blocks_per_pixel": 1, "observed_pixels": observed,
            "transparent_pixels": width * height - observed, "rgba_sha256": raw_hash.hexdigest(),
            "file_bytes": path.stat().st_size, "sha256": sha256(path),
            "png": {"color_type": "RGBA8", "deflate_level": 0, "scanline_filter": 0}}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    world, cache, out = args.world.resolve(), args.cache.resolve(), args.output_dir.resolve()
    dimension = (cache.parent / "dimension_config.txt").read_text(encoding="utf-8")
    if "dimensionTypeId:minecraft:overworld" not in dimension:
        raise ValueError("Cache is not identified as the overworld")
    bounds, chunks = world_bounds(world)
    x0, z0, x1, z1 = bounds
    width, height = x1 - x0, z1 - z0
    print(f"Stored-world bounds {bounds}; canvas {width} x {height}; chunks {chunks}", flush=True)
    sources = {}
    for path in sorted(cache.glob("*.xwmc*")):
        match = re.fullmatch(r"(-?\d+)_(-?\d+)\.xwmc(?:\.outdated)?", path.name)
        if match:
            key = tuple(map(int, match.groups()))
            if key not in sources or path.suffix != ".outdated":
                sources[key] = path
    if not sources:
        raise ValueError("No supported Xaero leaf caches found")
    decoder = runpy.run_path(str(Path(__file__).with_name("decode-xaero-cache.py")))["decode_region_cache"]
    canvas = bytearray(width * height * 4)
    records, observed = [], 0
    for index, ((rx, rz), path) in enumerate(sorted(sources.items())):
        stat = path.stat()
        source_hash = sha256(path)
        region = decoder(path)
        if (path.stat().st_size, path.stat().st_mtime_ns) != (stat.st_size, stat.st_mtime_ns):
            raise RuntimeError(f"Cache changed while reading: {path}")
        ox, oz = rx * 512, rz * 512
        ax0, az0, ax1, az1 = max(x0, ox), max(z0, oz), min(x1, ox + 512), min(z1, oz + 512)
        if ax0 < ax1 and az0 < az1:
            for z in range(az0, az1):
                source_start = ((z - oz) * 512 + ax0 - ox) * 4
                target = ((z - z0) * width + ax0 - x0) * 4
                count = (ax1 - ax0) * 4
                canvas[target:target + count] = region.rgba[source_start:source_start + count]
        observed += region.observed_pixels
        records.append({"path": str(path), "sha256": source_hash,
                        "bytes": stat.st_size, "mtime": datetime.datetime.fromtimestamp(stat.st_mtime).isoformat(),
                        "pending_refresh": path.suffix == ".outdated", **region.metadata()})
        if (index + 1) % 100 == 0:
            print(f"Read {index + 1}/{len(sources)} original-resolution cache regions", flush=True)
    out.mkdir(parents=True, exist_ok=False)
    reports = []
    # The two authored PUBG maps occupy two complete 8192-square block areas.
    main_bounds = (-4096, -12288, 4096, 4096)
    reports.append(write_png(out / "PUBG-Erangel-Miramar-8192x16384-1px-per-block.png", canvas, bounds, main_bounds))
    if reports[-1]["transparent_pixels"]:
        raise ValueError("Main PUBG maps contain missing cache pixels; inspect output before delivery")
    print(f"Main maps written: {reports[-1]['file_bytes']} bytes; every pixel has source data", flush=True)
    reports.append(write_png(out / f"PUBG-Erangel-Miramar-full-world-{width}x{height}-1px-per-block.png", canvas, bounds, bounds))
    print(f"Full world written: {reports[-1]['file_bytes']} bytes", flush=True)
    # A small display-only overview does not replace or resample either original PNG.
    try:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = None
        overview = Image.frombuffer("RGBA", (width, height), canvas, "raw", "RGBA", 0, 1)
        overview.thumbnail((900, 1800))
        overview.save(out / "overview-preview.png", compress_level=6)
    except ImportError:
        pass
    outdated = [r for r in records if r["pending_refresh"]]
    manifest = {"world": str(world), "cache": str(cache), "minecraft_started": False,
                "source_files_opened_read_only": True, "mca_stored_chunks": chunks,
                "mca_stored_block_columns": chunks * 256, "source_cache_regions": len(records),
                "source_observed_pixels": observed, "exports": reports,
                "pending_refresh_regions": outdated,
                "limitations": ["Four existing Xaero regions are marked outdated; their stored pixels may predate the latest terrain restoration.",
                                "The full-world PNG uses transparency for ungenerated or uncached columns; the two main PUBG maps have complete cached coverage.",
                                "This is the cached daytime terrain appearance without player markers or gameplay overlays."],
                "sources": records}
    (out / "export-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"exports": reports, "pending_refresh_regions": len(outdated), "manifest": str(out / "export-manifest.json")},
                     ensure_ascii=True, indent=2), flush=True)


if __name__ == "__main__":
    main()
