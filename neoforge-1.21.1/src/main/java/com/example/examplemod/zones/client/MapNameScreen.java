package com.example.examplemod.zones.client;

import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.localization.Texts;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Naming precedes drawing a new map; an empty edit box keeps its localized default hint. */
final class MapNameScreen extends Screen {
    private final Screen parent;
    private final String defaultName;
    private final Consumer<String> accept;
    private String value = "";
    private EditBox name;
    private int left, top, panelWidth;

    MapNameScreen(Screen parent, String defaultName, Consumer<String> accept) {
        super(Texts.of("screen.tacz_pubg.map_list.create"));
        this.parent = parent;
        this.defaultName = defaultName;
        this.accept = accept;
    }

    @Override protected void init() {
        panelWidth = Math.max(160, Math.min(360, width - 32));
        left = (width - panelWidth) / 2;
        top = Math.max(32, height / 2 - 54);
        name = addRenderableWidget(new EditBox(font, left, top + 22, panelWidth, 20,
                Texts.of("screen.tacz_pubg.map_config.map_name")));
        name.setMaxLength(80);
        name.setFilter(text -> text.codePoints().noneMatch(Character::isISOControl));
        name.setHint(Texts.component(defaultName));
        name.setValue(value);
        name.setResponder(text -> value = text);
        int half = (panelWidth - 8) / 2;
        addRenderableWidget(ConfigUi.button(Texts.of("gui.cancel"), ignored -> onClose())
                .bounds(left, top + 84, half, 20).build());
        addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.map_name.draw"), ignored -> accept())
                .bounds(left + half + 8, top + 84, half, 20).build());
        setInitialFocus(name);
    }

    private void accept() {
        minecraft.setScreen(parent);
        accept.accept(value.isBlank() ? defaultName : value.strip());
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { accept(); return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, top - 20, 0xffffffff);
        graphics.drawString(font, Texts.of("screen.tacz_pubg.map_config.map_name"), left, top + 7, 0xffeeeeee);
        graphics.drawWordWrap(font, Texts.of("screen.tacz_pubg.map_name.hint"), left, top + 51,
                panelWidth, 0xffb5d9ff);
    }
}
