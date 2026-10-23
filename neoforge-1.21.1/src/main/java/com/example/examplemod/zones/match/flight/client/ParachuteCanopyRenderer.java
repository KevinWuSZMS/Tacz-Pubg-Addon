package com.example.examplemod.zones.match.flight.client;

import com.example.examplemod.originalicons.OriginalIconState;
import com.example.examplemod.zones.match.flight.ParachuteItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** The authored C4D canopy and suspension lines, drawn only during an active deployment. */
final class ParachuteCanopyRenderer {
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(
            "tacz_pubg", "meshes/entity/parachute_canopy.json");
    private static final ResourceLocation PIXEL_MODEL = ResourceLocation.fromNamespaceAndPath(
            "tacz_pubg", "meshes/entity/parachute_canopy_pixel.json");
    private static final double HARNESS_HEIGHT = 1.25;
    private record Mesh(RenderType type, float[] vertices, int primitiveVertices,
            float[][] ropeLods, double radius, double minY, double maxY) {}
    private static Optional<Mesh> cachedDetailed;
    private static Optional<Mesh> cachedPixel;

    private ParachuteCanopyRenderer() {}

    static void register(IEventBus bus) {
        bus.addListener((RegisterClientReloadListenersEvent event) ->
                event.registerReloadListener((ResourceManagerReloadListener) manager -> {
                    cachedDetailed = null;
                    cachedPixel = null;
                }));
        NeoForge.EVENT_BUS.addListener(ParachuteCanopyRenderer::render);
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        // A world pass also draws the local canopy when looking up in first person.
        // Its own bounds keep the canopy visible when the wearer is below the camera's view.
        var camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Mesh mesh = null;
        var buffers = mc.renderBuffers().bufferSource();
        for (var player : mc.level.players()) {
            if (!player.isAlive() || player.isSpectator() || player.isInvisible()
                    || player.onGround() || player.isInWater() || player.isInLava() || player.isPassenger()
                    || !ParachuteItem.isEquipped(player) || FlightClient.renderStage(player) != 3
                    || !player.shouldRender(camera.x, camera.y, camera.z)) continue;
            var selected = meshForStyle();
            if (selected.isEmpty()) return;
            mesh = selected.get();
            double x = Mth.lerp(partial, player.xo, player.getX());
            double y = Mth.lerp(partial, player.yo, player.getY()) + HARNESS_HEIGHT;
            double z = Mth.lerp(partial, player.zo, player.getZ());
            if (!event.getFrustum().isVisible(new AABB(x - mesh.radius, y + mesh.minY, z - mesh.radius,
                    x + mesh.radius, y + mesh.maxY, z + mesh.radius))) continue;
            var pose = event.getPoseStack();
            pose.pushPose();
            try {
                pose.translate(x - camera.x, y - camera.y, z - camera.z);
                pose.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(partial, player.yRotO, player.getYRot())));
                int light = mc.getEntityRenderDispatcher().getPackedLightCoords(player, partial);
                var out = buffers.getBuffer(mesh.type);
                draw(mesh.vertices, mesh.primitiveVertices, pose.last(), out, light);
                if (mesh.ropeLods != null) {
                    double dx = x - camera.x, dy = y - HARNESS_HEIGHT - camera.y, dz = z - camera.z;
                    int lod = ParachuteRopeLod.select(player == mc.player, dx * dx + dy * dy + dz * dz);
                    // Arrays are built once at resource load; discarded lines are never traversed.
                    if (lod < ParachuteRopeLod.HIDDEN) draw(mesh.ropeLods[lod], 4, pose.last(), out, light);
                }
            } finally { pose.popPose(); }
        }
        if (mesh != null) buffers.endBatch(mesh.type);
    }

    private static void draw(float[] data, int primitiveVertices, PoseStack.Pose pose, VertexConsumer out, int light) {
        // Keep pixel quads intact; repeat the last corner only for detailed triangles.
        for (int primitive = 0; primitive < data.length; primitive += primitiveVertices * 8) {
            for (int vertex = 0; vertex < 4; vertex++) {
                int at = primitive + Math.min(vertex, primitiveVertices - 1) * 8;
                out.addVertex(pose.pose(), data[at], data[at + 1], data[at + 2])
                        .setColor(-1).setUv(data[at + 3], data[at + 4])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                        .setNormal(pose, data[at + 5], data[at + 6], data[at + 7]);
            }
        }
    }

    private static Optional<Mesh> meshForStyle() {
        if (OriginalIconState.lowPolyModels()) {
            if (cachedPixel == null) cachedPixel = load(PIXEL_MODEL, true);
            return cachedPixel;
        }
        if (cachedDetailed == null) cachedDetailed = load(MODEL, false);
        return cachedDetailed;
    }

    private static Optional<Mesh> load(ResourceLocation model, boolean pixel) {
        var manager = Minecraft.getInstance().getResourceManager();
        var resource = manager.getResource(model);
        if (resource.isEmpty()) return Optional.empty();
        try (var reader = resource.get().openAsReader()) {
            var object = JsonParser.parseReader(reader).getAsJsonObject();
            if (!object.get("coordinate_units").getAsString().equals("blocks"))
                throw new IllegalArgumentException("Canopy mesh must use block coordinates");
            String primitive = object.has("primitive") ? object.get("primitive").getAsString() : "triangles";
            if (!primitive.equals(pixel ? "quads" : "triangles"))
                throw new IllegalArgumentException("Unexpected canopy primitive type");
            int primitiveVertices = pixel ? 4 : 3;
            int faceBudget = pixel ? 2_400 : 6_000;
            var texture = ResourceLocation.parse(object.get("texture").getAsString());
            if (manager.getResource(texture).isEmpty()) throw new IllegalArgumentException("Missing canopy texture");
            float[] vertices = readVertices(object.getAsJsonArray("vertices"), primitiveVertices, faceBudget, false);
            float[][] ropeLods = null;
            if (pixel && object.has("rope_lods")) {
                var levels = object.getAsJsonArray("rope_lods");
                int[] distances = {ParachuteRopeLod.NEAR_DISTANCE, ParachuteRopeLod.MEDIUM_DISTANCE,
                        ParachuteRopeLod.FAR_DISTANCE};
                if (levels.size() != distances.length) throw new IllegalArgumentException("Invalid canopy rope LOD count");
                ropeLods = new float[distances.length][];
                int remaining = faceBudget - vertices.length / 32;
                int previous = Integer.MAX_VALUE;
                for (int i = 0; i < distances.length; i++) {
                    var level = levels.get(i).getAsJsonObject();
                    if (level.get("max_distance").getAsInt() != distances[i])
                        throw new IllegalArgumentException("Invalid canopy rope LOD distance");
                    ropeLods[i] = readVertices(level.getAsJsonArray("vertices"), 4, remaining, true);
                    if (ropeLods[i].length > previous) throw new IllegalArgumentException("Rope LOD must reduce geometry");
                    previous = ropeLods[i].length;
                }
            }
            // A rope may be in view while the cloth is above the screen. Bounds
            // therefore include the full near mesh even when the bottom block is absent.
            var bounds = ParachuteRopeLod.bounds(vertices, ropeLods == null ? null : ropeLods[0]);
            return Optional.of(new Mesh(RenderType.entityCutoutNoCull(texture), vertices, primitiveVertices, ropeLods,
                    bounds.radius(), bounds.minY(), bounds.maxY()));
        } catch (Exception error) {
            com.mojang.logging.LogUtils.getLogger().error("Invalid deployed parachute model {}", model, error);
            return Optional.empty();
        }
    }

    private static float[] readVertices(JsonArray array, int primitiveVertices, int faceBudget, boolean allowEmpty) {
        if ((!allowEmpty && array.isEmpty()) || array.size() % (primitiveVertices * 8) != 0
                || array.size() > faceBudget * primitiveVertices * 8)
            throw new IllegalArgumentException("Invalid canopy mesh size");
        float[] vertices = new float[array.size()];
        for (int i = 0; i < vertices.length; i++) {
            vertices[i] = array.get(i).getAsFloat();
            if (!Float.isFinite(vertices[i])) throw new IllegalArgumentException("Non-finite canopy vertex");
        }
        return vertices;
    }
}
