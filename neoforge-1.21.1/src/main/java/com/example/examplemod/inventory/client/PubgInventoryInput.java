package com.example.examplemod.inventory.client;

import com.example.examplemod.backpack.client.BackpackClient;
import com.example.examplemod.inventory.InventoryConfig;
import com.example.examplemod.keybindings.BindingInput;
import com.example.examplemod.keybindings.mixin.KeyMappingAccess;
import com.example.examplemod.lean.client.LeanClient;
import com.example.examplemod.movement.client.MovementClient;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.gui.GunRefitScreen;
import com.tacz.guns.client.input.AimKey;
import com.tacz.guns.client.input.FireSelectKey;
import com.tacz.guns.client.input.InspectKey;
import com.tacz.guns.client.input.RefitKey;
import com.tacz.guns.client.input.ReloadKey;
import com.tacz.guns.client.input.ShootKey;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.Input;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** One match inventory, two views. Only explicit keyboard actions pass through the PUBG view. */
public final class PubgInventoryInput {
    private static final Set<Long> PRESSED = new HashSet<>();
    private static boolean registered, wasInMatch, preferVanilla, combatReleaseRequired, closeKeyReady;
    private PubgInventoryInput() { }

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ScreenEvent.Opening event) -> {
            refreshMatch();
            if (event.getNewScreen() instanceof InventoryScreen && inMatch() && !preferVanilla)
                event.setNewScreen(new PubgInventoryScreen());
            if (event.getNewScreen() instanceof PubgInventoryScreen) {
                combatReleaseRequired = true;
                PRESSED.clear();
                closeKeyReady = !inventoryKeyHeld();
                stopCombat();
            }
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> {
            if (event.getScreen() instanceof InventoryScreen screen && inMatch())
                event.addListener(Button.builder(Component.literal("PUBG"), b -> openPubg())
                        .bounds(Math.min(screen.width - 48, screen.getGuiLeft() + screen.getXSize() + 3),
                                screen.getGuiTop() + 27, 45, 20).build());
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ClientTickEvent.Pre event) -> {
            refreshMatch();
            var mc = Minecraft.getInstance();
            if (activeScreen()) {
                if (!inventoryKeyHeld()) closeKeyReady = true;
                combatReleaseRequired = true;
                stopCombat();
                if (!inMatch() || mc.player == null || !mc.player.isAlive()) mc.setScreen(null);
            } else if (combatReleaseRequired) {
                if (!combatHeld()) combatReleaseRequired = false;
                else stopCombat();
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            release();
            wasInMatch = preferVanilla = combatReleaseRequired = false;
        });
    }

    public static boolean inMatch() {
        var player = Minecraft.getInstance().player;
        return player != null && player.isAlive() && InventoryConfig.active(player);
    }

    private static void refreshMatch() {
        boolean active = inMatch();
        if (active != wasInMatch) {
            preferVanilla = false;
            wasInMatch = active;
        }
    }

    public static boolean activeScreen() { return Minecraft.getInstance().screen instanceof PubgInventoryScreen; }

    public static boolean acceptsMovement() {
        var mc = Minecraft.getInstance();
        return activeScreen() && inMatch() && mc.isWindowActive() && mc.getOverlay() == null
                && !(mc.screen.getFocused() instanceof EditBox box && box.canConsumeInput());
    }

    /** Mouse bindings remain UI actions even when also bound to movement in the controls profile. */
    public static boolean heldForMovement(KeyMapping mapping) {
        if (!activeScreen()) return BindingInput.physicallyHeld(mapping);
        if (!acceptsMovement()) return false;
        return keyboardHeld(mapping);
    }

    private static boolean keyboardHeld(KeyMapping mapping) {
        return BindingInput.pollKeyboardHeld(mapping);
    }

    /** Match sprint has its own configurable mapping, separate from vanilla's cleared toggle. */
    public static boolean sprintHeld() {
        var options = Minecraft.getInstance().options;
        if (heldForMovement(options.keySprint)) return true;
        for (var key : options.keyMappings)
            if (key.getName().equals("key.tacz_pubg.match_sprint") && heldForMovement(key)) return true;
        return false;
    }

    /** Called before the existing stance controller applies crouch/prone/walk speed. */
    public static void updateMovement(Input input) {
        if (!activeScreen()) return;
        var options = Minecraft.getInstance().options;
        input.up = heldForMovement(options.keyUp);
        input.down = heldForMovement(options.keyDown);
        input.left = heldForMovement(options.keyLeft);
        input.right = heldForMovement(options.keyRight);
        input.forwardImpulse = input.up == input.down ? 0 : input.up ? 1 : -1;
        input.leftImpulse = input.left == input.right ? 0 : input.left ? 1 : -1;
        input.jumping = heldForMovement(options.keyJump);
        input.shiftKeyDown = heldForMovement(options.keyShift);
    }

    public static void openPreferred() {
        refreshMatch();
        if (preferVanilla) openVanilla(); else openPubg();
    }

    public static void openPubg() {
        if (!inMatch()) return;
        refreshMatch();
        preferVanilla = false;
        var mc = Minecraft.getInstance();
        // Vanilla returns its cursor/crafting contents authoritatively before our first snapshot request.
        if (mc.screen instanceof AbstractContainerScreen<?> || mc.player.containerMenu != mc.player.inventoryMenu
                || !mc.player.containerMenu.getCarried().isEmpty()) mc.player.closeContainer();
        mc.setScreen(new PubgInventoryScreen());
    }

    public static void openVanilla() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        refreshMatch();
        preferVanilla = true;
        mc.setScreen(new InventoryScreen(mc.player));
    }

    /** Keep TaCZ's actual screen and its refit transform, audio and laser-color close behavior. */
    public static boolean openRefit() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isAlive() || mc.player.isSpectator()) return false;
        var stack = mc.player.getMainHandItem();
        var gun = IGun.getIGunOrNull(stack);
        if (gun == null || gun.hasAttachmentLock(stack)) return false;
        var previous = mc.screen;
        mc.setScreen(new GunRefitScreen() {
            @Override public void onClose() {
                super.onClose();
                if (previous instanceof PubgInventoryScreen && inMatch()) Minecraft.getInstance().setScreen(previous);
            }
        });
        return true;
    }

    public static boolean keyPressed(int key, int scan, int modifiers) {
        if (!activeScreen()) return false;
        var mc = Minecraft.getInstance();
        boolean fresh = PRESSED.add(((long) key << 32) ^ (scan & 0xffffffffL));
        if (mc.screen.getFocused() instanceof EditBox box && box.canConsumeInput()) return false;
        if (mc.options.keyInventory.matches(key, scan) || BackpackClient.matchesOpenKey(key, scan)) {
            if (fresh && closeKeyReady) mc.screen.onClose();
            return true;
        }
        if (RefitKey.REFIT_KEY.matches(key, scan)) {
            if (fresh) openRefit();
            return true;
        }
        if (!acceptsMovement()) return false;
        int action = fresh ? GLFW.GLFW_PRESS : GLFW.GLFW_REPEAT;
        if (com.example.examplemod.throwables.client.ThrowableClient.inventoryKey(key, scan, action)) return true;
        if (com.example.examplemod.lootspawn.client.GroundLootClient.pickupKey().matches(key, scan)
                && (com.example.examplemod.vitals.client.MedicineClient.active()
                    || com.example.examplemod.vitals.client.BandageChainClient.hasIntent())) {
            if (fresh) com.example.examplemod.vitals.client.MedicineClient.tryUseHeld();
            return true;
        }
        boolean movement = MovementClient.inventoryKey(key, scan, action);
        boolean lean = LeanClient.inventoryKey(key, scan, action);
        if (movement || lean) return true;
        if (ReloadKey.RELOAD_KEY.matches(key, scan)) {
            var stack = mc.player.getMainHandItem();
            if (fresh && stack.getItem() instanceof IGun gun && !gun.useInventoryAmmo(stack))
                IClientPlayerGunOperator.fromLocalPlayer(mc.player).reload();
            return true;
        }
        if (FireSelectKey.FIRE_SELECT_KEY.matches(key, scan)) {
            if (fresh && IGun.mainHandHoldGun(mc.player)) IClientPlayerGunOperator.fromLocalPlayer(mc.player).fireSelect();
            return true;
        }
        if (InspectKey.INSPECT_KEY.matches(key, scan)) {
            if (fresh && IGun.mainHandHoldGun(mc.player)) IClientPlayerGunOperator.fromLocalPlayer(mc.player).inspect();
            return true;
        }
        for (int slot = 0; slot < mc.options.keyHotbarSlots.length; slot++) {
            if (!mc.options.keyHotbarSlots[slot].matches(key, scan)) continue;
            if (fresh && !mc.player.isSpectator()) {
                mc.player.getInventory().selected = slot;
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            }
            return true;
        }
        return directionalKey(key, scan)
                || ShootKey.SHOOT_KEY.matches(key, scan) || AimKey.AIM_KEY.matches(key, scan)
                || mc.options.keyAttack.matches(key, scan) || mc.options.keyUse.matches(key, scan);
    }

    public static boolean keyReleased(int key, int scan, int modifiers) {
        PRESSED.remove(((long) key << 32) ^ (scan & 0xffffffffL));
        if (!inventoryKeyHeld()) closeKeyReady = true;
        boolean movement = MovementClient.inventoryKey(key, scan, GLFW.GLFW_RELEASE);
        boolean lean = LeanClient.inventoryKey(key, scan, GLFW.GLFW_RELEASE);
        boolean item = com.example.examplemod.throwables.client.ThrowableClient.inventoryKey(key, scan, GLFW.GLFW_RELEASE);
        return movement || lean || item || directionalKey(key, scan);
    }

    private static boolean directionalKey(int key, int scan) {
        var options = Minecraft.getInstance().options;
        return options.keyUp.matches(key, scan) || options.keyDown.matches(key, scan)
                || options.keyLeft.matches(key, scan) || options.keyRight.matches(key, scan)
                || options.keyJump.matches(key, scan) || options.keyShift.matches(key, scan)
                || options.keySprint.matches(key, scan);
    }

    private static boolean inventoryKeyHeld() {
        return BindingInput.pollKeyboardHeld(Minecraft.getInstance().options.keyInventory) || BackpackClient.openKeyHeld();
    }

    public static boolean blocksCombat() { return activeScreen() || combatReleaseRequired; }

    private static boolean combatHeld() {
        var mc = Minecraft.getInstance();
        return BindingInput.pollHeld(ShootKey.SHOOT_KEY) || BindingInput.pollHeld(AimKey.AIM_KEY)
                || BindingInput.pollHeld(mc.options.keyAttack) || BindingInput.pollHeld(mc.options.keyUse);
    }

    private static void stopCombat() {
        var mc = Minecraft.getInstance();
        for (var key : new KeyMapping[]{mc.options.keyAttack, mc.options.keyUse, ShootKey.SHOOT_KEY, AimKey.AIM_KEY}) {
            ((KeyMappingAccess) key).taczPubg$setRawDown(false);
            ((KeyMappingAccess) key).taczPubg$setClicks(0);
        }
        if (mc.player != null) {
            var operator = IClientPlayerGunOperator.fromLocalPlayer(mc.player);
            operator.aim(false);
            var data = operator.getDataHolder();
            data.isCharging = false;
            data.chargeProgress = 0;
        }
    }

    /** Screen.removed releases held actions; persistent crouch/prone posture remains intentional. */
    public static void release() {
        PRESSED.clear();
        MovementClient.releaseInventoryInput();
        LeanClient.releaseInventoryInput();
        stopCombat();
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.input.up = player.input.down = player.input.left = player.input.right = false;
            player.input.jumping = player.input.shiftKeyDown = false;
            player.input.forwardImpulse = player.input.leftImpulse = 0;
        }
    }
}
