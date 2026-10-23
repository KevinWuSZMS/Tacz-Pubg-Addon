package com.example.examplemod.zones.match.client;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.lootconfig.client.LootConfigScreen;
import com.example.examplemod.zones.client.MapZoneConfigClient;
import com.example.examplemod.zones.match.MatchConfigModel;
import com.example.examplemod.zones.match.MatchConfigModel.Phase;
import com.example.examplemod.zones.match.MatchConfigModel.Settings;
import com.example.examplemod.zones.match.MatchConfigPayloads;
import com.example.examplemod.zones.match.MatchConfigPayloads.MapHeader;
import com.example.examplemod.zones.match.MatchConfigPayloads.Snapshot;
import com.example.examplemod.zones.match.MatchStatus;
import com.example.examplemod.zones.match.MatchRulePresets;
import com.example.examplemod.zones.match.lobby.LobbyModel;
import com.example.examplemod.zones.match.lobby.LobbyModel.Member;
import com.example.examplemod.zones.match.lobby.LobbyModel.Room;
import com.example.examplemod.zones.match.lobby.LobbyModel.View;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Scrollable native authoring. Local preview never activates a server match. */
public final class MatchConfigScreen extends Screen implements ConfigUi.DraftPage {
    private record Field(String key, String label, Supplier<String> value, Consumer<String> edit, boolean toggle, String hint) {}
    private record Enabled(AbstractWidget widget, BooleanSupplier allowed) {}
    private final Screen parent;
    private final List<Enabled> enabled = new ArrayList<>();
    private List<MapHeader> maps = List.of();
    private List<MatchConfigPayloads.RulePreset> rulePresets = List.of();
    private int wizardStep;
    private final MatchRuleDraft ruleDraft = new MatchRuleDraft();
    private String requestedPresetName = "";
    private long mapsGeneration = -1;
    private boolean saveSequence, startAfterSave;
    private Button saveConfiguration;
    private long revision = -1, latestRevision = -1, queuedMap;
    private RawSettings draft;
    private List<String> original;
    private MatchStatus status;
    private View roomView;
    private MatchLobbyDraft roomDraft;
    private Room roomOriginal;
    private long roomRevision = -1;
    private UUID selectedMember;
    private int selectedSeat = 1;
    private String defaultDifficulty = "normal", defaultLoadout = "scavenger", pendingKind = "read";
    private boolean roomPage, roomConflicted;
    private double roomScroll, rulesScroll;
    private FormList form;
    private MatchConfigLayout.Frame layout;
    private double scroll;
    private boolean pickingMap = true, pending, writing, statusOnlyPending, conflicted, error, preserveDraftOnRead, rebaseOnRead;
    private String message = Texts.tr("screen.tacz_pubg.match_config.reading_saved_maps_match_rules"), focusKey;
    private int focusCursor;

    public MatchConfigScreen(Screen parent) {
        super(Texts.of("screen.tacz_pubg.config_ui.create_match")); this.parent = parent; ConfigUi.remember(this, parent, 3);
    }
    Screen parent() { return parent; }
    long revision() { return revision; }
    long controlRevision() { return latestRevision >= 0 ? latestRevision : revision; }
    long lobbyRevision() { return roomRevision; }
    long mapId() { return draft == null ? 0 : draft.mapId; }
    boolean shouldPollStatus() { return mapId() > 0 && roomLocked(); }
    public boolean navigateConfigTab(int index) {
        if (index == 3) return true;
        if (blockingPending()) { showMessage(Texts.tr("screen.tacz_pubg.match_config.wait_server_confirmation_draft_retained"), true); return false; }
        rememberForm(); Screen root = ConfigUi.root(this);
        if (root instanceof LootConfigScreen loot) return loot.openConfigTab(index);
        if (index == 2) { MapZoneConfigClient.open(this); return true; }
        showMessage(Texts.tr("screen.tacz_pubg.match_config.page_was_opened_by_standalone_command_open_full"), true); return false;
    }
    /** Actual saved-map handoff, including a reused cached screen. */
    public void chooseSavedMap(long id) {
        if (id <= 0 || id == Long.MAX_VALUE) { showMessage(Texts.tr("screen.tacz_pubg.match_config.save_valid_map_first"), true); return; }
        if (pending) { queuedMap = id; showMessage(Texts.tr("screen.tacz_pubg.match_config.map_will_selected_after_request_completes_draft_retained", id), false); return; }
        if (id == mapId()) { showMessage(Texts.tr("screen.tacz_pubg.match_config.selected_map_click_next_configure_rules", id), false); return; }
        confirmDiscard(Texts.tr("screen.tacz_pubg.match_config.discard_current_match_draft_select_map", id), () -> {
            pickingMap = true; resetScroll(); preserveDraftOnRead = rebaseOnRead = false; MatchConfigClient.request(this, id);
        });
    }
    /** Explicit return/re-entry refresh; never called from init. */
    public void onMapEditorReturned() {
        if (pending || !maps.isEmpty() || draft != null) return;
        preserveDraftOnRead = false; rebaseOnRead = false; MatchConfigClient.request(this, mapId());
    }
    @Override protected void init() {
        rememberForm(); enabled.clear(); layout = MatchConfigLayout.forViewport(width, height);
        List<Button> tabs = ConfigUi.tabs(width, 3, this::navigateConfigTab);
        for (int i = 0; wizardStep == 0 && i < tabs.size(); i++) {
            final int index = i; Button tab = addRenderableWidget(tabs.get(i));
            enabled.add(new Enabled(tab, () -> index != 3 && !blockingPending()));
        }
        form = addRenderableWidget(new FormList(layout.form()));
        if (wizardStep == 0 || draft == null) mapChoices(); else editRows();
        form.setScrollAmount(scroll); footer(); restoreFocus(); updateEnabled();
        if (!pending && mapsGeneration >= 0 && mapsGeneration != MatchConfigClient.mapGeneration()) {
            preserveDraftOnRead = draft != null; rebaseOnRead = false; MatchConfigClient.request(this, mapId());
        }
    }
    private void rememberForm() {
        if (form == null) return;
        scroll = form.getScrollAmount();
        for (FormRow row : form.children()) for (int i = 0; i < row.widgets.size(); i++)
            if (row.widgets.get(i) instanceof EditBox box && box.isFocused()) { focusKey = row.keys.get(i); focusCursor = box.getCursorPosition(); }
    }
    private void forgetFocus() {
        focusKey = null;
        if (form != null) for (FormRow row : form.children()) for (AbstractWidget widget : row.widgets) widget.setFocused(false);
    }
    private void resetScroll() { scroll = 0; if (form != null) form.setScrollAmount(0); forgetFocus(); }
    private void restoreFocus() {
        if (focusKey == null) return;
        for (FormRow row : form.children()) for (int i = 0; i < row.widgets.size(); i++)
            if (focusKey.equals(row.keys.get(i)) && row.widgets.get(i) instanceof EditBox box) {
                form.setFocused(row); row.setFocused(box); box.setFocused(true);
                box.setCursorPosition(Math.min(focusCursor, box.getValue().length())); setFocused(form); return;
            }
    }
    private boolean blockingPending() { return pending && !statusOnlyPending; }
    private boolean canEdit() { return draft != null && !blockingPending() && !roomLocked(); }
    private boolean canEditRoom() { return roomDraft != null && !blockingPending() && !roomLocked(); }
    private boolean roomLocked() { return running() || roomView != null && List.of("spawning", "running").contains(roomView.stage()); }
    private boolean lobbyDirty() { return roomDraft != null && !roomDraft.room().equals(roomOriginal); }
    private void switchPage(boolean room) {
        if (room == roomPage) return;
        rememberForm(); if (roomPage) roomScroll = scroll; else rulesScroll = scroll;
        roomPage = room; scroll = room ? roomScroll : rulesScroll; forgetFocus(); rebuildWidgets();
    }
    private void updateEnabled() {
        for (Enabled binding : enabled) binding.widget.active = binding.allowed.getAsBoolean();
        if (saveConfiguration != null) {
            saveConfiguration.visible = hasUnsavedChanges() || saveSequence;
            saveConfiguration.setMessage(Texts.component(saveSequence ? Texts.tr("screen.tacz_pubg.gun_ammo_supply.saving")
                    : wizardStep == 1 ? Texts.tr("screen.tacz_pubg.match_config.save_rule") : Texts.tr("screen.tacz_pubg.configuration.save")));
        }
    }
    @Override public void tick() { super.tick(); updateEnabled(); }
    private void mapChoices() {
        section(Texts.tr("screen.tacz_pubg.match_config.select_saved_map"), Texts.tr("screen.tacz_pubg.match_config.match_rules_saved_independently_each_map"));
        if (maps.isEmpty()) { text(Texts.tr("screen.tacz_pubg.match_config.save_map_using_create_map_top_then_return"), 0xffb5d9ff); text(Texts.tr("screen.tacz_pubg.match_config.map_list_comes_current_server"), 0xffc3cbd5); }
        for (MapHeader map : maps) {
            FormRow row = new FormRow();
            Button choose = row.button("map-" + map.id(), (map.id() == mapId() ? "✓ " : "") + "#" + map.id() + " " + map.name(),
                    () -> chooseSavedMap(map.id()), () -> !blockingPending(), Texts.tr("screen.tacz_pubg.match_config.dimension_initial_safe_zone_generated_when_match_starts", map.dimension()));
            row.arrange = (x, y, w) -> choose.setRectangle(w, 20, x, y + 4); form.add(row);
        }
    }
    private void ruleChoices() {
        section(Texts.tr("screen.tacz_pubg.match_config.rule_presets"), Texts.tr("screen.tacz_pubg.match_config.select_rule_to_edit"));
        if (rulePresets.isEmpty()) text(Texts.tr("screen.tacz_pubg.match_config.no_saved_rules"), 0xffb5d9ff);
        for (var preset : rulePresets) {
            FormRow row = new FormRow();
            Button choose = row.button("rule-" + preset.name(),
                    (ruleDraft.selected() && preset.name().equals(ruleDraft.savedName()) ? "✓ " : "") + preset.name(),
                    () -> selectRule(preset.name()), this::canEdit, Texts.tr("screen.tacz_pubg.match_config.select_rule_to_edit"));
            Button delete = row.button("rule-delete-" + preset.name(), Texts.tr("screen.tacz_pubg.map_config.delete"),
                    () -> deleteRule(preset.name()), () -> canEdit() && !conflicted,
                    Texts.tr("screen.tacz_pubg.match_config.delete_rule_hint"));
            row.arrange = (x, y, w) -> {
                choose.setRectangle(w - 64, 20, x, y + 4);
                delete.setRectangle(60, 20, x + w - 60, y + 4);
            };
            form.add(row);
        }
        actions(List.of(Texts.tr("screen.tacz_pubg.match_config.new_rule")), List.of(this::newRule),
                List.of(() -> canEdit() && rulePresets.size() < MatchRulePresets.MAX_PRESETS));
    }
    private void selectRule(String name) {
        if (!canEdit() || ruleDraft.selected() && name.equals(ruleDraft.savedName())) return;
        confirmRuleDiscard(() -> {
            requestedPresetName = name; forgetFocus(); MatchConfigClient.loadPreset(this, name);
        });
    }
    private void newRule() {
        if (!canEdit() || rulePresets.size() >= MatchRulePresets.MAX_PRESETS) return;
        confirmRuleDiscard(() -> {
            draft = new RawSettings(MatchConfigModel.defaults(mapId()));
            draft.name = MatchRuleDraft.nextName(rulePresets.stream().map(MatchConfigPayloads.RulePreset::name).toList());
            ruleDraft.create(); applyLobbyRules(); forgetFocus(); rebuildWidgets();
            showMessage(Texts.tr("screen.tacz_pubg.match_config.new_rule_draft"), false);
        });
    }
    private void confirmRuleDiscard(Runnable action) {
        if (!ruleDirty()) { action.run(); return; }
        minecraft.setScreen(new ConfirmScreen(discard -> { minecraft.setScreen(this); if (discard) action.run(); },
                Texts.of("screen.tacz_pubg.match_config.discard_rule_changes"),
                Texts.of("screen.tacz_pubg.match_config.discard_rule_changes_hint"),
                Texts.of("screen.tacz_pubg.map_config.discard_continue"), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
    }
    private void deleteRule(String name) {
        if (!canEdit() || conflicted) return;
        minecraft.setScreen(new ConfirmScreen(confirm -> {
            minecraft.setScreen(this);
            if (confirm) { requestedPresetName = name; MatchConfigClient.deletePreset(this, name); }
        }, Texts.of("screen.tacz_pubg.match_config.delete_rule_confirm", name),
                Texts.of("screen.tacz_pubg.match_config.delete_rule_hint"),
                Texts.of("screen.tacz_pubg.map_config.delete"), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
    }
    private int applyLobbyRules() {
        if (roomDraft == null) return 0;
        int removed = roomDraft.applyRules(integer(draft.teamSize, Texts.tr("screen.tacz_pubg.match_config.team_size")), roomCapacity());
        selectedSeat = Math.clamp(selectedSeat, 1, roomCapacity());
        if (selectedMember != null && roomDraft.member(selectedMember) == null) selectedMember = null;
        return removed;
    }
    private void editRows() {
        section(currentMapLabel(), Texts.tr("screen.tacz_pubg.match_config.step", (wizardStep + 1)));
        if (roomPage) { roomRows(); return; }
        ruleChoices();
        if (!ruleDraft.selected()) return;
        section(Texts.tr("screen.tacz_pubg.match_config.basic_rules"), Texts.tr("screen.tacz_pubg.match_config.server_freezes_participant_roster_when_match_actually_starts"));
        fields(List.of(field("name", Texts.tr("screen.tacz_pubg.match_config.match_name"), () -> draft.name, s -> draft.name = s, Texts.tr("screen.tacz_pubg.match_config.characters_identifying_map_s_match_rules"))));
        actions(List.of(("1".equals(draft.teamSize) ? "✓ " : "") + Texts.tr("screen.tacz_pubg.match_config.solo"), ("2".equals(draft.teamSize) ? "✓ " : "") + Texts.tr("screen.tacz_pubg.match_config.duo"), ("4".equals(draft.teamSize) ? "✓ " : "") + Texts.tr("screen.tacz_pubg.match_config.squad")), List.of(() -> changeTeamSize(1), () -> changeTeamSize(2), () -> changeTeamSize(4)), List.of(this::canEdit, this::canEdit, this::canEdit));
        fields(List.of(field("maximum", Texts.tr("screen.tacz_pubg.match_config.total_participants"), () -> draft.maximum, s -> draft.maximum = s, Texts.tr("screen.tacz_pubg.match_config.independent_team_size_humans_bots_share_slots")),
                field("minimum", Texts.tr("screen.tacz_pubg.match_config.minimum_participants"), () -> draft.minimum, s -> draft.minimum = s, Texts.tr("screen.tacz_pubg.match_config.total_participants_verified_by_server_match_start")),
                field("preparation", Texts.tr("screen.tacz_pubg.match_config.preparation_time_seconds"), () -> draft.preparation, s -> draft.preparation = s, Texts.tr("screen.tacz_pubg.match_config.seconds_combat_time_limit_starts_after_preparation_ends")),
                field("limit", Texts.tr("screen.tacz_pubg.match_config.combat_time_limit_seconds"), () -> draft.limit, s -> draft.limit = s, Texts.tr("screen.tacz_pubg.match_config.seconds_cover_combined_duration_all_zone_phases")),
                field("disconnect", Texts.tr("screen.tacz_pubg.match_config.disconnect_grace_seconds"), () -> draft.disconnect, s -> draft.disconnect = s, Texts.tr("screen.tacz_pubg.match_config.eligibility_retained_during_grace_period_server_eliminates_player"))));
        fields(List.of(toggle("blue", Texts.tr("screen.tacz_pubg.match_config.enable_blue_zone"), () -> draft.blue, b -> draft.blue = b, Texts.tr("screen.tacz_pubg.match_config.controls_shrinking_blue_zone_damage_victory_combat_time")),
                toggle("autoEnd", Texts.tr("screen.tacz_pubg.match_config.automatic_victory"), () -> draft.autoEnd, b -> draft.autoEnd = b, Texts.tr("screen.tacz_pubg.match_config.server_determines_result_surviving_teams")),
                toggle("spectate", Texts.tr("screen.tacz_pubg.match_config.spectate_after_elimination"), () -> draft.spectate, b -> draft.spectate = b, Texts.tr("screen.tacz_pubg.match_config.elimination_restoration_performed_by_server")),
                toggle("friendlyFire", Texts.tr("screen.tacz_pubg.match_config.allow_friendly_fire"), () -> draft.friendlyFire, b -> draft.friendlyFire = b,
                        Texts.tr("screen.tacz_pubg.match_config.friendly_fire_also_respects_match_s_setting_vanilla")),
                toggle("block-drops", Texts.tr("screen.tacz_pubg.match_config.allow_block_drops"), () -> draft.allowBlockDrops,
                        b -> draft.allowBlockDrops = b, Texts.tr("screen.tacz_pubg.match_config.allow_block_drops_hint")),
                toggle("mob-drops", Texts.tr("screen.tacz_pubg.match_config.allow_mob_drops"), () -> draft.allowMobDrops,
                        b -> draft.allowMobDrops = b, Texts.tr("screen.tacz_pubg.match_config.allow_mob_drops_hint"))));
        fields(List.of(field("equipment-limit", Texts.tr("screen.tacz_pubg.match_config.equipment_limit"),
                () -> draft.equipmentLimit, s -> draft.equipmentLimit = s,
                Texts.tr("screen.tacz_pubg.match_config.equipment_limit_hint"))));
        section(Texts.tr("screen.tacz_pubg.match_config.flight_rules"), Texts.tr("screen.tacz_pubg.match_config.flight_rules_hint"));
        fields(List.of(toggle("flight-enabled", Texts.tr("screen.tacz_pubg.match_config.flight_enabled"), () -> draft.flightEnabled,
                        b -> draft.flightEnabled = b, Texts.tr("screen.tacz_pubg.match_config.flight_enabled_hint")),
                toggle("flight-random-route", Texts.tr("screen.tacz_pubg.match_config.flight_random_route"), () -> draft.flightRandomRoute,
                        b -> draft.flightRandomRoute = b, Texts.tr("screen.tacz_pubg.match_config.flight_random_route_hint")),
                flowField("flight-speed", "flight_speed", () -> draft.flightSpeed, s -> draft.flightSpeed = s),
                flowField("flight-height", "flight_height", () -> draft.flightHeight, s -> draft.flightHeight = s),
                flowField("flight-jump-delay", "flight_jump_delay", () -> draft.flightJumpDelay, s -> draft.flightJumpDelay = s),
                flowField("flight-auto-deploy", "flight_auto_deploy", () -> draft.flightAutoDeploy, s -> draft.flightAutoDeploy = s)));
        text(Texts.tr("screen.tacz_pubg.match_config.flight_duration_automatic"), 0xffb5d9ff);
        section(Texts.tr("screen.tacz_pubg.match_config.flow_rules"), Texts.tr("screen.tacz_pubg.match_config.flow_rules_hint"));
        fields(List.of(flowField("first-circle", "first_circle_delay", () -> draft.firstCircleDelay, s -> draft.firstCircleDelay = s),
                flowField("late-bias", "late_center_bias", () -> draft.lateCenterBias, s -> draft.lateCenterBias = s),
                flowField("exposure-grace", "exposure_grace", () -> draft.exposureGrace, s -> draft.exposureGrace = s),
                flowField("exposure-ramp", "exposure_ramp", () -> draft.exposureRamp, s -> draft.exposureRamp = s),
                flowField("exposure-multiplier", "exposure_multiplier", () -> draft.exposureMultiplier, s -> draft.exposureMultiplier = s)));
        section(Texts.tr("screen.tacz_pubg.match_config.match_bombardment_rules"), Texts.tr("screen.tacz_pubg.match_config.saved_match_rules_fixed_match_start"));
        fields(List.of(toggle("bomb-enabled", Texts.tr("screen.tacz_pubg.match_config.enable_bombardment"), () -> draft.bombEnabled, value -> draft.bombEnabled = value, Texts.tr("screen.tacz_pubg.match_config.use_match_s_bombardment_settings_during_combat")),
                field("bomb-ratio", Texts.tr("screen.tacz_pubg.match_config.bombardment_radius"), () -> draft.bombRatio, value -> draft.bombRatio = value, Texts.tr("screen.tacz_pubg.match_config.relative_half_map_s_shorter_side")),
                field("bomb-rate", Texts.tr("screen.tacz_pubg.match_config.missiles_per_minute"), () -> draft.bombRate, value -> draft.bombRate = value, Texts.tr("screen.tacz_pubg.match_config.missiles_launched_per_minute_match_s_bombardment_zone"))));
        fields(List.of(flowField("bomb-first", "bombing_first", () -> draft.bombFirst, s -> draft.bombFirst = s),
                flowField("bomb-warning", "bombing_warning", () -> draft.bombWarning, s -> draft.bombWarning = s),
                flowField("bomb-duration", "bombing_duration", () -> draft.bombDuration, s -> draft.bombDuration = s),
                flowField("bomb-cooldown", "bombing_cooldown", () -> draft.bombCooldown, s -> draft.bombCooldown = s),
                flowField("bomb-last-phase", "bombing_last_phase", () -> draft.bombLastPhase, s -> draft.bombLastPhase = s)));
        section(Texts.tr("screen.tacz_pubg.match_config.zone_phase", draft.phases.size(), MatchConfigModel.MAX_PHASES), Texts.tr("screen.tacz_pubg.match_config.radius_ratios_relative_previous_circle_standard_full_health"));
        actions(List.of(Texts.tr("screen.tacz_pubg.match_config.add_phase"), Texts.tr("screen.tacz_pubg.match_config.short_match_preset"), Texts.tr("screen.tacz_pubg.match_config.ranked_timing_reference")), List.of(this::addPhase,
                () -> preset(MatchConfigModel.shortTimingPreset(mapId()), Texts.tr("screen.tacz_pubg.match_config.short_match_suggestion")),
                () -> preset(MatchConfigModel.rankedTimingPreset(mapId()), Texts.tr("screen.tacz_pubg.match_config.pubg_ranked_timing_reference"))),
                List.of(() -> canEdit() && draft.phases.size() < MatchConfigModel.MAX_PHASES, this::canEdit, this::canEdit));
        actions(List.of(Texts.tr("screen.tacz_pubg.match_config.normal_timing_reference")),
                List.of(() -> preset(MatchConfigModel.normalTimingPreset(mapId()), Texts.tr("screen.tacz_pubg.match_config.pubg_normal_timing_reference"))),
                List.of(this::canEdit));
        boolean wide = form.getRowWidth() >= MatchConfigLayout.WIDE_PHASE_MIN;
        if (wide) {
            FormRow header = new FormRow(); header.painter = (g, x, y, w) -> {
                String[] labels = {Texts.tr("screen.tacz_pubg.match_config.zone"), Texts.tr("screen.tacz_pubg.match_config.wait_s"), Texts.tr("screen.tacz_pubg.match_config.shrink_s"), Texts.tr("screen.tacz_pubg.match_config.radius"), Texts.tr("screen.tacz_pubg.match_config.hp_s"), Texts.tr("screen.tacz_pubg.match_config.center"), ""}; var cells = MatchConfigLayout.phaseColumns(w);
                for (int i = 0; i < labels.length; i++) g.drawString(font, Texts.resolve(fit(labels[i], cells.get(i).width())), x + cells.get(i).x(), y + 10, 0xffa9c9dc);
            }; form.add(header);
        }
        for (int i = 0; i < draft.phases.size(); i++) phaseRows(i, draft.phases.get(i), wide);
        section(Texts.tr("screen.tacz_pubg.match_config.configuration_summary_static"), Texts.tr("screen.tacz_pubg.match_config.zone_phase_durations_radius_ratios")); dynamicText(this::timingLine, 0xffb5d9ff);
        text(Texts.tr("screen.tacz_pubg.match_config.disable_automatic_victory_solo_practice_avoid_immediate_single"), 0xffc3cbd5);
        text(Texts.tr("screen.tacz_pubg.match_config.teams_follow_lobby_slots_bombardment_zones_generated_match"), 0xffd8c393);
        section(Texts.tr("screen.tacz_pubg.match_config.server_runtime_status"), Texts.tr("screen.tacz_pubg.match_config.current_server_match_data"));
        runtimeActions();
        for (int i = 0; i < 7; i++) { final int line = i; dynamicText(() -> statusLines().size() > line ? statusLines().get(line) : "", 0xffdbe5ee); }
    }
    private void roomRows() {
        section(Texts.tr("screen.tacz_pubg.match_config.lobby_roster"), roomSummary());
        if (roomDraft == null) {
            text(Texts.tr("screen.tacz_pubg.match_config.read_map_s_lobby_before_arranging_participant_slots"), 0xffb5d9ff);
            actions(List.of(Texts.tr("screen.tacz_pubg.match_config.read_lobby")), List.of(() -> MatchConfigClient.readLobby(this, false)), List.of(() -> !pending));
            return;
        }
        dynamicText(this::roomSummary, 0xffffdc94);
        dynamicText(() -> Texts.tr("screen.tacz_pubg.match_config.roster_version", roomRevision, (lobbyDirty() ? Texts.tr("screen.tacz_pubg.map_config.unsaved") : Texts.tr("screen.tacz_pubg.match_config.saved")), (roomConflicted ? Texts.tr("screen.tacz_pubg.match_config.version_needs_review") : ""), lobbyStage()), 0xffb5d9ff);
        dynamicText(() -> hasUnsavedChanges() ? Texts.tr("screen.tacz_pubg.match_config.unsaved", (lobbyDirty() ? Texts.tr("screen.tacz_pubg.match_config.lobby_roster") : ""), (lobbyDirty() && dirty() ? "、" : ""), (dirty() ? Texts.tr("screen.tacz_pubg.match_config.match_rules") : ""))
                : Texts.tr("screen.tacz_pubg.match_config.rules_roster_saved_server_verifies_readiness_match_start"), 0xffd8c393);
        text(Texts.tr("screen.tacz_pubg.match_config.per_team_select_member_then_click_empty_slot", roomDraft.teamSize()), 0xffc3cbd5);
        section(Texts.tr("screen.tacz_pubg.match_config.new_bot"), Texts.tr("screen.tacz_pubg.match_config.bots_enter_without_equipment_collect_loot"));
        actions(List.of(Texts.tr("screen.tacz_pubg.match_config.difficulty", difficultyLabel(defaultDifficulty))),
                List.of(() -> { defaultDifficulty = cycle(defaultDifficulty, List.of("easy", "normal", "hard")); rebuildWidgets(); }),
                List.of(this::canEditRoom));
        actions(List.of(Texts.tr("screen.tacz_pubg.match_config.add_bot"), Texts.tr("screen.tacz_pubg.match_config.fill_bots"), Texts.tr("screen.tacz_pubg.match_config.clear_bots")),
                List.of(this::addBot, this::fillBots, () -> { roomDraft.removeBots(); selectedMember = null; rebuildWidgets(); }),
                List.of(() -> canAddBot() && vacantSeat() > 0, () -> canAddBot() && roomDraft.firstVacant(roomCapacity()) > 0,
                        () -> canEditRoom() && roomDraft.bots() > 0));
        if (roomView != null && !roomView.message().isBlank()) text(roomView.message(), "failed".equals(roomView.stage()) ? 0xffff9999 : 0xffb5d9ff);
        section(Texts.tr("screen.tacz_pubg.match_config.participant_slots"), Texts.tr("screen.tacz_pubg.match_config.gold_marks_selected_member_empty_slot"));
        int teamSize = roomDraft.teamSize();
        int visibleSeats = Math.max(roomCapacity(), roomDraft.room().members().stream().mapToInt(roomDraft::position).max().orElse(0));
        if (teamSize == 1) seatRows(1, visibleSeats, true);
        else for (int team = 1; (team - 1) * teamSize < visibleSeats; team++) {
            int first = (team - 1) * teamSize + 1, count = Math.min(teamSize, visibleSeats + 1 - first);
            long occupied = roomDraft.room().members().stream().filter(member -> member.team() == (first - 1) / teamSize + 1).count();
            section(Texts.tr("screen.tacz_pubg.match_config.team", String.format(java.util.Locale.ROOT, "%02d", team), String.format(java.util.Locale.ROOT, "%d", occupied), String.format(java.util.Locale.ROOT, "%d", count)), "");
            seatRows(first, count, false);
        }
        Member selected = selectedMember == null ? null : roomDraft.member(selectedMember);
        if (selected != null) {
            section(Texts.tr("screen.tacz_pubg.match_config.selected", selected.name()), Texts.tr("screen.tacz_pubg.match_config.team_slot", selected.team(), selected.slot()));
            if (LobbyModel.isBot(selected)) {
                actions(List.of(Texts.tr("screen.tacz_pubg.match_config.difficulty", difficultyLabel(selected.difficulty()))),
                        List.of(() -> editSelectedBot(true)), List.of(this::canEditRoom));
            }
            actions(List.of(Texts.tr("screen.tacz_pubg.match_config.remove_lobby"), Texts.tr("screen.tacz_pubg.item_picker.cancel_selection")), List.of(() -> {
                roomDraft.remove(selected.id()); selectedMember = null; rebuildWidgets();
            }, () -> { selectedMember = null; rebuildWidgets(); }), List.of(this::canEditRoom, () -> !blockingPending()));
        } else text(Texts.tr("screen.tacz_pubg.match_config.current_empty_slot_team_slot", ((selectedSeat - 1) / teamSize + 1), ((selectedSeat - 1) % teamSize + 1)), 0xffffdc94);
        section(Texts.tr("screen.tacz_pubg.match_config.online_players"), Texts.tr("screen.tacz_pubg.match_config.join_selected_empty_slot_server_checks_positions_game"));
        if (roomView == null || roomView.online().isEmpty()) text(Texts.tr("screen.tacz_pubg.match_config.online_player_list_needs_refreshing"), 0xffc3cbd5);
        else for (LobbyModel.OnlinePlayer player : roomView.online()) {
            Member member = roomDraft.member(player.id());
            actions(List.of((member == null ? "＋ " : Texts.tr("screen.tacz_pubg.match_config.lobby")) + player.name()), List.of(() -> {
                if (member != null) { selectedMember = member.id(); selectedSeat = roomDraft.position(member); rebuildWidgets(); return; }
                int position = vacantSeat();
                if (position < 1) { showMessage(Texts.tr("screen.tacz_pubg.match_config.lobby_slots_full_select_available_slot_first"), true); return; }
                roomDraft.add(new Member(player.id(), player.name(), "human", 1, 1, "normal", "scavenger"), position);
                selectedMember = player.id(); selectedSeat = position; rebuildWidgets();
            }), List.of(() -> canEditRoom() && (member != null || vacantSeat() > 0)));
            if (!player.message().isBlank()) text(player.message(), player.eligible() ? 0xffa6d8ad : 0xffd8c393);
        }
        section(Texts.tr("screen.tacz_pubg.match_config.server_runtime_status"), Texts.tr("screen.tacz_pubg.match_config.bots_deploy_participant_roster_freezes_match_start"));
        runtimeActions();
        for (int index = 0; index < 7; index++) { final int line = index; dynamicText(() -> statusLines().size() > line ? statusLines().get(line) : "", 0xffdbe5ee); }
    }
    private void seatRows(int first, int count, boolean showTeam) {
        int columns = MatchLobbyLayout.columns(form.getRowWidth());
        for (int offset = 0; offset < count; offset += columns) {
            FormRow row = new FormRow(); int amount = Math.min(columns, count - offset);
            for (int index = 0; index < amount; index++) {
                int position = first + offset + index; Member member = roomDraft.at(position);
                boolean selected = member == null ? selectedMember == null && selectedSeat == position : member.id().equals(selectedMember);
                String name = member == null ? Texts.tr("screen.tacz_pubg.match_config.empty") : (LobbyModel.isBot(member) ? Texts.tr("screen.tacz_pubg.match_config.bot") : "") + member.name();
                String label = (selected ? "■ " : "") + (showTeam ? Texts.tr("screen.tacz_pubg.match_config.team.seat_rows", String.format(java.util.Locale.ROOT, "%02d", position)) : ((position - 1) % roomDraft.teamSize() + 1) + " · ") + name;
                String hint = member == null ? Texts.tr("screen.tacz_pubg.match_config.select_empty_slot_move_selected_member_here") : member.name() + " · " + (LobbyModel.isBot(member)
                        ? difficultyLabel(member.difficulty()) + Texts.tr("screen.tacz_pubg.match_config.unarmed_scavenging") : Texts.tr("screen.tacz_pubg.match_config.human_player"));
                row.button("seat-" + position, label, () -> selectSeat(position), this::canEditRoom, hint)
                        .setMessage(Texts.component(label).withStyle(selected ? ChatFormatting.GOLD : member == null ? ChatFormatting.GRAY
                                : LobbyModel.isBot(member) ? ChatFormatting.GREEN : ChatFormatting.AQUA));
            }
            row.arrange = (x, y, w) -> {
                var cells = MatchLobbyLayout.seats(w, amount);
                for (int index = 0; index < amount; index++) { var cell = cells.get(index); row.widgets.get(index).setRectangle(cell.width(), 20, x + cell.x(), y + cell.y()); }
            };
            form.add(row);
        }
    }
    private void selectSeat(int position) {
        Member at = roomDraft.at(position);
        if (at != null) selectedMember = at.id();
        else if (selectedMember != null && roomDraft.member(selectedMember) != null) roomDraft.move(selectedMember, position);
        selectedSeat = position; rebuildWidgets();
    }
    private void changeTeamSize(int size) {
        if (!canEditRoom()) return;
        roomDraft.teamSize(size); draft.teamSize = Integer.toString(size); selectedSeat = Math.clamp(selectedSeat, 1, 100);
        showMessage(Texts.tr("screen.tacz_pubg.match_config.team_size_updated_save_roster_match_rules_before"), false); rebuildWidgets();
    }
    private void updateTeamSizeRaw(String value) {
        draft.teamSize = value;
        try {
            int size = Integer.parseInt(value.trim());
            if (roomDraft != null && size >= 1 && size <= 20) roomDraft.teamSize(size);
        } catch (NumberFormatException ignored) { /* The complete raw value remains available for correction. */ }
    }
    private int roomCapacity() {
        try { return Math.clamp(integer(draft.maximum, Texts.tr("screen.tacz_pubg.match_config.total_participants")), 1, 100); } catch (IllegalArgumentException error) { return 20; }
    }
    private int vacantSeat() {
        if (roomDraft == null) return 0;
        return selectedSeat <= roomCapacity() && roomDraft.at(selectedSeat) == null ? selectedSeat : roomDraft.firstVacant(roomCapacity());
    }
    private boolean canAddBot() { return canEditRoom() && roomView != null && roomView.botsAvailable() && roomDraft.bots() < 100; }
    private void addBot() {
        addBot(true);
    }
    private void addBot(boolean rebuild) {
        if (!canAddBot()) return;
        int position = vacantSeat(); if (position < 1) return;
        int index = 1;
        while (index <= 100) {
            String name = String.format(Locale.ROOT, "PUBG_Bot_%02d", index);
            if (roomDraft.room().members().stream().noneMatch(member -> member.name().equals(name))) break;
            index++;
        }
        Member bot = new Member(UUID.randomUUID(), String.format(Locale.ROOT, "PUBG_Bot_%02d", index), "bot", 1, 1, defaultDifficulty, defaultLoadout);
        roomDraft.add(bot, position); selectedMember = bot.id(); selectedSeat = position; if (rebuild) rebuildWidgets();
    }
    private void fillBots() {
        if (!canAddBot()) return;
        while (roomDraft.firstVacant(roomCapacity()) > 0 && roomDraft.bots() < 100) addBot(false);
        rebuildWidgets();
    }
    private void editSelectedBot(boolean difficulty) {
        Member member = roomDraft.member(selectedMember); if (member == null) return;
        roomDraft.botSettings(member.id(), difficulty ? cycle(member.difficulty(), List.of("easy", "normal", "hard")) : member.difficulty(),
                difficulty ? member.loadout() : cycle(member.loadout(), List.of("rifle", "smg", "scavenger"))); rebuildWidgets();
    }
    private static String cycle(String value, List<String> options) { return options.get((options.indexOf(value) + 1) % options.size()); }
    private static String difficultyLabel(String value) { return switch (value) { case "easy" -> Texts.tr("screen.tacz_pubg.match_config.easy"); case "hard" -> Texts.tr("screen.tacz_pubg.match_config.hard"); default -> Texts.tr("screen.tacz_pubg.match_config.normal"); }; }
    private static String loadoutLabel(String value) { return switch (value) { case "smg" -> Texts.tr("message.tacz_pubg.gun_class.smgs"); case "scavenger" -> Texts.tr("screen.tacz_pubg.match_config.scavenge"); default -> Texts.tr("message.tacz_pubg.gun_class.assault_rifles"); }; }
    private String roomSummary() {
        return roomDraft == null ? Texts.tr("screen.tacz_pubg.match_config.read_lobby_arrange_humans_bots") : Texts.tr("screen.tacz_pubg.match_config.participants_humans_bots_rules_players", roomDraft.size(), roomCapacity(), (roomDraft.size() - roomDraft.bots()), roomDraft.bots(), draft.minimum, roomCapacity());
    }
    private String lobbyStage() {
        if (roomView == null) return Texts.tr("screen.tacz_pubg.match_config.awaiting_read");
        return switch (roomView.stage()) { case "spawning" -> Texts.tr("screen.tacz_pubg.match_config.bots_ready", roomView.readyBots(), roomView.requestedBots());
            case "running" -> Texts.tr("screen.tacz_pubg.match_config.match_active"); case "failed" -> Texts.tr("screen.tacz_pubg.match_config.deployment_pending"); default -> Texts.tr("screen.tacz_pubg.match_config.awaiting_start"); };
    }
    private void runtimeActions() {
        actions(List.of(Texts.tr("screen.tacz_pubg.match_config.pause_match"), Texts.tr("screen.tacz_pubg.match_config.resume_match")),
                List.of(() -> MatchConfigClient.control(this, "pause"), () -> MatchConfigClient.control(this, "resume")),
                List.of(() -> !pending && running() && status != null && !"paused".equals(status.stage()),
                        () -> !pending && running() && status != null && "paused".equals(status.stage())));
    }
    private Field field(String key, String label, Supplier<String> value, Consumer<String> edit, String hint) {
        return new Field(key, label, value, edit, false, hint + Texts.tr("screen.tacz_pubg.match_config.input_retained_validated_save"));
    }
    private Field flowField(String key, String translation, Supplier<String> value, Consumer<String> edit) {
        String translationKey = "screen.tacz_pubg.match_config." + translation;
        return field(key, Texts.tr(translationKey), value, edit, Texts.tr(translationKey + "_hint"));
    }
    private Field toggle(String key, String label, Supplier<Boolean> value, Consumer<Boolean> edit, String hint) {
        return new Field(key, label, () -> Boolean.toString(value.get()), s -> edit.accept(Boolean.parseBoolean(s)), true, hint);
    }
    private void fields(List<Field> values) {
        int columns = form.getRowWidth() >= 460 ? 2 : 1;
        for (int offset = 0; offset < values.size(); offset += columns) {
            List<Field> group = values.subList(offset, Math.min(values.size(), offset + columns));
            FormRow row = new FormRow();
            for (Field field : group) {
                if (field.toggle) row.button(field.key, field.value.get().equals("true") ? Texts.tr("screen.tacz_pubg.match_config.enable") : Texts.tr("screen.tacz_pubg.common.disabled"), () -> {
                    field.edit.accept(Boolean.toString(!Boolean.parseBoolean(field.value.get()))); rebuildWidgets();
                }, this::canEdit, field.hint);
                else row.edit(field.key, field.value.get(), field.edit, field.hint, field.key.equals("name") ? 80 : 32);
            }
            row.arrange = (x, y, w) -> {
                var cells = MatchConfigLayout.fields(w, group.size());
                for (int i = 0; i < row.widgets.size(); i++) {
                    var b = cells.get(i).control(); row.widgets.get(i).setRectangle(b.width(), 20, x + b.x(), y + b.y());
                }
            };
            row.painter = (graphics, x, y, w) -> {
                var cells = MatchConfigLayout.fields(w, group.size());
                for (int i = 0; i < group.size(); i++) graphics.drawString(font, Texts.resolve(fit(group.get(i).label, cells.get(i).label().width())),
                        x + cells.get(i).label().x(), y + 10, 0xffe1e7ec);
            }; form.add(row);
        }
    }
    private void phaseRows(int index, RawPhase phase, boolean wide) {
        String key = "phase-" + index + "-";
        List<Field> values = List.of(field(key + "wait", Texts.tr("screen.tacz_pubg.match_config.wait_time_seconds"), () -> phase.wait, s -> phase.wait = s, Texts.tr("screen.tacz_pubg.match_config.delay_before_shrinking_phase")),
                field(key + "shrink", Texts.tr("screen.tacz_pubg.match_config.shrink_time_seconds"), () -> phase.shrink, s -> phase.shrink = s, Texts.tr("screen.tacz_pubg.match_config.greater_than_zero")),
                field(key + "ratio", Texts.tr("screen.tacz_pubg.match_config.radius_ratio"), () -> phase.ratio, s -> phase.ratio = s, Texts.tr("screen.tacz_pubg.match_config.relative_previous_circle")),
                field(key + "damage", Texts.tr("screen.tacz_pubg.match_config.damage_hp_s"), () -> phase.damage, s -> phase.damage = s, Texts.tr("screen.tacz_pubg.match_config.full_health_hp_hp_one_heart")));
        if (wide) {
            FormRow row = new FormRow();
            for (Field field : values) row.edit(field.key, field.value.get(), field.edit, field.label + "：" + field.hint, 32);
            row.button(key + "random", phase.randomCenter ? Texts.tr("screen.tacz_pubg.match_config.random") : Texts.tr("screen.tacz_pubg.match_config.concentric"), () -> { phase.randomCenter = !phase.randomCenter; rebuildWidgets(); }, this::canEdit,
                    Texts.tr("screen.tacz_pubg.match_config.server_picks_random_target_center_within_allowed_bounds"));
            row.button(key + "remove", "×", () -> removePhase(index), () -> canEdit() && draft.phases.size() > 1, Texts.tr("screen.tacz_pubg.match_config.remove_phase_least_one_remain"));
            row.arrange = (x, y, w) -> {
                var cells = MatchConfigLayout.phaseColumns(w);
                for (int i = 0; i < row.widgets.size(); i++) {
                    var b = cells.get(i + 1); row.widgets.get(i).setRectangle(b.width(), 20, x + b.x(), y + b.y());
                }
            };
            row.painter = (graphics, x, y, w) -> graphics.drawString(font, Texts.resolve(Integer.toString(index + 1)), x + 3, y + 10, 0xffffdc94);
            form.add(row);
        } else {
            FormRow title = new FormRow();
            Button remove = title.button(key + "remove", Texts.tr("screen.tacz_pubg.match_config.remove_phase"), () -> removePhase(index), () -> canEdit() && draft.phases.size() > 1, Texts.tr("screen.tacz_pubg.match_config.least_one_phase_remain"));
            title.arrange = (x, y, w) -> remove.setRectangle(76, 20, x + w - 76, y + 4);
            title.painter = (graphics, x, y, w) -> graphics.drawString(font, Texts.of("screen.tacz_pubg.match_config.phase", (index + 1)).getString(), x, y + 10, 0xffffdc94);
            form.add(title); fields(values);
            fields(List.of(toggle(key + "random", Texts.tr("screen.tacz_pubg.match_config.random_target_center"), () -> phase.randomCenter, b -> phase.randomCenter = b,
                    Texts.tr("screen.tacz_pubg.match_config.when_off_center_stays_fixed_target_circles_generated"))));
        }
    }
    private void addPhase() {
        if (!canEdit() || draft.phases.size() >= MatchConfigModel.MAX_PHASES) return;
        draft.phases.add(new RawPhase(MatchConfigModel.defaults(mapId()).phases().getFirst())); rebuildWidgets();
    }
    private void removePhase(int index) {
        if (!canEdit() || draft.phases.size() <= 1) return;
        draft.phases.remove(index); forgetFocus(); rebuildWidgets();
    }
    private void preset(Settings settings, String name) {
        if (!canEdit()) return;
        draft.replaceTiming(settings); forgetFocus();
        showMessage(Texts.tr("screen.tacz_pubg.match_config.zone_phases_replaced_other_drafts_retained_check_combat", name), false); rebuildWidgets();
    }
    private void section(String label, String note) {
        FormRow row = new FormRow(); row.painter = (g, x, y, w) -> {
            g.fill(x - 2, y + 2, x + w + 2, y + 25, 0x70334454);
            g.drawString(font, Texts.resolve(fit(label, w)), x + 3, y + 6, 0xffffe4a0);
            if (w > 320) g.drawString(font, Texts.resolve(fit(note, w - 12)), x + 3, y + 17, 0xff9faebe);
        }; form.add(row);
    }
    private void text(String value, int color) { dynamicText(() -> value, color); }
    private void dynamicText(Supplier<String> value, int color) {
        FormRow row = new FormRow(); row.painter = (g, x, y, w) -> g.drawString(font, Texts.resolve(fit(value.get(), w)), x, y + 10, color); form.add(row);
    }
    private void actions(List<String> labels, List<Runnable> actions, List<BooleanSupplier> conditions) {
        FormRow row = new FormRow();
        for (int i = 0; i < labels.size(); i++) row.button("action-" + labels.get(i), labels.get(i), actions.get(i), conditions.get(i), labels.get(i));
        row.arrange = (x, y, w) -> {
            int usable = w - (labels.size() - 1) * 4;
            for (int i = 0; i < row.widgets.size(); i++) {
                int left = usable * i / labels.size() + i * 4;
                int cell = usable * (i + 1) / labels.size() - usable * i / labels.size();
                row.widgets.get(i).setRectangle(cell, 20, x + left, y + 4);
            }
        }; form.add(row);
    }
    private void footer() {
        footerButton(Texts.tr("screen.tacz_pubg.loot_config.close"), new MatchConfigLayout.Rect(8, height - 28, 44, 20), this::onClose, () -> !blockingPending(), Texts.tr("screen.tacz_pubg.match_config.return_configuration_handle_unsaved_drafts_before_keeping_discarding"));
        if (wizardStep > 0 && !roomLocked()) footerButton(conflicted || roomConflicted ? Texts.tr("screen.tacz_pubg.loot_config.check_version") : Texts.tr("screen.tacz_pubg.visibility_config.reload"), new MatchConfigLayout.Rect(8, height - 52, 96, 20), () -> { if (conflicted || roomConflicted) rebase(); else reload(); }, () -> !pending && !roomLocked(), Texts.tr("screen.tacz_pubg.match_config.read_server_configuration_explicitly_confirm_before_replacing_draft"));
        int buttonWidth = Math.clamp((width - 190) / 2, 60, 100);
        int saveWidth = Math.min(100, Math.max(64, width - 2 * buttonWidth - 72));
        int saveX = width - buttonWidth * (wizardStep > 0 ? 2 : 1) - (wizardStep > 0 ? 16 : 12) - saveWidth;
        saveConfiguration = footerButton(Texts.tr("screen.tacz_pubg.configuration.save"), new MatchConfigLayout.Rect(Math.max(56, saveX), height - 28, saveWidth, 20),
                () -> saveAll(false), () -> !pending && !conflicted && !roomConflicted && !roomLocked() && draft != null,
                Texts.tr("screen.tacz_pubg.match_config.save_map_s_rules_roster_together_hides_after"));
        if (wizardStep > 0) footerButton(Texts.tr("screen.tacz_pubg.match_config.previous"), new MatchConfigLayout.Rect(width - buttonWidth * 2 - 12, height - 28, buttonWidth, 20),
                () -> setStep(wizardStep - 1), () -> !blockingPending(), Texts.tr("screen.tacz_pubg.match_config.return_previous_step_all_drafts_retained"));
        footerButton(wizardStep == 2 ? Texts.tr("screen.tacz_pubg.match_config.start_match") : Texts.tr("screen.tacz_pubg.match_config.next"), new MatchConfigLayout.Rect(width - buttonWidth - 8, height - 28, buttonWidth, 20),
                () -> { if (wizardStep == 2) saveAll(true); else setStep(wizardStep + 1); },
                () -> !pending && draft != null && (wizardStep != 1 || ruleDraft.selected()) && (wizardStep < 2 || !roomLocked() && !conflicted && !roomConflicted),
                wizardStep == 2 ? Texts.tr("screen.tacz_pubg.match_config.save_rules_roster_first_then_start_after_server") : Texts.tr("screen.tacz_pubg.match_config.continue_next_step_current_selections_drafts_retained"));
        if (roomLocked()) footerButton(Texts.tr("screen.tacz_pubg.match_config.end_match"), new MatchConfigLayout.Rect(8, height - 52, 96, 20), this::endMatch, () -> !pending, Texts.tr("screen.tacz_pubg.match_config.end_current_match_retain_configuration"));
    }
    private void setStep(int step) {
        if (step > 0 && draft == null) { showMessage(Texts.tr("screen.tacz_pubg.match_config.select_saved_map_first"), true); return; }
        if (step == 2) {
            if (!ruleDraft.selected()) { showMessage(Texts.tr("screen.tacz_pubg.match_config.select_rule_to_edit"), true); return; }
            try { validatedDraft(); applyLobbyRules(); } catch (IllegalArgumentException error) { showMessage(error.getMessage(), true); return; }
        }
        rememberForm(); if (wizardStep == 1) rulesScroll = scroll; else if (wizardStep == 2) roomScroll = scroll;
        wizardStep = Math.clamp(step, 0, 2); roomPage = wizardStep == 2; pickingMap = wizardStep == 0;
        scroll = wizardStep == 1 ? rulesScroll : wizardStep == 2 ? roomScroll : 0; forgetFocus(); rebuildWidgets();
    }
    private Button footerButton(String label, MatchConfigLayout.Rect b, Runnable action, BooleanSupplier condition, String hint) {
        Button button = addRenderableWidget(ConfigUi.button(Texts.component(label), ignored -> action.run()).bounds(b.x(), b.y(), b.width(), b.height()).build());
        button.setTooltip(Tooltip.create(Texts.component(hint))); enabled.add(new Enabled(button, condition)); return button;
    }
    private void saveAll(boolean start) {
        if (pending || draft == null) return;
        try { validatedDraft(); applyLobbyRules(); validatedLobby(); } catch (IllegalArgumentException error) { showMessage(error.getMessage(), true); return; }
        error = false; saveSequence = true; startAfterSave = start; continueSave();
    }
    private void continueSave() {
        if (!saveSequence || pending) return;
        if (conflicted || roomConflicted || error) { saveSequence = startAfterSave = false; updateEnabled(); return; }
        if (ruleDirty()) { MatchConfigClient.savePreset(this, ruleDraft.savedName()); return; }
        if (dirty()) { MatchConfigClient.save(this); return; }
        if (lobbyDirty()) { MatchConfigClient.saveLobby(this); return; }
        boolean start = startAfterSave; saveSequence = startAfterSave = false; updateEnabled();
        if (start) MatchConfigClient.control(this, "start");
    }
    private boolean running() { return status != null && status.mapId() == mapId() && List.of("preparing", "no_circle", "waiting", "shrinking", "final", "paused").contains(status.stage()); }
    private boolean hasSafeZone() { return maps.stream().anyMatch(map -> map.id() == mapId() && map.hasSafeZone()); }
    private boolean dirty() { return draft != null && (original == null || !draft.values().equals(original)); }
    private boolean ruleDirty() { return draft != null && ruleDraft.dirty(draft.values()); }
    @Override public boolean hasUnsavedChanges() { return dirty() || lobbyDirty() || ruleDirty(); }
    Settings validatedDraft() {
        if (draft == null) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.match_config.select_saved_map_first.validated_draft"));
        Settings settings = draft.model(); List<String> errors = MatchConfigModel.validate(settings);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors)); return settings;
    }
    Room validatedLobby() {
        if (roomDraft == null) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.match_config.read_lobby_roster_first"));
        Room room = roomDraft.room();
        if (room.members().stream().anyMatch(member -> roomDraft.position(member) > roomCapacity())) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.match_config.some_members_exceed_participant_limit_move_them_into", roomCapacity()));
        List<String> errors = LobbyModel.validate(room);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        return room;
    }
    private void confirmLobbyDiscard(Runnable action) {
        if (!lobbyDirty()) { action.run(); return; }
        minecraft.setScreen(new ConfirmScreen(confirmed -> { minecraft.setScreen(this); if (confirmed) action.run(); },
                Texts.of("screen.tacz_pubg.match_config.read_saved_lobby_roster"), Texts.of("screen.tacz_pubg.match_config.replaces_roster_draft_match_rule_input_retained"),
                Texts.of("screen.tacz_pubg.match_config.read_roster"), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
    }
    private void save() { if (!pending) MatchConfigClient.save(this); }
    private void reload() {
        if (pending) return;
        confirmDiscard(Texts.tr("screen.tacz_pubg.match_config.discard_page_s_match_draft_reload"), () -> { preserveDraftOnRead = rebaseOnRead = false; MatchConfigClient.request(this, mapId()); });
    }
    private void rebase() {
        if (pending || draft == null) return;
        minecraft.setScreen(new ConfirmScreen(confirm -> {
            minecraft.setScreen(this); if (confirm) { preserveDraftOnRead = rebaseOnRead = true; MatchConfigClient.request(this, mapId()); }
        }, Texts.of("screen.tacz_pubg.match_config.keep_draft_check_latest_server_version"), Texts.of("screen.tacz_pubg.match_config.keep_all_input_use_latest_saved_rules_as"),
                Texts.of("screen.tacz_pubg.match_config.read_keep_draft"), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
    }
    private void endMatch() {
        if (pending || !roomLocked()) return;
        boolean spawning = roomView != null && "spawning".equals(roomView.stage());
        minecraft.setScreen(new ConfirmScreen(confirm -> {
            minecraft.setScreen(this); if (confirm) MatchConfigClient.control(this, "end");
        }, Texts.component((spawning ? Texts.tr("screen.tacz_pubg.match_config.cancel_deployment_map") : Texts.tr("screen.tacz_pubg.match_config.end_match_map")) + mapId() + (spawning ? Texts.tr("screen.tacz_pubg.match_config.confirm_cancel_deployment_suffix") : Texts.tr("screen.tacz_pubg.match_config.confirm_end_suffix"))),
                Texts.component(spawning ? Texts.tr("screen.tacz_pubg.match_config.cancel_deployment_saved_rules_roster_page_s_draft") : Texts.tr("screen.tacz_pubg.match_config.stop_zone_phases_damage_end_match_page_s")),
                Texts.component(spawning ? Texts.tr("screen.tacz_pubg.match_config.cancel_deployment") : Texts.tr("screen.tacz_pubg.match_config.end_match")), Texts.of("screen.tacz_pubg.match_config.continue_game")));
    }
    private void confirmDiscard(String title, Runnable action) {
        if (!hasUnsavedChanges()) { action.run(); return; }
        minecraft.setScreen(new ConfirmScreen(discard -> { minecraft.setScreen(this); if (discard) action.run(); }, Texts.component(title),
                Texts.of("screen.tacz_pubg.match_config.discard_page_s_match_draft_keep_saved_maps"), Texts.of("screen.tacz_pubg.map_config.discard_continue"), Texts.of("screen.tacz_pubg.gun_ammo_supply.keep_editing")));
    }
    void setPending(boolean writing, boolean statusOnly, String kind) {
        pending = true; this.writing = writing; statusOnlyPending = statusOnly; pendingKind = kind; updateEnabled();
    }
    void requestFailed(String text) {
        pending = writing = statusOnlyPending = preserveDraftOnRead = rebaseOnRead = false;
        queuedMap = 0; saveSequence = startAfterSave = false; showMessage(text, true); updateEnabled();
    }
    void accept(Snapshot snapshot) {
        boolean wasStatus = statusOnlyPending;
        String kind = pendingKind;
        String oldRoomStage = roomView == null ? "" : roomView.stage();
        pending = writing = statusOnlyPending = false;
        maps = List.copyOf(snapshot.maps()); mapsGeneration = MatchConfigClient.mapGeneration();
        rulePresets = List.copyOf(snapshot.presets());
        if (snapshot.outcome() == MatchConfigPayloads.PRESET_LOADED && snapshot.settings() != null) {
            draft = new RawSettings(snapshot.settings()); draft.name = requestedPresetName;
            ruleDraft.acknowledge(requestedPresetName, draft.values());
            int removed = applyLobbyRules();
            showMessage(Texts.tr("screen.tacz_pubg.match_config.rule_preset_loaded_into_draft_save_configuration_apply")
                    + (removed > 0 ? Texts.tr("screen.tacz_pubg.match_config.excess_seats_removed", removed, roomCapacity()) : ""), false);
            forgetFocus(); rebuildWidgets(); processQueuedMap(); return;
        }
        if ("presetSave".equals(kind) && snapshot.outcome() != MatchConfigPayloads.FAILED && snapshot.outcome() != MatchConfigPayloads.CONFLICT) {
            revision = latestRevision = snapshot.revision();
            ruleDraft.acknowledge(draft.name, draft.values());
            showMessage(snapshot.message(), false); rebuildWidgets(); continueSave(); return;
        }
        if ("presetDelete".equals(kind) && snapshot.outcome() != MatchConfigPayloads.FAILED && snapshot.outcome() != MatchConfigPayloads.CONFLICT) {
            revision = latestRevision = snapshot.revision();
            if (requestedPresetName.equals(ruleDraft.savedName())) {
                ruleDraft.clear(); draft = new RawSettings(snapshot.settings()); original = draft.values(); forgetFocus();
            }
            showMessage(snapshot.message(), false); rebuildWidgets(); return;
        }
        if (snapshot.revision() >= 0) latestRevision = snapshot.revision();
        if (snapshot.status() == null || snapshot.status().mapId() == mapId()) status = snapshot.status();
        boolean roomChanged = acceptLobby(snapshot, kind);
        if (kind.startsWith("lobby")) {
            if (revision >= 0 && snapshot.revision() != revision) conflicted = true;
            String text = snapshot.message();
            if (snapshot.outcome() == MatchConfigPayloads.CONFLICT) text += Texts.tr("screen.tacz_pubg.match_config.roster_draft_retained_check_version");
            else if ("lobbyRebase".equals(kind) && snapshot.outcome() == MatchConfigPayloads.READ)
                text = Texts.tr("screen.tacz_pubg.match_config.latest_roster_read_as_comparison_baseline_slot_draft");
            showMessage(text, snapshot.outcome() == MatchConfigPayloads.FAILED || snapshot.outcome() == MatchConfigPayloads.CONFLICT);
            rebuildWidgets(); processQueuedMap(); continueSave(); return;
        }
        boolean keep = preserveDraftOnRead && snapshot.outcome() == MatchConfigPayloads.READ;
        boolean rebase = keep && rebaseOnRead; preserveDraftOnRead = rebaseOnRead = false;
        if (keep) {
            if (rebase && snapshot.settings() != null && snapshot.selectedMapId() == mapId()) {
                revision = snapshot.revision(); original = new RawSettings(snapshot.settings()).values(); conflicted = false;
                showMessage(Texts.tr("screen.tacz_pubg.match_config.latest_rules_read_as_comparison_baseline_original_draft"), false);
            } else {
                if (snapshot.revision() != revision || maps.stream().noneMatch(m -> m.id() == mapId())) conflicted = true;
                showMessage(Texts.tr("screen.tacz_pubg.match_config.map_list_updated_draft_retained", (conflicted ? Texts.tr("screen.tacz_pubg.match_config.version_map_changed_review_new_version_reload") : "")), conflicted);
            }
            rebuildWidgets();
        } else if (snapshot.outcome() == MatchConfigPayloads.CONFLICT) {
            if (snapshot.revision() != revision || "save".equals(kind) || !roomConflicted) conflicted = true;
            showMessage(snapshot.message() + Texts.tr("screen.tacz_pubg.match_config.draft_retained_check_corresponding_version"), true); updateEnabled();
        } else if (snapshot.outcome() == MatchConfigPayloads.FAILED) {
            showMessage(snapshot.message(), true); updateEnabled();
        } else if (snapshot.outcome() == MatchConfigPayloads.READ || snapshot.outcome() == MatchConfigPayloads.SAVED) {
            revision = snapshot.revision(); conflicted = false;
            if ("read".equals(kind)) ruleDraft.clear();
            if (snapshot.settings() != null) {
                draft = new RawSettings(snapshot.settings()); original = draft.values(); status = snapshot.status(); pickingMap = wizardStep == 0;
                if ("read".equals(kind) && queuedMap == 0) {
                    String preferred = ruleDraft.initialChoice(mapId(), snapshot.selectedMapId(), snapshot.preferredRuleName(),
                            rulePresets.stream().map(MatchConfigPayloads.RulePreset::name).toList());
                    if (!preferred.isEmpty()) {
                        if (preferred.equals(draft.name)) ruleDraft.acknowledge(preferred, draft.values());
                        else {
                            // Keep the saved map settings as the original comparison baseline.
                            // Starting still saves the selected last-played preset through the normal flow.
                            requestedPresetName = preferred; forgetFocus(); rebuildWidgets();
                            MatchConfigClient.loadPreset(this, preferred); return;
                        }
                    }
                }
            } else {
                draft = null; original = null; status = null;
                roomDraft = null; roomOriginal = null; roomView = null; roomRevision = -1;
                roomConflicted = false; selectedMember = null;
            }
            showMessage(snapshot.message(), false); rebuildWidgets();
        } else {
            // Runtime transitions update controls while the independent raw forms retain every input.
            boolean wasConflict = conflicted;
            if (revision >= 0 && snapshot.revision() != revision) conflicted = true;
            if (!wasConflict && conflicted) showMessage(Texts.tr("screen.tacz_pubg.match_config.server_template_version_changed_current_draft_retained_review"), true);
            else if (!wasStatus && !snapshot.message().isBlank()) showMessage(snapshot.message(), conflicted);
            boolean stageChanged = roomView != null && !oldRoomStage.equals(roomView.stage());
            if (!wasStatus && roomChanged && (roomPage || stageChanged) && !pickingMap) rebuildWidgets(); else updateEnabled();
        }
        processQueuedMap(); continueSave();
    }
    private void processQueuedMap() {
        if (queuedMap > 0) { long next = queuedMap; queuedMap = 0; if (next != mapId()) chooseSavedMap(next); }
    }
    private boolean acceptLobby(Snapshot snapshot, String kind) {
        View incoming = snapshot.lobby(); if (incoming == null) return false;
        boolean changed = !Objects.equals(roomView, incoming);
        roomView = incoming;
        boolean successful = snapshot.outcome() == MatchConfigPayloads.READ || snapshot.outcome() == MatchConfigPayloads.SAVED;
        boolean initialMap = roomDraft == null || roomDraft.room().mapId() != incoming.room().mapId();
        boolean replace = initialMap || successful && ("lobbySave".equals(kind) || "lobbyRead".equals(kind)
                || "read".equals(kind) && !preserveDraftOnRead);
        if (replace) {
            roomDraft = new MatchLobbyDraft(incoming.room()); roomOriginal = incoming.room(); roomRevision = incoming.revision();
            roomConflicted = false; selectedMember = null; selectedSeat = 1;
        } else if (successful && ("lobbyRebase".equals(kind) || "read".equals(kind) && rebaseOnRead)) {
            roomRevision = incoming.revision(); roomOriginal = incoming.room(); roomConflicted = false;
        } else if (incoming.revision() != roomRevision || "lobbySave".equals(kind) && snapshot.outcome() == MatchConfigPayloads.CONFLICT)
            roomConflicted = true;
        return changed;
    }
    private void showMessage(String text, boolean failure) { message = text == null ? "" : text; error = failure; }
    @Override public void onClose() {
        if (blockingPending()) { showMessage(Texts.tr("screen.tacz_pubg.match_config.wait_server_confirmation_draft_remains_after_timeout"), true); return; }
        confirmDiscard(Texts.tr("screen.tacz_pubg.match_config.discard_match_draft_return_configuration"), () -> {
            Screen root = ConfigUi.root(this); MatchConfigClient.closed(this); ConfigUi.forget(this);
            minecraft.setScreen(root instanceof LootConfigScreen ? root : parent);
        });
    }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { super.renderBackground(graphics, mouseX, mouseY, partialTick); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateEnabled(); super.render(graphics, mouseX, mouseY, partialTick);
        String line = Texts.tr("screen.tacz_pubg.match_config.map_version", (mapId() > 0 ? "#" + mapId() : Texts.tr("screen.tacz_pubg.spawn_area_client.awaiting_selection")), (revision < 0 ? Texts.tr("screen.tacz_pubg.match_config.awaiting_read") : revision), (hasUnsavedChanges() ? Texts.tr("screen.tacz_pubg.map_config.unsaved") : Texts.tr("screen.tacz_pubg.match_config.saved")), (conflicted || roomConflicted ? Texts.tr("screen.tacz_pubg.match_config.version_needs_review") : blockingPending() ? writing ? Texts.tr("screen.tacz_pubg.match_config.awaiting_confirmation") : Texts.tr("screen.tacz_pubg.match_config.reading") : ""));
        ConfigUi.renderHeader(graphics, font, width, Texts.tr("screen.tacz_pubg.config_ui.create_match"), line, conflicted || roomConflicted ? 0xffff8888 : 0xffb5d9ff);
        if (wizardStep > 0) {
            String[] steps = {Texts.tr("screen.tacz_pubg.match_config.select_map"), Texts.tr("screen.tacz_pubg.match_config.match_rules.render"), Texts.tr("screen.tacz_pubg.match_config.participants")};
            for (int i = 0; i < steps.length; i++) graphics.drawCenteredString(font, Texts.resolve(steps[i]), width * (i * 2 + 1) / 6, 42, wizardStep == i ? 0xff94e2ed : 0xff728496);
        }
        if (layout.hasPreview()) renderPreview(graphics, mouseX, mouseY);
        graphics.drawString(font, Texts.resolve(fit(message, width - 16)), 8, layout.messageY(), error ? 0xffff9999 : 0xffb3d9b6);
        if (mouseY >= layout.messageY() - 2 && mouseY < layout.firstFooterY() - 2 && !message.isBlank())
            graphics.renderTooltip(font, font.split(Texts.component(message), Math.max(160, width - 24)), mouseX, mouseY);
    }
    private String currentMapLabel() {
        return maps.stream().filter(m -> m.id() == mapId()).findFirst()
                .map(m -> "#" + m.id() + " " + m.name()).orElse(Texts.tr("screen.tacz_pubg.match_config.select_saved_map"));
    }
    private String timingLine() {
        if (draft == null) return Texts.tr("screen.tacz_pubg.match_config.select_saved_map_first");
        try { return Texts.tr("screen.tacz_pubg.match_config.zone_phases_preparation_s_phases", duration(draft.totalSeconds()), draft.preparation, draft.phases.size()); }
        catch (IllegalArgumentException error) { return Texts.tr("screen.tacz_pubg.match_config.complete_input_configuration_draft_retained"); }
    }
    private List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        if (roomView != null && "spawning".equals(roomView.stage())) {
            lines.add(Texts.tr("screen.tacz_pubg.match_config.current_status_deploying_bots"));
            lines.add(Texts.tr("screen.tacz_pubg.match_config.bots_ready.status_lines", roomView.readyBots(), roomView.requestedBots()));
            if (!roomView.message().isBlank()) lines.add(roomView.message());
            lines.add(Texts.tr("screen.tacz_pubg.match_config.roster_freezes_preparation_begins_once_all_bots_ready"));
            return lines;
        }
        if (status == null) { lines.add(Texts.tr("screen.tacz_pubg.match_config.current_status_awaiting_start")); lines.add(Texts.tr("screen.tacz_pubg.match_config.save_rules_first_then_click_start_match")); return lines; }
        boolean blueOff = "final".equals(status.stage()) && status.phase() == 0;
        lines.add(Texts.tr("screen.tacz_pubg.match_config.status", (blueOff ? Texts.tr("screen.tacz_pubg.match_config.blue_zone_off_combat") : stageLabel(status.stage()))));
        lines.add(Texts.tr("screen.tacz_pubg.match_config.phase_remaining", status.phase(), status.phaseCount(), duration(status.secondsRemaining())));
        lines.add(Texts.tr("screen.tacz_pubg.match_config.alive_teams", status.playersAlive(), status.playersTotal(), status.teamsAlive()));
        if (status.result() != null && !status.result().isBlank()) lines.add(Texts.tr("screen.tacz_pubg.match_config.result", status.result()));
        if (status.currentCircle() != null) lines.add(Texts.tr("screen.tacz_pubg.match_config.current_circle_x_z_r", String.format(java.util.Locale.ROOT, "%.0f", status.currentCircle().centerX()), String.format(java.util.Locale.ROOT, "%.0f", status.currentCircle().centerZ()), String.format(java.util.Locale.ROOT, "%.0f", status.currentCircle().radius())));
        if (status.targetCircle() != null) lines.add(Texts.tr("screen.tacz_pubg.match_config.target_circle_x_z_r", String.format(java.util.Locale.ROOT, "%.0f", status.targetCircle().centerX()), String.format(java.util.Locale.ROOT, "%.0f", status.targetCircle().centerZ()), String.format(java.util.Locale.ROOT, "%.0f", status.targetCircle().radius())));
        lines.add(Texts.tr("screen.tacz_pubg.match_config.server_freezes_participant_roster_match_start")); return lines;
    }
    private void renderPreview(GuiGraphics graphics, int mouseX, int mouseY) {
        var b = layout.preview(); graphics.fill(b.x(), b.y(), b.right(), b.bottom(), 0xaa14202d);
        if (roomPage && !pickingMap && roomDraft != null) { renderLobbyPreview(graphics, mouseX, mouseY); return; }
        graphics.enableScissor(b.x(), b.y(), b.right(), b.bottom());
        int x = b.x() + 8, y = b.y() + 8, w = b.width() - 16;
        graphics.drawString(font, Texts.of("screen.tacz_pubg.match_config.configuration_preview_zone_phase_ratios").getString(), x, y, 0xffffe4a0);
        graphics.drawString(font, Texts.resolve(fit(currentMapLabel(), w)), x, y + 15, 0xffd6e7f1);
        graphics.drawString(font, Texts.resolve(fit(timingLine(), w)), x, y + 29, 0xffb5d9ff);
        int centerX = x + w / 2, centerY = y + 86, radius = 35;
        drawCircle(graphics, centerX, centerY, radius, 0xff8ba8bb);
        double scale = 1;
        if (draft != null) for (int i = 0; i < Math.min(6, draft.phases.size()); i++) {
            try { scale *= Math.clamp(decimal(draft.phases.get(i).ratio, Texts.tr("screen.tacz_pubg.match_config.ratio")) / 100, 0, 1);
                drawCircle(graphics, centerX, centerY, Math.max(1, (int) (radius * scale)), 0xff65b9d4); }
            catch (IllegalArgumentException ignored) { break; }
        }
        graphics.drawString(font, Texts.resolve(fit(Texts.tr("screen.tacz_pubg.match_config.circle_lines_show_radius_ratios_actual_centers_determined"), w)), x, y + 127, 0xff95aab9);
        graphics.drawString(font, Texts.of("screen.tacz_pubg.match_config.actual_server_status").getString(), x, y + 145, 0xffffe4a0);
        int lineY = y + 161;
        for (String text : statusLines()) { graphics.drawString(font, Texts.resolve(fit(text, w)), x, lineY, 0xffd8e3ec); lineY += 14; }
        graphics.disableScissor();
        if (mouseX >= b.x() && mouseX < b.right() && mouseY >= b.y() && mouseY < b.bottom())
            graphics.renderTooltip(font, font.split(Texts.component(String.join("\n", statusLines())), Math.max(160, b.width())), mouseX, mouseY);
    }
    private void renderLobbyPreview(GuiGraphics graphics, int mouseX, int mouseY) {
        var panel = layout.preview(); int x = panel.x() + 8, y = panel.y() + 8, w = panel.width() - 16;
        graphics.enableScissor(panel.x(), panel.y(), panel.right(), panel.bottom());
        graphics.drawString(font, Texts.of("screen.tacz_pubg.match_config.lobby_overview", roomDraft.size(), roomCapacity()).getString(), x, y, 0xffffe4a0);
        graphics.drawString(font, Texts.resolve(fit(currentMapLabel(), w)), x, y + 15, 0xffd6e7f1);
        graphics.drawString(font, Texts.of("screen.tacz_pubg.match_config.humans_bots", (roomDraft.size() - roomDraft.bots()), roomDraft.bots()).getString(), x, y + 30, 0xffb5d9ff);
        graphics.drawString(font, Texts.of("screen.tacz_pubg.match_config.per_team", roomDraft.teamSize(), lobbyStage()).getString(), x, y + 45, 0xffb5d9ff);
        String hovered = null;
        var cells = MatchLobbyLayout.overview(panel);
        for (int index = 0; index < cells.size(); index++) {
            var cell = cells.get(index); int position = index + 1; Member member = roomDraft.at(position);
            boolean selected = member == null ? selectedMember == null && selectedSeat == position : member.id().equals(selectedMember);
            int color = selected ? 0xffc29b49 : member == null ? 0xff354556 : LobbyModel.isBot(member) ? 0xff46705c : 0xff416e91;
            graphics.fill(cell.x(), cell.y(), cell.right(), cell.bottom(), color);
            String marker = member == null ? "·" : LobbyModel.isBot(member) ? Texts.tr("screen.tacz_pubg.match_config.bot.render_lobby_preview") : Texts.tr("screen.tacz_pubg.match_config.human");
            graphics.drawCenteredString(font, Texts.resolve(position + " " + marker), cell.x() + cell.width() / 2, cell.y() + 6, 0xfff0f3f5);
            if (mouseX >= cell.x() && mouseX < cell.right() && mouseY >= cell.y() && mouseY < cell.bottom())
                hovered = Texts.tr("screen.tacz_pubg.match_config.team_slot.render_lobby_preview", ((position - 1) / roomDraft.teamSize() + 1), ((position - 1) % roomDraft.teamSize() + 1), (member == null ? Texts.tr("screen.tacz_pubg.match_config.empty") : member.name()));
        }
        graphics.drawString(font, Texts.resolve(fit(roomCapacity() > 20 ? Texts.tr("screen.tacz_pubg.match_config.edit_all_slots_list_below_first_shown_here", roomCapacity()) : Texts.tr("screen.tacz_pubg.match_config.select_member_then_empty_slot_move"), w)), x, panel.y() + 198, 0xffc3cbd5);
        if (roomView != null && !roomView.message().isBlank()) graphics.drawWordWrap(font, Texts.resolve(Texts.component(roomView.message())), x, panel.y() + 215, w, 0xffb5d9ff);
        graphics.disableScissor();
        if (hovered != null) graphics.renderTooltip(font, Texts.component(hovered), mouseX, mouseY);
        else if (roomView != null && !roomView.message().isBlank() && mouseX >= panel.x() && mouseX < panel.right()
                && mouseY >= panel.y() && mouseY < panel.bottom())
            graphics.renderTooltip(font, font.split(Texts.component(roomView.message()), Math.max(160, panel.width())), mouseX, mouseY);
    }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && roomPage && !pickingMap && layout.hasPreview() && canEditRoom()) {
            var cells = MatchLobbyLayout.overview(layout.preview());
            for (int index = 0; index < cells.size(); index++) {
                var cell = cells.get(index);
                if (mouseX >= cell.x() && mouseX < cell.right() && mouseY >= cell.y() && mouseY < cell.bottom()) {
                    selectSeat(index + 1); return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    private static void drawCircle(GuiGraphics g, int cx, int cy, int radius, int color) {
        for (int angle = 0; angle < 360; angle += 3) {
            double radians = Math.toRadians(angle);
            int x = cx + (int) Math.round(Math.cos(radians) * radius), y = cy + (int) Math.round(Math.sin(radians) * radius);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
    private static String stageLabel(String stage) {
        return switch (stage) {
            case "preparing" -> Texts.tr("screen.tacz_pubg.match_config.preparing"); case "no_circle" -> Texts.tr("screen.tacz_pubg.match_config.no_circle"); case "waiting" -> Texts.tr("screen.tacz_pubg.match_config.waiting_shrink"); case "shrinking" -> Texts.tr("screen.tacz_pubg.match_config.shrinking");
            case "final" -> Texts.tr("screen.tacz_pubg.match_config.final_phase"); case "paused" -> Texts.tr("screen.tacz_pubg.match_config.paused"); case "finished" -> Texts.tr("screen.tacz_pubg.match_config.settled"); case "stopped" -> Texts.tr("screen.tacz_pubg.match_config.stopped"); default -> stage;
        };
    }
    private String fit(String text, int pixels) { return font.plainSubstrByWidth(Texts.resolve(text), Math.max(0, pixels)); }
    private static String duration(long seconds) { return String.format(Locale.ROOT, "%d:%02d", Math.max(0, seconds) / 60, Math.max(0, seconds) % 60); }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static int integer(String text, String label) {
        try { return Integer.parseInt(text.trim()); } catch (NumberFormatException error) { throw new IllegalArgumentException(label + Texts.tr("screen.tacz_pubg.match_config.integer_required_input_retained")); }
    }
    private static double decimal(String text, String label) {
        try { double value = Double.parseDouble(text.trim()); if (!Double.isFinite(value)) throw new NumberFormatException(); return value; }
        catch (NumberFormatException error) { throw new IllegalArgumentException(label + Texts.tr("screen.tacz_pubg.match_config.finite_number_required_input_retained")); }
    }
    @FunctionalInterface private interface Arrange { void apply(int x, int y, int width); }
    @FunctionalInterface private interface Paint { void apply(GuiGraphics graphics, int x, int y, int width); }
    private final class FormList extends ContainerObjectSelectionList<FormRow> {
        FormList(MatchConfigLayout.Rect rect) {
            super(MatchConfigScreen.this.minecraft, rect.width(), rect.height(), rect.y(), MatchConfigLayout.ROW_HEIGHT);
            setX(rect.x()); centerListVertically = false;
        }
        void add(FormRow row) {
            row.arrange.apply(getRowLeft(), 0, getRowWidth());
            addEntry(row);
        }
        @Override public int getRowWidth() { return getWidth() - 14; }
        @Override protected int getScrollbarPosition() { return getX() + getWidth() - 6; }
    }
    static void initializeEditBox(EditBox box, String text, int maxLength) {
        box.setMaxLength(maxLength);
        box.setValue(Texts.resolve(text));
        // setValue at the temporary 20px width otherwise leaves only the final character visible.
        box.moveCursorToStart(false);
    }
    private final class FormRow extends ContainerObjectSelectionList.Entry<FormRow> {
        final List<AbstractWidget> widgets = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        Arrange arrange = (x, y, width) -> {};
        Paint painter = (graphics, x, y, width) -> {};
        Button button(String key, String text, Runnable action, BooleanSupplier condition, String hint) {
            Button button = ConfigUi.button(Texts.component(text), ignored -> action.run()).bounds(0, 0, 20, 20).build();
            button.setTooltip(Tooltip.create(Texts.component(hint))); widgets.add(button); keys.add(key); enabled.add(new Enabled(button, condition)); return button;
        }
        EditBox edit(String key, String text, Consumer<String> change, String hint, int maxLength) {
            EditBox box = ConfigUi.editBox(font, 0, 0, 20, 20, Texts.component(hint));
            initializeEditBox(box, text, maxLength); box.setTooltip(Tooltip.create(Texts.component(hint)));
            box.setResponder(value -> { change.accept(value); updateEnabled(); });
            widgets.add(box); keys.add(key); enabled.add(new Enabled(box, MatchConfigScreen.this::canEdit)); return box;
        }
        @Override public void render(GuiGraphics graphics, int index, int y, int x, int rowWidth, int rowHeight,
                int mouseX, int mouseY, boolean hovered, float partialTick) {
            arrange.apply(x, y, rowWidth); painter.apply(graphics, x, y, rowWidth);
            for (AbstractWidget widget : widgets) widget.render(graphics, mouseX, mouseY, partialTick);
        }
        @Override public List<? extends GuiEventListener> children() { return widgets; }
        @Override public List<? extends NarratableEntry> narratables() { return widgets; }
    }
    private static final class RawSettings {
        final long mapId;
        String name, minimum, maximum, teamSize, preparation, limit, disconnect, equipmentLimit;
        boolean blue, autoEnd, spectate, friendlyFire, bombEnabled, inheritBombing, allowBlockDrops, allowMobDrops,
                flightEnabled, flightRandomRoute;
        String bombRatio, bombRate;
        String flightSpeed, flightHeight, flightJumpDelay, flightAutoDeploy;
        String firstCircleDelay, flightSeconds, bombFirst, bombWarning, bombDuration, bombCooldown, bombLastPhase,
                lateCenterBias, exposureGrace, exposureRamp, exposureMultiplier;
        com.example.examplemod.zones.ZoneSettings.BombingProperties bombProjectile;
        final List<RawPhase> phases = new ArrayList<>();
        RawSettings(Settings settings) {
            mapId = settings.mapId(); name = settings.name(); minimum = Integer.toString(settings.minPlayers());
            maximum = Integer.toString(settings.maxPlayers()); teamSize = Integer.toString(settings.teamSize());
            preparation = Integer.toString(settings.preparationSeconds()); limit = Integer.toString(settings.maxMatchSeconds());
            disconnect = Integer.toString(settings.disconnectGraceSeconds()); blue = settings.blueEnabled();
            autoEnd = settings.autoEnd(); spectate = settings.spectateEliminated(); friendlyFire = settings.friendlyFire();
            allowBlockDrops = settings.allowBlockDrops(); allowMobDrops = settings.allowMobDrops();
            equipmentLimit = Integer.toString(settings.equipmentPerCategoryLimit());
            var flight = settings.flightRules();
            flightEnabled = flight.enabled(); flightRandomRoute = flight.randomRoute();
            flightSpeed = number(flight.speedBlocksPerSecond()); flightHeight = Integer.toString(flight.heightAboveGround());
            flightJumpDelay = Integer.toString(flight.jumpDelaySeconds()); flightAutoDeploy = Integer.toString(flight.autoDeployHeight());
            inheritBombing = settings.bombingRules() == null;
            var bombing = settings.bombingRules() == null ? new MatchConfigModel.BombingRules(true, .12, 60, com.example.examplemod.zones.ZoneSettings.BombingProperties.defaults()) : settings.bombingRules();
            bombEnabled = bombing.enabled(); bombRatio = number(bombing.radiusRatio() * 100); bombRate = number(bombing.roundsPerMinute()); bombProjectile = bombing.projectile();
            var flow = settings.flowRules();
            firstCircleDelay = Integer.toString(flow.firstCircleDelaySeconds()); flightSeconds = Integer.toString(flow.flightSeconds());
            bombFirst = Integer.toString(flow.bombingFirstSeconds()); bombWarning = Integer.toString(flow.bombingWarningSeconds());
            bombDuration = Integer.toString(flow.bombingDurationSeconds()); bombCooldown = Integer.toString(flow.bombingCooldownSeconds());
            bombLastPhase = Integer.toString(flow.bombingLastPhase()); lateCenterBias = number(flow.lateCenterBias() * 100);
            exposureGrace = Integer.toString(flow.exposureGraceSeconds()); exposureRamp = Integer.toString(flow.exposureRampSeconds());
            exposureMultiplier = number(flow.exposureMaxMultiplier());
            settings.phases().forEach(phase -> phases.add(new RawPhase(phase)));
        }
        Settings model() {
            return new Settings(mapId, name, integer(minimum, Texts.tr("screen.tacz_pubg.match_config.minimum_participants.model")), integer(maximum, Texts.tr("screen.tacz_pubg.match_config.maximum_participants")),
                    integer(teamSize, Texts.tr("screen.tacz_pubg.match_config.players_per_team")), integer(preparation, Texts.tr("screen.tacz_pubg.match_config.preparation_time")), integer(limit, Texts.tr("screen.tacz_pubg.match_config.combat_time_limit")),
                    integer(disconnect, Texts.tr("screen.tacz_pubg.match_config.disconnect_grace")), blue, autoEnd, spectate, friendlyFire,
                    phases.stream().map(RawPhase::model).toList(), bombing(), flow(), allowBlockDrops, allowMobDrops, flight(),
                    integer(equipmentLimit, Texts.tr("screen.tacz_pubg.match_config.equipment_limit")));
        }
        private MatchConfigModel.FlightRules flight() {
            String prefix = "screen.tacz_pubg.match_config.";
            return new MatchConfigModel.FlightRules(flightEnabled, flightRandomRoute,
                    decimal(flightSpeed, Texts.tr(prefix + "flight_speed")),
                    integer(flightHeight, Texts.tr(prefix + "flight_height")),
                    integer(flightJumpDelay, Texts.tr(prefix + "flight_jump_delay")),
                    integer(flightAutoDeploy, Texts.tr(prefix + "flight_auto_deploy")));
        }
        private MatchConfigModel.FlowRules flow() {
            String prefix = "screen.tacz_pubg.match_config.";
            return new MatchConfigModel.FlowRules(integer(firstCircleDelay, Texts.tr(prefix + "first_circle_delay")),
                    integer(flightSeconds, Texts.tr(prefix + "flight_seconds")), integer(bombFirst, Texts.tr(prefix + "bombing_first")),
                    integer(bombWarning, Texts.tr(prefix + "bombing_warning")), integer(bombDuration, Texts.tr(prefix + "bombing_duration")),
                    integer(bombCooldown, Texts.tr(prefix + "bombing_cooldown")), integer(bombLastPhase, Texts.tr(prefix + "bombing_last_phase")),
                    decimal(lateCenterBias, Texts.tr(prefix + "late_center_bias")) / 100,
                    integer(exposureGrace, Texts.tr(prefix + "exposure_grace")), integer(exposureRamp, Texts.tr(prefix + "exposure_ramp")),
                    decimal(exposureMultiplier, Texts.tr(prefix + "exposure_multiplier")));
        }
        private MatchConfigModel.BombingRules bombing() {
            var result = new MatchConfigModel.BombingRules(bombEnabled, decimal(bombRatio, Texts.tr("screen.tacz_pubg.match_config.bombardment_radius_ratio")) / 100, decimal(bombRate, Texts.tr("screen.tacz_pubg.match_config.missiles_per_minute")), bombProjectile);
            return inheritBombing && result.equals(MatchConfigModel.BombingRules.defaults()) ? null : result;
        }
        long totalSeconds() {
            long total = integer(firstCircleDelay, Texts.tr("screen.tacz_pubg.match_config.first_circle_delay"));
            for (RawPhase phase : phases) total += integer(phase.wait, Texts.tr("screen.tacz_pubg.match_config.wait_time")) + (long) integer(phase.shrink, Texts.tr("screen.tacz_pubg.match_config.shrink_time"));
            return total;
        }
        void replacePhases(List<Phase> replacement) {
            phases.clear();
            replacement.forEach(phase -> phases.add(new RawPhase(phase)));
        }
        void replaceTiming(Settings settings) {
            replacePhases(settings.phases());
            firstCircleDelay = Integer.toString(settings.flowRules().firstCircleDelaySeconds());
        }
        List<String> values() {
            List<String> values = new ArrayList<>(List.of(Long.toString(mapId), name, minimum, maximum, teamSize,
                    preparation, limit, disconnect, equipmentLimit, Boolean.toString(blue), Boolean.toString(autoEnd), Boolean.toString(spectate), Boolean.toString(friendlyFire)));
            values.addAll(List.of(Boolean.toString(bombEnabled), bombRatio, bombRate, bombProjectile.toString()));
            values.addAll(List.of(Boolean.toString(allowBlockDrops), Boolean.toString(allowMobDrops), Boolean.toString(flightEnabled),
                    Boolean.toString(flightRandomRoute), flightSpeed, flightHeight, flightJumpDelay, flightAutoDeploy));
            values.addAll(List.of(firstCircleDelay, flightSeconds, bombFirst, bombWarning, bombDuration, bombCooldown,
                    bombLastPhase, lateCenterBias, exposureGrace, exposureRamp, exposureMultiplier));
            phases.forEach(phase -> values.addAll(phase.values()));
            return List.copyOf(values);
        }
    }
    private static final class RawPhase {
        String wait, shrink, ratio, damage;
        boolean randomCenter;
        RawPhase(Phase phase) {
            wait = Integer.toString(phase.waitSeconds()); shrink = Integer.toString(phase.shrinkSeconds());
            ratio = BigDecimal.valueOf(phase.radiusRatio()).movePointRight(2).stripTrailingZeros().toPlainString();
            damage = number(phase.damagePerSecond()); randomCenter = phase.randomCenter();
        }
        Phase model() { return new Phase(integer(wait, Texts.tr("screen.tacz_pubg.match_config.wait_time")), integer(shrink, Texts.tr("screen.tacz_pubg.match_config.shrink_time")), decimal(ratio, Texts.tr("screen.tacz_pubg.match_config.target_radius_ratio")) / 100, decimal(damage, Texts.tr("screen.tacz_pubg.match_config.blue_zone_damage")), randomCenter); }
        List<String> values() { return List.of(wait, shrink, ratio, damage, Boolean.toString(randomCenter)); }
    }
}

