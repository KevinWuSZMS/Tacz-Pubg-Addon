package com.example.examplemod.lootconfig.client;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.lootconfig.LootCatalog;
import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.lootconfig.LootConfigModel.AmmoOption;
import com.example.examplemod.lootconfig.LootConfigModel.GunAmmoSupply;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Three per-gun plans share one draw; this child page only changes the parent's draft. */
public final class GunAmmoSupplyScreen extends Screen {
    private final Screen parent;
    private final String gunName, gunId;
    private final List<LootCatalog.Choice> catalog;
    private final Map<String, LootCatalog.Choice> ammoChoices = new HashMap<>();
    private final Map<Integer, EditBox> groupFields = new HashMap<>();
    private final GunAmmoSupply defaults;
    private final Consumer<GunAmmoSupply> onApply;
    private final Draft draft;
    private List<String> originalValues, submittedValues;
    private boolean awaitingSave;
    private int page, left, panelWidth;
    private Button apply;

    public GunAmmoSupplyScreen(Screen parent, String gunName, String gunId, List<LootCatalog.Choice> catalog,
            GunAmmoSupply configured, GunAmmoSupply defaults, Consumer<GunAmmoSupply> onApply) {
        this(parent, gunName, gunId, catalog, configured, defaults, null, onApply);
    }

    GunAmmoSupplyScreen(Screen parent, String gunName, String gunId, List<LootCatalog.Choice> catalog,
            GunAmmoSupply configured, GunAmmoSupply defaults, Draft seed, Consumer<GunAmmoSupply> onApply) {
        super(Texts.of("screen.tacz_pubg.gun_ammo_supply.per_gun_ammo_supply"));
        this.parent = parent;
        this.gunName = gunName;
        this.gunId = gunId;
        this.catalog = List.copyOf(catalog);
        for (var choice : catalog) if (choice.kind().equals("ammo")) ammoChoices.put(choice.id(), choice);
        this.defaults = defaults;
        this.onApply = onApply;
        draft = seed == null ? new Draft(configured == null ? java.util.Objects.requireNonNull(defaults) : configured,
                configured == null) : seed.copy();
        originalValues = draft.values();
    }

    @Override protected void init() {
        panelWidth = Math.max(180, Math.min(620, width - 16));
        left = (width - panelWidth) / 2;
        groupFields.clear();
        Button reset = button(Texts.tr("screen.tacz_pubg.gun_ammo_supply.reset_defaults"), left + panelWidth - 96, 20, 88, () -> {
            draft.replace(defaults, true);
            page = 0;
            rebuildWidgets();
        });
        reset.active = defaults != null;
        reset.setTooltip(Tooltip.create(Texts.component(defaults == null
                ? Texts.tr("screen.tacz_pubg.gun_ammo_supply.reload_server_catalog_obtain_gun_s_defaults") : Texts.tr("screen.tacz_pubg.gun_ammo_supply.restore_three_default_loadouts_provided_by_server_saved"))));
        EditBox rounds = edit(left + 88, roundsY(), 64, draft.rounds, Texts.tr("screen.tacz_pubg.gun_ammo_supply.rounds_per_group"),
                value -> draft.rounds = value, 10);
        rounds.setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.gun_ammo_supply.rounds_per_group_one_loadout_selected_per_spawn", LootConfigModel.MAX_AMMO_ROUNDS_PER_GROUP)));

        int perPage = rowsPerPage();
        int pages = (LootConfigModel.AMMO_SUPPLY_CHOICES + perPage - 1) / perPage;
        page = Math.clamp(page, 0, pages - 1);
        for (int index = page * perPage; index < Math.min(LootConfigModel.AMMO_SUPPLY_CHOICES, (page + 1) * perPage); index++) {
            final int option = index;
            int y = rowsTop() + (index % perPage) * 25;
            int pickerX = left + 26, selfX = groupsX() - 44;
            Button picker = button(fit(ammoName(draft.ammoIds[index]), selfX - pickerX - 10),
                    pickerX, y, selfX - pickerX - 6, () -> pickAmmo(option));
            picker.setTooltip(Tooltip.create(Texts.component(ammoDetails(draft.ammoIds[index]) + Texts.tr("screen.tacz_pubg.gun_ammo_supply.click_search_loaded_ammunition"))));
            Button self = button(Texts.tr("screen.tacz_pubg.gun_ammo_supply.current"), selfX, y, 38, () -> {
                draft.ammoIds[option] = "self";
                draft.dynamicDefaults = false;
                rebuildWidgets();
            });
            self.setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.gun_ammo_supply.follow_current_ammo_use_ammunition_actually_loaded_gun")));
            groupFields.put(index, edit(groupsX(), y, 42, draft.groups[index], Texts.tr("screen.tacz_pubg.gun_ammo_supply.loadout_groups", (index + 1)),
                    value -> draft.groups[option] = value, 10));
            edit(probabilityX(), y, 52, draft.probabilities[index], Texts.tr("screen.tacz_pubg.gun_ammo_supply.loadout_chance", (index + 1)),
                    value -> draft.probabilities[option] = value, 24);
        }
        if (pages > 1) {
            button("<", left + 8, height - 90, 28, () -> { page--; rebuildWidgets(); }).active = page > 0;
            button(">", left + 102, height - 90, 28, () -> { page++; rebuildWidgets(); }).active = page + 1 < pages;
        }
        apply = button(Texts.tr("screen.tacz_pubg.configuration.save"), left + panelWidth - 108, height - 26, 100, this::apply);
        apply.setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.gun_ammo_supply.submit_gun_s_ammo_supply_current_configuration_close")));
        button(Texts.tr("screen.tacz_pubg.gun_ammo_supply.back"), left + 8, height - 26, 70, this::onClose);
        updateControls();
    }

    private int probabilityX() { return left + panelWidth - 60; }
    private int groupsX() { return probabilityX() - 48; }
    private int roundsY() { return height < 200 ? 33 : height < 220 ? 39 : 45; }
    private int rowsTop() { return rowsTopFor(height); }
    static int rowsTopFor(int height) { return height < 200 ? 66 : height < 220 ? 80 : 94; }
    private int rowsPerPage() { return rowsPerPageFor(height); }

    static int rowsPerPageFor(int height) {
        int top = rowsTopFor(height);
        if (top + 25 * (LootConfigModel.AMMO_SUPPLY_CHOICES - 1) + 20 <= height - 76)
            return LootConfigModel.AMMO_SUPPLY_CHOICES;
        return Math.clamp((height - 114 - top) / 25 + 1, 1, LootConfigModel.AMMO_SUPPLY_CHOICES);
    }

    private void pickAmmo(int option) {
        minecraft.setScreen(new ItemPickerScreen(this, catalog, choice -> {
            draft.ammoIds[option] = choice.id();
            draft.dynamicDefaults = false;
            rebuildWidgets();
        }, "ammo", Texts.of("screen.tacz_pubg.gun_ammo_supply.choose_supply_ammo")));
    }

    private String validationMessage() {
        try { draft.parse(ammoChoices.keySet()); return ""; }
        catch (IllegalArgumentException issue) { return issue.getMessage(); }
    }

    private void updateControls() {
        if (parent instanceof LootConfigScreen loot && awaitingSave && !loot.isSavingConfiguration()) {
            awaitingSave = false;
            if (loot.wasLastSaveSuccessful() && !loot.isConfigurationDirty()) originalValues = submittedValues;
        }
        if (apply != null) {
            boolean saving = parent instanceof LootConfigScreen loot && loot.isSavingConfiguration();
            apply.active = !saving && validationMessage().isEmpty();
            apply.visible = awaitingSave || !draft.values().equals(originalValues) || parent instanceof LootConfigScreen loot && loot.isConfigurationDirty();
            apply.setMessage(Texts.component(saving ? Texts.tr("screen.tacz_pubg.gun_ammo_supply.saving") : Texts.tr("screen.tacz_pubg.configuration.save")));
        }
        for (var field : groupFields.entrySet()) field.getValue().setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.gun_ammo_supply.loadout_rounds_group_count_integer_total_rounds_groups", (field.getKey() + 1), draft.totalRoundsText(field.getKey()), LootConfigModel.MAX_AMMO_GROUPS)));
    }

    private void apply() {
        GunAmmoSupply supply;
        try { supply = draft.parse(ammoChoices.keySet()); }
        catch (IllegalArgumentException issue) { updateControls(); return; }
        onApply.accept(draft.dynamicDefaults ? null : supply);
        if (parent instanceof LootConfigScreen loot) {
            submittedValues = draft.values(); loot.saveConfiguration(); awaitingSave = loot.isSavingConfiguration(); updateControls();
        } else { originalValues = draft.values(); minecraft.setScreen(parent); }
    }

    @Override public void onClose() {
        if (draft.values().equals(originalValues)) { minecraft.setScreen(parent); return; }
        minecraft.setScreen(new ConfirmScreen(discard -> minecraft.setScreen(discard ? parent : this),
                Texts.of("screen.tacz_pubg.gun_ammo_supply.discard_ammo_supply_changes_page"), Texts.of("screen.tacz_pubg.gun_ammo_supply.main_page_s_existing_configuration_draft_will_preserved"),
                Texts.of("screen.tacz_pubg.gun_ammo_supply.discard_page_changes"), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateControls(); super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, Texts.resolve(title), width / 2, 8, 0xFFFFFFFF);
        if (height >= 200) graphics.drawString(font, Texts.resolve(fit(gunName + " · " + gunId, panelWidth - 112)), left + 8, 25, 0xFFB5D9FF);
        graphics.drawString(font, Texts.of("screen.tacz_pubg.gun_ammo_supply.rounds_per_group").getString(), left + 8, roundsY() + 6, 0xFFFFFFFF);
        if (height >= 200) graphics.drawString(font, Texts.resolve(fit(draft.dynamicDefaults ? Texts.tr("screen.tacz_pubg.gun_ammo_supply.follow_gun_defaults") : Texts.tr("screen.tacz_pubg.gun_ammo_supply.custom_gun_loadouts"), panelWidth - 170)),
                left + 162, roundsY() + 6, 0xFFB5D9FF);
        if (height >= 220) graphics.drawString(font, Texts.resolve(fit(Texts.tr("screen.tacz_pubg.gun_ammo_supply.select_one_ammo_loadout_per_spawn_total_rounds"), panelWidth - 16)), left + 8, 69, 0xFFB5D9FF);
        int headerY = rowsTop() - 13;
        graphics.drawString(font, Texts.of("screen.tacz_pubg.gun_ammo_supply.loadout_ammo").getString(), left + 8, headerY, 0xFFB5D9FF);
        graphics.drawString(font, Texts.of("screen.tacz_pubg.gun_ammo_supply.groups").getString(), groupsX(), headerY, 0xFFB5D9FF);
        graphics.drawString(font, Texts.of("screen.tacz_pubg.gun_ammo_supply.chance").getString(), probabilityX(), headerY, 0xFFB5D9FF);
        int perPage = rowsPerPage();
        for (int index = page * perPage; index < Math.min(LootConfigModel.AMMO_SUPPLY_CHOICES, (page + 1) * perPage); index++)
            graphics.drawString(font, Texts.resolve(Integer.toString(index + 1)), left + 8, rowsTop() + (index % perPage) * 25 + 6, 0xFFFFFFFF);
        int pages = (LootConfigModel.AMMO_SUPPLY_CHOICES + perPage - 1) / perPage;
        if (pages > 1) graphics.drawCenteredString(font, Texts.resolve((page + 1) + "/" + pages), left + 69, height - 84, 0xFFFFFFFF);
        String issue = validationMessage();
        String totalRounds = Texts.tr("screen.tacz_pubg.gun_ammo_supply.loadout_totals_rounds", draft.totalRoundsText(0), draft.totalRoundsText(1), draft.totalRoundsText(2));
        graphics.drawString(font, Texts.resolve(fit(totalRounds, panelWidth - 16)), left + 8, height - 69, 0xFFB5D9FF);
        graphics.drawString(font, Texts.resolve(fit(Texts.tr("screen.tacz_pubg.gun_ammo_supply.total_probability_equal", draft.totalProbabilityText()), panelWidth - 16)), left + 8,
                height - 53, issue.isEmpty() ? 0xFF8FE0A0 : 0xFFFFC078);
        String status = issue.isEmpty() ? Texts.tr("screen.tacz_pubg.gun_ammo_supply.after_editing_click_save_configuration_wait_server_confirmation") : issue;
        graphics.drawString(font, Texts.resolve(fit(status, panelWidth - 16)), left + 8, height - 40, issue.isEmpty() ? 0xFFB5D9FF : 0xFFFF8888);
        if (!issue.isEmpty() && mouseY >= height - 44 && mouseY < height - 27)
            graphics.renderTooltip(font, font.split(Texts.component(issue), Math.max(160, panelWidth - 16)), mouseX, mouseY);
    }

    private String ammoName(String id) {
        if (id.equals("self")) return Texts.tr("screen.tacz_pubg.gun_ammo_supply.follow_current_ammo");
        var choice = ammoChoices.get(id);
        return choice == null ? Texts.tr("screen.tacz_pubg.gun_ammo_supply.catalog_needs_updating", id) : choice.preview().getHoverName().getString();
    }

    private String ammoDetails(String id) {
        return id.equals("self") ? Texts.tr("screen.tacz_pubg.gun_ammo_supply.use_ammunition_actually_used_by_gun_when_spawns") : ammoName(id) + "\n" + id;
    }

    private String fit(String value, int pixels) { return font.plainSubstrByWidth(Texts.resolve(value), Math.max(0, pixels)); }

    private Button button(String text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(ConfigUi.button(Texts.component(text), ignored -> action.run())
                .bounds(x, y, Math.max(20, width), 20).build());
    }

    private EditBox edit(int x, int y, int width, String value, String label, Consumer<String> change, int maxLength) {
        EditBox field = ConfigUi.editBox(font, x, y, width, 20, Texts.component(label));
        field.setMaxLength(maxLength);
        field.setValue(Texts.resolve(value));
        field.setResponder(text -> {
            change.accept(text);
            draft.dynamicDefaults = false;
            updateControls();
        });
        return addRenderableWidget(field);
    }

    /** Raw values survive resize and the picker; invalid values never leave this page. */
    static final class Draft {
        final String[] ammoIds = new String[LootConfigModel.AMMO_SUPPLY_CHOICES];
        final String[] groups = new String[LootConfigModel.AMMO_SUPPLY_CHOICES];
        final String[] probabilities = new String[LootConfigModel.AMMO_SUPPLY_CHOICES];
        String rounds;
        boolean dynamicDefaults;

        Draft(GunAmmoSupply supply, boolean dynamicDefaults) { replace(supply, dynamicDefaults); }

        Draft copy() {
            Draft copy = new Draft(GunAmmoSupply.defaults(1), dynamicDefaults);
            copy.rounds = rounds;
            System.arraycopy(ammoIds, 0, copy.ammoIds, 0, ammoIds.length);
            System.arraycopy(groups, 0, copy.groups, 0, groups.length);
            System.arraycopy(probabilities, 0, copy.probabilities, 0, probabilities.length);
            return copy;
        }

        void replace(GunAmmoSupply supply, boolean dynamicDefaults) {
            for (int index = 0; index < LootConfigModel.AMMO_SUPPLY_CHOICES; index++) {
                ammoIds[index] = supply.ammoOptions().get(index).ammoId();
                groups[index] = Integer.toString(supply.ammoOptions().get(index).groups());
                probabilities[index] = percentage(supply.ammoOptions().get(index).probability());
            }
            rounds = Integer.toString(supply.roundsPerGroup());
            this.dynamicDefaults = dynamicDefaults;
        }

        GunAmmoSupply parse(Set<String> loadedAmmo) {
            List<AmmoOption> options = new ArrayList<>();
            for (int index = 0; index < LootConfigModel.AMMO_SUPPLY_CHOICES; index++) {
                String id = ammoIds[index];
                if (!id.equals("self") && !loadedAmmo.contains(id))
                    throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.gun_ammo_supply.loadout_ammo_catalog_needs_updating_choose_available_ammo", (index + 1)));
                options.add(new AmmoOption(id, integer(groups[index], Texts.tr("screen.tacz_pubg.gun_ammo_supply.loadout_groups", (index + 1)), LootConfigModel.MAX_AMMO_GROUPS),
                        parsePercentage(probabilities[index], Texts.tr("screen.tacz_pubg.gun_ammo_supply.loadout_probability", (index + 1)))));
            }
            double total = options.stream().mapToDouble(AmmoOption::probability).sum();
            if (Math.abs(total - 1) > LootConfigModel.AMMO_PROBABILITY_TOLERANCE)
                throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.gun_ammo_supply.three_loadout_probabilities_total_your_input_was_preserved"));
            GunAmmoSupply supply = new GunAmmoSupply(options,
                    integer(rounds, Texts.tr("screen.tacz_pubg.gun_ammo_supply.rounds_per_group"), LootConfigModel.MAX_AMMO_ROUNDS_PER_GROUP));
            List<String> issues = LootConfigModel.validateGunAmmoSupply(supply);
            if (!issues.isEmpty()) throw new IllegalArgumentException(String.join("；", issues));
            return supply;
        }

        List<String> values() {
            List<String> values = new ArrayList<>();
            values.add(Boolean.toString(dynamicDefaults));
            values.add(rounds);
            for (int index = 0; index < LootConfigModel.AMMO_SUPPLY_CHOICES; index++) {
                values.add(ammoIds[index]);
                values.add(groups[index]);
                values.add(probabilities[index]);
            }
            return List.copyOf(values);
        }

        String totalProbabilityText() {
            try {
                double total = 0;
                for (String value : probabilities) total += parsePercentage(value, Texts.tr("screen.tacz_pubg.gun_ammo_supply.loadout_probability.total_probability_text"));
                return BigDecimal.valueOf(total * 100).setScale(6, java.math.RoundingMode.HALF_UP)
                        .stripTrailingZeros().toPlainString();
            } catch (IllegalArgumentException issue) { return Texts.tr("screen.tacz_pubg.gun_ammo_supply.not_entered"); }
        }

        String totalRoundsText(int index) {
            try {
                return Long.toString((long) integer(rounds, Texts.tr("screen.tacz_pubg.gun_ammo_supply.rounds_per_group"), LootConfigModel.MAX_AMMO_ROUNDS_PER_GROUP)
                        * integer(groups[index], Texts.tr("screen.tacz_pubg.gun_ammo_supply.groups"), LootConfigModel.MAX_AMMO_GROUPS));
            } catch (IllegalArgumentException issue) { return Texts.tr("screen.tacz_pubg.gun_ammo_supply.empty"); }
        }

        private static int integer(String value, String name, int max) {
            try {
                int count = Integer.parseInt(value.trim());
                if (count < 1 || count > max) throw new NumberFormatException();
                return count;
            } catch (NumberFormatException issue) {
                throw new IllegalArgumentException(name + Texts.tr("screen.tacz_pubg.gun_ammo_supply.integer") + max + Texts.tr("screen.tacz_pubg.gun_ammo_supply.your_input_was_preserved"));
            }
        }

        private static String percentage(double probability) {
            BigDecimal value = BigDecimal.valueOf(probability).movePointRight(2).stripTrailingZeros();
            String plain = value.toPlainString();
            return plain.length() <= 24 ? plain : value.toString();
        }

        private static double parsePercentage(String value, String name) {
            try {
                double percentage = Double.parseDouble(value.trim());
                if (!Double.isFinite(percentage) || percentage < 0 || percentage > 100) throw new NumberFormatException();
                return percentage / 100;
            } catch (NumberFormatException issue) {
                throw new IllegalArgumentException(name + Texts.tr("screen.tacz_pubg.gun_ammo_supply.finite_number_your_input_was_preserved"));
            }
        }
    }
}
