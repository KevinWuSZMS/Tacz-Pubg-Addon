package com.example.examplemod.throwables;

import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Independent save-wide transaction; malformed original data is preserved. */
public final class ThrowableSettingsSavedData extends SavedData {
    private static final Factory<ThrowableSettingsSavedData> FACTORY = new Factory<>(ThrowableSettingsSavedData::new, ThrowableSettingsSavedData::load);
    private JsonObject settings = new JsonObject();
    private long revision;
    private CompoundTag protectedOriginal;
    private CompoundTag loadedOriginal;
    private String error = "";
    public static ThrowableSettingsSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "tacz_pubg_throwable_settings");
    }
    public JsonObject settings() { return settings.deepCopy(); }
    /** Internal immutable snapshot. update replaces it atomically; readers must not mutate it. */
    JsonObject view() { return settings; }
    public long revision() { return revision; }
    public String error() { return error; }
    /** Native subtype validation runs only after LR's resource registry has finished loading. */
    public void verifyNative() {
        if (!error.isEmpty()) return;
        try { ThrowableSettingsService.validateNative(settings); }
        catch (RuntimeException failure) {
            protectedOriginal = loadedOriginal == null ? serialized() : loadedOriginal.copy();
            error = String.valueOf(failure.getMessage());
            settings = new JsonObject();
            ThrowableSettingsService.clearCaches();
            com.mojang.logging.LogUtils.getLogger().error("Preserving invalid native throwable settings: {}", error);
        }
    }
    public void update(long expected, JsonObject value) {
        if (!error.isEmpty()) throw new IllegalStateException(error);
        if (expected != revision) throw new IllegalStateException("Settings changed; reload before saving");
        JsonObject validated = ThrowableSettingsModel.parse(value.toString());
        ThrowableSettingsService.validateNative(validated);
        if (settings.equals(validated)) return;
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("Settings revision exhausted");
        settings = validated; revision++; setDirty(); ThrowableSettingsService.clearCaches();
        loadedOriginal = null;
    }
    static ThrowableSettingsSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new ThrowableSettingsSavedData();
        data.loadedOriginal = tag.copy();
        try {
            if (tag.getInt("Schema") != 1 || !tag.contains("Settings", 8) || tag.getLong("Revision") < 0)
                throw new IllegalArgumentException("Invalid throwable settings format");
            data.settings = ThrowableSettingsModel.parse(tag.getString("Settings")); data.revision = tag.getLong("Revision");
        } catch (RuntimeException failure) { data.protectedOriginal = tag.copy(); data.error = String.valueOf(failure.getMessage()); }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (protectedOriginal != null) return protectedOriginal.copy();
        tag.putInt("Schema", 1); tag.putLong("Revision", revision); tag.putString("Settings", settings.toString()); return tag;
    }
    private CompoundTag serialized() {
        var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putLong("Revision", revision);
        tag.putString("Settings", settings.toString()); return tag;
    }
}
