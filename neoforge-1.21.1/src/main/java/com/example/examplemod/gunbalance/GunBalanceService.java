package com.example.examplemod.gunbalance;

import com.example.examplemod.localization.Texts;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.example.examplemod.lootconfig.LootConfigModel.GunSettings;
import com.example.examplemod.lootconfig.LootConfigStore;
import com.example.examplemod.gunbalance.ammo.AmmoProfiles;
import com.example.examplemod.gunbalance.reload.WorkbenchMagazineReload;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Independent gun settings share TaCZ's native reload/sync pipeline. */
public final class GunBalanceService {
    private record ActiveProfile(boolean balance, boolean scopes, boolean ammo, boolean magazine) {
        private static ActiveProfile from(GunSettings settings) {
            return new ActiveProfile("experimental".equals(settings.balance()),
                    "experimental".equals(settings.scopes()), "experimental".equals(settings.ammo()),
                    "experimental".equals(settings.magazine()));
        }
        private String description() {
            return Texts.tr("message.tacz_pubg.gun_balance_service.balance_optics_ammo_magazines", (balance ? Texts.tr("message.tacz_pubg.gun_balance_service.experimental") : Texts.tr("message.tacz_pubg.gun_balance_service.default")), (scopes ? Texts.tr("message.tacz_pubg.gun_balance_service.experimental") : Texts.tr("message.tacz_pubg.gun_balance_service.default")), (ammo ? Texts.tr("message.tacz_pubg.gun_balance_service.experimental") : Texts.tr("message.tacz_pubg.gun_balance_service.default")), (magazine ? Texts.tr("message.tacz_pubg.gun_balance_service.experimental") : Texts.tr("message.tacz_pubg.gun_balance_service.default")));
        }
    }
    private static final ActiveProfile INITIAL = new ActiveProfile(false, false, false, false);
    private static volatile ActiveProfile loadingProfile = INITIAL;
    private static volatile Set<ResourceLocation> originalScopeDataIds = Set.of();
    private static volatile Set<ResourceLocation> originalMagazineDataIds = Set.of();
    private static MinecraftServer owner;
    private static CompletableFuture<Void> queue = CompletableFuture.completedFuture(null);
    private static int pending;
    private static String lastError = "";
    private static ActiveProfile appliedProfile = INITIAL;
    private static ActiveProfile desiredProfile = INITIAL;
    private static long stateRevision;
    private static boolean balanceRuntimeReady;
    private static boolean magazineRuntimeReady;

    private GunBalanceService() {}

    public static boolean isScopeExperimentLoading() { return loadingProfile.scopes(); }
    public static boolean isMagazineExperimentLoading() { return loadingProfile.magazine(); }
    public static boolean originallyAllowedScope(ResourceLocation dataId) { return originalScopeDataIds.contains(dataId); }
    public static boolean originallyAllowedMagazine(ResourceLocation dataId) { return originalMagazineDataIds.contains(dataId); }
    public static boolean isBalanceExperimentActive(MinecraftServer server) {
        return server != null && owner == server && balanceRuntimeReady && appliedProfile.balance();
    }
    public static boolean isMagazineExperimentActive(MinecraftServer server) {
        return server != null && owner == server && magazineRuntimeReady && appliedProfile.magazine();
    }

    /** Respawn and deferred regions must never read an in-progress or failed gun-data reload. */
    public static boolean isSettingsReady(MinecraftServer server) {
        return server != null && owner == server && pending == 0 && lastError.isEmpty()
                && appliedProfile.equals(desiredProfile);
    }

    public static void requireSettingsReady(MinecraftServer server) {
        if (!isSettingsReady(server)) throw new IllegalStateException(lastError.isEmpty()
                ? Texts.tr("message.tacz_pubg.gun_balance_service.applying_gun_settings_wait_completion_notice_before_refreshing") : lastError);
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(GunBalanceService::onServerStarted);
        eventBus.addListener(GunBalanceService::onServerStopped);
        eventBus.addListener(GunBalanceService::onPlayerLoggedIn);
        eventBus.addListener(EventPriority.LOWEST, GunBalanceService::onDatapackSync);
    }

    /** Only implemented categories trigger resource reloads; other attachment choices remain stored. */
    public static boolean shouldApplySettings(MinecraftServer server, GunSettings settings) {
        ActiveProfile profile = ActiveProfile.from(settings);
        return owner != server || !lastError.isEmpty() || !profile.equals(desiredProfile)
                || (pending == 0 && !profile.equals(appliedProfile));
    }

    public static CompletableFuture<Void> onSettingsChange(MinecraftServer server, GunSettings settings) {
        return enqueue(server, ActiveProfile.from(settings));
    }

    private static CompletableFuture<Void> enqueue(MinecraftServer server, ActiveProfile profile) {
        if (!server.isSameThread()) {
            CompletableFuture<Void> dispatched = new CompletableFuture<>();
            server.execute(() -> enqueue(server, profile).whenComplete((ignored, error) -> {
                if (error == null) dispatched.complete(null); else dispatched.completeExceptionally(error);
            }));
            return dispatched;
        }
        if (owner != server) {
            owner = server;
            queue = CompletableFuture.completedFuture(null);
            pending = 0;
        }
        pending++;
        desiredProfile = profile;
        notifyOperators(server, Texts.tr("message.tacz_pubg.gun_balance_service.gun_settings_saved_applying_you_will_notified_when", profile.description()));
        // Publish the gate before dispatch: managedBlock may pump another config save inline.
        CompletableFuture<Void> previous = queue;
        CompletableFuture<Void> gate = new CompletableFuture<>();
        queue = gate;
        previous.handle((ignored, previousError) -> (Void) null)
                .thenComposeAsync(ignored -> reloadProfile(server, profile), server)
                .whenComplete((ignored, error) -> {
                    if (error == null) gate.complete(null); else gate.completeExceptionally(error);
                });
        return gate;
    }

    private static CompletableFuture<Void> reloadProfile(MinecraftServer server, ActiveProfile profile) {
        if (owner != server || server.isStopped())
            return CompletableFuture.failedFuture(new IllegalStateException(Texts.tr("error.tacz_pubg.gun_balance_service.server_stopped_before_gun_settings_reload")));
        loadingProfile = profile;
        lastError = "";
        if (magazineRuntimeReady || appliedProfile.magazine() != profile.magazine()) {
            // End the old clock before changing its rate; never rewind a fed reload into feeding again.
            WorkbenchMagazineReload.cancelActive(server);
            magazineRuntimeReady = false;
            syncExperiment(server);
        }
        CompletableFuture<Void> reload;
        try {
            server.getPackRepository().reload();
            reload = server.reloadResources(server.getPackRepository().getSelectedIds());
        } catch (RuntimeException error) { reload = CompletableFuture.failedFuture(error); }
        return reload.whenCompleteAsync((ignored, error) -> {
            if (owner != server) return;
            pending = Math.max(0, pending - 1);
            if (error == null) {
                appliedProfile = profile;
                balanceRuntimeReady = true;
                magazineRuntimeReady = true;
                // TaCZ's native reload and data sync finish before the camera/physics gate is confirmed.
                syncExperiment(server);
                notifyOperators(server, Texts.tr("message.tacz_pubg.gun_balance_service.gun_settings_applied_synchronized_guns_without_required_slots", profile.description()));
            } else {
                lastError = Texts.tr("message.tacz_pubg.gun_balance_service.failed_apply_gun_settings_your_selection_was_saved", error.getMessage());
                WorkbenchMagazineReload.cancelActive(server);
                balanceRuntimeReady = false;
                magazineRuntimeReady = false;
                LogUtils.getLogger().error("Unable to apply gun settings {}", profile, error);
                syncExperiment(server);
                notifyOperators(server, lastError);
            }
        }, server);
    }

    /** Inspect fresh data before applying balance; scope experiments never create a new slot. */
    public static Map<ResourceLocation, JsonElement> transformLoadedData(Map<ResourceLocation, JsonElement> source) {
        return transformLoadedData(source, null);
    }

    public static Map<ResourceLocation, JsonElement> transformLoadedData(Map<ResourceLocation, JsonElement> source,
            ResourceManager manager) {
        ActiveProfile profile = loadingProfile;
        Set<ResourceLocation> originalScopeIds = new HashSet<>();
        Set<ResourceLocation> originalMagazineIds = new HashSet<>();
        for (var entry : source.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject data = entry.getValue().getAsJsonObject();
            if (PubgScopeRules.hasScopeSlot(data)) originalScopeIds.add(entry.getKey());
            if (PubgMagazineRules.hasMagazineSlot(data) && PubgMagazineRules.supportsNativeMagazineLevels(data))
                originalMagazineIds.add(entry.getKey());
        }
        originalScopeDataIds = Set.copyOf(originalScopeIds);
        originalMagazineDataIds = Set.copyOf(originalMagazineIds);
        if (!profile.balance() && !profile.ammo()) return source;
        if (profile.ammo() && manager != null) AmmoProfiles.validateTargetResources(id -> manager.getResource(
                ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "index/ammo/" + id.getPath() + ".json")).isPresent());
        Map<ResourceLocation, JsonElement> transformed = new HashMap<>(source);
        for (var entry : source.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject data = entry.getValue().getAsJsonObject();
            transformed.put(entry.getKey(), GunDataExperimentPipeline.apply(
                    entry.getKey(), data, profile.balance(), profile.ammo()));
        }
        return transformed;
    }

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        owner = server;
        try {
            GunSettings settings = LootConfigStore.current(server.overworld()).gunSettings();
            if (!ActiveProfile.from(settings).equals(INITIAL)) onSettingsChange(server, settings);
        } catch (RuntimeException error) {
            lastError = Texts.tr("message.tacz_pubg.gun_balance_service.could_not_read_saved_gun_settings_keeping_default", error.getMessage());
            LogUtils.getLogger().error(lastError, error);
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        if (owner != null && owner != event.getServer()) return;
        owner = null;
        queue = CompletableFuture.completedFuture(null);
        loadingProfile = INITIAL;
        originalScopeDataIds = Set.of();
        originalMagazineDataIds = Set.of();
        pending = 0;
        lastError = "";
        appliedProfile = desiredProfile = INITIAL;
        stateRevision = 0;
        balanceRuntimeReady = false;
        magazineRuntimeReady = false;
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        syncExperiment(player);
        if (!player.hasPermissions(2)) return;
        if (pending > 0) player.sendSystemMessage(Texts.of("message.tacz_pubg.gun_balance_service.applying_gun_settings_wait_completion_notice"));
        else if (!lastError.isEmpty()) player.sendSystemMessage(Texts.component(lastError));
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) {
            refreshCurrentGun(event.getPlayer());
            syncExperiment(event.getPlayer());
            return;
        }
        MinecraftServer server = event.getPlayerList().getServer();
        for (var level : server.getAllLevels()) for (Entity entity : level.getAllEntities())
            if (entity instanceof LivingEntity living) refreshCurrentGun(living);
        // During a scheme reload, completion publishes the new state after native gun sync.
        if (pending == 0) syncExperiment(server);
    }

    private static void syncExperiment(MinecraftServer server) {
        var state = new GunExperimentPayloads.State(++stateRevision, isBalanceExperimentActive(server),
                isMagazineExperimentActive(server));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) PacketDistributor.sendToPlayer(player, state);
    }

    private static void syncExperiment(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new GunExperimentPayloads.State(++stateRevision,
                isBalanceExperimentActive(player.server), isMagazineExperimentActive(player.server)));
    }

    private static void refreshCurrentGun(LivingEntity entity) {
        if (IGun.mainHandHoldGun(entity)) AttachmentPropertyManager.postChangeEvent(entity, entity.getMainHandItem());
    }

    private static void notifyOperators(MinecraftServer server, String message) {
        LogUtils.getLogger().info(message);
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (player.hasPermissions(2)) player.sendSystemMessage(Texts.component(message));
    }
}
