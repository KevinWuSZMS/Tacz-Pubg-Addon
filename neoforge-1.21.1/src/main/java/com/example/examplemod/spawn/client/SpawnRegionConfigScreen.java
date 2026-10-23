package com.example.examplemod.spawn.client;

import com.example.examplemod.localization.Texts;

import java.math.BigDecimal;
import com.example.examplemod.configui.client.ConfigUi;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.examplemod.spawn.SpawnRegionConfigPayloads;
import com.example.examplemod.spawn.SpawnRegionConfigPayloads.Snapshot;
import com.example.examplemod.spawn.SpawnRegionConfigPayloads.Summary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Region coefficients have their own save/revision, without mutating the parent loot draft. */
public final class SpawnRegionConfigScreen extends Screen {
    private final Screen parent;
    private final double density;
    private final Map<Long, String> draft = new LinkedHashMap<>();
    private final Map<Long, Double> originals = new HashMap<>();
    private List<Summary> regions = List.of();
    private long revision = -1;
    private long selectedId = -1;
    private int total, offset, pageSize = 1, leftWidth;
    private boolean pending, saving, conflicted, error;
    private Button saveConfiguration;
    private String message = Texts.tr("screen.tacz_pubg.spawn_region_config.requesting_registered_regions");

    public SpawnRegionConfigScreen(Screen parent, double density) {
        super(Texts.of("screen.tacz_pubg.spawn_region_config.pubg_region_configuration"));
        this.parent = parent;
        this.density = density;
    }

    Screen parent() { return parent; }
    int pageSize() { return pageSize; }
    int offset() { return offset; }
    long revision() { return revision; }

    @Override
    protected void init() {
        pageSize = Math.clamp((height - 146) / 24, 1, SpawnRegionConfigPayloads.MAX_PAGE_SIZE);
        leftWidth = Math.min(160, Math.max(96, width / 3));
        button(Texts.tr("screen.tacz_pubg.spawn_region_config.reload_regions"), 8, 37, 110, this::reload).active = !pending;
        saveConfiguration = button(Texts.tr("screen.tacz_pubg.configuration.save"), width - 132, height - 26, 124, this::save);
        saveConfiguration.active = !pending && !conflicted && revision >= 0 && !draft.isEmpty();
        saveConfiguration.visible = !draft.isEmpty() || saving;
        button(Texts.tr("screen.tacz_pubg.spawn_region_config.back_loot_configuration"), 8, height - 26, 104, this::onClose).active = !pending;
        int rows = Math.min(regions.size(), pageSize);
        for (int row = 0; row < rows; row++) {
            Summary region = regions.get(row);
            String label = (region.id() == selectedId ? "> " : "") + "#" + region.id() + " · " + region.area() + "㎡";
            button(fit(label, leftWidth - 20), 8, 77 + row * 24, leftWidth - 8, () -> {
                selectedId = region.id();
                rebuildWidgets();
            }).setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.spawn_region_config.id_m", region.id(), region.dimension(), region.area())));
        }
        button(Texts.tr("screen.tacz_pubg.item_picker.previous"), 8, height - 63, 68, () -> loadPage(Math.max(0, offset - pageSize)))
                .active = !pending && !conflicted && offset > 0;
        button(Texts.tr("screen.tacz_pubg.item_picker.next"), 80, height - 63, 68, () -> loadPage(offset + pageSize))
                .active = !pending && !conflicted && offset + regions.size() < total;
        Summary selected = selected();
        if (selected != null) {
            int fieldX = leftWidth + 12;
            int fieldWidth = Math.max(40, width - fieldX - 82);
            EditBox coefficient = ConfigUi.editBox(font, fieldX, 159, fieldWidth, 20, Texts.of("screen.tacz_pubg.spawn_region_config.region_rarity_multiplier"));
            coefficient.setMaxLength(24);
            coefficient.setValue(Texts.resolve(draft.getOrDefault(selected.id(), Double.toString(selected.rarity()))));
            coefficient.setResponder(value -> editCoefficient(selected.id(), value));
            boolean editable = !pending && !conflicted && (draft.containsKey(selected.id())
                    || draft.size() < SpawnRegionConfigPayloads.MAX_CHANGES);
            coefficient.active = editable;
            addRenderableWidget(coefficient);
            button(Texts.tr("screen.tacz_pubg.spawn_region_config.reset"), width - 66, 159, 58, () -> {
                editCoefficient(selected.id(), "1");
                rebuildWidgets();
            }).active = editable;
        }
        if (pending) for (var child : children()) if (child instanceof AbstractWidget widget) widget.active = false;
    }

    private void editCoefficient(long id, String value) {
        Double parsed = parse(value);
        Double original = originals.get(id);
        if (parsed != null && original != null && Double.compare(parsed, original) == 0) {
            draft.remove(id);
        } else if (draft.containsKey(id) || draft.size() < SpawnRegionConfigPayloads.MAX_CHANGES) {
            draft.put(id, value);
        } else {
            showMessage(Texts.tr("screen.tacz_pubg.spawn_region_config.reached_limit_region_changes_per_submission_save_before", SpawnRegionConfigPayloads.MAX_CHANGES), true);
            return;
        }
        // Responder updates only the footer; preserve current text focus/cursor.
        if (saveConfiguration != null) {
            saveConfiguration.visible = !draft.isEmpty() || saving;
            saveConfiguration.active = !pending && !conflicted && revision >= 0 && !draft.isEmpty();
        }
    }

    Map<Long, Double> changes() {
        Map<Long, Double> changes = new LinkedHashMap<>();
        for (var entry : draft.entrySet()) {
            Double value = parse(entry.getValue());
            if (value == null) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.spawn_region_config.multiplier_region_finite_between_input_retained", entry.getKey()));
            changes.put(entry.getKey(), value);
        }
        return changes;
    }

    private static Double parse(String text) {
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) && value >= 0 && value <= 1000 ? value : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private void save() {
        if (pending || conflicted || revision < 0 || draft.isEmpty()) return;
        SpawnRegionConfigClient.save(this);
    }

    private void loadPage(int nextOffset) {
        if (pending || conflicted) return;
        SpawnRegionConfigClient.request(this, revision, nextOffset);
    }

    private void reload() {
        if (pending) return;
        if (draft.isEmpty()) reloadDiscarded();
        else minecraft.setScreen(new ConfirmScreen(discard -> {
            minecraft.setScreen(this);
            if (discard) reloadDiscarded();
        }, Texts.of("screen.tacz_pubg.spawn_region_config.discard_region_multiplier_drafts_reload"),
                Texts.of("screen.tacz_pubg.spawn_region_config.review_region_ids_after_merges_splits_keep_parent"),
                Texts.of("screen.tacz_pubg.spawn_region_config.discard_load"), Texts.of("screen.tacz_pubg.spawn_region_config.keep_draft")));
    }

    private void reloadDiscarded() {
        draft.clear();
        originals.clear();
        conflicted = false;
        revision = -1;
        SpawnRegionConfigClient.request(this, -1, offset);
    }

    void setPending(boolean saving) {
        pending = true;
        this.saving = saving;
        showMessage(saving ? Texts.tr("screen.tacz_pubg.spawn_region_config.submitting_region_multipliers_awaiting_server_confirmation") : Texts.tr("screen.tacz_pubg.spawn_region_config.reading_region_summaries"), false);
        rebuildWidgets();
    }

    void requestFailed(String message) {
        pending = false;
        saving = false;
        showMessage(message, true);
        rebuildWidgets();
    }

    void accept(Snapshot snapshot) {
        pending = false;
        saving = false;
        if (snapshot.outcome() == SpawnRegionConfigPayloads.CONFLICT) {
            conflicted = true;
            showMessage(snapshot.message(), true);
            rebuildWidgets();
            return;
        }
        if (snapshot.outcome() == SpawnRegionConfigPayloads.FAILED) {
            showMessage(snapshot.message(), true);
            rebuildWidgets();
            return;
        }
        if (snapshot.outcome() == SpawnRegionConfigPayloads.SAVED) {
            draft.clear();
            originals.clear();
        }
        revision = snapshot.revision();
        total = snapshot.total();
        offset = snapshot.offset();
        regions = snapshot.regions();
        for (Summary region : regions) originals.put(region.id(), region.rarity());
        if (selected() == null) selectedId = regions.isEmpty() ? -1 : regions.getFirst().id();
        showMessage(snapshot.message(), false);
        rebuildWidgets();
    }

    private Summary selected() {
        return regions.stream().filter(region -> region.id() == selectedId).findFirst().orElse(null);
    }

    private void showMessage(String message, boolean error) {
        this.message = message == null ? "" : message;
        this.error = error;
    }

    @Override
    public void onClose() {
        if (pending) { showMessage(Texts.tr("screen.tacz_pubg.spawn_region_config.wait_server_confirmation_editing_resumes_after_timeout"), true); return; }
        if (!draft.isEmpty()) minecraft.setScreen(new ConfirmScreen(discard -> {
            if (discard) closeDiscarded(); else minecraft.setScreen(this);
        }, Texts.of("screen.tacz_pubg.spawn_region_config.discard_pending_region_multipliers"), Texts.of("screen.tacz_pubg.spawn_region_config.saved_multipliers_parent_page_s_loot_draft_retained"),
                Texts.of("screen.tacz_pubg.spawn_region_config.discard_return"), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
        else closeDiscarded();
    }

    private void closeDiscarded() {
        SpawnRegionConfigClient.closed(this);
        minecraft.setScreen(parent);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, Texts.resolve(title), width / 2, 8, 0xFFFFFFFF);
        graphics.drawString(font, Texts.resolve(fit(Texts.tr("screen.tacz_pubg.spawn_region_config.regions_version_changes", total, (revision < 0 ? Texts.tr("screen.tacz_pubg.spawn_region_config.awaiting_loading") : revision), draft.size(), SpawnRegionConfigPayloads.MAX_CHANGES, (conflicted ? Texts.tr("screen.tacz_pubg.spawn_region_config.version_conflict") : pending ? saving ? Texts.tr("screen.tacz_pubg.spawn_region_config.saving") : Texts.tr("screen.tacz_pubg.spawn_region_config.loading") : "")), width - 16)),
                8, 23, conflicted ? 0xFFFF8888 : 0xFFB5D9FF);
        graphics.drawString(font, Texts.of("screen.tacz_pubg.spawn_region_config.registered_regions").getString(), 8, 64, 0xFFE0E0E0);
        Summary selected = selected();
        int right = leftWidth + 12, available = Math.max(10, width - right - 8);
        if (selected == null) graphics.drawString(font, Texts.of("screen.tacz_pubg.spawn_region_config.use_edit_wand_confirm_loot_floor_regions_first").getString(), right, 82, 0xFFAAAAAA);
        else {
            Double rarity = parse(draft.getOrDefault(selected.id(), Double.toString(selected.rarity())));
            String[] lines = {
                    Texts.tr("screen.tacz_pubg.spawn_region_config.id", selected.id()), Texts.tr("screen.tacz_pubg.spawn_region_config.dimension", selected.dimension()),
                    Texts.tr("screen.tacz_pubg.spawn_region_config.area_m_floors", selected.area(), selected.area()),
                    Texts.tr("screen.tacz_pubg.spawn_region_config.minimum_position", coordinates(selected.min())), Texts.tr("screen.tacz_pubg.spawn_region_config.maximum_position", coordinates(selected.max())),
                    Texts.tr("screen.tacz_pubg.spawn_region_config.expected_item_count", (rarity == null ? Texts.tr("screen.tacz_pubg.spawn_region_config.enter_valid_multiplier") : number(selected.area() * rarity * density))),
                    Texts.tr("screen.tacz_pubg.spawn_region_config.rarity_multiplier_default_disabled")};
            for (int i = 0; i < lines.length; i++) {
                graphics.drawString(font, Texts.resolve(fit(lines[i], available)), right, 67 + i * 13,
                        i == 5 ? 0xFFB5D9FF : 0xFFE0E0E0);
            }
            if (mouseX >= right && mouseY >= 80 && mouseY < 94) {
                graphics.renderTooltip(font, Texts.component(selected.dimension().toString()), mouseX, mouseY);
            }
        }
        int shown = regions.isEmpty() ? 0 : offset + 1;
        graphics.drawString(font, Texts.resolve(shown + "–" + (offset + regions.size()) + " / " + total),
                8, height - 76, 0xFFAAAAAA);
        String densityText = Texts.tr("screen.tacz_pubg.spawn_region_config.preview_density_items_m_comes_parent_draft_actual", number(density));
        graphics.drawString(font, Texts.resolve(fit(densityText, width - 16)),
                8, height - 48, 0xFFAAAAAA);
        if (mouseY >= height - 52 && mouseY < height - 39) {
            graphics.renderTooltip(font, font.split(Texts.component(densityText
                    + Texts.tr("screen.tacz_pubg.spawn_region_config.expected_items_area_rarity_multiplier_density_companion_ammo")),
                    Math.max(100, width - 24)), mouseX, mouseY);
        }
        if (!message.isBlank()) {
            graphics.drawString(font, Texts.resolve(fit(message, width - 16)), 8, height - 37, error ? 0xFFFF8888 : 0xFFB0E0B0);
            if (mouseY >= height - 41 && mouseY < height - 27) {
                graphics.renderTooltip(font, font.split(Texts.component(message), Math.max(100, width - 24)), mouseX, mouseY);
            }
        }
    }

    private String fit(String text, int pixels) { return font.plainSubstrByWidth(Texts.resolve(text), Math.max(0, pixels)); }
    private static String coordinates(BlockPos position) { return position.getX() + ", " + position.getY() + ", " + position.getZ(); }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }

    private Button button(String text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(ConfigUi.button(Texts.component(text), ignored -> action.run())
                .bounds(x, y, Math.max(20, width), 20).build());
    }
}
