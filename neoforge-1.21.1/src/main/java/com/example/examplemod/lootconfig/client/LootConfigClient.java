package com.example.examplemod.lootconfig.client;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.lootconfig.LootConfigPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client session only; the server owns validation, revisions, and persistence. */
public final class LootConfigClient {
    private static boolean registered;
    private static LootConfigScreen active;
    private static int pendingTicks;

    private LootConfigClient() {}

    public static void register() {
        if (registered) return;
        registered = true;
        LootConfigPayloads.setClientHandler(LootConfigClient::accept);
        NeoForge.EVENT_BUS.addListener(LootConfigClient::onLogout);
        NeoForge.EVENT_BUS.addListener(LootConfigClient::onTick);
    }

    public static void accept(LootConfigPayloads.Snapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> accept(snapshot));
            return;
        }
        if (minecraft.level == null || minecraft.player == null) return;
        if (snapshot.openScreen()) {
            if (!snapshot.success()) {
                minecraft.player.displayClientMessage(Texts.component(snapshot.message()), false);
                return;
            }
            if (active != null && (active.hasUnsavedChanges() || ConfigUi.hasOtherDrafts(active))) {
                active.showMessage(Texts.tr("screen.tacz_pubg.loot_config_client.unsaved_draft_available_continue_editing_discard_before_loading"), true);
                minecraft.setScreen(active);
                return;
            }
            active = new LootConfigScreen(snapshot.revision(), snapshot.document(), snapshot.catalog(), snapshot.message());
            minecraft.setScreen(active);
        } else if (active != null) {
            if (active.isStaleResult(snapshot)) return;
            pendingTicks = 0;
            active.acceptSaveResult(snapshot);
        } else if (!snapshot.message().isBlank()) {
            minecraft.player.displayClientMessage(Texts.component(snapshot.message()), false);
        }
    }

    static void save(LootConfigScreen screen, long revision, LootConfigModel.Document draft) {
        if (active != screen || pendingTicks > 0) return;
        pendingTicks = 400;
        screen.setSaving(true);
        try {
            LootConfigPayloads.submit(draft, revision);
        } catch (RuntimeException exception) {
            pendingTicks = 0;
            screen.setSaving(false);
            screen.showMessage(Texts.tr("screen.tacz_pubg.loot_config_client.submission_failed", exception.getMessage()), true);
        }
    }

    static void closed(LootConfigScreen screen) {
        if (active == screen) {
            ConfigUi.end(screen);
            active = null;
            pendingTicks = 0;
        }
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ConfigUi.clear();
        active = null;
        pendingTicks = 0;
        LootConfigPayloads.clearClientTransfers();
    }

    private static void onTick(ClientTickEvent.Post event) {
        if (pendingTicks > 0 && --pendingTicks == 0 && active != null) {
            active.setSaving(false);
            active.showMessage(Texts.tr("screen.tacz_pubg.loot_config_client.server_confirmation_timed_out_draft_preserved_check_current"), true);
        }
    }
}
