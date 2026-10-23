package com.example.examplemod.spawn.client;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.spawn.SpawnAreaPayloads;
import com.example.examplemod.spawn.SpawnFloorGeometry;
import com.example.examplemod.spawn.SpawnFloorBoundary;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;

/** Registered only by the client mod entry point; safe to omit on a dedicated server. */
public final class SpawnAreaClient {
    private static final double RENDER_DISTANCE = 64;
    private static final int CHUNK_RADIUS = 4;
    private static final double SURFACE_OFFSET = 0.003;
    private static final RenderType OVERLAY = RenderType.create(
            "tacz_pubg_spawn_floor_overlay", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 16_384, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
                    .setOutputState(RenderStateShard.MAIN_TARGET)
                    .createCompositeState(false));
    private static boolean registered;
    private static long outlineRevision = Long.MIN_VALUE;
    private static List<SpawnFloorBoundary.Segment> outline = List.of();

    private SpawnAreaClient() {}

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        SpawnAreaPayloads.setClientHandler(SpawnAreaClientState::accept);
        NeoForge.EVENT_BUS.addListener(SpawnAreaClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(SpawnAreaClient::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(SpawnAreaClient::onRenderGui);
        NeoForge.EVENT_BUS.addListener(SpawnAreaClient::onLogout);
        NeoForge.EVENT_BUS.addListener(SpawnAreaClient::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(SpawnAreaClient::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(SpawnAreaKeys::onInteraction);
    }

    private static boolean holdingStick() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && com.example.examplemod.spawn.SpawnAreaService.holdsTool(minecraft.player);
    }

    private static boolean shown() {
        return holdingStick() || SpawnAreaClientState.visible();
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        SpawnAreaKeys.tick();
        SpawnAreaClientState.tick(shown());
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SpawnAreaClientState.clear();
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        invalidateClientChunk(event);
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        invalidateClientChunk(event);
    }

    private static void invalidateClientChunk(ChunkEvent event) {
        if (event.getLevel() instanceof ClientLevel level && level == Minecraft.getInstance().level) {
            long key = event.getChunk().getPos().toLong();
            // A chunk load callback must not read the world, even on the client.
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().level == level) {
                    SpawnAreaClientState.invalidateChunk(key);
                }
            });
        }
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        SpawnAreaClientState.bindLevel(minecraft.level);
        if (minecraft.level == null || minecraft.player == null || !shown()) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        // AFTER_LEVEL runs after Iris's final composite; its particle targets and shader overrides
        // are no longer active. Bind the real scene target and supply THIS frame's exact view /
        // projection matrices rather than inheriting a mutable particle pose or a tick eye height.
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        float[] previousColor = RenderSystem.getShaderColor().clone();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity().mul(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f(event.getProjectionMatrix()), VertexSorting.DISTANCE_TO_ORIGIN);
        minecraft.getMainRenderTarget().bindWrite(false);
        PoseStack poses = new PoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        try {
            VertexConsumer vertices = buffers.getBuffer(OVERLAY);
            drawChunks(SpawnAreaClientState.savedChunks(), false, camera, event, poses, vertices);
            drawChunks(SpawnAreaClientState.previewChunks(), true, camera, event, poses, vertices);
            drawOutline(camera, poses, vertices);
            buffers.endBatch(OVERLAY);
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
        }
    }

    private static void drawChunks(Map<Long, Map<BlockPos, SpawnAreaClientState.FloorMesh>> chunks,
            boolean preview, Vec3 camera, RenderLevelStageEvent event, PoseStack poses, VertexConsumer vertices) {
        int cameraChunkX = net.minecraft.util.Mth.floor(camera.x) >> 4;
        int cameraChunkZ = net.minecraft.util.Mth.floor(camera.z) >> 4;
        double maximumDistance = RENDER_DISTANCE * RENDER_DISTANCE;
        for (int x = cameraChunkX - CHUNK_RADIUS; x <= cameraChunkX + CHUNK_RADIUS; x++) {
            for (int z = cameraChunkZ - CHUNK_RADIUS; z <= cameraChunkZ + CHUNK_RADIUS; z++) {
                Map<BlockPos, SpawnAreaClientState.FloorMesh> chunk = chunks.get(ChunkPos.asLong(x, z));
                if (chunk == null) {
                    continue;
                }
                for (SpawnAreaClientState.FloorMesh mesh : chunk.values()) {
                    BlockPos position = mesh.position();
                    if (mesh.surfaces().isEmpty() || position.distToCenterSqr(camera) > maximumDistance
                            || !event.getFrustum().isVisible(new AABB(position).inflate(0, 1, 0))) {
                        continue;
                    }
                    poses.pushPose();
                    // Subtract in double precision before converting vertices to floats.
                    poses.translate(position.getX() - camera.x, position.getY() - camera.y, position.getZ() - camera.z);
                    int rgb = SpawnAreaClientState.lootPalette().get((preview ? SpawnAreaClientState.selectionTier() : SpawnAreaClientState.tier(position)).id());
                    float red = ((rgb >> 16) & 255) / 255f, green = ((rgb >> 8) & 255) / 255f, blue = (rgb & 255) / 255f;
                    for (SpawnFloorGeometry.Surface surface : mesh.surfaces()) {
                        double minX = surface.minX();
                        double minZ = surface.minZ();
                        double maxX = surface.maxX();
                        double maxZ = surface.maxZ();
                        double y = surface.y() + SURFACE_OFFSET;
                        if (preview) {
                            y += SURFACE_OFFSET;
                            Map<BlockPos, SpawnAreaClientState.FloorMesh> confirmedChunk =
                                    SpawnAreaClientState.savedChunks().get(ChunkPos.asLong(position));
                            if (confirmedChunk == null || !confirmedChunk.containsKey(position)) {
                                quad(poses, vertices, minX, minZ, maxX, maxZ, y, .55f + red * .45f, .55f + green * .45f, .55f + blue * .45f, 0.22f);
                            }
                        } else {
                            quad(poses, vertices, minX, minZ, maxX, maxZ, y, red * .65f, green * .65f, blue * .65f, 0.46f);
                        }
                    }
                    poses.popPose();
                }
            }
        }
    }

    private static void drawOutline(Vec3 camera, PoseStack poses, VertexConsumer vertices) {
        if (outlineRevision != SpawnAreaClientState.geometryRevision()) {
            List<SpawnFloorBoundary.Surface> surfaces = new ArrayList<>();
            // White identifies the pending edit, including any already-blue cells in that edit.
            // Confirmed floors have no permanent white border: committing removes it immediately.
            appendBoundarySurfaces(SpawnAreaClientState.previewChunks(), surfaces);
            outline = SpawnFloorBoundary.outline(surfaces);
            outlineRevision = SpawnAreaClientState.geometryRevision();
        }
        double halfWidth = .009;
        for (var line : outline) {
            double minX = line.alongX() ? line.start() : line.fixed();
            double maxX = line.alongX() ? line.end() : line.fixed();
            double minZ = line.alongX() ? line.fixed() : line.start();
            double maxZ = line.alongX() ? line.fixed() : line.end();
            AABB bounds = new AABB(minX, line.y(), minZ, maxX, line.y(), maxZ).inflate(.02);
            if (bounds.distanceToSqr(camera) > RENDER_DISTANCE * RENDER_DISTANCE) continue;
            quad(poses, vertices, minX - camera.x - halfWidth, minZ - camera.z - halfWidth,
                    maxX - camera.x + halfWidth, maxZ - camera.z + halfWidth,
                    line.y() - camera.y + 3 * SURFACE_OFFSET, 1, 1, 1, .85f);
        }
    }

    private static void appendBoundarySurfaces(Map<Long, Map<BlockPos, SpawnAreaClientState.FloorMesh>> chunks,
            List<SpawnFloorBoundary.Surface> surfaces) {
        for (var chunk : chunks.values()) for (var mesh : chunk.values()) {
            BlockPos position = mesh.position();
            for (var surface : mesh.surfaces()) surfaces.add(new SpawnFloorBoundary.Surface(
                    position.getX() + surface.minX(), position.getZ() + surface.minZ(),
                    position.getX() + surface.maxX(), position.getZ() + surface.maxZ(), position.getY() + surface.y()));
        }
    }

    private static void quad(PoseStack poses, VertexConsumer vertices,
            double minX, double minZ, double maxX, double maxZ, double y,
            float red, float green, float blue, float alpha) {
        PoseStack.Pose pose = poses.last();
        vertices.addVertex(pose, (float) minX, (float) y, (float) minZ).setColor(red, green, blue, alpha);
        vertices.addVertex(pose, (float) minX, (float) y, (float) maxZ).setColor(red, green, blue, alpha);
        vertices.addVertex(pose, (float) maxX, (float) y, (float) maxZ).setColor(red, green, blue, alpha);
        vertices.addVertex(pose, (float) maxX, (float) y, (float) minZ).setColor(red, green, blue, alpha);
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.options.hideGui || !holdingStick()) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        String first = formatPosition(SpawnAreaClientState.pos1());
        String second = formatPosition(SpawnAreaClientState.pos2());
        String[] lines = {
                Texts.tr("screen.tacz_pubg.spawn_area_client.loot_floor_tool_left_click_pos_right_click"),
                "pos1: " + first + "    pos2: " + second,
                Texts.tr("screen.tacz_pubg.spawn_area_client.selection_blocks_same_y_maximum", SpawnAreaClientState.selectedArea()),
                Texts.tr("screen.tacz_pubg.spawn_area_client.current_tier_light_preview_dark_confirmed_white_lines", SpawnAreaClientState.selectionTier().label()),
                Texts.tr("screen.tacz_pubg.spawn_area_client.air_clear_selection_save", SpawnAreaKeys.clearLabel(), SpawnAreaKeys.saveLabel()),
                Texts.tr("screen.tacz_pubg.spawn_area_client.air_erase_confirmed_blocks_selection_area_add_remove", SpawnAreaKeys.eraseLabel()),
                Texts.tr("screen.tacz_pubg.spawn_area_client.nearby_confirmed_blocks", SpawnAreaClientState.savedCount(), (SpawnAreaClientState.truncated() ? Texts.tr("screen.tacz_pubg.spawn_area_client.sync_range_truncated") : ""))
        };
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, minecraft.font.width(Texts.resolve(line)));
        }
        try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics, "area_selection",
                6, 6, width + 8, 6 + lines.length * 11)) {
        graphics.fill(6, 6, width + 14, 12 + lines.length * 11, 0xA0000000);
        for (int i = 0; i < lines.length; i++) {
            graphics.drawString(minecraft.font, Texts.resolve(lines[i]), 10, 10 + i * 11, i == 3 ? 0xFF64E9FF : 0xFFFFFFFF);
        }
        }
    }

    private static String formatPosition(BlockPos position) {
        return position == null ? Texts.tr("screen.tacz_pubg.spawn_area_client.awaiting_selection") : position.getX() + ", " + position.getY() + ", " + position.getZ();
    }
}
