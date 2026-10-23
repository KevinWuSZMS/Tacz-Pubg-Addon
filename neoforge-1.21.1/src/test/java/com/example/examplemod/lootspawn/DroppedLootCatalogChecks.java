package com.example.examplemod.lootspawn;

import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;
import com.example.examplemod.lootconfig.LootConfigModel.General;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Saved-entry scope, atomic client transfers and fixed toss angles; no game is launched. */
public final class DroppedLootCatalogChecks {
    private static int checks;

    private DroppedLootCatalogChecks() {}

    public static void main(String[] args) throws Exception {
        savedEntries();
        try {
            clientSnapshots();
        } finally {
            DroppedLootCatalog.clearClient();
        }
        tossAngles();
        System.out.println("Dropped loot catalog checks passed: " + checks);
    }

    private static void savedEntries() {
        Document saved = new Document(General.defaults(), List.of(
                new Entry("item", "minecraft:stone", 1, 64, 0),
                new Entry("item", "minecraft:oak_slab", 1, 64, 0),
                new Entry("item", "minecraft:apple", 1, 4, 10),
                new Entry("gun", "test:rifle", 1, 1, 1),
                new Entry("ammo", "test:cartridge", 30, 30, 0),
                new Entry("attachment", "test:scope", 1, 1, 1)));
        Set<String> ids = DroppedLootCatalog.itemIds(saved);
        check(ids.equals(Set.of("minecraft:stone", "minecraft:oak_slab", "minecraft:apple")),
                "saved ordinary items and blocks register even with weight zero; asset IDs stay separate");
        check(!ids.contains("minecraft:diamond"), "an unsaved picker choice does not register drop behavior");
        check(!ids.contains("test:rifle") && !ids.contains("test:cartridge") && !ids.contains("test:scope"),
                "gun, ammo and attachment asset IDs are handled by their item interfaces, not registry matching");
        Document duplicateScope = new Document(General.defaults(), List.of(
                new Entry("item", "minecraft:stone", 1, 1, 0),
                new Entry("item", "minecraft:stone", 1, 64, 25)));
        check(DroppedLootCatalog.itemIds(duplicateScope).equals(Set.of("minecraft:stone")),
                "the matching cache cannot contain duplicate registry IDs");
        check(DroppedLootCatalog.itemIds(new Document(General.defaults(), List.of())).isEmpty(),
                "an empty ordinary-item list has an empty matching cache");
        rejectsUnsupported(() -> ids.add("minecraft:diamond"), "the published matching cache is immutable");

        List<Entry> large = new ArrayList<>();
        for (int i = 0; i < 5000; i++) large.add(new Entry("item", "test:item_" + i, 1, 1, 0));
        Set<String> largeIds = DroppedLootCatalog.itemIds(new Document(General.defaults(), large));
        check(largeIds.size() == 5000 && largeIds.contains("test:item_4999"),
                "saved scope is not truncated to 128 or 4096 entries");
    }

    private static void clientSnapshots() throws Exception {
        DroppedLootCatalog.clearClient();
        check(clientIds().isEmpty(), "a new connection starts without another world's ordinary-item scope");
        receive(UUID.randomUUID(), 0, 0, 1, "[\"minecraft:stone\"]");
        check(clientIds().equals(Set.of("minecraft:stone")), "one valid part atomically publishes the snapshot");

        UUID next = UUID.randomUUID();
        receive(next, 1, 0, 2, "[\"minecraft:apple\",");
        check(clientIds().equals(Set.of("minecraft:stone")), "an incomplete new snapshot leaves the old scope live");
        receive(next, 1, 1, 2, "\"minecraft:oak_slab\"]");
        check(clientIds().equals(Set.of("minecraft:apple", "minecraft:oak_slab")),
                "the final valid part replaces rather than unions the previous scope");

        UUID missing = UUID.randomUUID();
        receive(missing, 2, 0, 3, "[\"minecraft:diamond\",");
        receive(missing, 2, 2, 3, "\"minecraft:gold_ingot\"]");
        receive(missing, 2, 1, 3, "\"minecraft:iron_ingot\",");
        check(clientIds().equals(Set.of("minecraft:apple", "minecraft:oak_slab")),
                "out-of-order parts abort the incomplete transfer and retain the previous snapshot");

        UUID interrupted = UUID.randomUUID();
        receive(interrupted, 3, 0, 2, "[\"minecraft:diamond\",");
        receive(UUID.randomUUID(), 3, 1, 2, "\"minecraft:gold_ingot\"]");
        receive(interrupted, 3, 1, 2, "\"minecraft:iron_ingot\"]");
        check(clientIds().equals(Set.of("minecraft:apple", "minecraft:oak_slab")),
                "parts from a different transfer cannot complete or contaminate a pending snapshot");

        UUID replaced = UUID.randomUUID();
        receive(replaced, 4, 0, 2, "[\"minecraft:diamond\",");
        receive(UUID.randomUUID(), 5, 0, 1, "[\"minecraft:stick\"]");
        receive(replaced, 4, 1, 2, "\"minecraft:iron_ingot\"]");
        check(clientIds().equals(Set.of("minecraft:stick")),
                "a fresh complete transfer replaces a pending one; its old trailing part cannot overwrite it");

        UUID mismatchedHeader = UUID.randomUUID();
        receive(mismatchedHeader, 6, 0, 2, "[\"minecraft:diamond\",");
        receive(mismatchedHeader, 7, 1, 2, "\"minecraft:iron_ingot\"]");
        check(clientIds().equals(Set.of("minecraft:stick")),
                "a changed revision within one transfer is rejected without changing the live scope");
        receive(UUID.randomUUID(), -1, 0, 1, "[\"minecraft:diamond\"]");
        receive(UUID.randomUUID(), 8, 0, 0, "[]");
        receive(UUID.randomUUID(), 8, 0, DroppedLootCatalog.MAX_PARTS + 1, "[]");
        receive(UUID.randomUUID(), 8, -1, 1, "[]");
        receive(UUID.randomUUID(), 8, 1, 1, "[]");
        check(clientIds().equals(Set.of("minecraft:stick")), "invalid transfer headers cannot replace the live scope");

        receive(UUID.randomUUID(), 9, 0, 1, "[\"minecraft:stone\",\"minecraft:stone\"]");
        check(clientIds().equals(Set.of("minecraft:stone")), "duplicate received IDs collapse to one immutable match");
        Set<String> published = clientIds();
        rejectsUnsupported(() -> published.add("minecraft:diamond"), "client readers cannot mutate the received snapshot");

        UUID oldWorld = UUID.randomUUID();
        receive(oldWorld, 10, 0, 2, "[\"minecraft:diamond\",");
        DroppedLootCatalog.clearClient();
        check(clientIds().isEmpty(), "logout clears the current ordinary-item scope");
        receive(oldWorld, 10, 1, 2, "\"minecraft:iron_ingot\"]");
        check(clientIds().isEmpty(), "logout also discards the previous world's partial transfer");
        receive(UUID.randomUUID(), 0, 0, 1, "[\"minecraft:apple\"]");
        check(clientIds().equals(Set.of("minecraft:apple")), "a new world can start at revision zero after disconnect");
        receive(UUID.randomUUID(), 0, 0, 1, "[]");
        check(clientIds().isEmpty(), "a complete empty snapshot removes all ordinary-item matches");
    }

    private static void receive(UUID id, long revision, int index, int parts, String text) {
        DroppedLootCatalog.receive(new GroundLootPayloads.DropCatalogPart(id, revision, index, parts, text));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> clientIds() throws Exception {
        // Inspect only the immutable IDs. No registries, item models, level or client are created.
        Field field = DroppedLootCatalog.class.getDeclaredField("clientItems");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    private static void tossAngles() {
        for (float view : new float[] {0, 90, 179, -179, 350, -350, 720, -720}) {
            for (double random : new double[] {0, .5, 1}) {
                float yaw = DroppedLootPolicy.tossYaw(view, random);
                double expectedDeviation = random * 30 - 15;
                check(Math.abs(signedDifference(yaw, view) - expectedDeviation) < 1e-4,
                        "toss yaw is the view angle plus the bounded deviation, including wraparound");
                check(yaw >= 0 && yaw < 360, "every accepted toss angle is in the canonical finite range");
                for (int tick = 0; tick < 20; tick++) {
                    check(DroppedLootPolicy.stableYaw(yaw) == yaw,
                            "a once-selected toss yaw remains fixed throughout flight and landing");
                }
            }
        }
        for (float view : new float[] {0, 90, 179, -179}) {
            for (int step = 0; step <= 100; step++) {
                double random = step / 100.0;
                double deviation = signedDifference(DroppedLootPolicy.tossYaw(view, random), view);
                check(deviation >= -15.0001 && deviation <= 15.0001,
                        "all random samples remain within fifteen degrees of the player's view");
            }
        }
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            rejects(() -> DroppedLootPolicy.tossYaw(invalid, .5), "nonfinite view yaw is rejected");
        }
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                -.00001, 1.00001, -1, 2}) {
            rejects(() -> DroppedLootPolicy.tossYaw(90, invalid), "nonfinite or out-of-range random input is rejected");
        }
    }

    private static double signedDifference(float yaw, float view) {
        double difference = (yaw - (double) view) % 360;
        if (difference > 180) difference -= 360;
        if (difference < -180) difference += 360;
        return difference;
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    private static void rejects(Runnable action, String description) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            checks++;
            return;
        }
        throw new AssertionError(description);
    }

    private static void rejectsUnsupported(Runnable action, String description) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            checks++;
            return;
        }
        throw new AssertionError(description);
    }
}
