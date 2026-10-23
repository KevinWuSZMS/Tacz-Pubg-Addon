package com.example.examplemod.spawn.client;

import com.example.examplemod.spawn.SpawnFloorGeometry;
import com.example.examplemod.lootconfig.LootTier;
import com.example.examplemod.spawn.SpawnAreaPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client-only, disposable copies of the server's authoring data. */
public final class SpawnAreaClientState {
    private static final int CHECKS_PER_TICK = 256;
    private static final int MAX_PREVIEW_BLOCKS = 4_096;
    private static final int ACTIVE_CHUNK_RADIUS = 4;
    private static final Direction[] NEIGHBOURS = Direction.values();
    private static Map<Long, Map<BlockPos, FloorMesh>> saved = new HashMap<>();
    private static Map<Long, Map<BlockPos, FloorMesh>> preview = new HashMap<>();
    private static List<FloorMesh> activeChecks = List.of();
    private static @Nullable ClientLevel boundLevel;
    private static @Nullable ResourceLocation dimension;
    private static @Nullable BlockPos pos1;
    private static @Nullable BlockPos pos2;
    private static @Nullable BlockPos activeOrigin;
    private static boolean checksDirty = true;
    private static boolean visible;
    private static boolean truncated;
    private static long revision = -1;
    private static int checkCursor;
    private static int savedCount;
    private static long geometryRevision;
    private static Map<Long, LootTier> floorTiers = Map.of();
    private static Map<Long, LootTier> mapFloorTiers = Map.of();
    private static ResourceLocation mapFloorDimension;
    private static long mapFloorRevision = -1;
    private static LootTier selectionTier = LootTier.HIGH;
    private static Map<String, Integer> lootPalette = com.example.examplemod.zones.MapVisualStyle.defaultLootTierRgb();
    private static int overlapBlackLayers = 5;
    private static boolean globalLootPalette;
    public static Map<String, Integer> lootPalette() { return lootPalette; }
    public static int overlapBlackLayers() { return overlapBlackLayers; }
    public static boolean globalLootPalette() { return globalLootPalette; }
    public static Map<Long, LootTier> mapFloors() { return mapFloorTiers; }
    public static Map<Long, LootTier> mapFloors(ResourceLocation requestedDimension) {
        // A foreign-dimension editor preview must not hide the player's existing minimap data.
        if (requestedDimension.equals(dimension)) return floorTiers;
        return requestedDimension.equals(mapFloorDimension) ? mapFloorTiers : Map.of();
    }
    public static LootTier tier(BlockPos pos) { return floorTiers.getOrDefault(pos.asLong(), LootTier.HIGH); }
    public static LootTier selectionTier() { return selectionTier; }

    private SpawnAreaClientState() {}

    public static void accept(SpawnAreaPayloads.Snapshot packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> accept(packet));
            return;
        }
        bindLevel(minecraft.level);
        if (boundLevel == null) return;
        if (packet.revision() < Math.max(revision, mapFloorRevision)) return;
        // Map authoring can inspect cached terrain in a different dimension from the player's world.
        mapFloorTiers = packet.floorTiers(); mapFloorDimension = packet.dimension();
        mapFloorRevision = packet.revision();
        lootPalette = packet.lootPalette(); overlapBlackLayers = packet.lootOverlapBlackLayers(); globalLootPalette = packet.globalLootPalette();
        geometryRevision++;
        if (!boundLevel.dimension().location().equals(packet.dimension())) {
            return;
        }
        dimension = packet.dimension();
        visible = packet.visible();
        truncated = packet.truncated();
        revision = packet.revision();
        floorTiers = packet.floorTiers(); selectionTier = packet.selectionTier();
        lootPalette = packet.lootPalette(); overlapBlackLayers = packet.lootOverlapBlackLayers(); globalLootPalette = packet.globalLootPalette();
        Map<Long, Map<BlockPos, FloorMesh>> replacement = new HashMap<>();
        for (BlockPos position : packet.floors()) {
            BlockPos immutable = position.immutable();
            long chunkKey = ChunkPos.asLong(immutable);
            FloorMesh previous = saved.getOrDefault(chunkKey, Map.of()).get(immutable);
            replacement.computeIfAbsent(chunkKey, ignored -> new HashMap<>())
                    .put(immutable, previous == null ? new FloorMesh(immutable) : previous);
        }
        saved = replacement;
        savedCount = packet.floors().size();
        geometryRevision++;

        if (!java.util.Objects.equals(pos1, packet.pos1()) || !java.util.Objects.equals(pos2, packet.pos2())) {
            pos1 = packet.pos1() == null ? null : packet.pos1().immutable();
            pos2 = packet.pos2() == null ? null : packet.pos2().immutable();
            rebuildPreview();
        }
        checksDirty = true;
    }

    static void tick(boolean shown) {
        Minecraft minecraft = Minecraft.getInstance();
        bindLevel(minecraft.level);
        if (!shown || boundLevel == null || minecraft.player == null || dimension == null) {
            return;
        }
        BlockPos origin = minecraft.player.blockPosition();
        if (checksDirty || activeOrigin == null
                || (origin.getX() >> 4) != (activeOrigin.getX() >> 4)
                || (origin.getZ() >> 4) != (activeOrigin.getZ() >> 4)
                || Math.abs(origin.getY() - activeOrigin.getY()) >= 8) {
            rebuildActiveChecks(origin);
        }
        int budget = Math.min(CHECKS_PER_TICK, activeChecks.size());
        for (int i = 0; i < budget; i++) {
            if (checkCursor >= activeChecks.size()) {
                checkCursor = 0;
            }
            FloorMesh floor = activeChecks.get(checkCursor++);
            if (floor.refresh(boundLevel)) geometryRevision++;
        }
    }

    static void bindLevel(@Nullable ClientLevel level) {
        if (boundLevel != level) {
            clear();
            boundLevel = level;
        }
    }

    public static void clear() {
        floorTiers = Map.of(); mapFloorTiers = Map.of(); mapFloorDimension = null; mapFloorRevision = -1; selectionTier = LootTier.HIGH;
        lootPalette = com.example.examplemod.zones.MapVisualStyle.defaultLootTierRgb(); overlapBlackLayers = 5; globalLootPalette = false;
        saved = new HashMap<>();
        preview = new HashMap<>();
        activeChecks = List.of();
        boundLevel = null;
        dimension = null;
        pos1 = null;
        pos2 = null;
        activeOrigin = null;
        visible = false;
        truncated = false;
        revision = -1;
        checkCursor = 0;
        savedCount = 0;
        checksDirty = true;
        geometryRevision++;
    }

    static void invalidateChunk(long chunkKey) {
        invalidate(saved.get(chunkKey));
        invalidate(preview.get(chunkKey));
        checksDirty = true;
        geometryRevision++;
    }

    private static void invalidate(@Nullable Map<BlockPos, FloorMesh> chunk) {
        if (chunk != null) {
            chunk.values().forEach(FloorMesh::invalidate);
        }
    }

    private static void rebuildPreview() {
        preview = new HashMap<>();
        if (pos1 == null) {
            return;
        }
        BlockPos end = pos2 == null ? pos1 : pos2;
        if (pos1.getY() != end.getY() || selectedArea() > MAX_PREVIEW_BLOCKS) {
            return;
        }
        int minX = Math.min(pos1.getX(), end.getX());
        int maxX = Math.max(pos1.getX(), end.getX());
        int minZ = Math.min(pos1.getZ(), end.getZ());
        int maxZ = Math.max(pos1.getZ(), end.getZ());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos position = new BlockPos(x, pos1.getY(), z);
                preview.computeIfAbsent(ChunkPos.asLong(position), ignored -> new HashMap<>())
                        .put(position, new FloorMesh(position));
            }
        }
    }

    private static void rebuildActiveChecks(BlockPos origin) {
        List<FloorMesh> checks = new ArrayList<>();
        int chunkX = origin.getX() >> 4;
        int chunkZ = origin.getZ() >> 4;
        for (int x = chunkX - ACTIVE_CHUNK_RADIUS; x <= chunkX + ACTIVE_CHUNK_RADIUS; x++) {
            for (int z = chunkZ - ACTIVE_CHUNK_RADIUS; z <= chunkZ + ACTIVE_CHUNK_RADIUS; z++) {
                long key = ChunkPos.asLong(x, z);
                collectChecks(saved.get(key), origin, checks);
                collectChecks(preview.get(key), origin, checks);
            }
        }
        Vec3 center = Vec3.atCenterOf(origin);
        checks.sort(Comparator.comparing((FloorMesh floor) -> floor.states != null)
                .thenComparingDouble(floor -> floor.position.distToCenterSqr(center)));
        activeChecks = checks;
        activeOrigin = origin.immutable();
        checkCursor = 0;
        checksDirty = false;
    }

    private static void collectChecks(@Nullable Map<BlockPos, FloorMesh> chunk, BlockPos origin, List<FloorMesh> output) {
        if (chunk == null) {
            return;
        }
        for (FloorMesh floor : chunk.values()) {
            // A margin keeps meshes ready when the camera crosses a chunk boundary.
            if (Math.abs(floor.position.getY() - origin.getY()) <= 72) {
                output.add(floor);
            }
        }
    }

    static Map<Long, Map<BlockPos, FloorMesh>> savedChunks() {
        return saved;
    }

    static Map<Long, Map<BlockPos, FloorMesh>> previewChunks() {
        return preview;
    }

    public static long geometryRevision() { return geometryRevision; }

    static boolean visible() {
        return visible;
    }

    static @Nullable BlockPos pos1() {
        return pos1;
    }

    static @Nullable BlockPos pos2() {
        return pos2;
    }

    static long selectedArea() {
        if (pos1 == null || pos2 == null || pos1.getY() != pos2.getY()) {
            return 0;
        }
        return (Math.abs((long) pos1.getX() - pos2.getX()) + 1)
                * (Math.abs((long) pos1.getZ() - pos2.getZ()) + 1);
    }

    static int savedCount() {
        return savedCount;
    }

    static boolean truncated() {
        return truncated;
    }

    static final class FloorMesh {
        private final BlockPos position;
        private @Nullable BlockState[] states;
        private List<SpawnFloorGeometry.Surface> surfaces = List.of();

        private FloorMesh(BlockPos position) {
            this.position = position;
        }

        private boolean refresh(ClientLevel level) {
            if (level.getChunkSource().getChunk(position.getX() >> 4, position.getZ() >> 4,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null) {
                boolean changed = states != null || !surfaces.isEmpty();
                invalidate();
                return changed;
            }
            BlockState[] current = new BlockState[NEIGHBOURS.length + 1];
            current[0] = level.getBlockState(position);
            for (int i = 0; i < NEIGHBOURS.length; i++) {
                current[i + 1] = level.getBlockState(position.relative(NEIGHBOURS[i]));
            }
            if (!java.util.Arrays.equals(states, current)) {
                states = current;
                surfaces = List.copyOf(SpawnFloorGeometry.topSurfaces(level, position));
                return true;
            }
            return false;
        }

        private void invalidate() {
            states = null;
            surfaces = List.of();
        }

        BlockPos position() {
            return position;
        }

        List<SpawnFloorGeometry.Surface> surfaces() {
            return surfaces;
        }
    }
}
