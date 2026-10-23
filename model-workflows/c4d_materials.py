"""Read C4D material links for offline base-color previews and static exports.

Shader links, not bitmap name suffixes, decide the color channel. Source shader
channels are retained as metadata; this module does not claim to evaluate C4D
reflection, transparency, procedural shaders, or other lighting effects. Missing
bitmaps have a conspicuous, recorded material-color fallback instead of a guessed
replacement. No files are written beside or into the original source scene.
"""
from __future__ import annotations

import hashlib
from pathlib import Path
import os
import tempfile

from PIL import Image


_CHANNELS = {
    8000: ("color", 0), 8002: ("luminance", 1),
    8003: ("transparency", 2), 8004: ("reflection", 3),
    8005: ("environment", 4), 8006: ("bump", 6),
    8007: ("alpha", 7), 8008: ("specular", 8),
    8009: ("displacement", 11), 8001: ("diffusion", 12),
    8012: ("normal", 13),
}
_CHANNEL_NAMES = ("color", "luminance", "transparency", "reflection",
                  "environment", "fog", "bump", "alpha", "specular",
                  "specular_color", "glow", "displacement", "diffusion", "normal")


def _constant_texture(color, cache_dir: Path) -> str:
    """Materialize an explicit constant so existing bitmap-only tools can reuse it."""
    rgba = tuple(round(max(0.0, min(1.0, float(v))) * 255) for v in color)
    key = hashlib.sha256(bytes(rgba)).hexdigest()[:16]
    path = cache_dir / f"constant_{key}.png"
    if not path.is_file():
        cache_dir.mkdir(parents=True, exist_ok=True)
        # Several independent extraction workers can share this content cache.
        # A reader must never observe a partially written PNG.
        fd, temporary = tempfile.mkstemp(prefix="constant_", suffix=".png", dir=cache_dir)
        try:
            os.close(fd)
            Image.new("RGBA", (1, 1), rgba).save(temporary)
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    return str(path.resolve())


def read_materials(root, source: Path, cache_dir: Path | None = None, *, texture_roots=(),
                   texture_overrides=None):
    """Return ``(materials, alias_to_index)`` for actual material assignments.

    The helper import is deferred because c4d_reader delegates to this module.
    Aliases ``None`` and the native null link resolve to an explicit neutral
    material, allowing unassigned source polygons to remain in the extraction.
    """
    import c4d_reader as c

    source = Path(source).resolve()
    texture_roots = tuple(Path(path).resolve(strict=True) for path in texture_roots)
    cache_dir = Path(cache_dir) if cache_dir else Path(__file__).resolve().parent / "cache" / "materials"
    materials, aliases = [], {}
    used = set()
    for tag in root.walk(5616):
        if tag.children(110050):
            used.add(c._alias(c._parameters(tag).get(1010)))

    def diagnostic(material, message):
        material.setdefault("extraction_warnings", []).append(message)

    def bitmap(shader, shader_alias):
        record = {"shader_alias": shader_alias.hex()}
        if shader is None:
            record["status"] = "unresolved_shader_link"
            return record
        type_records = shader.children(110064)
        type_values = type_records[0].values(15) if type_records else []
        shader_type = type_values[0].data if type_values else shader.ident
        record["shader_type"] = shader_type
        if shader_type != 5833:
            record["status"] = "unsupported_shader"
            record["stored_bitmap_references"] = [v.data for ch in shader.walk() for v in ch.values(131)]
            return record
        parameters = c._parameters(shader)
        filename = c._param(parameters, 1000)
        if not isinstance(filename, str) or not filename:
            record["status"] = "empty_bitmap_filename"
            return record
        record["filename"] = filename
        try:
            record["texture"] = str(c._resolve_texture(source, filename, texture_roots=texture_roots,
                                                       texture_overrides=texture_overrides,
                                                       resolution_info=record))
            record["status"] = "resolved"
        except c.C4DTextureAmbiguityError:
            # Explicit search roots must not silently choose one of several
            # different files or turn an ambiguous user request into fallback.
            raise
        except (c.C4DReadError, OSError) as error:
            record["status"] = "missing_bitmap"
            record["error"] = str(error)
        return record

    nodes = [node for material_root in root.walk(110308)
             for listing in material_root.children(110100)
             for node in listing.children() if node.ident != 110069]
    for node in nodes:
        identity = c._identity(node)
        if identity not in used:
            # Environment/plugin or leftover editor materials have no part in
            # polygon output and must not block an otherwise valid asset.
            continue
        material = {"name": c._name(node), "source_material_type": node.ident,
                    "source_material_alias": identity.hex()}
        aliases[identity] = len(materials)
        materials.append(material)
        if node.ident != 5703:
            material["texture_fallback"] = True
            diagnostic(material, f"Unsupported assigned material type {node.ident}; neutral color preview only.")
            material["base_color_texture"] = _constant_texture((0.65, 0.65, 0.65, 1), cache_dir)
            material["base_color_source"] = "unsupported_material_neutral_fallback"
            continue

        params = c._parameters(node)
        constant = c._param(params, 2100, (0.8, 0.8, 0.8))
        brightness = float(c._param(params, 2101, 1.0))
        strength = float(c._param(params, 2102, 1.0))
        mixing = int(c._param(params, 2103, 0))
        material["source_color_parameters"] = {
            "color": list(constant), "brightness": brightness,
            "texture_strength": strength, "texture_mixing": mixing,
        }
        channels = node.children(5703)
        flags = [bool(v.data) for v in channels[0].values(21)] if channels else []
        material["source_enabled_channels"] = {
            name: flags[i] for i, name in enumerate(_CHANNEL_NAMES) if i < len(flags)
        }
        shaders = {}
        for shader in node.walk(5707):
            try:
                shaders[c._identity(shader)] = shader
            except c.C4DReadError:
                # Shader data may contain an internal chunk also numbered 5707.
                pass
        source_channels = {}
        for parameter_id, (name, flag_index) in _CHANNELS.items():
            shader_alias = c._alias(params.get(parameter_id))
            if shader_alias is None or not any(shader_alias):
                continue
            record = bitmap(shaders.get(shader_alias), shader_alias)
            record["enabled"] = flags[flag_index] if flag_index < len(flags) else None
            source_channels[name] = record
        material["source_channels"] = source_channels

        color = source_channels.get("color")
        color_enabled = material["source_enabled_channels"].get("color", True)
        if color and color.get("texture") and color_enabled:
            material["base_color_texture"] = color["texture"]
            material["base_color_source"] = "linked_color_bitmap"
            if abs(brightness - 1) > 1e-8 or abs(strength - 1) > 1e-8 or mixing != 0:
                diagnostic(material, "Color bitmap is retained; nondefault C4D brightness/texture mixing is not baked.")
        else:
            rgba = [float(v) * brightness for v in constant] + [1.0]
            material["base_color_texture"] = _constant_texture(rgba, cache_dir)
            material["constant_base_color"] = rgba
            material["base_color_source"] = "constant_material_color"
            if color and color_enabled:
                material["texture_fallback"] = True
                material["base_color_source"] = "declared_material_color_fallback"
                diagnostic(material, f"Color channel {color.get('status')}; using declared material color, not a replacement bitmap.")
            elif not color_enabled:
                diagnostic(material, "C4D color channel disabled; constant material color used for base-color-only preview.")

        # These optional fields retain compatibility with existing exporters.
        # Their original channel names are still recorded above: many source
        # authors put RM/AO maps in bump or displacement slots.
        optional = {"normal_texture": [], "roughness_metallic_texture": [],
                    "ambient_occlusion_texture": [], "emissive_texture": []}
        for name, record in source_channels.items():
            path = record.get("texture")
            if not path:
                if record.get("status") != "resolved":
                    diagnostic(material, f"{name} channel: {record.get('status')}; reference retained in source_channels.")
                continue
            stem = Path(path).stem.lower()
            if name == "normal":
                optional["normal_texture"].append(path)
            if stem.endswith(("_rm", "_rma", "_orm")):
                optional["roughness_metallic_texture"].append(path)
            if stem.endswith(("_ao", "_occlusion")):
                optional["ambient_occlusion_texture"].append(path)
            if "emmisive" in stem or "emissive" in stem:
                optional["emissive_texture"].append(path)
        for key, paths in optional.items():
            paths = list(dict.fromkeys(paths))
            if len(paths) == 1:
                material[key] = paths[0]
            elif paths:
                diagnostic(material, f"Multiple {key} maps retained in source_channels; no single map selected.")
        material["preview_effects_not_applied"] = [
            name for name, enabled in material["source_enabled_channels"].items()
            if enabled and name != "color"
        ]

    neutral = {"name": "__unassigned_source_material__", "texture_fallback": True,
               "base_color_source": "unassigned_source_polygon_neutral_fallback",
               "constant_base_color": [0.65, 0.65, 0.65, 1.0],
               "base_color_texture": _constant_texture((0.65, 0.65, 0.65, 1), cache_dir),
               "extraction_warnings": ["Source polygon has no resolving material assignment; neutral preview fallback only."]}
    aliases[None] = aliases[b"\0" * 8] = len(materials)
    materials.append(neutral)
    return materials, aliases
