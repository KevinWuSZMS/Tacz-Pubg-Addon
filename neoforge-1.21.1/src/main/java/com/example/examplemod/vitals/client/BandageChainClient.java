package com.example.examplemod.vitals.client;

import com.example.examplemod.throwables.client.ThrowableClient;
import com.example.examplemod.vitals.MatchVitalsPayloads;
import com.example.examplemod.vitals.PubgMedicineItem;
import com.example.examplemod.vitals.PubgMedicineModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Only an explicitly started, uncancelled bandage use may accept a server continuation. */
public final class BandageChainClient {
    private static LocalPlayer owner;
    private static ClientLevel level;
    private static InteractionHand hand;
    private static int slot = -1, offhandTicks, intentTicks;
    private static boolean registered;
    private BandageChainClient() {}

    public static void register() {
        if (registered) return;
        registered = true;
        MatchVitalsPayloads.setBandageContinuedClientHandler(BandageChainClient::accept);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> cancel());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((LivingEntityUseItemEvent.Stop event) -> {
            if (event.getEntity() == owner && bandage(event.getItem())) cancel();
        });
        NeoForge.EVENT_BUS.addListener((LivingEntityUseItemEvent.Start event) -> {
            var mc = Minecraft.getInstance();
            // Offhand medicine retains its ordinary initial right-click authorization.
            if (mc.player != null && event.getEntity() == mc.player && mc.options.keyUse.isDown()
                    && event.getItem() == mc.player.getOffhandItem() && bandage(event.getItem()))
                started(mc.player.getInventory().selected, InteractionHand.OFF_HAND);
        });
    }

    private static boolean bandage(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof PubgMedicineItem medicine
                && medicine.kind() == PubgMedicineModel.Kind.BANDAGE;
    }

    public static void started(int selectedSlot, InteractionHand usedHand) {
        var mc = Minecraft.getInstance();
        cancel();
        if (mc.player == null || !bandage(mc.player.getItemInHand(usedHand))) return;
        owner = mc.player; level = mc.level; hand = usedHand; slot = selectedSlot;
        intentTicks = owner.getItemInHand(hand).getUseDuration(owner) + 100;
    }

    /** Clear intent before sending cancellation, so a delayed continuation cannot restart it. */
    public static void cancel() { owner = null; level = null; hand = null; slot = -1; offhandTicks = 0; intentTicks = 0; }

    public static void interrupted() {
        cancel();
        var player = Minecraft.getInstance().player;
        if (player != null && player.isUsingItem() && bandage(player.getUseItem())) player.stopUsingItem();
    }

    private static boolean validOwner() {
        var mc = Minecraft.getInstance();
        return owner != null && mc.player == owner && mc.level == level && owner.isAlive() && !owner.isSpectator()
                && MedicineClient.screenAllowed()
                && owner.getInventory().selected == slot && !com.example.examplemod.combat.client.CombatInputClient.blocked();
    }

    /** Includes the short gap between vanilla completion and the next server confirmation. */
    public static boolean hasIntent() {
        if (!validOwner()) return false;
        return MedicineInputRules.hasChainIntent(true, intentTicks, bandage(owner.getItemInHand(hand)),
                owner.getInventory().items.stream().anyMatch(BandageChainClient::bandage));
    }

    private static void accept(MatchVitalsPayloads.BandageContinued packet) {
        var mc = Minecraft.getInstance();
        boolean session = packet.session().equals(MatchVitalsPayloads.NO_SESSION)
                ? !MatchVitalsHud.active() : MatchVitalsHud.activeSession(packet.session());
        boolean intent = hasIntent();
        boolean canContinue = intent && MedicineInputRules.acceptsContinuation(intent, session,
                slot == packet.slot() && hand == packet.hand(), mc.isPaused() || MatchVitalsHud.paused(),
                bandage(owner.getItemInHand(hand)), MedicineClient.movementKeyPressed() || owner.isSprinting(),
                PubgMedicineModel.Kind.BANDAGE.canUse(owner.getHealth(), owner.getMaxHealth(), 0),
                owner.isUsingItem() && !bandage(owner.getUseItem()));
        if (canContinue && hand == InteractionHand.MAIN_HAND) {
            // The old client timer may still be waiting for vanilla's completion notification.
            owner.stopUsingItem();
            canContinue = ThrowableClient.startAutomaticMedicineUse(packet.slot(), packet.duration(), false);
        } else if (canContinue && mc.screen == null) {
            owner.stopUsingItem();
            owner.startUsingItem(InteractionHand.OFF_HAND);
            offhandTicks = packet.duration() + 5;
            intentTicks = packet.duration() + 100;
        } else canContinue = false;
        if (!canContinue) {
            cancel();
            PacketDistributor.sendToServer(new MatchVitalsPayloads.CancelBandageContinuation(packet.session(), packet.token()));
        }
    }

    public static boolean sustaining() {
        return validOwner() && hand == InteractionHand.OFF_HAND && offhandTicks > 0 && owner.isUsingItem()
                && owner.getUsedItemHand() == hand && bandage(owner.getUseItem());
    }

    private static void tick() {
        if (!hasIntent()) { cancel(); return; }
        if (owner.isUsingItem() && !bandage(owner.getUseItem())) { cancel(); return; }
        var mc = Minecraft.getInstance();
        if (MedicineClient.movementKeyPressed() || owner.isSprinting()) { cancel(); return; }
        if (!mc.isPaused() && !MatchVitalsHud.paused()) {
            intentTicks--;
            if (offhandTicks > 0) offhandTicks--;
        }
    }
}
