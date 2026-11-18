package com.example.examplemod.vitals.client;

import com.example.examplemod.vitals.MatchVitalsPayloads;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/** Personal server-authorized match HUD; world previews or another player's match cannot activate it. */
public final class MatchVitalsHud {
    private static MatchVitalsPayloads.Snapshot current;
    private static int packetAge, trailHoldTicks;
    private static float damageTrail, lastObservedHealth;
    private static final DownedHealthTrail downedTrail = new DownedHealthTrail();
    private static boolean wasDowned;

    private MatchVitalsHud() {}

    public static void register(IEventBus modBus) {
        MedicineClient.register();
        MedicineUseToast.register(modBus);
        MatchVitalsPayloads.setClientHandler(MatchVitalsHud::accept);
        MatchVitalsPayloads.setRecoveryClientHandler(PubgMedicineHud::acceptRecovery);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> { clear(); PubgMedicineHud.clear(); });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener(MatchVitalsHud::beforeLayer);
        modBus.addListener(MatchVitalsHud::registerLayers);
        com.example.examplemod.hud.client.HudLayoutClient.registerPreview("vitals", (width, height) -> {
            var f = CombatHudStyle.frame(width, height);
            return new com.example.examplemod.hud.client.HudLayoutModel.Rect(f.left(), f.top(), f.width(), f.height());
        }, (graphics, rectangle, selected) -> previewVitals(graphics, rectangle));
        com.example.examplemod.hud.client.HudLayoutClient.registerPreview("medicine_progress", PubgMedicineHud::previewBounds,
                (graphics, rectangle, selected) -> PubgMedicineHud.preview(graphics, rectangle));
        com.example.examplemod.hud.client.HudLayoutClient.registerPreview("weapon_stack", WeaponStackRenderer::bounds,
                (graphics, rectangle, selected) -> WeaponStackRenderer.preview(graphics, rectangle));
    }

    private static void accept(MatchVitalsPayloads.Snapshot next) {
        if (next == null || !next.active()) { clear(); return; }
        boolean sameSession = current != null && current.sessionId().equals(next.sessionId());
        if (!sameSession) {
            var player = Minecraft.getInstance().player;
            lastObservedHealth = damageTrail = player == null ? next.health() : player.getHealth();
            trailHoldTicks = 0;
            downedTrail.clear();
            wasDowned = false;
        }
        current = next;
        packetAge = 0;
    }

    private static void clear() {
        current = null;
        packetAge = trailHoldTicks = 0;
        damageTrail = lastObservedHealth = 0;
        downedTrail.clear();
        wasDowned = false;
    }

    private static void tick() {
        var mc = Minecraft.getInstance();
        PubgMedicineHud.tick();
        if (current == null || mc.player == null) return;
        float health = mc.player.getHealth();
        var combat = com.example.examplemod.combat.client.CombatHudClient.snapshot();
        boolean downed = !mc.player.isSpectator() && mc.getCameraEntity() == mc.player
                && combat != null && activeSession(combat.session()) && combat.downed();
        if (downed || wasDowned) {
            damageTrail = lastObservedHealth = health;
            trailHoldTicks = 0;
        } else if (health < lastObservedHealth) {
            damageTrail = Math.max(damageTrail, lastObservedHealth);
            trailHoldTicks = 12;
        } else if (health >= damageTrail) {
            damageTrail = health;
        }
        wasDowned = downed;
        if (!downed) downedTrail.clear();
        lastObservedHealth = health;
        // An integrated server can legitimately send no packets while the pause menu is open.
        if (mc.isPaused()) return;
        if (++packetAge > 100) { clear(); return; }
        if (current.paused()) return;
        if (downed) {
            downedTrail.tick(combat.downedHealth(), combat.downedMaxHealth(), combat.downCount());
            return;
        }
        if (trailHoldTicks > 0) trailHoldTicks--;
        else damageTrail = Math.max(health, damageTrail - Math.max(.05f, mc.player.getMaxHealth() * .025f));
    }

    public static boolean active() {
        return hasVitals() && current.inMatch();
    }
    private static boolean hasVitals() {
        Minecraft mc = Minecraft.getInstance();
        return current != null && current.active() && mc.player != null && mc.level != null;
    }
    public static double currentBoost() { return hasVitals() ? current.boost() : 0; }
    public static boolean activeSession(java.util.UUID session) {
        return active() && current.sessionId().equals(session);
    }
    public static boolean paused() { return active() && current.paused(); }

    private static boolean personalView() {
        return active();
    }

    private static void beforeLayer(RenderGuiLayerEvent.Pre event) {
        if (!personalView()) return;
        ResourceLocation layer = event.getName();
        if (layer.equals(VanillaGuiLayers.PLAYER_HEALTH) || layer.equals(VanillaGuiLayers.FOOD_LEVEL)
                || layer.equals(VanillaGuiLayers.ARMOR_LEVEL)
                || layer.equals(VanillaGuiLayers.EXPERIENCE_BAR) || layer.equals(VanillaGuiLayers.EXPERIENCE_LEVEL)
                || layer.toString().equals("tacz:tac_gun_hud_overlay") || layer.toString().equals("tacz:tac_kill_amount_overlay")
                || com.example.examplemod.pubgsettings.PubgSettingsService.client().enabled("hud.hide_hotbar")
                && (layer.equals(VanillaGuiLayers.HOTBAR) || layer.equals(VanillaGuiLayers.SELECTED_ITEM_NAME)))
            event.setCanceled(true);
    }

    private static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR,
                ResourceLocation.fromNamespaceAndPath("tacz_pubg", "match_vitals"), (graphics, delta) -> render(graphics));
        event.registerAbove(VanillaGuiLayers.HOTBAR,
                ResourceLocation.fromNamespaceAndPath("tacz_pubg", "medicine_use"),
                (graphics, delta) -> PubgMedicineHud.render(graphics));
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "weapon_stack"), (graphics, delta) -> WeaponStackRenderer.render(graphics));
        // Keep vanilla oxygen and mounts functional; their normal row would overlap the new bars.
        for (var layer : new ResourceLocation[] {VanillaGuiLayers.AIR_LEVEL,
                VanillaGuiLayers.VEHICLE_HEALTH}) moveDuringMatch(event, layer, () -> -18);
        moveDuringMatch(event, VanillaGuiLayers.JUMP_METER, MatchVitalsHud::jumpMeterOffset);
        moveDuringMatch(event, VanillaGuiLayers.SELECTED_ITEM_NAME, MatchVitalsHud::selectedItemOffset);
    }

    private static int auxiliaryRows() {
        var mc = Minecraft.getInstance();
        int rows = 0;
        if (mc.player.getVehicle() instanceof LivingEntity mount && mount.showVehicleHealth()) {
            // Match Gui.getVehicleMaxHearts, including vanilla's three-row cap.
            int hearts = Math.min(30, (int) (mount.getMaxHealth() + .5f) / 2);
            rows = (hearts + 9) / 10;
        }
        if (mc.gameMode.canHurtPlayer() && (mc.player.isEyeInFluid(FluidTags.WATER)
                || mc.player.getAirSupply() < mc.player.getMaxAirSupply())) rows++;
        return rows;
    }

    private static int jumpMeterOffset() {
        return -44 - Math.max(0, auxiliaryRows() - 2) * 10;
    }

    private static int selectedItemOffset() {
        var mc = Minecraft.getInstance();
        int baseDistance = mc.gameMode.canHurtPlayer() ? 59 : 45;
        int rows = auxiliaryRows();
        int highestDistance = rows == 0 ? 0 : 57 + (rows - 1) * 10;
        if (mc.player.getVehicle() instanceof PlayerRideableJumping)
            highestDistance = Math.max(highestDistance, 29 - jumpMeterOffset());
        return -Math.max(30, highestDistance + 11 - baseDistance);
    }

    private static void moveDuringMatch(RegisterGuiLayersEvent event, ResourceLocation layer, IntSupplier offset) {
        event.wrapLayer(layer, original -> (graphics, delta) -> {
            if (!personalView()) { original.render(graphics, delta); return; }
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(0, offset.getAsInt(), 0);
                original.render(graphics, delta);
            } finally {
                graphics.pose().popPose();
            }
        });
    }

    private static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (!hasVitals() || mc.options.hideGui) return;
        if (!current.inMatch()) { renderOrdinaryBoost(graphics); return; }
        var state = current;
        boolean observing = mc.player.isSpectator() || mc.getCameraEntity() != mc.player;
        var frame = CombatHudStyle.frame(graphics.guiWidth(), graphics.guiHeight());
        try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics, "vitals",
                frame.left(), frame.top(), frame.width(), frame.height())) {
        // Vanilla's health packets arrive on damage; do not wait for the lower-frequency boost heartbeat.
        var combat = com.example.examplemod.combat.client.CombatHudClient.snapshot();
        boolean downed = !observing && combat != null && activeSession(combat.session()) && combat.downed();
        double health = downed ? combat.downedHealth() : observing ? state.health() : mc.player.getHealth();
        double maximum = downed ? combat.downedMaxHealth() : observing ? state.maxHealth() : mc.player.getMaxHealth();
        var target = downed || observing ? new PubgMedicineHud.Target(health, health) : PubgMedicineHud.target(health, maximum);
        var rectangles = downed
                ? PubgVitalsLayout.downedBars(graphics.guiWidth(), graphics.guiHeight(), health, maximum,
                    downedTrail.value(health, maximum, combat.downCount()), CombatHudStyle.vitals())
                : PubgVitalsLayout.bars(graphics.guiWidth(), graphics.guiHeight(),
                    health, maximum, observing ? health : damageTrail, state.boost(),
                    observing ? state.absorption() : mc.player.getAbsorptionAmount(), target.total(), target.next(), CombatHudStyle.vitals());
        for (var rectangle : rectangles)
            graphics.fill(rectangle.left(), rectangle.top(), rectangle.right(), rectangle.bottom(), rectangle.color());
        }
    }
    private static void previewVitals(GuiGraphics graphics, com.example.examplemod.hud.client.HudLayoutModel.Rect rectangle) {
        var frame = CombatHudStyle.frame(graphics.guiWidth(), graphics.guiHeight());
        graphics.pose().pushPose(); graphics.pose().translate(rectangle.x() - frame.left(), rectangle.y() - frame.top(), 0);
        for (var r : PubgVitalsLayout.bars(graphics.guiWidth(), graphics.guiHeight(), 7.6, 20, 7.6, 45, 0, 15, 10.6, CombatHudStyle.vitals()))
            graphics.fill(r.left(), r.top(), r.right(), r.bottom(), r.color());
        graphics.pose().popPose();
    }
    private static void renderOrdinaryBoost(GuiGraphics graphics) {
        // Ordinary medicine must not replace hearts, food, armor or experience with the match HUD.
        int width = PubgVitalsLayout.width(graphics.guiWidth(), 182);
        int left = (graphics.guiWidth() - width) / 2;
        int top = graphics.guiHeight() - 76;
        try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics, "vitals",
                left - 1, top - 1, width + 2, 6)) {
            graphics.fill(left - 1, top - 1, left + width + 1, top + 5, PubgVitalsLayout.BORDER);
            int[] thresholds = {0, 20, 60, 90, 100};
            for (int tier = 0; tier < 4; tier++) {
                int start = thresholds[tier], end = thresholds[tier + 1];
                int x = left + (int) Math.round(width * start / 100.0);
                int right = left + (int) Math.round(width * end / 100.0) - (tier < 3 ? 2 : 0);
                graphics.fill(x, top, right, top + 4, PubgVitalsLayout.BACKGROUND);
                double filled = Math.clamp((current.boost() - start) / (double) (end - start), 0, 1);
                graphics.fill(x, top, x + (int) Math.round((right - x) * filled), top + 4, PubgVitalsLayout.BOOST);
            }
        }
    }
}
