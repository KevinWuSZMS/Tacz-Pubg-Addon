package com.example.examplemod.lootconfig.client;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.lootconfig.AttachmentLootPolicy;
import com.example.examplemod.lootconfig.CatalogSearch;
import com.example.examplemod.lootconfig.LootCatalog;
import com.example.examplemod.lootconfig.LootCategory;
import com.example.examplemod.lootconfig.LootTier;
import com.example.examplemod.lootconfig.LootConfigModel.TierProfile;
import com.example.examplemod.lootconfig.GunClass;
import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;
import com.example.examplemod.lootconfig.LootConfigModel.General;
import com.example.examplemod.lootconfig.LootConfigModel.GunAmmoSupply;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettingCategory;
import com.example.examplemod.lootconfig.GunSelection;
import com.example.examplemod.lootconfig.LootConfigPayloads;
import com.example.examplemod.lootconfig.TwoStageLootSampler;
import com.example.examplemod.spawn.client.SpawnRegionConfigClient;
import com.example.examplemod.zones.client.MapZoneConfigClient;
import com.example.examplemod.zones.ZoneSettings;
import com.example.examplemod.operatorpermissions.OperatorPermissions;
import com.example.examplemod.zones.match.client.MatchConfigClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Native four-tab authoring shell; only server acknowledgement commits the immutable document. */
public final class LootConfigScreen extends Screen {
    private static final List<GunSettingCategory> EXPERIMENT_ORDER = List.of(GunSettingCategory.BALANCE,
            GunSettingCategory.SCOPES, GunSettingCategory.AMMO, GunSettingCategory.MAGAZINE,
            GunSettingCategory.MUZZLE, GunSettingCategory.GRIP, GunSettingCategory.STOCK, GunSettingCategory.LASER);
    private Document savedDocument, draft, submittedDocument;
    private List<String> submittedFormValues;
    private long revision;
    private LootTier activeTier = LootTier.HIGH;
    private Button saveConfiguration;
    private boolean lastSaveSuccessful;
    private List<LootCatalog.Choice> catalog;
    private final Map<String, LootCatalog.Choice> choices = new HashMap<>();
    private Document weightViewDocument, probabilityBaseDocument;
    private final Map<String, Double> effectiveWeights = new HashMap<>();
    private final Map<String, Entry> effectiveEntries = new HashMap<>();
    private TwoStageLootSampler probabilityView;
    private LootConfigPayloads.Snapshot conflict;
    private GeneralForm generalForm;
    private CategoryForm categoryForm;
    private GunClassForm gunClassForm;
    private ItemForm itemForm;
    private GunAmmoSupplyScreen.Draft ammoForm;
    private List<String> ammoBaseline;
    private String ammoKey;
    private final List<PageWidget> pageWidgets = new ArrayList<>();
    private final List<AbstractWidget> fixedWidgets = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private ItemList itemList;
    private int selected, pendingSelection = -1, right, rightWidth, bodyY, contentHeight, view;
    private boolean saving, error, advanced, gunClassesExpanded, scrollDragging;
    private LootCategory poolFilter = LootCategory.GUN;
    private String query = "", message;
    private double scroll;
    private final double[] pageScroll = new double[2];
    private Layout layout;
    static final int PROBABILITY_TOTAL_Y = 24;
    static final int POOL_CONTROLS_Y = 44;
    static final int CATEGORY_GRID_Y = 70;

    private record PageWidget(AbstractWidget widget, int logicalY, boolean enabled) {}
    private record Line(String text, int x, int y, int maxWidth, int color) {}
    /** All coordinates are logical; scrolling is a translation clipped to one viewport. */
    record Layout(int width, int height, int columns, int cellWidth, int generalY, int generalColumns,
                  int advancedY, int bodyY, int leftWidth, int right, int rightWidth, boolean inlineAmmo) {
        int top() { return ConfigUi.CONTENT_TOP; }
        int bottom() { return height - 46; }
        int viewportHeight() { return bottom() - top(); }
        boolean contains(double x, double y) { return x >= 8 && x < width - 8 && y >= top() && y < bottom(); }
        boolean intersects(int logicalY, int widgetHeight, double scroll) {
            int y = top() + logicalY - (int) scroll;
            return y < bottom() && y + widgetHeight > top();
        }
        boolean fullyVisible(int logicalY, int widgetHeight, double scroll) {
            int y = top() + logicalY - (int) scroll;
            return y >= top() && y + widgetHeight <= bottom();
        }
    }
    static Layout layoutFor(int width, int height, boolean advanced) {
        return layoutFor(width, height, advanced, false);
    }
    static Layout layoutFor(int width, int height, boolean advanced, boolean gunClassesExpanded) {
        int columns = width >= 600 ? 10 : 5;
        int cellWidth = (width - 24 - (columns - 1) * 4) / columns;
        int rows = (LootCategory.values().length + columns - 1) / columns;
        int generalY = CATEGORY_GRID_Y + rows * 47 + 7;
        if (gunClassesExpanded) generalY += gunClassHeight(width);
        int generalColumns = width >= 600 ? 3 : 1;
        int advancedY = generalY + 16 + ((3 + generalColumns - 1) / generalColumns) * 26;
        int advancedHeight = advanced ? 14 + (width >= 600 ? 4 : 7) * 29 + 28 : 0;
        int bodyY = advancedY + 30 + advancedHeight;
        int leftWidth = width >= 600 ? Math.min(215, width / 3) : Math.max(104, width / 3);
        int right = 8 + leftWidth + 12, rightWidth = width - right - 14;
        return new Layout(width, height, columns, cellWidth, generalY, generalColumns, advancedY,
                bodyY, leftWidth, right, rightWidth, width >= 600);
    }
    private static int gunClassHeight(int width) { return 54 + (width >= 600 ? 1 : 3) * 43; }

    public LootConfigScreen(long revision, Document document, List<LootCatalog.Choice> catalog, String message) {
        super(Texts.of("screen.tacz_pubg.loot_config.pubg_configuration"));
        ConfigUi.begin(this);
        this.revision = revision;
        savedDocument = document; draft = viewTier(document, activeTier);
        generalForm = new GeneralForm(document.general());
        categoryForm = new CategoryForm(draft.categoryProbabilities());
        setCatalog(catalog);
        gunClassForm = new GunClassForm(document.general().gunSelection(), gunClassCounts());
        this.message = message == null ? "" : message;
    }

    private void setCatalog(List<LootCatalog.Choice> catalog) {
        this.catalog = List.copyOf(catalog);
        choices.clear();
        for (var choice : catalog) choices.put(choice.kind() + ":" + choice.id(), choice);
        if (gunClassForm != null && gunClassForm.automatic)
            gunClassForm = new GunClassForm(GunSelection.defaults(), gunClassCounts());
        weightViewDocument = null;
    }

    @Override protected void init() {
        pageWidgets.clear(); fixedWidgets.clear(); lines.clear(); itemList = null;
        layout = layoutFor(width, height, advanced, gunClassesExpanded);
        for (Button tab : ConfigUi.tabs(width, view, this::openConfigTab)) fixed(tab);
        saveConfiguration = fixedButton(saving ? Texts.tr("screen.tacz_pubg.gun_ammo_supply.saving") : conflict != null ? Texts.tr("screen.tacz_pubg.loot_config.check_version") : Texts.tr("screen.tacz_pubg.configuration.save"),
                width - 108, height - 26, 100, () -> { if (conflict != null) rebaseDraft(); else save(); });
        fixedButton(Texts.tr("screen.tacz_pubg.loot_config.close"), 8, height - 26, 70, this::onClose);
        saveConfiguration.visible = hasUnsavedChanges() || saving;
        if (view == 0) initProbability(); else initExperiments();
        for (int index = 0; index < pageWidgets.size(); index++) {
            PageWidget page = pageWidgets.get(index);
            pageWidgets.set(index, new PageWidget(page.widget(), page.logicalY(), page.enabled() && page.widget().active));
        }
        positionWidgets();
        if (saving) fixedWidgets.forEach(widget -> widget.active = false);
    }

    @Override public void resize(Minecraft minecraft, int width, int height) {
        rememberScroll();
        super.resize(minecraft, width, height);
    }

    /** Shared navigation from map/game pages; child screens keep their independent drafts. */
    public boolean openConfigTab(int index) {
        if (!canNavigate(index, saving)) return false;
        // An incomplete numeric field is a retained draft, not a lock on the other three tabs.
        applyForms();
        rememberScroll();
        if (index <= 1) {
            view = index;
            minecraft.setScreen(this);
        } else {
            Screen remembered = ConfigUi.remembered(this, index);
            if (remembered != null) minecraft.setScreen(remembered);
            else if (index == 2) MapZoneConfigClient.open(this);
            else MatchConfigClient.open(this);
        }
        return true;
    }
    static boolean canNavigate(int index, boolean saving) { return index >= 0 && index <= 3 && !saving; }

    private void initProbability() {
        int tierWidth = (width - 36) / 5;
        for (int i = 0; i < LootTier.values().length; i++) {
            LootTier tier = LootTier.values()[i];
            Button tierButton = button((tier == activeTier ? "✓ " : "") + tier.label() + Texts.tr("screen.tacz_pubg.loot_config.loot"), 10 + i * (tierWidth + 4), 0, tierWidth, () -> chooseTier(tier));
            tierButton.setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.loot_config.edit_tier_s_items_weights_quantities_category_probabilities", tier.quantityMultiplier())));
        }
        button((gunClassesExpanded ? "▼ " : "▶ ") + Texts.tr("screen.tacz_pubg.loot_config.gun_class_shares"), width - 140, POOL_CONTROLS_Y, 58, () -> {
            rememberScroll(); gunClassesExpanded = !gunClassesExpanded; rebuildWidgets();
        }).setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.loot_config.choose_gun_category_then_gun_class_then_gun")));
        button(Texts.tr("screen.tacz_pubg.loot_config.all_items"), width - 76, POOL_CONTROLS_Y, 62, () -> choosePool(null));
        line(Texts.tr("screen.tacz_pubg.loot_config.category_share"), 10, POOL_CONTROLS_Y + 6, Math.min(110, width - 158), 0xFFFFFFFF);
        for (int index = 0; index < LootCategory.values().length; index++) {
            LootCategory category = LootCategory.values()[index];
            int x = 10 + (index % layout.columns()) * (layout.cellWidth() + 4);
            int y = CATEGORY_GRID_Y + (index / layout.columns()) * 47;
            Button select = button((poolFilter == category ? "● " : "") + category.label(), x, y,
                    layout.cellWidth(), () -> choosePool(category));
            select.setTooltip(Tooltip.create(Texts.component(category == LootCategory.GUN
                    ? Texts.tr("screen.tacz_pubg.loot_config.choose_gun_class_by_share_then_choose_gun")
                    : Texts.tr("screen.tacz_pubg.loot_config.choose_category_items_below_selected_by_weight_within", category.label()))));
            edit(x, y + 22, layout.cellWidth() - 8, categoryForm.values.get(category.id()),
                    value -> categoryForm.values.put(category.id(), value), 24, category.label() + Texts.tr("screen.tacz_pubg.loot_config.share"));
        }
        if (gunClassesExpanded) initGunClasses();
        line(Texts.tr("screen.tacz_pubg.loot_config.general_spawn_probability"), 10, layout.generalY(), width - 24, 0xFFDDDDBB);
        String[] commonLabels = {Texts.tr("screen.tacz_pubg.loot_config.surface_participation"), Texts.tr("screen.tacz_pubg.loot_config.companion_attachments"), Texts.tr("screen.tacz_pubg.loot_config.distinct_attachment_types_b")};
        String[] values = {generalForm.chance, generalForm.attachmentChance, generalForm.attachmentKinds};
        for (int index = 0; index < 3; index++) {
            int column = index % layout.generalColumns(), row = index / layout.generalColumns();
            int span = (width - 24) / layout.generalColumns(), x = 10 + column * span;
            int y = layout.generalY() + 16 + row * 26;
            int fieldWidth = Math.min(70, span / 3);
            line(commonLabels[index], x, y + 6, span - fieldWidth - 10, 0xFFE0E0E0);
            final int field = index;
            EditBox box = edit(x + span - fieldWidth - 6, y, fieldWidth, values[index], value -> {
                if (field == 0) generalForm.chance = value;
                else if (field == 1) generalForm.attachmentChance = value;
                else generalForm.attachmentKinds = value;
            }, 24, commonLabels[index]);
            box.setTooltip(Tooltip.create(Texts.component(index == 0 ? Texts.tr("screen.tacz_pubg.loot_config.whether_eligible_surface_participates_spawning")
                    : Texts.tr("screen.tacz_pubg.loot_config.corresponding_attachment_experiments_enabled_supply_compatible_dedicated_muzzles"))));
        }
        int settingsWidth = (width - 40) / 3;
        button((advanced ? "▼ " : "▶ ") + Texts.tr("screen.tacz_pubg.loot_config.quantity_density_spacing"), 10, layout.advancedY(), settingsWidth, () -> {
            rememberScroll(); advanced = !advanced; rebuildWidgets();
        });
        button(Texts.tr("screen.tacz_pubg.loot_config.bombardment_colors_heavy_weapons"), 16 + settingsWidth, layout.advancedY(), settingsWidth, this::editZoneSettings)
                .setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.loot_config.bombardment_properties_pass_through_blocks_zone_colors_per")));
        button(Texts.tr("screen.tacz_pubg.throwable_settings.title"), 22 + 2 * settingsWidth, layout.advancedY(), settingsWidth, () -> {
            if (!saving) { rememberScroll(); minecraft.setScreen(new com.example.examplemod.throwables.client.ThrowableSettingsScreen(this)); }
        });
        if (advanced) initAdvanced();
        initItems();
    }

    private Map<GunClass, Integer> gunClassCounts() {
        Map<GunClass, Integer> counts = new java.util.EnumMap<>(GunClass.class);
        for (GunClass type : GunClass.values()) counts.put(type, 0);
        for (LootCatalog.Choice choice : catalog) if (choice.kind().equals("gun"))
            counts.merge(GunClass.byId(choice.gunClass()), 1, Integer::sum);
        return Map.copyOf(counts);
    }

    private void initGunClasses() {
        int y = layout.generalY() - gunClassHeight(width), columns = width >= 600 ? 9 : 3;
        int span = (width - 24) / columns;
        Map<GunClass, Integer> counts = gunClassCounts();
        line(Texts.tr("screen.tacz_pubg.loot_config.gun_class_share"), 10, y + 6, 110, 0xFFFFFFFF);
        button(Texts.tr("screen.tacz_pubg.loot_config.reset_by_gun_count"), width - 142, y, 124, () -> {
            gunClassForm = new GunClassForm(GunSelection.defaults(), gunClassCounts());
            weightViewDocument = null; rememberScroll(); rebuildWidgets();
        });
        for (int i = 0; i < GunClass.values().length; i++) {
            GunClass type = GunClass.values()[i]; int x = 10 + i % columns * span, row = y + 30 + i / columns * 43;
            line(type.label(), x, row, span - 6, 0xFFE0E0E0);
            edit(x, row + 14, span - 8, gunClassForm.percentages.get(type.id()), value -> {
                gunClassForm.percentages.put(type.id(), value); gunClassForm.automatic = false;
            }, 24, type.label() + Texts.tr("screen.tacz_pubg.loot_config.gun_class_share")).setTooltip(Tooltip.create(Texts.component(type.label()
                    + Texts.tr("screen.tacz_pubg.loot_config.loaded") + counts.get(type) + Texts.tr("screen.tacz_pubg.loot_config.gun_models_guns_selected_by_their_base_weights"))));
        }
    }

    private void editZoneSettings() {
        if (saving) return;
        rememberScroll();
        // This independent child does not parse or discard incomplete probability/ammo fields.
        minecraft.setScreen(new ZoneSettingsScreen(this, generalForm.zoneSettings, catalog, settings -> {
            generalForm.zoneSettings = settings;
            showMessage(Texts.tr("screen.tacz_pubg.loot_config.bombardment_colors_heavy_weapon_settings_draft_click_save"), false);
            rebuildWidgets();
        }));
    }

    private void editOperatorPermissions() {
        if (saving) return;
        rememberScroll();
        minecraft.setScreen(new OperatorPermissionsScreen(this, generalForm.operatorPermissions, permissions -> {
            generalForm.operatorPermissions = permissions;
            showMessage(Texts.tr("screen.tacz_pubg.loot_config.op_permissions_draft_awaiting_server_confirmation"), false);
        }));
    }

    private void initAdvanced() {
        String[] labels = {Texts.tr("screen.tacz_pubg.loot_config.density_items_m"), Texts.tr("screen.tacz_pubg.loot_config.minimum_items_per_surface"), Texts.tr("screen.tacz_pubg.loot_config.item_limit_unlimited"), Texts.tr("screen.tacz_pubg.loot_config.surface_y_tolerance"), Texts.tr("screen.tacz_pubg.loot_config.main_item_spacing"), Texts.tr("screen.tacz_pubg.loot_config.minimum_ammo_radius"), Texts.tr("screen.tacz_pubg.loot_config.maximum_ammo_radius")};
        String[] values = {generalForm.density, generalForm.minimum, generalForm.maximum, generalForm.yTolerance,
                generalForm.spacing, generalForm.radiusMin, generalForm.radiusMax};
        int columns = width >= 600 ? 2 : 1, span = (width - 24) / columns;
        int start = layout.advancedY() + 30;
        for (int index = 0; index < labels.length; index++) {
            int x = 10 + (index % columns) * span, y = start + (index / columns) * 29;
            int fieldWidth = Math.min(100, span / 3);
            line(labels[index], x, y + 6, span - fieldWidth - 12, 0xFFCCCCCC);
            final int field = index;
            edit(x + span - fieldWidth - 8, y, fieldWidth, values[index], value -> {
                switch (field) {
                    case 0 -> generalForm.density = value; case 1 -> generalForm.minimum = value;
                    case 2 -> generalForm.maximum = value; case 3 -> generalForm.yTolerance = value;
                    case 4 -> generalForm.spacing = value; case 5 -> generalForm.radiusMin = value;
                    case 6 -> generalForm.radiusMax = value; default -> throw new IllegalStateException();
                }
            }, 24, labels[index]);
        }
        int bottom = start + ((labels.length + columns - 1) / columns) * 29;
        button(Texts.tr("screen.tacz_pubg.loot_config.loot_region_multiplier"), 10, bottom, (width - 32) / 2, () -> {
            if (!saving && applyForms()) SpawnRegionConfigClient.open(this, draft.general().lootDensity());
        });
        button(Texts.tr("screen.tacz_pubg.loot_config.player_target_visibility"), 16 + (width - 32) / 2, bottom, (width - 32) / 2, () -> {
            if (!saving && applyForms()) minecraft.setScreen(new com.example.examplemod.visibility.client.VisibilityConfigScreen(this));
        });
    }

    private void initItems() {
        selected = Math.clamp(selected, 0, Math.max(0, draft.items().size() - 1));
        bodyY = layout.bodyY(); right = layout.right(); rightWidth = layout.rightWidth();
        line((poolFilter == null ? Texts.tr("screen.tacz_pubg.loot_config.all_items") : poolFilter.label()) + Texts.tr("screen.tacz_pubg.loot_config.pool_weight"), 10, bodyY, layout.leftWidth(), 0xFFFFFFFF);
        EditBox search = edit(10, bodyY + 16, layout.leftWidth(), query, value -> {
            query = value;
            if (itemList != null) { itemList.refill(); itemList.setScrollAmount(0); }
        }, 128, Texts.tr("screen.tacz_pubg.loot_config.search_name_id"));
        search.setHint(Texts.of("screen.tacz_pubg.loot_config.search_name_id.init_items"));
        itemList = pageWidget(new ItemList(layout.leftWidth(), 166, 0), bodyY + 42, true);
        itemList.setX(10); itemList.refill(); itemList.setScrollAmount(scroll);
        button(Texts.tr("screen.tacz_pubg.loot_config.add_select_item"), 10, bodyY + 214, layout.leftWidth(), this::pickItem);
        if (draft.items().isEmpty()) { itemForm = null; contentHeight = bodyY + 245; return; }
        Entry entry = draft.items().get(selected);
        if (poolFilter != null && categoryOf(entry) != poolFilter) {
            clearItemForms(); line(Texts.tr("screen.tacz_pubg.loot_config.click_add_configure_category"), right, bodyY + 6, rightWidth, 0xFFAAAAAA);
            contentHeight = bodyY + 245; return;
        }
        if (itemForm == null || !itemForm.original.key().equals(entry.key())) itemForm = new ItemForm(entry);
        ensureAmmoForm(entry);
        int fieldWidth = Math.min(105, Math.max(54, rightWidth / 3));
        int fieldX = right + rightWidth - fieldWidth;
        edit(fieldX, bodyY + 60, fieldWidth, itemForm.weight, value -> itemForm.weight = value, 24, Texts.tr("screen.tacz_pubg.loot_config.base_weight"));
        line(Texts.tr("screen.tacz_pubg.loot_config.base_weight"), right, bodyY + 66, rightWidth - fieldWidth - 8, 0xFFE0E0E0);
        button(Texts.tr("screen.tacz_pubg.loot_config.set_weight_zero"), right, bodyY + 86, Math.min(106, rightWidth), () -> {
            itemForm.weight = "0"; rememberScroll(); rebuildWidgets();
        });
        int detailBottom;
        if (entry.kind().equals("gun")) {
            if (layout.inlineAmmo() && ammoForm != null) detailBottom = initInlineAmmo(entry, bodyY + 116);
            else {
                Button supply = button(Texts.tr("screen.tacz_pubg.loot_config.companion_ammo_choose_one_three"), right, bodyY + 116, rightWidth, this::editAmmoSupply);
                supply.active = ammoForm != null;
                supply.setTooltip(Tooltip.create(Texts.component(ammoSupplyDetails(entry))));
                line(Texts.tr("screen.tacz_pubg.loot_config.configure_ammo_groups_probabilities_per_gun"), right, bodyY + 143, rightWidth, 0xFFB5D9FF);
                detailBottom = bodyY + 165;
            }
        } else {
            line(Texts.tr("screen.tacz_pubg.loot_config.minimum_quantity"), right, bodyY + 124, rightWidth - fieldWidth - 8, 0xFFE0E0E0);
            edit(fieldX, bodyY + 118, fieldWidth, itemForm.minimum, value -> itemForm.minimum = value, 10, Texts.tr("screen.tacz_pubg.loot_config.minimum_quantity"));
            line(Texts.tr("screen.tacz_pubg.loot_config.maximum_quantity"), right, bodyY + 150, rightWidth - fieldWidth - 8, 0xFFE0E0E0);
            edit(fieldX, bodyY + 144, fieldWidth, itemForm.maximum, value -> itemForm.maximum = value, 10, Texts.tr("screen.tacz_pubg.loot_config.maximum_quantity"));
            detailBottom = bodyY + 176;
        }
        button(entry.kind().equals("item") ? Texts.tr("screen.tacz_pubg.loot_config.remove_entry") : Texts.tr("screen.tacz_pubg.loot_config.reset_quantity"), right, detailBottom, rightWidth, () -> {
            if (entry.kind().equals("item")) removeItem();
            else { itemForm.minimum = itemForm.maximum = "1"; rememberScroll(); rebuildWidgets(); }
        });
        double capacity = draft.gunSettings().inventory().capacityOverrides().getOrDefault(entry.key(),
                com.example.examplemod.backpack.BackpackWeights.unit(preview(entry)) / 1000.0);
        button(Texts.tr("screen.tacz_pubg.inventory.capacity_button", String.format(Locale.ROOT, "%.4g", capacity)),
                right, detailBottom + 27, rightWidth, this::editItemCapacity);
        contentHeight = Math.max(bodyY + 247, detailBottom + 61);
    }

    private void ensureAmmoForm(Entry entry) {
        if (!entry.kind().equals("gun")) { clearAmmoForm(); return; }
        if (entry.key().equals(ammoKey)) return;
        GunAmmoSupply supply = entry.ammoSupply() == null ? ammoDefaults(entry) : entry.ammoSupply();
        ammoKey = entry.key();
        ammoForm = supply == null ? null : new GunAmmoSupplyScreen.Draft(supply, entry.ammoSupply() == null);
        ammoBaseline = ammoForm == null ? List.of() : ammoForm.values();
    }
    private void clearAmmoForm() { ammoForm = null; ammoKey = null; ammoBaseline = null; }
    private void clearItemForms() { itemForm = null; clearAmmoForm(); }
    private GunAmmoSupply ammoDefaults(Entry entry) {
        var choice = choices.get(entry.key()); return choice == null ? null : choice.ammoDefaults();
    }

    private int initInlineAmmo(Entry entry, int y) {
        line(Texts.tr("screen.tacz_pubg.loot_config.companion_ammo_choose_one_three.init_inline_ammo"), right, y + 5, rightWidth, 0xFFFFFFFF);
        line(Texts.tr("screen.tacz_pubg.gun_ammo_supply.rounds_per_group"), right, y + 31, 75, 0xFFCCCCCC);
        edit(right + 80, y + 25, 62, ammoForm.rounds, value -> { ammoForm.rounds = value; ammoForm.dynamicDefaults = false; }, 10, Texts.tr("screen.tacz_pubg.gun_ammo_supply.rounds_per_group"));
        Button reset = button(Texts.tr("screen.tacz_pubg.loot_config.restore_gun_defaults"), right + rightWidth - 105, y + 25, 105, () -> {
            GunAmmoSupply defaults = ammoDefaults(entry);
            if (defaults != null) { ammoForm.replace(defaults, true); rememberScroll(); rebuildWidgets(); }
        });
        reset.active = ammoDefaults(entry) != null;
        int probX = right + rightWidth - 108, groupsX = probX - 46, selfX = groupsX - 40;
        line(Texts.tr("screen.tacz_pubg.gun_ammo_supply.loadout_ammo"), right, y + 55, selfX - right - 4, 0xFFB5D9FF);
        line(Texts.tr("screen.tacz_pubg.gun_ammo_supply.groups"), groupsX, y + 55, 40, 0xFFB5D9FF);
        line(Texts.tr("screen.tacz_pubg.gun_ammo_supply.chance"), probX, y + 55, 52, 0xFFB5D9FF);
        line(Texts.tr("screen.tacz_pubg.loot_config.total_rounds"), right + rightWidth - 50, y + 55, 50, 0xFFB5D9FF);
        for (int index = 0; index < 3; index++) {
            final int option = index; int rowY = y + 70 + index * 26;
            line(Integer.toString(index + 1), right, rowY + 6, 14, 0xFFFFFFFF);
            String id = ammoForm.ammoIds[index]; var choice = choices.get("ammo:" + id);
            String label = "self".equals(id) ? Texts.tr("screen.tacz_pubg.gun_ammo_supply.follow_current_ammo") : choice == null ? id : choice.preview().getHoverName().getString();
            Button picker = button(fit(label, selfX - right - 28), right + 18, rowY, selfX - right - 24, () -> pickInlineAmmo(option));
            picker.setTooltip(Tooltip.create(Texts.component(label + "\n" + id)));
            button(Texts.tr("screen.tacz_pubg.gun_ammo_supply.current"), selfX, rowY, 34, () -> { ammoForm.ammoIds[option] = "self"; ammoForm.dynamicDefaults = false; rememberScroll(); rebuildWidgets(); });
            edit(groupsX, rowY, 40, ammoForm.groups[index], value -> { ammoForm.groups[option] = value; ammoForm.dynamicDefaults = false; }, 10, Texts.tr("screen.tacz_pubg.loot_config.loadout_groups"));
            edit(probX, rowY, 52, ammoForm.probabilities[index], value -> { ammoForm.probabilities[option] = value; ammoForm.dynamicDefaults = false; }, 24, Texts.tr("screen.tacz_pubg.loot_config.loadout_chance"));
        }
        return y + 198;
    }

    private void pickInlineAmmo(int option) {
        if (saving || !applyForms(false)) return;
        rememberScroll();
        minecraft.setScreen(new ItemPickerScreen(this, catalog, choice -> {
            ammoForm.ammoIds[option] = choice.id(); ammoForm.dynamicDefaults = false; rebuildWidgets();
        }, "ammo", Texts.of("screen.tacz_pubg.gun_ammo_supply.choose_supply_ammo")));
    }

    private void editAmmoSupply() {
        if (saving || !applyForms(false) || selected >= draft.items().size()) return;
        Entry entry = draft.items().get(selected);
        if (ammoForm == null) { showMessage(Texts.tr("screen.tacz_pubg.loot_config.load_gun_defaults_try_again"), true); return; }
        rememberScroll();
        minecraft.setScreen(new GunAmmoSupplyScreen(this, name(entry), entry.id(), catalog, entry.ammoSupply(),
                ammoDefaults(entry), ammoForm, supply -> {
            List<Entry> items = new ArrayList<>(draft.items());
            for (int index = 0; index < items.size(); index++) {
                Entry original = items.get(index);
                if (original.key().equals(entry.key())) {
                    items.set(index, new Entry(original.kind(), original.id(), original.minCount(), original.maxCount(), original.weight(), supply));
                    selected = index; break;
                }
            }
            draft = new Document(draft.general(), items, draft.gunSettings(), draft.categoryProbabilities(), draft.tierProfiles());
            clearItemForms(); weightViewDocument = null;
            showMessage(Texts.tr("screen.tacz_pubg.loot_config.gun_ammo_draft_save_apply"), false);
        }));
    }

    private void initExperiments() {
        line(Texts.tr("screen.tacz_pubg.loot_config.select_independently_changes_apply_save"), 10, 3, width - 26, 0xFFB5D9FF);
        int labelWidth = width >= 600 ? 160 : 77;
        int optionX = 10 + labelWidth, optionWidth = (width - optionX - 24) / 2;
        for (int index = 0; index < EXPERIMENT_ORDER.size(); index++) {
            GunSettingCategory category = EXPERIMENT_ORDER.get(index); int y = 25 + index * 46;
            line(category.label(), 32, y + 6, labelWidth - 30, 0xFFFFFFFF);
            String current = draft.gunSettings().value(category);
            button((LootConfigModel.INITIAL.equals(current) ? "● " : "○ ") + Texts.tr("screen.tacz_pubg.loot_config.default_original"), optionX, y, optionWidth,
                    () -> chooseGunSetting(category, LootConfigModel.INITIAL));
            button((LootConfigModel.EXPERIMENTAL.equals(current) ? "● " : "○ ") + Texts.tr("screen.tacz_pubg.loot_config.experimental"), optionX + optionWidth + 6, y, optionWidth,
                    () -> chooseGunSetting(category, LootConfigModel.EXPERIMENTAL));
            line(experimentDescription(category), 10, y + 27, width - 30, 0xFFAAAAAA);
        }
        int iconsY = 25 + EXPERIMENT_ORDER.size() * 46;
        line(Texts.tr("screen.tacz_pubg.original_icons.use_original"), 10, iconsY + 6, labelWidth, 0xFFFFFFFF);
        boolean original = draft.gunSettings().usePubgOriginalIcons();
        button((original ? "● " : "○ ") + Texts.tr("screen.tacz_pubg.original_icons.on"), optionX, iconsY, optionWidth,
                () -> chooseOriginalIcons(true));
        button((!original ? "● " : "○ ") + Texts.tr("screen.tacz_pubg.original_icons.off"), optionX + optionWidth + 6, iconsY, optionWidth,
                () -> chooseOriginalIcons(false));
        line(Texts.tr("screen.tacz_pubg.original_icons.description"), 10, iconsY + 27, width - 30, 0xFFAAAAAA);
        int modelY = iconsY + 46;
        line(Texts.tr("screen.tacz_pubg.inventory.model_style"), 10, modelY + 6, labelWidth, 0xFFFFFFFF);
        boolean lowPoly = draft.gunSettings().lowPolyModels();
        button((!lowPoly ? "● " : "○ ") + Texts.tr("screen.tacz_pubg.inventory.style.playful_world"), optionX, modelY, optionWidth,
                () -> chooseLowPoly(false));
        button((lowPoly ? "● " : "○ ") + Texts.tr("screen.tacz_pubg.inventory.style.pixel_hardcore"), optionX + optionWidth + 6, modelY, optionWidth,
                () -> chooseLowPoly(true));
        line(Texts.tr("screen.tacz_pubg.inventory.model_style_hint"), 10, modelY + 27, width - 30, 0xFFAAAAAA);
        for (int index = 0; index < 2; index++) {
            boolean equipment = index == 0;
            int y = modelY + 46 + index * 46;
            String key = equipment ? "screen.tacz_pubg.inventory.auto_equipment" : "screen.tacz_pubg.inventory.auto_attachments";
            boolean enabled = equipment ? draft.gunSettings().inventory().autoEquipmentReplacement()
                    : draft.gunSettings().inventory().autoAttachmentEquip();
            line(Texts.tr(key), 10, y + 6, labelWidth, 0xFFFFFFFF);
            button((enabled ? "● " : "○ ") + Texts.tr("screen.tacz_pubg.original_icons.on"),
                    optionX, y, optionWidth, () -> chooseAutomatic(equipment, true));
            button((!enabled ? "● " : "○ ") + Texts.tr("screen.tacz_pubg.original_icons.off"),
                    optionX + optionWidth + 6, y, optionWidth, () -> chooseAutomatic(equipment, false));
            line(Texts.tr(key + "_hint"), 10, y + 27, width - 30, 0xFFAAAAAA);
        }
        button(Texts.tr("screen.tacz_pubg.inventory.settings"), 10, modelY + 140, width - 30, this::editInventorySettings);
        button(Texts.tr("screen.tacz_pubg.hud_layout.open"), 10, modelY + 168, width - 30,
                () -> {
                    if (!applyForms()) return;
                    rememberScroll();
                    minecraft.setScreen(new com.example.examplemod.hud.client.HudLayoutEditorScreen(this));
                });
        int otherY = modelY + 208;
        line(Texts.tr("screen.tacz_pubg.loot_config.model_permissions_battle_settings"), 10, otherY, width - 30, 0xFFB5D9FF);
        button(Texts.tr("screen.tacz_pubg.model_scale.title"), 10, otherY + 18, width - 30, this::editModelScales);
        button(Texts.tr("screen.tacz_pubg.loot_config.operator_permissions"), 10, otherY + 46, width - 30, this::editOperatorPermissions);
        button(Texts.tr("screen.tacz_pubg.battle_settings.title"), 10, otherY + 74, width - 30, this::editBattleSettings);
        contentHeight = otherY + 108;
    }
    private void editModelScales() {
        if (saving) return;
        rememberScroll();
        // Keep this authoring session and its raw numeric fields intact beneath independent pages.
        minecraft.setScreen(new com.example.examplemod.modelscale.client.ModelScaleSettingsScreen(this));
    }
    private void editBattleSettings() {
        if (saving) return;
        rememberScroll();
        minecraft.setScreen(new com.example.examplemod.pubgsettings.client.PubgSettingsScreen(this));
    }
    private void chooseAutomatic(boolean equipment, boolean enabled) {
        if (saving || !applyForms()) return;
        rememberScroll();
        var inventory = draft.gunSettings().inventory();
        inventory = equipment ? inventory.withAutoEquipmentReplacement(enabled) : inventory.withAutoAttachmentEquip(enabled);
        draft = new Document(draft.general(), draft.items(), draft.gunSettings().withInventory(inventory),
                draft.categoryProbabilities(), draft.tierProfiles());
        rebuildWidgets();
    }
    private void chooseLowPoly(boolean enabled) {
        if (saving || !applyForms()) return;
        rememberScroll();
        draft = new Document(draft.general(), draft.items(), draft.gunSettings().withLowPolyModels(enabled),
                draft.categoryProbabilities(), draft.tierProfiles());
        rebuildWidgets();
    }
    private void editInventorySettings() {
        if (saving || !applyForms()) return;
        rememberScroll();
        minecraft.setScreen(new com.example.examplemod.inventory.client.InventorySettingsScreen(this,
                draft.gunSettings().inventory(), catalog, inventory -> {
            draft = new Document(draft.general(), draft.items(), draft.gunSettings().withInventory(inventory),
                    draft.categoryProbabilities(), draft.tierProfiles());
            rebuildWidgets();
        }));
    }
    private void editItemCapacity() {
        if (saving || !applyForms() || selected >= draft.items().size()) return;
        rememberScroll();
        Entry entry = draft.items().get(selected);
        ItemStack preview = preview(entry);
        minecraft.setScreen(new com.example.examplemod.inventory.client.ItemCapacityScreen(this, preview, entry.key(),
                draft.gunSettings().inventory().capacityOverrides().get(entry.key()),
                com.example.examplemod.backpack.BackpackWeights.unit(preview) / 1000.0, capacity -> {
            draft = new Document(draft.general(), draft.items(), draft.gunSettings().withInventory(
                    draft.gunSettings().inventory().withCapacity(entry.key(), capacity)),
                    draft.categoryProbabilities(), draft.tierProfiles());
            rebuildWidgets();
        }));
    }
    private void chooseOriginalIcons(boolean enabled) {
        if (saving || !applyForms()) return;
        rememberScroll();
        draft = new Document(draft.general(), draft.items(), draft.gunSettings().withOriginalIcons(enabled),
                draft.categoryProbabilities(), draft.tierProfiles());
        rebuildWidgets();
    }
    private String experimentDescription(GunSettingCategory category) {
        return switch (category) {
            case BALANCE -> Texts.tr("screen.tacz_pubg.loot_config.gun_roles_heavy_weapon_explosive_balance"); case SCOPES -> Texts.tr("screen.tacz_pubg.loot_config.classic_battle_royale_scope_compatibility");
            case AMMO -> Texts.tr("screen.tacz_pubg.loot_config.unified_ammo_mapping_configure_per_gun_supplies_general"); case MAGAZINE -> Texts.tr("screen.tacz_pubg.loot_config.nine_magazine_types_quickdraw_extended_quickdraw_reload_faster");
            case MUZZLE, GRIP, STOCK -> Texts.tr("screen.tacz_pubg.loot_config.dedicated_attachment_weights_reduced_companion_b_settings_general");
            case LASER -> Texts.tr("screen.tacz_pubg.loot_config.keep_selected_laser_attachment");
        };
    }
    private void chooseGunSetting(GunSettingCategory category, String choice) {
        if (saving) return;
        applyForms();
        rememberScroll();
        draft = new Document(draft.general(), draft.items(), draft.gunSettings().withValue(category, choice), draft.categoryProbabilities(), draft.tierProfiles());
        weightViewDocument = null; rebuildWidgets();
    }
    static Document viewTier(Document document, LootTier tier) {
        TierProfile profile = document.tier(tier);
        return new Document(document.general(), profile.items(), document.gunSettings(), profile.categoryProbabilities(), document.tierProfiles());
    }
    static Document normalizeDocument(Document document) {
        TierProfile high = document.tier(LootTier.HIGH);
        return new Document(document.general(), high.items(), document.gunSettings(), high.categoryProbabilities(), document.tierProfiles());
    }
    private Document configurationDocument() {
        return normalizeDocument(draft.withTier(activeTier, new TierProfile(draft.items(), draft.categoryProbabilities())));
    }
    private void chooseTier(LootTier tier) {
        if (tier == activeTier || saving || !applyForms()) return;
        rememberScroll();
        Document complete = configurationDocument(); activeTier = tier; draft = viewTier(complete, tier);
        categoryForm = new CategoryForm(draft.categoryProbabilities()); clearItemForms(); selected = 0; scroll = 0;
        weightViewDocument = null; rebuildWidgets();
    }
    public void saveConfiguration() { save(); }
    public boolean isSavingConfiguration() { return saving; }
    public boolean isConfigurationDirty() { return hasUnsavedChanges(); }
    public boolean wasLastSaveSuccessful() { return lastSaveSuccessful; }
    private void choosePool(LootCategory category) {
        if (saving || !applyForms()) return;
        rememberScroll(); poolFilter = category; scroll = 0;
        for (int index = 0; index < draft.items().size(); index++) {
            if (category == null || categoryOf(draft.items().get(index)) == category) { selected = index; break; }
        }
        clearItemForms(); rebuildWidgets();
    }
    private void pickItem() {
        if (saving || !applyForms()) return;
        rememberScroll();
        minecraft.setScreen(new ItemPickerScreen(this, catalog, choice -> {
            String key = choice.kind() + ":" + choice.id();
            for (int index = 0; index < draft.items().size(); index++) if (draft.items().get(index).key().equals(key)) {
                selected = index; poolFilter = categoryOf(draft.items().get(index)); clearItemForms(); rebuildWidgets(); return;
            }
            List<Entry> items = new ArrayList<>(draft.items());
            Entry added = new Entry(choice.kind(), choice.id(), 1, 1, choice.kind().equals("ammo") ? 0 : 1);
            items.add(added); draft = new Document(draft.general(), items, draft.gunSettings(), draft.categoryProbabilities(), draft.tierProfiles());
            selected = items.size() - 1; poolFilter = categoryOf(added); clearItemForms(); query = ""; scroll = Double.MAX_VALUE; rebuildWidgets();
        }));
    }
    private void removeItem() {
        if (saving || !applyForms()) return;
        rememberScroll(); Entry entry = draft.items().get(selected);
        minecraft.setScreen(new ConfirmScreen(remove -> {
            if (remove) {
                List<Entry> items = new ArrayList<>(draft.items()); items.remove(selected);
                draft = new Document(draft.general(), items, draft.gunSettings(), draft.categoryProbabilities(), draft.tierProfiles());
                selected = Math.max(0, selected - 1); clearItemForms();
            }
            minecraft.setScreen(this);
        }, Texts.of("screen.tacz_pubg.loot_config.remove_entry_loot_configuration"), Texts.component(entry.id()), Texts.of("screen.tacz_pubg.loot_config.remove"), Texts.of("screen.tacz_pubg.gun_ammo_supply.back")));
    }
    private void selectItem(int index) {
        if (saving || index == selected || !applyForms()) return;
        rememberScroll(); selected = index; clearItemForms(); rebuildWidgets();
    }
    @Override public void tick() {
        super.tick();
        if (saveConfiguration != null) saveConfiguration.visible = hasUnsavedChanges() || saving;
        if (pendingSelection >= 0) { int index = pendingSelection; pendingSelection = -1; selectItem(index); }
    }
    private void rememberScroll() { if (itemList != null) scroll = itemList.getScrollAmount(); }

    private boolean applyForms() { return applyForms(true); }
    private boolean applyForms(boolean includeAmmo) {
        try {
            General general = generalForm.parse(gunClassForm.parse()); List<Entry> items = new ArrayList<>(draft.items());
            if (itemForm != null && selected < items.size()) {
                Entry parsed = itemForm.parse();
                if (includeAmmo && ammoForm != null && parsed.key().equals(ammoKey)) {
                    GunAmmoSupply supply = ammoForm.parse(choices.values().stream().filter(choice -> choice.kind().equals("ammo"))
                            .map(LootCatalog.Choice::id).collect(java.util.stream.Collectors.toSet()));
                    parsed = new Entry(parsed.kind(), parsed.id(), parsed.minCount(), parsed.maxCount(), parsed.weight(), ammoForm.dynamicDefaults ? null : supply);
                } else if (!includeAmmo) {
                    Entry current = items.get(selected);
                    parsed = new Entry(parsed.kind(), parsed.id(), parsed.minCount(), parsed.maxCount(), parsed.weight(), current.ammoSupply());
                }
                items.set(selected, parsed);
            }
            Document changed = new Document(general, items, draft.gunSettings(), categoryForm.parse(), draft.tierProfiles()).withTier(activeTier, new TierProfile(items, categoryForm.parse()));
            List<String> issues = LootConfigModel.validate(changed).stream().filter(issue -> !LootConfigModel.isCategoryTotalError(issue)
                    && !LootConfigModel.isGunClassTotalError(issue)).toList();
            if (!issues.isEmpty()) throw new IllegalArgumentException(String.join("；", issues));
            draft = changed; generalForm.gunSelection = general.gunSelection(); weightViewDocument = null; return true;
        } catch (IllegalArgumentException issue) { showMessage(issue.getMessage(), true); return false; }
    }
    private List<String> formValues() {
        List<String> values = new ArrayList<>(List.of(generalForm.minimum, generalForm.maximum, generalForm.chance,
                generalForm.yTolerance, generalForm.spacing, generalForm.density, generalForm.radiusMin,
                generalForm.radiusMax, generalForm.attachmentChance, generalForm.attachmentKinds));
        values.add(generalForm.zoneSettings.toString());
        values.add(generalForm.operatorPermissions.toString());
        values.addAll(gunClassForm.values());
        for (LootCategory category : LootCategory.values()) values.add(categoryForm.values.get(category.id()));
        if (itemForm != null) values.addAll(List.of(itemForm.original.key(), itemForm.weight, itemForm.minimum, itemForm.maximum));
        if (ammoForm != null) values.addAll(ammoForm.values());
        return List.copyOf(values);
    }
    private void save() {
        if (saving || !applyForms()) return;
        List<String> issues = LootConfigModel.validate(draft);
        if (!issues.isEmpty()) { showMessage(String.join("；", issues), true); return; }
        LootConfigClient.save(this, revision, configurationDocument());
    }

    public boolean hasUnsavedChanges() {
        return !configurationDocument().equals(normalizeDocument(savedDocument)) || !generalForm.equalsValues(draft.general())
                || !gunClassForm.equalsValues(draft.general().gunSelection(), gunClassCounts())
                || !categoryForm.equalsValues(draft.categoryProbabilities())
                || itemForm != null && !itemForm.equalsValues()
                || ammoForm != null && !ammoForm.values().equals(ammoBaseline);
    }

    void setSaving(boolean value) {
        saving = value;
        rememberScroll();
        if (saving) {
            lastSaveSuccessful = false; submittedDocument = configurationDocument(); submittedFormValues = formValues();
            showMessage(Texts.tr("screen.tacz_pubg.loot_config.submitting_awaiting_server_confirmation"), false);
        }
        rebuildWidgets();
    }

    void acceptSaveResult(LootConfigPayloads.Snapshot snapshot) {
        if (isStaleResult(snapshot)) return;
        saving = false; lastSaveSuccessful = snapshot.success();
        if (!snapshot.catalog().isEmpty()) setCatalog(snapshot.catalog());
        if (snapshot.success()) {
            boolean differentSubmission = submittedDocument != null && !snapshot.document().equals(submittedDocument);
            boolean editedAfterSubmission = shouldPreserveDraft(configurationDocument(), submittedDocument, snapshot.document(),
                    formValues(), submittedFormValues, hasUnsavedChanges());
            revision = snapshot.revision();
            savedDocument = snapshot.document();
            if (!editedAfterSubmission) {
                draft = viewTier(snapshot.document(), activeTier);
                generalForm = new GeneralForm(draft.general());
                gunClassForm = new GunClassForm(draft.general().gunSelection(), gunClassCounts());
                categoryForm = new CategoryForm(draft.categoryProbabilities());
                clearItemForms();
            }
            // A late success for A must not clear the still-pending B baseline.
            if (!differentSubmission) { submittedDocument = null; submittedFormValues = null; }
            conflict = null;
            if (editedAfterSubmission) {
                showMessage(differentSubmission ? Texts.tr("screen.tacz_pubg.loot_config.confirmation_older_different_submission_draft_retained_review")
                        : Texts.tr("screen.tacz_pubg.loot_config.previous_submission_was_saved_newer_edits_remain_save"), false);
                rebuildWidgets();
                return;
            }
        } else if (snapshot.revision() > revision) conflict = snapshot;
        showMessage(snapshot.message(), !snapshot.success());
        rebuildWidgets();
    }

    boolean isStaleResult(LootConfigPayloads.Snapshot snapshot) { return snapshot.revision() < revision; }

    static boolean editedAfterSubmission(Document current, Document submitted, List<String> currentRaw, List<String> submittedRaw) {
        return submitted != null && (!current.equals(submitted) || submittedRaw != null && !currentRaw.equals(submittedRaw));
    }
    static boolean shouldPreserveDraft(Document current, Document submitted, Document acknowledged,
            List<String> currentRaw, List<String> submittedRaw, boolean unsaved) {
        return submitted == null ? unsaved
                : !acknowledged.equals(submitted) || editedAfterSubmission(current, submitted, currentRaw, submittedRaw);
    }

    private void rebaseDraft() {
        if (conflict == null || !applyForms()) return;
        minecraft.setScreen(new ConfirmScreen(confirm -> {
            if (confirm && conflict != null) {
                revision = conflict.revision();
                savedDocument = conflict.document();
                conflict = null;
                showMessage(Texts.tr("screen.tacz_pubg.loot_config.draft_retained_baseline_updated_review_before_saving"), false);
            }
            minecraft.setScreen(this);
        }, Texts.of("screen.tacz_pubg.loot_config.overwrite_latest_server_configuration_draft"), Texts.of("screen.tacz_pubg.loot_config.another_editor_may_have_changed_configuration"),
                Texts.of("screen.tacz_pubg.loot_config.keep_draft_retry"), Texts.of("screen.tacz_pubg.loot_config.back_review")));
    }

    void showMessage(String text, boolean failure) { message = text == null ? "" : text; error = failure; }

    @Override public void onClose() {
        if (saving) { showMessage(Texts.tr("screen.tacz_pubg.loot_config.saving_awaiting_server_confirmation"), false); return; }
        if (hasUnsavedChanges() || ConfigUi.hasOtherDrafts(this)) minecraft.setScreen(new ConfirmScreen(discard -> {
            if (discard) closeDiscarded(); else minecraft.setScreen(this);
        }, Texts.of("screen.tacz_pubg.loot_config.discard_unsaved_draft"), Texts.of("screen.tacz_pubg.loot_config.discard_pending_loot_map_game_edits"),
                Texts.of("screen.tacz_pubg.loot_config.discard_draft"), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
        else closeDiscarded();
    }

    private void closeDiscarded() { LootConfigClient.closed(this); minecraft.setScreen(null); }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { super.renderBackground(graphics, mouseX, mouseY, partialTick); }
    @Override public boolean isPauseScreen() { return false; }

    private void positionWidgets() {
        pageScroll[view] = Math.clamp(pageScroll[view], 0, Math.max(0, contentHeight - layout.viewportHeight()));
        for (PageWidget page : pageWidgets) {
            AbstractWidget widget = page.widget(); widget.setY(layout.top() + page.logicalY() - (int) pageScroll[view]);
            widget.visible = layout.intersects(page.logicalY(), widget.getHeight(), pageScroll[view]);
            widget.active = page.enabled() && !saving && (widget instanceof ItemList
                    || layout.fullyVisible(page.logicalY(), widget.getHeight(), pageScroll[view]));
            if (!layout.fullyVisible(page.logicalY(), widget.getHeight(), pageScroll[view]) && getFocused() == widget) setFocused(null);
        }
    }
    private void movePage(double amount) { pageScroll[view] += amount; positionWidgets(); }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!saving && layout.contains(mouseX, mouseY)) {
            if (itemList != null && itemList.visible && itemList.isMouseOver(mouseX, mouseY)) {
                double before = itemList.getScrollAmount();
                itemList.mouseScrolled(mouseX, mouseY, horizontal, vertical); scroll = itemList.getScrollAmount();
                if (before == scroll) movePage(-vertical * 28);
            } else movePage(-vertical * 28);
            return true;
        }
        return false;
    }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!saving && button == 0 && mouseX >= width - 7 && mouseX < width - 2
                && mouseY >= layout.top() && mouseY < layout.bottom() && contentHeight > layout.viewportHeight()) {
            scrollDragging = true; dragScroll(mouseY); return true;
        }
        if (layout.contains(mouseX, mouseY)) {
            if (saving) return false;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // Content widgets may overlap the header after translation; they must never consume its clicks.
        for (AbstractWidget widget : fixedWidgets) if (widget.mouseClicked(mouseX, mouseY, button)) {
            setFocused(widget); if (button == 0) setDragging(true); return true;
        }
        setFocused(null); return false;
    }
    private void dragScroll(double y) {
        double fraction = (y - layout.top()) / Math.max(1, layout.viewportHeight());
        pageScroll[view] = fraction * Math.max(0, contentHeight - layout.viewportHeight()); positionWidgets();
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (scrollDragging) { dragScroll(y); return true; }
        return layout.contains(x, y) && super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        scrollDragging = false;
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (getFocused() instanceof AbstractWidget widget && pageWidgets.stream().anyMatch(page -> page.widget() == widget)
                && (!widget.visible || !widget.active)) setFocused(null);
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.enableScissor(8, layout.top(), width - 7, layout.bottom());
        graphics.fill(8, layout.top(), width - 8, layout.bottom(), 0xB0202020);
        graphics.pose().pushPose(); graphics.pose().translate(0, layout.top() - (int) pageScroll[view], 0);
        for (Line line : lines) graphics.drawString(font, Texts.resolve(fit(line.text(), line.maxWidth())), line.x(), line.y(), line.color());
        if (view == 0) renderProbability(graphics); else renderExperimentIcons(graphics);
        graphics.pose().popPose();
        int mx = layout.contains(mouseX, mouseY) ? mouseX : -1000, my = layout.contains(mouseX, mouseY) ? mouseY : -1000;
        for (PageWidget page : pageWidgets) page.widget().render(graphics, mx, my, partialTick);
        graphics.disableScissor();
        if (contentHeight > layout.viewportHeight()) {
            int top = layout.top(), size = layout.viewportHeight();
            graphics.fill(width - 6, top, width - 3, layout.bottom(), 0xFF242424);
            int thumb = Math.max(15, size * size / contentHeight);
            int y = top + (int) ((size - thumb) * pageScroll[view] / Math.max(1, contentHeight - size));
            graphics.fill(width - 6, y, width - 3, y + thumb, 0xFFB8B8B8);
        }
        ConfigUi.renderHeader(graphics, font, width, Texts.tr("screen.tacz_pubg.loot_config.pubg_configuration"), saving ? Texts.tr("screen.tacz_pubg.loot_config.awaiting_server_confirmation")
                : hasUnsavedChanges() ? Texts.tr("screen.tacz_pubg.loot_config.unsaved_draft") : Texts.tr("screen.tacz_pubg.loot_config.saved_version", revision),
                hasUnsavedChanges() ? 0xFFFFD070 : 0xFF8FE0A0);
        if (!message.isBlank()) graphics.drawString(font, Texts.resolve(fit(message, width - 18)), 9, height - 38, error ? 0xFFFF8888 : 0xFFB0E0B0);
        if (view == 0 && layout.contains(mouseX, mouseY) && itemList != null && itemList.visible
                && itemList.isMouseOver(mouseX, mouseY) && itemList.hoveredRow() != null)
            graphics.renderTooltip(font, font.split(Texts.component(itemDetails(itemList.hoveredRow().item)), Math.max(160, width - 32)), mouseX, mouseY);
        if (!message.isBlank() && mouseY >= height - 42 && mouseY < height - 27)
            graphics.renderTooltip(font, font.split(Texts.component(message), Math.max(160, width - 32)), mouseX, mouseY);
    }
    private void renderExperimentIcons(GuiGraphics graphics) {
        for (int index = 0; index < EXPERIMENT_ORDER.size(); index++) {
            GunSettingCategory category = EXPERIMENT_ORDER.get(index);
            String kind = category == GunSettingCategory.BALANCE ? "gun" : category == GunSettingCategory.AMMO ? "ammo" : "attachment";
            String semantic = category == GunSettingCategory.SCOPES ? "scope" : category.id();
            final int y = 27 + index * 46;
            catalog.stream().filter(choice -> choice.kind().equals(kind)
                    && (!kind.equals("attachment") || choice.attachmentInfo().category().equals(semantic)))
                    .findFirst().ifPresent(choice -> graphics.renderItem(choice.preview(), 10, y));
        }
    }
    private void renderProbability(GuiGraphics graphics) {
        ensureProbabilityView();
        if (gunClassesExpanded) {
            int columns = width >= 600 ? 9 : 3;
            int summaryY = layout.generalY() - gunClassHeight(width) + 30
                    + ((GunClass.values().length + columns - 1) / columns) * 43;
            String summary = gunClassForm.automatic ? Texts.tr("screen.tacz_pubg.loot_config.calculate_by_gun_model_count") : Texts.tr("screen.tacz_pubg.loot_config.custom_gun_class_shares");
            int color = 0xFFB5D9FF;
            try {
                double total = gunClassForm.parse().configuredProbabilities(gunClassCounts()).values().stream().mapToDouble(Double::doubleValue).sum();
                summary += Texts.tr("screen.tacz_pubg.loot_config.total", percentText(total));
                if (Math.abs(total - 1) > LootConfigModel.CATEGORY_TOTAL_TOLERANCE) color = 0xFFFFC078;
            } catch (IllegalArgumentException ignored) { summary += Texts.tr("screen.tacz_pubg.loot_config.complete_input"); color = 0xFFFFC078; }
            graphics.drawString(font, Texts.resolve(fit(summary, width - 28)), 10, summaryY, color);
        }
        String totalLabel;
        int totalColor;
        try {
            double total = categoryForm.parse().values().stream().mapToDouble(Double::doubleValue).sum();
            totalLabel = Texts.tr("screen.tacz_pubg.loot_config.total.render_probability", percentText(total));
            totalColor = Math.abs(total - 1) <= LootConfigModel.CATEGORY_TOTAL_TOLERANCE ? 0xFF8FE0A0 : 0xFFFFC078;
        } catch (IllegalArgumentException issue) {
            totalLabel = Texts.tr("screen.tacz_pubg.loot_config.complete_input.render_probability");
            totalColor = 0xFFFFC078;
        }
        // Keep totals on their own row; size and center the rendered text using the active font.
        String totalText = fit(totalLabel, width - 20);
        graphics.drawString(font, Texts.resolve(totalText), Math.max(10, (width - font.width(Texts.resolve(totalText))) / 2), PROBABILITY_TOTAL_Y, totalColor);
        if (draft.items().isEmpty()) return;
        Entry entry = draft.items().get(selected); ItemStack stack = preview(entry);
        if (poolFilter != null && categoryOf(entry) != poolFilter) return;
        if (!stack.isEmpty()) graphics.renderItem(stack, right, bodyY + 1);
        graphics.drawString(font, Texts.resolve(fit(name(entry), rightWidth - 25)), right + 24, bodyY, 0xFFFFFFFF);
        graphics.drawString(font, Texts.resolve(fit(entry.id(), rightWidth - 25)), right + 24, bodyY + 13, 0xFFAAAAAA);
        String poolSummary = entry.kind().equals("gun")
                ? gunClassOf(entry).label() + " " + percentText(probabilityView.gunClassProbability(gunClassOf(entry)))
                        + Texts.tr("screen.tacz_pubg.loot_config.within_class") + percentText(probabilityView.withinGunClassProbability(effectiveEntry(entry))) + "%"
                : Texts.tr("screen.tacz_pubg.loot_config.category_pool", percentText(probabilityView.poolProbability(categoryOf(entry))), percentText(probabilityView.withinPoolProbability(effectiveEntry(entry))));
        graphics.drawString(font, Texts.resolve(fit(poolSummary, rightWidth)), right, bodyY + 32, 0xFF89C7FF);
        graphics.drawString(font, Texts.resolve(fit(Texts.tr("screen.tacz_pubg.loot_config.overall_effective_weight", percentText(probabilityView.itemProbability(effectiveEntry(entry))), String.format(Locale.ROOT, "%.4g", effectiveWeight(entry))), rightWidth)), right, bodyY + 45, 0xFFB5D9FF);
        if (layout.inlineAmmo() && ammoForm != null) {
            int start = bodyY + 116, totalsX = right + rightWidth - 50;
            for (int index = 0; index < 3; index++) graphics.drawString(font, Texts.resolve(fit(ammoForm.totalRoundsText(index), 50)), totalsX,
                    start + 76 + index * 26, 0xFFB5D9FF);
            String issue = "";
            try { ammoForm.parse(choices.values().stream().filter(choice -> choice.kind().equals("ammo"))
                    .map(LootCatalog.Choice::id).collect(java.util.stream.Collectors.toSet())); }
            catch (IllegalArgumentException invalid) { issue = invalid.getMessage(); }
            graphics.drawString(font, Texts.of("screen.tacz_pubg.loot_config.total_loadout", ammoForm.totalProbabilityText(), (ammoForm.dynamicDefaults ? Texts.tr("screen.tacz_pubg.loot_config.use_defaults") : Texts.tr("screen.tacz_pubg.loot_config.custom"))).getString(), right, start + 155, issue.isEmpty() ? 0xFF8FE0A0 : 0xFFFFC078);
            graphics.drawString(font, Texts.resolve(fit(issue.isEmpty() ? Texts.tr("screen.tacz_pubg.loot_config.total_rounds_groups_rounds_per_group") : issue, rightWidth)),
                    right, start + 171, issue.isEmpty() ? 0xFFAAAAAA : 0xFFFF8888);
        }
    }
    private ItemStack preview(Entry entry) { var choice = choices.get(entry.key()); return choice == null ? ItemStack.EMPTY : choice.preview(); }
    private LootCategory categoryOf(Entry entry) {
        var choice = choices.get(entry.key());
        return LootCategory.classify(entry, choice == null ? "other" : choice.attachmentInfo().category());
    }
    private GunClass gunClassOf(Entry entry) {
        var choice = choices.get(entry.key());
        return choice == null ? GunClass.OTHER : GunClass.byId(choice.gunClass());
    }
    private void ensureProbabilityView() {
        if (weightViewDocument != null && probabilityBaseDocument == draft) return;
        Document view = draft;
        try {
            List<Entry> items = new ArrayList<>(draft.items());
            if (itemForm != null && selected < items.size()) items.set(selected, itemForm.parse());
            view = new Document(generalForm.parse(gunClassForm.parse()), items, draft.gunSettings(), categoryForm.parse(), draft.tierProfiles());
        } catch (IllegalArgumentException ignored) {
            // Keep the last committed draft preview while a numeric field is incomplete.
        }
        if (!view.equals(weightViewDocument)) {
            effectiveWeights.clear();
            effectiveEntries.clear();
            List<Entry> entries = new ArrayList<>();
            for (Entry configured : view.items()) {
                var choice = choices.get(configured.key());
                var info = choice == null ? null : choice.attachmentInfo();
                double weight = AttachmentLootPolicy.effectiveWeight(configured, view.gunSettings(), info);
                effectiveWeights.put(configured.key(), weight);
                Entry effective = configured.withWeight(weight);
                effectiveEntries.put(configured.key(), effective);
                entries.add(effective);
            }
            Document effective = new Document(view.general(), entries, view.gunSettings(), view.categoryProbabilities(), view.tierProfiles());
            probabilityView = TwoStageLootSampler.prepare(effective, this::categoryOf, this::gunClassOf, gunClassCounts());
            weightViewDocument = view;
        }
        probabilityBaseDocument = draft;
    }
    private Entry effectiveEntry(Entry entry) { return effectiveEntries.getOrDefault(entry.key(), entry); }
    private double effectiveWeight(Entry entry) {
        ensureProbabilityView();
        return effectiveWeights.getOrDefault(entry.key(), entry.weight());
    }
    private String itemDetails(Entry entry) {
        ensureProbabilityView();
        LootCategory category = categoryOf(entry);
        return name(entry) + "\n" + entry.kind() + " · " + entry.id()
                + Texts.tr("screen.tacz_pubg.loot_config.pool") + category.label()
                + (entry.kind().equals("gun") ? Texts.tr("screen.tacz_pubg.loot_config.gun_class_actual_weighted_share_within_class_gun", gunClassOf(entry).label(), String.format(java.util.Locale.ROOT, "%.3f", probabilityView.gunClassProbability(gunClassOf(entry)) * 100), String.format(java.util.Locale.ROOT, "%.3f", probabilityView.withinGunClassProbability(effectiveEntry(entry)) * 100), String.format(java.util.Locale.ROOT, "%d", gunClassCounts().getOrDefault(gunClassOf(entry), 0))) : "")
                + Texts.tr("screen.tacz_pubg.loot_config.actual_category_pool_actual_overall", String.format(java.util.Locale.ROOT, "%.3f", probabilityView.poolProbability(category) * 100), String.format(java.util.Locale.ROOT, "%.3f", probabilityView.withinPoolProbability(effectiveEntry(entry)) * 100), String.format(java.util.Locale.ROOT, "%.4f", probabilityView.itemProbability(effectiveEntry(entry)) * 100))
                + Texts.tr("screen.tacz_pubg.loot_config.base_weight.item_details") + entry.weight() + Texts.tr("screen.tacz_pubg.loot_config.effective_weight") + effectiveWeights.getOrDefault(entry.key(), entry.weight())
                + Texts.tr("screen.tacz_pubg.loot_config.effective_category_shares_normalized_selection") + attachmentSupportLabel(entry)
                + (entry.kind().equals("gun") ? "\n" + ammoSupplyDetails(entry) : "");
    }

    private String ammoSupplySummary(Entry entry) {
        GunAmmoSupply supply = entry.ammoSupply() == null ? ammoDefaults(entry) : entry.ammoSupply();
        return supply == null ? Texts.tr("screen.tacz_pubg.loot_config.waiting_default_ammo_supplies") : Texts.tr("screen.tacz_pubg.loot_config.rounds_per_group_choose_one_three", supply.roundsPerGroup());
    }

    private String ammoSupplyDetails(Entry entry) {
        GunAmmoSupply supply = entry.ammoSupply() == null ? ammoDefaults(entry) : entry.ammoSupply();
        if (supply == null) return Texts.tr("screen.tacz_pubg.loot_config.load_catalog_read_defaults");
        StringBuilder details = new StringBuilder(ammoSupplySummary(entry))
                .append(entry.ammoSupply() == null ? Texts.tr("screen.tacz_pubg.loot_config.use_gun_s_default_ammo_supplies") : Texts.tr("screen.tacz_pubg.loot_config.use_gun_s_custom_ammo_supplies"));
        for (int index = 0; index < supply.ammoOptions().size(); index++) {
            var option = supply.ammoOptions().get(index);
            var choice = choices.get("ammo:" + option.ammoId());
            String label = option.ammoId().equals("self") ? Texts.tr("screen.tacz_pubg.gun_ammo_supply.follow_current_ammo")
                    : choice == null ? option.ammoId() : choice.preview().getHoverName().getString() + " · " + option.ammoId();
            details.append(Texts.tr("screen.tacz_pubg.loot_config.loadout")).append(index + 1).append("：").append(label)
                    .append(" · ").append(option.groups()).append(Texts.tr("screen.tacz_pubg.loot_config.groups"))
                    .append((long) option.groups() * supply.roundsPerGroup()).append(Texts.tr("screen.tacz_pubg.loot_config.rounds"))
                    .append(" · ").append(percentText(option.probability())).append('%');
        }
        return details.toString();
    }
    private String attachmentSupportLabel(Entry entry) {
        var choice = choices.get(entry.key());
        return !entry.kind().equals("attachment") || choice == null ? ""
                : Texts.tr("screen.tacz_pubg.loot_config.compatible_guns", choice.attachmentInfo().supportedGunCount(), (choice.attachmentInfo().specialist() ? Texts.tr("screen.tacz_pubg.loot_config.dedicated_attachment") : ""));
    }
    private String name(Entry entry) { ItemStack stack = preview(entry); return stack.isEmpty() ? entry.id() : stack.getHoverName().getString(); }
    private String fit(String value, int pixels) { return font.plainSubstrByWidth(Texts.resolve(value), Math.max(0, pixels)); }
    private void line(String text, int x, int y, int maxWidth, int color) { lines.add(new Line(text, x, y, maxWidth, color)); }
    private <T extends AbstractWidget> T fixed(T widget) { fixedWidgets.add(widget); return addRenderableWidget(widget); }
    private Button fixedButton(String text, int x, int y, int width, Runnable action) {
        return fixed(ConfigUi.button(Texts.component(text), ignored -> action.run()).bounds(x, y, width, 20).build());
    }
    private <T extends AbstractWidget> T pageWidget(T widget, int logicalY, boolean enabled) {
        pageWidgets.add(new PageWidget(widget, logicalY, enabled)); return addWidget(widget);
    }
    private Button button(String text, int x, int y, int width, Runnable action) {
        return pageWidget(ConfigUi.button(Texts.component(text), ignored -> action.run())
                .bounds(x, y, Math.max(20, width), 20).build(), y, true);
    }
    private EditBox edit(int x, int y, int width, String value, Consumer<String> responder, int length, String label) {
        EditBox box = ConfigUi.editBox(font, x, y, width, 20, Texts.component(label));
        box.setMaxLength(length); box.setValue(Texts.resolve(value)); box.setResponder(text -> { responder.accept(text); weightViewDocument = null; });
        return pageWidget(box, y, true);
    }
    private final class ItemList extends ObjectSelectionList<ItemRow> {
        private boolean populating;
        ItemList(int width, int height, int top) { super(LootConfigScreen.this.minecraft,width,height,top,32); }
        ItemRow hoveredRow() { return getHovered(); }
        void refill() {
            populating = true;
            clearEntries();
            for (int i = 0; i < draft.items().size(); i++) {
                LootConfigModel.Entry entry = draft.items().get(i);
                if ((poolFilter == null || categoryOf(entry) == poolFilter)
                        && CatalogSearch.matches(query,entry.kind(),entry.id(),name(entry))) {
                    ItemRow row = new ItemRow(entry,i); addEntry(row);
                    if (i == selected) setSelected(row);
                }
            }
            populating = false;
        }
        @Override public void setSelected(ItemRow row) {
            super.setSelected(row);
            if (!populating && row != null) pendingSelection = row.index;
        }
        @Override public int getRowWidth() { return width - 20; }
        @Override protected int getScrollbarPosition() { return getX() + width - 6; }
    }

    private final class ItemRow extends ObjectSelectionList.Entry<ItemRow> {
        final Entry item; final int index;
        ItemRow(Entry item,int index) { this.item=item; this.index=index; }
        @Override public Component getNarration() { return Texts.component(itemDetails(item)); }
        @Override public boolean mouseClicked(double x,double y,int button) { if (button != 0) return false; pendingSelection = index; return true; }
        @Override public void render(GuiGraphics graphics,int rowIndex,int y,int x,int rowWidth,int rowHeight,int mouseX,int mouseY,boolean hovered,float partialTick) {
            ItemStack stack = preview(item);
            if (!stack.isEmpty()) graphics.renderItem(stack,x+3,y+6);
            double weight = effectiveWeight(item);
            graphics.drawString(font,Texts.resolve(fit(name(item),rowWidth-28)),x+24,y+3,weight>0?0xFFFFFFFF:0xFF999999);
            graphics.drawString(font,Texts.resolve(fit(Texts.tr("screen.tacz_pubg.loot_config.effective_weight.render", weight, item.id()),rowWidth-28)),x+24,y+16,0xFF89C7FF);
        }
    }

    private static final class CategoryForm {
        final Map<String, String> values = new HashMap<>();
        CategoryForm(Map<String, Double> probabilities) {
            for (LootCategory category : LootCategory.values()) values.put(category.id(),
                    java.math.BigDecimal.valueOf(probabilities.get(category.id())).movePointRight(2).stripTrailingZeros().toPlainString());
        }
        Map<String, Double> parse() {
            Map<String, Double> probabilities = new HashMap<>();
            for (LootCategory category : LootCategory.values()) probabilities.put(category.id(),
                    decimal(values.get(category.id()), category.label() + Texts.tr("screen.tacz_pubg.loot_config.category_probability"), 0, 100) / 100);
            return Map.copyOf(probabilities);
        }
        boolean equalsValues(Map<String, Double> probabilities) { return values.equals(new CategoryForm(probabilities).values); }
    }

    private static String percentText(double probability) {
        return new java.math.BigDecimal(String.format(Locale.ROOT, "%.6f", probability * 100)).stripTrailingZeros().toPlainString();
    }

    private static final class ItemForm {
        final Entry original;
        String weight,minimum,maximum;
        ItemForm(Entry entry) { original=entry; weight=Double.toString(entry.weight()); minimum=Integer.toString(entry.minCount()); maximum=Integer.toString(entry.maxCount()); }
        Entry parse() { return new Entry(original.kind(),original.id(),integer(minimum,Texts.tr("screen.tacz_pubg.loot_config.minimum_quantity"),1,Integer.MAX_VALUE),integer(maximum,Texts.tr("screen.tacz_pubg.loot_config.maximum_quantity"),1,Integer.MAX_VALUE),decimal(weight,Texts.tr("screen.tacz_pubg.loot_config.spawn_weight"),0,1_000_000),original.ammoSupply()); }
        boolean equalsValues() { ItemForm other=new ItemForm(original); return weight.equals(other.weight)&&minimum.equals(other.minimum)&&maximum.equals(other.maximum); }
    }

    private static final class GeneralForm {
        String minimum,maximum,chance,yTolerance,spacing,density,radiusMin,radiusMax,attachmentChance,attachmentKinds;
        ZoneSettings zoneSettings;
        GunSelection gunSelection;
        OperatorPermissions operatorPermissions;
        GeneralForm(General g) {
            zoneSettings = g.zoneSettings();
            gunSelection = g.gunSelection();
            operatorPermissions = g.operatorPermissions();
            minimum=Integer.toString(g.minItemsPerPlane()); maximum=g.maxItemsPerPlane()==Integer.MAX_VALUE?"0":Integer.toString(g.maxItemsPerPlane());
            chance=Double.toString(g.planeSpawnChance()*100); yTolerance=Double.toString(g.samePlaneYTolerance());
            spacing=Double.toString(g.minItemSpacing()); density=Double.toString(g.lootDensity());
            radiusMin=Double.toString(g.ammoRadiusMin()); radiusMax=Double.toString(g.ammoRadiusMax());
            attachmentChance=Double.toString(g.companionAttachmentChance()*100);
            attachmentKinds=Integer.toString(g.companionAttachmentKinds());
        }
        General parse() {
            return parse(gunSelection);
        }
        General parse(GunSelection selection) {
            int min=integer(minimum,Texts.tr("screen.tacz_pubg.loot_config.minimum_item_count"),0,Integer.MAX_VALUE), max=integer(maximum,Texts.tr("screen.tacz_pubg.loot_config.item_limit"),0,Integer.MAX_VALUE);
            if(max==0) max=Integer.MAX_VALUE;
            if(min>max) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.loot_config.minimum_count_not_exceed_limit"));
            double low=decimal(radiusMin,Texts.tr("screen.tacz_pubg.loot_config.minimum_ammo_radius"),0,64), high=decimal(radiusMax,Texts.tr("screen.tacz_pubg.loot_config.maximum_ammo_radius"),0,64);
            if(low>high) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.loot_config.minimum_ammo_radius_not_exceed_maximum"));
            return new General(max,decimal(chance,Texts.tr("screen.tacz_pubg.loot_config.surface_probability"),0,100)/100,min,decimal(yTolerance,Texts.tr("screen.tacz_pubg.loot_config.y_tolerance"),0,2),
                    decimal(spacing,Texts.tr("screen.tacz_pubg.loot_config.item_spacing"),0,64),decimal(density,Texts.tr("screen.tacz_pubg.loot_config.loot_density"),0,1000),low,high,
                    decimal(attachmentChance,Texts.tr("screen.tacz_pubg.loot_config.attachment_probability"),0,100)/100,integer(attachmentKinds,Texts.tr("screen.tacz_pubg.loot_config.attachment_types_b"),0,16),zoneSettings,selection,operatorPermissions);
        }
        boolean equalsValues(General g) {
            GeneralForm other=new GeneralForm(g);
            return minimum.equals(other.minimum)&&maximum.equals(other.maximum)&&chance.equals(other.chance)
                    &&yTolerance.equals(other.yTolerance)&&spacing.equals(other.spacing)&&density.equals(other.density)
                    &&radiusMin.equals(other.radiusMin)&&radiusMax.equals(other.radiusMax)
                    &&attachmentChance.equals(other.attachmentChance)&&attachmentKinds.equals(other.attachmentKinds)
                    &&zoneSettings.equals(other.zoneSettings)&&gunSelection.equals(other.gunSelection)
                    &&operatorPermissions.equals(other.operatorPermissions);
        }
    }
    private static int integer(String text,String name,int min,int max) {
        try { int n=Integer.parseInt(text.trim()); if(n<min||n>max) throw new NumberFormatException(); return n; }
        catch(NumberFormatException e) { throw new IllegalArgumentException(name+Texts.tr("screen.tacz_pubg.loot_config.integer")+min+"–"+max+Texts.tr("screen.tacz_pubg.gun_ammo_supply.your_input_was_preserved")); }
    }
    private static double decimal(String text,String name,double min,double max) {
        try { double n=Double.parseDouble(text.trim()); if(!Double.isFinite(n)||n<min||n>max) throw new NumberFormatException(); return n; }
        catch(NumberFormatException e) { throw new IllegalArgumentException(name+Texts.tr("screen.tacz_pubg.loot_config.integer")+min+"–"+max+Texts.tr("screen.tacz_pubg.loot_config.finite_input_retained")); }
    }
}
