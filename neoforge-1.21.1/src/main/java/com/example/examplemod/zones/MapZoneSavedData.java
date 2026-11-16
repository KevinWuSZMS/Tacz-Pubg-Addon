package com.example.examplemod.zones;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.zones.MapZoneModel.BombingZone;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** Save-wide authoring data. No ticks, explosions, player damage or match activation. */
public final class MapZoneSavedData extends SavedData {
    private static final String FILE_NAME = "tacz_pubg_maps";
    private static final Factory<MapZoneSavedData> FACTORY = new Factory<>(MapZoneSavedData::new, MapZoneSavedData::load);
    private final TreeMap<Long, MapDefinition> maps = new TreeMap<>();
    private long revision;
    private long nextBombingZoneId = 1;
    private String loadError = "";
    @Nullable private CompoundTag protectedOriginal;
    @Nullable private MinecraftServer runtimeOwner;

    public static MapZoneSavedData get(MinecraftServer server) {
        MapZoneSavedData data = server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
        data.runtimeOwner = server;
        return data;
    }

    public long revision() { return revision; }
    public List<MapDefinition> maps() { return List.copyOf(maps.values()); }
    @Nullable public MapDefinition map(long id) { return maps.get(id); }
    /** Nonempty means the original malformed/unsupported data is protected against editing. */
    public String loadError() { return loadError; }

    /** Validation, optimistic concurrency and ID ownership all precede a single atomic commit. */
    public MapDefinition saveMap(long expectedRevision, MapDefinition candidate) {
        requireWritable(expectedRevision);
        if (candidate != null) requireNotRunning(candidate.id());
        List<String> errors = MapZoneModel.validate(candidate);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        MapDefinition old = candidate.id() == 0 ? null : maps.get(candidate.id());
        if (candidate.id() != 0 && old == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.map_does_not_exist", candidate.id()));
        if (old == null && maps.size() >= MapZoneModel.MAX_MAPS) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.world_allows_most_maps", MapZoneModel.MAX_MAPS));
        Set<Long> ownedZones = new HashSet<>();
        if (old != null) old.bombingZones().forEach(zone -> ownedZones.add(zone.id()));
        for (BombingZone zone : candidate.bombingZones()) {
            if (zone.id() > 0 && !ownedZones.contains(zone.id())) {
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.bombardment_zone_id_does_not_belong_map_use", zone.id()));
            }
        }
        long assignedMapId = old == null ? allocate(nextMapId(), Texts.tr("screen.tacz_pubg.map_config.map")) : old.id();
        long nextZone = nextBombingZoneId;
        List<BombingZone> zones = new ArrayList<>();
        for (BombingZone zone : candidate.bombingZones()) {
            long id = zone.id();
            if (id == 0) { id = allocate(nextZone, Texts.tr("screen.tacz_pubg.zone_settings.bombardment_zone")); nextZone++; }
            zones.add(new BombingZone(id, zone.area(), zone.missilesPerMinute(), zone.missileDamage(), zone.blastRadius()));
        }
        MapDefinition assigned = new MapDefinition(assignedMapId, candidate.name(), candidate.dimension(),
                candidate.minX(), candidate.minZ(), candidate.maxX(), candidate.maxZ(), candidate.safeZone(),
                candidate.blueDamagePerSecond(), zones, candidate.flightPath(), candidate.visualStyle(), candidate.stagingSpawn());
        // The fully assigned representation, including longer numeric IDs, must fit network/storage limits.
        MapZoneModel.toJson(assigned);
        if (runtimeOwner != null) com.example.examplemod.backup.MapResetService.validateSave(runtimeOwner, old, assigned);
        if (old == null) clearMapAssociations(assignedMapId);
        maps.put(assignedMapId, assigned);
        nextBombingZoneId = nextZone;
        revision++;
        setDirty();
        if (runtimeOwner != null) com.example.examplemod.backup.MapResetService.saved(runtimeOwner, old, assigned);
        return assigned;
    }

    public void deleteMap(long expectedRevision, long id) {
        deleteMap(expectedRevision, id, false);
    }

    /** Explicit administrator confirmation abandons pending recovery instead of waiting for it. */
    public void forceDeleteMap(long expectedRevision, long id) {
        deleteMap(expectedRevision, id, true);
    }

    private void deleteMap(long expectedRevision, long id, boolean force) {
        requireWritable(expectedRevision);
        if (!maps.containsKey(id)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.map_does_not_exist", id));
        if (!force) {
            requireNotRunning(id);
            if (runtimeOwner != null) com.example.examplemod.backup.MapResetService.requireEditable(runtimeOwner, id);
        } else if (runtimeOwner != null) {
            // Check every persistent store before canceling any live work. Force bypasses task
            // readiness, not corruption of shared save files containing other maps' data.
            com.example.examplemod.backup.MapResetService.requireDeletable(runtimeOwner);
            requireMapAssociationsRemovable(id);
            com.example.examplemod.zones.match.MatchService.abortForMapDeletion(runtimeOwner, maps.get(id));
        }
        clearMapAssociations(id);
        maps.remove(id);
        if (runtimeOwner != null) com.example.examplemod.backup.MapResetService.deleted(runtimeOwner, id);
        revision++;
        setDirty();
    }

    /** Deleted high IDs are reusable; surviving maps keep their stable references. */
    private long nextMapId() { return maps.isEmpty() ? 1 : maps.lastKey() + 1; }

    private void clearMapAssociations(long id) {
        if (runtimeOwner == null) return;
        var settings = com.example.examplemod.zones.match.MatchConfigSavedData.get(runtimeOwner);
        var rooms = com.example.examplemod.zones.match.lobby.LobbySavedData.get(runtimeOwner);
        // Validate both persistent stores before removing either record.
        requireMapAssociationsRemovable(id);
        com.example.examplemod.zones.match.MatchService.forgetDeletedMap(runtimeOwner, id);
        settings.removeMap(id);
        rooms.removeMap(id);
        com.example.examplemod.zones.match.lobby.LobbyService.forgetDeletedMap(runtimeOwner, id);
    }

    private void requireMapAssociationsRemovable(long id) {
        com.example.examplemod.zones.match.MatchConfigSavedData.get(runtimeOwner).requireMapRemovable(id);
        com.example.examplemod.zones.match.lobby.LobbySavedData.get(runtimeOwner).requireMapRemovable(id);
    }

    private void requireWritable(long expectedRevision) {
        if (!loadError.isEmpty()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_zone_saved_data.failed_read_map_data_original_data_protected_editing", loadError));
        if (expectedRevision != revision) throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_zone_saved_data.map_configuration_changed_reload_before_saving"));
        if (revision == Long.MAX_VALUE) throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_zone_saved_data.map_configuration_version_exhausted"));
    }

    private void requireNotRunning(long mapId) {
        if (runtimeOwner != null && mapId > 0
                && com.example.examplemod.zones.match.MatchService.isRunning(runtimeOwner, mapId))
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_zone_saved_data.map_has_active_match_run_pubgdebug_endmatch_before", mapId, mapId));
    }

    private static long allocate(long next, String label) {
        if (next <= 0 || next == Long.MAX_VALUE) throw new IllegalStateException(label + Texts.tr("message.tacz_pubg.map_zone_saved_data.ids_exhausted"));
        return next;
    }

    // Package access for in-memory NBT checks; no real world files are opened by the checks.
    static MapZoneSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        MapZoneSavedData data = new MapZoneSavedData();
        if (tag.isEmpty()) return data;
        try {
            Set<String> allowed = Set.of("version", "revision", "next_map_id", "next_bombing_zone_id", "maps");
            for (String key : tag.getAllKeys()) if (!allowed.contains(key)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.unknown_saved_field", key));
            if (!tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.unsupported_map_data_version"));
            requireLong(tag, "revision"); requireLong(tag, "next_map_id"); requireLong(tag, "next_bombing_zone_id");
            long revision = tag.getLong("revision"), nextMap = tag.getLong("next_map_id"), nextZone = tag.getLong("next_bombing_zone_id");
            if (revision < 0 || nextMap <= 0 || nextZone <= 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.invalid_map_version_id_counter"));
            if (!(tag.get("maps") instanceof ListTag list) || (!list.isEmpty() && list.getElementType() != Tag.TAG_STRING)
                    || list.size() > MapZoneModel.MAX_MAPS) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.map_list_missing_has_wrong_type_exceeds_limit"));
            TreeMap<Long, MapDefinition> restored = new TreeMap<>();
            Set<Long> zones = new HashSet<>();
            long largestMap = 0, largestZone = 0;
            for (int i = 0; i < list.size(); i++) {
                MapDefinition map = MapZoneModel.parse(list.getString(i));
                if (map.id() <= 0 || restored.putIfAbsent(map.id(), map) != null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.saved_map_id_zero_duplicated"));
                largestMap = Math.max(largestMap, map.id());
                for (BombingZone zone : map.bombingZones()) {
                    if (zone.id() <= 0 || !zones.add(zone.id())) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.saved_bombardment_zone_id_zero_duplicated_across_world"));
                    largestZone = Math.max(largestZone, zone.id());
                }
            }
            if (nextMap <= largestMap || nextZone <= largestZone) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.saved_id_counter_behind_new_ids_cannot_assigned"));
            data.maps.putAll(restored);
            data.revision = revision; data.nextBombingZoneId = nextZone;
            // Older saves retained the lifetime high-water mark even after deleting every map.
            if (nextMap != data.nextMapId()) data.setDirty();
        } catch (RuntimeException error) {
            data.loadError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            data.protectedOriginal = tag.copy();
            LogUtils.getLogger().error("TaCZ PUBG map data could not be loaded. Original NBT is protected; editing is disabled: {}", data.loadError);
        }
        return data;
    }

    private static void requireLong(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LONG)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.missing_invalid_map_save_field", key));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        // Even an accidental external setDirty() must never replace unreadable original records with emptiness.
        if (protectedOriginal != null) return protectedOriginal.copy();
        tag.putInt("version", 1); tag.putLong("revision", revision);
        tag.putLong("next_map_id", nextMapId()); tag.putLong("next_bombing_zone_id", nextBombingZoneId);
        ListTag saved = new ListTag();
        for (MapDefinition map : maps.values()) saved.add(StringTag.valueOf(MapZoneModel.toJson(map)));
        tag.put("maps", saved);
        return tag;
    }
}
