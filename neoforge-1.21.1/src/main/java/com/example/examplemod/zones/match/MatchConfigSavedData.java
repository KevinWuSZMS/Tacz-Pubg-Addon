package com.example.examplemod.zones.match;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneSavedData;
import com.example.examplemod.zones.match.MatchConfigModel.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Collection;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Per-map next-match templates. Running sessions and player inventories are never persisted here. */
public final class MatchConfigSavedData extends SavedData {
    private static final String FILE_NAME = "tacz_pubg_matches";
    private static final int LEGACY_MAX_PLAYERS = 100;
    private static final Factory<MatchConfigSavedData> FACTORY = new Factory<>(MatchConfigSavedData::new, MatchConfigSavedData::load);
    private final TreeMap<Long, Settings> templates = new TreeMap<>();
    /** An empty value remembers a deleted rule without reviving a same-named replacement. */
    private final TreeMap<Long, String> lastPlayedRules = new TreeMap<>();
    private long revision;
    private String loadError = "";
    private CompoundTag protectedOriginal;
    /** In-memory converted rules stay behind the file-backup gate until attached to their server. */
    private CompoundTag pendingMigrationOriginal;
    private MinecraftServer owner;

    public static MatchConfigSavedData get(MinecraftServer server) {
        MatchConfigSavedData data = server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
        data.owner = server;
        data.completeRosterMigration(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE_NAME + ".dat"));
        return data;
    }
    public long revision() { return revision; }
    public String loadError() { return loadError; }
    public Settings settings(long mapId) {
        requireReadable();
        return templates.getOrDefault(mapId, MatchConfigModel.defaults(mapId));
    }
    public List<Settings> templates() { return List.copyOf(templates.values()); }

    public String preferredRuleName(long mapId, Collection<String> available) {
        requireReadable();
        String name = lastPlayedRules.get(mapId);
        // Existing worlds already keep the applied map template, so they retain a useful
        // selection even before their first round with explicit last-played metadata.
        if (name == null) { Settings saved = templates.get(mapId); name = saved == null ? "" : saved.name(); }
        return available.contains(name) ? name : "";
    }
    /** Called only after a real session starts successfully; merely saving a draft is independent. */
    public void rememberPlayedRule(Settings settings) {
        requireReadable(); MatchRulePresets.checkName(settings.name());
        if (settings.mapId() <= 0 || settings.mapId() == Long.MAX_VALUE)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.invalid_last_played_map_id"));
        if (!lastPlayedRules.containsKey(settings.mapId()) && lastPlayedRules.size() >= MapZoneModel.MAX_MAPS)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.world_allows_most_match_templates"));
        if (!settings.name().equals(lastPlayedRules.put(settings.mapId(), settings.name()))) setDirty();
    }
    public void renameRememberedRule(String previous, String name) {
        requireReadable();
        if (previous.isEmpty() || previous.equals(name)) return;
        MatchRulePresets.checkName(name);
        templates.forEach((id, settings) -> {
            if (!lastPlayedRules.containsKey(id) && settings.name().equals(previous)) lastPlayedRules.put(id, name);
        });
        lastPlayedRules.replaceAll((id, remembered) -> remembered.equals(previous) ? name : remembered);
        setDirty();
    }
    public void forgetRememberedRule(String name) {
        requireReadable();
        templates.forEach((id, settings) -> {
            if (!lastPlayedRules.containsKey(id) && settings.name().equals(name)) lastPlayedRules.put(id, "");
        });
        lastPlayedRules.replaceAll((id, remembered) -> remembered.equals(name) ? "" : remembered);
        setDirty();
    }

    public void requireMapRemovable(long mapId) {
        requireReadable();
        if (pendingMigrationOriginal != null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_config_saved_data.complete_player_match_template_migration_backup_before_saving"));
        if (revision == Long.MAX_VALUE)
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_config_saved_data.match_configuration_version_exhausted"));
    }

    /** Map IDs may be reused, so removed maps cannot leave templates for their successors. */
    public void removeMap(long mapId) {
        requireMapRemovable(mapId);
        templates.remove(mapId);
        lastPlayedRules.remove(mapId);
        // Invalidate unsaved clients as well: their old map ID may now name another map.
        revision++; setDirty();
    }

    public void recordPresetChange(long expectedRevision) {
        requireReadable();
        if (expectedRevision != revision || revision == Long.MAX_VALUE) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_config_saved_data.rule_library_version_changed_reload"));
        revision++; setDirty();
    }

    public Settings saveSettings(long expectedRevision, Settings settings) {
        requireReadable();
        if (pendingMigrationOriginal != null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_config_saved_data.complete_player_match_template_migration_backup_before_saving"));
        if (expectedRevision != revision) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_config_saved_data.match_configuration_changed_draft_retained_reload_review"));
        if (revision == Long.MAX_VALUE) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_config_saved_data.match_configuration_version_exhausted"));
        MatchConfigModel.toJson(settings);
        if (owner != null) {
            MapZoneSavedData maps = MapZoneSavedData.get(owner);
            if (!maps.loadError().isEmpty()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_config_saved_data.restore_map_data_reading_before_saving_matches"));
            if (maps.map(settings.mapId()) == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.select_map_current_list", settings.mapId()));
            if (MatchService.isRunning(owner, settings.mapId())) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_config_saved_data.end_match_before_modifying_map_s_match_template"));
            // Discard only orphan templates whose maps have explicitly been deleted, when making a valid save.
            templates.keySet().removeIf(id -> maps.map(id) == null);
            lastPlayedRules.keySet().removeIf(id -> maps.map(id) == null);
        }
        if (!templates.containsKey(settings.mapId()) && templates.size() >= MapZoneModel.MAX_MAPS)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.world_allows_most_match_templates"));
        templates.put(settings.mapId(), settings);
        revision++;
        setDirty();
        return settings;
    }

    private void requireReadable() {
        if (!loadError.isEmpty()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_config_saved_data.failed_read_match_data_original_data_protected", loadError));
    }

    static MatchConfigSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        MatchConfigSavedData data = new MatchConfigSavedData();
        if (tag.isEmpty()) return data;
        try {
            if (!Set.of("version", "revision", "templates", "lastPlayedRules").containsAll(tag.getAllKeys())
                    || !tag.getAllKeys().containsAll(Set.of("version", "revision", "templates")))
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.check_saved_match_field_names_completeness"));
            if (!tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.check_match_save_version"));
            if (!tag.contains("revision", Tag.TAG_LONG) || tag.getLong("revision") < 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.check_match_save_version_counter"));
            if (!(tag.get("templates") instanceof ListTag list) || (!list.isEmpty() && list.getElementType() != Tag.TAG_STRING)
                    || list.size() > MapZoneModel.MAX_MAPS) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.check_match_template_list"));
            TreeMap<Long, Settings> restored = new TreeMap<>();
            boolean migrated = false;
            for (int i = 0; i < list.size(); i++) {
                StoredTemplate template = parseStoredTemplate(list.getString(i));
                Settings settings = template.settings();
                migrated |= template.migrated();
                if (restored.putIfAbsent(settings.mapId(), settings) != null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.duplicate_map_id_match_templates"));
            }
            if (migrated && tag.getLong("revision") == Long.MAX_VALUE)
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.keep_incrementable_match_version_counter_player_template_migration"));
            data.revision = tag.getLong("revision");
            data.templates.putAll(restored);
            if (tag.contains("lastPlayedRules")) {
                if (!(tag.get("lastPlayedRules") instanceof ListTag played) || played.size() > MapZoneModel.MAX_MAPS
                        || !played.isEmpty() && played.getElementType() != Tag.TAG_COMPOUND)
                    throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.invalid_last_played_rule_selections_format"));
                for (int i = 0; i < played.size(); i++) {
                    CompoundTag row = played.getCompound(i);
                    if (!row.getAllKeys().equals(Set.of("mapId", "name"))
                            || !row.contains("mapId", Tag.TAG_LONG) || !row.contains("name", Tag.TAG_STRING))
                        throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.invalid_last_played_rule_selection"));
                    long id = row.getLong("mapId"); String name = row.getString("name");
                    if (id <= 0 || id == Long.MAX_VALUE) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.invalid_last_played_map_id"));
                    if (!name.isEmpty()) MatchRulePresets.checkName(name);
                    if (data.lastPlayedRules.putIfAbsent(id, name) != null)
                        throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.duplicate_last_played_map_id"));
                }
            }
            if (migrated) data.pendingMigrationOriginal = tag.copy();
        } catch (RuntimeException error) {
            data.loadError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            data.protectedOriginal = tag.copy();
            LogUtils.getLogger().error("Match templates could not be loaded; original NBT protected: {}", data.loadError);
        }
        return data;
    }

    /** Storage-only compatibility. Network JSON continues through the strict 20-player model codec. */
    private static StoredTemplate parseStoredTemplate(String json) {
        try { return new StoredTemplate(MatchConfigModel.parse(json), false); }
        catch (IllegalArgumentException currentError) {
            JsonElement element = readLegacyJson(json);
            if (!element.isJsonObject()) throw currentError;
            JsonObject root = element.getAsJsonObject();
            int minimum = legacyRosterNumber(root, "minPlayers");
            int maximum = legacyRosterNumber(root, "maxPlayers");
            int teamSize = legacyRosterNumber(root, "teamSize");
            if (minimum > maximum || teamSize > maximum) throw currentError;
            if (minimum <= MatchConfigModel.MAX_PLAYERS && maximum <= MatchConfigModel.MAX_PLAYERS
                    && teamSize <= MatchConfigModel.MAX_PLAYERS) throw currentError;
            root.addProperty("minPlayers", Math.min(minimum, MatchConfigModel.MAX_PLAYERS));
            root.addProperty("maxPlayers", Math.min(maximum, MatchConfigModel.MAX_PLAYERS));
            root.addProperty("teamSize", Math.min(teamSize, MatchConfigModel.MAX_PLAYERS));
            // The current codec rechecks every authored field, including the original phase schedule.
            return new StoredTemplate(MatchConfigModel.parse(root.toString()), true);
        }
    }

    private static int legacyRosterNumber(JsonObject root, String key) {
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.enter_integer_legacy_participant_count", key));
        int number;
        try { number = value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.enter_integer_legacy_participant_count", key), error); }
        if (number < 1 || number > LEGACY_MAX_PLAYERS)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.legacy_participant_count_field", key));
        return number;
    }

    /** Preserve duplicate-key, nesting and size checks before editing the three legacy root numbers. */
    private static JsonElement readLegacyJson(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MatchConfigModel.MAX_JSON_BYTES)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.check_match_json_within_kib"));
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement result = readLegacyValue(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.keep_one_complete_match_json_document"));
            return result;
        } catch (IOException | NumberFormatException error) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.check_legacy_match_json_format"), error);
        }
    }

    private static JsonElement readLegacyValue(JsonReader reader, int depth) throws IOException {
        if (depth > 6) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.keep_match_json_nesting_within_six_levels"));
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.keep_one_value_each_match_json_field", key));
                    object.add(key, readLegacyValue(reader, depth + 1));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) {
                    if (array.size() >= MatchConfigModel.MAX_PHASES) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.most_zone_phases_allowed"));
                    array.add(readLegacyValue(reader, depth + 1));
                }
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_saved_data.enter_valid_match_json_value"));
        };
    }

    /** Package access permits isolated backup/revision checks using a temporary file. */
    void completeRosterMigration(Path source) {
        if (pendingMigrationOriginal == null) return;
        Path backup;
        try {
            if (!Files.isRegularFile(source)) throw new IOException(Texts.tr("message.tacz_pubg.match_config_saved_data.restore_original_match_save_file_then_restart_world"));
            backup = source.resolveSibling(source.getFileName() + ".before-20-player-"
                    + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".bak");
            Files.copy(source, backup);
        } catch (IOException | RuntimeException error) {
            loadError = Texts.tr("message.tacz_pubg.match_config_saved_data.complete_player_match_template_migration_backup_then_restart", (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            protectedOriginal = pendingMigrationOriginal.copy();
            pendingMigrationOriginal = null;
            templates.clear();
            LogUtils.getLogger().error("Match template migration backup failed; original NBT protected: {}", loadError);
            return;
        }
        revision++;
        pendingMigrationOriginal = null;
        setDirty();
        LogUtils.getLogger().info("Match templates migrated to {} players; original file backed up at {}",
                MatchConfigModel.MAX_PLAYERS, backup);
    }

    private record StoredTemplate(Settings settings, boolean migrated) {}

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (protectedOriginal != null) return protectedOriginal.copy();
        if (pendingMigrationOriginal != null) return pendingMigrationOriginal.copy();
        tag.putInt("version", 1); tag.putLong("revision", revision);
        ListTag list = new ListTag();
        templates.values().forEach(settings -> list.add(StringTag.valueOf(MatchConfigModel.toJson(settings))));
        tag.put("templates", list);
        if (!lastPlayedRules.isEmpty()) {
            ListTag played = new ListTag();
            lastPlayedRules.forEach((id, name) -> { CompoundTag row = new CompoundTag();
                row.putLong("mapId", id); row.putString("name", name); played.add(row); });
            tag.put("lastPlayedRules", played);
        } else tag.remove("lastPlayedRules");
        return tag;
    }
}
