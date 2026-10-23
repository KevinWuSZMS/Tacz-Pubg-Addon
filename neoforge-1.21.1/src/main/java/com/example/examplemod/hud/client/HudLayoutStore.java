package com.example.examplemod.hud.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Personal client file only. This model never enters a world document or server payload. */
public final class HudLayoutStore {
    public static final int VERSION = 2;
    public record Layout(Map<String, HudLayoutModel.Position> positions, boolean nativeMapResetPending) {
        public Layout { positions = independentPositions(positions); }
    }
    private HudLayoutStore() {}
    /** One explicit reset for pre-v2 installs, including installs with only a native Xaero profile. */
    public static Layout loadForClient(Path path) throws IOException {
        if (!Files.exists(path)) {
            var fresh = new Layout(Map.of(), true);
            write(path, fresh);
            return fresh;
        }
        var json = readJson(path);
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsInt() : 1;
            if (version == VERSION) return new Layout(decode(json), root.has("nativeMapResetPending")
                    && root.get("nativeMapResetPending").getAsBoolean());
            if (version != 1) throw new IllegalArgumentException("HUD version");
            // Preserve the exact old file before replacing every old HUD coordinate.
            Path backup = path.resolveSibling(path.getFileName() + ".pre-v2." + java.util.UUID.randomUUID() + ".bak");
            Files.copy(path, backup);
            var reset = new Layout(Map.of(), true);
            write(path, reset);
            return reset;
        } catch (RuntimeException invalid) { throw new IOException("Invalid HUD layout", invalid); }
    }
    public static Map<String, HudLayoutModel.Position> read(Path path) throws IOException {
        if (!Files.exists(path)) return Map.of();
        try { return decode(readJson(path)); }
        catch (RuntimeException invalid) { throw new IOException("Invalid HUD layout", invalid); }
    }
    private static String readJson(Path path) throws IOException {
        if (Files.size(path) > 65_536) throw new IOException("HUD layout file is too large");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
    static Map<String, HudLayoutModel.Position> decode(String json) {
        var root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("version") || (root.get("version").getAsInt() != 1
                && root.get("version").getAsInt() != VERSION)) throw new IllegalArgumentException("HUD version");
        var positions = root.getAsJsonObject("positions");
        if (positions == null || positions.size() > 128) throw new IllegalArgumentException("HUD positions");
        var result = new LinkedHashMap<String, HudLayoutModel.Position>();
        positions.entrySet().forEach(entry -> {
            if (!entry.getKey().matches("[a-z0-9_]{1,64}")) throw new IllegalArgumentException("HUD identifier");
            var point = entry.getValue().getAsJsonObject();
            result.put(entry.getKey(), new HudLayoutModel.Position(point.get("x").getAsDouble(), point.get("y").getAsDouble()));
        });
        return Map.copyOf(result);
    }
    public static void write(Path path, Map<String, HudLayoutModel.Position> positions) throws IOException {
        write(path, new Layout(positions, false));
    }
    /** Map children never have independent persisted offsets. */
    static Map<String, HudLayoutModel.Position> independentPositions(Map<String, HudLayoutModel.Position> positions) {
        var result = new LinkedHashMap<>(positions);
        result.remove("match_phase"); result.remove("weapon_stack");
        return Map.copyOf(result);
    }
    public static void write(Path path, Layout layout) throws IOException {
        var root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("nativeMapResetPending", layout.nativeMapResetPending());
        var values = new JsonObject();
        layout.positions().forEach((id, position) -> {
            var point = new JsonObject();
            point.addProperty("x", position.x());
            point.addProperty("y", position.y());
            values.add(id, point);
        });
        root.add("positions", values);
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n", StandardCharsets.UTF_8);
        try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unavailable) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
