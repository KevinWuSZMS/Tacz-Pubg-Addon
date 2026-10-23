package com.example.examplemod.originalicons;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact item/asset IDs only; a gun named AK does not silently become an AKM. */
public final class OriginalIconCatalog {
    public record Icon(String texture, int width, int height) {
        public Icon {
            if (!texture.startsWith("tacz_pubg:textures/pubg_original/") || !texture.endsWith(".png")
                    || texture.contains("..") || width < 1 || height < 1)
                throw new IllegalArgumentException("Invalid bundled original icon");
        }
    }
    private static final Map<String, Icon> ICONS = load();
    private OriginalIconCatalog() {}
    public static Icon find(String kind, String id) { return ICONS.get(kind + ":" + id); }
    public static Map<String, Icon> all() { return ICONS; }
    private static Map<String, Icon> load() {
        try (var stream = OriginalIconCatalog.class.getResourceAsStream("/assets/tacz_pubg/original_icons.json")) {
            if (stream == null) return Map.of();
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Icon> values = new LinkedHashMap<>();
            for (var entry : json.getAsJsonObject("icons").entrySet()) {
                var value = entry.getValue().getAsJsonObject();
                values.put(entry.getKey(), new Icon(value.get("texture").getAsString(),
                        value.get("width").getAsInt(), value.get("height").getAsInt()));
            }
            return Collections.unmodifiableMap(values);
        } catch (Exception failure) {
            // A missing optional presentation resource must never prevent joining a game.
            com.mojang.logging.LogUtils.getLogger().error("Could not load bundled PUBG inventory icons", failure);
            return Map.of();
        }
    }
}
