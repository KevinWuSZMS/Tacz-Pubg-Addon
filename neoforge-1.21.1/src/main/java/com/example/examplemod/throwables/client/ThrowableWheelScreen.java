package com.example.examplemod.throwables.client;

import com.example.examplemod.hud.client.HudLayoutClient;
import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.localization.Texts;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

/** Hold-and-release radial selection with a hidden relative pointer and a neutral center. */
final class ThrowableWheelScreen extends Screen {
    private record Entry(String action, Item item) {}
    private final List<Entry> entries;
    private final KeyMapping trigger;
    private final boolean medicine;
    private final RadialWheelLayout.Pointer pointer = new RadialWheelLayout.Pointer();
    private HudLayoutModel.Rect bounds;
    private double lastMouseX, lastMouseY;
    private boolean cursorCaptured, committed;

    ThrowableWheelScreen(boolean medicine, KeyMapping trigger) {
        super(Texts.of(medicine ? "screen.tacz_pubg.throwable.medicine_wheel" : "screen.tacz_pubg.throwable.throwable_wheel"));
        this.trigger = trigger;
        this.medicine = medicine;
        entries = RadialWheelLayout.actions(medicine).stream().map(action -> new Entry(action,
                BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("tacz_pubg", action)))).toList();
    }

    @Override protected void init() {
        var defaults = RadialWheelRenderer.bounds(width, height);
        bounds = HudLayoutClient.resolve(medicine ? "medicine_wheel" : "throwable_wheel", width, height,
                defaults.x(), defaults.y(), defaults.width(), defaults.height());
        pointer.reset();
        // Keep MouseHandler ungrabbed: grabMouse() closes screens and enables camera turning.
        GLFW.glfwSetInputMode(minecraft.getWindow().getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        cursorCaptured = true;
        samplePointer(true);
    }

    @Override public boolean isPauseScreen() { return false; }

    private boolean canChoose() {
        return minecraft.isWindowActive() && minecraft.player != null && minecraft.player.isAlive()
                && !minecraft.player.isSpectator() && minecraft.level != null;
    }

    private void samplePointer(boolean reset) {
        if (!cursorCaptured) return;
        var window = minecraft.getWindow();
        try (var memory = MemoryStack.stackPush()) {
            var x = memory.mallocDouble(1);
            var y = memory.mallocDouble(1);
            GLFW.glfwGetCursorPos(window.getWindow(), x, y);
            double currentX = x.get(0), currentY = y.get(0);
            if (!reset) pointer.move((currentX - lastMouseX) * width / window.getScreenWidth(),
                    (currentY - lastMouseY) * height / window.getScreenHeight(), RadialWheelRenderer.radius(bounds));
            lastMouseX = currentX;
            lastMouseY = currentY;
        }
    }

    @Override public void tick() {
        if (!canChoose()) { onClose(); return; }
        // Opening a Screen clears KeyMapping.isDown(), so inspect the physical trigger instead.
        if (!com.example.examplemod.keybindings.BindingInput.physicallyHeld(trigger)) choose();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        if (!canChoose()) { onClose(); return; }
        samplePointer(false);
        int[] counts = new int[RadialWheelLayout.SLOT_COUNT];
        var inventory = minecraft.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            for (int i = 0; i < entries.size(); i++) if (stack.is(entries.get(i).item)) counts[i] += stack.getCount();
        }
        RadialWheelRenderer.draw(graphics, bounds, medicine, pointer.hovered(RadialWheelRenderer.radius(bounds)), counts);
    }

    private void choose() {
        // A release may arrive before this frame's render/mouseMoved callback; include the final flick.
        samplePointer(false);
        int hovered = pointer.hovered(RadialWheelRenderer.radius(bounds));
        if (!committed && canChoose() && hovered >= 0) {
            committed = true;
            ThrowableClient.sendQuick(entries.get(hovered).action);
        }
        onClose();
    }

    @Override public void removed() {
        if (cursorCaptured) {
            GLFW.glfwSetInputMode(minecraft.getWindow().getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            minecraft.mouseHandler.setIgnoreFirstMove();
            cursorCaptured = false;
        }
        trigger.setDown(false);
        pointer.reset();
        super.removed();
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (trigger.matchesMouse(button)) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) { choose(); return true; }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { onClose(); return true; }
        return true;
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (trigger.matchesMouse(button) && !com.example.examplemod.keybindings.BindingInput.physicallyHeld(trigger)) choose();
        return true;
    }

    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        if (trigger.matches(key, scan) && !com.example.examplemod.keybindings.BindingInput.physicallyHeld(trigger)) { choose(); return true; }
        return super.keyReleased(key, scan, modifiers);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return true;
    }
}
