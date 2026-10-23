package com.example.examplemod.zones.client.visual;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapVisualStyle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Supplier;

/** Display-only blue-zone effects. It never ticks a match, loads terrain, or applies damage. */
public final class BlueZoneVisuals {
    private static final RenderType CURTAIN = overlayType("tacz_pubg_blue_zone_curtain");
    // Draw lightning after the blue tint. Sorting coplanar blue and white quads in one batch
    // otherwise sometimes puts the tint over a bolt and greatly reduces its contrast.
    private static final RenderType LIGHTNING = overlayType("tacz_pubg_blue_zone_lightning");
    private static final RenderType CURTAIN_LOD = overlayType("tacz_pubg_blue_zone_curtain_lod", BlueZoneShaders::lodShader);
    private static final RenderType LIGHTNING_LOD = overlayType("tacz_pubg_blue_zone_lightning_lod", BlueZoneShaders::lodShader);
    private static final double WALL_HALF_HEIGHT = 192;
    private static final double BOLT_CELL_HEIGHT = 64;
    private static final double LIGHTNING_DETAIL_DISTANCE = 384;

    private static RenderType overlayType(String name) {
        return overlayType(name, BlueZoneShaders::shader);
    }

    private static RenderType overlayType(String name, Supplier<ShaderInstance> shader) {
        return RenderType.create(name, DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 32_768, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(shader))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setOutputState(RenderStateShard.MAIN_TARGET)
                    .createCompositeState(false));
    }
    private static Supplier<MapZoneModel.MapDefinition> mapSource = () -> null;
    private static boolean registered;
    private static MapZoneModel.Circle cachedCircle;
    private static BlueZoneVisualGeometry.Rectangle cachedBounds;
    private static Vec3 cachedCamera;
    private static List<BlueZoneVisualGeometry.Segment> cachedCurtain = List.of();

    private BlueZoneVisuals() {}

    /** Call once from the client entrypoint, e.g. register(MapOverlayClient::current). */
    public static void register(Supplier<MapZoneModel.MapDefinition> currentMap) {
        mapSource = Objects.requireNonNull(currentMap, "currentMap");
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(BlueZoneVisuals::renderWorld);
        NeoForge.EVENT_BUS.addListener(BlueZoneVisuals::renderHud);
        NeoForge.EVENT_BUS.addListener(BlueZoneVisuals::beginFrame);
    }

    private static void beginFrame(RenderFrameEvent.Pre event) {
        VoxyBlueZoneDepth.beginFrame(currentMap() != null);
    }

    private static MapZoneModel.MapDefinition currentMap() {
        Minecraft mc = Minecraft.getInstance();
        MapZoneModel.MapDefinition map = mapSource.get();
        if (mc.level == null || map == null || map.safeZone() == null
                || !mc.level.dimension().location().equals(map.dimension())) return null;
        return map;
    }

    private static BlueZoneVisualGeometry.Rectangle rectangle(MapZoneModel.MapDefinition map) {
        return new BlueZoneVisualGeometry.Rectangle(map.minX(), map.minZ(), map.maxX(), map.maxZ());
    }

    private static void renderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        var lod = VoxyBlueZoneDepth.take(mc.level, camera);
        MapZoneModel.MapDefinition map = currentMap();
        if (map == null) {
            cachedCircle = null;
            cachedBounds = null;
            cachedCamera = null;
            cachedCurtain = List.of();
            return;
        }
        var bounds = rectangle(map);
        // Circle geometry is independent of terrain render distance and unrelated map metadata.
        if (!Objects.equals(cachedCircle, map.safeZone()) || !Objects.equals(cachedBounds, bounds)
                || cachedCamera == null || Math.hypot(camera.x - cachedCamera.x, camera.z - cachedCamera.z) > 2) {
            var circle = map.safeZone();
            cachedCurtain = BlueZoneVisualGeometry.curtain(circle.centerX(), circle.centerZ(), circle.radius(),
                    bounds, camera.x, camera.z, BlueZoneVisualGeometry.MAX_DETAIL_DISTANCE);
            cachedCircle = circle;
            cachedBounds = bounds;
            cachedCamera = camera;
        }
        if (cachedCurtain.isEmpty() || BlueZoneShaders.shader() == null) return;
        ShaderInstance lodShader = BlueZoneShaders.lodShader();
        boolean useLod = lod != null && lodShader != null;
        RenderType curtain = useLod ? CURTAIN_LOD : CURTAIN;
        RenderType lightning = useLod ? LIGHTNING_LOD : LIGHTNING;
        if (useLod) {
            lodShader.setSampler("LodDepth", lod.textureId());
            lodShader.safeGetUniform("LodTransform").set(lod.transform());
            lodShader.safeGetUniform("MainTargetSize").set((float) mc.getMainRenderTarget().viewWidth,
                    (float) mc.getMainRenderTarget().viewHeight);
        }
        MapVisualStyle style = map.visualStyle();
        double ticks = (mc.level.getGameTime() % 100_000) + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        // AFTER_LEVEL follows the final scene composite. Supply this event's exact matrices,
        // use a normal RenderType, and restore matrices/color plus the borrowed LOD texture unit.
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        float[] previousColor = RenderSystem.getShaderColor().clone();
        var view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.identity().mul(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f(event.getProjectionMatrix()), VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        mc.getMainRenderTarget().bindWrite(false);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        try (var textureBinding = useLod ? new VoxyBlueZoneDepth.TextureBinding() : null) {
            PoseStack poses = new PoseStack();
            VertexConsumer vertices = buffers.getBuffer(curtain);
            float opacity = (float) style.blueWorldOpacity();
            for (var segment : cachedCurtain) {
                double x1 = segment.start().x(), z1 = segment.start().z();
                double x2 = segment.end().x(), z2 = segment.end().z();
                // The world's frustum includes its terrain far plane. GPU side/near clipping
                // remains active; only this blue-zone shader admits the distant circumference.
                ribbon(vertices, poses, camera, x1, z1, x2, z2, -WALL_HALF_HEIGHT, -96, 0, opacity, style.blueZoneRgb());
                ribbon(vertices, poses, camera, x1, z1, x2, z2, -96, 96, opacity, opacity, style.blueZoneRgb());
                ribbon(vertices, poses, camera, x1, z1, x2, z2, 96, WALL_HALF_HEIGHT, opacity, 0, style.blueZoneRgb());
            }
            buffers.endBatch(curtain);
            vertices = buffers.getBuffer(lightning);
            int bolts = 0;
            HashSet<Long> usedBoltSeeds = new HashSet<>();
            for (var segment : cachedCurtain) {
                double x1 = segment.start().x(), z1 = segment.start().z();
                double x2 = segment.end().x(), z2 = segment.end().z();
                // Distant arcs retain their curtain but do not spend vertices on tiny animated bolts.
                if (Math.hypot((x1 + x2) / 2 - camera.x, (z1 + z2) / 2 - camera.z) > LIGHTNING_DETAIL_DISTANCE) continue;
                long seed = hash((long) Math.floor((x1 + x2) / 16), (long) Math.floor((z1 + z2) / 16));
                if (bolts < 16 && ((seed & 3) == 0 || bolts == 0) && usedBoltSeeds.add(seed)
                        && Math.hypot(x2 - x1, z2 - z1) >= .8) {
                    worldBolt(vertices, poses, camera, segment, ticks, seed, style);
                    bolts++;
                }
            }
            buffers.endBatch(lightning);
        } finally {
            if (useLod) lodShader.setSampler("LodDepth", null);
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
        }
    }

    private static void ribbon(VertexConsumer out, PoseStack poses, Vec3 camera,
            double x1, double z1, double x2, double z2, double y1, double y2,
            float lowerAlpha, float upperAlpha, int rgb) {
        float red = ((rgb >> 16) & 255) / 255f, green = ((rgb >> 8) & 255) / 255f, blue = (rgb & 255) / 255f;
        var pose = poses.last();
        out.addVertex(pose, (float) (x1 - camera.x), (float) y1, (float) (z1 - camera.z)).setColor(red, green, blue, lowerAlpha);
        out.addVertex(pose, (float) (x2 - camera.x), (float) y1, (float) (z2 - camera.z)).setColor(red, green, blue, lowerAlpha);
        out.addVertex(pose, (float) (x2 - camera.x), (float) y2, (float) (z2 - camera.z)).setColor(red, green, blue, upperAlpha);
        out.addVertex(pose, (float) (x1 - camera.x), (float) y2, (float) (z1 - camera.z)).setColor(red, green, blue, upperAlpha);
    }

    private static void worldBolt(VertexConsumer out, PoseStack poses, Vec3 camera,
            BlueZoneVisualGeometry.Segment segment,
            double ticks, long seed, MapVisualStyle style) {
        double dx = segment.end().x() - segment.start().x(), dz = segment.end().z() - segment.start().z();
        double length = Math.hypot(dx, dz);
        if (length < .8) return;
        double tx = dx / length, tz = dz / length;
        double centerX = (segment.start().x() + segment.end().x()) / 2;
        double centerZ = (segment.start().z() + segment.end().z()) / 2;
        double distance = Math.hypot(centerX - camera.x, centerZ - camera.z);
        double halfWidth = Math.min(.16, .035 + distance * .0006);
        double swing = Math.min(1.4, Math.max(.2, length * .28));
        float intensity = (float) BlueZoneVisualGeometry.lightningStrength(ticks, seed);
        // Zero world opacity also hides the corresponding lightning; this is useful for previews.
        float visibility = (float) Math.min(1, style.blueWorldOpacity() / .34);
        long firstCell = (long) Math.floor((camera.y - 128) / BOLT_CELL_HEIGHT);
        for (long cell = firstCell; cell < firstCell + 5; cell++) {
            double previousOffset = noise(seed, cell * 8) * swing;
            for (int i = 1; i <= 8; i++) {
                double offset = noise(seed, cell * 8 + i) * swing;
                double ax = centerX + tx * previousOffset, az = centerZ + tz * previousOffset;
                double bx = centerX + tx * offset, bz = centerZ + tz * offset;
                // Y is world-anchored, so crouching and falling do not drag lightning with the eye.
                double ya = cell * BOLT_CELL_HEIGHT + (i - 1) * 8, yb = ya + 8;
                boltQuad(out, poses, camera, ax, az, bx, bz, ya, yb,
                        tx, tz, halfWidth * 3, style.blueZoneRgb(), .24f * intensity * visibility);
                boltQuad(out, poses, camera, ax, az, bx, bz, ya, yb,
                        tx, tz, halfWidth, style.lightningRgb(), .80f * intensity * visibility);
                if (i == 3 || i == 6) {
                    double fork = noise(seed + 17, cell * 8 + i) * swing;
                    boltQuad(out, poses, camera, ax, az, ax + tx * fork, az + tz * fork,
                            ya, ya - 4, tx, tz, halfWidth * .6, style.lightningRgb(), .48f * intensity * visibility);
                }
                previousOffset = offset;
            }
        }
    }

    private static void boltQuad(VertexConsumer out, PoseStack poses, Vec3 camera,
            double ax, double az, double bx, double bz,
            double ya, double yb, double tx, double tz, double halfWidth, int rgb, float alpha) {
        float red = ((rgb >> 16) & 255) / 255f, green = ((rgb >> 8) & 255) / 255f, blue = (rgb & 255) / 255f;
        var pose = poses.last();
        out.addVertex(pose, (float) (ax - tx * halfWidth - camera.x), (float) (ya - camera.y),
                (float) (az - tz * halfWidth - camera.z)).setColor(red, green, blue, alpha);
        out.addVertex(pose, (float) (ax + tx * halfWidth - camera.x), (float) (ya - camera.y),
                (float) (az + tz * halfWidth - camera.z)).setColor(red, green, blue, alpha);
        out.addVertex(pose, (float) (bx + tx * halfWidth - camera.x), (float) (yb - camera.y),
                (float) (bz + tz * halfWidth - camera.z)).setColor(red, green, blue, alpha);
        out.addVertex(pose, (float) (bx - tx * halfWidth - camera.x), (float) (yb - camera.y),
                (float) (bz - tz * halfWidth - camera.z)).setColor(red, green, blue, alpha);
    }

    private static void renderHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        MapZoneModel.MapDefinition map = currentMap();
        if (map == null || mc.options.hideGui || mc.screen != null) return;
        // Camera, not the player's feet: spectator cameras and third-person views use their actual zone.
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        if (!map.isBlue(mc.level.dimension().location(), camera.x, camera.z)) return;
        GuiGraphics graphics = event.getGuiGraphics();
        int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
        int thickness = Math.max(1, (int) Math.ceil(Math.min(width, height) * .14));
        MapVisualStyle style = map.visualStyle();
        // Thin nested rings are non-overlapping: corners do not accumulate opacity.
        int bands = Math.min(24, thickness);
        for (int i = 0; i < bands; i++) {
            int outer = thickness * i / bands, inner = thickness * (i + 1) / bands;
            double strength = 1 - (i + .5) / bands;
            int color = MapVisualStyle.argb(style.blueZoneRgb(), style.blueScreenOpacity() * strength * strength);
            graphics.fill(outer, outer, width - outer, inner, color);
            graphics.fill(outer, height - inner, width - outer, height - outer, color);
            graphics.fill(outer, inner, inner, height - inner, color);
            graphics.fill(width - inner, inner, width - outer, height - inner, color);
        }
        double ticks = (mc.level.getGameTime() % 100_000) + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for (int edge = 0; edge < 4; edge++) for (int index = 0; index < 2; index++) {
            screenBolt(graphics, width, height, thickness, edge, index, ticks, map.id(), style);
        }
    }

    private static void screenBolt(GuiGraphics graphics, int width, int height, int thickness,
            int edge, int index, double ticks, long mapId, MapVisualStyle style) {
        long seed = hash(mapId + edge * 7L, index + 31L);
        double visibility = Math.min(1, style.blueScreenOpacity() / .26);
        int color = MapVisualStyle.argb(style.lightningRgb(), .55 * visibility
                * BlueZoneVisualGeometry.lightningStrength(ticks, seed));
        double length = (edge < 2 ? width : height) * .17;
        double start = (edge < 2 ? width : height) * (.18 + .48 * index);
        int px = 0, py = 0;
        for (int i = 0; i <= 7; i++) {
            double along = start + length * i / 7;
            double inset = thickness * (.22 + .12 * noise(seed, i));
            int x = switch (edge) { case 0, 1 -> (int) along; case 2 -> (int) inset; default -> width - 1 - (int) inset; };
            int y = switch (edge) { case 0 -> (int) inset; case 1 -> height - 1 - (int) inset; default -> (int) along; };
            if (i > 0) screenLine(graphics, px, py, x, y, color);
            px = x; py = y;
        }
    }

    private static void screenLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        // One rotated GUI quad per segment, rather than hundreds of per-pixel fill calls.
        graphics.pose().pushPose();
        graphics.pose().translate(x1, y1, 0);
        graphics.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(y2 - y1, x2 - x1)));
        graphics.fill(0, 0, Math.max(1, (int) Math.ceil(Math.hypot(x2 - x1, y2 - y1))), 1, color);
        graphics.pose().popPose();
    }

    private static long hash(long x, long z) {
        long value = x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        return value ^ (value >>> 27);
    }

    private static double noise(long seed, long index) { return ((hash(seed, index) & 65535) / 32767.5) - 1; }
}
