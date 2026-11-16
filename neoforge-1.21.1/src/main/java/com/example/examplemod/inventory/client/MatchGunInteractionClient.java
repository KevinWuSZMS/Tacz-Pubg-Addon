package com.example.examplemod.inventory.client;

import com.example.examplemod.inventory.InventoryConfig;
import com.example.examplemod.lootspawn.client.GroundLootClient;
import com.mojang.blaze3d.platform.InputConstants;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.input.InteractKey;
import com.tacz.guns.util.InputExtraCheck;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/** Routes the current pickup binding through TaCZ's existing interaction path during a match. */
public final class MatchGunInteractionClient {
    private static boolean dispatching;

    private MatchGunInteractionClient() {}

    public static boolean active() {
        var player = Minecraft.getInstance().player;
        return player != null && player.isAlive() && InventoryConfig.active(player) && IGun.mainHandHoldGun(player);
    }

    public static boolean ready() {
        var mc = Minecraft.getInstance();
        return active() && mc.level != null && mc.gameMode != null && InputExtraCheck.isInGame()
                && !mc.isPaused() && !mc.player.isUsingItem() && !mc.player.isHandsBusy();
    }

    /** Keyboard interception precedes vanilla offhand swapping and conflicting quick-use bindings. */
    public static boolean captureKey(long window, int key, int scan, int action) {
        var mc = Minecraft.getInstance();
        var pickup = GroundLootClient.pickupKey();
        if (window != mc.getWindow().getWindow() || !active() || !InputExtraCheck.isInGame()
                || !pickup.matches(key, scan)) return false;
        if (action == GLFW.GLFW_RELEASE) {
            // Release any mapping held before entering a match, just as KeyboardHandler normally would.
            KeyMapping.set(InputConstants.getKey(key, scan), false);
            pickup.setDown(false);
        } else {
            pickup.setDown(true);
            if (action == GLFW.GLFW_PRESS) {
                while (pickup.consumeClick()) {}
                GroundLootClient.tryPickupOrInteract();
            }
        }
        return true;
    }

    /** Cancelling Pre prevents native shooting/aiming from also seeing a rebound mouse pickup press. */
    public static boolean captureMouse(InputEvent.MouseButton.Pre event) {
        var pickup = GroundLootClient.pickupKey();
        if (!active() || !InputExtraCheck.isInGame() || !pickup.matchesMouse(event.getButton())) return false;
        if (event.getAction() == GLFW.GLFW_RELEASE) {
            KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(event.getButton()), false);
            pickup.setDown(false);
        } else if (event.getAction() == GLFW.GLFW_PRESS) {
            pickup.setDown(true);
            while (pickup.consumeClick()) {}
            GroundLootClient.tryPickupOrInteract();
        }
        return true;
    }

    public static void suppressSharedOffhandClick() {
        var swap = Minecraft.getInstance().options.keySwapOffhand;
        if (com.example.examplemod.keybindings.BindingInput.overlaps(swap, GroundLootClient.pickupKey())) {
            while (swap.consumeClick()) {}
        }
    }

    /** TaCZ's click guard also runs inside Minecraft.startUseItem(), including mouse Pre dispatch. */
    public static boolean interactionModifierDown(KeyMapping original) {
        return active() ? dispatching || GroundLootClient.pickupKey().isDown() : original.isDown();
    }

    public static KeyMapping displayedBinding() {
        return active() ? GroundLootClient.pickupKey() : InteractKey.INTERACT_KEY;
    }

    public static boolean interact() {
        if (!ready()) return false;
        var hit = Minecraft.getInstance().hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) return false;
        boolean previous = dispatching;
        dispatching = true;
        try {
            // This public r7 entry uses the same hitResult and block/entity allowlists as its key listener.
            // It ultimately invokes native startUseItem/gameMode, preserving reach and server permissions.
            return InteractKey.onInteractControllerPress(true);
        } finally {
            dispatching = previous;
        }
    }
}
