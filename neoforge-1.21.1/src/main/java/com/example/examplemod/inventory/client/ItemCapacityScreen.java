package com.example.examplemod.inventory.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.function.Consumer;

/** One global per-item setting, independent of loot-tier probability and stack quantity. */
public final class ItemCapacityScreen extends Screen {
    private final Screen parent;
    private final ItemStack preview;
    private final String key;
    private final double fallback;
    private final Double original;
    private final Consumer<Double> apply;
    private EditBox value;
    private String error = "";
    public ItemCapacityScreen(Screen parent, ItemStack preview, String key, Double original, double fallback, Consumer<Double> apply) {
        super(Component.translatable("screen.tacz_pubg.inventory.item_capacity"));
        this.parent = parent; this.preview = preview.copyWithCount(1); this.key = key;
        this.original = original; this.fallback = fallback; this.apply = apply;
    }
    @Override protected void init() {
        int center = width / 2, top = Math.max(40, height / 2 - 44);
        value = addRenderableWidget(new EditBox(font, center - 70, top + 35, 140, 20, title));
        value.setMaxLength(20);
        value.setValue(Double.toString(original == null ? fallback : original));
        addRenderableWidget(Button.builder(Component.translatable("screen.tacz_pubg.inventory.apply_draft"), b -> {
            try {
                double parsed = Double.parseDouble(value.getValue().trim());
                if (!Double.isFinite(parsed) || parsed < 0 || parsed > 1_000_000) throw new NumberFormatException();
                apply.accept(parsed);
                minecraft.setScreen(parent);
            } catch (NumberFormatException invalid) { error = Component.translatable("screen.tacz_pubg.inventory.capacity_error").getString(); }
        }).bounds(center - 115, top + 66, 112, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.tacz_pubg.inventory.reset_default"), b -> {
            apply.accept(null); minecraft.setScreen(parent);
        }).bounds(center + 3, top + 66, 112, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(center - 50, height - 28, 100, 20).build());
        setInitialFocus(value);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int center = width / 2, top = Math.max(40, height / 2 - 44);
        graphics.drawCenteredString(font, title, center, 15, 0xffffffff);
        graphics.renderItem(preview, center - 8, top - 16);
        graphics.drawCenteredString(font, preview.getHoverName(), center, top + 3, 0xffffffff);
        graphics.drawCenteredString(font, key, center, top + 17, 0xffaaaaaa);
        graphics.drawCenteredString(font, Component.translatable("screen.tacz_pubg.inventory.capacity_hint"), center, top + 94, 0xffaaaaaa);
        if (!error.isEmpty()) graphics.drawCenteredString(font, error, center, height - 45, 0xffff8888);
    }
}
