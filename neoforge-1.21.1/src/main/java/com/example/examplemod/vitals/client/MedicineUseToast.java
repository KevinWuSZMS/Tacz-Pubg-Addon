package com.example.examplemod.vitals.client;

import com.example.examplemod.hud.client.HudLayoutClient;
import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.localization.Texts;
import com.example.examplemod.vitals.MatchVitalsPayloads;
import com.example.examplemod.vitals.PubgMedicineModel;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Dedicated successful-use message; local cast prediction must never show a success toast. */
public final class MedicineUseToast {
    public static final String HUD_ID = "medicine_complete";
    // The still references establish position and wording, not the display duration.
    private static final int DISPLAY_TICKS = 60;
    private static final int FADE_TICKS = 10;
    private static final float CENTER_Y = .793F;
    private static final float LINE_HEIGHT_FRACTION = .02F;
    private static MatchVitalsPayloads.MedicineCompleted current;
    private static LocalPlayer owner;
    private static ClientLevel level;
    private static int remaining;
    private static boolean registered;

    private MedicineUseToast() {}

    public static void register(IEventBus modBus) {
        if (registered) return;
        registered = true;
        MatchVitalsPayloads.setMedicineCompletedClientHandler(MedicineUseToast::accept);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        modBus.addListener(MedicineUseToast::registerLayer);
        HudLayoutClient.registerPreview(HUD_ID, MedicineUseToast::previewBounds,
                (graphics, rectangle, selected) -> preview(graphics, rectangle));
    }

    private static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath("tacz_pubg", HUD_ID),
                (graphics, delta) -> render(graphics));
    }

    private static void accept(MatchVitalsPayloads.MedicineCompleted packet) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()) return;
        current = packet;
        owner = mc.player;
        level = mc.level;
        remaining = DISPLAY_TICKS;
    }

    private static void clear() {
        current = null;
        owner = null;
        level = null;
        remaining = 0;
    }

    private static boolean valid() {
        var mc = Minecraft.getInstance();
        return current != null && remaining > 0 && owner != null && mc.player == owner && mc.level == level
                && owner.isAlive() && !owner.isSpectator()
                && (current.session().equals(MatchVitalsPayloads.NO_SESSION)
                    ? !MatchVitalsHud.active() : MatchVitalsHud.activeSession(current.session()));
    }

    private static void tick() {
        if (!valid()) { clear(); return; }
        if (Minecraft.getInstance().isPaused() || MatchVitalsHud.paused()) return;
        if (--remaining <= 0) clear();
    }

    public static Component label(PubgMedicineModel.Kind kind) {
        String item = switch (kind) {
            case BANDAGE -> "bandage";
            case FIRST_AID_KIT -> "first_aid_kit";
            case MED_KIT -> "med_kit";
            case ENERGY_DRINK -> "energy_drink";
            case PAINKILLER -> "painkiller";
            case ADRENALINE_SYRINGE -> "adrenaline_syringe";
        };
        return Texts.of("message.tacz_pubg.medicine.used", Component.translatable("item.tacz_pubg." + item))
                .withStyle(ChatFormatting.BOLD);
    }

    public static HudLayoutModel.Rect previewBounds(int width, int height) {
        return bounds(label(PubgMedicineModel.Kind.ADRENALINE_SYRINGE), width, height);
    }

    public static void preview(GuiGraphics graphics, HudLayoutModel.Rect rectangle) {
        draw(graphics, label(PubgMedicineModel.Kind.ADRENALINE_SYRINGE), rectangle, 255);
    }

    private static void render(GuiGraphics graphics) {
        var mc = Minecraft.getInstance();
        if (!valid() || mc.options.hideGui || mc.getCameraEntity() != mc.player) return;
        Component text = label(current.kind());
        var bounds = bounds(text, graphics.guiWidth(), graphics.guiHeight());
        var placed = HudLayoutClient.resolveRect(graphics, HUD_ID,
                bounds.x(), bounds.y(), bounds.width(), bounds.height());
        draw(graphics, text, placed, 255 * Math.min(remaining, FADE_TICKS) / FADE_TICKS);
    }

    private static HudLayoutModel.Rect bounds(Component text, int width, int height) {
        var font = Minecraft.getInstance().font;
        int textWidth = font.width(text) + 1;
        float scale = Math.min(height * LINE_HEIGHT_FRACTION / font.lineHeight,
                Math.max(1, width - 12) / (float) textWidth);
        int w = Math.max(1, Math.round(textWidth * scale));
        int h = Math.max(1, Math.round(font.lineHeight * scale));
        return new HudLayoutModel.Rect((width - w) / 2, Math.round(height * CENTER_Y - h / 2F), w, h);
    }

    private static void draw(GuiGraphics graphics, Component text, HudLayoutModel.Rect rect, int alpha) {
        var font = Minecraft.getInstance().font;
        int textWidth = font.width(text) + 1;
        float scale = Math.min(rect.width() / (float) textWidth, rect.height() / (float) font.lineHeight);
        graphics.pose().pushPose();
        graphics.pose().translate(rect.x() + (rect.width() - textWidth * scale) / 2F,
                rect.y() + (rect.height() - font.lineHeight * scale) / 2F, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, (alpha << 24) | 0xF1F1F1, true);
        graphics.pose().popPose();
    }
}
