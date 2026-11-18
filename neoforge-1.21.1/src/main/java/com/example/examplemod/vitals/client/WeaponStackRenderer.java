package com.example.examplemod.vitals.client;

import com.example.examplemod.hud.client.HudLayoutClient;
import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.inventory.client.PubgInventoryScreen;
import com.example.examplemod.pubgsettings.PubgSettingsService;
import com.example.examplemod.keybindings.client.BindingVisuals;
import com.mojang.blaze3d.systems.RenderSystem;
import com.tacz.guns.client.gui.GunRefitScreen;
import java.util.ArrayList;
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
        if (texture == null || Minecraft.getInstance().getResourceManager().getResource(texture).isEmpty()) return false;
        int drawWidth = Math.max(1, Math.min(width, height * 3));
        int drawHeight = Math.max(1, Math.min(height, drawWidth / 3));
        x += (width - drawWidth) / 2; y += (height - drawHeight) / 2;
        graphics.flush();
        float[] previous = RenderSystem.getShaderColor().clone();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f);
        try {
            graphics.blit(texture, x, y, drawWidth, drawHeight, 0, 0, 39, 13, 39, 13);
        } finally {
            RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
        }
        return true;
    }
    private static ThrowableKey throwableKey(ItemStack stack) {
        return new ThrowableKey(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()), ((IThrowable) stack.getItem()).getId(stack));
    }
    public static int throwableCount(ItemStack equipped, Iterable<ItemStack> inventory) {
        if (equipped.isEmpty() || !(equipped.getItem() instanceof IThrowable)) return 0;
        var selected = throwableKey(equipped);
        long count = 0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && stack.getItem() instanceof IThrowable && selected.equals(throwableKey(stack)))
                count = Math.min(Integer.MAX_VALUE, count + stack.getCount());
        }
        return (int) count;
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
        return WeaponStackLayout.bounds(map, w);
    }
    public static void render(GuiGraphics g) {
        var mc = Minecraft.getInstance();
        if (!MatchVitalsHud.active() || mc.player == null || mc.options.hideGui) return;
        if (mc.screen instanceof PubgInventoryScreen || mc.screen instanceof GunRefitScreen) return;
        var b = bounds(g.guiWidth(), g.guiHeight());
        draw(g, b, false);
    }
    public static void preview(GuiGraphics g, HudLayoutModel.Rect r) { draw(g, r, true); }
    private static void draw(GuiGraphics g, HudLayoutModel.Rect bounds, boolean preview) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || bounds.width() < 8 || bounds.height() < 30) return;
        var occupied = new ArrayList<Boolean>();
        for (int i = 0; i < 5; i++) occupied.add(!mc.player.getInventory().getItem(i).isEmpty());
        var rows = visibleSlots(occupied);
        int iconHeight = PubgSettingsService.client().integer("hud.weapon_stack_row_height");
        int padding = PubgSettingsService.client().integer("hud.weapon_stack_gap");
        if (preview && rows.isEmpty()) {
            for (int line = 0; line < 5; line++) {
                var row = WeaponStackLayout.row(bounds, line, iconHeight, padding);
                drawKey(g, row.key(), line, false);
                if (line == 4) {
                    var icon = row.smallItem();
                    CombatHudTextures.draw(g, ThrowableHudIcons.texture(net.minecraft.resources.ResourceLocation.parse("tacz_pubg:frag_grenade"), null),
                            icon.x(), icon.y(), icon.width(), icon.height(), 0xBFFFFFFF);
                    drawCount(g, row.count(), 1, 0xFFFFFFFF);
                } else if (line == 3) {
                    drawItem(g, new ItemStack(net.minecraft.world.item.Items.IRON_SWORD), row.smallItem(), .7f);
                } else {
                    var area = row.gun();
                    drawGunSilhouette(g, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("tacz", line == 2 ? "m1911" : "m4a1"),
                            area.x(), area.y(), area.width(), area.height(), line == 0 ? 0xFFFFFFFF : 0x70FFFFFF);
                }
            }
            return;
        }
        for (int line = 0; line < rows.size(); line++) {
            int slot = rows.get(line);
            var row = WeaponStackLayout.row(bounds, line, iconHeight, padding);
            ItemStack item = mc.player.getInventory().getItem(slot);
            boolean chosen = slot == mc.player.getInventory().selected;
            float alpha = chosen ? 1 : (float) PubgSettingsService.client().number("hud.icon_inactive_alpha");
            var gun = WeaponHudData.read(mc.player, item);
            boolean empty = gun.present() && gun.loaded() == 0;
            int color = empty ? 0xFFFF5555 : ((int) (alpha * 255) << 24) | 0xFFFFFF;
            drawKey(g, row.key(), slot, empty);
            if (item.getItem() instanceof IThrowable) {
                var texture = ThrowableHudIcons.texture(item);
                var icon = row.smallItem();
                if (texture != null && mc.getResourceManager().getResource(texture).isPresent())
                    CombatHudTextures.draw(g, texture, icon.x(), icon.y(), icon.width(), icon.height(), color);
                else drawItem(g, item, icon, alpha);
                drawCount(g, row.count(), throwableCount(item, mc.player.getInventory().items), color);
            } else if (gun.present() && gun.texture() != null) {
                var area = row.gun();
                if (!drawGunSilhouette(g, item, area.x(), area.y(), area.width(), area.height(), color))
                    drawItem(g, item, row.smallItem(), alpha);
            } else {
                drawItem(g, item, row.smallItem(), alpha);
            }
        }
    }
    private static void drawCount(GuiGraphics g, HudLayoutModel.Rect box, int count, int color) {
        String label = Integer.toString(count);
        float height = Math.min(box.height(), CombatHudNumbers.DEFAULT_HEIGHT * box.width() / Math.max(1, CombatHudNumbers.visibleWidth(label)));
        var ink = CombatHudNumbers.inkBounds(label, height);
        CombatHudNumbers.draw(g, label, box.x() + box.width() - ink.right(), box.y() + box.height() - height, height, color);
    }
    private static void drawItem(GuiGraphics g, ItemStack item, HudLayoutModel.Rect box, float alpha) {
        g.flush();
        float[] previous = RenderSystem.getShaderColor().clone();
        RenderSystem.setShaderColor(1, 1, 1, alpha);
        g.pose().pushPose();
        try {
            g.pose().translate(box.x(), box.y(), 0);
            g.pose().scale(box.width() / 16f, box.height() / 16f, 1);
            g.renderItem(item, 0, 0);
            g.flush();
        } finally {
            g.pose().popPose();
            RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
        }
    }
    private static final class Keycaps { private static final BindingVisuals VISUALS = new BindingVisuals(); }
    private static void drawKey(GuiGraphics g, HudLayoutModel.Rect box, int slot, boolean empty) {
        var mc = Minecraft.getInstance();
        String label = mc.options.keyHotbarSlots[slot].getTranslatedKeyMessage().getString();
        Keycaps.VISUALS.roundRect(g, box.x(), box.y(), box.width(), box.height(), Math.min(2, box.width() / 5f), 0xCFE8E5DC, 0);
        int color = empty ? 0xFFDA3434 : 0xFF545851;
        if (label.matches("[0-9]+")) {
            float height = Math.max(1, Math.min(box.height() - 2, 9));
            height = Math.min(height, 9f * Math.max(1, box.width() - 2) / Math.max(1, CombatHudNumbers.visibleWidth(label)));
            CombatHudNumbers.drawVisibleCentered(g, label, box.x() + box.width() / 2f, box.y() + (box.height() - height) / 2f, height, color);
        } else {
            float scale = Math.min(Math.max(1, box.height() - 2) / (float) mc.font.lineHeight,
                    Math.max(1, box.width() - 2) / (float) Math.max(1, mc.font.width(label)));
            g.pose().pushPose();
            g.pose().translate(box.x() + (box.width() - mc.font.width(label) * scale) / 2,
                    box.y() + (box.height() - mc.font.lineHeight * scale) / 2, 0);
            g.pose().scale(scale, scale, 1);
            g.drawString(mc.font, label, 0, 0, color, false);
            g.pose().popPose();
        }
    }
}
