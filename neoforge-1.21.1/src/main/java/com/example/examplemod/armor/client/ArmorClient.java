package com.example.examplemod.armor.client;

import com.example.examplemod.armor.ArmorRules;
import com.example.examplemod.armor.PubgArmorItem;
import com.example.examplemod.backpack.client.BackpackClient;
import com.example.examplemod.hud.client.HudLayoutClient;
import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.vitals.client.CombatHudNumbers;
import com.example.examplemod.vitals.client.CombatHudTextures;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

@EventBusSubscriber(modid = "tacz_pubg", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ArmorClient {
    private ArmorClient() {}
    @SubscribeEvent public static void layers(EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins())
            if (event.getSkin(skin) instanceof PlayerRenderer renderer) renderer.addLayer(new Layer(renderer));
    }
    @SubscribeEvent public static void hud(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "combat_equipment"),
                (graphics, delta) -> renderEquipmentPanel(graphics));
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath("tacz_pubg", PlayerStanceHud.HUD_ID),
                (graphics, delta) -> PlayerStanceHud.render(graphics));
        HudLayoutClient.registerPreview(PlayerStanceHud.HUD_ID, PlayerStanceHud::previewBounds,
                (graphics, rectangle, selected) -> PlayerStanceHud.preview(graphics, rectangle));
        HudLayoutClient.registerPreview("equipment", (width, height) -> {
            var panel = anchoredPanel(width, height);
            return new HudLayoutModel.Rect(panel.x(), panel.y(), panel.width(), panel.height());
        }, (graphics, rectangle, selected) -> renderCells(graphics,
                new ArmorHudLayout.Panel(rectangle.x(), rectangle.y(), rectangle.width(), rectangle.height()), true));
    }
    private static void renderEquipmentPanel(GuiGraphics graphics) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !com.example.examplemod.vitals.client.MatchVitalsHud.active() || mc.player.isSpectator()) return;
        var panel = anchoredPanel(graphics.guiWidth(), graphics.guiHeight());
        try (var layout = HudLayoutClient.apply(graphics, "equipment", panel.x(), panel.y(), panel.width(), panel.height())) {
            renderCells(graphics, panel, false);
        }
    }
    static HudLayoutModel.Rect equipmentBounds(int width, int height) {
        var panel = anchoredPanel(width, height);
        return HudLayoutClient.resolve("equipment", width, height, panel.x(), panel.y(), panel.width(), panel.height());
    }
    private static ArmorHudLayout.Panel anchoredPanel(int width, int height) {
        var frame = com.example.examplemod.vitals.client.CombatHudStyle.frame(width, height);
        var panel = new ArmorHudLayout.Panel(frame.left(), frame.rowTop(), frame.width(), frame.rowHeight());
        var vitals = HudLayoutClient.resolve("vitals", width, height, frame.left(), frame.top(), frame.width(), frame.height());
        return panel.move(vitals.x() - frame.left(), vitals.y() - frame.top()).inside(width, height);
    }
    private static void renderCells(GuiGraphics graphics, ArmorHudLayout.Panel panel, boolean preview) {
        var mc = Minecraft.getInstance();
        var settings = com.example.examplemod.pubgsettings.PubgSettingsService.client();
        int icon = settings.integer("hud.equipment_icon_size"), gap = settings.integer("hud.equipment_gap");
        var gun = mc.player == null ? com.example.examplemod.vitals.client.WeaponHudData.Gun.EMPTY
                : com.example.examplemod.vitals.client.WeaponHudData.read(mc.player, mc.player.getMainHandItem());
        String loadedText = gun.present() ? Integer.toString(gun.loaded()) : "30", reserveText = gun.present() ? gun.reserveText() : "95";
        var fit = ArmorHudLayout.metrics(panel.width(), panel.height(), icon, gap, CombatHudNumbers.visibleWidth("100%"),
                CombatHudNumbers.visibleWidth(loadedText), CombatHudNumbers.visibleWidth(reserveText));
        float logicalWidth = fit.logicalWidth();
        int logicalHeight = fit.logicalHeight();
        float scale = fit.scale();
        // The shared frame remains exactly as wide as the health/boost bars; only its contents may shrink.
        graphics.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + panel.height(), settings.color("hud.panel_color"));
        graphics.pose().pushPose();
        graphics.pose().translate(panel.x() + (panel.width() - logicalWidth * scale) / 2,
                panel.y() + (panel.height() - logicalHeight * scale) / 2, 0);
        graphics.pose().scale(scale, scale, 1);
        var state = BackpackClient.current();
        boolean synced = state != null && state.active();
        int packTier = synced ? state.tier() : 0;
        double packFraction = !synced || state.capacity() <= 0 ? 0 : Math.clamp(state.used() / (double) state.capacity(), 0, 1);
        if (preview && !synced) { packTier = 2; packFraction = .64; }
        compactCell(graphics, fit, 0, CombatHudTextures.backpack(packTier), synced || preview ? percentage(packFraction) : "—", packTier,
                packFraction >= 1 ? 0xffe49a52 : 0xffe8e9e7, false);
        for (int index = 0; index < 2; index++) {
            boolean helmet = index == 0;
            ItemStack stack = mc.player == null ? ItemStack.EMPTY
                    : mc.player.getItemBySlot(helmet ? EquipmentSlot.HEAD : EquipmentSlot.CHEST);
            PubgArmorItem armor = stack.getItem() instanceof PubgArmorItem a && a.helmet() == helmet ? a : null;
            int tier = armor == null ? 0 : armor.tier();
            double health = armor == null ? 0 : PubgArmorItem.health(stack);
            int maximum = armor == null ? 0 : ArmorRules.maximum(helmet, tier);
            double fraction = maximum == 0 ? 0 : Math.clamp(health / maximum, 0, 1);
            if (preview && armor == null) { tier = helmet ? 2 : 3; fraction = helmet ? .86 : .72; }
            var durability = ArmorHudLayout.durability(helmet, tier, fraction);
            compactCell(graphics, fit, index + 1, helmet ? CombatHudTextures.helmet(tier) : CombatHudTextures.armor(tier, durability.brokenVest()),
                    durability.label(), tier, durability.color(), durability.brokenVest());
        }
        float middle = logicalHeight / 2f;
        if (gun.present() || preview) {
            RenderSystem.enableBlend();
            graphics.pose().pushPose();
            graphics.pose().translate(fit.modeCenter() - ArmorHudLayout.MODE_SIZE / 2f, middle - ArmorHudLayout.MODE_SIZE / 2f, 0);
            graphics.blit(com.example.examplemod.vitals.client.WeaponHudData.fireModeTexture(gun.present() ? gun.mode()
                    : com.tacz.guns.api.item.gun.FireMode.AUTO), 0, 0, 0, 0, 10, 10, 10, 10);
            graphics.pose().popPose();
            float loadedHeight = CombatHudNumbers.DEFAULT_HEIGHT * ArmorHudLayout.LOADED_SCALE;
            CombatHudNumbers.drawVisibleCentered(graphics, loadedText, fit.loadedCenter(), middle - loadedHeight / 2,
                    loadedHeight, gun.present() && gun.loaded() == 0 ? 0xFFFF5555 : 0xFFF2F0E8);
            CombatHudNumbers.drawVisibleCentered(graphics, reserveText, fit.reserveCenter(), middle - 2,
                    CombatHudNumbers.DEFAULT_HEIGHT, 0xFF9DA1A2);
        }
        int inactive = ((int) (settings.number("hud.icon_inactive_alpha") * 255) << 24) | 0xFFFFFF;
        float statusMiddle = fit.iconSize() / 2f;
        int statusSize = Math.min(ArmorHudLayout.STATUS_SIZE, fit.iconSize());
        statusIcon(graphics, CombatHudTextures.SPRINT, fit.statusCenter(0), statusMiddle, statusSize,
                preview || mc.player != null && mc.player.isSprinting() ? 0xFFFFFFFF : inactive);
        statusIcon(graphics, CombatHudTextures.HEAL, fit.statusCenter(1), statusMiddle, Math.min(ArmorHudLayout.HEAL_SIZE, fit.iconSize()),
                preview || com.example.examplemod.vitals.client.PubgMedicineHud.recovering() ? 0xFFFFFFFF : inactive);
        statusIcon(graphics, CombatHudTextures.LUNGS, fit.statusCenter(2), statusMiddle, fit.iconSize(),
                preview || mc.player != null && (mc.player.isUnderWater() || mc.player.getAirSupply() < mc.player.getMaxAirSupply())
                        ? 0xFFFFFFFF : inactive);
        double oxygen = preview ? .72 : mc.player == null ? 1
                : com.example.examplemod.vitals.client.PubgVitalsLayout.fraction(mc.player.getAirSupply(), mc.player.getMaxAirSupply());
        CombatHudNumbers.drawVisibleCentered(graphics, percentage(oxygen), fit.statusCenter(2), fit.percentageTop(),
                CombatHudNumbers.DEFAULT_HEIGHT * fit.percentageScale(), oxygen <= .25 ? 0xffe45e54 : 0xffe8e9e7);
        graphics.pose().popPose();
    }
    private static void statusIcon(GuiGraphics graphics, ResourceLocation texture, float center, float middle, int size, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(center - size / 2f, middle - size / 2f, 0);
        CombatHudTextures.draw(graphics, texture, 0, 0, size, size, color);
        graphics.pose().popPose();
    }
    private static String percentage(double fraction) { return Math.round(Math.clamp(fraction, 0, 1) * 100) + "%"; }
    private static void compactCell(GuiGraphics graphics, ArmorHudLayout.Metrics fit, int index,
            ResourceLocation icon, String value, int tier, int color, boolean brokenVest) {
        int size = fit.iconSize();
        int iconColor = tier > 0 ? 0xFFFFFFFF
                : ((int) (com.example.examplemod.pubgsettings.PubgSettingsService.client().number("hud.icon_inactive_alpha") * 255) << 24) | 0xFFFFFF;
        graphics.pose().pushPose();
        graphics.pose().translate(fit.cellCenter(index) - size / 2f, 0, 0);
        // Broken references have wider transparent margins; crop only those margins at draw time.
        CombatHudTextures.draw(graphics, icon, 0, 0, size, size, iconColor, brokenVest ? 12 : 0);
        if (tier > 0) {
            graphics.fill(size - 6, size - 7, size, size, 0x9B171B1E);
            graphics.pose().pushPose();
            graphics.pose().translate(size - 5, size - 6.5f, 0);
            graphics.pose().scale(.7f, .7f, 1);
            CombatHudNumbers.draw(graphics, Integer.toString(tier), 0, 0, brokenVest ? ArmorHudLayout.BROKEN_COLOR : 0xFFE8E9E7);
            graphics.pose().popPose();
        }
        graphics.pose().popPose();
        CombatHudNumbers.drawVisibleCentered(graphics, value, fit.cellCenter(index), fit.percentageTop(),
                CombatHudNumbers.DEFAULT_HEIGHT * fit.percentageScale(), color);
    }
    private static final class Layer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        Layer(PlayerRenderer renderer) { super(renderer); }
        @Override public void render(PoseStack pose, MultiBufferSource buffer, int light, AbstractClientPlayer player,
                float limbSwing, float limbSwingAmount, float partialTick, float age, float yaw, float pitch) {
            if (player.isInvisible() || player.isSpectator()) return;
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST}) {
                ItemStack stack = player.getItemBySlot(slot);
                if (!(stack.getItem() instanceof PubgArmorItem armor)) continue;
                pose.pushPose();
                (armor.helmet() ? getParentModel().head : getParentModel().body).translateAndRotate(pose);
                if (!armor.helmet()) {
                    com.example.examplemod.originalicons.client.WearableItemRenderer.render(stack, false, player, pose, buffer, light);
                    pose.popPose();
                    continue;
                }
                double multiplier = com.example.examplemod.modelscale.ModelScaleService.worn(stack);
                pose.translate(0, HelmetWearPlacement.y(armor.tier(),
                        com.example.examplemod.originalicons.OriginalIconState.lowPolyModels(), multiplier), 0);
                float scale = HelmetWearPlacement.BASE_SCALE * (float) multiplier;
                pose.scale(scale, -scale, -scale);
                Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light,
                        OverlayTexture.NO_OVERLAY, pose, buffer, player.level(), player.getId());
                pose.popPose();
            }
        }
    }
}
