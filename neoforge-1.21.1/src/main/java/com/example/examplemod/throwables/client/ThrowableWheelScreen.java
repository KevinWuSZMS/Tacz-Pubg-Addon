package com.example.examplemod.throwables.client;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.throwables.ThrowableKind;
import com.example.examplemod.throwables.ThrowableRegistration;
import com.example.examplemod.vitals.PubgBoostItems;
import com.example.examplemod.vitals.PubgMedicineItems;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Hold-and-release radial selection with an explicit mouse-click option. */
final class ThrowableWheelScreen extends Screen {
    private record Entry(String action, Item item) {}
    private final List<Entry> entries = new ArrayList<>();
    private final KeyMapping trigger;
    private final boolean medicine;
    private int hovered = -1;
    private boolean committed;
    ThrowableWheelScreen(boolean medicine, KeyMapping trigger) {
        super(Texts.of(medicine ? "screen.tacz_pubg.throwable.medicine_wheel" : "screen.tacz_pubg.throwable.throwable_wheel"));
        this.trigger = trigger;
        this.medicine = medicine;
        if (medicine) {
            entries.add(new Entry("med_kit", PubgMedicineItems.MED_KIT.get()));
            entries.add(new Entry("first_aid_kit", PubgMedicineItems.FIRST_AID_KIT.get()));
            entries.add(new Entry("bandage", PubgMedicineItems.BANDAGE.get()));
            entries.add(new Entry("energy_drink", PubgBoostItems.ENERGY_DRINK.get()));
            entries.add(new Entry("painkiller", PubgBoostItems.PAINKILLER.get()));
            entries.add(new Entry("adrenaline_syringe", PubgBoostItems.ADRENALINE_SYRINGE.get()));
        } else for (ThrowableKind kind : ThrowableKind.values()) entries.add(new Entry(kind.id, ThrowableRegistration.ITEMS.get(kind).get()));
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0x88090E14);
        int cx = width / 2, cy = height / 2;
        double ring = Math.min(82, Math.min(width, height) * .3);
        int boxWidth = (int) Math.ceil(ring * 2 + 60), boxHeight = (int) Math.ceil(ring * 2 + 85);
        var bounds = com.example.examplemod.hud.client.HudLayoutClient.resolve(
                medicine ? "medicine_wheel" : "throwable_wheel", width, height,
                cx - boxWidth / 2, cy - boxHeight / 2, boxWidth, boxHeight);
        cx = bounds.x() + boxWidth / 2;
        cy = bounds.y() + boxHeight / 2;
        hovered = -1;
        graphics.drawCenteredString(font, font.plainSubstrByWidth(title.getString(), boxWidth - 8),
                cx, Math.max(8, (int) (cy - ring - 38)), 0xFFFFE4A0);
        for (int i = 0; i < entries.size(); i++) {
            double angle = -Math.PI / 2 + i * Math.PI * 2 / entries.size();
            int x = cx + (int) (Math.cos(angle) * ring), y = cy + (int) (Math.sin(angle) * ring);
            if (Math.hypot(mouseX - x, mouseY - y) < 29) hovered = i;
        }
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            double angle = -Math.PI / 2 + i * Math.PI * 2 / entries.size();
            int x = cx + (int) (Math.cos(angle) * ring), y = cy + (int) (Math.sin(angle) * ring);
            int count = 0;
            if (minecraft.player != null) for (int slot = 0; slot < minecraft.player.getInventory().getContainerSize(); slot++) {
                var stack = minecraft.player.getInventory().getItem(slot);
                if (stack.is(entry.item)) count += stack.getCount();
            }
            graphics.fill(x - 25, y - 25, x + 25, y + 25, hovered == i ? 0xDD705D32 : 0xCC26313C);
            graphics.renderItem(new ItemStack(entry.item), x - 8, y - 13);
            graphics.drawCenteredString(font, String.valueOf(count), x, y + 8, count > 0 ? 0xFFFFFFFF : 0xFF80858B);
            if (hovered == i) graphics.drawCenteredString(font,
                    font.plainSubstrByWidth(entry.item.getName(new ItemStack(entry.item)).getString(), boxWidth - 8),
                    cx, cy - 4, 0xFFFFFFFF);
        }
        String hint = Texts.of("screen.tacz_pubg.throwable.wheel_hint").getString();
        float hintScale = Math.min(1, (boxWidth - 8f) / Math.max(1, font.width(hint)));
        graphics.pose().pushPose();
        graphics.pose().translate(cx, Math.min(height - 13, (int) (cy + ring + 32)), 0);
        graphics.pose().scale(hintScale, hintScale, 1);
        graphics.drawCenteredString(font, hint, 0, 0, 0xFFDDDDDD);
        graphics.pose().popPose();
    }
    private void choose() {
        if (!committed && hovered >= 0 && hovered < entries.size()) {
            committed = true; ThrowableClient.sendQuick(entries.get(hovered).action);
        }
        onClose();
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0) { choose(); return true; }
        if (button == 1) { onClose(); return true; }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        if (trigger.matches(key, scan)) { trigger.setDown(false); choose(); return true; }
        return super.keyReleased(key, scan, modifiers);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
}
