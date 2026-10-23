package com.example.examplemod.visibility;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Offline policy, packet budgets and save protection. Does not instantiate a Minecraft world/client/server. */
public final class VisibilityChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        check(VisibilityPolicy.Settings.defaults().equals(new VisibilityPolicy.Settings(512, 512)), "both defaults are 512");
        for (int range = 16; range <= 1024; range++) {
            double squared = (double) range * range;
            check(VisibilityPolicy.inRange(squared, range), "inclusive exact boundary");
            check(!VisibilityPolicy.inRange(Math.nextUp(squared), range), "outside boundary");
            check(VisibilityPolicy.inRange(Math.nextDown(squared), range), "inside boundary");
            check(!VisibilityPolicy.inRange(-1, range), "invalid negative squared distance");
        }
        check(!VisibilityPolicy.inRange(Double.NaN, 512), "NaN rejected");
        check(!VisibilityPolicy.inRange(Double.POSITIVE_INFINITY, 512), "infinity rejected");
        check(Math.abs(VisibilityPolicy.originalRemotePlayerDistance(12, 1, false) - 960) < 1e-9,
                "RemotePlayer native tenfold factor prevents confusing base Entity 96 with actual player range");
        check(VisibilityPolicy.originalRemotePlayerDistance(12, 1, true) < 960, "pose affects original native bbox formula");
        for (var settings : List.of(new VisibilityPolicy.Settings(256, 1024), new VisibilityPolicy.Settings(1024, 16),
                VisibilityPolicy.Settings.defaults())) {
            check(VisibilityPolicy.distanceForKinds(settings, false, false) == 0, "empty carrier retains no extended management");
            check(VisibilityPolicy.distanceForKinds(settings, true, false) == settings.players(), "player passenger tree inherits player range");
            check(VisibilityPolicy.distanceForKinds(settings, false, true) == settings.targets(), "target passenger tree inherits target range");
            check(VisibilityPolicy.distanceForKinds(settings, true, true) == Math.max(settings.players(), settings.targets()),
                    "mixed recursive passenger tree inherits maximum configured range");
        }
        rejects(() -> new VisibilityPolicy.Settings(15, 512));
        rejects(() -> new VisibilityPolicy.Settings(512, 1025));
        targetDrawLedger();

        var state = new VisibilityPayloads.State(UUID.randomUUID(), 3, new VisibilityPolicy.Settings(1024, 16), true, "saved");
        var buffer = buffer();
        try {
            VisibilityPayloads.State.CODEC.encode(buffer, state);
            check(VisibilityPayloads.State.CODEC.decode(buffer).equals(state), "settings packet round trip");
            check(buffer.readableBytes() == 0, "settings consume exactly one packet");
        } finally { buffer.release(); }
        var request = new VisibilityPayloads.Request(UUID.randomUUID(), true, 3, 512, 512);
        buffer = buffer();
        try {
            VisibilityPayloads.Request.CODEC.encode(buffer, request);
            check(VisibilityPayloads.Request.CODEC.decode(buffer).equals(request), "save request round trip");
        } finally { buffer.release(); }
        byte[] bytes = new byte[1024];
        var target = new VisibilityPayloads.Target(BlockPos.ZERO, 10, 15728880, bytes);
        bytes[0] = 1;
        check(target.nbt()[0] == 0, "server input NBT is defensively copied");
        byte[] copy = target.nbt(); copy[0] = 2;
        check(target.nbt()[0] == 0, "packet accessor cannot mutate stored NBT");
        var list = new ArrayList<VisibilityPayloads.Target>();
        for (int i = 0; i < VisibilityPolicy.MAX_TARGETS; i++) list.add(new VisibilityPayloads.Target(new BlockPos(i, 64, 300), i, 0, bytes));
        var targets = new VisibilityPayloads.Targets(ResourceLocation.parse("minecraft:overworld"), 42, list, 9);
        list.clear(); check(targets.targets().size() == 128, "batch list copied");
        buffer = buffer();
        try {
            VisibilityPayloads.Targets.CODEC.encode(buffer, targets);
            check(buffer.readableBytes() < 135 * 1024, "worst-case 128 target packet is under 135 KiB");
            var decoded = VisibilityPayloads.Targets.CODEC.decode(buffer);
            check(decoded.targets().size() == 128 && decoded.omitted() == 9 && decoded.sequence() == 42, "target header/count round trip");
            for (int i = 0; i < 128; i++) {
                check(decoded.targets().get(i).pos().equals(targets.targets().get(i).pos()), "target coordinates preserved");
                check(java.util.Arrays.equals(decoded.targets().get(i).nbt(), targets.targets().get(i).nbt()), "target NBT preserved");
            }
            check(buffer.readableBytes() == 0, "snapshot consumes bounded packet");
        } finally { buffer.release(); }
        rejects(() -> new VisibilityPayloads.Target(BlockPos.ZERO, 0, 0, new byte[1025]));
        rejects(() -> new VisibilityPayloads.Targets(ResourceLocation.parse("minecraft:overworld"), 0,
                java.util.Collections.nCopies(129, target), 0));
        buffer = buffer();
        try {
            buffer.writeResourceLocation(ResourceLocation.parse("minecraft:overworld")); buffer.writeVarLong(0);
            buffer.writeVarInt(0); buffer.writeVarInt(129);
            final var bad = buffer;
            rejects(() -> VisibilityPayloads.Targets.CODEC.decode(bad));
        } finally { buffer.release(); }

        var saved = new VisibilitySavedData();
        saved.update(0, new VisibilityPolicy.Settings(256, 1024));
        check(saved.revision() == 1, "one committed edit advances revision");
        rejects(() -> saved.update(0, VisibilityPolicy.Settings.defaults()));
        check(saved.settings().players() == 256 && saved.revision() == 1, "stale write leaves settings untouched");
        saved.update(1, saved.settings()); check(saved.revision() == 1, "unchanged write does not advance revision");
        var tag = saved.save(new CompoundTag(), RegistryAccess.EMPTY);
        var load = VisibilitySavedData.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class); load.setAccessible(true);
        var restored = (VisibilitySavedData) load.invoke(null, tag, RegistryAccess.EMPTY);
        check(restored.settings().equals(saved.settings()) && restored.revision() == 1, "save round trip preserves distances and revision");
        var badTag = tag.copy(); badTag.putInt("Schema", 99); badTag.putString("KeepMe", "original");
        var protectedData = (VisibilitySavedData) load.invoke(null, badTag, RegistryAccess.EMPTY);
        check(!protectedData.loadError().isEmpty(), "unsupported schema reports protected original");
        rejects(() -> protectedData.update(0, VisibilityPolicy.Settings.defaults()));
        check(protectedData.save(new CompoundTag(), RegistryAccess.EMPTY).equals(badTag), "malformed original is preserved exactly");
        System.out.println("Visibility checks: " + checks + " passed (no game launch)");
    }
    private static void targetDrawLedger() {
        var ledger = new TargetRenderLedger();
        ledger.record(1);
        check(!ledger.wasRendered(1), "shadow/item draw before the native BE phase cannot hide a replica");
        ledger.begin(List.of(1L, 2L));
        ledger.record(3);
        check(!ledger.wasRendered(3), "unrelated native targets do not consume snapshot draw ownership");
        ledger.record(1);
        check(ledger.wasRendered(1), "actually completed native target drawing suppresses one replica");
        check(!ledger.wasRendered(2), "cached target without native draw remains eligible for replica rendering");
        ledger.finish(); ledger.record(2);
        check(!ledger.wasRendered(2), "draws outside the completed native BE phase do not change frame ownership");
        ledger.begin(List.of(1L, 2L));
        check(!ledger.wasRendered(1), "next frame resets actual-draw proof even if position remains cached");
        ledger.record(2); ledger.finish();
        check(ledger.wasRendered(2) && !ledger.wasRendered(1), "draw proof changes per frame with compiled/native availability");
        ledger.clear();
        check(!ledger.wasRendered(2), "logout clears previous world positions");
        var maximum = new ArrayList<Long>();
        for (long i = 0; i < 128; i++) maximum.add(i);
        ledger.begin(maximum);
        for (long i = 0; i < 128; i++) { ledger.record(i); check(ledger.wasRendered(i), "bounded native draw candidate recorded"); }
        maximum.add(128L);
        rejects(() -> ledger.begin(maximum));
        check(ledger.wasRendered(0), "invalid next-frame batch leaves the previous proof untouched");
    }
    private static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void rejects(Runnable action) {
        checks++;
        try { action.run(); throw new AssertionError("Invalid input accepted"); }
        catch (IllegalArgumentException | IllegalStateException | io.netty.handler.codec.DecoderException expected) {}
    }
}
