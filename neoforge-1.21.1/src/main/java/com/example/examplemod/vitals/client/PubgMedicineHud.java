package com.example.examplemod.vitals.client;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.vitals.MatchVitalsPayloads;
import com.example.examplemod.vitals.PubgMedicineItem;
import com.example.examplemod.vitals.PubgMedicineModel;
import com.example.examplemod.hud.client.HudLayoutClient;
import com.example.examplemod.hud.client.HudLayoutModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** The cast ring is local presentation; recovery forecasts come from the server's fixed budget. */
public final class PubgMedicineHud {
    private static MatchVitalsPayloads.RecoveryState recovery = MatchVitalsPayloads.RecoveryState.INACTIVE;
    private static int recoveryAge;
    private PubgMedicineHud() {}
    static void acceptRecovery(MatchVitalsPayloads.RecoveryState state) { recovery = state; recoveryAge = 0; }
    static void clear() { recovery = MatchVitalsPayloads.RecoveryState.INACTIVE; recoveryAge = 0; }
    static void tick() {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null) { clear(); return; }
        if (mc.isPaused() || MatchVitalsHud.paused()) return;
        if (++recoveryAge > 100) clear();
        if (mc.gameMode == null || !player.isUsingItem() || !(player.getUseItem().getItem() instanceof PubgMedicineItem medicine)) return;
        boolean capped = !medicine.kind().isBoost() && !medicine.kind().canUse(player.getHealth(), player.getMaxHealth(), 0);
        if (capped || MedicineClient.movementKeyPressed()) mc.gameMode.releaseUsingItem(player);
    }
    private static boolean recoveryValid() {
        return recovery.remaining() > 0 && (recovery.session().equals(MatchVitalsPayloads.NO_SESSION)
                ? !MatchVitalsHud.active() : MatchVitalsHud.activeSession(recovery.session()));
    }
    public record Target(double total, double next) {}
    public static Target target(double health, double maximum) {
        var player = Minecraft.getInstance().player;
        boolean valid = recoveryValid();
        var casting = player != null && player.isUsingItem()
                && player.getUseItem().getItem() instanceof PubgMedicineItem medicine ? medicine.kind() : null;
        return forecast(health, maximum, valid ? recovery.remaining() : 0,
                valid ? recovery.next() : 0, valid ? recovery.ceiling() : 0, casting);
    }
    /** Both cast previews and authorized recovery packets use the same next-pulse amount as healing. */
    public static Target forecast(double health, double maximum, double budget, double step,
                                  double ceiling, PubgMedicineModel.Kind casting) {
        if (casting != null && !casting.isBoost()) {
            // An older treatment can still be pulsing while the next item is being cast.
            // Its next pulse arrives first; the new cast belongs only in the total forecast.
            if (budget <= 0) step = casting.firstStepBudget(health, maximum);
            budget += casting.healingBudget(health, maximum);
            ceiling = Math.max(ceiling, casting.ceiling());
        }
        return new Target(Math.max(health, Math.min(maximum * ceiling, health + budget)),
                Math.max(health, Math.min(maximum * ceiling, health + step)));
    }
    public static boolean recovering() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.isPaused() || MatchVitalsHud.paused()) return false;
        var combat = com.example.examplemod.combat.client.CombatHudClient.snapshot();
        if (combat != null && MatchVitalsHud.activeSession(combat.session()) && combat.rescue().active()
                && (combat.rescue().rescuer().equals(mc.player.getUUID()) || combat.rescue().target().equals(mc.player.getUUID()))) return true;
        if (combat != null && MatchVitalsHud.activeSession(combat.session()) && combat.downed()) return false;
        if (mc.player.isUsingItem() && mc.player.getUseItem().getItem() instanceof PubgMedicineItem) return true;
        return mc.player.getHealth() < mc.player.getMaxHealth()
                && (target(mc.player.getHealth(), mc.player.getMaxHealth()).total() > mc.player.getHealth() + 1e-6 || MatchVitalsHud.currentBoost() > 0);
    }
    public static HudLayoutModel.Rect previewBounds(int width, int height) {
        return ActionCircleRenderer.bounds(width, height, actionLabel(PubgMedicineModel.Kind.FIRST_AID_KIT),
                Component.keybind("key.tacz_pubg.cancel_use"), false);
    }
    public static Component actionLabel(PubgMedicineModel.Kind kind) {
        return Texts.of(switch (kind) {
            case BANDAGE -> "screen.tacz_pubg.medicine.action_bandage";
            case FIRST_AID_KIT -> "screen.tacz_pubg.medicine.action_first_aid";
            case MED_KIT -> "screen.tacz_pubg.medicine.action_med_kit";
            case ENERGY_DRINK -> "screen.tacz_pubg.medicine.action_energy_drink";
            case PAINKILLER -> "screen.tacz_pubg.medicine.action_painkiller";
            case ADRENALINE_SYRINGE -> "screen.tacz_pubg.medicine.action_adrenaline";
        });
    }
    public static void preview(GuiGraphics g, HudLayoutModel.Rect bounds) {
        var stack = new ItemStack(com.example.examplemod.vitals.PubgMedicineItems.FIRST_AID_KIT.get());
        int duration = stack.getUseDuration(Minecraft.getInstance().player);
        ActionCircleRenderer.draw(g, bounds, stack, false,
                (int) Math.round(duration * .78), duration, actionLabel(PubgMedicineModel.Kind.FIRST_AID_KIT),
                Component.keybind("key.tacz_pubg.cancel_use"));
    }
    static void render(GuiGraphics graphics) {
        if (InventoryActionHud.usesInventoryForeground()) return;
        renderContents(graphics);
    }
    static void renderContents(GuiGraphics graphics) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.options.hideGui) return;
        var combat = com.example.examplemod.combat.client.CombatHudClient.snapshot();
        boolean rescue = combat != null && MatchVitalsHud.activeSession(combat.session()) && combat.rescue().active()
                && (combat.rescue().rescuer().equals(player.getUUID()) || combat.rescue().target().equals(player.getUUID()));
        PubgMedicineItem medicine = player.isUsingItem() && player.getUseItem().getItem() instanceof PubgMedicineItem m ? m : null;
        if (!rescue && medicine == null) return;
        int duration = rescue ? combat.rescue().durationTicks() : medicine.getUseDuration(player.getUseItem(), player);
        int remaining = rescue ? duration - combat.rescue().elapsedTicks() : Math.clamp(player.getUseItemRemainingTicks(), 0, duration);
        Component label = rescue ? Texts.of(combat.rescue().rescuer().equals(player.getUUID())
                ? "screen.tacz_pubg.medicine.action_rescuing" : "screen.tacz_pubg.medicine.action_being_rescued",
                combat.rescue().rescuer().equals(player.getUUID()) ? combat.rescue().targetName() : combat.rescue().rescuerName())
                : actionLabel(medicine.kind());
        Component cancel = Component.keybind(rescue ? "key.tacz_pubg.pickup_loot" : "key.tacz_pubg.cancel_use");
        var bounds = ActionCircleRenderer.bounds(graphics.guiWidth(), graphics.guiHeight(), label, cancel, rescue);
        try (var ignored = HudLayoutClient.apply(graphics, "medicine_progress", bounds.x(), bounds.y(), bounds.width(), bounds.height())) {
            ActionCircleRenderer.draw(graphics, bounds, rescue ? ItemStack.EMPTY : player.getUseItem(), rescue, remaining, duration, label, cancel);
        }
    }
}
