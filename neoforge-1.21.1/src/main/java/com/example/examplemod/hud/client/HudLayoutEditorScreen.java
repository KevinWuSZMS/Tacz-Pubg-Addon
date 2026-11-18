package com.example.examplemod.hud.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** A full-size clear game preview; ESC exposes the four explicit draft actions. */
public final class HudLayoutEditorScreen extends Screen {
    private final Screen parent;
    private boolean started, committed, dragging, menuOpen;
    private int selected = -1;
    private double dragOffsetX, dragOffsetY;
    private String error = "";

    public HudLayoutEditorScreen(Screen parent) {
        super(Component.translatable("screen.tacz_pubg.hud_layout.title"));
        this.parent = parent;
    }
    @Override protected void init() {
        if (!started) {
            HudLayoutClient.beginEdit();
            started = true;
            if (!HudLayoutClient.loadError().isEmpty())
                error = Component.translatable("screen.tacz_pubg.hud_layout.load_error").getString();
        }
        stopDragging();
        HudLayoutClient.resized(width, height);
        if (!menuOpen) return;
        var buttons = HudLayoutModel.menu(width, height).buttons();
        menuButton(buttons.get(0), "screen.tacz_pubg.hud_layout.save_exit", this::saveAndExit);
        menuButton(buttons.get(1), "screen.tacz_pubg.hud_layout.discard_exit", this::discardAndExit);
        menuButton(buttons.get(2), "screen.tacz_pubg.hud_layout.continue_editing", () -> setMenu(false));
        menuButton(buttons.get(3), "screen.tacz_pubg.hud_layout.restore_initial", () -> {
            HudLayoutClient.resetAll();
            error = "";
            setMenu(false);
        });
    }
    private void menuButton(HudLayoutModel.Rect bounds, String key, Runnable action) {
        addRenderableWidget(Button.builder(Component.translatable(key), ignored -> action.run())
                .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height()).build());
    }
    private String id() { return selected < 0 ? null : HudLayoutClient.IDS.get(selected); }
    private void select(int index) {
        selected = Math.floorMod(index, HudLayoutClient.IDS.size());
        stopDragging();
    }
    private void stopDragging() { dragging = false; setDragging(false); }
    private void setMenu(boolean open) {
        stopDragging();
        menuOpen = open;
        rebuildWidgets();
    }
    private void saveAndExit() {
        try {
            HudLayoutClient.saveEdit();
            committed = true;
            minecraft.setScreen(parent);
        } catch (IOException | RuntimeException failure) {
            // Stay in this same screen/session so retry and discard both retain the entry snapshot.
            error = Component.translatable("screen.tacz_pubg.hud_layout.save_error",
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()).getString();
        }
    }
    private void discardAndExit() {
        HudLayoutClient.cancelEdit();
        committed = true;
        minecraft.setScreen(parent);
    }
    @Override public void onClose() { setMenu(true); }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void removed() {
        if (!committed) HudLayoutClient.cancelEdit();
        started = false;
        super.removed();
    }
    @Override public boolean isPauseScreen() { return false; }
    // Screen.renderWithTooltip is final and calls render; super.render then calls this virtual
    // background entry. Both overrides prevent blur even after all previews have already drawn.
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override protected void renderBlurredBackground(float partialTick) {}

    private List<String> drawOrder() {
        var result = new ArrayList<>(HudLayoutClient.IDS);
        if (id() != null) { result.remove(id()); result.add(id()); }
        return result;
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.enableScissor(0, 0, width, height);
        for (String component : drawOrder()) {
            // These two editable entries share one anchor; show only the selected action example.
            if (component.equals("medicine_progress") && "loot_pickup".equals(id())) continue;
            boolean chosen = component.equals(id());
            var rectangle = HudLayoutClient.previewRect(component, width, height);
            HudLayoutClient.definition(component).preview().draw(graphics, rectangle, chosen);
            outline(graphics, rectangle, chosen ? 0xFFFFDB78 : 0x706D8B9E);
        }
        graphics.disableScissor();
        if (menuOpen) {
            var layout = HudLayoutModel.menu(width, height);
            var panel = layout.panel();
            graphics.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + panel.height(), 0xEF141A21);
            graphics.drawCenteredString(font, title, width / 2, panel.y() + 7, 0xFFF2F1E7);
            String message = error;
            if (message.isEmpty() && MinimapPlacementPolicy.nativeMapComponent(id()) && !XaeroHudBridge.available())
                message = Component.translatable("screen.tacz_pubg.hud_layout.xaero_unavailable").getString();
            int bottom = panel.y() + panel.height() - 4;
            int lineY = layout.messageY();
            for (var line : font.split(Component.literal(message), Math.max(1, panel.width() - 20))) {
                if (lineY + font.lineHeight > bottom) break;
                graphics.drawString(font, line, panel.x() + 10, lineY, 0xFFFF8E85);
                lineY += font.lineHeight;
            }
        } else {
            String hint = Component.translatable("screen.tacz_pubg.hud_layout.escape_hint").getString();
            graphics.drawString(font, font.plainSubstrByWidth(hint, Math.max(1, width - 12)),
                    6, Math.max(0, height - font.lineHeight - 6), 0xFFDFE6EC);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    private static void outline(GuiGraphics graphics, HudLayoutModel.Rect rectangle, int color) {
        int x = rectangle.x(), y = rectangle.y(), right = x + rectangle.width(), bottom = y + rectangle.height();
        graphics.fill(x, y, right, y + 1, color); graphics.fill(x, bottom - 1, right, bottom, color);
        graphics.fill(x, y, x + 1, bottom, color); graphics.fill(right - 1, y, right, bottom, color);
    }
    private boolean movable() { return MinimapPlacementPolicy.movable(id(), XaeroHudBridge.available()); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (menuOpen) return super.mouseClicked(x, y, button);
        if (button != 0 || x < 0 || y < 0 || x >= width || y >= height) return false;
        var order = drawOrder(); Collections.reverse(order);
        for (String component : order) {
            var rectangle = HudLayoutClient.previewRect(component, width, height);
            if (!rectangle.contains(x, y)) continue;
            select(HudLayoutClient.IDS.indexOf(component));
            if (!movable()) return true;
            dragOffsetX = x - rectangle.x(); dragOffsetY = y - rectangle.y();
            dragging = true; setDragging(true);
            return true;
        }
        selected = -1; stopDragging(); return false;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (!menuOpen && dragging && button == 0) {
            HudLayoutClient.move(id(), x - dragOffsetX, y - dragOffsetY, width, height);
            return true;
        }
        return menuOpen && super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && dragging) { stopDragging(); return true; }
        return menuOpen && super.mouseReleased(x, y, button);
    }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { setMenu(!menuOpen); return true; }
        if (menuOpen) return super.keyPressed(key, scanCode, modifiers);
        if (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_RIGHT_BRACKET || key == GLFW.GLFW_KEY_TAB) {
            select(selected + (hasShiftDown() ? -1 : 1)); return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_LEFT_BRACKET) { select(selected - 1); return true; }
        int dx = key == GLFW.GLFW_KEY_LEFT ? -1 : key == GLFW.GLFW_KEY_RIGHT ? 1 : 0;
        int dy = key == GLFW.GLFW_KEY_UP ? -1 : key == GLFW.GLFW_KEY_DOWN ? 1 : 0;
        if ((dx != 0 || dy != 0) && movable()) {
            int amount = hasShiftDown() ? 10 : 1;
            var rectangle = HudLayoutClient.previewRect(id(), width, height);
            HudLayoutClient.move(id(), rectangle.x() + dx * amount, rectangle.y() + dy * amount, width, height);
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }
}
