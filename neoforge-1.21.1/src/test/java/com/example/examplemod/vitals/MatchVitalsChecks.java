package com.example.examplemod.vitals;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.food.FoodData;

/** Personal activation and wire boundaries only; no live player, world or game is created. */
public final class MatchVitalsChecks {
    private static int checks;
    public static void main(String[] args) {
        check(!MatchVitalsService.activePolicy(true, false, 599, 30), "Preparation does not change hunger or personal HUD");
        check(MatchVitalsService.activePolicy(true, false, 600, 30), "Exact combat start activates personal vitals");
        check(MatchVitalsService.activePolicy(true, false, 601, 30), "A frozen combat clock can retain the active HUD");
        check(!MatchVitalsService.activePolicy(false, false, 601, 30), "Eliminated and non-roster players do not receive personal vitals");
        check(!MatchVitalsService.activePolicy(true, true, 601, 30), "Ending restores normal personal behavior");
        var id = UUID.randomUUID();
        for (var state : new MatchVitalsPayloads.Snapshot[] {
                new MatchVitalsPayloads.Snapshot(id, true, false, 40, 12, 20, 2),
                new MatchVitalsPayloads.Snapshot(id, true, true, 90, 18, 40, 0),
                new MatchVitalsPayloads.Snapshot(MatchVitalsPayloads.ORDINARY_SESSION, true, false, 60, 20, 20, 0),
                MatchVitalsPayloads.Snapshot.INACTIVE }) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                MatchVitalsPayloads.Snapshot.CODEC.encode(buffer, state);
                check(MatchVitalsPayloads.Snapshot.CODEC.decode(buffer).equals(state), "Personal snapshot survives encoding");
                check(buffer.readableBytes() == 0, "Decoder consumes exactly one packet");
            } finally { buffer.release(); }
        }
        rejects(() -> new MatchVitalsPayloads.Snapshot(id, true, false, Float.NaN, 12, 20, 0));
        check(!new MatchVitalsPayloads.Snapshot(MatchVitalsPayloads.ORDINARY_SESSION, true, false, 40, 20, 20, 0).inMatch(),
                "Ordinary boost has its own HUD identity and cannot enable match-only input or hunger");
        check(new MatchVitalsPayloads.Snapshot(id, true, false, 40, 20, 20, 0).inMatch(), "A real match retains its match HUD identity");
        rejects(() -> new MatchVitalsPayloads.Snapshot(id, true, false, 101, 12, 20, 0));
        rejects(() -> new MatchVitalsPayloads.Snapshot(id, true, false, 1, 12, 0, 0));
        rejects(() -> new MatchVitalsPayloads.Snapshot(id, true, false, 1, 12, 20, -1));
        rejects(() -> new MatchVitalsPayloads.Snapshot(MatchVitalsPayloads.NO_SESSION, true, false, 1, 12, 20, 0));
        rejects(() -> new MatchVitalsPayloads.Snapshot(id, false, false, 0, 12, 20, 0));
        rejects(() -> new MatchVitalsPayloads.Snapshot(MatchVitalsPayloads.NO_SESSION, false, true, 0, 12, 20, 0));
        for (var recovery : new MatchVitalsPayloads.RecoveryState[]{
                new MatchVitalsPayloads.RecoveryState(id, 7.4, 3, .75, 12),
                new MatchVitalsPayloads.RecoveryState(MatchVitalsPayloads.NO_SESSION, 1.4, 1.4, .75, 4),
                MatchVitalsPayloads.RecoveryState.INACTIVE}) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                MatchVitalsPayloads.RecoveryState.CODEC.encode(buffer, recovery);
                check(MatchVitalsPayloads.RecoveryState.CODEC.decode(buffer).equals(recovery), "Fixed-budget recovery forecast roundtrips");
                check(buffer.readableBytes() == 0, "Recovery codec leaves no trailing values");
            } finally { buffer.release(); }
        }
        rejects(() -> new MatchVitalsPayloads.RecoveryState(id, 1, 2, .75, 4));
        rejects(() -> new MatchVitalsPayloads.RecoveryState(id, Double.NaN, 0, .75, 4));
        rejects(() -> new MatchVitalsPayloads.RecoveryState(id, 1, 1, 1.1, 4));
        for (int slot : new int[]{0, 8}) {
            var start = new MatchVitalsPayloads.StartMedicine(slot, 123);
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try { MatchVitalsPayloads.StartMedicine.CODEC.encode(buffer, start);
                check(MatchVitalsPayloads.StartMedicine.CODEC.decode(buffer).equals(start), "Held medicine request preserves slot and cancellation identity");
            } finally { buffer.release(); }
        }
        check(new MatchVitalsPayloads.MedicineStarted(0, 1, 1200).duration() == 1200, "Configurable one-minute treatment duration remains within wire safety bounds");
        rejects(() -> new MatchVitalsPayloads.MedicineStarted(0, 1, 1201));
        // The same vanilla NBT mechanism used by the lifecycle service preserves all four food fields.
        var food = new FoodData();
        var original = new CompoundTag();
        original.putInt("foodLevel", 7); original.putInt("foodTickTimer", 37);
        original.putFloat("foodSaturationLevel", 2.5f); original.putFloat("foodExhaustionLevel", 1.75f);
        food.readAdditionalSaveData(original);
        var backup = new CompoundTag(); food.addAdditionalSaveData(backup);
        food.setFoodLevel(20); food.setSaturation(0); food.setExhaustion(0);
        food.readAdditionalSaveData(backup);
        var restored = new CompoundTag(); food.addAdditionalSaveData(restored);
        check(restored.equals(original), "Ending or reconnecting restores hunger, saturation, exhaustion and timer");
        System.out.println("MatchVitalsChecks: " + checks + " personal-state and packet checks passed; no game launched.");
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Malformed vitals accepted");
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
