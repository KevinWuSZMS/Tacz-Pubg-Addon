package com.example.examplemod.lootconfig.client;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.configui.client.ConfigUi;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.lwjgl.glfw.GLFW;

import com.example.examplemod.lootconfig.CatalogSearch;
import com.example.examplemod.lootconfig.LootCatalog;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

/** Searchable, paged item picker. Choosing edits the parent draft, never the inventory. */
public final class ItemPickerScreen extends Screen {
    private static final String[] KINDS = {"all", "gun", "ammo", "attachment", "item"};
    private static final String[] LABELS = {Texts.tr("screen.tacz_pubg.item_picker.all"), Texts.tr("screen.tacz_pubg.item_picker.guns"), Texts.tr("screen.tacz_pubg.item_picker.ammo"), Texts.tr("screen.tacz_pubg.item_picker.attachments"), Texts.tr("screen.tacz_pubg.item_picker.other_items")};
    private static final int CELL = 28;
    private final Screen parent;
    private final List<IndexedChoice> catalog;
    private final Consumer<LootCatalog.Choice> onPick;
    private final String fixedKind;
    private List<IndexedChoice> filtered = List.of();
    private String query = "";
    private String kind = "all";
    private EditBox search;
    private Button previous;
    private Button next;
    private final List<Button> categories = new ArrayList<>();
    private int left, panelWidth, gridLeft, gridTop, columns, rows, page, suggestion;

    public ItemPickerScreen(Screen parent, List<LootCatalog.Choice> catalog, Consumer<LootCatalog.Choice> onPick) {
        this(parent, catalog, onPick, null, Texts.of("screen.tacz_pubg.item_picker.select_item"));
    }

    /** A locked category prevents a specialised editor from choosing another asset type. */
    public ItemPickerScreen(Screen parent, List<LootCatalog.Choice> catalog, Consumer<LootCatalog.Choice> onPick,
            String fixedKind, Component title) {
        super(title);
        this.parent = parent;
        this.fixedKind = fixedKind;
        this.kind = fixedKind == null ? "all" : fixedKind;
        this.catalog = catalog.stream().filter(choice -> fixedKind == null || fixedKind.equals(choice.kind()))
                .map(choice -> new IndexedChoice(choice, choice.preview().getHoverName().getString())).toList();
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(220, Math.min(560, width - 16));
        left = (width - panelWidth) / 2;
        columns = Math.max(1, (panelWidth - 16) / CELL);
        rows = Math.max(1, (height - (fixedKind == null ? 166 : 140)) / CELL);
        gridLeft = left + (panelWidth - columns * CELL) / 2;
        gridTop = fixedKind == null ? 96 : 70;
        search = addRenderableWidget(ConfigUi.editBox(font, left + 8, 29, panelWidth - 16, 20, Texts.of("screen.tacz_pubg.item_picker.name_item_id")));
        search.setMaxLength(128);
        search.setHint(Texts.of("screen.tacz_pubg.item_picker.name_id_mod_type_excluded"));
        search.setValue(Texts.resolve(query));
        search.setResponder(value -> {
            query = value;
            page = 0;
            suggestion = 0;
            filter();
        });
        categories.clear();
        int categoryWidth = (panelWidth - 16) / KINDS.length;
        for (int i = 0; fixedKind == null && i < KINDS.length; i++) {
            final String category = KINDS[i];
            categories.add(addRenderableWidget(ConfigUi.button(Texts.component(LABELS[i]), button -> {
                kind = category;
                page = 0;
                suggestion = 0;
                filter();
                setFocused(search);
            }).bounds(left + 8 + i * categoryWidth, 55, categoryWidth - 2, 20).build()));
        }
        previous = addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.item_picker.previous"), button -> changePage(-1))
                .bounds(left + 8, height - 26, 66, 20).build());
        next = addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.item_picker.next"), button -> changePage(1))
                .bounds(left + 78, height - 26, 66, 20).build());
        addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.item_picker.cancel_selection"), button -> onClose())
                .bounds(left + panelWidth - 96, height - 26, 88, 20).build());
        filter();
        setInitialFocus(search);
    }

    private void filter() {
        filtered = catalog.stream().filter(entry -> (kind.equals("all") || kind.equals(entry.choice.kind()))
                && CatalogSearch.matches(query, entry.choice.kind(), entry.choice.id(), entry.name)).toList();
        suggestion = Math.clamp(suggestion, 0, Math.max(0, filtered.size() - 1));
        page = Math.clamp(page, 0, pageCount() - 1);
        updateControls();
    }

    private int pageCount() {
        return Math.max(1, (filtered.size() + columns * rows - 1) / (columns * rows));
    }

    private void updateControls() {
        if (previous != null) previous.active = page > 0;
        if (next != null) next.active = page + 1 < pageCount();
        for (int i = 0; i < categories.size(); i++) {
            categories.get(i).setMessage(Texts.component(kind.equals(KINDS[i]) ? "[" + LABELS[i] + "]" : LABELS[i]));
        }
        if (search != null) {
            String id = filtered.isEmpty() ? "" : filtered.get(suggestion).choice.id();
            search.setSuggestion(!query.isBlank() && id.startsWith(query) && id.length() > query.length()
                    ? id.substring(query.length()) : null);
        }
    }

    private void changePage(int delta) {
        page = Math.clamp(page + delta, 0, pageCount() - 1);
        suggestion = Math.min(page * columns * rows, Math.max(0, filtered.size() - 1));
        updateControls();
    }

    private int hoveredIndex(double mouseX, double mouseY) {
        if (mouseX < gridLeft || mouseY < gridTop || mouseX >= gridLeft + columns * CELL || mouseY >= gridTop + rows * CELL) return -1;
        int index = page * columns * rows + (int) ((mouseY - gridTop) / CELL) * columns + (int) ((mouseX - gridLeft) / CELL);
        return index < filtered.size() ? index : -1;
    }

    private void choose(int index) {
        if (index < 0 || index >= filtered.size()) return;
        LootCatalog.Choice choice = filtered.get(index).choice;
        minecraft.setScreen(parent);
        onPick.accept(choice);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int hovered = hoveredIndex(mouseX, mouseY);
        if (button == 0 && hovered >= 0) {
            choose(hovered);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical != 0) {
            changePage(vertical > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (search.isFocused() && !filtered.isEmpty()) {
            if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_UP) {
                suggestion = Math.floorMod(suggestion + (keyCode == GLFW.GLFW_KEY_DOWN ? 1 : -1), filtered.size());
                page = suggestion / (columns * rows);
                updateControls();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB && !query.isBlank()
                    && (modifiers & (GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) == 0
                    && !query.equals(filtered.get(suggestion).choice.id())) {
                search.setValue(Texts.resolve(filtered.get(suggestion).choice.id()));
                search.moveCursorToEnd(false);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                choose(suggestion);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, Texts.resolve(title), width / 2, 12, 0xFFFFFFFF);
        graphics.drawString(font, Texts.resolve(font.plainSubstrByWidth(Texts.of("screen.tacz_pubg.item_picker.tab_completes_ids_select_enter_left_click_confirms").getString(), panelWidth - 16)),
                left + 8, gridTop - 15, 0xFFB6CEDD);
        int hovered = hoveredIndex(mouseX, mouseY);
        for (int slot = 0; slot < columns * rows; slot++) {
            int index = page * columns * rows + slot;
            if (index >= filtered.size()) break;
            int x = gridLeft + slot % columns * CELL;
            int y = gridTop + slot / columns * CELL;
            graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1,
                    index == hovered ? 0xFF52769B : index == suggestion ? 0xFF315D85 : 0xFF293847);
            graphics.renderItem(filtered.get(index).choice.preview(), x + 6, y + 6);
        }
        int detail = hovered >= 0 ? hovered : suggestion;
        int detailY = height - 64;
        if (!filtered.isEmpty()) {
            IndexedChoice selected = filtered.get(detail);
            graphics.drawString(font, Texts.resolve(font.plainSubstrByWidth(Texts.resolve(selected.name), panelWidth - 16)), left + 8, detailY, 0xFFFFFFFF);
            graphics.drawString(font, Texts.resolve(font.plainSubstrByWidth(Texts.resolve(selected.choice.kind() + " · " + selected.choice.id()), panelWidth - 16)),
                    left + 8, detailY + 12, 0xFF89C7FF);
        } else {
            graphics.drawString(font, Texts.of("screen.tacz_pubg.item_picker.try_fewer_keywords_another_category").getString(), left + 8, detailY, 0xFFFFCB83);
        }
        graphics.drawString(font, Texts.of("screen.tacz_pubg.item_picker.matches_page", filtered.size(), (page + 1), pageCount()).getString(), left + 8, height - 39, 0xFFAABBCD);
        if (hovered >= 0) {
            LootCatalog.Choice choice = filtered.get(hovered).choice;
            graphics.renderTooltip(font, List.of(choice.preview().getHoverName(),
                    Texts.component(choice.kind() + " · " + choice.id()),
                    Texts.of("screen.tacz_pubg.item_picker.registered_item", BuiltInRegistries.ITEM.getKey(choice.preview().getItem()))), Optional.empty(), mouseX, mouseY);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record IndexedChoice(LootCatalog.Choice choice, String name) {}
}
