package com.example.examplemod.vitals.client;

import com.example.examplemod.hud.client.HudLayoutClient;
import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.pubgsettings.PubgSettingsService;
import com.example.examplemod.zones.client.xaero.XaeroHudAnchor;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import me.xjqsh.lrtactical.api.item.IThrowable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/** Slots 1..5 retain their real slot numbers; empty slots never reserve a row. */
public final class WeaponStackRenderer {
    public record ThrowableKey(net.minecraft.resources.ResourceLocation item, net.minecraft.resources.ResourceLocation asset) {}
    private WeaponStackRenderer() {}
    public static boolean drawGunSilhouette(GuiGraphics graphics, net.minecraft.resources.ResourceLocation gunId,
                                            int x, int y, int width, int height, int argb) {
        var texture = com.tacz.guns.api.TimelessAPI.getClientGunIndex(gunId)
                .map(com.tacz.guns.client.resource.index.ClientGunIndex::getDefaultDisplay).map(display -> display.getHUDTexture()).orElse(null);
        return drawSilhouette(graphics, texture, x, y, width, height, argb);
    }
    public static boolean drawGunSilhouette(GuiGraphics graphics, ItemStack stack, int x, int y, int width, int height, int argb) {
        var texture = com.tacz.guns.api.TimelessAPI.getGunDisplay(stack).map(display -> display.getHUDTexture()).orElse(null);
        return drawSilhouette(graphics, texture, x, y, width, height, argb);
    }
    private static boolean drawSilhouette(GuiGraphics graphics, net.minecraft.resources.ResourceLocation texture,
                                          int x, int y, int width, int height, int argb) {
        if (texture == null) return false;
        int drawWidth = Math.max(1, Math.min(width, height * 3));
        int drawHeight = Math.max(1, Math.min(height, drawWidth / 3));
        x += (width - drawWidth) / 2; y += (height - drawHeight) / 2;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f);
        graphics.blit(texture, x, y, drawWidth, drawHeight, 0, 0, 39, 13, 39, 13);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        return true;
    }
    private static ThrowableKey throwableKey(ItemStack stack) {
        return new ThrowableKey(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()), ((IThrowable) stack.getItem()).getId(stack));
    }
    public static List<Integer> visibleSlots(List<Boolean> occupied) {
        var result = new ArrayList<Integer>();
        for (int i = 0; i < Math.min(5, occupied.size()); i++) if (occupied.get(i)) result.add(i);
        return List.copyOf(result);
    }
    public static HudLayoutModel.Rect bounds(int width, int height) {
        var settings = PubgSettingsService.client();
        var map = HudLayoutClient.mapBounds(width, height);
        int w = Math.min(settings.integer("hud.weapon_stack_width"), Math.max(1, width - 8));
        int row = settings.integer("hud.weapon_stack_row_height"), gap = settings.integer("hud.weapon_stack_gap");
        int h = Math.min((row + gap) * 5, Math.max(1, height - 4));
        return com.example.examplemod.hud.client.MinimapPlacementPolicy.weaponsLeft(map, w, h, 4);
    }
    public static void render(GuiGraphics g) {
        var mc = Minecraft.getInstance();
        if (!MatchVitalsHud.active() || mc.player == null || mc.options.hideGui) return;
        var b = bounds(g.guiWidth(), g.guiHeight());
        draw(g, b, false);
    }
    public static void preview(GuiGraphics g, HudLayoutModel.Rect r) { draw(g, r, true); }
    private static void draw(GuiGraphics g, HudLayoutModel.Rect bounds, boolean preview) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var occupied = new ArrayList<Boolean>();
        for (int i = 0; i < 5; i++) occupied.add(!mc.player.getInventory().getItem(i).isEmpty());
        var rows = visibleSlots(occupied);
        int step = Math.min(Math.max(1, bounds.height() / Math.max(1, rows.size())),
                PubgSettingsService.client().integer("hud.weapon_stack_row_height") + PubgSettingsService.client().integer("hud.weapon_stack_gap"));
        if (preview && rows.isEmpty()) {
            for (int row = 0; row < 3; row++) {
                int y = bounds.y() + bounds.height() - (row + 1) * step;
                drawGunSilhouette(g, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("tacz", row == 1 ? "m1911" : "m4a1"),
                        bounds.x(), y + 3, bounds.width() - 14, Math.max(1, Math.min(step - 4, (bounds.width() - 14) / 3)), row == 0 ? 0xFFFFFFFF : 0x70FFFFFF);
                CombatHudNumbers.draw(g, Integer.toString(row == 0 ? 1 : row == 1 ? 3 : 5), bounds.x() + bounds.width() - 7, y + 5, 0xFFC7C8C7);
            }
            return;
        }
        for (int line = 0; line < rows.size(); line++) {
            int slot = rows.get(line), y = bounds.y() + bounds.height() - (line + 1) * step;
            ItemStack item = mc.player.getInventory().getItem(slot);
            boolean chosen = slot == mc.player.getInventory().selected;
            float alpha = chosen ? 1 : (float) PubgSettingsService.client().number("hud.icon_inactive_alpha");
            var gun = WeaponHudData.read(mc.player, item);
            boolean empty = chosen && gun.present() && gun.loaded() == 0;
            int color = empty ? 0xFFFF5555 : ((int) (alpha * 255) << 24) | 0xFFFFFF;
            CombatHudNumbers.draw(g, Integer.toString(slot + 1), bounds.x() + bounds.width() - 7, y + 6, color);
            if (item.getItem() instanceof IThrowable) {
                var types = new LinkedHashMap<ThrowableKey, ItemStack>();
                for (ItemStack stack : mc.player.getInventory().items) if (!stack.isEmpty() && stack.getItem() instanceof IThrowable) {
                    var key = throwableKey(stack);
                    var previous = types.get(key);
                    if (previous == null) types.put(key, stack.copy()); else previous.setCount((int) Math.min(Integer.MAX_VALUE, (long) previous.getCount() + stack.getCount()));
                }
                int cell = Math.max(1, (bounds.width() - 10) / Math.max(1, types.size())), index = 0;
                for (ItemStack stack : types.values()) {
                    int x = bounds.x() + index++ * cell, size = Math.min(16, Math.max(4, cell - 1));
                    float typeAlpha = chosen && throwableKey(stack).equals(throwableKey(item)) ? 1 : (float) PubgSettingsService.client().number("hud.icon_inactive_alpha");
                    RenderSystem.setShaderColor(1, 1, 1, typeAlpha);
                    g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(size / 16f, size / 16f, 1);
                    g.renderItem(stack, 0, 0); g.pose().popPose();
                    RenderSystem.setShaderColor(1, 1, 1, 1);
                    CombatHudNumbers.draw(g, Integer.toString(stack.getCount()), x, y + 15, ((int) (typeAlpha * 255) << 24) | 0xFFFFFF);
                }
            } else if (gun.present() && gun.texture() != null) {
                drawGunSilhouette(g, item, bounds.x(), y + 3, bounds.width() - 13, step - 4, color);
            } else {
                RenderSystem.setShaderColor(1, 1, 1, alpha);
                g.renderItem(item, bounds.x() + bounds.width() - 32, y + 1);
                RenderSystem.setShaderColor(1, 1, 1, 1);
            }
        }
    }
}
