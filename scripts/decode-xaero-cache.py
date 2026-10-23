#!/usr/bin/env python3
"""Read Xaero World Map 1.47 leaf caches without loading Minecraft or OpenGL.

Supported on-disk schema: 25.1 (0x00010019), cache_1 leaf regions,
uncompressed GL_RGBA8 buffers. Other schemas and lossy/compressed texture
formats are deliberately rejected. Inputs are opened read-only.

Each leaf region is 512 x 512 blocks, split into 8 x 8 textures of 64 x 64
pixels. No resizing is performed. The Windows cache byte order is [light,
red, green, blue], matching Xaero's GL_BGRA / GL_UNSIGNED_INT_8_8_8_8 upload.
The day map shader outputs RGB unchanged at brightness=1 and treats only a
completely zero texel as transparent. The light byte is not image alpha.

Evidence: xaeroworldmap-neoforge-1.21.1-1.47.0.jar, LeveledRegion's
loadCacheTextures/readCacheInput, MapRegion.readCacheMetaData,
RegionTexture.readCacheData and xaerolib's assets/minecraft/shaders/core/
xaerolib/map.fsh. This is a CPU reader of the cache format, not a call to
Xaero's PNGExporter, which requires an active GL renderer.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import struct
import zlib
import zipfile


SCHEMA = 0x00010019
REGION_SIZE = 512
TILE_SIZE = 64


class Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def take(self, length: int) -> bytes:
        if length < 0 or self.pos + length > len(self.data):
            raise ValueError(f"Truncated/invalid cache at byte {self.pos}: {length}")
        value = self.data[self.pos:self.pos + length]
        self.pos += length
        return value

    def byte(self) -> int:
        return self.take(1)[0]

    def integer(self) -> int:
        return struct.unpack(">i", self.take(4))[0]

    def skip_utf(self) -> None:
        self.take(struct.unpack(">H", self.take(2))[0])


@dataclass
class RegionImage:
    region_x: int
    region_z: int
    rgba: bytearray
    tile_count: int
    observed_pixels: int
    tiles_with_light: int
    schema: int = SCHEMA

    def metadata(self) -> dict:
        return {
            "region_x": self.region_x,
            "region_z": self.region_z,
            "origin_x": self.region_x * REGION_SIZE,
            "origin_z": self.region_z * REGION_SIZE,
            "width": REGION_SIZE,
            "height": REGION_SIZE,
            "blocks_per_pixel": 1,
            "schema": self.schema,
            "tiles": self.tile_count,
            "observed_pixels": self.observed_pixels,
            "tiles_with_light": self.tiles_with_light,
            "texture_format": "uncompressed GL_RGBA8",
            "appearance": "day, brightness=1; no highlights",
            "north": "top (-Z)",
        }


def decode_region_cache(path: str | Path) -> RegionImage:
    """Return a 512-square day map at exactly one pixel per cached block."""
    path = Path(path)
    match = re.fullmatch(r"(-?\d+)_(-?\d+)\.xwmc(?:\.outdated)?", path.name)
    if path.parent.name != "cache_1" or match is None:
        raise ValueError("Expected cache_1/<region_x>_<region_z>.xwmc leaf cache")
    region_x, region_z = map(int, match.groups())
    with zipfile.ZipFile(path, "r") as archive:
        reader = Reader(archive.read("cache.xaero"))
    schema = reader.integer()
    if schema != SCHEMA:
        raise ValueError(f"Unsupported cache schema: 0x{schema:08x}")

    # MapRegion metadata: cache hash, reload, highlights, cave start/depth.
    reader.take(5 * 4)
    metadata_tags = set()
    while (tag := reader.byte()) != 255:
        if tag >> 4 >= 8 or tag & 15 >= 8 or tag in metadata_tags:
            raise ValueError(f"Invalid/duplicate metadata texture tag: {tag}")
        metadata_tags.add(tag)
        reader.integer()  # Cached texture version.

    region_palette_size = reader.integer()
    if not 0 <= region_palette_size <= 65536:
        raise ValueError(f"Invalid region biome palette size: {region_palette_size}")
    for _ in range(region_palette_size):
        tag = reader.byte()
        if tag == 0:
            reader.skip_utf()
        elif tag != 255:
            reader.integer()  # Legacy numeric biome key.

    pixels = bytearray(REGION_SIZE * REGION_SIZE * 4)
    tags = set()
    observed = 0
    with_light = 0
    while (tag := reader.byte()) != 255:
        tile_x, tile_z = tag >> 4, tag & 15
        if tile_x >= 8 or tile_z >= 8 or tag in tags:
            raise ValueError(f"Invalid/duplicate texture tag: {tag}")
        tags.add(tag)
        compressed = reader.byte()
        texture_format = reader.integer()
        length = reader.integer()
        if (compressed, texture_format, length) != (0, 32856, 16384):
            raise ValueError(
                "Unsupported texture; refusing resampling/lossy conversion: "
                f"compressed={compressed}, format={texture_format}, length={length}"
            )
        raw = reader.take(length)
        with_light += bool(reader.byte())
        reader.take(2 * 1024 * 8)  # Height and top-height 16-bit storages.
        palette_size = reader.integer()
        if not 0 <= palette_size <= 4096:
            raise ValueError(f"Invalid texture biome palette size: {palette_size}")
        for _ in range(palette_size):
            if reader.integer() != -1:
                reader.take(2)  # Palette element's population count.
        if palette_size:
            storage_present = reader.byte()
            if storage_present not in (0, 1):
                raise ValueError(f"Invalid biome storage marker: {storage_present}")
            if storage_present:
                reader.take(1024 * 8)

        tile = bytearray(length)
        tile[0::4] = raw[1::4]
        tile[1::4] = raw[2::4]
        tile[2::4] = raw[3::4]
        alpha = bytes(255 if any(raw[i:i + 4]) else 0 for i in range(0, length, 4))
        tile[3::4] = alpha
        observed += alpha.count(255)
        for row in range(TILE_SIZE):
            target = ((tile_z * TILE_SIZE + row) * REGION_SIZE + tile_x * TILE_SIZE) * 4
            start = row * TILE_SIZE * 4
            pixels[target:target + TILE_SIZE * 4] = tile[start:start + TILE_SIZE * 4]

    if tags != metadata_tags:
        raise ValueError("Texture metadata and payload tags differ")
    if reader.pos != len(reader.data):
        raise ValueError(f"Unexpected trailing data: {len(reader.data) - reader.pos} bytes")
    return RegionImage(region_x, region_z, pixels, len(tags), observed, with_light)


def write_rgba_png(path: str | Path, width: int, height: int, rgba: bytes | bytearray, compression: int = 0) -> None:
    """Write lossless RGBA PNG without third-party libraries or resizing."""
    if len(rgba) != width * height * 4:
        raise ValueError("Pixel buffer size does not match image dimensions")

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))

    compressor = zlib.compressobj(compression)
    stride = width * 4
    with Path(path).open("xb") as output:
        output.write(b"\x89PNG\r\n\x1a\n")
        output.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)))
        for row in range(height):
            payload = compressor.compress(b"\x00" + rgba[row * stride:(row + 1) * stride])
            if payload:
                output.write(chunk(b"IDAT", payload))
        payload = compressor.flush()
        if payload:
            output.write(chunk(b"IDAT", payload))
        output.write(chunk(b"IEND", b""))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cache", type=Path)
    parser.add_argument("--output", type=Path, help="New PNG path; existing files are never overwritten")
    args = parser.parse_args()
    result = decode_region_cache(args.cache)
    if args.output:
        write_rgba_png(args.output, REGION_SIZE, REGION_SIZE, result.rgba)
    print(json.dumps(result.metadata(), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
