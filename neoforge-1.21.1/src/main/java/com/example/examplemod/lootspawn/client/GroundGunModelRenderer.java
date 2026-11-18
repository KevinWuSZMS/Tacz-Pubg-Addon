package com.example.examplemod.lootspawn.client;

import com.example.examplemod.ExampleMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockCube;
import com.tacz.guns.client.model.bedrock.BedrockModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.util.RenderDistance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.ClientHooks;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Measure and draw the same static mesh without running TaCZ's shared animation renderer. */
public final class GroundGunModelRenderer {
    private static final Map<BedrockGunModel, Map<GunDisplayInstance, Map<BakedModel, Map<Appearance, Placement>>>> CACHE = new IdentityHashMap<>();
    private static final Set<ResourceLocation> WARNED = new HashSet<>();
    private static final Map<BedrockGunModel, Map<Appearance, AABB>> INVENTORY_BOUNDS = new IdentityHashMap<>();
    private GroundGunModelRenderer() {}
    static void clearCache() { CACHE.clear(); INVENTORY_BOUNDS.clear(); WARNED.clear(); }

    /** Draw the real assembled mesh in a bounded inventory panel without mutating first-person animation. */
    public static void renderInventory(ItemStack stack, net.minecraft.client.gui.GuiGraphics graphics,
                                       int x, int y, int width, int height, float turn) {
        if (width <= 0 || height <= 0) return;
        var display = TimelessAPI.getGunDisplay(stack).orElse(null);
        if (display == null || display.getGunModel() == null || display.getModelTexture() == null) {
            if (display != null) {
                graphics.flush();
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                graphics.blit(display.getSlotTexture(), x, y, width, height, 0, 0, 256, 128, 256, 128);
            }
            return;
        }
        BedrockGunModel model = display.getGunModel();
        Appearance appearance = appearance(stack);
        Map<Appearance, AABB> cache = INVENTORY_BOUNDS.computeIfAbsent(model, ignored -> new HashMap<>());
        if (cache.size() > 128) cache.clear();
        AABB bounds = cache.computeIfAbsent(appearance, ignored -> {
            PoseStack measurement = new PoseStack();
            inventoryRotation(measurement, 0);
            GroundLootMeshBounds measured = new GroundLootMeshBounds();
            for (BedrockPart part : model.getShouldRender())
                collectGun(part, measurement, texture -> measured, display.getModelTexture(), appearance, 0xF000F0);
            if (!Double.isFinite(measured.minX) || !Double.isFinite(measured.maxX)) return new AABB(-1, -1, -1, 1, 1, 1);
            return new AABB(measured.minX, measured.minY, measured.minZ, measured.maxX, measured.maxY, measured.maxZ);
        });
        graphics.flush();
        // GuiGraphics scissors do not inherit the logical inventory's GUI scaling.
        Matrix4f screenPose = graphics.pose().last().pose();
        Vector3f topLeft = screenPose.transformPosition(new Vector3f(x, y, 0));
        Vector3f bottomRight = screenPose.transformPosition(new Vector3f(x + width, y + height, 0));
        graphics.enableScissor((int) Math.floor(topLeft.x), (int) Math.floor(topLeft.y),
                (int) Math.ceil(bottomRight.x), (int) Math.ceil(bottomRight.y));
        PoseStack poses = graphics.pose();
        poses.pushPose();
        try {
            float scale = (float) Math.min((width - 6) / Math.max(.1, bounds.getXsize()),
                    (height - 4) / Math.max(.1, bounds.getYsize()));
            poses.translate(x + width / 2f, y + height / 2f, 120);
            poses.scale(scale, scale, -scale);
            var center = bounds.getCenter();
            poses.translate(-center.x, -center.y, -center.z);
            inventoryRotation(poses, turn);
            com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
            MeshSink sink = texture -> graphics.bufferSource().getBuffer(RenderType.entityCutoutNoCull(texture));
            for (BedrockPart part : model.getShouldRender())
                collectGun(part, poses, sink, display.getModelTexture(), appearance, 0xF000F0);
            graphics.flush();
        } finally {
            poses.popPose(); graphics.disableScissor();
            com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
        }
    }
    private static void inventoryRotation(PoseStack poses, float turn) {
        // BedrockModel already converts authored Y-up coordinates to Y-down, matching GUI space.
        // Keep the muzzle (authored -Z) pointing left without flipping the magazine above the gun.
        poses.mulPose(Axis.YP.rotationDegrees(90 + turn));
    }

    static AABB bounds(ItemStack stack, BakedModel bakedModel) {
        GunDisplayInstance display = TimelessAPI.getGunDisplay(stack).orElse(null);
        if (display == null || display.getGunModel() == null) return GroundLootIconRenderer.bounds(bakedModel);
        Placement placement = placement(display, display.getGunModel(), bakedModel, appearance(stack));
        return placement == Placement.INVALID ? GroundLootIconRenderer.bounds(bakedModel) : placement.bounds;
    }
    static void render(ItemStack stack, BakedModel bakedModel, PoseStack poses, MultiBufferSource buffers, int light) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) return;
        ResourceLocation id = gun.getGunId(stack);
        GunDisplayInstance display = TimelessAPI.getGunDisplay(stack).orElse(null);
        if (display == null) { fallback(id, MissingTextureAtlasSprite.getLocation(), bakedModel, poses, buffers, light); return; }
        var lod = display.getLodModel();
        boolean useLod = lod != null && !RenderDistance.inRenderHighPolyModelDistance(poses);
        BedrockGunModel model = useLod ? lod.getLeft() : display.getGunModel();
        ResourceLocation texture = useLod ? lod.getRight() : display.getModelTexture();
        if (model == null || texture == null) { fallback(id, display.getSlotTexture(), bakedModel, poses, buffers, light); return; }
        Appearance appearance = appearance(stack);
        Placement placement = placement(display, model, bakedModel, appearance);
        if (placement == Placement.INVALID) { fallback(id, display.getSlotTexture(), bakedModel, poses, buffers, light); return; }
        poses.pushPose();
        try {
            GroundLootFlatPose flat = placement.flat;
            poses.translate(flat.offsetX(), flat.offsetY(), flat.offsetZ());
            if (flat.xRotationDegrees() != 0) poses.mulPose(Axis.XP.rotationDegrees(flat.xRotationDegrees()));
            if (flat.zRotationDegrees() != 0) poses.mulPose(Axis.ZP.rotationDegrees(flat.zRotationDegrees()));
            poses.mulPose(placement.groundMatrix);
            MeshSink sink = tex -> buffers.getBuffer(RenderType.entityCutoutNoCull(tex));
            for (BedrockPart part : model.getShouldRender()) collectGun(part, poses, sink, texture, appearance, light);
        } finally { poses.popPose(); }
    }
    private static Placement placement(GunDisplayInstance display, BedrockGunModel model, BakedModel baked, Appearance appearance) {
        Map<Appearance, Placement> values = CACHE.computeIfAbsent(model, ignored -> new IdentityHashMap<>())
                .computeIfAbsent(display, ignored -> new IdentityHashMap<>()).computeIfAbsent(baked, ignored -> new HashMap<>());
        if (values.size() > 128) values.clear();
        return values.computeIfAbsent(appearance, ignored -> {
            try { return measure(display, model, baked, appearance); }
            catch (RuntimeException invalid) { return Placement.INVALID; }
        });
    }
    private static Appearance appearance(ItemStack stack) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) return Appearance.EMPTY;
        Map<AttachmentType, MountedAttachment> attachments = new EnumMap<>(AttachmentType.class);
        Set<String> installed = new HashSet<>(), adapters = new HashSet<>();
        boolean showMount = false, showMuzzle = false;
        int extendedLevel = 0;
        for (AttachmentType type : AttachmentType.values()) {
            if (type == AttachmentType.NONE) continue;
            var level = Minecraft.getInstance().level;
            ItemStack attachment = level == null ? ItemStack.EMPTY : gun.getAttachment(level.registryAccess(), stack, type);
            if (attachment.isEmpty()) attachment = gun.getBuiltinAttachment(stack, type);
            if (attachment.isEmpty()) continue;
            installed.add(type.name().toLowerCase(Locale.ROOT));
            IAttachment accessor = IAttachment.getIAttachmentOrNull(attachment);
            if (accessor == null) continue;
            var index = TimelessAPI.getClientAttachmentIndex(accessor.getAttachmentId(attachment)).orElse(null);
            if (index == null) continue;
            if (type == AttachmentType.EXTENDED_MAG) extendedLevel = index.getData().getExtendedMagLevel();
            if (type == AttachmentType.SCOPE) showMount = index.isShowMount();
            if (type == AttachmentType.MUZZLE) showMuzzle = index.isShowMuzzle();
            if (index.getAdapterNodeName() != null) adapters.add(index.getAdapterNodeName());
            if (index.getAttachmentModel() != null && index.getModelTexture() != null)
                attachments.put(type, new MountedAttachment(accessor.getAttachmentId(attachment).toString(),
                        index.getAttachmentModel(), index.getModelTexture()));
        }
        var visibility = new GroundLootBonePolicy.Appearance(installed.contains("scope"), showMount, extendedLevel,
                installed.contains("laser") || installed.contains("grip"), gun.hasBulletInBarrel(stack),
                gun.getCurrentAmmoCount(stack) > 0, installed, adapters, showMuzzle);
        return new Appearance(visibility, Map.copyOf(attachments));
    }
    private static Placement measure(GunDisplayInstance display, BedrockGunModel model, BakedModel bakedModel, Appearance appearance) {
        PoseStack ground = new PoseStack();
        ClientHooks.handleCameraTransforms(ground, bakedModel, ItemDisplayContext.GROUND, false);
        ground.translate(-0.5F, -0.5F, -0.5F);
        ground.translate(0.5, 2, 0.5);
        ground.scale(-1, -1, 1);
        Vector3f scale = display.getTransform().getScale().getGround();
        applyGroundOrigin(ground, model.getGroundOriginPath(), scale);
        if (scale != null) { ground.translate(0, 1.5, 0); ground.scale(scale.x(), scale.y(), scale.z()); ground.translate(0, -1.5, 0); }
        Matrix4f matrix = new Matrix4f(ground.last().pose());
        GroundLootMeshBounds bounds = new GroundLootMeshBounds();
        MeshSink sink = ignored -> bounds;
        for (BedrockPart part : model.getShouldRender()) collectGun(part, ground, sink, display.getModelTexture(), appearance, 0);
        double width = bounds.maxX - bounds.minX, height = bounds.maxY - bounds.minY, depth = bounds.maxZ - bounds.minZ;
        GroundLootFlatPose flat = GroundLootFlatPose.fromBounds(bounds.minX, bounds.minY, bounds.minZ,
                Math.max(bounds.minX + 1e-6, bounds.maxX), Math.max(bounds.minY + 1e-6, bounds.maxY), Math.max(bounds.minZ + 1e-6, bounds.maxZ));
        if (flat.zRotationDegrees() != 0) { double old = width; width = height; height = old; }
        else if (flat.xRotationDegrees() != 0) { double old = height; height = depth; depth = old; }
        return new Placement(matrix, flat, new AABB(-width / 2, 0, -depth / 2, width / 2, height, depth / 2));
    }
    static void applyGroundOrigin(PoseStack poses, List<BedrockPart> path, Vector3f groundScale) {
        if (path == null) return;
        Vector3f scale = groundScale == null ? new Vector3f(1, 1, 1) : groundScale;
        poses.translate(0, 1.5, 0);
        for (int i = path.size() - 1; i >= 0; i--) {
            BedrockPart part = path.get(i);
            poses.mulPose(Axis.XN.rotation(part.getInitRotX()));
            poses.mulPose(Axis.YN.rotation(part.getInitRotY()));
            poses.mulPose(Axis.ZN.rotation(part.getInitRotZ()));
            if (part.getParent() != null) poses.translate(-part.x * scale.x() / 16.0F, -part.y * scale.y() / 16.0F, -part.z * scale.z() / 16.0F);
            else poses.translate(-part.x * scale.x() / 16.0F, (1.5F - part.y / 16.0F) * scale.y(), -part.z * scale.z() / 16.0F);
        }
        poses.translate(0, -1.5, 0);
    }
    /** Read only the authored rest pose; never mutate the player's shared animation object. */
    static void transformStatic(BedrockPart part, PoseStack poses) {
        poses.translate(part.x / 16.0F, part.y / 16.0F, part.z / 16.0F);
        if (part.getInitRotZ() != 0) poses.mulPose(Axis.ZP.rotation(part.getInitRotZ()));
        if (part.getInitRotY() != 0) poses.mulPose(Axis.YP.rotation(part.getInitRotY()));
        if (part.getInitRotX() != 0) poses.mulPose(Axis.XP.rotation(part.getInitRotX()));
    }
    private static void collectGun(BedrockPart part, PoseStack poses, MeshSink sink, ResourceLocation texture, Appearance appearance, int light) {
        String parent = part.getParent() == null ? null : part.getParent().name;
        if (!GroundLootBonePolicy.gunVisible(part.name, parent, appearance.visibility)) return;
        poses.pushPose();
        try {
            transformStatic(part, poses);
            AttachmentType mount = attachmentPosition(part.name);
            if (mount != null) {
                MountedAttachment attachment = appearance.attachments.get(mount);
                if (attachment != null) {
                    poses.translate(0, -1.5, 0);
                    for (BedrockPart root : attachment.model.getShouldRender()) collectSupplement(attachment.assetId, root, poses, sink.get(attachment.texture), light);
                }
                return;
            }
            VertexConsumer vertices = sink.get(texture);
            for (BedrockCube cube : part.cubes) cube.compile(poses.last(), vertices, part.illuminated ? 0xF000F0 : light, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
            for (BedrockPart child : part.children) collectGun(child, poses, sink, texture, appearance, light);
        } finally { poses.popPose(); }
    }
    static void collectSupplement(String assetId, BedrockPart part, PoseStack poses, VertexConsumer vertices, int light) {
        if (!GroundLootBonePolicy.supplementVisible(assetId, part.name)) return;
        poses.pushPose();
        try {
            transformStatic(part, poses);
            for (BedrockCube cube : part.cubes) cube.compile(poses.last(), vertices, part.illuminated ? 0xF000F0 : light, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
            for (BedrockPart child : part.children) collectSupplement(assetId, child, poses, vertices, light);
        } finally { poses.popPose(); }
    }
    private static AttachmentType attachmentPosition(String name) {
        if (name == null) return null;
        for (AttachmentType type : AttachmentType.values())
            if (type != AttachmentType.NONE && name.equals(type.name().toLowerCase(Locale.ROOT) + "_pos")) return type;
        return null;
    }
    private static void fallback(ResourceLocation id, ResourceLocation texture, BakedModel baked, PoseStack poses, MultiBufferSource buffers, int light) {
        if (WARNED.add(id)) ExampleMod.LOGGER.warn("Ground loot gun {}: no usable static mesh bounds; drawing floor icon", id);
        GroundLootIconRenderer.texture(texture, baked, poses, buffers, light);
    }
    private interface MeshSink { VertexConsumer get(ResourceLocation texture); }
    private record MountedAttachment(String assetId, BedrockModel model, ResourceLocation texture) {}
    private record Appearance(GroundLootBonePolicy.Appearance visibility, Map<AttachmentType, MountedAttachment> attachments) {
        private static final Appearance EMPTY = new Appearance(GroundLootBonePolicy.Appearance.EMPTY, Map.of());
    }
    private record Placement(Matrix4f groundMatrix, GroundLootFlatPose flat, AABB bounds) {
        private static final Placement INVALID = new Placement(null, null, null);
    }
}
