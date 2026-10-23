package com.example.examplemod.lootconfig;

import com.example.examplemod.localization.Texts;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.example.examplemod.gunbalance.GunBalanceService;
import com.example.examplemod.operatorpermissions.OperatorPermissions;
import com.example.examplemod.operatorpermissions.OperatorPermissionsService;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** World-local JSON configuration; the server thread owns reads, validation and commits. */
public final class LootConfigStore {
    private static final Map<MinecraftServer, LootConfigStore> STORES = new WeakHashMap<>();
    private final Path path;
    private LootConfigModel.Document document;
    private long revision;
    private String loadError = "";

    private LootConfigStore(ServerLevel level) {
        path = level.getServer().getWorldPath(LevelResource.ROOT).resolve("serverconfig")
                .resolve("tacz_pubg-loot.json").toAbsolutePath().normalize();
        document = LootCatalog.defaultDocument(level);
        if (Files.exists(path)) {
            try {
                if (Files.size(path) > LootConfigModel.MAX_JSON_BYTES + 4096) {
                    throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_store.configuration_file_exceeds_size_limit"));
                }
                JsonElement parsed = LootConfigModel.readJson(Files.readString(path, StandardCharsets.UTF_8),
                        LootConfigModel.MAX_JSON_BYTES + 4096);
                if (!parsed.isJsonObject()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_store.configuration_file_object"));
                JsonObject root = parsed.getAsJsonObject();
                if (!Set.of("schemaVersion", "revision", "document").equals(root.keySet())
                        || !root.get("schemaVersion").isJsonPrimitive()
                        || !root.get("schemaVersion").getAsJsonPrimitive().isNumber()
                        || !root.get("revision").isJsonPrimitive()
                        || !root.get("revision").getAsJsonPrimitive().isNumber()
                        || (root.get("schemaVersion").getAsBigDecimal().intValueExact() != 1
                            && root.get("schemaVersion").getAsBigDecimal().intValueExact() != 2
                            && root.get("schemaVersion").getAsBigDecimal().intValueExact() != 3
                            && root.get("schemaVersion").getAsBigDecimal().intValueExact() != 4
                            && root.get("schemaVersion").getAsBigDecimal().intValueExact() != 5
                            && root.get("schemaVersion").getAsBigDecimal().intValueExact() != 6
                            && root.get("schemaVersion").getAsBigDecimal().intValueExact() != 7
                            && root.get("schemaVersion").getAsBigDecimal().intValueExact() != 8
                            && root.get("schemaVersion").getAsBigDecimal().intValueExact() != 9
                            && root.get("schemaVersion").getAsBigDecimal().intValueExact() != 10
                            && root.get("schemaVersion").getAsBigDecimal().intValueExact() != 11
                            && root.get("schemaVersion").getAsBigDecimal().intValueExact() != 12)) {
                    throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_store.use_schemaversion_configuration_file_check_fields"));
                }
                long loadedRevision = root.get("revision").getAsBigDecimal().longValueExact();
                if (loadedRevision < 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_store.enter_valid_configuration_version"));
                LootConfigModel.Document loadedDocument = LootConfigModel.parse(root.get("document").toString());
                if (LootConfigModel.toJson(loadedDocument).getBytes(StandardCharsets.UTF_8).length > LootConfigModel.MAX_JSON_BYTES) {
                    throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_store.formatted_configuration_exceeds_mib_transfer_budget"));
                }
                document = LootCatalog.supplement(loadedDocument, level);
                revision = loadedRevision;
                boolean legacy = root.get("schemaVersion").getAsInt() < 12
                        || root.getAsJsonObject("document").has("groups")
                        || !root.getAsJsonObject("document").has("categoryProbabilities")
                        || !root.getAsJsonObject("document").has("gunSettings")
                        || !root.getAsJsonObject("document").getAsJsonObject("general").has("companionAttachmentChance")
                        || !root.getAsJsonObject("document").getAsJsonObject("general").has("companionAttachmentKinds")
                        || !root.getAsJsonObject("document").getAsJsonObject("general").has("gunSelection")
                        || !root.getAsJsonObject("document").getAsJsonObject("general").has("operatorPermissions")
                        || !root.getAsJsonObject("document").getAsJsonObject("general").has("zoneSettings")
                        || !root.getAsJsonObject("document").getAsJsonObject("general").getAsJsonObject("zoneSettings").has("heavyWeaponTerrain")
                        || !root.getAsJsonObject("document").getAsJsonObject("general").getAsJsonObject("zoneSettings").getAsJsonObject("bombing").has("spawnHeight")
                        || !root.getAsJsonObject("document").getAsJsonObject("general").getAsJsonObject("zoneSettings").getAsJsonObject("bombing").has("passThroughBlocks");
                if (legacy || !document.equals(loadedDocument)) {
                    // Preserve the exact original before changing schema or appending new pack assets.
                    Path backup = path.resolveSibling(path.getFileName() + ".before-loot-migration-"
                            + java.time.Instant.now().toEpochMilli() + "-" + java.util.UUID.randomUUID() + ".bak");
                    Files.copy(path, backup);
                    writeAtomic(document, revision);
                    LogUtils.getLogger().info("Migrated/supplemented loot and gun settings; original config preserved at {}", backup);
                }
            } catch (IOException | RuntimeException error) {
                loadError = Texts.tr("message.tacz_pubg.loot_config_store.failed_read_configuration_original_file_retained_overwriting_disabled", error.getMessage());
                LogUtils.getLogger().error("Unable to load loot configuration {}", path, error);
            }
        } else {
            document = new LootConfigModel.Document(document.general().withOperatorPermissions(
                    OperatorPermissionsService.captureGameRules(level.getServer())), document.items(),
                    document.gunSettings(), document.categoryProbabilities(), document.tierProfiles());
            try {
                writeAtomic(document, revision);
            } catch (IOException error) {
                loadError = Texts.tr("message.tacz_pubg.loot_config_store.configuration_file_creation_notice", error.getMessage());
                LogUtils.getLogger().error("Unable to create loot configuration {}", path, error);
            }
        }
    }

    public static LootConfigStore get(ServerLevel level) {
        return STORES.computeIfAbsent(level.getServer(), ignored -> new LootConfigStore(level));
    }

    public static LootConfigModel.Document current(ServerLevel level) {
        LootConfigStore store = get(level);
        if (!store.loadError.isEmpty()) throw new IllegalStateException(store.loadError);
        return store.document();
    }

    public LootConfigModel.Document document() {
        return document;
    }

    public long revision() {
        return revision;
    }

    public Path path() {
        return path;
    }

    public String loadError() {
        return loadError;
    }

    public SaveResult save(ServerLevel level, long expectedRevision, LootConfigModel.Document candidate) {
        if (!loadError.isEmpty()) return new SaveResult(false, loadError);
        if (expectedRevision != revision) {
            return new SaveResult(false, Texts.tr("message.tacz_pubg.loot_config_store.another_editor_updated_configuration_reopen_merge_your_changes"));
        }
        List<String> errors = LootConfigModel.validate(candidate);
        if (!errors.isEmpty()) return new SaveResult(false, String.join("；", errors));
        if (!com.example.examplemod.zones.bombing.BombingAmmoCatalog.isSupported(candidate.general().zoneSettings().bombing().ammoId()))
            return new SaveResult(false, Texts.tr("message.tacz_pubg.loot_config_store.select_available_rocket_current_catalog_general_settings"));
        for (String id : candidate.general().zoneSettings().bombing().passThroughBlocks()) {
            if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.ResourceLocation.parse(id)))
                return new SaveResult(false, Texts.tr("message.tacz_pubg.loot_config_store.select_registered_pass_through_block", id));
        }
        var heavy = com.example.examplemod.zones.heavy.HeavyWeaponCatalog.capture();
        for (String id : candidate.general().zoneSettings().heavyWeaponTerrain().keySet()) {
            if (!heavy.containsKey(id)) return new SaveResult(false, Texts.tr("message.tacz_pubg.loot_config_store.select_heavy_weapon_current_catalog", id));
        }
        for (String id : candidate.general().zoneSettings().heavyWeaponDamageCurves().keySet()) {
            if (!heavy.containsKey(id)) return new SaveResult(false, Texts.tr("message.tacz_pubg.loot_config_store.select_heavy_weapon_damage_curve_current_catalog", id));
        }
        String json = LootConfigModel.toJson(candidate);
        if (json.getBytes(StandardCharsets.UTF_8).length > LootConfigModel.MAX_JSON_BYTES) {
            return new SaveResult(false, Texts.tr("message.tacz_pubg.loot_config_store.configuration_transfer_budget_mib_reduce_before_saving"));
        }
        java.util.List<LootConfigModel.Entry> allEntries = new java.util.ArrayList<>(candidate.items());
        candidate.tierProfiles().values().forEach(profile -> allEntries.addAll(profile.items()));
        for (LootConfigModel.Entry entry : allEntries) {
            String error = LootCatalog.validationError(entry, level);
            if (error != null && !error.isEmpty()) return new SaveResult(false, entry.id() + "：" + error);
        }
        if (revision == Long.MAX_VALUE) return new SaveResult(false, Texts.tr("message.tacz_pubg.loot_config_store.configuration_version_limit_has_been_reached_contact_world"));
        try {
            boolean applyGunSettings = GunBalanceService.shouldApplySettings(level.getServer(), candidate.gunSettings());
            writeAtomic(candidate, revision + 1);
            document = candidate;
            revision++;
            com.example.examplemod.originalicons.OriginalIconService.sync(level.getServer());
            OperatorPermissionsService.applyToGameRules(level.getServer(), candidate.general().operatorPermissions());
            try {
                com.example.examplemod.lootspawn.DroppedLootCatalog.configurationSaved(level);
            } catch (RuntimeException syncError) {
                LogUtils.getLogger().error("Loot config saved; drop catalog sync failed and will retry on login/reload", syncError);
            }
            if (applyGunSettings) {
                GunBalanceService.onSettingsChange(level.getServer(), candidate.gunSettings());
                return new SaveResult(true, Texts.tr("message.tacz_pubg.loot_config_store.configuration_saved_gun_settings_reloading_you_will_notified"));
            }
            return new SaveResult(true, Texts.tr("message.tacz_pubg.loot_config_store.configuration_saved_world_s_serverconfig_tacz_pubg_loot"));
        } catch (IOException error) {
            LogUtils.getLogger().error("Unable to save loot configuration {}", path, error);
            return new SaveResult(false, Texts.tr("message.tacz_pubg.loot_config_store.configuration_write_notice_saved_configuration_retained", error.getMessage()));
        }
    }

    /** A /gamerule write changes only OP permissions, preserving every authoring field and tier profile. */
    public SaveResult saveOperatorPermissions(ServerLevel level, OperatorPermissions permissions) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.loot_config_store.change_op_permissions_server_thread"));
        if (!loadError.isEmpty()) return new SaveResult(false, loadError);
        if (permissions.equals(document.general().operatorPermissions())) {
            OperatorPermissionsService.applyToGameRules(level.getServer(), permissions);
            return new SaveResult(true, Texts.tr("message.tacz_pubg.loot_config_store.op_permissions_unchanged"));
        }
        if (revision == Long.MAX_VALUE) return new SaveResult(false, Texts.tr("message.tacz_pubg.loot_config_store.configuration_version_limit_has_been_reached_contact_world"));
        var candidate = new LootConfigModel.Document(document.general().withOperatorPermissions(permissions), document.items(),
                document.gunSettings(), document.categoryProbabilities(), document.tierProfiles());
        try {
            writeAtomic(candidate, revision + 1);
            document = candidate; revision++;
            OperatorPermissionsService.applyToGameRules(level.getServer(), permissions);
            return new SaveResult(true, Texts.tr("message.tacz_pubg.loot_config_store.op_permissions_saved_world_s_configuration"));
        } catch (IOException error) {
            LogUtils.getLogger().error("Unable to save PUBG operator permissions {}", path, error);
            return new SaveResult(false, Texts.tr("message.tacz_pubg.loot_config_store.failed_write_op_permissions_previous_settings_retained", error.getMessage()));
        }
    }

    private void writeAtomic(LootConfigModel.Document candidate, long newRevision) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 12);
        root.addProperty("revision", newRevision);
        root.add("document", LootConfigModel.readJson(LootConfigModel.toJson(candidate)));
        byte[] bytes = new GsonBuilder().disableHtmlEscaping().create()
                .toJson(root).getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "tacz_pubg-loot-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Refuse a non-atomic fallback rather than exposing a partial replacement.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                LogUtils.getLogger().warn("Unable to remove loot configuration temporary file {}", temporary, cleanupError);
            }
        }
    }

    public record SaveResult(boolean success, String message) {}
}
