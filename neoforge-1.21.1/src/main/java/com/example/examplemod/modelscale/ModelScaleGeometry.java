package com.example.examplemod.modelscale;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Numeric-only source geometry for server placement and pickup; never loads client render classes. */
public final class ModelScaleGeometry {
    private static final Map<String, AABB> CACHE = new HashMap<>();
    private ModelScaleGeometry() {}
    public static AABB baseGroundBounds(ItemStack stack, Level level) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!ModelScaleSettings.supported(id)) return null;
        String style = ModelScaleService.style(level);
        return CACHE.computeIfAbsent(style + "/" + id, ignored -> load(id, style.equals(ModelScaleSettings.PIXEL)));
    }
    static AABB load(String id, boolean lowPoly) {
        String name = id.substring(id.indexOf(':') + 1);
        try {
            JsonObject model = read("models/item/" + name + ".json");
            if (model == null) return fallback();
            Matrix4f transform = new Matrix4f();
            JsonObject ground = model.has("display") ? model.getAsJsonObject("display").getAsJsonObject("ground") : null;
            if (ground != null) {
                Vector3f rotation = vector(ground, "rotation", 0).mul((float) (Math.PI / 180));
                transform.rotate(new Quaternionf().rotationXYZ(rotation.x, rotation.y, rotation.z));
                transform.scale(vector(ground, "scale", 1));
            }
            transform.translate(-.5f, -.5f, -.5f);
            double[] limits = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            boolean measuredMesh = !lowPoly && includeMesh(name, limits, transform);
            if (!measuredMesh && model.has("elements")) {
                for (var element : model.getAsJsonArray("elements")) {
                    var object = element.getAsJsonObject();
                    Vector3f from = vector(object, "from", 0).div(16), to = vector(object, "to", 0).div(16);
                    Matrix4f elementTransform = new Matrix4f(transform);
                    if (object.has("rotation")) {
                        var rotation = object.getAsJsonObject("rotation");
                        Vector3f origin = vector(rotation, "origin", 8).div(16);
                        float angle = rotation.get("angle").getAsFloat() * (float) (Math.PI / 180);
                        elementTransform.translate(origin);
                        switch (rotation.get("axis").getAsString()) {
                            case "x" -> elementTransform.rotateX(angle);
                            case "y" -> elementTransform.rotateY(angle);
                            case "z" -> elementTransform.rotateZ(angle);
                            default -> throw new IllegalArgumentException("Unknown element rotation axis");
                        }
                        elementTransform.translate(-origin.x, -origin.y, -origin.z);
                    }
                    for (int corner = 0; corner < 8; corner++) include(limits, elementTransform,
                            (corner & 1) == 0 ? from.x : to.x, (corner & 2) == 0 ? from.y : to.y,
                            (corner & 4) == 0 ? from.z : to.z);
                }
            }
            double w = limits[3] - limits[0], h = limits[4] - limits[1], d = limits[5] - limits[2];
            if (!Double.isFinite(w + h + d) || w <= 0 || h <= 0 || d <= 0) return fallback();
            // Exactly the same thin-axis choice and floor recentering as GroundLootFlatPose.
            if (h > Math.min(w, Math.min(h, d)) * 1.05) {
                if (w <= d) { double previous = w; w = h; h = previous; }
                else { double previous = h; h = d; d = previous; }
            }
            return new AABB(-w / 2, 0, -d / 2, w / 2, Math.max(.004, h), d / 2);
        } catch (Exception error) {
            com.mojang.logging.LogUtils.getLogger().warn("Unable to measure model {} for ground placement", id, error);
            return fallback();
        }
    }
    private static AABB fallback() { return new AABB(-.25, 0, -.25, .25, .4, .25); }
    private static JsonObject read(String relative) throws java.io.IOException {
        try (var stream = ModelScaleGeometry.class.getResourceAsStream("/assets/tacz_pubg/" + relative)) {
            if (stream == null) return null;
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static boolean includeMesh(String name, double[] limits, Matrix4f transform) throws java.io.IOException {
        try (var stream = ModelScaleGeometry.class.getResourceAsStream("/assets/tacz_pubg/meshes/item/" + name + ".json")) {
            if (stream == null) return false;
            // Stream the packed array: bounds need only XYZ, not a second in-memory copy of UVs/normals.
            try (var reader = new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                reader.beginObject();
                while (reader.hasNext()) {
                    if (!reader.nextName().equals("vertices")) { reader.skipValue(); continue; }
                    reader.beginArray(); int count = 0; float x = 0, y = 0;
                    while (reader.hasNext()) {
                        double number = reader.nextDouble();
                        if (!Double.isFinite(number) || count > 2_400_000) throw new IllegalArgumentException("Invalid source geometry");
                        switch (count++ % 8) {
                            case 0 -> x = (float) number / 16;
                            case 1 -> y = (float) number / 16;
                            case 2 -> include(limits, transform, x, y, (float) number / 16);
                            default -> { }
                        }
                    }
                    reader.endArray();
                    return count > 0 && count % 24 == 0;
                }
            }
            return false;
        }
    }
    private static Vector3f vector(JsonObject object, String key, float fallback) {
        if (!object.has(key)) return new Vector3f(fallback);
        var values = object.getAsJsonArray(key);
        return new Vector3f(values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat());
    }
    private static void include(double[] limits, Matrix4f transform, float x, float y, float z) {
        Vector3f p = transform.transformPosition(new Vector3f(x, y, z));
        limits[0] = Math.min(limits[0], p.x); limits[1] = Math.min(limits[1], p.y); limits[2] = Math.min(limits[2], p.z);
        limits[3] = Math.max(limits[3], p.x); limits[4] = Math.max(limits[4], p.y); limits[5] = Math.max(limits[5], p.z);
    }
}
