package com.example.examplemod.zones.match;

import com.example.examplemod.localization.Texts;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.List;
import java.util.TreeMap;
import java.util.Set;

/** Named rule libraries are independent of map selection and never change a running session. */
public final class MatchRulePresets extends SavedData {
    public static final int MAX_PRESETS = 32;
    private static final Factory<MatchRulePresets> FACTORY = new Factory<>(MatchRulePresets::new, MatchRulePresets::load);
    private final TreeMap<String, MatchConfigModel.Settings> values = new TreeMap<>();
    private CompoundTag protectedOriginal;
    public static MatchRulePresets get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "tacz_pubg_rule_presets");
    }
    public List<MatchConfigPayloads.RulePreset> list() {
        readable();
        return values.keySet().stream().map(MatchConfigPayloads.RulePreset::new).toList();
    }
    public MatchConfigModel.Settings load(String name, long mapId) {
        readable();
        var value = values.get(name);
        if (value == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_rule_presets.select_saved_rule_configuration"));
        return MatchConfigModel.forMap(value, mapId);
    }
    public void put(String name, MatchConfigModel.Settings settings) {
        readable(); checkName(name); MatchConfigModel.toJson(settings);
        if (!values.containsKey(name) && values.size() >= MAX_PRESETS) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_rule_presets.rule_library_allows_most_configurations"));
        values.put(name, settings); setDirty();
    }
    /** Create/update/rename without overwriting another named rule or losing the old one on failure. */
    public void saveRule(String previousName, String name, MatchConfigModel.Settings settings) {
        readable(); checkName(name); MatchConfigModel.toJson(settings);
        if (!previousName.isEmpty() && !values.containsKey(previousName))
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_rule_presets.select_saved_rule_configuration"));
        if (!name.equals(previousName) && values.containsKey(name))
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_rule_presets.duplicate_rule_name"));
        if (previousName.isEmpty() && values.size() >= MAX_PRESETS)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_rule_presets.rule_library_allows_most_configurations"));
        if (!previousName.isEmpty()) values.remove(previousName);
        values.put(name, settings); setDirty();
    }
    public void delete(String name) {
        readable(); checkName(name);
        if (values.remove(name) == null)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_rule_presets.select_saved_rule_configuration"));
        setDirty();
    }
    private void readable() { if (protectedOriginal != null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_rule_presets.failed_read_rule_library_original_data_protected")); }
    public static void checkName(String name) {
        if (name == null || name.isBlank() || name.length() > 80 || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_rule_presets.rule_names_contain_displayable_characters"));
    }
    private static MatchRulePresets load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new MatchRulePresets();
        if (tag.isEmpty()) return data;
        try {
            if (!tag.getAllKeys().equals(Set.of("version", "rules")) || tag.getInt("version") != 1 || !(tag.get("rules") instanceof ListTag list)
                    || list.size() > MAX_PRESETS || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_rule_presets.invalid_rule_library_format"));
            for (int i = 0; i < list.size(); i++) {
                var row = list.getCompound(i);
                if (!row.getAllKeys().equals(Set.of("name", "json")) || !row.contains("name", Tag.TAG_STRING) || !row.contains("json", Tag.TAG_STRING))
                    throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_rule_presets.invalid_rule_library_entry_format"));
                String name = row.getString("name"); checkName(name);
                if (data.values.putIfAbsent(name, MatchConfigModel.parse(row.getString("json"))) != null)
                    throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_rule_presets.duplicate_rule_name"));
            }
        } catch (RuntimeException error) { data.values.clear(); data.protectedOriginal = tag.copy(); }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (protectedOriginal != null) return protectedOriginal.copy();
        tag.putInt("version", 1); ListTag list = new ListTag();
        values.forEach((name, rules) -> { var row = new CompoundTag(); row.putString("name", name);
            row.putString("json", MatchConfigModel.toJson(rules)); list.add(row); });
        tag.put("rules", list); return tag;
    }
}
