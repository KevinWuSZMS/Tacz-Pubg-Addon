package com.example.examplemod.zones.client;

import com.example.examplemod.localization.Texts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.example.examplemod.zones.MapZoneConfigPayloads;
import com.example.examplemod.zones.MapZoneConfigPayloads.Snapshot;
import com.example.examplemod.zones.MapZoneConfigPayloads.Summary;
import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.BombingZone;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.FlightPath;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.MapVisualStyle;
import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.lootconfig.client.LootConfigScreen;
import com.example.examplemod.zones.match.client.MatchConfigClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** All authoring is a local string draft until an independently revisioned server save succeeds. */
public final class MapZoneConfigScreen extends Screen implements ConfigUi.DraftPage {
    private enum View { LIST, MAP, SAFE, BOMB, FLIGHT, COLORS, PREVIEW }
    private record Field(String label, Supplier<String> value, Consumer<String> edit, boolean text, boolean color, boolean bombardmentRadius) {
        Field(String label, Supplier<String> value, Consumer<String> edit, boolean text, boolean color) {
            this(label, value, edit, text, color, false);
        }
    }
    private final Screen parent;
    private List<Summary> maps = List.of();
    private long revision = -1;
    private int total, offset, pageSize = 1, fieldPage, fieldsPerPage = 2, selectedBomb;
    private View view = View.LIST;
    private RawMap draft;
    private List<String> original;
    private MapDefinition originalMap;
    private boolean pending, writing, conflicted, error;
    private long entityRecordingPendingMapId;
    private Screen entityChoiceReturnTo;
    private boolean developerForm, openGraphicalAfterLoad, createGameAfterSave;
    private String message = Texts.tr("screen.tacz_pubg.map_config.reading_map_list");
    private String suggestedMapName = Texts.tr("screen.tacz_pubg.map_editor.default_name") + " 1";

    public MapZoneConfigScreen(Screen parent) {
        super(Texts.of("screen.tacz_pubg.map_config.pubg_map_zone_configuration"));
        this.parent = parent;
        ConfigUi.remember(this, parent, 2);
    }

    Screen parent() { return parent; }
    int pageSize() { return pageSize; }
    int offset() { return offset; }
    long revision() { return revision; }

    void showMapList() {
        view = View.LIST;
        developerForm = false;
        openGraphicalAfterLoad = false;
    }

    @Override
    protected void init() {
        pageSize = Math.clamp((height - 181) / 27, 1, MapZoneConfigPayloads.MAX_PAGE_SIZE);
        fieldsPerPage = 2 * Math.max(1, (height - 213) / 38);
        if (showingList()) for (Button tab : ConfigUi.tabs(width, 2, this::navigateConfigTab)) addRenderableWidget(tab);
        if (showingList()) listWidgets();
        else editorWidgets();
        updateSaveButton();
        if (pending) for (var child : children()) if (child instanceof AbstractWidget widget) widget.active = false;
    }

    private boolean showingList() {
        return view == View.LIST || draft == null || !developerForm || !ConfigUi.developerMode();
    }

    private void listWidgets() {
        button(Texts.tr("screen.tacz_pubg.map_list.create"), 8, 66, 90,
                () -> confirmDiscard(Texts.tr("screen.tacz_pubg.map_config.discard_current_map_draft_create_new_map"), this::newMap))
                .active = !pending && !conflicted && revision >= 0 && total < MapZoneModel.MAX_MAPS;
        button(Texts.tr("screen.tacz_pubg.map_config.reload"), 104, 66, 90, this::reload).active = !pending;
        developerToggle(width - 112, 66, 104);
        for (int row = 0; row < Math.min(pageSize, maps.size()); row++) {
            Summary map = maps.get(row);
            String label = "#" + map.id() + " " + map.name() + " · " + map.dimension();
            int coordinateWidth = ConfigUi.developerMode() ? 62 : 0;
            Button entry = button(fit(label, width - 104 - coordinateWidth), 8, 108 + row * 27,
                    width - 78 - coordinateWidth, () -> openMap(map.id()));
            if (ConfigUi.developerMode()) button(Texts.tr("screen.tacz_pubg.map_config.coordinates"), width - 126, 108 + row * 27, 56,
                    () -> openMap(map.id(), true)).active = !pending && !conflicted;
            button(Texts.tr("screen.tacz_pubg.map_config.delete"), width - 64, 108 + row * 27, 56, () -> deleteMap(map)).active = !pending && !conflicted;
            entry.active = !pending && !conflicted;
            entry.setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.map_config.x_z_safe_zone_bombardment_zones", map.id(), map.name(), map.dimension(), number(map.minX()), number(map.maxX()), number(map.minZ()), number(map.maxZ()), (map.hasSafeZone() ? Texts.tr("screen.tacz_pubg.map_config.set") : Texts.tr("screen.tacz_pubg.map_config.not_set")), map.bombingZoneCount())));
        }
        button(Texts.tr("screen.tacz_pubg.item_picker.previous"), 8, height - 68, 65, () -> loadPage(Math.max(0, offset - pageSize)))
                .active = !pending && !conflicted && offset > 0;
        button(Texts.tr("screen.tacz_pubg.item_picker.next"), 78, height - 68, 65, () -> loadPage(offset + pageSize))
                .active = !pending && !conflicted && offset + maps.size() < total;
        button(Texts.tr("screen.tacz_pubg.visibility_config.back_configuration"), width - 108, height - 26, 100, this::onClose).active = !pending;
        if (draft != null) button(Texts.tr("screen.tacz_pubg.map_config.continue_editing_draft"), 8, height - 26, 100, this::openNativePreview)
                .active = !pending && !conflicted;
    }

    private void developerToggle(int x, int y, int size) {
        button(Texts.tr("screen.tacz_pubg.map_config.developer", (ConfigUi.developerMode() ? Texts.tr("screen.tacz_pubg.map_config.developer_toggle") : Texts.tr("screen.tacz_pubg.common.off"))), x, y, size, () -> {
            ConfigUi.setDeveloperMode(!ConfigUi.developerMode());
            if (!ConfigUi.developerMode()) developerForm = false;
            rebuildWidgets();
        }).setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.map_config.manual_coordinate_forms_appear_only_developer_mode_normal")));
    }

    private void editorWidgets() {
        int tabWidth = Math.max(28, (width - 36) / 6);
        tab(Texts.tr("screen.tacz_pubg.map_config.map"), View.MAP, 8, tabWidth);
        tab(Texts.tr("screen.tacz_pubg.map_config.safe_blue_zone"), View.SAFE, 12 + tabWidth, tabWidth);
        tab(Texts.tr("screen.tacz_pubg.zone_settings.bombardment_zone"), View.BOMB, 16 + tabWidth * 2, tabWidth);
        tab(Texts.tr("screen.tacz_pubg.zone_settings.flight_route"), View.FLIGHT, 20 + tabWidth * 3, tabWidth);
        tab(Texts.tr("screen.tacz_pubg.map_config.map_colors"), View.COLORS, 24 + tabWidth * 4, tabWidth);
        tab(Texts.tr("screen.tacz_pubg.map_config.map_editing"), View.PREVIEW, 28 + tabWidth * 5, tabWidth);
        if (view == View.MAP && draft.id > 0) {
            button(Texts.tr("screen.tacz_pubg.map_config.delete_map"), width - 78, 92, 70, this::deleteMap).active = !pending && !conflicted;
            if (entityRecordingPending(draft.id)) button(Texts.tr("screen.tacz_pubg.map_entities.choose"), 8, 92,
                    Math.max(80, Math.min(180, width - 100)), () -> openEntityRecordingChoice(this)).active = !pending && !conflicted;
        } else if (view == View.SAFE) {
            button(draft.safe ? Texts.tr("screen.tacz_pubg.map_config.remove_safe_zone") : Texts.tr("screen.tacz_pubg.map_config.create_safe_zone"), 8, 92, 104, () -> {
                if (draft.safe) draft.safe = false;
                else {
                    try { draft.createSafe(); }
                    catch (IllegalArgumentException exception) { showMessage(exception.getMessage(), true); return; }
                }
                fieldPage = 0;
                rebuildWidgets();
            }).setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.map_config.creating_safe_zone_enables_blue_zone_map_center")));
        } else if (view == View.BOMB) bombControls();
        else if (view == View.FLIGHT) {
            button(draft.flight ? Texts.tr("screen.tacz_pubg.map_config.remove_flight_route") : Texts.tr("screen.tacz_pubg.map_config.create_flight_route"), 8, 92, 104, () -> {
                if (draft.flight) draft.flight = false;
                else {
                    try { draft.createFlight(); }
                    catch (IllegalArgumentException exception) { showMessage(exception.getMessage(), true); return; }
                }
                fieldPage = 0;
                rebuildWidgets();
            }).setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.map_config.x_z_line_start_end_both_endpoints_inside")));
        } else if (view == View.COLORS) {
            button(Texts.tr("screen.tacz_pubg.map_config.reset_default_colors"), width - 112, 92, 104, () -> {
                draft.setVisualStyle(MapVisualStyle.DEFAULT);
                showMessage(Texts.tr("screen.tacz_pubg.map_config.default_colors_opacity_current_draft_click_save_configuration"), false);
                rebuildWidgets();
            }).setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.map_config.reset_map_draft_s_six_colors_four_opacity")));
        }

        if (view != View.PREVIEW) {
            List<Field> fields = fields();
            int pages = Math.max(1, (fields.size() + fieldsPerPage - 1) / fieldsPerPage);
            fieldPage = Math.clamp(fieldPage, 0, pages - 1);
            int columnWidth = Math.max(40, (width - 28) / 2);
            int start = fieldPage * fieldsPerPage;
            for (int index = start; index < Math.min(fields.size(), start + fieldsPerPage); index++) {
                int local = index - start;
                Field field = fields.get(index);
                int x = 8 + local % 2 * (columnWidth + 12), y = 128 + local / 2 * 38;
                EditBox input = ConfigUi.editBox(font, x, y, columnWidth - (field.color() ? 27 : 0), 20, Texts.component(field.label()));
                input.setMaxLength(field.text() ? 80 : 40);
                input.setValue(Texts.resolve(field.value().get()));
                input.setResponder(value -> { field.edit().accept(value); updateSaveButton(); });
                input.setTooltip(Tooltip.create(Texts.component(field.bombardmentRadius()
                        ? Texts.tr("screen.tacz_pubg.map_config.bombardment_zone_radius_determines_area_where_bombardment_impacts")
                        : field.label()
                        + (field.color() ? Texts.tr("screen.tacz_pubg.map_config.enter_six_digit_hex_rgb_optionally_prefixed_click")
                        : field.text() ? Texts.tr("screen.tacz_pubg.map_config.input_drafts_retained_validated_save") : Texts.tr("screen.tacz_pubg.map_config.enter_finite_number_validated_save")))));
                addRenderableWidget(input);
                if (field.color()) {
                    button("", x + columnWidth - 23, y, 23, () -> minecraft.setScreen(
                            new MapZoneColorPicker(this, field.label(), field.value().get(), value -> {
                                field.edit().accept(value);
                                updateSaveButton();
                            }))).setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.map_config.open_palette", field.label())));
                }
            }
            if (pages > 1) {
                button(Texts.tr("screen.tacz_pubg.map_config.previous_field_page"), 8, height - 76, 80, () -> { fieldPage--; rebuildWidgets(); }).active = fieldPage > 0;
                button(Texts.tr("screen.tacz_pubg.map_config.next_field_page"), 94, height - 76, 80, () -> { fieldPage++; rebuildWidgets(); }).active = fieldPage + 1 < pages;
            }
        }
        int footerWidth = Math.max(40, (width - 28) / 4);
        button(Texts.tr("screen.tacz_pubg.map_list.title"), 8, height - 26, footerWidth, this::returnToList).active = !pending;
        saveButton = button(Texts.tr("screen.tacz_pubg.configuration.save"), 20 + footerWidth * 3, height - 26, footerWidth, this::save);
        saveButton.active = !pending && !conflicted && revision >= 0 && dirty();
        button(Texts.tr("screen.tacz_pubg.map_config.reload"), 16 + footerWidth * 2, height - 26, footerWidth, this::reload).active = !pending;
        button(Texts.tr("screen.tacz_pubg.visibility_config.back_configuration"), 12 + footerWidth, height - 26, footerWidth, this::onClose).active = !pending;
    }

    private void tab(String label, View target, int x, int width) {
        Button tab = button((view == target ? "> " : "") + label, x, 66, width, () -> {
            if (target == View.PREVIEW) { openNativePreview(); return; }
            view = target;
            fieldPage = 0;
            rebuildWidgets();
        });
        tab.active = !pending;
    }

    private void bombControls() {
        int navigationWidth = Math.min(130, Math.max(85, width - 168));
        button("<", 8, 92, 24, () -> { selectedBomb--; fieldPage = 0; rebuildWidgets(); })
                .active = selectedBomb > 0;
        button(">", 8 + navigationWidth - 24, 92, 24, () -> { selectedBomb++; fieldPage = 0; rebuildWidgets(); })
                .active = selectedBomb + 1 < draft.bombs.size();
        button(Texts.tr("screen.tacz_pubg.map_config.add"), width - 124, 92, 54, () -> {
            try { draft.bombs.add(RawBomb.fresh(draft)); }
            catch (IllegalArgumentException exception) { showMessage(exception.getMessage(), true); return; }
            selectedBomb = draft.bombs.size() - 1;
            fieldPage = 0;
            rebuildWidgets();
        }).active = draft.bombs.size() < MapZoneModel.MAX_BOMBING_ZONES;
        button(Texts.tr("screen.tacz_pubg.loot_config.remove"), width - 64, 92, 56, () -> {
            if (draft.bombs.isEmpty()) return;
            draft.bombs.remove(selectedBomb);
            selectedBomb = Math.max(0, Math.min(selectedBomb, draft.bombs.size() - 1));
            fieldPage = 0;
            rebuildWidgets();
        }).active = !draft.bombs.isEmpty();
    }

    private List<Field> fields() {
        List<Field> fields = new ArrayList<>();
        if (draft == null) return fields;
        if (view == View.MAP) {
            fields.add(new Field(Texts.tr("screen.tacz_pubg.map_config.map_name"), () -> draft.name, value -> draft.name = value, true, false));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.minimum_x"), () -> draft.minX, value -> draft.minX = value));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.minimum_z"), () -> draft.minZ, value -> draft.minZ = value));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.maximum_x"), () -> draft.maxX, value -> draft.maxX = value));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.maximum_z"), () -> draft.maxZ, value -> draft.maxZ = value));
        } else if (view == View.SAFE) {
            if (draft.safe) {
                fields.add(field(Texts.tr("screen.tacz_pubg.map_config.safe_zone_center_x"), () -> draft.safeX, value -> draft.safeX = value));
                fields.add(field(Texts.tr("screen.tacz_pubg.map_config.safe_zone_center_z"), () -> draft.safeZ, value -> draft.safeZ = value));
                fields.add(field(Texts.tr("screen.tacz_pubg.map_config.safe_zone_radius_m"), () -> draft.safeRadius, value -> draft.safeRadius = value));
            }
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.blue_zone_damage_per_second_hp"), () -> draft.blueDamage, value -> draft.blueDamage = value));
        } else if (view == View.FLIGHT && draft.flight) {
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.route_start_x"), () -> draft.flightStartX, value -> draft.flightStartX = value));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.route_start_z"), () -> draft.flightStartZ, value -> draft.flightStartZ = value));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.route_end_x"), () -> draft.flightEndX, value -> draft.flightEndX = value));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.route_end_z"), () -> draft.flightEndZ, value -> draft.flightEndZ = value));
        } else if (view == View.COLORS) {
            fields.add(colorField(Texts.tr("screen.tacz_pubg.map_config.safe_zone_boundary_rgb"), () -> draft.safeRgb, value -> draft.safeRgb = value));
            fields.add(colorField(Texts.tr("screen.tacz_pubg.map_config.blue_zone_rgb"), () -> draft.blueRgb, value -> draft.blueRgb = value));
            fields.add(colorField(Texts.tr("screen.tacz_pubg.map_config.bombardment_zone_rgb"), () -> draft.bombRgb, value -> draft.bombRgb = value));
            fields.add(colorField(Texts.tr("screen.tacz_pubg.map_config.lightning_rgb"), () -> draft.lightningRgb, value -> draft.lightningRgb = value));
            fields.add(colorField(Texts.tr("screen.tacz_pubg.map_config.map_boundary_rgb"), () -> draft.borderRgb, value -> draft.borderRgb = value));
            fields.add(colorField(Texts.tr("screen.tacz_pubg.map_config.flight_route_rgb"), () -> draft.flightRgb, value -> draft.flightRgb = value));
            for (var tier : com.example.examplemod.lootconfig.LootTier.values())
                fields.add(colorField(tier.label() + Texts.tr("screen.tacz_pubg.map_config.tier_loot_rgb"), () -> draft.lootRgb.get(tier.id()), value -> draft.lootRgb.put(tier.id(), value)));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.floors_reach_solid_black_n"), () -> draft.lootLayers, value -> draft.lootLayers = value));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.map_blue_zone_opacity"), () -> draft.blueMapPercent, value -> draft.blueMapPercent = value));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.map_bombardment_zone_opacity"), () -> draft.bombMapPercent, value -> draft.bombMapPercent = value));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.world_blue_zone_opacity"), () -> draft.blueWorldPercent, value -> draft.blueWorldPercent = value));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.screen_edge_opacity_inside_zone"), () -> draft.blueScreenPercent, value -> draft.blueScreenPercent = value));
        } else if (view == View.BOMB && !draft.bombs.isEmpty()) {
            selectedBomb = Math.clamp(selectedBomb, 0, draft.bombs.size() - 1);
            RawBomb bomb = draft.bombs.get(selectedBomb);
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.bombardment_center_x"), () -> bomb.x, value -> bomb.x = value));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.bombardment_center_z"), () -> bomb.z, value -> bomb.z = value));
            fields.add(new Field(Texts.tr("screen.tacz_pubg.map_config.bombardment_zone_radius_m"), () -> bomb.radius, value -> bomb.radius = value, false, false, true));
            fields.add(field(Texts.tr("screen.tacz_pubg.map_config.missiles_per_minute_integer"), () -> bomb.missiles, value -> bomb.missiles = value));
            // Legacy values stay in the map document for compatibility. Live ammunition properties
            // are edited only in 基础设置 and frozen independently when a match starts.
        }
        return fields;
    }

    private static Field field(String name, Supplier<String> value, Consumer<String> edit) {
        return new Field(name, value, edit, false, false);
    }

    private static Field colorField(String name, Supplier<String> value, Consumer<String> edit) {
        return new Field(name, value, edit, true, true);
    }

    private Button saveButton;

    private void updateSaveButton() {
        if (saveButton != null) {
            saveButton.active = !pending && !conflicted && revision >= 0 && dirty();
            saveButton.visible = dirty() || writing;
        }
    }

    private boolean dirty() { return draft != null && (original == null || !draft.values().equals(original)); }
    @Override public boolean hasUnsavedChanges() { return dirty(); }

    MapDefinition validatedDraft() {
        if (draft == null) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.map_config.create_select_map_first"));
        MapDefinition definition = draft.model();
        List<String> errors = MapZoneModel.validate(definition);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        MapZoneModel.toJson(definition); // Match the server's bounded UTF-8 document limit before sending.
        return definition;
    }

    private void newMap() {
        if (pending || conflicted || revision < 0 || total >= MapZoneModel.MAX_MAPS) return;
        minecraft.setScreen(new MapNameScreen(this, suggestedMapName, this::newMap));
    }

    private void newMap(String name) {
        if (pending || conflicted || revision < 0 || total >= MapZoneModel.MAX_MAPS) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        double x = minecraft.player.getX(), z = minecraft.player.getZ();
        draft = RawMap.fresh(minecraft.level.dimension().location(), x, z, name);
        original = null;
        originalMap = null;
        view = View.MAP;
        selectedBomb = 0;
        fieldPage = 0;
        showMessage(Texts.tr("screen.tacz_pubg.map_editor.reset_notice"), false);
        developerForm = false;
        rebuildWidgets();
        openNativePreview();
    }

    private void openMap(long id) {
        openMap(id, false);
    }

    private void openMap(long id, boolean coordinates) {
        if (pending || conflicted) return;
        confirmDiscard(Texts.tr("screen.tacz_pubg.map_config.discard_current_map_draft_switch"), () -> {
            openGraphicalAfterLoad = !coordinates;
            developerForm = coordinates;
            MapZoneConfigClient.request(this, revision, offset, id);
        });
    }

    private void loadPage(int nextOffset) {
        if (pending || conflicted) return;
        MapZoneConfigClient.request(this, revision, nextOffset, 0);
    }

    private void save() {
        if (pending || conflicted || revision < 0 || !dirty()) return;
        MapDefinition updated;
        try { updated = validatedDraft(); }
        catch (IllegalArgumentException error) { showMessage(error.getMessage(), true); createGameAfterSave = false; return; }
        boolean fresh = draft.id == 0;
        boolean resized = originalMap != null && originalMap.id() == updated.id()
                && (originalMap.minX() != updated.minX() || originalMap.maxX() != updated.maxX()
                || originalMap.minZ() != updated.minZ() || originalMap.maxZ() != updated.maxZ());
        if (fresh || resized) {
            minecraft.setScreen(new ConfirmScreen(accepted -> {
                minecraft.setScreen(this);
                if (accepted) MapZoneConfigClient.save(this);
                else createGameAfterSave = false;
            }, Texts.component(fresh ? Texts.tr("screen.tacz_pubg.map_config.create_map_establish_reset_baseline") : Texts.tr("screen.tacz_pubg.map_config.change_bounds_back_up_entire_map_again")),
                    Texts.component((resized ? Texts.tr("screen.tacz_pubg.map_config.new_baseline_will_use_current_terrain_within_new") : "")
                            + com.example.examplemod.backup.MapResetService.NOTICE),
                    Texts.component(fresh ? Texts.tr("screen.tacz_pubg.map_config.create_back_up") : Texts.tr("screen.tacz_pubg.map_config.save_back_up_again")), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
        } else MapZoneConfigClient.save(this);
    }

    private void useForGame() {
        if (draft == null || pending || conflicted || revision < 0) return;
        try { validatedDraft(); }
        catch (IllegalArgumentException exception) { showMessage(exception.getMessage(), true); rebuildWidgets(); return; }
        if (dirty() || draft.id == 0) {
            createGameAfterSave = true;
            save();
        } else openSavedMapForGame();
    }

    private void openSavedMapForGame() {
        if (draft != null && entityRecordingPending(draft.id)) { openEntityRecordingChoice(this); return; }
        if (draft == null || draft.id <= 0 || dirty() || pending || conflicted) return;
        Screen root = ConfigUi.root(this);
        MatchConfigClient.open(root == this ? this : root, draft.id);
    }

    /** Called only by the graphical editor's explicit save/create-game buttons. */
    public void saveNativeDraft(MapDefinition updated, boolean forGame) {
        if (pending || conflicted) return;
        applyNativeDraft(updated);
        createGameAfterSave = forGame;
        if (forGame) useForGame();
        else if (dirty()) save();
        else { clearMapDraft(); showMessage(Texts.tr("screen.tacz_pubg.map_config.map_already_matches_saved_server_version"), false); rebuildWidgets(); }
    }

    private void applyNativeDraft(MapDefinition updated) {
        if (draft == null || updated.id() != draft.id || !updated.dimension().equals(draft.dimension))
            throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.map_config.keep_current_map_id_dimension"));
        List<String> errors = MapZoneModel.validate(updated);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        MapZoneModel.toJson(updated);
        draft = new RawMap(updated);
    }

    /** Copy the local geometry first; switching tabs retains the independently revisioned map draft. */
    public void navigateNativeDraft(MapDefinition updated, int index) {
        applyNativeDraft(updated);
        minecraft.setScreen(this);
        navigateConfigTab(index);
    }

    public void listNativeDraft(MapDefinition updated) {
        applyNativeDraft(updated);
        minecraft.setScreen(this);
        returnToList();
    }

    /** The editor has already confirmed discarding. Never route through a single-map landing page. */
    public void discardNativeDraft() {
        if (pending) return;
        clearMapDraft();
        minecraft.setScreen(this);
    }

    private void clearMapDraft() {
        entityRecordingPendingMapId = 0;
        draft = null;
        original = null;
        view = View.LIST;
        fieldPage = 0;
        developerForm = false;
        openGraphicalAfterLoad = false;
        createGameAfterSave = false;
        rebuildWidgets();
    }

    public void deleteNativeDraft(MapDefinition updated) {
        applyNativeDraft(updated);
        minecraft.setScreen(this);
        if (draft.id == 0) returnToList();
        else deleteMap();
    }

    public void navigateConfigTab(int index) {
        if (index == 2 || index < 0 || index > 3) return;
        if (pending) { showMessage(Texts.tr("screen.tacz_pubg.map_config.wait_map_confirmation_server_before_switching_configuration_pages"), true); return; }
        Runnable navigate = () -> {
            Screen root = ConfigUi.root(this);
            if (root instanceof LootConfigScreen loot) { loot.openConfigTab(index); return; }
            if (index == 3) { MatchConfigClient.open(this); return; }
            showMessage(Texts.tr("screen.tacz_pubg.map_config.page_was_opened_independently_by_map_command_use"), true);
            rebuildWidgets();
        };
        if (!dirty()) { navigate.run(); return; }
        minecraft.setScreen(new ConfirmScreen(keep -> {
            minecraft.setScreen(this);
            if (keep) navigate.run();
        }, Texts.of("screen.tacz_pubg.map_config.keep_map_draft_switch"),
                Texts.of("screen.tacz_pubg.map_config.map_draft_will_remain_configuration_session"),
                Texts.of("screen.tacz_pubg.map_config.keep_switch"), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
    }

    private void openNativePreview() {
        if (draft == null || pending || conflicted) return;
        try {
            MapDefinition model = validatedDraft();
            boolean opened = com.example.examplemod.zones.client.xaero.editor.NativeMapEditor.open(
                    this, model, updated -> {
                        saveNativeDraft(updated, false);
                    }, () -> {
                        if (draft != null && draft.id == 0) returnToList();
                        else deleteMap();
                    });
            if (!opened) showMessage(com.example.examplemod.zones.client.xaero.editor.NativeMapEditor.lastError(), true);
        } catch (IllegalArgumentException exception) {
            showMessage(Texts.tr("screen.tacz_pubg.map_config.fix_configuration_before_opening_map_editor", exception.getMessage()), true);
        }
    }

    private void deleteMap(Summary map) {
        if (pending || conflicted) return;
        minecraft.setScreen(new ConfirmScreen(confirm -> {
            minecraft.setScreen(this);
            if (confirm) MapZoneConfigClient.delete(this, map.id());
        }, Texts.of("screen.tacz_pubg.map_config.delete_map.delete_map", map.id(), map.name()),
                Texts.of("screen.tacz_pubg.map_config.delete_map_s_boundaries_flight_route_safe_zone"),
                Texts.of("screen.tacz_pubg.map_config.delete_map"), Texts.of("screen.tacz_pubg.map_config.cancel")));
    }

    private void deleteMap() {
        if (draft == null || draft.id == 0 || pending || conflicted) return;
        long id = draft.id;
        minecraft.setScreen(new ConfirmScreen(confirm -> {
            minecraft.setScreen(this);
            if (confirm) MapZoneConfigClient.delete(this, id);
        }, Texts.of("screen.tacz_pubg.map_config.delete_map_zone_configuration", id),
                Texts.of("screen.tacz_pubg.map_config.delete_map_definition_safe_zone_bombardment_zones_flight"),
                Texts.of("screen.tacz_pubg.map_config.delete_definition"), Texts.of("screen.tacz_pubg.map_config.cancel")));
    }

    private void reload() {
        if (pending) return;
        confirmDiscard(Texts.tr("screen.tacz_pubg.map_config.discard_map_draft_reload"), () -> {
            long id = draft == null ? 0 : draft.id;
            draft = null;
            original = null;
            fieldPage = 0;
            conflicted = false;
            revision = -1;
            if (id == 0) view = View.LIST;
            MapZoneConfigClient.request(this, -1, offset, id);
        });
    }

    private void returnToList() {
        if (pending) return;
        confirmDiscard(Texts.tr("screen.tacz_pubg.map_config.discard_map_draft_return_list"), this::clearMapDraft);
    }

    private void confirmDiscard(String title, Runnable action) {
        if (!dirty()) { action.run(); return; }
        minecraft.setScreen(new ConfirmScreen(discard -> {
            minecraft.setScreen(this);
            if (discard) action.run();
        }, Texts.component(title), Texts.of("screen.tacz_pubg.map_config.discard_page_s_map_zone_draft_server_data"),
                Texts.of("screen.tacz_pubg.map_config.discard_continue"), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
    }

    void setPending(boolean writing) {
        pending = true;
        this.writing = writing;
        showMessage(writing ? Texts.tr("screen.tacz_pubg.map_config.waiting_server_save_confirmation") : Texts.tr("screen.tacz_pubg.map_config.reading_maps"), false);
        rebuildWidgets();
    }

    void requestFailed(String message) {
        pending = false;
        writing = false;
        createGameAfterSave = false;
        openGraphicalAfterLoad = false;
        showMessage(message, true);
        rebuildWidgets();
    }

    void accept(Snapshot snapshot) {
        pending = false;
        writing = false;
        if (snapshot.outcome() == MapZoneConfigPayloads.CONFLICT) {
            createGameAfterSave = false;
            openGraphicalAfterLoad = false;
            conflicted = true;
            showMessage(snapshot.message() + Texts.tr("screen.tacz_pubg.map_config.draft_retained_explicitly_discard_reload_latest_version"), true);
            rebuildWidgets();
            return;
        }
        if (snapshot.outcome() == MapZoneConfigPayloads.FAILED) {
            createGameAfterSave = false;
            openGraphicalAfterLoad = false;
            showMessage(snapshot.message(), true);
            rebuildWidgets();
            return;
        }
        revision = snapshot.revision();
        total = snapshot.total();
        offset = snapshot.offset();
        maps = snapshot.maps();
        suggestedMapName = snapshot.suggestedMapName();
        conflicted = false;
        if (snapshot.detail() != null) entityRecordingPendingMapId = snapshot.entityRecordingPending() ? snapshot.detail().id() : 0;
        if (snapshot.entityRecordingPending() && snapshot.detail() != null) {
            draft = new RawMap(snapshot.detail()); originalMap = snapshot.detail(); original = draft.values();
            view = View.MAP; showMessage(snapshot.message(), false); rebuildWidgets();
            openEntityRecordingChoice(this);
            return;
        }
        if (snapshot.outcome() == MapZoneConfigPayloads.SAVED && !createGameAfterSave) {
            draft = null; original = null; view = View.LIST; openGraphicalAfterLoad = false;
        } else if (snapshot.outcome() == MapZoneConfigPayloads.DELETED) {
            draft = null;
            original = null;
            view = View.LIST;
        } else if (snapshot.detail() != null && snapshot.outcome() != MapZoneConfigPayloads.ENTITY_RECORDING_DECIDED) {
            draft = new RawMap(snapshot.detail());
            originalMap = snapshot.detail();
            original = draft.values();
            if (view == View.LIST) view = View.MAP;
            selectedBomb = Math.max(0, Math.min(selectedBomb, draft.bombs.size() - 1));
        } else if (snapshot.outcome() == MapZoneConfigPayloads.SAVED) {
            // A successful new map must never remain an id-zero draft that can be saved twice.
            draft = null;
            original = null;
            view = View.LIST;
            if (snapshot.selectedMapId() > 0) {
                MapZoneConfigClient.request(this, revision, offset, snapshot.selectedMapId());
                return;
            }
        }
        showMessage(snapshot.message(), false);
        rebuildWidgets();
        if ((snapshot.outcome() == MapZoneConfigPayloads.SAVED || snapshot.outcome() == MapZoneConfigPayloads.PAGE
                || snapshot.outcome() == MapZoneConfigPayloads.ENTITY_RECORDING_DECIDED)
                && createGameAfterSave && draft != null && draft.id > 0 && !dirty()) {
            createGameAfterSave = false;
            openSavedMapForGame();
        } else if (openGraphicalAfterLoad && draft != null) {
            openGraphicalAfterLoad = false;
            openNativePreview();
        } else if (snapshot.outcome() == MapZoneConfigPayloads.ENTITY_RECORDING_DECIDED && entityChoiceReturnTo != null && entityChoiceReturnTo != this) {
            Screen resume = entityChoiceReturnTo; entityChoiceReturnTo = null; minecraft.setScreen(resume);
        }
    }

    public boolean entityRecordingPending(long mapId) {
        return mapId > 0 && entityRecordingPendingMapId == mapId;
    }

    /** The map draft and native editor remain owned by their existing screens. ESC changes no server choice. */
    public void openEntityRecordingChoice(Screen returnTo, MapDefinition nativeDraft) {
        applyNativeDraft(nativeDraft);
        openEntityRecordingChoice(returnTo);
    }

    public void openEntityRecordingChoice(Screen returnTo) {
        if (draft == null || pending || conflicted || !entityRecordingPending(draft.id)) return;
        long mapId = draft.id; entityChoiceReturnTo = returnTo;
        minecraft.setScreen(new MapEntityRecordingScreen(returnTo, originalMap, record -> {
            minecraft.setScreen(this);
            MapZoneConfigClient.chooseEntityRecording(this, mapId, record);
        }));
    }

    private void showMessage(String message, boolean error) {
        this.message = message == null ? "" : message;
        this.error = error;
    }

    @Override
    public void onClose() {
        if (pending) { showMessage(Texts.tr("screen.tacz_pubg.map_config.wait_server_confirmation_wait_ends_automatically_timeout"), true); return; }
        if (!showingList()) { returnToList(); return; }
        confirmDiscard(Texts.tr("screen.tacz_pubg.map_config.discard_map_draft_return_configuration"), () -> {
            MapZoneConfigClient.closed(this);
            ConfigUi.forget(this);
            minecraft.setScreen(parent);
            if (parent instanceof com.example.examplemod.zones.match.client.MatchConfigScreen match)
                match.onMapEditorReturned();
        });
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        String status = Texts.tr("screen.tacz_pubg.map_config.maps_version", total, MapZoneModel.MAX_MAPS, (revision < 0 ? Texts.tr("screen.tacz_pubg.spawn_region_config.awaiting_loading") : revision), (dirty() ? Texts.tr("screen.tacz_pubg.map_config.unsaved") : ""), (conflicted ? Texts.tr("screen.tacz_pubg.spawn_region_config.version_conflict") : pending ? writing ? Texts.tr("screen.tacz_pubg.spawn_region_config.saving") : Texts.tr("screen.tacz_pubg.spawn_region_config.loading") : ""));
        ConfigUi.renderHeader(graphics, font, width, Texts.tr("screen.tacz_pubg.map_editor.title"), status, conflicted ? 0xFFFF8888 : 0xFFB5D9FF);
        if (showingList()) renderList(graphics);
        else renderEditor(graphics, mouseX, mouseY);
        String phase = Texts.tr("screen.tacz_pubg.map_config.bombardment_properties_shared_colors_general_settings_zone_damage");
        if (draft != null && view != View.LIST && !ConfigUi.developerMode())
            graphics.drawString(font, Texts.resolve(fit(phase, width - 16)), 8, height - 52, 0xFFDECF9B);
        if (mouseY >= height - 65 && mouseY < height - 50) {
            graphics.renderTooltip(font, font.split(Texts.component(phase
                    + Texts.tr("screen.tacz_pubg.map_config.graphical_editing_changes_local_draft_saving_applies_after")), width - 24), mouseX, mouseY);
        }
        graphics.drawString(font, Texts.resolve(fit(message, width - 16)), 8, height - 39, error ? 0xFFFF8888 : 0xFFB0E0B0);
        if (!message.isBlank() && mouseY >= height - 43 && mouseY < height - 28) {
            graphics.renderTooltip(font, font.split(Texts.component(message), Math.max(100, width - 24)), mouseX, mouseY);
        }
    }

    private void renderList(GuiGraphics graphics) {
        graphics.drawString(font, Texts.of("screen.tacz_pubg.map_config.select_create_map_draw_directly_actual_terrain").getString(), 8, 94, 0xFFE0E0E0);
        if (maps.isEmpty()) graphics.drawString(font, Texts.of("screen.tacz_pubg.map_config.click_new_map_start_drawing").getString(), 8, 112, 0xFFAAAAAA);
        int shown = maps.isEmpty() ? 0 : offset + 1;
        graphics.drawString(font, Texts.resolve(shown + "–" + (offset + maps.size()) + " / " + total), 154, height - 62, 0xFFAAAAAA);
    }

    private void renderEditor(GuiGraphics graphics, int mouseX, int mouseY) {
        if (view == View.PREVIEW) {
            try {
                MapDefinition model = validatedDraft();
                MapZonePreview.draw(graphics, model, 8, 65, width - 16, Math.max(8, height - 151));
                graphics.drawString(font, Texts.resolve(fit(Texts.tr("screen.tacz_pubg.map_config.north_z_east_x_blue_fill_outside_safe"), width - 16)),
                        8, height - 79, 0xFFE0E0E0);
            } catch (IllegalArgumentException exception) {
                graphics.enableScissor(8, 65, width - 8, Math.max(66, height - 86));
                graphics.drawWordWrap(font, Texts.resolve(Texts.of("screen.tacz_pubg.map_config.fix_configuration_preview", exception.getMessage())),
                        8, 68, width - 16, 0xFFFF8888);
                graphics.disableScissor();
            }
            return;
        }
        if (view == View.MAP) {
            String text = (draft.id == 0 ? Texts.tr("screen.tacz_pubg.map_editor.default_name") : "#" + draft.id) + " · " + draft.dimension;
                graphics.drawString(font, Texts.resolve(fit(text, width - (draft.id > 0 ? 92 : 16))), 8, 97, 0xFFB5D9FF);
        } else if (view == View.SAFE) {
            graphics.drawString(font, Texts.resolve(fit(Texts.tr("screen.tacz_pubg.map_config.center_inside_map_overflow_clipped"), width - 128)), 120, 97, 0xFFAAAAAA);
        } else if (view == View.FLIGHT) {
            graphics.drawString(font, Texts.resolve(fit(Texts.tr("screen.tacz_pubg.map_config.start_end_both_endpoints_inside_map"), width - 128)), 120, 97, 0xFFAAAAAA);
            if (!draft.flight) graphics.drawString(font, Texts.of("screen.tacz_pubg.map_config.click_create_flight_route_set_endpoints").getString(), 8, 128, 0xFFAAAAAA);
        } else if (view == View.COLORS) {
            graphics.drawString(font, Texts.resolve(fit(Texts.tr("screen.tacz_pubg.map_config.these_colors_apply_when_general_settings_uses_per"), width - 128)), 8, 97, 0xFFAAAAAA);
        } else if (view == View.BOMB) {
            if (draft.bombs.isEmpty()) graphics.drawString(font, Texts.of("screen.tacz_pubg.map_config.click_add_draw_bombardment_zone").getString(), 8, 128, 0xFFAAAAAA);
            else {
                RawBomb bomb = draft.bombs.get(selectedBomb);
                String text = (selectedBomb + 1) + "/" + draft.bombs.size() + (bomb.id == 0 ? Texts.tr("screen.tacz_pubg.map_config.new") : " #" + bomb.id);
                int navigationWidth = Math.min(130, Math.max(85, width - 168));
                graphics.drawCenteredString(font, Texts.resolve(fit(text, navigationWidth - 52)), 8 + navigationWidth / 2, 98, 0xFFE0E0E0);
            }
        }
        List<Field> fields = fields();
        int columnWidth = Math.max(40, (width - 28) / 2), start = fieldPage * fieldsPerPage;
        for (int index = start; index < Math.min(fields.size(), start + fieldsPerPage); index++) {
            int local = index - start, x = 8 + local % 2 * (columnWidth + 12), y = 116 + local / 2 * 38;
            graphics.drawString(font, Texts.resolve(fit(fields.get(index).label(), columnWidth)), x, y, 0xFFE0E0E0);
            Field field = fields.get(index);
            if (field.color()) {
                int swatchX = x + columnWidth - 20, swatchY = y + 15;
                try {
                    int rgb = MapVisualStyle.parseHex(field.value().get());
                    graphics.fill(swatchX, swatchY, swatchX + 17, swatchY + 13, 0xFF000000 | rgb);
                } catch (IllegalArgumentException exception) {
                    graphics.fill(swatchX, swatchY, swatchX + 17, swatchY + 13, 0xFF491A25);
                    graphics.drawCenteredString(font, Texts.resolve("!"), swatchX + 8, swatchY + 2, 0xFFFF8888);
                }
            }
        }
        int pages = Math.max(1, (fields.size() + fieldsPerPage - 1) / fieldsPerPage);
        if (pages > 1) graphics.drawString(font, Texts.of("screen.tacz_pubg.map_config.parameters", (fieldPage + 1), pages).getString(),
                182, height - 70, 0xFFAAAAAA);
    }

    private String fit(String text, int pixels) { return font.plainSubstrByWidth(Texts.resolve(text), Math.max(0, pixels)); }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private Button button(String text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(ConfigUi.button(Texts.component(text), ignored -> action.run())
                .bounds(x, y, Math.max(20, width), 20).build());
    }

    private static double numeric(String value, String label) {
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed)) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + Texts.tr("screen.tacz_pubg.map_config.finite_number_input_retained"));
        }
    }

    /** Text survives tab switches, resize, validation failures and revision conflicts. */
    private static final class RawMap {
        final long id;
        final ResourceLocation dimension;
        String name, minX, minZ, maxX, maxZ, safeX, safeZ, safeRadius, blueDamage;
        String flightStartX, flightStartZ, flightEndX, flightEndZ;
        String safeRgb, blueRgb, bombRgb, lightningRgb, borderRgb, flightRgb;
        final java.util.Map<String, String> lootRgb = new java.util.LinkedHashMap<>();
        String lootLayers;
        String blueMapPercent, bombMapPercent, blueWorldPercent, blueScreenPercent;
        boolean safe, flight;
        Circle stagingSpawn;
        final List<RawBomb> bombs = new ArrayList<>();

        RawMap(MapDefinition model) {
            id = model.id(); dimension = model.dimension(); name = model.name();
            stagingSpawn = model.stagingSpawn();
            minX = number(model.minX()); minZ = number(model.minZ());
            maxX = number(model.maxX()); maxZ = number(model.maxZ());
            safe = model.safeZone() != null;
            safeX = number(safe ? model.safeZone().centerX() : (model.minX() + model.maxX()) / 2);
            safeZ = number(safe ? model.safeZone().centerZ() : (model.minZ() + model.maxZ()) / 2);
            safeRadius = number(safe ? model.safeZone().radius()
                    : Math.max(1, Math.min(model.maxX() - model.minX(), model.maxZ() - model.minZ()) / 3));
            blueDamage = number(model.blueDamagePerSecond());
            flight = model.flightPath() != null;
            flightStartX = number(flight ? model.flightPath().startX() : model.minX());
            flightStartZ = number(flight ? model.flightPath().startZ() : (model.minZ() + model.maxZ()) / 2);
            flightEndX = number(flight ? model.flightPath().endX() : model.maxX());
            flightEndZ = number(flight ? model.flightPath().endZ() : (model.minZ() + model.maxZ()) / 2);
            setVisualStyle(model.visualStyle());
            for (BombingZone bomb : model.bombingZones()) bombs.add(new RawBomb(bomb));
        }

        static RawMap fresh(ResourceLocation dimension, double x, double z, String name) {
            double extent = 64;
            x = Math.clamp(x, -MapZoneModel.MAX_COORDINATE + extent, MapZoneModel.MAX_COORDINATE - extent);
            z = Math.clamp(z, -MapZoneModel.MAX_COORDINATE + extent, MapZoneModel.MAX_COORDINATE - extent);
            return new RawMap(new MapDefinition(0, name, dimension, x - extent, z - extent, x + extent, z + extent,
                    null, MapZoneModel.DEFAULT_BLUE_DAMAGE_PER_SECOND, List.of()));
        }

        MapDefinition model() {
            Circle circle = safe ? new Circle(numeric(safeX, Texts.tr("screen.tacz_pubg.map_config.safe_zone_center_x")), numeric(safeZ, Texts.tr("screen.tacz_pubg.map_config.safe_zone_center_z")),
                    numeric(safeRadius, Texts.tr("screen.tacz_pubg.map_config.safe_zone_radius"))) : null;
            List<BombingZone> zones = new ArrayList<>();
            for (int index = 0; index < bombs.size(); index++) zones.add(bombs.get(index).model(index + 1));
            FlightPath path = flight ? new FlightPath(numeric(flightStartX, Texts.tr("screen.tacz_pubg.map_config.route_start_x")), numeric(flightStartZ, Texts.tr("screen.tacz_pubg.map_config.route_start_z")),
                    numeric(flightEndX, Texts.tr("screen.tacz_pubg.map_config.route_end_x")), numeric(flightEndZ, Texts.tr("screen.tacz_pubg.map_config.route_end_z"))) : null;
            return new MapDefinition(id, name, dimension, numeric(minX, Texts.tr("screen.tacz_pubg.map_config.minimum_x")), numeric(minZ, Texts.tr("screen.tacz_pubg.map_config.minimum_z")),
                    numeric(maxX, Texts.tr("screen.tacz_pubg.map_config.maximum_x")), numeric(maxZ, Texts.tr("screen.tacz_pubg.map_config.maximum_z")), circle, numeric(blueDamage, Texts.tr("screen.tacz_pubg.map_config.blue_zone_damage_per_second")), zones, path, visualStyle(), stagingSpawn);
        }

        void setVisualStyle(MapVisualStyle style) {
            safeRgb = MapVisualStyle.hex(style.safeZoneRgb());
            blueRgb = MapVisualStyle.hex(style.blueZoneRgb());
            bombRgb = MapVisualStyle.hex(style.bombingZoneRgb());
            lightningRgb = MapVisualStyle.hex(style.lightningRgb());
            borderRgb = MapVisualStyle.hex(style.mapBorderRgb());
            flightRgb = MapVisualStyle.hex(style.flightPathRgb());
            style.lootTierRgb().forEach((id, rgb) -> lootRgb.put(id, MapVisualStyle.hex(rgb)));
            lootLayers = Integer.toString(style.lootOverlapBlackLayers());
            blueMapPercent = number(style.blueMapOpacity() * 100);
            bombMapPercent = number(style.bombingMapOpacity() * 100);
            blueWorldPercent = number(style.blueWorldOpacity() * 100);
            blueScreenPercent = number(style.blueScreenOpacity() * 100);
        }

        MapVisualStyle visualStyle() {
            return new MapVisualStyle(color(safeRgb, Texts.tr("screen.tacz_pubg.zone_settings.safe_zone_boundary")), color(blueRgb, Texts.tr("screen.tacz_pubg.zone_settings.blue_zone")), color(bombRgb, Texts.tr("screen.tacz_pubg.zone_settings.bombardment_zone")),
                    color(lightningRgb, Texts.tr("screen.tacz_pubg.zone_settings.lightning")), color(borderRgb, Texts.tr("screen.tacz_pubg.zone_settings.map_boundary")), color(flightRgb, Texts.tr("screen.tacz_pubg.zone_settings.flight_route")),
                    opacity(blueMapPercent, Texts.tr("screen.tacz_pubg.zone_settings.map_blue_zone")), opacity(bombMapPercent, Texts.tr("screen.tacz_pubg.zone_settings.map_bombardment_zone")),
                    opacity(blueWorldPercent, Texts.tr("screen.tacz_pubg.zone_settings.world_blue_zone")), opacity(blueScreenPercent, Texts.tr("screen.tacz_pubg.map_config.screen_edge_inside_zone")), lootColors(), lootLayerCount());
        }

        private java.util.Map<String, Integer> lootColors() {
            var result = new java.util.LinkedHashMap<String, Integer>();
            lootRgb.forEach((id, value) -> result.put(id, color(value, Texts.tr("screen.tacz_pubg.loot_config.loot"))));
            return result;
        }
        private int lootLayerCount() {
            try { return Integer.parseInt(lootLayers.trim()); }
            catch (NumberFormatException error) { throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.map_config.floors_reach_solid_black_n_integer")); }
        }
        private static int color(String value, String label) {
            try { return MapVisualStyle.parseHex(value); }
            catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(label + Texts.tr("screen.tacz_pubg.map_config.color_six_digit_hex_rgb_optional_input_retained"));
            }
        }

        private static double opacity(String value, String label) {
            double percent = numeric(value, label + Texts.tr("screen.tacz_pubg.zone_settings_draft.opacity"));
            if (percent < 0 || percent > 100) throw new IllegalArgumentException(label + Texts.tr("screen.tacz_pubg.map_config.opacity_input_retained"));
            return percent / 100;
        }

        double[] rectangle() {
            double x1 = numeric(minX, Texts.tr("screen.tacz_pubg.map_config.minimum_x")), z1 = numeric(minZ, Texts.tr("screen.tacz_pubg.map_config.minimum_z"));
            double x2 = numeric(maxX, Texts.tr("screen.tacz_pubg.map_config.maximum_x")), z2 = numeric(maxZ, Texts.tr("screen.tacz_pubg.map_config.maximum_z"));
            if (x2 <= x1 || z2 <= z1) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.map_config.set_map_width_depth_positive_values_first_input"));
            return new double[] {x1, z1, x2, z2};
        }

        void createSafe() {
            double[] bounds = rectangle();
            safeX = number((bounds[0] + bounds[2]) / 2);
            safeZ = number((bounds[1] + bounds[3]) / 2);
            safeRadius = number(Math.max(1, Math.min(bounds[2] - bounds[0], bounds[3] - bounds[1]) / 3));
            safe = true;
        }

        void createFlight() {
            double[] bounds = rectangle();
            flightStartX = number(bounds[0]); flightEndX = number(bounds[2]);
            flightStartZ = flightEndZ = number((bounds[1] + bounds[3]) / 2);
            flight = true;
        }

        List<String> values() {
            List<String> values = new ArrayList<>(List.of(name, minX, minZ, maxX, maxZ, Boolean.toString(safe),
                    safeX, safeZ, safeRadius, blueDamage, Boolean.toString(flight),
                    flightStartX, flightStartZ, flightEndX, flightEndZ, safeRgb, blueRgb, bombRgb, lightningRgb,
                    borderRgb, flightRgb, blueMapPercent, bombMapPercent, blueWorldPercent, blueScreenPercent));
            values.add(lootLayers); values.addAll(lootRgb.values());
            values.add(String.valueOf(stagingSpawn));
            for (RawBomb bomb : bombs) values.addAll(List.of(Long.toString(bomb.id), bomb.x, bomb.z,
                    bomb.radius, bomb.missiles, bomb.damage, bomb.blast));
            return List.copyOf(values);
        }
    }

    private static final class RawBomb {
        final long id;
        String x, z, radius, missiles, damage, blast;

        RawBomb(BombingZone model) {
            id = model.id(); x = number(model.area().centerX()); z = number(model.area().centerZ());
            radius = number(model.area().radius()); missiles = Integer.toString(model.missilesPerMinute());
            damage = number(model.missileDamage()); blast = number(model.blastRadius());
        }

        static RawBomb fresh(RawMap map) {
            double[] bounds = map.rectangle();
            double x = (bounds[0] + bounds[2]) / 2, z = (bounds[1] + bounds[3]) / 2;
            double radius = Math.max(1, Math.min(32, Math.min(bounds[2] - bounds[0], bounds[3] - bounds[1]) / 8));
            return new RawBomb(new BombingZone(0, new Circle(x, z, radius), MapZoneModel.DEFAULT_MISSILES_PER_MINUTE,
                    MapZoneModel.DEFAULT_MISSILE_DAMAGE, MapZoneModel.DEFAULT_BLAST_RADIUS));
        }

        BombingZone model(int index) {
            int count;
            try { count = Integer.parseInt(missiles.trim()); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.map_config.missiles_per_minute_bombardment_zone_integer", index)); }
            return new BombingZone(id, new Circle(numeric(x, Texts.tr("screen.tacz_pubg.map_config.bombardment_center_x")), numeric(z, Texts.tr("screen.tacz_pubg.map_config.bombardment_center_z")),
                    numeric(radius, Texts.tr("screen.tacz_pubg.map_config.bombardment_zone_radius"))), count, numeric(damage, Texts.tr("screen.tacz_pubg.map_config.missile_damage")), numeric(blast, Texts.tr("screen.tacz_pubg.map_config.explosion_radius")));
        }
    }
}
