package com.example.examplemod.lootconfig.client;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.lootconfig.LootCatalog;
import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.gunbalance.heavy.HeavyWeaponProfiles;
import net.minecraft.client.gui.components.AbstractSliderButton;
import com.example.examplemod.zones.MapVisualStyle;
import com.example.examplemod.zones.ZoneSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Basic-settings child; application updates the root's independently acknowledged draft. */
final class ZoneSettingsScreen extends Screen {
    private static final String[] COLOR_LABELS = {Texts.tr("screen.tacz_pubg.zone_settings.safe_zone_boundary"), Texts.tr("screen.tacz_pubg.zone_settings.blue_zone"), Texts.tr("screen.tacz_pubg.zone_settings.bombardment_zone"), Texts.tr("screen.tacz_pubg.zone_settings.lightning"), Texts.tr("screen.tacz_pubg.zone_settings.map_boundary"), Texts.tr("screen.tacz_pubg.zone_settings.flight_route"), Texts.tr("screen.tacz_pubg.zone_settings.very_low_loot"), Texts.tr("screen.tacz_pubg.zone_settings.low_loot"), Texts.tr("screen.tacz_pubg.zone_settings.medium_loot"), Texts.tr("screen.tacz_pubg.zone_settings.high_loot"), Texts.tr("screen.tacz_pubg.zone_settings.very_high_loot")};
    private static final String[] OPACITY_LABELS = {Texts.tr("screen.tacz_pubg.zone_settings.map_blue_zone"), Texts.tr("screen.tacz_pubg.zone_settings.map_bombardment_zone"), Texts.tr("screen.tacz_pubg.zone_settings.world_blue_zone"), Texts.tr("screen.tacz_pubg.zone_settings.blue_zone_screen_overlay")};
    private static final int TOP = 62;
    private static final int HEAVY_TOP = 42, HEAVY_ROW_HEIGHT = 326;
    private final Screen parent;
    private final Consumer<ZoneSettings> apply;
    private final List<LootCatalog.Choice> ammunition;
    private final Set<String> availableAmmo;
    private final Set<String> registeredBlocks;
    private final List<LootCatalog.Choice> heavyWeapons;
    private final ZoneSettingsDraft draft;
    private List<String> baseline;
    private List<String> pendingSave;
    private final List<Widget> content = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private Button applyButton;
    private int page;
    private boolean scrollDragging;
    private double scroll;
    private final double[] pageScroll = new double[3];
    private int contentHeight;
    private String validation = "";
    private record Widget(AbstractWidget widget, int y) {}
    private record Label(String text, int x, int y, int color) {}

    ZoneSettingsScreen(Screen parent, ZoneSettings source, List<LootCatalog.Choice> catalog, Consumer<ZoneSettings> apply) {
        super(Texts.of("screen.tacz_pubg.zone_settings.general_bombardment_colors_heavy_weapons"));
        this.parent = parent; this.apply = apply;
        ammunition = catalog.stream().filter(choice -> choice.kind().equals("ammo") && choice.bombingAmmo()).toList();
        availableAmmo = ammunition.stream().map(LootCatalog.Choice::id).collect(Collectors.toUnmodifiableSet());
        registeredBlocks = BuiltInRegistries.BLOCK.keySet().stream().map(ResourceLocation::toString).collect(Collectors.toUnmodifiableSet());
        var loadedHeavy = com.example.examplemod.zones.heavy.HeavyWeaponCatalog.capture();
        heavyWeapons = catalog.stream().filter(choice -> choice.kind().equals("gun") && choice.heavyWeapon() != null)
                .map(choice -> new LootCatalog.Choice(choice.kind(), choice.id(), choice.preview(), choice.attachmentInfo(),
                        choice.ammoDefaults(), choice.bombingAmmo(), choice.gunClass(), loadedHeavy.getOrDefault(choice.id(), choice.heavyWeapon())))
                .toList();
        draft = new ZoneSettingsDraft(source); baseline = draft.values();
    }

    @Override protected void init() {
        content.clear(); labels.clear();
        int tabWidth = (width - 32) / 3;
        String[] tabs = {Texts.tr("screen.tacz_pubg.zone_settings.bombardment_properties"), Texts.tr("screen.tacz_pubg.zone_settings.zone_display_colors"), Texts.tr("screen.tacz_pubg.zone_settings.heavy_weapons")};
        for (int i = 0; i < tabs.length; i++) {
            final int target = i;
            addRenderableWidget(ConfigUi.button(Texts.component((page == i ? "● " : "") + tabs[i]), ignored -> switchPage(target))
                    .bounds(10 + i * (tabWidth + 6), 34, tabWidth, 20).build());
        }
        if (page == 1) initColors(); else if (page == 2) initHeavyWeapons(); else initBombing();
        applyButton = addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.configuration.save"), ignored -> commit())
                .bounds(width - 194, height - 26, 90, 20)
                .tooltip(Tooltip.create(Texts.of("screen.tacz_pubg.zone_settings.save_page_general_draft_await_server_confirmation"))).build());
        addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.operator_permissions.cancel_back"), ignored -> onClose())
                .bounds(width - 98, height - 26, 88, 20).build());
        updateValidation(); position();
    }

    private void switchPage(int value) {
        pageScroll[page] = scroll; page = value; scroll = pageScroll[page]; rebuildWidgets();
    }

    private void initBombing() {
        label(Texts.tr("screen.tacz_pubg.zone_settings.configure_each_projectile_s_damage_explosion"), 10, 6, 0xFFB5D9FF);
        label(Texts.tr("screen.tacz_pubg.zone_settings.available_loaded_rocket_ammo"), 10, 25, 0xFFFFFFFF);
        var selected = ammunition.stream().filter(choice -> choice.id().equals(draft.ammoId)).findFirst();
        String name = selected.map(choice -> choice.preview().getHoverName().getString()).orElse(Texts.tr("screen.tacz_pubg.zone_settings.select_valid_ammo", draft.ammoId));
        Button picker = button(name, 10, 40, width - 28, () -> minecraft.setScreen(new ItemPickerScreen(this, ammunition,
                choice -> { draft.ammoId = choice.id(); rebuildWidgets(); }, "ammo", Texts.of("screen.tacz_pubg.zone_settings.select_bombardment_rocket"))));
        picker.active = !ammunition.isEmpty();
        picker.setTooltip(Tooltip.create(Texts.component(name + "\n" + draft.ammoId + Texts.tr("screen.tacz_pubg.zone_settings.click_select_loaded_rocket"))));
        label(draft.ammoId, 10, 65, selected.isPresent() ? 0xFFAAAAAA : 0xFFFF8888);
        field(Texts.tr("screen.tacz_pubg.zone_settings.spawn_height_y"), 88, draft.spawnHeight, value -> draft.spawnHeight = value, FieldRole.HEIGHT);
        field(Texts.tr("screen.tacz_pubg.zone_settings.center_damage_hp"), 119, draft.damage, value -> draft.damage = value);
        field(Texts.tr("screen.tacz_pubg.zone_settings.explosion_damage_radius_blocks"), 150, draft.radius, value -> draft.radius = value, FieldRole.RADIUS);
        field(Texts.tr("screen.tacz_pubg.zone_settings.edge_damage_center"), 181, draft.edgePercent, value -> draft.edgePercent = value);
        button(Texts.tr("screen.tacz_pubg.zone_settings.break_blocks", (draft.destroyBlocks ? Texts.tr("screen.tacz_pubg.common.enabled") : Texts.tr("screen.tacz_pubg.common.disabled"))), 10, 214, width - 28, () -> {
            draft.destroyBlocks = !draft.destroyBlocks; rebuildWidgets();
        }).setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.zone_settings.enable_bombardment_block_damage")));
        label(Texts.tr("screen.tacz_pubg.zone_settings.damage_falls_linearly_center_edge_zero_outside"), 10, 245, 0xFFDECF9B);
        label(Texts.tr("screen.tacz_pubg.zone_settings.means_zero_edge_damage_means_uniform_damage"), 10, 259, 0xFFAAAAAA);
        label(Texts.tr("screen.tacz_pubg.zone_settings.diagram_center_radius_edge"), 10, 283, 0xFFB5D9FF);
        label(Texts.tr("screen.tacz_pubg.zone_settings.applies_next_match_current_match_keeps_settings"), 10, 366, 0xFFDECF9B);
        button(Texts.tr("screen.tacz_pubg.zone_settings.reset_bombardment_defaults"), 10, 387, width - 28, () -> {
            var defaults = new ZoneSettingsDraft(ZoneSettings.defaults());
            draft.ammoId = defaults.ammoId; draft.damage = defaults.damage; draft.radius = defaults.radius;
            draft.edgePercent = defaults.edgePercent; draft.destroyBlocks = defaults.destroyBlocks;
            draft.spawnHeight = defaults.spawnHeight; draft.passThroughBlocks.clear(); draft.passThroughBlocks.addAll(defaults.passThroughBlocks);
            draft.blockInput = ""; rebuildWidgets();
        });
        label(Texts.tr("screen.tacz_pubg.zone_settings.pass_through_blocks_air_automatic"), 10, 425, 0xFFFFFFFF);
        label(Texts.tr("screen.tacz_pubg.zone_settings.bombs_fall_through_listed_blocks"), 10, 441, 0xFFAAAAAA);
        edit(10, 459, width - 143, draft.blockInput, value -> draft.blockInput = value, Texts.tr("screen.tacz_pubg.zone_settings.block_id"), FieldRole.BLOCK_ID);
        button(Texts.tr("screen.tacz_pubg.zone_settings.add"), width - 127, 459, 48, () -> addBlock(draft.blockInput));
        button(Texts.tr("screen.tacz_pubg.zone_settings.select_block"), width - 73, 459, 55, this::pickBlock);
        for (int i = 0; i < draft.passThroughBlocks.size(); i++) {
            String id = draft.passThroughBlocks.get(i); int y = 491 + i * 27;
            label(font.plainSubstrByWidth(Texts.resolve(id), width - 88), 10, y + 6, 0xFFE0E0E0);
            button(Texts.tr("screen.tacz_pubg.loot_config.remove"), width - 65, y, 47, () -> { draft.passThroughBlocks.remove(id); rebuildWidgets(); })
                    .setTooltip(Tooltip.create(Texts.component(id)));
        }
        contentHeight = 520 + draft.passThroughBlocks.size() * 27;
    }

    private void addBlock(String id) {
        try { draft.addBlock(id, registeredBlocks); draft.blockInput = ""; rebuildWidgets(); }
        catch (IllegalArgumentException issue) { validation = issue.getMessage(); }
    }

    private void pickBlock() {
        List<LootCatalog.Choice> blocks = BuiltInRegistries.ITEM.stream().filter(item -> item instanceof BlockItem)
                .map(item -> new LootCatalog.Choice("item", BuiltInRegistries.ITEM.getKey(item).toString(), new ItemStack(item))).toList();
        minecraft.setScreen(new ItemPickerScreen(this, blocks, choice -> {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(choice.id()));
            if (item instanceof BlockItem block) addBlock(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
        }, "item", Texts.of("screen.tacz_pubg.zone_settings.select_pass_through_block")));
    }

    private void initHeavyWeapons() {
        label(Texts.tr("screen.tacz_pubg.zone_settings.heavy_weapon_parameters_come_loaded_gun_resources"), 10, 6, 0xFFB5D9FF);
        label(Texts.tr("screen.tacz_pubg.zone_settings.grenade_terrain_blast_strength_creeper_damage_curves_use"), 10, 20, 0xFFDECF9B);
        int y = HEAVY_TOP;
        for (LootCatalog.Choice choice : heavyWeapons) {
            var info = choice.heavyWeapon(); String id = choice.id();
            label(choice.preview().getHoverName().getString(), 32, y + 3, 0xFFFFFFFF);
            label(id, 32, y + 17, 0xFFAAAAAA);
            label(Texts.tr("screen.tacz_pubg.zone_settings.ammo", info.ammoId()), 10, y + 35, 0xFFE0E0E0);
            label(Texts.tr("screen.tacz_pubg.zone_settings.center_direct_damage_hp", number(info.directDamage())), 10, y + 49, 0xFFE0E0E0);
            label(Texts.tr("screen.tacz_pubg.zone_settings.explosion_damage_hp", number(info.explosionDamage())), 10, y + 63, 0xFFE0E0E0);
            label(Texts.tr("screen.tacz_pubg.zone_settings.resource_radius_blocks", number(info.damageRadius())), 10, y + 77, 0xFFE0E0E0);
            label(Texts.tr("screen.tacz_pubg.zone_settings.terrain_blast_strength", number(info.terrainPower())), 10, y + 91, 0xFFE0E0E0);
            boolean destroy = draft.heavyWeaponTerrain.getOrDefault(id, info.nativeDestroyBlocks());
            int half = (width - 34) / 2;
            button(Texts.tr("screen.tacz_pubg.zone_settings.terrain_destruction", (destroy ? Texts.tr("screen.tacz_pubg.common.enabled") : Texts.tr("screen.tacz_pubg.common.disabled"))), 10, y + 111, half, () -> {
                draft.heavyWeaponTerrain.put(id, !destroy); rebuildWidgets();
            });
            button(Texts.tr("screen.tacz_pubg.zone_settings.reset_resource_values"), 16 + half, y + 111, half, () -> {
                draft.heavyWeaponTerrain.remove(id); rebuildWidgets();
            }).setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.zone_settings.resource_default", (info.nativeDestroyBlocks() ? Texts.tr("screen.tacz_pubg.common.enabled") : Texts.tr("screen.tacz_pubg.common.disabled")))));
            label(Texts.tr("screen.tacz_pubg.zone_settings.parameters_terrain", (info.experimentalBalance() ? Texts.tr("screen.tacz_pubg.zone_settings.experimental_balance") : Texts.tr("screen.tacz_pubg.zone_settings.original_resources")), (draft.heavyWeaponTerrain.containsKey(id) ? Texts.tr("screen.tacz_pubg.zone_settings.page_settings") : Texts.tr("screen.tacz_pubg.zone_settings.resource_defaults"))), 10, y + 136, 0xFFDECF9B);
            var profile = HeavyWeaponProfiles.forGun(ResourceLocation.parse(id));
            if (profile != null) {
                String[] raw = draft.heavyWeaponDamageCurves.getOrDefault(id,
                        new String[]{number(profile.damageRadius()), number(profile.curveP()), number(profile.curveQ())});
                field(Texts.tr("screen.tacz_pubg.zone_settings.damage_radius_r_blocks"), y + 153, raw[0], value -> draft.curve(id, profile.damageRadius(), profile.curveP(), profile.curveQ())[0] = value);
                heavySlider(id, 1, "p", raw, profile, y + 180);
                heavySlider(id, 2, "q", raw, profile, y + 207);
                label(Texts.tr("screen.tacz_pubg.zone_settings.d_r_damage_curve_zero_when_r_r"), 10, y + 236, 0xFFB5D9FF);
                button(Texts.tr("screen.tacz_pubg.zone_settings.reset_gun_curve"), 10, y + 288, width - 28, () -> {
                    draft.heavyWeaponDamageCurves.remove(id); rebuildWidgets();
                });
            }
            y += HEAVY_ROW_HEIGHT;
        }
        for (String missing : draft.heavyWeaponTerrain.keySet().stream().filter(id -> heavyWeapons.stream().noneMatch(choice -> choice.id().equals(id))).sorted().toList()) {
            label(Texts.tr("screen.tacz_pubg.zone_settings.waiting_resources", missing), 10, y + 6, 0xFFDECF9B);
            button(Texts.tr("screen.tacz_pubg.zone_settings.remove_gun_settings"), 10, y + 23, width - 28, () -> { draft.heavyWeaponTerrain.remove(missing); rebuildWidgets(); });
            y += 57;
        }
        if (heavyWeapons.isEmpty()) { label(Texts.tr("screen.tacz_pubg.zone_settings.load_heavy_weapon_resources_view_them"), 10, y + 6, 0xFFAAAAAA); y += 30; }
        contentHeight = y + 14;
    }

    private void heavySlider(String id, int index, String title, String[] raw, HeavyWeaponProfiles.Profile profile, int y) {
        double value;
        try { value = Double.parseDouble(raw[index]); } catch (NumberFormatException ignored) { value = 1; }
        var slider = new AbstractSliderButton(10, y, width - 28, 20, Texts.component(title), Math.clamp(value - 1, 0, 1)) {
            @Override protected void updateMessage() { setMessage(Texts.component(title + " = " + number(Math.round((1 + value) * 100) / 100.0) + "   [1–2]")); }
            @Override protected void applyValue() {
                draft.curve(id, profile.damageRadius(), profile.curveP(), profile.curveQ())[index] = number(Math.round((1 + value) * 100) / 100.0);
                updateValidation();
            }
        };
        slider.setMessage(Texts.component(title + " = " + raw[index] + "   [1–2]"));
        slider.setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.zone_settings.drag_use_arrow_keys_steps_affects_only_gun")));
        content.add(new Widget(slider, y)); addWidget(slider);
    }

    @Override public void tick() {
        if (parent instanceof LootConfigScreen root) {
            boolean saving = root.isSavingConfiguration();
            if (pendingSave != null && !saving && root.wasLastSaveSuccessful() && !root.isConfigurationDirty()) {
                baseline = pendingSave; pendingSave = null;
            }
            if (applyButton != null) {
                applyButton.visible = saving || root.isConfigurationDirty() || !draft.values().equals(baseline);
                applyButton.active = !saving && validation.isBlank();
                applyButton.setMessage(Texts.component(saving ? Texts.tr("screen.tacz_pubg.gun_ammo_supply.saving") : Texts.tr("screen.tacz_pubg.configuration.save")));
            }
        }
    }

    private static String number(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }

    private void initColors() {
        button(Texts.tr("screen.tacz_pubg.zone_settings.color_source", (draft.useGlobalVisualStyle ? Texts.tr("screen.tacz_pubg.zone_settings.global_colors") : Texts.tr("screen.tacz_pubg.zone_settings.per_map_colors"))), 10, 5, width - 28, () -> {
            draft.useGlobalVisualStyle = !draft.useGlobalVisualStyle; rebuildWidgets();
        }).setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.zone_settings.global_colors_apply_all_maps_per_map_colors")));
        label(Texts.tr("screen.tacz_pubg.zone_settings.select_global_colors_save_apply"), 10, 33, 0xFFDECF9B);
        int inputWidth = Math.min(110, width / 3), inputX = width - 68 - inputWidth;
        for (int i = 0; i < COLOR_LABELS.length; i++) {
            final int index = i; int y = 54 + i * 31;
            label(COLOR_LABELS[i] + " RGB", 10, y + 6, 0xFFE0E0E0);
            edit(inputX, y, inputWidth, draft.colors[i], value -> draft.colors[index] = value, COLOR_LABELS[i] + " RGB");
            button("", width - 61, y, 43, () -> minecraft.setScreen(new Palette(this,
                    COLOR_LABELS[index], draft.colors[index], value -> { draft.colors[index] = value; updateValidation(); })))
                    .setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.zone_settings.choose_color", COLOR_LABELS[index])));
        }
        for (int i = 0; i < OPACITY_LABELS.length; i++) {
            final int index = i; int y = 404 + i * 31;
            field(OPACITY_LABELS[i] + Texts.tr("screen.tacz_pubg.zone_settings.opacity"), y, draft.opacityPercent[i], value -> draft.opacityPercent[index] = value);
        }
        button(Texts.tr("screen.tacz_pubg.zone_settings.reset_colors"), 10, 568, width - 28, () -> { draft.setStyle(MapVisualStyle.DEFAULT); rebuildWidgets(); });
        label(Texts.tr("screen.tacz_pubg.zone_settings.zone_preview"), 10, 600, 0xFFB5D9FF);
        label(Texts.tr("screen.tacz_pubg.zone_settings.colors_synchronize_save_blue_zone_damage_follows_phase"), 10, 695, 0xFFDECF9B);
        field(Texts.tr("screen.tacz_pubg.zone_settings.loot_black_overlay_layers_n"), 537, draft.lootOverlapBlackLayers, value -> draft.lootOverlapBlackLayers = value);
        contentHeight = 718;
    }

    private enum FieldRole { NORMAL, HEIGHT, RADIUS, BLOCK_ID }

    private void field(String title, int y, String value, Consumer<String> change) {
        field(title, y, value, change, FieldRole.NORMAL);
    }

    private void field(String title, int y, String value, Consumer<String> change, FieldRole role) {
        int fieldWidth = Math.min(110, width / 3);
        label(title, 10, y + 6, 0xFFE0E0E0);
        edit(width - 18 - fieldWidth, y, fieldWidth, value, change, title, role);
    }

    private void label(String text, int x, int y, int color) { labels.add(new Label(text, x, y, color)); }
    private Button button(String text, int x, int y, int size, Runnable action) {
        Button button = ConfigUi.button(Texts.component(text), ignored -> action.run()).bounds(x, y, size, 20).build();
        content.add(new Widget(button, y)); return addWidget(button);
    }
    private void edit(int x, int y, int size, String value, Consumer<String> change, String title) {
        edit(x, y, size, value, change, title, FieldRole.NORMAL);
    }

    private void edit(int x, int y, int size, String value, Consumer<String> change, String title, FieldRole role) {
        EditBox box = ConfigUi.editBox(font, x, y, size, 20, Texts.component(title)); box.setMaxLength(role == FieldRole.BLOCK_ID ? 128 : 80); box.setValue(Texts.resolve(value));
        box.setResponder(text -> { change.accept(text); updateValidation(); });
        box.setTooltip(Tooltip.create(Texts.component(role == FieldRole.HEIGHT
                ? Texts.tr("screen.tacz_pubg.zone_settings.enter_world_height_limit_integer_y_dimension_s") : role == FieldRole.RADIUS
                ? Texts.tr("screen.tacz_pubg.zone_settings.each_projectile_s_radius")
                : title + Texts.tr("screen.tacz_pubg.zone_settings.input_drafts_retained_validated_when_applied"))));
        content.add(new Widget(box, y)); addWidget(box);
    }

    private void updateValidation() {
        try { draft.parse(availableAmmo, registeredBlocks); validation = ""; }
        catch (IllegalArgumentException issue) { validation = issue.getMessage(); }
        if (applyButton != null) applyButton.active = validation.isBlank();
    }
    private void commit() {
        try { ZoneSettings settings = draft.parse(availableAmmo, registeredBlocks); apply.accept(settings);
            if (parent instanceof LootConfigScreen root) { pendingSave = draft.values(); root.saveConfiguration(); }
            else { baseline = draft.values(); minecraft.setScreen(parent); } }
        catch (IllegalArgumentException issue) { validation = issue.getMessage(); updateValidation(); }
    }
    @Override public void onClose() {
        if (!draft.values().equals(baseline)) minecraft.setScreen(new ConfirmScreen(discard -> {
            minecraft.setScreen(discard ? parent : this);
        }, Texts.of("screen.tacz_pubg.zone_settings.cancel_page_s_changes"), Texts.of("screen.tacz_pubg.zone_settings.discard_page_return_general_keep_other_drafts"),
                Texts.of("screen.tacz_pubg.gun_ammo_supply.discard_page_changes"), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
        else minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen() { return false; }

    private int bottom() { return height - 56; }
    private boolean viewport(double x, double y) { return x >= 8 && x < width - 8 && y >= TOP && y < bottom(); }
    private void position() {
        scroll = Math.clamp(scroll, 0, Math.max(0, contentHeight - (bottom() - TOP)));
        for (Widget child : content) {
            int y = TOP + child.y() - (int) scroll; var widget = child.widget(); widget.setY(y);
            widget.visible = y < bottom() && y + widget.getHeight() > TOP;
            boolean complete = y >= TOP && y + widget.getHeight() <= bottom();
            if (widget instanceof EditBox) widget.active = complete;
            // Picker availability is independently limited by the loaded server catalog.
            else if (child.y() != 40 || page != 0) widget.active = complete;
            else widget.active = complete && !ammunition.isEmpty();
            if (!complete && getFocused() == widget) setFocused(null);
        }
    }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (viewport(x, y)) { scroll -= dy * 28; position(); return true; }
        return false;
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && x >= width - 7 && x < width - 2 && y >= TOP && y < bottom()
                && contentHeight > bottom() - TOP) {
            scrollDragging = true; dragScroll(y); return true;
        }
        if (viewport(x, y)) return super.mouseClicked(x, y, button);
        for (var child : children()) if (child instanceof AbstractWidget widget
                && content.stream().noneMatch(entry -> entry.widget() == widget) && widget.mouseClicked(x, y, button)) {
            setFocused(widget); if (button == 0) setDragging(true); return true;
        }
        setFocused(null); return false;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (scrollDragging) { dragScroll(y); return true; }
        return viewport(x, y) && super.mouseDragged(x, y, button, dx, dy);
    }
    private void dragScroll(double y) {
        scroll = (y - TOP) / Math.max(1, bottom() - TOP) * Math.max(0, contentHeight - (bottom() - TOP)); position();
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        scrollDragging = false; return super.mouseReleased(x, y, button);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, Texts.resolve(title), width / 2, 12, 0xFFFFFFFF);
        graphics.enableScissor(8, TOP, width - 8, bottom()); graphics.fill(8, TOP, width - 8, bottom(), 0xB0202020);
        graphics.pose().pushPose(); graphics.pose().translate(0, TOP - (int) scroll, 0);
        for (Label label : labels) graphics.drawString(font, Texts.resolve(font.plainSubstrByWidth(Texts.resolve(label.text()), width - label.x() - 18)), label.x(), label.y(), label.color());
        if (page == 1) renderColors(graphics); else if (page == 0) renderFalloff(graphics);
        else for (int i = 0; i < heavyWeapons.size(); i++) {
            var choice = heavyWeapons.get(i); int rowY = HEAVY_TOP + i * HEAVY_ROW_HEIGHT;
            graphics.renderItem(choice.preview(), 10, rowY + 2);
            var profile = HeavyWeaponProfiles.forGun(ResourceLocation.parse(choice.id()));
            if (profile != null) {
                String[] raw = draft.heavyWeaponDamageCurves.getOrDefault(choice.id(), new String[]{number(profile.damageRadius()), number(profile.curveP()), number(profile.curveQ())});
                try {
                    var curve = new com.example.examplemod.zones.heavy.HeavyWeaponDamageCurve(Double.parseDouble(raw[0]), Double.parseDouble(raw[1]), Double.parseDouble(raw[2]));
                    int span = Math.max(1, (width - 36) / 12);
                    for (int point = 0; point < 12; point++) {
                        double factor = curve.multiplierAt(curve.damageRadius() * point / 11.0);
                        graphics.fill(12 + point * span, rowY + 281 - (int) (28 * factor), 12 + point * span + span - 2, rowY + 281, 0xFFE87942);
                    }
                } catch (IllegalArgumentException ignored) { }
            }
        }
        graphics.pose().popPose();
        for (Widget child : content) child.widget().render(graphics, viewport(mouseX, mouseY) ? mouseX : -1000,
                viewport(mouseX, mouseY) ? mouseY : -1000, partialTick);
        if (page == 1) {
            graphics.pose().pushPose(); graphics.pose().translate(0, TOP - (int) scroll, 0);
            renderSwatches(graphics); graphics.pose().popPose();
        }
        graphics.disableScissor();
        if (contentHeight > bottom() - TOP) {
            int height = bottom() - TOP, thumb = Math.max(12, height * height / contentHeight);
            int y = TOP + (int) ((height - thumb) * scroll / Math.max(1, contentHeight - height));
            graphics.fill(width - 6, TOP, width - 3, bottom(), 0xFF242424); graphics.fill(width - 6, y, width - 3, y + thumb, 0xFFAAAAAA);
        }
        String status = validation.isBlank() ? Texts.tr("screen.tacz_pubg.zone_settings.save_after_editing_await_server_confirmation") : validation;
        graphics.drawString(font, Texts.resolve(font.plainSubstrByWidth(Texts.resolve(status), width - 20)), 10, height - 46,
                validation.isBlank() ? 0xFFB0E0B0 : 0xFFFF8888);
        if (mouseY >= height - 50 && mouseY < height - 31) graphics.renderTooltip(font,
                font.split(Texts.component(status), width - 24), mouseX, mouseY);
    }

    private void renderFalloff(GuiGraphics graphics) {
        try {
            var bomb = draft.parse(availableAmmo, registeredBlocks).bombing(); int count = 9, span = (width - 36) / count;
            for (int i = 0; i < count; i++) {
                double distance = bomb.damageRadius() * i / (count - 1.0), damage = bomb.damageAt(distance);
                double factor = bomb.damage() == 0 ? 0 : damage / bomb.damage();
                int x = 12 + i * span, bar = (int) Math.round(40 * factor);
                graphics.fill(x, 341 - bar, x + span - 4, 341, 0xFFE87942);
                graphics.drawString(font, Texts.resolve(Math.round(damage) + ""), x, 346, 0xFFE0E0E0);
            }
        } catch (IllegalArgumentException ignored) { graphics.drawString(font, Texts.of("screen.tacz_pubg.zone_settings.enter_valid_values_view_diagram").getString(), 10, 309, 0xFFFF8888); }
    }

    private void renderSwatches(GuiGraphics graphics) {
        for (int i = 0; i < COLOR_LABELS.length; i++) {
            int rgb;
            try { rgb = MapVisualStyle.parseHex(draft.colors[i]); }
            catch (IllegalArgumentException ignored) { rgb = 0x491A25; }
            graphics.fill(width - 61 + 3, 54 + i * 31 + 3, width - 61 + 40, 54 + i * 31 + 17, 0xFF000000 | rgb);
        }
    }
    private void renderColors(GuiGraphics graphics) {
        try {
            var style = draft.style(); int x = 12, y = 619, w = width - 36;
            graphics.fill(x, y, x + w, y + 40, 0xFF314437);
            graphics.fill(x, y, x + w / 3, y + 40, MapVisualStyle.argb(style.blueZoneRgb(), style.blueMapOpacity()));
            graphics.fill(x + w * 2 / 3, y + 5, x + w - 8, y + 30, MapVisualStyle.argb(style.bombingZoneRgb(), style.bombingMapOpacity()));
            graphics.fill(x, y, x + w, y + 1, 0xFF000000 | style.mapBorderRgb());
            graphics.fill(x, y + 39, x + w, y + 40, 0xFF000000 | style.mapBorderRgb());
            graphics.fill(x + w / 3, y, x + w / 3 + 1, y + 40, 0xFF000000 | style.safeZoneRgb());
            graphics.fill(x + 12, y + 31, x + w - 12, y + 33, 0xFF000000 | style.flightPathRgb());
            graphics.fill(x + w / 3 + 12, y + 4, x + w / 3 + 14, y + 23, 0xFF000000 | style.lightningRgb());
            graphics.drawString(font, Texts.of("screen.tacz_pubg.zone_settings.blue_area").getString(), x + 5, y + 7, 0xFFFFFFFF);
            graphics.drawString(font, Texts.of("screen.tacz_pubg.zone_settings.bombardment_zone").getString(), x + w * 2 / 3 + 4, y + 12, 0xFFFFFFFF);
            int half = (w - 6) / 2;
            graphics.fill(x, y + 47, x + half, y + 65, 0xFF1B2432);
            graphics.fill(x, y + 47, x + half, y + 65, MapVisualStyle.argb(style.blueZoneRgb(), style.blueWorldOpacity()));
            graphics.fill(x + half + 6, y + 47, x + w, y + 65, 0xFF1B2432);
            graphics.fill(x + half + 6, y + 47, x + w, y + 65, MapVisualStyle.argb(style.blueZoneRgb(), style.blueScreenOpacity()));
            graphics.drawString(font, Texts.of("screen.tacz_pubg.zone_settings.world_blue_zone").getString(), x + 5, y + 52, 0xFFFFFFFF);
            graphics.drawString(font, Texts.of("screen.tacz_pubg.zone_settings.screen_edge").getString(), x + half + 11, y + 52, 0xFFFFFFFF);
        } catch (IllegalArgumentException ignored) { graphics.drawString(font, Texts.of("screen.tacz_pubg.zone_settings.enter_valid_colors_view_preview").getString(), 10, 635, 0xFFFF8888); }
    }

    private static final class Palette extends Screen {
        private static final int[] RGB = {0xFFFFFF,0xEAF5FF,0x93A9B5,0x333B49,0x101820,0x000000,
                0x1549D8,0x003CAA,0x008BFF,0x46C8FF,0x7659E8,0xC16DFF,0xEF3038,0xB51324,0xFF7B37,
                0xFFDC65,0xF7FFFF,0xF5AAC7,0x1B995A,0x54D985,0x23B9B2,0x9BE4B4,0xA9744F,0xD8BD99};
        private final Screen parent; private final Consumer<String> edit; private String raw;
        private int left, panel, cell;
        Palette(Screen parent, String label, String raw, Consumer<String> edit) {
            super(Texts.component(label + Texts.tr("screen.tacz_pubg.zone_settings.palette"))); this.parent = parent; this.raw = raw; this.edit = edit;
        }
        @Override protected void init() {
            panel = Math.min(310, width - 24); left = (width - panel) / 2; cell = (panel - 20) / 6;
            EditBox input = addRenderableWidget(ConfigUi.editBox(font, left, 35, panel, 20, Texts.of("screen.tacz_pubg.zone_settings.six_digit_rgb")));
            input.setMaxLength(80); input.setValue(Texts.resolve(raw)); input.setResponder(value -> { raw = value; edit.accept(value); });
            for (int i = 0; i < RGB.length; i++) {
                int rgb = RGB[i], x = left + i % 6 * (cell + 4), y = 85 + i / 6 * 25;
                addRenderableWidget(ConfigUi.button(Component.empty(), ignored -> input.setValue(Texts.resolve(MapVisualStyle.hex(rgb))))
                        .bounds(x, y, cell, 21).tooltip(Tooltip.create(Texts.component("#" + MapVisualStyle.hex(rgb)))).build());
            }
            addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.zone_settings.return_page_draft"), ignored -> onClose()).bounds(left, height - 26, panel, 20).build());
        }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick); graphics.drawCenteredString(font, Texts.resolve(title), width / 2, 12, 0xFFFFFFFF);
            String status;
            try { int rgb = MapVisualStyle.parseHex(raw); status = "#" + MapVisualStyle.hex(rgb); graphics.fill(left + panel - 42, 61, left + panel, 76, 0xFF000000 | rgb); }
            catch (IllegalArgumentException ignored) { status = Texts.tr("screen.tacz_pubg.zone_settings.enter_six_digit_rgb_input_retained"); }
            graphics.drawString(font, Texts.resolve(status), left, 64, 0xFFB5D9FF);
            for (int i = 0; i < RGB.length; i++) {
                int x = left + i % 6 * (cell + 4), y = 85 + i / 6 * 25;
                graphics.fill(x + 3, y + 3, x + cell - 3, y + 18, 0xFF000000 | RGB[i]);
            }
        }
        @Override public void onClose() { minecraft.setScreen(parent); }
        @Override public boolean isPauseScreen() { return false; }
    }
}
