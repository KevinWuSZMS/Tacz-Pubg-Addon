package com.example.examplemod.gunbalance;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.util.AllowAttachmentTagMatcher;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/** Native tag/network transformation; initial mode revokes only the nine new IDs. */
public final class PubgMagazineCompatibility {
    private static final ResourceLocation MARKER = ResourceLocation.fromNamespaceAndPath("tacz_pubg", "experimental_pubg_magazines");
    private static final Gson GSON = new Gson();

    private PubgMagazineCompatibility() {}

    public static boolean isExperimentEnabled(ResourceLocation gunId, ResourceLocation attachmentId) {
        if (gunId == null || attachmentId == null || !PubgMagazineRules.MAGAZINE_IDS.contains(attachmentId.toString())) return false;
        Set<String> active = CommonAssetsManager.get().getAttachmentTags(MARKER);
        if (active == null || !active.contains(attachmentId.toString())) return false;
        if (!PubgMagazinePolicy.allowedFor(gunId.toString(), true).contains(attachmentId.toString())) return false;
        return TimelessAPI.getCommonGunIndex(gunId).map(index -> {
            var data = index.getGunData();
            int[] capacities = data.getExtendedMagAmmoAmount();
            return data.getAllowAttachments() != null && data.getAllowAttachments().contains(AttachmentType.EXTENDED_MAG)
                    && capacities != null && capacities.length >= 3 && capacities[0] > 0 && capacities[1] > 0 && capacities[2] > 0
                    && !data.getBuiltInAttachments().containsKey(AttachmentType.EXTENDED_MAG)
                    && AllowAttachmentTagMatcher.match(gunId, attachmentId);
        }).orElse(false);
    }

    public static Map<ResourceLocation, String> applyNativeTags(ResourceManager manager,
            Map<ResourceLocation, Set<String>> tags, Map<ResourceLocation, Set<String>> allowed,
            Map<ResourceLocation, String> networkCache, boolean enabled) {
        Map<ResourceLocation, String> updated = new HashMap<>(networkCache);
        // Capture tags before removing marker: a nested old marker must be expanded
        // and stripped as well, without discarding unrelated attachment leaves.
        Map<String, Set<String>> namedTags = new HashMap<>();
        tags.forEach((id, values) -> namedTags.put(id.toString(), values));
        Set<String> loaded = enabled ? loadedMagazineIds(manager) : Set.of();
        tags.remove(MARKER); updated.remove(MARKER);
        if (enabled && !loaded.isEmpty()) {
            tags.put(MARKER, Set.copyOf(loaded)); updated.put(MARKER, GSON.toJson(new TreeSet<>(loaded)));
        }
        Map<ResourceLocation, Set<String>> additions = new HashMap<>();
        FileToIdConverter guns = FileToIdConverter.json("index/guns");
        if (enabled) for (var entry : guns.listMatchingResources(manager).entrySet()) {
            ResourceLocation gunId = guns.fileToId(entry.getKey());
            try (var reader = entry.getValue().openAsReader()) {
                JsonObject index = GSON.fromJson(reader, JsonObject.class);
                if (index == null || !index.has("data")) continue;
                ResourceLocation dataId = ResourceLocation.parse(index.get("data").getAsString());
                if (!GunBalanceService.originallyAllowedMagazine(dataId)) continue;
                ResourceLocation dataPath = ResourceLocation.fromNamespaceAndPath(dataId.getNamespace(), "data/guns/" + dataId.getPath() + ".json");
                var resource = manager.getResource(dataPath);
                if (resource.isEmpty()) continue;
                try (var dataReader = resource.get().openAsReader()) {
                    JsonObject data = GSON.fromJson(dataReader, JsonObject.class);
                    if (data == null || !PubgMagazineRules.supportsNativeMagazineLevels(data)) continue;
                }
                Set<String> selected = new HashSet<>(PubgMagazinePolicy.allowedFor(gunId.toString(), true));
                selected.retainAll(loaded);
                if (!selected.isEmpty()) additions.put(gunId, Set.copyOf(selected));
            } catch (IOException | RuntimeException error) {
                com.mojang.logging.LogUtils.getLogger().warn("Cannot enable PUBG magazines for {}", gunId, error);
            }
        }
        Set<ResourceLocation> gunIds = new HashSet<>(allowed.keySet()); gunIds.addAll(additions.keySet());
        for (ResourceLocation gunId : gunIds) {
            Set<String> values = PubgMagazineRules.applyPolicy(allowed.getOrDefault(gunId, Set.of()), namedTags,
                    additions.getOrDefault(gunId, Set.of()), enabled);
            allowed.put(gunId, values);
            ResourceLocation key = ResourceLocation.fromNamespaceAndPath(gunId.getNamespace(), "allow_attachments/" + gunId.getPath());
            updated.put(key, GSON.toJson(new TreeSet<>(values)));
        }
        AllowAttachmentTagMatcher.resetCache();
        return Map.copyOf(updated);
    }

    private static Set<String> loadedMagazineIds(ResourceManager manager) {
        Set<String> result = new HashSet<>();
        FileToIdConverter indices = FileToIdConverter.json("index/attachments");
        for (var entry : indices.listMatchingResources(manager).entrySet()) {
            String id = indices.fileToId(entry.getKey()).toString();
            if (!PubgMagazineRules.MAGAZINE_IDS.contains(id)) continue;
            try (var reader = entry.getValue().openAsReader()) {
                JsonObject index = GSON.fromJson(reader, JsonObject.class);
                if (index != null && index.has("type") && "extended_mag".equalsIgnoreCase(index.get("type").getAsString())) result.add(id);
            } catch (IOException | RuntimeException error) {
                com.mojang.logging.LogUtils.getLogger().warn("Cannot read PUBG magazine {}", id, error);
            }
        }
        return Set.copyOf(result);
    }
}
