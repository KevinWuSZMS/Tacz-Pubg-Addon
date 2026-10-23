package com.example.examplemod.pubgsettings;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.Set;

public final class PubgSettingsSavedData extends SavedData {
    private static final Factory<PubgSettingsSavedData> FACTORY = new Factory<>(PubgSettingsSavedData::new,PubgSettingsSavedData::load);
    private PubgSettings settings = PubgSettings.initial();
    private long revision;
    private CompoundTag protectedOriginal;
    public static PubgSettingsSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY,"tacz_pubg_battle_hud");
    }
    public PubgSettings settings() { return settings; }
    public long revision() { return revision; }
    public boolean readable() { return protectedOriginal == null; }
    public boolean update(long expected,PubgSettings next) {
        if (!readable() || expected != revision || revision == Long.MAX_VALUE) return false;
        settings = next; revision++; setDirty(); return true;
    }
    static PubgSettingsSavedData load(CompoundTag tag,HolderLookup.Provider registries) {
        var data = new PubgSettingsSavedData();
        if (tag.isEmpty()) return data;
        try {
            if (!tag.getAllKeys().equals(Set.of("version","revision","settings"))
                    || !tag.contains("version",Tag.TAG_INT) || tag.getInt("version") != 1
                    || !tag.contains("revision",Tag.TAG_LONG) || tag.getLong("revision") < 0
                    || !tag.contains("settings",Tag.TAG_STRING)) throw new IllegalArgumentException("Invalid battle settings save");
            data.settings = PubgSettings.parse(tag.getString("settings")); data.revision = tag.getLong("revision");
        } catch (RuntimeException invalid) { data.protectedOriginal = tag.copy(); }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        if (protectedOriginal != null) return protectedOriginal.copy();
        tag.putInt("version",1); tag.putLong("revision",revision); tag.putString("settings",settings.toJson()); return tag;
    }
}
