package com.example.examplemod.originalicons.client;

import com.example.examplemod.originalicons.OriginalIconCatalog;
import com.example.examplemod.originalicons.OriginalIconPayloads;
import com.example.examplemod.originalicons.OriginalIconState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class OriginalIconClient {
    private static final Map<ResourceLocation, Boolean> AVAILABLE = new HashMap<>();
    private OriginalIconClient() {}
    public static void register(IEventBus modBus) {
        modBus.addListener((net.neoforged.neoforge.client.event.ModelEvent.RegisterGeometryLoaders event) ->
                event.register(SeamlessPixelGeometry.LOADER_ID, SeamlessPixelGeometry.LOADER));
        OriginalIconPayloads.setClientHandler((state, connection) -> {
            var current = Minecraft.getInstance().getConnection();
            if (current != null && current.getConnection() == connection)
                OriginalIconState.accept(state.revision(), state.enabled(), state.lowPolyModels());
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> OriginalIconState.clear());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> OriginalIconState.clear());
        modBus.addListener((RegisterClientReloadListenersEvent e) ->
                e.registerReloadListener((ResourceManagerReloadListener) manager -> {
                    AVAILABLE.clear(); DetailedItemRenderer.clear(); WearableItemRenderer.clear();
                }));
    }
    private static OriginalIconCatalog.Icon find(ItemStack stack) {
        if (stack.getItem() instanceof IGun gun)
            return OriginalIconCatalog.find("gun", gun.getGunId(stack).toString());
        if (stack.getItem() instanceof IAmmo ammo)
            return OriginalIconCatalog.find("ammo", ammo.getAmmoId(stack).toString());
        if (stack.getItem() instanceof IAttachment attachment)
            return OriginalIconCatalog.find("attachment", attachment.getAttachmentId(stack).toString());
        var ordinary = OriginalIconCatalog.find("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (ordinary != null) return ordinary;
        if (stack.getItem() instanceof me.xjqsh.lrtactical.api.item.IThrowable throwable)
            return OriginalIconCatalog.find("throwable", throwable.getId(stack).toString());
        return null;
    }
    public static boolean render(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers) {
        if (context != ItemDisplayContext.GUI || !OriginalIconState.enabled() || stack.isEmpty()) return false;
        var icon = find(stack);
        if (icon == null) return false;
        var texture = ResourceLocation.parse(icon.texture());
        if (!AVAILABLE.computeIfAbsent(texture, id -> Minecraft.getInstance().getResourceManager().getResource(id).isPresent()))
            return false;
        float width = .5f * Math.min(1f, (float) icon.width() / icon.height());
        float height = .5f * Math.min(1f, (float) icon.height() / icon.width());
        var matrix = pose.last().pose();
        var vertices = buffers.getBuffer(RenderType.entityTranslucentEmissive(texture, false));
        // ItemRenderer's GUI origin is the slot center; GuiGraphics already applies its 16px scale.
        // A fully emissive quad keeps authentic icon colors independent of model lighting.
        vertices.addVertex(matrix, -width, height, 0).setColor(-1).setUv(0, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(15728880).setNormal(pose.last(), 0, 0, 1);
        vertices.addVertex(matrix, -width, -height, 0).setColor(-1).setUv(0, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(15728880).setNormal(pose.last(), 0, 0, 1);
        vertices.addVertex(matrix, width, -height, 0).setColor(-1).setUv(1, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(15728880).setNormal(pose.last(), 0, 0, 1);
        vertices.addVertex(matrix, width, height, 0).setColor(-1).setUv(1, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(15728880).setNormal(pose.last(), 0, 0, 1);
        return true;
    }
}
