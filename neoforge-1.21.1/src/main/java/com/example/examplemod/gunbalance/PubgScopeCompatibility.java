package com.example.examplemod.gunbalance;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.util.AllowAttachmentTagMatcher;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/** The native attachment-tag cache carries both compatibility and the research-profile marker. */
public final class PubgScopeCompatibility {
    private static final ResourceLocation MARKER = ResourceLocation.fromNamespaceAndPath(
            "tacz_pubg", "universal_pubg_scopes");
    private static final Gson GSON = new Gson();

    private PubgScopeCompatibility() {}

    /** Also valid on a remote client: TaCZ synchronizes these tags with its gun data. */
    public static boolean isUniversalScopeEnabled(ResourceLocation gunId, ResourceLocation scopeId) {
        if (scopeId == null || !PubgScopeRules.SCOPE_IDS.contains(scopeId.toString())) return false;
        Set<String> scopes = CommonAssetsManager.get().getAttachmentTags(MARKER);
        return scopes != null && scopes.contains(scopeId.toString())
                && TimelessAPI.getCommonGunIndex(gunId).map(index -> index.getGunData().getAllowAttachments() != null
                        && index.getGunData().getAllowAttachments().contains(AttachmentType.SCOPE)).orElse(false)
                && AllowAttachmentTagMatcher.match(gunId, scopeId);
    }

    public static Map<ResourceLocation, String> extendNativeTags(ResourceManager manager,
            Map<ResourceLocation, Set<String>> tags,
            Map<ResourceLocation, Set<String>> allowed,
            Map<ResourceLocation, String> networkCache) {
        Set<String> allScopes = loadedScopeIds(manager);
        Set<String> scopes = new HashSet<>(allScopes);
        scopes.retainAll(PubgScopeRules.SCOPE_IDS);
        if (scopes.isEmpty()) return networkCache;
        Map<ResourceLocation, String> updated = new HashMap<>(networkCache);
        tags.put(MARKER, Set.copyOf(scopes));
        updated.put(MARKER, GSON.toJson(new TreeSet<>(scopes)));
        Map<String, Set<String>> namedTags = new HashMap<>();
        tags.forEach((id, values) -> namedTags.put(id.toString(), values));
        FileToIdConverter guns = FileToIdConverter.json("index/guns");
        for (var entry : guns.listMatchingResources(manager).entrySet()) {
            ResourceLocation gunId = guns.fileToId(entry.getKey());
            boolean originalScopeSlot;
            try (var reader = entry.getValue().openAsReader()) {
                JsonObject index = GSON.fromJson(reader, JsonObject.class);
                if (index == null || !index.has("data")) continue;
                originalScopeSlot = GunBalanceService.originallyAllowedScope(
                        ResourceLocation.parse(index.get("data").getAsString()));
            } catch (IOException | RuntimeException error) {
                com.mojang.logging.LogUtils.getLogger().warn("Cannot enable PUBG scopes for {}", gunId, error);
                continue;
            }
            Set<String> policy = PubgScopePolicy.allowedFor(gunId.toString(), originalScopeSlot);
            if (policy == null) continue;
            Set<String> availablePolicy = new HashSet<>(policy);
            availablePolicy.retainAll(scopes);
            Set<String> values = new HashSet<>(PubgScopeRules.applyPolicy(
                    allowed.getOrDefault(gunId, Set.of()), namedTags, availablePolicy, originalScopeSlot));
            allowed.put(gunId, values);
            ResourceLocation key = ResourceLocation.fromNamespaceAndPath(gunId.getNamespace(),
                    "allow_attachments/" + gunId.getPath());
            updated.put(key, GSON.toJson(new TreeSet<>(values)));
        }
        AllowAttachmentTagMatcher.resetCache();
        return Map.copyOf(updated);
    }

    private static Set<String> loadedScopeIds(ResourceManager manager) {
        Set<String> scopes = new HashSet<>();
        FileToIdConverter indices = FileToIdConverter.json("index/attachments");
        for (var entry : indices.listMatchingResources(manager).entrySet()) {
            String id = indices.fileToId(entry.getKey()).toString();
            try (var reader = entry.getValue().openAsReader()) {
                JsonObject index = GSON.fromJson(reader, JsonObject.class);
                if (index != null && index.has("type") && "scope".equalsIgnoreCase(index.get("type").getAsString()))
                    scopes.add(id);
            } catch (IOException | RuntimeException error) {
                com.mojang.logging.LogUtils.getLogger().warn("Cannot enable PUBG scope {}", id, error);
            }
        }
        return scopes;
    }
}
