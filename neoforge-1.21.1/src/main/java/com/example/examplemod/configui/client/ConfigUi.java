package com.example.examplemod.configui.client;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Native screen chrome and a bounded authoring session. No HTML loading or browser dependency. */
public final class ConfigUi {
    /** A cached independent editor exposes only whether closing would discard its work. */
    public interface DraftPage { boolean hasUnsavedChanges(); }
    public static final int TABS_Y = ConfigUiLayout.TABS_Y;
    public static final int TABS_HEIGHT = ConfigUiLayout.TABS_HEIGHT;
    public static final int CONTENT_TOP = ConfigUiLayout.CONTENT_TOP;
    private static final String[] LABELS = {Texts.tr("screen.tacz_pubg.config_ui.general"), Texts.tr("screen.tacz_pubg.config_ui.default_experimental"), Texts.tr("screen.tacz_pubg.map_editor.title"), Texts.tr("screen.tacz_pubg.config_ui.create_match")};
    private static final Screen[] PAGES = new Screen[4];
    private static Screen currentRoot;
    private static boolean developerMode;

    private ConfigUi() {}
    public static void begin(Screen root) {
        currentRoot = root;
        java.util.Arrays.fill(PAGES, null);
        PAGES[0] = PAGES[1] = root;
        developerMode = false;
    }
    public static void remember(Screen child, Screen parent, int tab) {
        if (tab < 0 || tab >= PAGES.length) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.config_ui.unknown_config_tab"));
        if (currentRoot == null || parent == null || !belongs(parent)) begin(parent == null ? child : parent);
        PAGES[tab] = child;
    }
    private static boolean belongs(Screen screen) {
        if (screen == currentRoot) return true;
        for (Screen page : PAGES) if (page == screen) return true;
        return false;
    }
    public static Screen root(Screen owner) { return belongs(owner) ? currentRoot : owner; }
    public static Screen remembered(Screen root, int tab) {
        return root == currentRoot && tab >= 0 && tab < PAGES.length ? PAGES[tab] : null;
    }
    public static boolean hasOtherDrafts(Screen root) {
        if (root != currentRoot) return false;
        for (int i = 2; i < PAGES.length; i++)
            if (PAGES[i] instanceof DraftPage draft && draft.hasUnsavedChanges()) return true;
        return false;
    }
    public static void forget(Screen child) {
        if (child == currentRoot) { end(child); return; }
        for (int i = 2; i < PAGES.length; i++) if (PAGES[i] == child) PAGES[i] = null;
    }
    public static void end(Screen root) {
        if (currentRoot != root) return;
        clear();
    }
    /** A disconnect ends the complete editing session, including cached child pages. */
    public static void clear() {
        currentRoot = null;
        java.util.Arrays.fill(PAGES, null);
        developerMode = false;
    }
    public static boolean developerMode() { return developerMode; }
    public static void setDeveloperMode(boolean enabled) { developerMode = enabled; }

    public static List<Button> tabs(int width, int active, Consumer<Integer> navigate) {
        List<Button> result = new ArrayList<>(4);
        var bounds = ConfigUiLayout.tabs(width);
        for (int i = 0; i < 4; i++) {
            final int index = i;
            var b = bounds.get(i);
            Button button = new Tab(b, i, i == active, ignored -> navigate.accept(index));
            button.setTooltip(Tooltip.create(Texts.component(switch (i) {
                case 0 -> Texts.tr("screen.tacz_pubg.config_ui.loot_probabilities_ammunition_supply_bombardment_properties_zone_colors");
                case 1 -> Texts.tr("screen.tacz_pubg.config_ui.other_settings_description");
                case 2 -> Texts.tr("screen.tacz_pubg.config_ui.draw_boundaries_circles_routes_real_map_coordinate_inputs");
                default -> Texts.tr("screen.tacz_pubg.config_ui.configure_participants_teams_circle_stages_matches_saved_maps");
            })));
            result.add(button);
        }
        return List.copyOf(result);
    }
    public static void renderHeader(GuiGraphics graphics, Font font, int width,
            String title, String status, int statusColor) {
        graphics.drawCenteredString(font, Texts.resolve(Texts.component(title)), width / 2, 7, 0xffffffff);
        if (status != null && !status.isBlank())
            graphics.drawString(font, Texts.resolve(font.plainSubstrByWidth(Texts.resolve(status), Math.max(20, width - 16))), 8, 22, statusColor);
    }
    /** Use Minecraft's textured button, including its hover, disabled and focus states. */
    public static Button.Builder button(Component label, Button.OnPress action) {
        return Button.builder(label, action);
    }
    /** Shared configuration field; editing, selection, clipboard and narration remain EditBox behavior. */
    public static EditBox editBox(Font font, int x, int y, int width, int height, Component label) {
        return new EditBox(font, x, y, width, height, label);
    }
    private static final class Tab extends Button {
        private final int index;
        private final boolean selected;
        private final ItemStack icon;
        Tab(ConfigUiLayout.Rect b, int index, boolean selected, OnPress onPress) {
            super(b.x(), b.y(), b.width(), b.height(), Texts.component(LABELS[index]), onPress, DEFAULT_NARRATION);
            this.index = index;
            this.selected = selected;
            icon = new ItemStack(switch (index) {
                case 0 -> Items.REDSTONE;
                case 1 -> Items.BLAZE_POWDER;
                case 2 -> Items.MAP;
                default -> Items.CLOCK;
            });
        }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            if (selected) {
                int x = getX(), y = getY(), right = x + getWidth(), bottom = y + getHeight();
                graphics.fill(x + 1, y + 1, right - 1, y + 2, 0xffffe4a0);
                graphics.fill(x + 1, bottom - 2, right - 1, bottom - 1, 0xffffe4a0);
                graphics.fill(x + 1, y + 1, x + 2, bottom - 1, 0xffffe4a0);
                graphics.fill(right - 2, y + 1, right - 1, bottom - 1, 0xffffe4a0);
            }
        }
        @Override public void renderString(GuiGraphics graphics, Font font, int color) {
            String text = font.plainSubstrByWidth(Texts.resolve(LABELS[index]), Math.max(10, getWidth() - 20));
            int left = getX() + (getWidth() - font.width(Texts.resolve(text)) - 16) / 2;
            graphics.pose().pushPose();
            graphics.pose().translate(left, getY() + (getHeight() - 12) / 2.0, 0);
            graphics.pose().scale(.75f, .75f, 1);
            graphics.renderItem(icon, 0, 0);
            graphics.pose().popPose();
            graphics.drawString(font, Texts.resolve(text), left + 16, getY() + (getHeight() - 8) / 2,
                    active && selected ? 0xffffe4a0 : color);
        }
    }
}
