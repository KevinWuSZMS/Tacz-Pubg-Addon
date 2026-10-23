package com.example.examplemod.spawn;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** In-memory topology, identity and NBT checks. No server, client, chunks or real saves. */
public final class SpawnRegionChecks {
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation NETHER = ResourceLocation.parse("minecraft:the_nether");
    private static int checks;

    private SpawnRegionChecks() {}

    public static void main(String[] args) {
        mapPreviewWire();
        tiersAndStacking();
        exactShapes();
        adjacency();
        mergeAndSplit();
        raritiesAreAtomic();
        persistenceAndMigration();
        deterministicHistory();
        limitsAreAtomic();
        System.out.println("Spawn region checks passed: " + checks);
    }

    private static void mapPreviewWire() {
        var tiers = com.example.examplemod.lootconfig.LootTier.values();
        var floors = List.of(new BlockPos(3, 64, 7), new BlockPos(3, 74, 7));
        var palette = com.example.examplemod.zones.MapVisualStyle.defaultLootTierRgb();
        var packet = new SpawnAreaPayloads.Snapshot(NETHER, false, 17, floors, null, null, false,
                Map.of(floors.get(0).asLong(), tiers[0], floors.get(1).asLong(), tiers[4]), tiers[2], palette, 3, true);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            SpawnAreaPayloads.Snapshot.STREAM_CODEC.encode(buffer, packet);
            check(SpawnAreaPayloads.Snapshot.STREAM_CODEC.decode(buffer).equals(packet), "cross-dimension map snapshot keeps Y floors, independent tiers and complete palette/n");
            check(buffer.readableBytes() == 0, "map snapshot consumes exact bytes");
            var request = new SpawnAreaPayloads.ToolRequest(SpawnAreaPayloads.ToolAction.MAP_PREVIEW, NETHER);
            SpawnAreaPayloads.ToolRequest.STREAM_CODEC.encode(buffer, request);
            check(SpawnAreaPayloads.ToolRequest.STREAM_CODEC.decode(buffer).equals(request), "read-only map preview explicitly requests draft dimension");
            var legacy = new SpawnAreaPayloads.Snapshot(OVERWORLD, true, 8, floors, null, null, false);
            SpawnAreaPayloads.Snapshot.STREAM_CODEC.encode(buffer, legacy);
            check(SpawnAreaPayloads.Snapshot.STREAM_CODEC.decode(buffer).equals(legacy), "old snapshot constructor canonicalizes high-tier defaults");
        } finally { buffer.release(); }
    }

    private static void tiersAndStacking() {
        var data = new SpawnAreaSavedData();
        var tiers = com.example.examplemod.lootconfig.LootTier.values();
        for (int i = 0; i < tiers.length; i++) {
            data.add(OVERWORLD, List.of(new BlockPos(i, 64, 0), new BlockPos(i, 74, 0)), tiers[i]);
            var region = data.regionAt(OVERWORLD, new BlockPos(i, 64, 0));
            check(region.tier() == tiers[i] && region.rarity() == tiers[i].quantityMultiplier(), "flag stores independent tier and quantity multiplier");
        }
        check(data.regions().size() == 5, "adjacent distinct tiers never merge");
        var restored = SpawnAreaSavedData.load(data.save(new CompoundTag(), null), null);
        check(restored.regions().equals(data.regions()), "all five independent tier identities survive NBT");
        java.util.Map<Long, com.example.examplemod.lootconfig.LootTier> floors = new java.util.HashMap<>();
        for (int y : new int[]{64, 74, 84}) floors.put(new BlockPos(3, y, 7).asLong(), tiers[1]);
        var columns = LootMapShading.columns(floors);
        check(columns.size() == 1 && columns.getFirst().layers() == 3, "same X/Z distinct Y are one three-storey preview column");
        var colors = com.example.examplemod.zones.MapVisualStyle.defaultLootTierRgb();
        int shaded = LootMapShading.color(columns.getFirst(), colors, 5);
        check(((shaded >> 8) & 255) == Math.round(((tiers[1].rgb() >> 8) & 255) * .5), "overlap RGB darkens linearly");
        check(LootMapShading.color(columns.getFirst(), colors, 3) == 0xff000000, "configured n layers are opaque black");
        check(LootMapShading.color(columns.getFirst(), colors, 2) == 0xff000000, "extra storeys clamp at black");
    }

    private static void exactShapes() {
        SpawnAreaSavedData data = new SpawnAreaSavedData();
        check(data.regions().isEmpty() && data.totalCount() == 0 && data.revision() == 0, "new save is empty");
        check(data.region(1) == null && data.regionAt(OVERWORLD, floor(0, 0)) == null, "unknown regions return null");
        List<BlockPos> lShape = List.of(floor(0, 0), floor(1, 0), floor(0, 1));
        check(data.add(OVERWORLD, lShape) == 3, "three approved cells added");
        var region = data.regions().getFirst();
        check(region.id() == 1 && region.rarity() == 1 && region.area() == 3, "new region starts ID 1 and rarity 1");
        check(Set.copyOf(region.floors()).equals(Set.copyOf(lShape)), "L shape retains exact cells");
        check(data.regionAt(OVERWORLD, floor(1, 1)) == null, "L bounding box is not filled");
        check(region.min().equals(floor(0, 0)) && region.max().equals(floor(1, 1)), "bounding information is retained");
        check(data.regions(OVERWORLD).size() == 1 && data.regions(NETHER).isEmpty(), "region dimensions are filtered");
        check(data.revision() == 1 && data.groups(OVERWORLD).groupCount() == 1, "geometry revision and old group API work");
        check(data.nearby(OVERWORLD, new ChunkPos(0, 0), 0).size() == 3, "nearby index retains the exact shape");
        check(data.add(OVERWORLD, lShape) == 0 && data.revision() == 1, "duplicate addition does not change identity or revision");
        expectFailure(() -> region.floors().add(floor(7, 7)), "shape list is immutable");
        expectFailure(() -> data.regions().clear(), "region list is immutable");
        expectFailure(() -> data.floors(OVERWORLD).clear(), "old floor view stays immutable");

        SpawnAreaSavedData ring = new SpawnAreaSavedData();
        List<BlockPos> shape = new ArrayList<>();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) if (x != 1 || z != 1) shape.add(floor(x, z));
        ring.add(OVERWORLD, shape);
        check(ring.regions().size() == 1 && ring.regions().getFirst().area() == 8, "ring remains a single eight-square-metre region");
        check(!ring.contains(OVERWORLD, floor(1, 1)) && ring.regionAt(OVERWORLD, floor(1, 1)) == null, "interior hole is not approved");
        check(ring.regions().getFirst().floors().stream().noneMatch(pos -> pos.equals(floor(1, 1))), "hole remains absent from persisted shape");
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(8, 64, 8);
        ring.add(OVERWORLD, List.of(mutable));
        mutable.set(9, 64, 9);
        check(ring.contains(OVERWORLD, floor(8, 8)) && !ring.contains(OVERWORLD, floor(9, 9)), "mutable caller positions are frozen");
    }

    private static void adjacency() {
        SpawnAreaSavedData corner = new SpawnAreaSavedData();
        corner.add(OVERWORLD, List.of(floor(0, 0), floor(1, 1)));
        check(corner.regions().size() == 2, "corner contact alone does not merge");
        corner.add(OVERWORLD, List.of(floor(1, 0)));
        check(corner.regions().size() == 1 && corner.regions().getFirst().area() == 3, "shared edges merge existing irregular regions");
        SpawnAreaSavedData vertical = new SpawnAreaSavedData();
        vertical.add(OVERWORLD, List.of(new BlockPos(0, 64, 0), new BlockPos(0, 74, 0), new BlockPos(0, 84, 0)));
        check(vertical.regions().size() == 1 && vertical.regions().getFirst().area() == 3, "inclusive ten-block vertical links are transitive");
        check(vertical.regions().getFirst().max().getY() == 84, "multi-floor height is retained");
        check(!vertical.contains(OVERWORLD, new BlockPos(0, 65, 0)), "vertical grouping never invents floors between storeys");
        vertical.add(OVERWORLD, List.of(new BlockPos(0, 95, 0)));
        check(vertical.regions().size() == 2, "eleven-block vertical gap remains separate");
        SpawnAreaSavedData offset = new SpawnAreaSavedData();
        offset.add(OVERWORLD, List.of(floor(0, 0), new BlockPos(1, 65, 0)));
        check(offset.regions().size() == 2, "different-height horizontally offset cells do not silently connect");
        offset.add(NETHER, List.of(floor(0, 0)));
        check(offset.regions().size() == 3 && offset.regionAt(NETHER, floor(0, 0)).id() == 3, "dimensions never merge and share one ID sequence");
    }

    private static void mergeAndSplit() {
        SpawnAreaSavedData data = new SpawnAreaSavedData();
        data.add(OVERWORLD, List.of(floor(2, 0)));
        data.add(OVERWORLD, List.of(floor(0, 0)));
        data.setRarities(data.revision(), Map.of(1L, 7D, 2L, .25));
        data.add(OVERWORLD, List.of(floor(1, 0)));
        check(data.regions().size() == 1 && data.region(2) == null, "bridge merges regions and retires newer ID");
        check(data.region(1).rarity() == 7 && data.region(1).anchor().equals(floor(2, 0)), "merge keeps oldest ID, its coefficient and anchor");
        check(data.region(1).area() == 3, "merge keeps all approved cells");
        data.remove(OVERWORLD, List.of(floor(1, 0)));
        check(data.regions().size() == 2 && data.region(1).floors().equals(List.of(floor(2, 0))), "anchor side preserves old identity after split");
        check(data.region(3) != null && data.region(3).floors().equals(List.of(floor(0, 0))), "other split gets a fresh ID without reusing merged ID");
        check(data.region(1).rarity() == 7 && data.region(3).rarity() == 7, "split components inherit merged coefficient");
        check(data.regionAt(OVERWORLD, floor(1, 0)) == null, "removed bridge has no region index");
        check(data.groups(OVERWORLD).groupCount() == 2, "group cache invalidates after split");
        data.clear(OVERWORLD);
        check(data.regions().isEmpty() && data.totalCount() == 0 && data.dimensions().isEmpty(), "clear removes shapes and derived indices");
        data.add(NETHER, List.of(floor(0, 0)));
        check(data.regionAt(NETHER, floor(0, 0)).id() == 4, "clear never resets global ID sequence");
        check(data.clear(OVERWORLD) == 0, "clearing an empty dimension is harmless");

        SpawnAreaSavedData fallback = new SpawnAreaSavedData();
        fallback.add(OVERWORLD, List.of(floor(0, 0), floor(1, 0), floor(2, 0), floor(3, 0), floor(4, 0)));
        fallback.setRarities(fallback.revision(), Map.of(1L, 3D));
        check(fallback.remove(OVERWORLD, List.of(floor(0, 0), floor(2, 0), floor(2, 0))) == 2, "duplicate removals count once");
        check(fallback.region(1).floors().equals(List.of(floor(1, 0))) && fallback.region(1).anchor().equals(floor(1, 0)),
                "deleted anchor falls back to the smallest remaining component");
        check(fallback.region(2).area() == 2 && fallback.region(2).rarity() == 3, "non-retaining split inherits coefficient");
    }

    private static void raritiesAreAtomic() {
        SpawnAreaSavedData data = new SpawnAreaSavedData();
        data.add(OVERWORLD, List.of(floor(0, 0), floor(9, 0)));
        long before = data.revision();
        data.setRarities(before, Map.of(1L, 0D, 2L, 1_000D));
        check(data.region(1).rarity() == 0 && data.region(2).rarity() == 1_000, "zero and upper-bound coefficients are accepted");
        check(data.revision() == before + 1, "multi-region coefficient commit increments revision once");
        long revision = data.revision();
        data.setRarities(revision, Map.of(1L, 0D, 2L, 1_000D));
        check(data.revision() == revision, "unchanged coefficients do not invalidate revision");
        data.setRarities(revision, Map.of());
        check(data.revision() == revision, "empty coefficient update is harmless");
        for (double bad : new double[] {-1, 1_000.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            LinkedHashMap<Long, Double> changes = new LinkedHashMap<>();
            changes.put(1L, 2D);
            changes.put(2L, bad);
            expectFailure(() -> data.setRarities(revision, changes), "invalid coefficient rejects the whole request");
            check(data.region(1).rarity() == 0 && data.region(2).rarity() == 1_000 && data.revision() == revision,
                    "invalid request never partially changes coefficients or revision");
        }
        expectFailure(() -> data.setRarities(revision, Map.of(1L, 2D, 99L, 5D)), "missing ID rejects update");
        check(data.region(1).rarity() == 0 && data.revision() == revision, "missing ID leaves valid entries untouched");
        expectFailure(() -> data.setRarities(before, Map.of(1L, 3D)), "stale revision rejects update");
        check(data.region(1).rarity() == 0, "stale revision leaves coefficients untouched");
        Map<Long, Double> nullValue = new LinkedHashMap<>();
        nullValue.put(1L, 2D);
        nullValue.put(2L, null);
        expectFailure(() -> data.setRarities(revision, nullValue), "null coefficient rejects update");
        check(data.region(1).rarity() == 0, "null coefficient does not partially apply");
        expectFailure(() -> data.setRarities(revision, null), "missing changes reject update");
    }

    private static void persistenceAndMigration() {
        SpawnAreaSavedData original = new SpawnAreaSavedData();
        original.add(OVERWORLD, List.of(floor(0, 0), floor(1, 0), floor(0, 1)));
        original.add(NETHER, List.of(new BlockPos(-10, 70, -20)));
        original.setRarities(original.revision(), Map.of(1L, 0D, 2L, 2.5));
        UUID author = UUID.fromString("129d0ae3-f41f-4ba9-a4ba-9933c4864c17");
        original.setShowPreference(author, true);
        CompoundTag tag = original.save(new CompoundTag(), null);
        check(tag.getInt("version") == 3 && tag.getLong("next_region_id") == 3, "v3 stores tiers and ID high-water mark");
        check(tag.getList("regions", Tag.TAG_COMPOUND).size() == 2 && !tag.contains("dimensions"), "v2 stores explicit regions instead of bounding boxes");
        SpawnAreaSavedData loaded = SpawnAreaSavedData.load(tag, null);
        check(loaded.regions().equals(original.regions()), "shape, identity, coefficient and anchor survive NBT round trip");
        check(loaded.revision() == original.revision() && loaded.showPreference(author), "revision and visibility survive round trip");
        check(loaded.floors(OVERWORLD).equals(original.floors(OVERWORLD)) && loaded.totalCount() == 4, "old floor APIs restore from region data");
        check(loaded.regionAt(NETHER, new BlockPos(-10, 70, -20)).id() == 2, "dimension index restores without reading chunks");
        loaded.clear(OVERWORLD);
        SpawnAreaSavedData afterClear = SpawnAreaSavedData.load(loaded.save(new CompoundTag(), null), null);
        afterClear.add(OVERWORLD, List.of(floor(4, 4)));
        check(afterClear.regionAt(OVERWORLD, floor(4, 4)).id() == 3, "deleted IDs are not reused after save/reload");

        SpawnAreaSavedData anchored = new SpawnAreaSavedData();
        anchored.add(OVERWORLD, List.of(floor(2, 0)));
        anchored.add(OVERWORLD, List.of(floor(0, 0)));
        anchored.add(OVERWORLD, List.of(floor(1, 0)));
        SpawnAreaSavedData anchoredReload = SpawnAreaSavedData.load(anchored.save(new CompoundTag(), null), null);
        check(anchoredReload.region(1).anchor().equals(floor(2, 0)), "non-minimal oldest anchor survives persistence");
        anchoredReload.remove(OVERWORLD, List.of(floor(1, 0)));
        check(anchoredReload.region(1).floors().equals(List.of(floor(2, 0))), "reloaded anchor still chooses the retaining split");

        CompoundTag legacy = new CompoundTag();
        legacy.putInt("version", 1);
        legacy.putLong("revision", 12);
        ListTag dimensions = new ListTag();
        CompoundTag oldDimension = new CompoundTag();
        oldDimension.putString("id", OVERWORLD.toString());
        oldDimension.putLongArray("floors", new long[] {floor(1, 0).asLong(), floor(0, 1).asLong(), floor(0, 0).asLong(), floor(0, 0).asLong(), floor(8, 8).asLong()});
        dimensions.add(oldDimension);
        legacy.put("dimensions", dimensions);
        ListTag authors = new ListTag();
        authors.add(StringTag.valueOf(author.toString()));
        legacy.put("visible_authors", authors);
        CompoundTag oldCopy = legacy.copy();
        SpawnAreaSavedData migrated = SpawnAreaSavedData.load(legacy, null);
        check(migrated.regions().size() == 2 && migrated.totalCount() == 4, "v1 deduplicates floors and migrates connected shapes");
        check(migrated.region(1).area() == 3 && migrated.region(2).area() == 1, "v1 deterministic connected components receive separate IDs");
        check(migrated.regions().stream().allMatch(region -> region.rarity() == 1), "v1 migrated rarity defaults to one");
        check(migrated.revision() == 12 && migrated.showPreference(author), "migration preserves revision and author preference");
        check(legacy.equals(oldCopy), "migration does not mutate supplied source NBT");
        check(!migrated.contains(OVERWORLD, floor(1, 1)), "migration preserves L-shaped hole");
        check(SpawnAreaSavedData.load(migrated.save(new CompoundTag(), null), null).regions().equals(migrated.regions()), "migrated identities survive subsequent v2 reload");

        CompoundTag malformed = original.save(new CompoundTag(), null);
        malformed.getList("regions", Tag.TAG_COMPOUND).getCompound(0).putDouble("rarity", Double.NaN);
        SpawnAreaSavedData repaired = SpawnAreaSavedData.load(malformed, null);
        check(repaired.region(1).rarity() == 1 && repaired.totalCount() == 4, "invalid saved rarity is repaired without losing shape");
        expectFailure(() -> new SpawnAreaSavedData.Region(0, OVERWORLD, List.of(floor(0, 0)), 1), "zero region ID is invalid");
        expectFailure(() -> new SpawnAreaSavedData.Region(1, OVERWORLD, List.of(), 1), "empty region is invalid");
        expectFailure(() -> new SpawnAreaSavedData.Region(1, OVERWORLD, List.of(floor(0, 0)), 1, floor(1, 0)), "anchor outside shape is invalid");
    }

    private static void deterministicHistory() {
        List<BlockPos> shape = new ArrayList<>(List.of(floor(-2, 0), floor(-1, 0), floor(0, 0), floor(0, 1), floor(9, 9)));
        SpawnAreaSavedData forward = new SpawnAreaSavedData();
        forward.add(OVERWORLD, shape);
        Collections.reverse(shape);
        SpawnAreaSavedData reverse = new SpawnAreaSavedData();
        reverse.add(OVERWORLD, shape);
        check(forward.regions().equals(reverse.regions()), "new identities and shape order do not depend on selection order");
        forward.setRarities(forward.revision(), Map.of(1L, 4D));
        reverse.setRarities(reverse.revision(), Map.of(1L, 4D));
        forward.remove(OVERWORLD, List.of(floor(-2, 0), floor(0, 0)));
        reverse.remove(OVERWORLD, List.of(floor(0, 0), floor(-2, 0)));
        check(forward.regions().equals(reverse.regions()), "split identity and anchor fallback are deterministic");
        for (var region : forward.regions()) {
            check(region.floors().stream().allMatch(pos -> forward.regionAt(OVERWORLD, pos).id() == region.id()), "all floor indices agree with current identities");
        }
        check(forward.totalCount() == forward.regions().stream().mapToInt(SpawnAreaSavedData.Region::area).sum(), "region areas cover each approved floor once");
    }

    private static void limitsAreAtomic() {
        SpawnAreaSavedData data = new SpawnAreaSavedData();
        List<BlockPos> oversized = new ArrayList<>(SpawnAreaSavedData.MAX_FLOORS_PER_DIMENSION + 1);
        for (int i = 0; i <= SpawnAreaSavedData.MAX_FLOORS_PER_DIMENSION; i++) oversized.add(floor(i, 0));
        expectFailure(() -> data.add(OVERWORLD, oversized), "dimension floor limit is enforced before committing");
        check(data.totalCount() == 0 && data.regions().isEmpty() && data.revision() == 0, "over-limit geometry leaves all state untouched");
        data.add(OVERWORLD, List.of(floor(0, 0)));
        check(data.regions().getFirst().id() == 1, "rejected geometry does not consume an ID");
    }

    private static BlockPos floor(int x, int z) { return new BlockPos(x, 64, z); }
    private static void expectFailure(Runnable action, String message) {
        boolean rejected = false;
        try { action.run(); }
        catch (RuntimeException expected) { rejected = true; }
        check(rejected, message);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
