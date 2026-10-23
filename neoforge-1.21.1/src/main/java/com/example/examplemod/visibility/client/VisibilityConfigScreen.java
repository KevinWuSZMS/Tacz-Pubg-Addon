package com.example.examplemod.visibility.client;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.visibility.VisibilityPayloads;
import com.example.examplemod.visibility.VisibilityPolicy;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Independent edit transaction, so opening this screen preserves the parent's unsaved loot draft. */
public final class VisibilityConfigScreen extends Screen {
    private final Screen parent;
    private String playerText = "512", targetText = "512", message = Texts.tr("screen.tacz_pubg.visibility_config.reading_distance_settings");
    private VisibilityPolicy.Settings saved = VisibilityPolicy.Settings.defaults();
    private long revision = -1;
    private UUID request;
    private int pendingTicks;
    private boolean started, pending, writing, error;
    private Button saveButton, defaultsButton, reloadButton;
    private EditBox players, targets;
    public VisibilityConfigScreen(Screen parent) { super(Texts.of("screen.tacz_pubg.visibility_config.pubg_long_distance_visibility")); this.parent = parent; }
    @Override protected void init() {
        int left = Math.max(10, width / 2 - 180), boxX = left + 120;
        players = ConfigUi.editBox(font, boxX, 54, 90, 20, Texts.of("screen.tacz_pubg.visibility_config.player_visibility_distance"));
        targets = ConfigUi.editBox(font, boxX, 84, 90, 20, Texts.of("screen.tacz_pubg.visibility_config.target_visibility_distance"));
        players.setMaxLength(4); targets.setMaxLength(4);
        players.setValue(Texts.resolve(playerText)); targets.setValue(Texts.resolve(targetText));
        players.setResponder(value -> { playerText = value; updateButtons(); });
        targets.setResponder(value -> { targetText = value; updateButtons(); });
        players.setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.visibility_config.blocks_adjusts_both_server_tracking_client_rendering")));
        targets.setTooltip(Tooltip.create(Texts.of("screen.tacz_pubg.visibility_config.loaded_tacz_block_targets_humanoid_minecart_targets")));
        addRenderableWidget(players); addRenderableWidget(targets);
        defaultsButton = addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.visibility_config.reset_default"), button -> {
            players.setValue(Texts.resolve("512")); targets.setValue(Texts.resolve("512"));
        }).bounds(left, 116, 110, 20).build());
        reloadButton = addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.visibility_config.reload"), button -> {
            if (dirty()) minecraft.setScreen(new ConfirmScreen(yes -> {
                minecraft.setScreen(this); if (yes) send(false);
            }, Texts.of("screen.tacz_pubg.visibility_config.discard_distance_draft"), Texts.of("screen.tacz_pubg.visibility_config.reload_long_distance_visibility_settings")));
            else send(false);
        }).bounds(left + 120, 116, 100, 20).build());
        saveButton = addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.configuration.save"), button -> send(true))
                .bounds(width - 110, height - 28, 100, 20).build());
        addRenderableWidget(ConfigUi.button(Texts.of("screen.tacz_pubg.visibility_config.back_configuration"), button -> onClose())
                .bounds(10, height - 28, 90, 20).build());
        updateButtons();
        if (!started) { started = true; send(false); }
    }
    private VisibilityPolicy.Settings parse() {
        return new VisibilityPolicy.Settings(Integer.parseInt(playerText.trim()), Integer.parseInt(targetText.trim()));
    }
    private boolean dirty() {
        try { return !parse().equals(saved); } catch (RuntimeException ignored) { return true; }
    }
    private void updateButtons() {
        if (saveButton != null) {
            saveButton.visible = dirty() || writing;
            saveButton.setMessage(Texts.component(writing ? Texts.tr("screen.tacz_pubg.gun_ammo_supply.saving") : Texts.tr("screen.tacz_pubg.configuration.save")));
            try { parse(); saveButton.active = revision >= 0 && !pending && dirty(); }
            catch (RuntimeException ignored) { saveButton.active = false; }
        }
        if (players != null) players.setEditable(!pending);
        if (targets != null) targets.setEditable(!pending);
        if (defaultsButton != null) defaultsButton.active = !pending;
        if (reloadButton != null) reloadButton.active = !pending;
    }
    private void send(boolean save) {
        if (pending) return;
        try {
            VisibilityPolicy.Settings next = save ? parse() : saved;
            request = UUID.randomUUID(); pending = true; writing = save; pendingTicks = 0;
            message = save ? Texts.tr("screen.tacz_pubg.visibility_config.saving") : Texts.tr("screen.tacz_pubg.visibility_config.reading"); error = false; updateButtons();
            PacketDistributor.sendToServer(new VisibilityPayloads.Request(request, save, revision, next.players(), next.targets()));
        } catch (RuntimeException failure) { message = Texts.tr("screen.tacz_pubg.visibility_config.enter_integer"); error = true; pending = writing = false; updateButtons(); }
    }
    void accept(VisibilityPayloads.State state) {
        if (request == null || !request.equals(state.request())) return;
        pending = writing = false; request = null;
        message = state.message(); error = !state.success();
        if (state.success()) {
            revision = state.revision(); saved = state.settings();
            playerText = Integer.toString(saved.players()); targetText = Integer.toString(saved.targets());
            players.setValue(Texts.resolve(playerText)); targets.setValue(Texts.resolve(targetText));
        }
        updateButtons();
    }
    @Override public void tick() {
        if (pending && ++pendingTicks > 200) {
            pending = writing = false; request = null; error = true;
            message = Texts.tr("screen.tacz_pubg.visibility_config.server_response_timed_out_draft_retained_reload_confirm"); updateButtons();
        }
    }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { super.renderBackground(graphics, mouseX, mouseY, partialTick); }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = Math.max(10, width / 2 - 180);
        graphics.drawCenteredString(font, Texts.resolve(title), width / 2, 16, 0xffffff);
        graphics.drawString(font, Texts.of("screen.tacz_pubg.visibility_config.player_distance_blocks").getString(), left, 60, 0xffffff);
        graphics.drawString(font, Texts.of("screen.tacz_pubg.visibility_config.target_distance_blocks").getString(), left, 90, 0xffffff);
        graphics.drawString(font, Texts.of("screen.tacz_pubg.visibility_config.synchronization_covers_loaded_players_targets").getString(), left, 148, 0xb3cce8);
        graphics.drawString(font, Texts.of("screen.tacz_pubg.visibility_config.voxy_displays_distant_terrain_entities_collisions_update_chunk").getString(), left, 163, 0xb3cce8);
        if (VisibilityClient.omittedTargets() > 0)
            graphics.drawString(font, Texts.of("screen.tacz_pubg.visibility_config.too_many_nearby_targets_distant_snapshots_retain_nearest").getString(), left, 178, 0xffcf78);
        graphics.drawWordWrap(font, Texts.resolve(Texts.component(message)), left, Math.max(174, height - 66), Math.min(360, width - left - 10), error ? 0xff9999 : 0x99dd99);
    }
    @Override public void onClose() {
        if (writing) return;
        if (dirty()) minecraft.setScreen(new ConfirmScreen(yes -> minecraft.setScreen(yes ? parent : this),
                Texts.of("screen.tacz_pubg.visibility_config.discard_distance_draft"), Texts.of("screen.tacz_pubg.visibility_config.saved_distances_remain_use_after_returning")));
        else minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen() { return false; }
}
