package com.example.examplemod.inventory.client;

import com.example.examplemod.keybindings.BindingInput;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.gui.GunRefitScreen;
import com.tacz.guns.client.input.RefitKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/** Native TaCZ widgets, model animation and sounds, with an explicit PUBG return destination. */
public final class PubgGunRefitScreen extends GunRefitScreen {
    private final PubgInventoryScreen previous;
    private final LocalPlayer owner;
    private final ClientLevel level;
    private final Item gunItem;
    private final ResourceLocation gunId;
    private final PubgRefitSession session;
    private boolean inventoryKeyReady, refitKeyReady;

    PubgGunRefitScreen(PubgInventoryScreen previous, int slot) {
        this.previous = previous;
        var mc = Minecraft.getInstance();
        owner = mc.player;
        level = mc.level;
        var stack = owner.getInventory().getItem(slot);
        gunItem = stack.getItem();
        gunId = IGun.getIGunOrNull(stack).getGunId(stack);
        session = new PubgRefitSession(slot);
        inventoryKeyReady = !BindingInput.pollKeyboardHeld(mc.options.keyInventory);
        refitKeyReady = !BindingInput.pollKeyboardHeld(RefitKey.REFIT_KEY);
    }

    private boolean targetValid() {
        var mc = Minecraft.getInstance();
        var stack = owner.getMainHandItem();
        var gun = IGun.getIGunOrNull(stack);
        return session.matches(mc.player == owner && mc.level == level && owner.isAlive() && !owner.isSpectator(),
                owner.getInventory().selected, gun != null && stack.getItem() == gunItem
                        && gunId.equals(gun.getGunId(stack)) && !gun.hasAttachmentLock(stack), PubgInventoryInput.inMatch());
    }

    private boolean checkTarget() {
        if (targetValid()) return true;
        finish(false);
        return false;
    }

    @Override public void init() {
        if (checkTarget()) super.init();
    }

    @Override public void tick() {
        if (!checkTarget()) return;
        var mc = Minecraft.getInstance();
        if (!BindingInput.pollKeyboardHeld(mc.options.keyInventory)) inventoryKeyReady = true;
        if (!BindingInput.pollKeyboardHeld(RefitKey.REFIT_KEY)) refitKeyReady = true;
        super.tick();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (checkTarget()) super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        // Consume the inventory key here: calling native onClose would return to the backpack.
        if (Minecraft.getInstance().options.keyInventory.matches(key, scan)) {
            if (inventoryKeyReady) finish(true);
            return true;
        }
        if (!checkTarget()) return true;
        if (RefitKey.REFIT_KEY.matches(key, scan)) {
            if (refitKeyReady) onClose();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        var mc = Minecraft.getInstance();
        if (mc.options.keyInventory.matches(key, scan)) { inventoryKeyReady = true; return true; }
        if (RefitKey.REFIT_KEY.matches(key, scan)) { refitKeyReady = true; return true; }
        return super.keyReleased(key, scan, modifiers);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        return !checkTarget() || super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        return !checkTarget() || super.mouseReleased(x, y, button);
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return !checkTarget() || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        return !checkTarget() || super.mouseScrolled(x, y, dx, dy);
    }

    @Override public void onClose() { finish(false); }

    private void finish(boolean closeAll) {
        var mc = Minecraft.getInstance();
        boolean canReturn = mc.player == owner && mc.level == level && PubgInventoryInput.inMatch();
        var exit = session.close(closeAll, canReturn, targetValid());
        if (exit.destination() == PubgRefitSession.Destination.KEEP) return;
        // Native onClose uploads laser colors from main hand. Never run it for another selected gun.
        if (exit.flushNative()) super.onClose();
        mc.setScreen(exit.destination() == PubgRefitSession.Destination.INVENTORY ? previous : null);
    }

    @Override public void removed() {
        super.removed();
        PubgInventoryInput.release();
    }
}
