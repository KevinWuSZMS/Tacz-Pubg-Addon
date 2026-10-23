package com.example.examplemod.zones.client;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** An explicit post-creation choice. Dismissing this screen never submits a default answer. */
public final class MapEntityRecordingScreen extends Screen {
    private final Screen parent;
    private final MapDefinition map;
    private final Consumer<Boolean> choose;
    private List<FormattedCharSequence> description = List.of();
    private int descriptionY;

    MapEntityRecordingScreen(Screen parent, MapDefinition map, Consumer<Boolean> choose) {
        super(Texts.of("screen.tacz_pubg.map_entities.title"));
        this.parent = parent; this.map = map; this.choose = choose;
    }

    @Override protected void init() {
        int buttonWidth = Math.max(180, Math.min(490, width - 32));
        description = font.split(Texts.of("screen.tacz_pubg.map_entities.description", map.id(), map.name()), buttonWidth);
        Component yes = Texts.of("screen.tacz_pubg.map_entities.record_all"), no = Texts.of("screen.tacz_pubg.map_entities.record_none");
        int yesHeight = optionHeight(font.split(yes, buttonWidth - 20).size());
        int noHeight = optionHeight(font.split(no, buttonWidth - 20).size());
        int contentHeight = 24 + description.size() * font.lineHeight + 12 + yesHeight + 6 + noHeight + 12 + 20;
        int top = Math.max(12, (height - contentHeight) / 2);
        descriptionY = top + 24;
        int x = (width - buttonWidth) / 2, y = descriptionY + description.size() * font.lineHeight + 12;
        addRenderableWidget(new WrappedOption(x, y, buttonWidth, yesHeight, yes, () -> choose.accept(true)));
        addRenderableWidget(new WrappedOption(x, y + yesHeight + 6, buttonWidth, noHeight, no, () -> choose.accept(false)));
        addRenderableWidget(Button.builder(Texts.of("screen.tacz_pubg.map_entities.decide_later"), b -> onClose())
                .bounds(x, y + yesHeight + noHeight + 18, buttonWidth, 20).build());
    }

    static int optionHeight(int lines) { return Math.max(24, 12 + Math.max(1, lines) * 9); }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics graphics, int mx, int my, float partial) {
        super.render(graphics, mx, my, partial);
        graphics.drawCenteredString(font, title, width / 2, descriptionY - 24, 0xFFFFFFFF);
        for (int i = 0; i < description.size(); i++) graphics.drawCenteredString(font, description.get(i), width / 2,
                descriptionY + i * font.lineHeight, 0xFFD7DDE2);
    }

    private final class WrappedOption extends Button {
        private final List<FormattedCharSequence> lines;
        WrappedOption(int x, int y, int width, int height, Component label, Runnable action) {
            super(x, y, width, height, label, b -> action.run(), DEFAULT_NARRATION);
            lines = font.split(label, width - 20);
        }
        @Override protected void renderWidget(GuiGraphics graphics, int mx, int my, float partial) {
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), isHoveredOrFocused() ? 0xFF647481 : 0xFF424A52);
            graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), isHoveredOrFocused() ? 0xFFFFFFFF : 0xFF919BA3);
            int y = getY() + (getHeight() - lines.size() * font.lineHeight) / 2;
            for (var line : lines) { graphics.drawCenteredString(font, line, getX() + getWidth() / 2, y, 0xFFFFFFFF); y += font.lineHeight; }
        }
    }
}
