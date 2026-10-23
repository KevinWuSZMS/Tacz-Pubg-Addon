package com.example.examplemod.zones.match.lobby;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.zones.MapZoneModel;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.Set;
import java.util.TreeMap;

/** Per-map next-match rooms are saved independently of running bot entities. */
public final class LobbySavedData extends SavedData {
    private static final Factory<LobbySavedData> FACTORY = new Factory<>(LobbySavedData::new, LobbySavedData::load);
    private final TreeMap<Long, LobbyModel.Room> rooms = new TreeMap<>();
    private long revision;
    private String loadError = "";
    private CompoundTag protectedOriginal;
    public static LobbySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "tacz_pubg_lobbies");
    }
    public long revision() { return revision; }
    public String loadError() { return loadError; }
    public boolean hasRoom(long id) { return rooms.containsKey(id); }
    public LobbyModel.Room room(long mapId, int teamSize) {
        requireReadable(); return rooms.getOrDefault(mapId, LobbyModel.defaults(mapId, teamSize));
    }
    public void requireMapRemovable(long mapId) {
        requireReadable();
        if (revision == Long.MAX_VALUE)
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_saved_data.lobby_version_counter_exhausted"));
    }
    public void removeMap(long mapId) {
        requireMapRemovable(mapId);
        rooms.remove(mapId);
        revision++; setDirty();
    }
    public void saveRoom(long expectedRevision, LobbyModel.Room room) {
        requireReadable(); LobbyModel.requireValid(room);
        if (revision != expectedRevision) throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_saved_data.lobby_version_changed_read_review_roster_draft"));
        if (revision == Long.MAX_VALUE) throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_saved_data.lobby_version_counter_exhausted"));
        if (!rooms.containsKey(room.mapId()) && rooms.size() >= MapZoneModel.MAX_MAPS)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_saved_data.world_allows_most_map_lobbies"));
        rooms.put(room.mapId(), room); revision++; setDirty();
    }
    private void requireReadable() {
        if (!loadError.isEmpty()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_saved_data.lobby_data_protected_check_saved_data_first", loadError));
    }
    public static LobbySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        LobbySavedData data = new LobbySavedData(); if (tag.isEmpty()) return data;
        try {
            if (!tag.getAllKeys().equals(Set.of("version", "revision", "rooms")) || !tag.contains("version", Tag.TAG_INT)
                    || tag.getInt("version") != 1 || !tag.contains("revision", Tag.TAG_LONG) || tag.getLong("revision") < 0
                    || !(tag.get("rooms") instanceof ListTag list) || list.size() > MapZoneModel.MAX_MAPS
                    || !list.isEmpty() && list.getElementType() != Tag.TAG_STRING) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_saved_data.check_saved_lobby_fields"));
            var restored = new TreeMap<Long, LobbyModel.Room>();
            for (int i = 0; i < list.size(); i++) {
                var room = LobbyModel.parse(list.getString(i));
                if (restored.putIfAbsent(room.mapId(), room) != null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_saved_data.keep_one_lobby_configuration_per_map"));
            }
            data.rooms.putAll(restored); data.revision = tag.getLong("revision");
        } catch (RuntimeException error) {
            data.loadError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); data.protectedOriginal = tag.copy();
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (protectedOriginal != null) return protectedOriginal.copy();
        tag.putInt("version", 1); tag.putLong("revision", revision); ListTag list = new ListTag();
        rooms.values().forEach(room -> list.add(StringTag.valueOf(LobbyModel.toJson(room)))); tag.put("rooms", list); return tag;
    }
}
