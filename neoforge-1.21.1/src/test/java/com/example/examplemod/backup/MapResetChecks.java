package com.example.examplemod.backup;

import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.util.List;
import java.util.UUID;

/** In-memory undo/restart fixtures and target-bytecode checks; no game or world files. */
public final class MapResetChecks {
    private static int checks;
    private static MapDefinition map(String name, double max) {
        return new MapDefinition(1, name, ResourceLocation.parse("minecraft:overworld"), -16, -16, max, 256, null, 1, List.of());
    }
    private static CompoundTag original(BlockPos pos, String block) {
        var tag = new CompoundTag(); tag.putLong("pos", pos.asLong());
        var state = new CompoundTag(); state.putString("Name", block); tag.put("state", state); return tag;
    }
    public static void main(String[] args) throws Exception {
        var data = new MapResetData(); var e = new MapResetData.Entry(map("arena", 256)); data.entries.put(1L, e);
        var pos = new BlockPos(1, 70, 1); var stone = original(pos, "minecraft:stone");
        check(e.locked(), "new map starts protected immediately");
        check(!e.record(pos, stone) && e.changes.isEmpty(), "protected edits cannot enter the round journal");
        rejects(e::activate, "only an explicitly prepared round may start");
        e.prepare(); check(e.locked(), "bot preparation stays protected"); e.activate();
        check(!e.locked() && e.record(pos, stone), "first active change captures its original state");
        stone.getCompound("state").putString("Name", "minecraft:air");
        check(e.changes.get(pos.asLong()).getCompound("state").getString("Name").equals("minecraft:stone"), "journal owns an immutable copy");
        for (int i = 0; i < 200; i++) check(!e.record(pos, original(pos, "minecraft:dirt")), "repeated edits preserve the first original");
        check(e.changes.size() == 1, "two hundred edits of one position produce one undo");
        // A large map with edits in six chunks must never create work for its other chunks.
        for (int x = 0; x < 6; x++) {
            var changed = new BlockPos(x * 16 + 1, 71, 1); e.record(changed, original(changed, "minecraft:stone"));
        }
        check(e.changes.size() == 7 && e.changes.keySet().stream().map(BlockPos::of).map(p -> p.getX() >> 4).distinct().count() == 6,
                "only edited positions and their six chunks enter restoration");
        var chest = new BlockPos(2, 70, 1); var saved = original(chest, "minecraft:chest");
        var inventory = new CompoundTag(); inventory.putString("id", "minecraft:chest"); inventory.putString("item_fixture", "diamond x 7"); saved.put("block_entity", inventory);
        e.record(chest, saved);
        var resumed = roundTrip(data).entries.get(1L);
        check(resumed.state.equals("RESTORING") && resumed.locked(), "restart of active round restores rather than resumes gameplay");
        check(resumed.changes.get(chest.asLong()).getCompound("block_entity").equals(inventory), "container-only original NBT survives save/load");
        check(resumed.changes.equals(e.changes), "restart retains every first-write record");
        e.end(); check(e.locked() && e.state.equals("RESTORING"), "ending immediately blocks further mutations");
        check(!e.record(new BlockPos(3, 70, 1), stone), "restore writes cannot enter the round journal");
        check(roundTrip(data).entries.get(1L).changes.equals(e.changes), "interrupted restore retains complete replay data");
        e.state = "PROTECTED";
        check(roundTrip(data).entries.get(1L).state.equals("RESTORING"), "completed undo remains replayable across chunk-save crash windows");
        e.prepare(); e.activate(); check(e.changes.isEmpty(), "next round starts a fresh journal after restoration");
        e.end(); check(e.state.equals("RESTORING"), "unchanged terrain still requires entity cleanup");
        e.state = "PROTECTED";
        e.prepare(); check(roundTrip(data).entries.get(1L).state.equals("RESTORING"), "restart discards launch permission and cleans prepared entities");
        e.state = "FAILED"; e.failure = "fixture failure";
        check(roundTrip(data).entries.get(1L).state.equals("FAILED"), "failure stays locked pending explicit retry");
        check(MapResetData.sameRange(e.map, map("renamed", 256)), "rename preserves journal bounds");
        check(!MapResetData.sameRange(e.map, map("resize", 255)), "bounds changes require a new protected region");
        check(MapResetData.contains(e.map, new BlockPos(-16, -64, -16)) && !MapResetData.contains(e.map, new BlockPos(256, 70, 1)), "negative and exclusive maximum edges");
        var invalid = data.save(new CompoundTag(), null);
        invalid.getList("maps", Tag.TAG_COMPOUND).getCompound(0).putString("state", "UNKNOWN");
        var corrupt = MapResetData.load(invalid, null); rejects(corrupt::requireWritable, "unknown journal format fails closed");
        check(invalid.equals(corrupt.save(new CompoundTag(), null)), "unreadable originals are preserved");
        e.state = "ACTIVE"; e.record(pos, original(pos, "minecraft:stone"));
        var duplicate = data.save(new CompoundTag(), null); var records = duplicate.getList("maps", Tag.TAG_COMPOUND).getCompound(0).getList("changes", Tag.TAG_COMPOUND);
        records.add(records.getCompound(0).copy()); rejects(MapResetData.load(duplicate, null)::requireWritable, "ambiguous duplicate originals are rejected");
        editingLifecycle();
        recordingPolicyAndSkips();
        targets();
        System.out.println("MapResetChecks: " + checks + " journal lifecycle, restart, first-write and hook target checks passed; no game launched.");
    }
    private static void recordingPolicyAndSkips() {
        var dimension = ResourceLocation.parse("minecraft:overworld");
        var edge = new MapDefinition(1, "edge", dimension, 0, 0, 500, 500, null, 1, List.of());
        var wider = new MapDefinition(1, "larger", dimension, -250, -250, 251, 250, null, 1, List.of());
        var skinny = new MapDefinition(1, "skinny", dimension, 0, 0, 2000, 100, null, 1, List.of());
        check(!MapResetData.needsEntityRecordingChoice(edge, 500), "exactly 500 x 500 does not prompt");
        check(MapResetData.needsEntityRecordingChoice(wider, 500), "larger area prompts including negative bounds");
        check(!MapResetData.needsEntityRecordingChoice(skinny, 500), "threshold compares area rather than either side");
        check(MapResetData.needsEntityRecordingChoice(edge, 499), "configured threshold applies independently of defaults");
        check(MapResetData.recordingForSave(wider, 500, null) == MapResetData.EntityRecording.PENDING
                        && MapResetData.recordingForSave(edge, 500, null) == MapResetData.EntityRecording.RECORD,
                "only new maps derive their initial recording policy from the configured area threshold");
        for (var policy : MapResetData.EntityRecording.values()) {
            var existing = new MapResetData.Entry(edge); existing.entityRecording = policy;
            check(MapResetData.recordingForSave(wider, 1, existing) == policy
                            && MapResetData.recordingForSave(skinny, 60000000, existing) == policy,
                    "resizing across thresholds preserves the existing " + policy + " decision");
        }

        var data = new MapResetData(); var pending = new MapResetData.Entry(edge);
        pending.entityRecording = MapResetData.EntityRecording.PENDING; data.entries.put(1L, pending);
        rejects(pending::prepare, "pending choice blocks starting a match");
        check(pending.locked() && !pending.managesEntities(), "pending maps remain terrain-protected without touching entities");
        var resumed = roundTrip(data).entries.get(1L);
        check(resumed.entityRecording == MapResetData.EntityRecording.PENDING && resumed.state.equals("PROTECTED"),
                "pending choice survives restart without silently recording");
        var editing = pending.editingCopy(); editing.freeze();
        check(editing.entityRecording == MapResetData.EntityRecording.PENDING && editing.state.equals("PROTECTED") && !editing.entityCapture,
                "freeze preserves a pending decision without beginning a sweep");

        var ignored = new MapResetData.Entry(edge); ignored.entityRecording = MapResetData.EntityRecording.IGNORE;
        ignored.prepare(); ignored.activate();
        check(!ignored.managesEntities() && !ignored.entityReplay && ignored.entityRound == null,
                "ignored entities have neither round ownership nor replay work");
        ignored.end(); check(ignored.state.equals("PROTECTED"), "ignored unchanged map finishes immediately");
        var ignoreEditing = ignored.editingCopy(); ignoreEditing.freeze();
        check(ignoreEditing.state.equals("PROTECTED") && !ignoreEditing.entityCapture,
                "freeze also preserves an explicit ignore policy");
        ignored.prepare(); ignored.activate();
        var pos = new BlockPos(1, 70, 1); ignored.record(pos, original(pos, "minecraft:stone")); ignored.end();
        data.entries.put(1L, ignored); resumed = roundTrip(data).entries.get(1L);
        check(resumed.state.equals("RESTORING") && !resumed.managesEntities() && resumed.needsTerrainRestore(),
                "ignoring entities still restores every changed terrain original after restart");
        rejects(() -> ignored.skipRecovery(true, false), "entity skip refuses an ignored map with only terrain pending");

        var both = new MapResetData.Entry(edge); var uuid = UUID.randomUUID();
        both.entities.put(uuid, baseline(uuid, "minecraft:pig"));
        var ownership = new CompoundTag(); ownership.putUUID("uuid", uuid);
        ownership.putString("dimension", dimension.toString()); ownership.putLong("chunk", 0);
        both.ownedEntities.put(uuid, ownership);
        both.prepare(); both.activate(); both.record(pos, original(pos, "minecraft:gold_block")); both.end();
        both.ownedEntities.put(uuid, ownership);
        var terrainOnly = both.copy(); terrainOnly.skipRecovery(true, false);
        check(terrainOnly.state.equals("RESTORING") && terrainOnly.needsTerrainRestore() && !terrainOnly.managesEntities(),
                "skipping entities leaves terrain restoration scheduled");
        rejects(() -> terrainOnly.skipRecovery(true, false), "entity skip cannot repeatedly succeed after its component was skipped");
        var entityOnly = both.copy(); entityOnly.skipRecovery(false, true);
        check(entityOnly.state.equals("RESTORING") && !entityOnly.needsTerrainRestore() && entityOnly.managesEntities(),
                "skipping terrain leaves entity restoration scheduled");
        check(!both.entityRestoreSkipped && !both.terrainRestoreSkipped && both.changes.size() == 1,
                "an uncommitted policy copy cannot mutate the rollback entry");
        both.skipRecovery(true, true); data.entries.put(1L, both); resumed = roundTrip(data).entries.get(1L);
        check(resumed.state.equals("PROTECTED") && resumed.entityRestoreSkipped && resumed.terrainRestoreSkipped,
                "both confirmed skips survive restart without replaying abandoned work");
        check(resumed.changes.equals(both.changes) && resumed.entities.equals(both.entities)
                        && resumed.ownedEntities.equals(both.ownedEntities),
                "skips retain first-write originals, entity baselines and ownership records");
        resumed.prepare();
        check(!resumed.entityRestoreSkipped && !resumed.terrainRestoreSkipped && resumed.managesEntities(),
                "skip decisions expire at the next prepared round");
        check(resumed.changes.size() == 1, "preparation alone does not discard terrain recovery originals");
        resumed.activate(); check(resumed.changes.isEmpty(), "activation begins a new first-write journal");
        var active = resumed;
        rejects(() -> active.skipRecovery(true, true), "skip refuses an active round");
        var capture = new MapResetData.Entry(edge).editingCopy(); capture.freeze();
        rejects(() -> capture.skipRecovery(false, true), "terrain skip refuses an entity-only capture with no terrain journal");
        capture.entities.put(uuid, baseline(uuid, "minecraft:pig")); capture.state = "FAILED";
        capture.skipRecovery(true, false); data.entries.put(1L, capture); var afterSkip = roundTrip(data).entries.get(1L);
        check(afterSkip.state.equals("PROTECTED") && afterSkip.entityRecording == MapResetData.EntityRecording.IGNORE
                        && !afterSkip.entityCapture && afterSkip.entities.containsKey(uuid),
                "skipping a failed partial capture preserves records and cannot present it as a complete baseline");

        var legacy = new MapResetData(); legacy.entries.put(1L, new MapResetData.Entry(edge));
        var oldTag = legacy.save(new CompoundTag(), null); oldTag.putInt("version", 2);
        var oldEntry = oldTag.getList("maps", Tag.TAG_COMPOUND).getCompound(0);
        oldEntry.remove("entity_recording"); oldEntry.remove("entity_restore_skipped"); oldEntry.remove("terrain_restore_skipped");
        check(MapResetData.load(oldTag, null).entries.get(1L).entityRecording == MapResetData.EntityRecording.RECORD,
                "legacy journals preserve their original record-and-restore policy");
        oldTag.putInt("version", 3); oldEntry.putString("entity_recording", "UNKNOWN");
        rejects(MapResetData.load(oldTag, null)::requireWritable, "unknown future entity policies fail closed and retain the original file");
    }
    private static void editingLifecycle() {
        var data = new MapResetData();
        var old = new MapResetData.Entry(map("editable", 256));
        var pos = new BlockPos(1, 70, 1);
        var entityId = UUID.randomUUID();
        old.entities.put(entityId, baseline(entityId, "minecraft:pig"));
        old.prepare(); old.activate(); old.record(pos, original(pos, "minecraft:stone")); old.end();
        old.state = "PROTECTED"; // A completed restore still retains its crash-recovery journal.
        data.entries.put(1L, old);
        check(roundTrip(data).entries.get(1L).state.equals("RESTORING"), "fixture has an old completed journal that would replay");
        var editing = old.editingCopy(); data.entries.put(1L, editing);
        check(editing.state.equals("EDITING") && !editing.locked(), "unfreeze creates an unlocked editing state");
        check(editing.changes.isEmpty() && editing.entities.isEmpty() && editing.ownedEntities.isEmpty()
                        && editing.entityRound == null && !editing.entityReplay && !editing.entityCapture,
                "unfreeze retires all previous round journals and entity baselines");
        check(!editing.identity.equals(old.identity), "new edit baseline retires old entity ownership without reusing its generation");
        check(old.changes.size() == 1 && old.entities.size() == 1,
                "old entry stays intact so a failed atomic commit can restore it");
        check(!editing.record(pos, original(pos, "minecraft:gold_block")) && editing.changes.isEmpty(),
                "permanent editor changes never enter round undo");
        editing.end(); check(editing.state.equals("EDITING"), "generic end cleanup does not relock an editing map");
        rejects(editing::prepare, "a map under editing cannot prepare a match");
        rejects(editing::activate, "a map under editing cannot activate a match");
        var resumed = roundTrip(data).entries.get(1L);
        check(resumed.state.equals("EDITING") && !resumed.locked() && resumed.changes.isEmpty()
                        && resumed.entities.isEmpty() && !resumed.entityReplay,
                "restart preserves editing and cannot replay the retired baseline");
        editing.freeze();
        check(editing.state.equals("CAPTURING") && editing.entityCapture && editing.locked(),
                "freeze locks immediately while the replacement entity baseline is captured");
        rejects(editing::prepare, "capture must finish before match preparation");
        var replacementId = UUID.randomUUID();
        editing.entities.put(replacementId, baseline(replacementId, "minecraft:cow"));
        resumed = roundTrip(data).entries.get(1L);
        check(resumed.state.equals("CAPTURING") && resumed.entities.containsKey(replacementId)
                        && !resumed.entities.containsKey(entityId),
                "restart resumes new capture without recovering deleted pre-edit entities");
        editing.entityCapture = false; editing.state = "PROTECTED";
        resumed = roundTrip(data).entries.get(1L);
        check(resumed.state.equals("PROTECTED") && resumed.entities.equals(editing.entities),
                "new frozen baseline survives restart without replaying an old round");
        editing.prepare(); editing.activate();
        check(editing.record(pos, original(pos, "minecraft:gold_block")),
                "the next round captures edited terrain as its new undo original");
        resumed = roundTrip(data).entries.get(1L);
        check(resumed.state.equals("RESTORING") && resumed.changes.get(pos.asLong())
                        .getCompound("state").getString("Name").equals("minecraft:gold_block"),
                "interrupted next round restores the edited block rather than the old stone");
        for (var state : List.of("ACTIVE", "PREPARED", "CAPTURING", "RESTORING", "FAILED")) {
            var busy = new MapResetData.Entry(old.map); busy.state = state;
            rejects(busy::editingCopy, "unfreeze refuses " + state);
            rejects(busy::freeze, "freeze refuses " + state);
        }
        var first = new MapResetData.Entry(map("first", 256)).editingCopy();
        var otherMap = new MapDefinition(2, "overlap", first.map.dimension(), 0, 0, 32, 32, null, 1, List.of());
        var second = new MapResetData.Entry(otherMap);
        check(MapResetData.lockedAt(List.of(first, second), first.map.dimension(), pos),
                "unfreezing one map cannot bypass an overlapping frozen map");
        check(MapResetData.lockedAt(List.of(second, first), first.map.dimension(), pos),
                "overlap protection is independent of definition ordering");
        second = second.editingCopy();
        check(!MapResetData.lockedAt(List.of(first, second), first.map.dimension(), pos),
                "all overlapping maps may be unfrozen one at a time until edits are allowed");
        first.freeze();
        check(MapResetData.lockedAt(List.of(first, second), first.map.dimension(), pos),
                "refreezing either overlapping map protects its entire region");
        check(!MapResetData.lockedAt(List.of(first, second), first.map.dimension(), new BlockPos(300, 70, 300)),
                "freeze never protects positions outside both maps");
    }
    private static CompoundTag baseline(UUID uuid, String type) {
        var nbt = new CompoundTag(); nbt.putUUID("UUID", uuid); nbt.putString("id", type);
        var position = new ListTag();
        position.add(DoubleTag.valueOf(1)); position.add(DoubleTag.valueOf(70)); position.add(DoubleTag.valueOf(1));
        nbt.put("Pos", position);
        var record = new CompoundTag(); record.putUUID("uuid", uuid); record.put("nbt", nbt); return record;
    }
    private static void targets() throws Exception {
        String pos = "Lnet/minecraft/core/BlockPos;", state = "Lnet/minecraft/world/level/block/state/BlockState;";
        method("net/minecraft/world/level/chunk/LevelChunk", "setBlockState", "(" + pos + state + "Z)" + state);
        method("net/minecraft/world/level/chunk/LevelChunk", "removeBlockEntity", "(" + pos + ")V");
        method("net/minecraft/world/level/Level", "destroyBlock", "(" + pos + "ZLnet/minecraft/world/entity/Entity;I)Z");
        method("net/minecraft/world/level/block/entity/BlockEntity", "setChanged", "()V");
        method("net/minecraft/world/level/block/entity/BlockEntity", "loadWithComponents", "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V");
        method("net/minecraft/server/level/ServerLevel", "tickBlock", "(" + pos + "Lnet/minecraft/world/level/block/Block;)V");
        method("net/minecraft/server/level/ServerLevel", "tickFluid", "(" + pos + "Lnet/minecraft/world/level/material/Fluid;)V");
        method("net/minecraft/world/level/block/entity/BaseContainerBlockEntity", "removeItem", "(II)Lnet/minecraft/world/item/ItemStack;");
        method("net/minecraft/world/level/block/entity/BaseContainerBlockEntity", "setItem", "(ILnet/minecraft/world/item/ItemStack;)V");
        method("net/minecraft/world/level/block/entity/HopperBlockEntity", "tryTakeInItemFromSlot",
                "(Lnet/minecraft/world/level/block/entity/Hopper;Lnet/minecraft/world/Container;ILnet/minecraft/core/Direction;)Z");
        method("net/minecraft/world/level/block/entity/HopperBlockEntity", "tryMoveInItem",
                "(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;");
        method("net/minecraft/world/level/chunk/LevelChunk$BoundTickingBlockEntity", "tick", "()V");
        var ticker = node("net/minecraft/world/level/chunk/LevelChunk$BoundTickingBlockEntity");
        check(ticker.fields.stream().anyMatch(f -> f.name.equals("blockEntity") && f.desc.equals("Lnet/minecraft/world/level/block/entity/BlockEntity;")), "tick shadow matches actual erased type");
        method("net/minecraft/client/server/IntegratedServer", "tickPaused", "()V");
        var paused = node("net/minecraft/client/server/IntegratedServer").methods.stream().filter(m -> m.name.equals("tickServer")).findFirst().orElseThrow();
        check(java.util.Arrays.stream(paused.instructions.toArray()).anyMatch(i -> i instanceof org.objectweb.asm.tree.MethodInsnNode m && m.name.equals("tickPaused")), "real integrated server calls the paused maintenance target");
        try (var stream = MapResetChecks.class.getResourceAsStream("/tacz_pubg.backup.mixins.json")) {
            var json = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String mixin : List.of("LevelChunkJournalMixin", "LevelProtectionMixin", "BlockEntityJournalMixin", "BlockEntityTickProtectionMixin", "ContainerProtectionMixin", "HopperProtectionMixin", "ScheduledTickProtectionMixin", "IntegratedServerMaintenanceMixin")) check(json.contains(mixin), "required hook registered: " + mixin);
        }
    }
    private static void method(String target, String name, String descriptor) throws Exception { check(node(target).methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)), "target signature: " + target + "." + name); }
    private static ClassNode node(String name) throws Exception { try (var stream = MapResetChecks.class.getResourceAsStream("/" + name + ".class")) { var n = new ClassNode(); new ClassReader(stream).accept(n, ClassReader.SKIP_DEBUG); return n; } }
    private static MapResetData roundTrip(MapResetData data) { return MapResetData.load(data.save(new CompoundTag(), null), null); }
    private static void rejects(Runnable action, String why) { try { action.run(); } catch (IllegalStateException expected) { checks++; return; } throw new AssertionError(why); }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
