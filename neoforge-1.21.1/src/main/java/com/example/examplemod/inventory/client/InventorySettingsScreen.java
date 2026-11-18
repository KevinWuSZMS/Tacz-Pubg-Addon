package com.example.examplemod.inventory.client;

import com.example.examplemod.inventory.InventorySettings;
import com.example.examplemod.lootconfig.LootCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Child draft; the parent still owns saving and server revision acknowledgement. */
public final class InventorySettingsScreen extends Screen {
    private final Screen parent;
    private final InventorySettings baseline;
    private final List<LootCatalog.Choice> ammo;
    private final Consumer<InventorySettings> apply;
    private InventorySettings draft;
    private int page;
    private String search = "", selected = "", error = "";
    private String limitDraft;
    private double ammoScroll;
    private EditBox limit;
    private AmmoList list;
    private int right, rightWidth;

    public InventorySettingsScreen(Screen parent, InventorySettings source, List<LootCatalog.Choice> catalog,
            Consumer<InventorySettings> apply) {
        super(Component.translatable("screen.tacz_pubg.inventory.settings"));
        this.parent = parent; baseline = source; draft = source; this.apply = apply;
        ammo = catalog.stream().filter(choice -> choice.kind().equals("ammo")).toList();
        if (!ammo.isEmpty()) selected = ammo.getFirst().id();
    }
    @Override protected void init() {
        setDragging(false);
        int tabWidth = (width - 32) / 3;
        addRenderableWidget(Button.builder(Component.translatable("screen.tacz_pubg.inventory.hotbar"), b -> switchPage(0))
                .bounds(10, 34, tabWidth, 20).build()).active = page != 0;
        addRenderableWidget(Button.builder(Component.translatable("screen.tacz_pubg.inventory.ammo_stacks"), b -> switchPage(1))
                .bounds(16 + tabWidth, 34, tabWidth, 20).build()).active = page != 1;
        addRenderableWidget(Button.builder(Component.translatable("screen.tacz_pubg.inventory.pickup"), b -> switchPage(2))
                .bounds(22 + 2 * tabWidth, 34, tabWidth, 20).build()).active = page != 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.tacz_pubg.inventory.apply_draft"), b -> commit())
                .bounds(width - 126, height - 26, 116, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(10, height - 26, 90, 20).build());
        if (page == 0) initHotbar(); else if (page == 1) initAmmo(); else initPickup();
    }
    private void initHotbar() {
        limit = null; list = null;
        int columns = 3;
        int cell = (width - 28 - (columns - 1) * 6) / columns;
        for (int slot = 0; slot < 9; slot++) {
            final int index = slot;
            int x = 14 + slot % columns * (cell + 6), y = 76 + slot / columns * 32;
            Button button = addRenderableWidget(Button.builder(label(slot), b -> {
                String current = draft.hotbar().get(index);
                int next = (InventorySettings.ROLES.indexOf(current) + 1) % InventorySettings.ROLES.size();
                draft = draft.withRole(index, InventorySettings.ROLES.get(next));
                rebuildWidgets();
            }).bounds(x, y, cell, 24).tooltip(Tooltip.create(Component.translatable(slot < 3
                    ? "screen.tacz_pubg.inventory.fixed_slot" : "screen.tacz_pubg.inventory.role_hint"))).build());
            button.active = slot >= 3;
        }
    }
    private Component label(int slot) {
        return Component.literal((slot + 1) + "  ").append(Component.translatable("screen.tacz_pubg.inventory.role." + draft.hotbar().get(slot)));
    }
    private void initPickup() {
        limit = null; list = null;
        Component mode = Component.translatable(draft.pubgDirectEquip()
                ? "screen.tacz_pubg.inventory.pickup_equip" : "screen.tacz_pubg.inventory.pickup_store");
        addRenderableWidget(Button.builder(Component.translatable("screen.tacz_pubg.inventory.pubg_direct_equip", mode), b -> {
            draft = draft.withPubgDirectEquip(!draft.pubgDirectEquip());
            rebuildWidgets();
        }).bounds(14, 76, width - 28, 24).tooltip(Tooltip.create(
                Component.translatable("screen.tacz_pubg.inventory.pubg_direct_equip_hint"))).build());
    }
    private void initAmmo() {
        int listWidth = Math.max(80, Math.min(Math.max(132, (width - 26) * 3 / 5), width - 102));
        right = 20 + listWidth;
        rightWidth = Math.max(20, width - right - 12);
        EditBox filter = addRenderableWidget(new EditBox(font, 10, 63, listWidth, 20,
                Component.translatable("screen.tacz_pubg.inventory.search_ammo")));
        filter.setValue(search);
        filter.setHint(Component.translatable("screen.tacz_pubg.inventory.search_ammo"));
        filter.setResponder(value -> { search = value; ammoScroll = 0; list.refill(); list.setScrollAmount(0); });
        double savedScroll = ammoScroll;
        list = addRenderableWidget(new AmmoList(listWidth, Math.max(35, height - 132), 88));
        list.setX(10);
        list.refill();
        list.setScrollAmount(savedScroll);
        limit = addRenderableWidget(new EditBox(font, right, 108, rightWidth, 20,
                Component.translatable("screen.tacz_pubg.inventory.stack_limit")));
        limit.setMaxLength(5);
        limit.setValue(limitDraft == null ? Integer.toString(draft.ammoStackLimits().getOrDefault(selected, InventorySettings.DEFAULT_AMMO_STACK)) : limitDraft);
        limit.setResponder(value -> limitDraft = value);
        limit.active = !selected.isEmpty();
        addRenderableWidget(Button.builder(Component.translatable("screen.tacz_pubg.inventory.reset_default"), b -> {
            draft = draft.withAmmoLimit(selected, null);
            error = ""; limitDraft = null;
            limit.setValue(Integer.toString(InventorySettings.DEFAULT_AMMO_STACK));
        }).bounds(right, 138, rightWidth, 20).build()).active = !selected.isEmpty();
    }
    private void switchPage(int next) {
        if (!flush()) return;
        if (list != null) ammoScroll = list.getScrollAmount();
        page = next; error = ""; limitDraft = null; rebuildWidgets();
    }
    private boolean flush() {
        if (page != 1 || limit == null || selected.isEmpty()) return true;
        try {
            int value = Integer.parseInt(limit.getValue().trim());
            if (value < 1 || value > InventorySettings.MAX_AMMO_STACK) throw new NumberFormatException();
            Integer baselineValue = draft.ammoStackLimits().get(selected);
            if (baselineValue != null || value != InventorySettings.DEFAULT_AMMO_STACK)
                draft = draft.withAmmoLimit(selected, value);
            error = ""; return true;
        } catch (IllegalArgumentException invalid) {
            error = Component.translatable("screen.tacz_pubg.inventory.stack_error", InventorySettings.MAX_AMMO_STACK).getString();
            return false;
        }
    }
    private boolean select(String id) {
        if (id.equals(selected)) return true;
        if (!flush()) return false;
        if (list != null) ammoScroll = list.getScrollAmount();
        selected = id; error = ""; limitDraft = null;
        // Keep this list alive: rebuilding during a row callback makes Screen focus the removed list.
        if (limit != null) limit.setValue(Integer.toString(
                draft.ammoStackLimits().getOrDefault(selected, InventorySettings.DEFAULT_AMMO_STACK)));
        return true;
    }
    private void commit() {
        if (!flush()) return;
        apply.accept(draft);
        minecraft.setScreen(parent);
    }
    @Override public void onClose() {
        boolean pending = limit != null && !limit.getValue().equals(Integer.toString(
                draft.ammoStackLimits().getOrDefault(selected, InventorySettings.DEFAULT_AMMO_STACK)));
        if (draft.equals(baseline) && !pending) minecraft.setScreen(parent);
        else minecraft.setScreen(new ConfirmScreen(discard -> minecraft.setScreen(discard ? parent : this),
                Component.translatable("screen.tacz_pubg.inventory.discard"),
                Component.translatable("screen.tacz_pubg.inventory.discard_hint")));
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xffffffff);
        if (page == 0) {
            graphics.drawString(font, Component.translatable("screen.tacz_pubg.inventory.match_only"), 14, 61, 0xffbcd4bd);
        } else if (page == 1) {
            LootCatalog.Choice choice = ammo.stream().filter(value -> value.id().equals(selected)).findFirst().orElse(null);
            if (choice != null) {
                graphics.renderItem(choice.preview(), right, 66);
                graphics.drawString(font, font.plainSubstrByWidth(choice.preview().getHoverName().getString(), rightWidth - 20),
                        right + 20, 70, 0xffffffff);
            }
            graphics.drawString(font, Component.translatable("screen.tacz_pubg.inventory.stack_limit"), right, 93, 0xffdddddd);
            graphics.drawWordWrap(font, Component.translatable("screen.tacz_pubg.inventory.stack_hint",
                    InventorySettings.DEFAULT_AMMO_STACK, InventorySettings.MAX_AMMO_STACK), right, 170, rightWidth, 0xffaaaaaa);
        } else {
            graphics.drawString(font, Component.translatable("screen.tacz_pubg.inventory.match_only"), 14, 61, 0xffbcd4bd);
            graphics.drawWordWrap(font, Component.translatable("screen.tacz_pubg.inventory.pubg_direct_equip_hint"),
                    14, 112, width - 28, 0xffaaaaaa);
        }
        if (!error.isEmpty()) graphics.drawString(font, font.plainSubstrByWidth(error, width - 20), 10, height - 43, 0xffff8888);
    }
    private final class AmmoList extends ObjectSelectionList<AmmoRow> {
        AmmoList(int width, int height, int top) { super(Minecraft.getInstance(), width, height, top, 35); }
        void refill() {
            super.setFocused(null);
            clearEntries();
            String query = search.toLowerCase(Locale.ROOT);
            for (var choice : ammo) if ((choice.id() + " " + choice.preview().getHoverName().getString()).toLowerCase(Locale.ROOT).contains(query)) {
                AmmoRow row = new AmmoRow(choice); addEntry(row);
                if (choice.id().equals(selected)) setSelected(row);
            }
        }
        @Override public int getRowWidth() { return getWidth() - 20; }
        // The native default is realRowRight + 10, outside a narrow panel's mouse hitbox.
        @Override protected int getScrollbarPosition() { return getX() + getWidth() - 8; }
        @Override public void setScrollAmount(double amount) {
            super.setScrollAmount(amount);
            ammoScroll = getScrollAmount();
        }
        @Override public void setFocused(GuiEventListener focused) {
            if (focused instanceof AmmoRow row && !select(row.choice.id())) return;
            super.setFocused(focused);
        }
        @Override public boolean mouseClicked(double x, double y, int button) {
            if (!visible || !active || !isMouseOver(x, y)) return false;
            if (button == 0 && getMaxScroll() > 0 && x >= getScrollbarPosition()
                    && x < getScrollbarPosition() + 6) {
                super.setFocused(null);
                updateScrollingState(x, y, button);
                setDragging(true);
                return true;
            }
            return super.mouseClicked(x, y, button);
        }
        @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
            // Keep native thumb scaling/clamping and pointer capture even outside the viewport.
            return visible && active && super.mouseDragged(x, y, button, dx, dy);
        }
        @Override public boolean mouseReleased(double x, double y, int button) {
            boolean captured = button == 0 && isDragging();
            boolean handled = super.mouseReleased(x, y, button);
            if (button == 0) {
                setDragging(false);
                updateScrollingState(x, y, -1);
            }
            return handled || captured;
        }
        @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
            return visible && active && isMouseOver(x, y) && super.mouseScrolled(x, y, horizontal, vertical);
        }
    }
    private final class AmmoRow extends ObjectSelectionList.Entry<AmmoRow> {
        private final LootCatalog.Choice choice;
        AmmoRow(LootCatalog.Choice choice) { this.choice = choice; }
        @Override public Component getNarration() { return choice.preview().getHoverName(); }
        @Override public boolean mouseClicked(double x, double y, int button) { return button == 0; }
        @Override public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                int mouseX, int mouseY, boolean hovered, float partialTick) {
            graphics.renderItem(choice.preview(), left + 2, top + 3);
            graphics.drawString(font, font.plainSubstrByWidth(choice.preview().getHoverName().getString(), width - 62), left + 23, top + 2, 0xffffffff);
            graphics.drawString(font, Integer.toString(draft.ammoStackLimits().getOrDefault(choice.id(), InventorySettings.DEFAULT_AMMO_STACK)),
                    left + width - 35, top + 2, 0xffdfc96e);
            graphics.drawString(font, font.plainSubstrByWidth(choice.id(), width - 25), left + 23, top + 16, 0xffaaaaaa);
        }
    }
}
