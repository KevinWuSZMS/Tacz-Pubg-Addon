package com.example.examplemod.visibility;

import com.example.examplemod.localization.Texts;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Server thread owned, save-wide settings with optimistic edit revisions. */
public final class VisibilitySavedData extends SavedData {
    private static final Factory<VisibilitySavedData> FACTORY = new Factory<>(VisibilitySavedData::new, VisibilitySavedData::load);
    private VisibilityPolicy.Settings settings = VisibilityPolicy.Settings.defaults();
    private long revision;
    private CompoundTag protectedOriginal;
    private String loadError = "";
    public static VisibilitySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "tacz_pubg_visibility");
    }
    public VisibilityPolicy.Settings settings() { return settings; }
    public long revision() { return revision; }
    public String loadError() { return loadError; }
    public void update(long expectedRevision, VisibilityPolicy.Settings next) {
        if (!loadError.isEmpty()) throw new IllegalStateException(loadError);
        if (expectedRevision != revision) throw new IllegalStateException(Texts.tr("message.tacz_pubg.visibility_saved_data.distance_settings_have_changed_reload_before_saving"));
        if (revision == Long.MAX_VALUE) throw new IllegalStateException(Texts.tr("message.tacz_pubg.visibility_saved_data.configuration_version_exhausted"));
        if (next == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.visibility_saved_data.missing_distance_configuration"));
        if (settings.equals(next)) return;
        settings = next; revision++; setDirty();
    }
    private static VisibilitySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new VisibilitySavedData();
        try {
            if (tag.getInt("Schema") != 1 || !tag.contains("Players", 3) || !tag.contains("Targets", 3)
                    || !tag.contains("Revision", 4) || tag.getLong("Revision") < 0)
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.visibility_saved_data.invalid_distance_data_format"));
            data.settings = new VisibilityPolicy.Settings(tag.getInt("Players"), tag.getInt("Targets"));
            data.revision = tag.getLong("Revision");
        } catch (RuntimeException error) {
            data.protectedOriginal = tag.copy();
            data.loadError = Texts.tr("message.tacz_pubg.visibility_saved_data.failed_read_distance_settings_original_data_retained", error.getMessage());
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (protectedOriginal != null) return protectedOriginal.copy();
        tag.putInt("Schema", 1); tag.putInt("Players", settings.players()); tag.putInt("Targets", settings.targets());
        tag.putLong("Revision", revision); return tag;
    }
}
