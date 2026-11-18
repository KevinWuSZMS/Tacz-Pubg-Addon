package com.example.examplemod.backpack.client;

import com.example.examplemod.backpack.BackpackMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class BackpackScreen extends AbstractContainerScreen<BackpackMenu> {
    public BackpackScreen(BackpackMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 224;
        inventoryLabelY = 71;
    }
    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, 0xff121619);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xffc2c4bd);
        graphics.fill(x + 6, y + 20, x + 170, y + 65, 0xff414c46);
        for (var slot : menu.slots) {
            graphics.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, 0xff50554f);
            graphics.fill(x + slot.x, y + slot.y, x + slot.x + 16, y + slot.y + 16, 0xff8a9187);
        }
        var state = BackpackClient.current();
        if (state != null) {
            int usedWidth = (int) Math.min(150L, (long) state.used() * 150 / Math.max(1, state.capacity()));
            graphics.fill(x + 13, y + 52, x + 163, y + 56, 0xff212723);
            graphics.fill(x + 13, y + 52, x + 13 + usedWidth, y + 56,
                    state.used() > state.capacity() ? 0xffe76a5c : 0xffd5b65f);
        }
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, 0xff20251e, false);
        graphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xff343a31, false);
        graphics.drawString(font, Component.translatable("screen.tacz_pubg.inventory.recovery"), 8, 169, 0xff343a31, false);
        graphics.drawString(font, Component.translatable("screen.tacz_pubg.backpack.equipped"), 13, 31, 0xffe6e8e0, false);
        var state = BackpackClient.current();
        if (state != null) {
            String label = BackpackClient.units(state.used()) + " / " + BackpackClient.units(state.capacity());
            graphics.drawCenteredString(font, label, 88, 59, state.used() > state.capacity() ? 0xffa32118 : 0xff303a2d);
        }
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (mouseX >= leftPos + 6 && mouseX < leftPos + 170 && mouseY >= topPos + 49 && mouseY < topPos + 69)
            graphics.renderTooltip(font, Component.translatable("screen.tacz_pubg.backpack.hint"), mouseX, mouseY);
    }
}
