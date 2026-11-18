package com.example.examplemod.inventory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable world rules, shared by all loot tiers. Capacity is per individual item, not per stack. */
public record InventorySettings(List<String> hotbar, Map<String, Double> capacityOverrides,
                                Map<String, Integer> ammoStackLimits, boolean autoEquipmentReplacement,
                                boolean autoAttachmentEquip, boolean pubgDirectEquip) {
    public InventorySettings(List<String> hotbar, Map<String, Double> capacityOverrides, Map<String, Integer> ammoStackLimits) {
        this(hotbar, capacityOverrides, ammoStackLimits, false, false);
    }
    public InventorySettings(List<String> hotbar, Map<String, Double> capacityOverrides, Map<String, Integer> ammoStackLimits,
                             boolean autoEquipmentReplacement, boolean autoAttachmentEquip) {
        this(hotbar, capacityOverrides, ammoStackLimits, autoEquipmentReplacement, autoAttachmentEquip, true);
    }
    public static final int DEFAULT_AMMO_STACK = 999;
    public static final int MAX_AMMO_STACK = 4096;
    public static final List<String> ROLES = List.of("free", "melee", "throwable", "med_kit", "first_aid_kit", "bandage", "boost");
    public InventorySettings {
        hotbar = List.copyOf(hotbar);
        capacityOverrides = Map.copyOf(capacityOverrides);
        ammoStackLimits = Map.copyOf(ammoStackLimits);
        if (hotbar.size() != 9 || !hotbar.subList(0, 3).equals(List.of("long_gun", "long_gun", "handgun")))
            throw new IllegalArgumentException("Slots 1/2 must hold long guns; slot 3 must hold a handgun");
        var used = new java.util.HashSet<String>();
        for (String role : hotbar.subList(3, 9))
            if (!ROLES.contains(role) || (!role.equals("free") && !used.add(role)))
                throw new IllegalArgumentException("Invalid or duplicate hotbar role: " + role);
        if (capacityOverrides.size() > 8192 || ammoStackLimits.size() > 4096)
            throw new IllegalArgumentException("Too many inventory overrides");
        capacityOverrides.forEach((id, value) -> {
            if (!id.matches("(item|gun|ammo|attachment):[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || !Double.isFinite(value) || value < 0 || value > 1_000_000)
                throw new IllegalArgumentException("Invalid item capacity: " + id);
        });
        ammoStackLimits.forEach((id, value) -> {
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || value < 1 || value > MAX_AMMO_STACK)
                throw new IllegalArgumentException("Invalid ammo stack limit: " + id);
        });
    }
    public static InventorySettings defaults() {
        return new InventorySettings(List.of("long_gun", "long_gun", "handgun", "melee", "throwable",
                "free", "med_kit", "first_aid_kit", "bandage"), Map.of(), Map.of());
    }
    public int slot(String role) { return hotbar.indexOf(role); }
    public InventorySettings withRole(int slot, String role) {
        if (slot < 3 || slot > 8 || !ROLES.contains(role)) throw new IllegalArgumentException("Invalid editable hotbar slot");
        var values = new ArrayList<>(hotbar);
        if (!role.equals("free")) {
            int previous = values.indexOf(role);
            if (previous >= 3) values.set(previous, "free");
        }
        values.set(slot, role);
        return new InventorySettings(values, capacityOverrides, ammoStackLimits, autoEquipmentReplacement, autoAttachmentEquip, pubgDirectEquip);
    }
    public InventorySettings withCapacity(String key, Double value) {
        var values = new LinkedHashMap<>(capacityOverrides);
        if (value == null) values.remove(key); else values.put(key, value);
        return new InventorySettings(hotbar, values, ammoStackLimits, autoEquipmentReplacement, autoAttachmentEquip, pubgDirectEquip);
    }
    public InventorySettings withAmmoLimit(String id, Integer value) {
        var values = new LinkedHashMap<>(ammoStackLimits);
        if (value == null) values.remove(id); else values.put(id, value);
        return new InventorySettings(hotbar, capacityOverrides, values, autoEquipmentReplacement, autoAttachmentEquip, pubgDirectEquip);
    }
    public InventorySettings withAutoEquipmentReplacement(boolean enabled) {
        return new InventorySettings(hotbar, capacityOverrides, ammoStackLimits, enabled, autoAttachmentEquip, pubgDirectEquip);
    }
    public InventorySettings withAutoAttachmentEquip(boolean enabled) {
        return new InventorySettings(hotbar, capacityOverrides, ammoStackLimits, autoEquipmentReplacement, enabled, pubgDirectEquip);
    }
    public InventorySettings withPubgDirectEquip(boolean enabled) {
        return new InventorySettings(hotbar, capacityOverrides, ammoStackLimits, autoEquipmentReplacement, autoAttachmentEquip, enabled);
    }
    public static InventorySettings parse(JsonElement element) {
        if (element == null || element.isJsonNull()) return defaults();
        JsonObject root = element.getAsJsonObject();
        if (!Set.of("hotbar", "capacityOverrides", "ammoStackLimits", "autoEquipmentReplacement", "autoAttachmentEquip", "pubgDirectEquip").containsAll(root.keySet()))
            throw new IllegalArgumentException("Unknown inventory setting");
        var hotbar = root.has("hotbar") ? new ArrayList<String>() : new ArrayList<>(defaults().hotbar());
        if (root.has("hotbar")) for (var value : root.getAsJsonArray("hotbar")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Hotbar roles must be strings");
            hotbar.add(value.getAsString());
        }
        var capacities = new LinkedHashMap<String, Double>();
        if (root.has("capacityOverrides")) root.getAsJsonObject("capacityOverrides").entrySet().forEach(entry -> {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("Capacity must be numeric");
            capacities.put(entry.getKey(), entry.getValue().getAsDouble());
        });
        var limits = new LinkedHashMap<String, Integer>();
        if (root.has("ammoStackLimits")) root.getAsJsonObject("ammoStackLimits").entrySet().forEach(entry -> {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("Stack limit must be numeric");
            limits.put(entry.getKey(), entry.getValue().getAsBigDecimal().intValueExact());
        });
        return new InventorySettings(hotbar, capacities, limits, flag(root, "autoEquipmentReplacement"),
                flag(root, "autoAttachmentEquip"), flag(root, "pubgDirectEquip", true));
    }
    private static boolean flag(JsonObject root, String key) {
        return flag(root, key, false);
    }
    private static boolean flag(JsonObject root, String key, boolean fallback) {
        if (!root.has(key)) return fallback;
        var value = root.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(key + " must be boolean");
        return value.getAsBoolean();
    }
}
