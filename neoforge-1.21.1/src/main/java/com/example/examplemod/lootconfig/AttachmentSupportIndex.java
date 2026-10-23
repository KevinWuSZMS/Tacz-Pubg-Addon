package com.example.examplemod.lootconfig;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.example.examplemod.gunbalance.PubgMagazineCompatibility;
import com.example.examplemod.gunbalance.PubgMagazineRules;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** One read-only inventory scan after TaCZ's reload/synchronization has completed. */
public final class AttachmentSupportIndex {
    public record Inventory(int loadedGunCount, Map<String, String> physicalTypes,
                            Map<String, Set<String>> supportingGunIds,
                            Map<String, Set<String>> ammoSupportingGunIds) {}

    private AttachmentSupportIndex() {}

    public static Inventory capture() {
        Map<String, String> types = new HashMap<>();
        Map<String, Set<String>> support = new HashMap<>();
        Map<String, Set<String>> ammoSupport = new HashMap<>();
        Map<String, ItemStack> attachments = new HashMap<>();
        for (var entry : TimelessAPI.getAllCommonAttachmentIndex()) {
            ItemStack stack = AttachmentItemBuilder.create().setId(entry.getKey()).setCount(1).build();
            if (stack.isEmpty()) continue;
            String id = entry.getKey().toString();
            types.put(id, entry.getValue().getType().name().toLowerCase(java.util.Locale.ROOT));
            support.put(id, new HashSet<>()); attachments.put(id, stack);
        }
        for (var entry : TimelessAPI.getAllCommonAmmoIndex()) ammoSupport.put(entry.getKey().toString(), new HashSet<>());
        int gunCount = 0;
        for (var entry : TimelessAPI.getAllCommonGunIndex()) {
            ItemStack gun = GunItemBuilder.create().setId(entry.getKey()).build(null);
            IGun access = IGun.getIGunOrNull(gun);
            if (gun.isEmpty() || access == null) continue;
            gunCount++;
            String gunId = entry.getKey().toString();
            ResourceLocation ammo = entry.getValue().getGunData().getAmmoId();
            if (ammo != null && ammoSupport.containsKey(ammo.toString())) ammoSupport.get(ammo.toString()).add(gunId);
            for (var attachment : attachments.entrySet()) {
                AttachmentType type = AttachmentType.valueOf(types.get(attachment.getKey()).toUpperCase(java.util.Locale.ROOT));
                if (canUse(gun, access, ResourceLocation.parse(attachment.getKey()), type, attachment.getValue()))
                    support.get(attachment.getKey()).add(gunId);
            }
        }
        support.replaceAll((id, guns) -> Set.copyOf(guns)); ammoSupport.replaceAll((id, guns) -> Set.copyOf(guns));
        return new Inventory(gunCount, Map.copyOf(types), Map.copyOf(support), Map.copyOf(ammoSupport));
    }

    /** Recheck the actual spawned stack, including lock, occupied same ID, built-in and native slot/tag rules. */
    public static boolean canUse(ItemStack gun, String attachmentId) {
        if (gun == null || gun.isEmpty()) return false;
        IGun access = IGun.getIGunOrNull(gun);
        ResourceLocation id = ResourceLocation.tryParse(attachmentId);
        if (access == null || id == null) return false;
        var index = TimelessAPI.getCommonAttachmentIndex(id).orElse(null);
        if (index == null) return false;
        ItemStack attachment = AttachmentItemBuilder.create().setId(id).setCount(1).build();
        return !attachment.isEmpty() && canUse(gun, access, id, index.getType(), attachment);
    }

    private static boolean canUse(ItemStack gun, IGun access, ResourceLocation id, AttachmentType type, ItemStack attachment) {
        if (access.hasAttachmentLock(gun) || !access.allowAttachmentType(gun, type)
                || !access.allowAttachment(gun, attachment)) return false;
        // Native open slots can replace a different fallback built-in. A duplicate
        // of the exact built-in/installed ID adds no useful companion to this gun.
        if (id.equals(access.getBuiltInAttachmentId(gun, type)) || id.equals(access.getAttachmentId(gun, type))) return false;
        return !PubgMagazineRules.MAGAZINE_IDS.contains(id.toString())
                || PubgMagazineCompatibility.isExperimentEnabled(access.getGunId(gun), id);
    }
}
