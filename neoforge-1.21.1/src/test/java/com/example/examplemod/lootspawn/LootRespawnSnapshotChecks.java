package com.example.examplemod.lootspawn;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.UUID;

import com.example.examplemod.lootconfig.LootConfigModel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** In-memory snapshot persistence only; no server, world files or game is started. */
public final class LootRespawnSnapshotChecks {
    private static int checks;

    public static void main(String[] args) {
        var dimension = ResourceLocation.parse("minecraft:overworld");
        var positions = new ArrayList<>(List.of(new BlockPos(2, 64, 3), new BlockPos(2, 74, 3)));
        var job = new LootRespawnSavedData.Job(17, dimension, positions, 123, "", 2.5, com.example.examplemod.lootconfig.LootTier.LOW);
        positions.clear();
        check(job.floors().size() == 2, "job owns a snapshot of its region shape");
        rejects(() -> job.floors().clear(), "snapshot shape is immutable");
        check(new LootRespawnSavedData.Job(2, dimension, job.floors(), 1, "").rarity() == 1,
                "legacy job constructor defaults rarity to one");
        rejects(() -> new LootRespawnSavedData.Job(2, dimension, job.floors(), 1, "", Double.NaN), "NaN rarity rejected");
        rejects(() -> new LootRespawnSavedData.Job(2, dimension, job.floors(), 1, "", -1), "negative rarity rejected");
        rejects(() -> new LootRespawnSavedData.Job(2, dimension, job.floors(), 1, "", 1001), "rarity above limit rejected");

        var config = LootConfigModel.defaultDocument();
        var categories = new HashMap<>(config.categoryProbabilities());
        categories.replaceAll((key, value) -> 0.);
        categories.put("gun", .80);
        categories.put("stock", .20);
        config = new LootConfigModel.Document(config.general(), config.items(), config.gunSettings(), categories);
        var requester = UUID.fromString("00000000-0000-0000-0000-000000000017");
        var data = new LootRespawnSavedData();
        data.begin(config, List.of(job), requester);
        var tag = data.save(new CompoundTag(), null);
        check(tag.getInt("version") == 2, "new snapshots use version two");
        var restored = LootRespawnSavedData.load(tag, null);
        check(restored.job(17).equals(job), "shape, ID, seed and coefficient round-trip together");
        check(restored.document().equals(config), "saved density configuration round-trips");
        check(restored.document().categoryProbabilities().get("stock") == .20,
                "refresh keeps authored category shares across a restart");
        check(requester.equals(restored.requester()) && restored.epoch() == 1, "requester and epoch survive restart");
        restored.failed(17, "local space unavailable");
        check(restored.job(17).rarity() == 2.5, "failure status preserves fixed coefficient");
        check(restored.job(17).tier() == com.example.examplemod.lootconfig.LootTier.LOW, "failed refresh/restart keeps selected independent tier");

        var legacy = tag.copy();
        legacy.putInt("version", 1);
        legacy.getList("jobs", Tag.TAG_COMPOUND).getCompound(0).remove("rarity");
        check(LootRespawnSavedData.load(legacy, null).job(17).rarity() == 1,
                "legacy NBT without coefficient defaults to one");
        var invalid = tag.copy();
        invalid.getList("jobs", Tag.TAG_COMPOUND).getCompound(0).putDouble("rarity", Double.POSITIVE_INFINITY);
        var broken = LootRespawnSavedData.load(invalid, null);
        check(!broken.snapshotError().isBlank() && broken.job(17) == null, "corrupt coefficient blocks task restoration");
        data.begin(config, List.of(new LootRespawnSavedData.Job(18, dimension, job.floors(), 2, "", 0)), requester);
        check(LootRespawnSavedData.load(data.save(new CompoundTag(), null), null).job(18).rarity() == 0,
                "explicit zero coefficient remains disabled across restarts");
        check(data.job(17) == null && data.epoch() == 2, "new refresh replaces pending snapshot in a new epoch");
        System.out.println("Loot respawn snapshot checks passed: " + checks);
    }

    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
