package com.example.examplemod.modelscale;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Save-wide settings and user-defined defaults, shared by every dimension. */
public final class ModelScaleSavedData extends SavedData {
    private static final Factory<ModelScaleSavedData> FACTORY = new Factory<>(ModelScaleSavedData::new, ModelScaleSavedData::load);
    private ModelScaleSettings settings = ModelScaleSettings.initial();
    private long revision;
    private CompoundTag unreadable;
    private CompoundTag previousSchema1;
    public static ModelScaleSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "tacz_pubg_model_scales");
    }
    public long revision() { return revision; }
    public ModelScaleSettings settings() { return settings; }
    public boolean save(long expectedRevision, ModelScaleSettings next) {
        if (unreadable != null || expectedRevision != revision || revision == Long.MAX_VALUE) return false;
        settings = next; revision++; setDirty(); return true;
    }
    static ModelScaleSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new ModelScaleSavedData();
        try {
            int schema = tag.getInt("schema");
            if (schema != 1 && schema != 2) throw new IllegalArgumentException("Unsupported model scale schema");
            data.settings = ModelScaleSettings.parse(tag.getString("settings"));
            data.revision = Math.max(0, tag.getLong("revision"));
            if (schema == 1) {
                data.previousSchema1 = tag.copy();
                data.settings = data.settings.alignHelmetWornScales();
                if (data.revision < Long.MAX_VALUE) data.revision++;
                data.setDirty();
            } else if (tag.contains("previous_schema1", net.minecraft.nbt.Tag.TAG_COMPOUND))
                data.previousSchema1 = tag.getCompound("previous_schema1").copy();
        } catch (RuntimeException error) {
            data.unreadable = tag.copy();
            com.mojang.logging.LogUtils.getLogger().error("Preserving unreadable model scale settings", error);
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (unreadable != null) return unreadable.copy();
        tag.putInt("schema", 2); tag.putLong("revision", revision); tag.putString("settings", settings.toJson());
        if (previousSchema1 != null) tag.put("previous_schema1", previousSchema1.copy());
        return tag;
    }
}
