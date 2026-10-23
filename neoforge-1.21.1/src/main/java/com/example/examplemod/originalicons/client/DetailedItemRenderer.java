package com.example.examplemod.originalicons.client;

import com.example.examplemod.originalicons.OriginalIconState;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Curved, authored meshes share the vanilla item's transforms and its normal gameplay renderer. */
public final class DetailedItemRenderer {
    public record WearableGeometry(AABB bounds, double backPanelZ) {}
    private record Mesh(ResourceLocation texture, float[] vertices, WearableGeometry geometry, Map<BakedModel, AABB> groundBounds) {}
    private static final Map<ResourceLocation, Optional<Mesh>> CACHE = new HashMap<>();
    private DetailedItemRenderer() {}
    public static void clear() { CACHE.clear(); }
    /** Untransformed item geometry, centered exactly as ItemRenderer draws the NONE context. */
    public static Optional<AABB> modelBounds(ItemStack stack) {
        return wearableGeometry(stack).map(WearableGeometry::bounds);
    }
    public static Optional<WearableGeometry> wearableGeometry(ItemStack stack) {
        return mesh(stack).map(Mesh::geometry);
    }
    public static boolean render(ItemStack stack, ItemDisplayContext context, boolean left,
            PoseStack pose, MultiBufferSource buffers, int light, int overlay, BakedModel model) {
        var mesh = mesh(stack);
        if (mesh.isEmpty()) return false;
        pose.pushPose();
        try {
            net.neoforged.neoforge.client.ClientHooks.handleCameraTransforms(pose, model, context, left);
            pose.translate(-.5f, -.5f, -.5f);
            float[] data = mesh.get().vertices;
            var vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(mesh.get().texture));
            // The renderer uses quads; duplicate the last vertex of each triangle without adding geometry.
            for (int triangle = 0; triangle < data.length; triangle += 24) {
                for (int vertex = 0; vertex < 4; vertex++) {
                    int base = triangle + Math.min(vertex, 2) * 8;
                    vertices.addVertex(pose.last().pose(), data[base] / 16f, data[base + 1] / 16f, data[base + 2] / 16f)
                            .setColor(-1).setUv(data[base + 3], data[base + 4]).setOverlay(overlay).setLight(light)
                            .setNormal(pose.last(), data[base + 5], data[base + 6], data[base + 7]);
                }
            }
        } finally { pose.popPose(); }
        return true;
    }
    /** Measure the geometry actually drawn, once for each mesh/model pair, after its GROUND transform. */
    public static Optional<AABB> groundBounds(ItemStack stack, BakedModel model) {
        return mesh(stack).map(mesh -> mesh.groundBounds.computeIfAbsent(model, baked -> {
            PoseStack poses = new PoseStack();
            net.neoforged.neoforge.client.ClientHooks.handleCameraTransforms(poses, baked, ItemDisplayContext.GROUND, false);
            poses.translate(-.5f, -.5f, -.5f);
            return transformedBounds(mesh.vertices, poses.last().pose());
        }));
    }
    private static Optional<Mesh> mesh(ItemStack stack) {
        if (OriginalIconState.lowPolyModels() || stack.isEmpty()) return Optional.empty();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!id.getNamespace().equals("tacz_pubg")) return Optional.empty();
        return CACHE.computeIfAbsent(id, DetailedItemRenderer::load);
    }
    static AABB transformedBounds(float[] vertices, Matrix4f transform) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        Vector3f point = new Vector3f();
        for (int at = 0; at < vertices.length; at += 8) {
            point.set(vertices[at] / 16f, vertices[at + 1] / 16f, vertices[at + 2] / 16f);
            transform.transformPosition(point);
            minX = Math.min(minX, point.x()); minY = Math.min(minY, point.y()); minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x()); maxY = Math.max(maxY, point.y()); maxZ = Math.max(maxZ, point.z());
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
    static WearableGeometry wearableGeometry(float[] vertices) {
        AABB bounds = transformedBounds(vertices, new Matrix4f().translate(-.5f, -.5f, -.5f));
        // The source backpacks have +Z shoulder straps. Find their broad central +Z-facing padded
        // panel, excluding side straps, the top handle and lower buckles. Anchoring by maxZ instead
        // would put the entire shoulder loop behind the torso and leave the bag floating in space.
        record Surface(double z, double area) {}
        var panels = new ArrayList<Surface>();
        double totalArea = 0, centerX = (bounds.minX + bounds.maxX) * .5;
        for (int at = 0; at < vertices.length; at += 24) {
            double x = (vertices[at] + vertices[at + 8] + vertices[at + 16]) / 48.0 - .5;
            double y = (vertices[at + 1] + vertices[at + 9] + vertices[at + 17]) / 48.0 - .5;
            if (Math.abs(x - centerX) >= bounds.getXsize() * .2 || y <= bounds.minY + bounds.getYsize() * .25
                    || y >= bounds.minY + bounds.getYsize() * .75) continue;
            double ax = vertices[at + 8] - vertices[at], ay = vertices[at + 9] - vertices[at + 1], az = vertices[at + 10] - vertices[at + 2];
            double bx = vertices[at + 16] - vertices[at], by = vertices[at + 17] - vertices[at + 1], bz = vertices[at + 18] - vertices[at + 2];
            double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            double area = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (area <= 1e-10 || nz / area <= .7) continue;
            panels.add(new Surface((vertices[at + 2] + vertices[at + 10] + vertices[at + 18]) / 48.0 - .5, area));
            totalArea += area;
        }
        panels.sort(Comparator.comparingDouble(Surface::z));
        double accumulated = 0;
        for (var surface : panels) {
            accumulated += surface.area;
            if (accumulated >= totalArea * .5) return new WearableGeometry(bounds, surface.z);
        }
        return new WearableGeometry(bounds, bounds.maxZ);
    }
    private static Optional<Mesh> load(ResourceLocation id) {
        var manager = Minecraft.getInstance().getResourceManager();
        var resource = manager.getResource(id.withPath("meshes/item/" + id.getPath() + ".json"));
        if (resource.isEmpty()) return Optional.empty();
        try (var reader = resource.get().openAsReader()) {
            var object = JsonParser.parseReader(reader).getAsJsonObject();
            var texture = ResourceLocation.parse(object.get("texture").getAsString());
            if (manager.getResource(texture).isEmpty()) return Optional.empty();
            var array = object.getAsJsonArray("vertices");
            if (array.size() == 0 || array.size() % 24 != 0 || array.size() > 2_400_000)
                throw new IllegalArgumentException("Invalid triangle mesh size");
            float[] data = new float[array.size()];
            for (int i = 0; i < data.length; i++) {
                data[i] = array.get(i).getAsFloat();
                if (!Float.isFinite(data[i])) throw new IllegalArgumentException("Non-finite mesh vertex");
            }
            return Optional.of(new Mesh(texture, data, wearableGeometry(data), new IdentityHashMap<>()));
        } catch (Exception error) {
            com.mojang.logging.LogUtils.getLogger().error("Invalid detailed model {}; using bundled low-poly fallback", id, error);
            return Optional.empty();
        }
    }
}
