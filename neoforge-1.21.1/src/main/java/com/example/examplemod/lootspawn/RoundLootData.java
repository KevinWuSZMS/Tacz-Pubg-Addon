package com.example.examplemod.lootspawn;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Tombstones outlive inventories/entities that are currently offline or unloaded. */
public final class RoundLootData extends SavedData {
    record Round(String token, MapDefinition map) {}
    record Cleanup(MapDefinition map, long serial) {}
    final Map<Long, Round> active = new LinkedHashMap<>();
    final Set<String> retired = new HashSet<>();
    final Map<Long, Cleanup> cleanups = new LinkedHashMap<>();
    long serial;
    static RoundLootData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(RoundLootData::new, RoundLootData::load), "tacz_pubg_round_loot");
    }
    static RoundLootData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new RoundLootData(); data.serial = tag.getLong("serial");
        for (var entry : tag.getList("retired", Tag.TAG_STRING)) data.retired.add(entry.getAsString());
        for (var entry : tag.getList("cleanups", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) entry;
            var map = MapZoneModel.parse(t.getString("map"));
            data.cleanups.put(map.id(), new Cleanup(map, t.getLong("serial")));
        }
        // Matches do not resume after a restart. Their outstanding items must not escape cleanup.
        for (var entry : tag.getList("active", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) entry;
            var map = MapZoneModel.parse(t.getString("map"));
            data.retired.add(t.getString("token"));
            data.cleanups.put(map.id(), new Cleanup(map, ++data.serial));
            data.setDirty();
        }
        return data;
    }
    Round begin(MapDefinition map) {
        if (active.containsKey(map.id())) throw new IllegalStateException("Map loot round already exists");
        var round = new Round(UUID.randomUUID().toString(), map);
        active.put(map.id(), round); setDirty(); return round;
    }
    Round end(MapDefinition map) {
        var round = active.remove(map.id());
        if (round != null) retired.add(round.token);
        cleanups.put(map.id(), new Cleanup(map, ++serial)); setDirty(); return round;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("serial", serial);
        var tokens = new ListTag(); retired.stream().sorted().forEach(t -> tokens.add(StringTag.valueOf(t))); tag.put("retired", tokens);
        var rounds = new ListTag();
        active.values().forEach(r -> { var t = new CompoundTag(); t.putString("token", r.token); t.putString("map", MapZoneModel.toJson(r.map)); rounds.add(t); });
        tag.put("active", rounds);
        var cleanup = new ListTag();
        cleanups.values().forEach(c -> { var t = new CompoundTag(); t.putString("map", MapZoneModel.toJson(c.map)); t.putLong("serial", c.serial); cleanup.add(t); });
        tag.put("cleanups", cleanup); return tag;
    }
}
