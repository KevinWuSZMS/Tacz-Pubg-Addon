package com.example.examplemod.zones.client;

import com.example.examplemod.localization.Texts;

import java.util.function.Consumer;

import com.example.examplemod.zones.MapVisualStyle;
import com.example.examplemod.configui.client.ConfigUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A palette edits only its parent's unsaved string draft, including incomplete or invalid input. */
final class MapZoneColorPicker extends Screen {
    private static final int[] PALETTE = {
            0xFFFFFF, 0xEAF5FF, 0x93A9B5, 0x333B49, 0x101820, 0x000000,
            0x1549D8, 0x003CAA, 0x008BFF, 0x46C8FF, 0x7659E8, 0xC16DFF,
            0xEF3038, 0xB51324, 0xFF7B37, 0xFFDC65, 0xF7FFFF, 0xF5AAC7,
            0x1B995A, 0x54D985, 0x23B9B2, 0x9BE4B4, 0xA9744F, 0xD8BD99
    };
    private final Screen parent;
    private final Consumer<String> edit;
    private String raw;
    private EditBox input;
    private int left, top, cellWidth, palettePage, paletteRows, palettePages = 1;

    MapZoneColorPicker(Screen parent, String label, String raw, Consumer<String> edit) {
        super(Texts.component(label + Texts.tr("screen.tacz_pubg.zone_settings.palette")));
        this.parent = parent;
        this.raw = raw;
        this.edit = edit;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(310, Math.max(170, width - 24));
        left = (width - panelWidth) / 2;
        top = Math.max(23, Math.min(55, height - 225));
        input = ConfigUi.editBox(font, left, top + 15, panelWidth - 60, 20, Texts.of("screen.tacz_pubg.map_zone_color_picker.six_digit_hex_rgb"));
        input.setMaxLength(80);
        input.setValue(Texts.resolve(raw));
        input.setResponder(value -> { raw = value; edit.accept(value); });
        input.setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.map_zone_color_picker.six_digit_hexadecimal_rgb_e_g_d_d")));
        addRenderableWidget(input);
        cellWidth = (panelWidth - 5 * 4) / 6;
        // Reserve footer space before choosing rows. Very short windows still retain the hex editor.
        paletteRows = Math.clamp(1 + (int) Math.floor((height - 60.0 - (top + 62) - 21) / 25), 0, 4);
        int perPage = Math.max(1, paletteRows * 6);
        palettePages = Math.max(1, (PALETTE.length + perPage - 1) / perPage);
        palettePage = Math.clamp(palettePage, 0, palettePages - 1);
        int start = palettePage * perPage;
        for (int index = start; paletteRows > 0 && index < Math.min(PALETTE.length, start + perPage); index++) {
            int rgb = PALETTE[index];
            int local = index - start;
            int x = left + local % 6 * (cellWidth + 4), y = top + 62 + local / 6 * 25;
            addRenderableWidget(ConfigUi.button(Component.empty(), ignored -> input.setValue(Texts.resolve(MapVisualStyle.hex(rgb))))
                    .bounds(x, y, cellWidth, 21)
                    .tooltip(Tooltip.create(Texts.component("#" + MapVisualStyle.hex(rgb))))
                    .build());
        }
        if (paletteRows > 0 && palettePages > 1) {
            addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.item_picker.previous"), ignored -> { palettePage--; rebuildWidgets(); })
                    .bounds(left, height - 56, 65, 20).build()).active = palettePage > 0;
            addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.item_picker.next"), ignored -> { palettePage++; rebuildWidgets(); })
                    .bounds(left + panelWidth - 65, height - 56, 65, 20).build()).active = palettePage + 1 < palettePages;
        }
        addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.map_zone_color_picker.back_color_settings"), ignored -> onClose())
                .bounds(left, height - 30, panelWidth, 20)
                .tooltip(Tooltip.create(Texts.of("screen.tacz_pubg.map_zone_color_picker.write_color_draft_click_save_configuration_after_returning"))).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, Texts.resolve(title), width / 2, 12, 0xFFFFFFFF);
        graphics.drawString(font, Texts.of("screen.tacz_pubg.map_zone_color_picker.six_digit_hex_rgb_optional").getString(), left, top + 3, 0xFFE0E0E0);
        int panelWidth = Math.min(310, Math.max(170, width - 24));
        try {
            int rgb = MapVisualStyle.parseHex(raw);
            graphics.fill(left + panelWidth - 53, top + 15, left + panelWidth, top + 35, 0xFF000000 | rgb);
            graphics.drawString(font, Texts.resolve("#" + MapVisualStyle.hex(rgb)), left, top + 42, 0xFFB0E0B0);
        } catch (IllegalArgumentException exception) {
            graphics.fill(left + panelWidth - 53, top + 15, left + panelWidth, top + 35, 0xFF491A25);
            graphics.drawString(font, Texts.of("screen.tacz_pubg.map_zone_color_picker.enter_six_digit_rgb_color_input_retained").getString(), left, top + 42, 0xFFFF8888);
        }
        int perPage = Math.max(1, paletteRows * 6), start = palettePage * perPage;
        for (int index = start; paletteRows > 0 && index < Math.min(PALETTE.length, start + perPage); index++) {
            int local = index - start;
            int x = left + local % 6 * (cellWidth + 4), y = top + 62 + local / 6 * 25;
            graphics.fill(x + 3, y + 3, x + cellWidth - 3, y + 18, 0xFF000000 | PALETTE[index]);
        }
        if (paletteRows > 0 && palettePages > 1) {
            graphics.drawCenteredString(font, Texts.resolve((palettePage + 1) + "/" + palettePages), width / 2, height - 50, 0xFFB5D9FF);
        } else if (height >= 150) {
            graphics.drawCenteredString(font, Texts.of("screen.tacz_pubg.map_zone_color_picker.click_save_configuration_after_returning_apply_colors").getString(), width / 2, height - 46, 0xFFB5D9FF);
        }
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
    }
}
