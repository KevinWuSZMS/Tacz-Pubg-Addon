package com.example.examplemod.throwables.client;

import com.example.examplemod.hud.client.HudLayoutClient;
import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.keybindings.BindingInput;
import com.example.examplemod.localization.Texts;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.lwjgl.glfw.GLFW;

/** Hold-and-release radial selection with a hidden relative pointer and a neutral center. */
final class ThrowableWheelScreen extends Screen {
    private record Entry(String action, Item item) {}
    private final List<Entry> entries;
    private final KeyMapping trigger;
    private final boolean medicine;
    private final RadialWheelLayout.Pointer pointer = new RadialWheelLayout.Pointer();
    private HudLayoutModel.Rect bounds;
    private boolean cursorCaptured, closed;

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
        captureCursor();
    }

    @Override public boolean isPauseScreen() { return false; }

    private boolean canChoose() {
        return minecraft.isWindowActive() && minecraft.player != null && minecraft.player.isAlive()
                && !minecraft.player.isSpectator() && minecraft.level != null;
    }

    private void captureCursor() {
        minecraft.mouseHandler.setIgnoreFirstMove();
        GLFW.glfwSetInputMode(minecraft.getWindow().getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        cursorCaptured = true;
    }

    /** Consume each native movement in order, including movements before the release event. */
    void movePointer(double dx, double dy) {
        if (closed || !cursorCaptured || !canChoose() || !BindingInput.pollHeld(trigger)) return;
        var window = minecraft.getWindow();
        if (GLFW.glfwGetInputMode(window.getWindow(), GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_DISABLED) return;
        pointer.move(dx * width / Math.max(1, window.getScreenWidth()),
                dy * height / Math.max(1, window.getScreenHeight()), RadialWheelRenderer.radius(bounds));
    }

    private boolean updateOpenState() {
        if (closed) return false;
        if (!canChoose()) { onClose(); return false; }
        // Poll native state even if a cancelled callback left the logical key cache held.
        ThrowableClient.releaseWheelTrigger();
        if (closed) return false;
        if (GLFW.glfwGetInputMode(minecraft.getWindow().getWindow(), GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_DISABLED) {
            captureCursor();
        }
        return true;
    }

    @Override public void tick() {
        updateOpenState();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        if (!updateOpenState()) return;
        RadialWheelRenderer.draw(graphics, bounds, medicine, pointer.hovered(RadialWheelRenderer.radius(bounds)), counts());
    }

    private int[] counts() {
        int[] counts = new int[RadialWheelLayout.SLOT_COUNT];
        var inventory = minecraft.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            for (int i = 0; i < entries.size(); i++) if (stack.is(entries.get(i).item)) counts[i] += stack.getCount();
        }
        return counts;
    }

    void confirmRelease() {
        if (closed) return;
        int hovered = pointer.hovered(RadialWheelRenderer.radius(bounds));
        String action = canChoose() && hovered >= 0 && counts()[hovered] > 0 ? entries.get(hovered).action : null;
        onClose();
        if (action != null) ThrowableClient.sendQuick(action);
    }

    @Override public void onClose() {
        if (closed) return;
        closed = true;
        ThrowableClient.wheelClosed();
        super.onClose();
    }

    @Override public void removed() {
        closed = true;
        ThrowableClient.wheelClosed();
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
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { onClose(); return true; }
        return true;
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        ThrowableClient.releaseWheelTrigger();
        return true;
    }

    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        ThrowableClient.releaseWheelTrigger();
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return true;
    }
}
