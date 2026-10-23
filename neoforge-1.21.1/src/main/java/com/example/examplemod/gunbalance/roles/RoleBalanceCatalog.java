package com.example.examplemod.gunbalance.roles;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Strict, atomic catalog parsing, shared by runtime and offline checks. */
public record RoleBalanceCatalog(Map<String, Entry> byGun, Map<String, Entry> byData) {
    public record Entry(String gunId, String dataId, String role, RoleBalanceTransform.Target target) {}

    public RoleBalanceCatalog {
        byGun = Map.copyOf(byGun);
        byData = Map.copyOf(byData);
    }

    public static RoleBalanceCatalog parse(JsonObject root) {
        if (root.get("schemaVersion").getAsInt() != 1 || root.get("stepMeters").getAsInt() != 5)
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_catalog.unsupported_approved_role_catalog_schema"));
        Map<String, Entry> guns = new HashMap<>();
        Map<String, Entry> data = new HashMap<>();
        int applied = 0, preserved = 0;
        for (JsonElement element : root.getAsJsonArray("guns")) {
            JsonObject row = element.getAsJsonObject();
            String gunId = id(row, "gunId"), dataId = id(row, "dataId");
            String role = row.get("role").getAsString();
            RoleBalanceTransform.Target target = null;
            switch (row.get("action").getAsString()) {
                case "apply" -> { target = parseTarget(row.getAsJsonObject("target")); applied++; }
                case "preserve" -> { if (row.has("target")) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_catalog.preserved_role_has_a_target")); preserved++; }
                default -> throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_catalog.unknown_role_action"));
            }
            Entry entry = new Entry(gunId, dataId, role, target);
            if (guns.putIfAbsent(gunId, entry) != null) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_catalog.duplicate_gun_role") + gunId);
            Entry previous = data.putIfAbsent(dataId, entry);
            if (previous != null && (!Objects.equals(previous.target(), target) || !previous.role().equals(role)))
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_catalog.conflicting_shared_data_role") + dataId);
        }
        if (applied != root.get("appliedGuns").getAsInt() || preserved != root.get("preservedGuns").getAsInt())
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_catalog.role_approval_counts_do_not_match_catalog_contents"));
        return new RoleBalanceCatalog(guns, data);
    }

    public static RoleBalanceTransform.Target parseTarget(JsonObject target) {
        var envelope = new ArrayList<RoleBalanceTransform.Knot>();
        for (JsonElement element : target.getAsJsonArray("damageEnvelope")) {
            JsonObject point = element.getAsJsonObject();
            envelope.add(new RoleBalanceTransform.Knot(point.get("distance").getAsDouble(), point.get("retained").getAsDouble()));
        }
        return new RoleBalanceTransform.Target(target.get("damage10").getAsDouble(), target.get("head").getAsDouble(),
                target.get("aim").getAsDouble(), target.get("hip").getAsDouble(), target.get("ads").getAsDouble(),
                target.get("rpm").getAsDouble(), target.get("rpmCap").getAsDouble(), target.get("pellets").getAsInt(), envelope);
    }

    private static String id(JsonObject object, String key) {
        String value = object.get(key).getAsString();
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_catalog.invalid_role_resource_id") + value);
        return value;
    }
}
