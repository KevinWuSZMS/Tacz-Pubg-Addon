package com.example.examplemod.vitals.client;

import com.example.examplemod.lootspawn.client.GroundLootClient;
import com.example.examplemod.keybindings.BindingInput;
import com.example.examplemod.throwables.client.ThrowableClient;
import com.example.examplemod.vitals.MatchVitalsPayloads;
import com.example.examplemod.vitals.PubgMedicineItem;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** The existing configurable pickup/interaction binding owns a held medicine before any pickup. */
public final class MedicineClient {
    private static int nextRequest, pending, pendingSlot = -1, requestedTick;
    private static Item expectedItem;
    private static boolean registered;
    private MedicineClient() {}
    static void register() {
        if (registered) return;
        registered = true;
        MatchVitalsPayloads.setMedicineClientHandler(MedicineClient::accept);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            if (pending() && (!sameHeld() || Minecraft.getInstance().player.tickCount - requestedTick > 100)) cancelPending();
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clearPending());
    }
    public static boolean pending() { return pending > 0; }
    private static void clearPending() { pending = 0; pendingSlot = -1; expectedItem = null; }
    public static void cancelPending() {
        if (pending() && Minecraft.getInstance().getConnection() != null)
            PacketDistributor.sendToServer(new MatchVitalsPayloads.CancelMedicine(pending));
        clearPending();
    }
    private static boolean sameHeld() {
        var mc = Minecraft.getInstance();
        return active() && mc.player.getInventory().selected == pendingSlot && mc.player.getMainHandItem().getItem() == expectedItem
                && !mc.options.keyJump.isDown() && !mc.options.keySprint.isDown();
    }
    private static void accept(MatchVitalsPayloads.MedicineStarted packet) {
        if (packet.request() != pending) return;
        if (packet.duration() == 0) { clearPending(); return; }
        if (!sameHeld() || packet.slot() != pendingSlot) { cancelPending(); return; }
        if (!ThrowableClient.startAutomaticMedicineUse(packet.slot(), packet.duration(), false)) cancelPending();
        else clearPending();
    }
    public static boolean active() {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null && mc.gameMode != null && mc.screen == null
                && mc.player.isAlive() && !mc.player.isSpectator()
                && !com.example.examplemod.combat.client.CombatInputClient.blocked()
                && mc.player.getMainHandItem().getItem() instanceof PubgMedicineItem;
    }
    /** Return true even on a refused treatment: this press must never fall through to ground loot. */
    public static boolean tryUseHeld() {
        if (BandageChainClient.hasIntent()) {
            suppressOffhandSwap();
            ThrowableClient.consumeSharedCancelClick();
            ThrowableClient.cancelMedicineUse();
            return true;
        }
        if (!active()) return false;
        var mc = Minecraft.getInstance();
        suppressOffhandSwap();
        ThrowableClient.consumeSharedCancelClick();
        // The next complete interaction press cancels; it cannot become a new use or a pickup.
        if (pending()) { cancelPending(); return true; }
        if (mc.player.isUsingItem() && mc.player.getUseItem().getItem() instanceof PubgMedicineItem) {
            ThrowableClient.cancelMedicineUse();
            return true;
        }
        if (!mc.isPaused() && !MatchVitalsHud.paused() && !mc.player.isUsingItem() && !mc.player.isHandsBusy()) {
            pending = nextRequest = nextRequest == Integer.MAX_VALUE ? 1 : nextRequest + 1;
            pendingSlot = mc.player.getInventory().selected; requestedTick = mc.player.tickCount;
            expectedItem = mc.player.getMainHandItem().getItem();
            // Input may arrive before vanilla's next gameMode.tick publishes a hotbar change.
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(pendingSlot));
            PacketDistributor.sendToServer(new MatchVitalsPayloads.StartMedicine(pendingSlot, pending));
        }
        return true;
    }
    public static boolean captureKey(long window, int key, int scan, int action) {
        var mc = Minecraft.getInstance();
        var interaction = GroundLootClient.pickupKey();
        if (!active() || window != mc.getWindow().getWindow() || !interaction.matches(key, scan)) return false;
        if (action == GLFW.GLFW_RELEASE) {
            KeyMapping.set(InputConstants.getKey(key, scan), false);
            interaction.setDown(false);
        } else {
            interaction.setDown(true);
            if (action == GLFW.GLFW_PRESS) {
                while (interaction.consumeClick()) {}
                if (BindingInput.freshPress(interaction, BindingInput.keyboardId(key, scan))) GroundLootClient.tryPickupOrInteract();
            }
        }
        return true;
    }
    public static boolean captureMouse(InputEvent.MouseButton.Pre event) {
        var interaction = GroundLootClient.pickupKey();
        if (!active() || !interaction.matchesMouse(event.getButton())) return false;
        if (event.getAction() == GLFW.GLFW_RELEASE) {
            KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(event.getButton()), false);
            interaction.setDown(false);
        } else if (event.getAction() == GLFW.GLFW_PRESS) {
            interaction.setDown(true);
            while (interaction.consumeClick()) {}
            if (BindingInput.freshPress(interaction, BindingInput.mouseId(event.getButton()))) GroundLootClient.tryPickupOrInteract();
        }
        return true;
    }
    private static void suppressOffhandSwap() {
        var swap = Minecraft.getInstance().options.keySwapOffhand;
        if (com.example.examplemod.keybindings.BindingInput.overlaps(swap, GroundLootClient.pickupKey())) while (swap.consumeClick()) {}
    }
    /** A single interaction press keeps only its authorized medicine session using until completion/cancel. */
    public static boolean useKeyDown(KeyMapping mapping) {
        return mapping == Minecraft.getInstance().options.keyUse
                && (ThrowableClient.sustainingMedicineUse() || BandageChainClient.sustaining()) || mapping.isDown();
    }
}
