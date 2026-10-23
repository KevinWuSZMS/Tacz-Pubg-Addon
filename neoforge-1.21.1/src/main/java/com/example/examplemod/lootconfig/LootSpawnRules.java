package com.example.examplemod.lootconfig;

import com.example.examplemod.localization.Texts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;

import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;
import com.example.examplemod.lootconfig.LootConfigModel.General;
import net.minecraft.core.BlockPos;

/** Deterministic policy, separate from world access, rendering and entity transactions. */
public final class LootSpawnRules {
    private LootSpawnRules() {}

    public record FloorSample(BlockPos floor, double surfaceY) {
        public FloorSample {
            floor = floor.immutable();
            if (!Double.isFinite(surfaceY)) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.loot_spawn_rules.non_finite_floor_height"));
        }
    }
    public record Plane(List<FloorSample> floors) {
        public Plane { floors = List.copyOf(floors); }
    }
    public record PlannedItem(FloorSample anchor, Entry entry, int count) {}
    private record Column(int x, int z) {}
    private static final Comparator<FloorSample> ORDER = Comparator.comparingDouble(FloorSample::surfaceY)
            .thenComparingInt(s -> s.floor().getX()).thenComparingInt(s -> s.floor().getZ())
            .thenComparingInt(s -> s.floor().getY());

    /** Height range is bounded for the WHOLE plane; stairs cannot chain into higher floors. */
    public static List<Plane> planes(Collection<FloorSample> input, double tolerance) {
        if (!Double.isFinite(tolerance) || tolerance < 0 || tolerance > 2) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.loot_spawn_rules.invalid_height_tolerance"));
        Map<BlockPos, FloorSample> remaining = new HashMap<>();
        Map<Column, List<FloorSample>> columns = new HashMap<>();
        for (FloorSample sample : input) remaining.put(sample.floor(), sample);
        List<FloorSample> sorted = remaining.values().stream().sorted(ORDER).toList();
        for (FloorSample sample : sorted) columns.computeIfAbsent(column(sample.floor()), ignored -> new ArrayList<>()).add(sample);
        List<Plane> result = new ArrayList<>();
        for (FloorSample first : sorted) {
            if (remaining.remove(first.floor()) == null) continue;
            ArrayDeque<FloorSample> queue = new ArrayDeque<>();
            List<FloorSample> members = new ArrayList<>();
            queue.add(first);
            double maximumY = first.surfaceY() + tolerance + 1e-7;
            while (!queue.isEmpty()) {
                FloorSample current = queue.removeFirst();
                members.add(current);
                for (Column neighbour : neighbours(current.floor())) {
                    for (FloorSample next : columns.getOrDefault(neighbour, List.of())) {
                        if (next.surfaceY() >= first.surfaceY() - 1e-7 && next.surfaceY() <= maximumY
                                && remaining.remove(next.floor()) != null) queue.addLast(next);
                    }
                }
            }
            members.sort(ORDER);
            result.add(new Plane(members));
        }
        return List.copyOf(result);
    }

    /** Conservative jobs based on saved coordinates only; actual planes are computed once loaded. */
    public static List<List<BlockPos>> candidateRegions(Collection<BlockPos> input, double tolerance) {
        int dy = (int) Math.ceil(tolerance + 1);
        Map<Column, List<BlockPos>> columns = new HashMap<>();
        Set<BlockPos> remaining = new HashSet<>();
        for (BlockPos pos : input) remaining.add(pos.immutable());
        List<BlockPos> sorted = remaining.stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
        for (BlockPos pos : sorted) columns.computeIfAbsent(column(pos), ignored -> new ArrayList<>()).add(pos);
        List<List<BlockPos>> result = new ArrayList<>();
        for (BlockPos first : sorted) {
            if (!remaining.remove(first)) continue;
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            List<BlockPos> members = new ArrayList<>();
            queue.add(first);
            while (!queue.isEmpty()) {
                BlockPos current = queue.removeFirst();
                members.add(current);
                for (Column neighbour : neighbours(current)) {
                    for (BlockPos next : columns.getOrDefault(neighbour, List.of())) {
                        if (Math.abs(next.getY() - current.getY()) <= dy && remaining.remove(next)) queue.addLast(next);
                    }
                }
            }
            result.add(List.copyOf(members));
        }
        return List.copyOf(result);
    }

    /** Whole expected items plus one Bernoulli trial for the fractional remainder. */
    public static int rollDensityCount(double area, double rarity, double density, RandomGenerator random) {
        if (!Double.isFinite(area) || area < 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_spawn_rules.region_area_finite_nonnegative"));
        if (!Double.isFinite(rarity) || rarity < 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_spawn_rules.region_rarity_multiplier_finite_nonnegative"));
        if (!Double.isFinite(density) || density < 0 || density > 1000) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_spawn_rules.loot_density_between_items_m"));
        }
        if (area == 0 || rarity == 0 || density == 0) return 0;
        // Multiply the smallest and largest factor first, so a tiny coefficient can balance a very large one.
        double low = Math.min(area, Math.min(rarity, density));
        double high = Math.max(area, Math.max(rarity, density));
        double middle = area >= Math.min(rarity, density) && area <= Math.max(rarity, density) ? area
                : rarity >= Math.min(area, density) && rarity <= Math.max(area, density) ? rarity : density;
        double expected = (low * high) * middle;
        // A legal but huge configuration must not wrap to a negative count. plan() later caps by real floor capacity.
        if (expected >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        int whole = (int) Math.floor(expected);
        double remainder = expected - whole;
        return whole + (remainder > 0 && random.nextDouble() < remainder ? 1 : 0);
    }

    public static Optional<Entry> chooseItem(Document document, RandomGenerator random) {
        return TwoStageLootSampler.prepare(document).choose(random);
    }

    public static Optional<Entry> chooseItem(Document document, RandomGenerator random,
            Function<Entry, LootCategory> classifier) {
        return TwoStageLootSampler.prepare(document, classifier).choose(random);
    }

    public static List<PlannedItem> plan(Collection<FloorSample> floors, Document document, RandomGenerator random) {
        return plan(floors, document, 1, random);
    }

    /** Count density once over all approved floor cells in a region, including its separate storeys. */
    public static List<PlannedItem> plan(Collection<FloorSample> floors, Document document,
            double rarity, RandomGenerator random) {
        return plan(floors, document, rarity, random, entry -> LootCategory.classify(entry, ""));
    }

    /** Semantic classification is supplied by the caller's cached native-asset snapshot. */
    public static List<PlannedItem> plan(Collection<FloorSample> floors, Document document,
            double rarity, RandomGenerator random, Function<Entry, LootCategory> classifier) {
        return plan(floors, document, rarity, random, TwoStageLootSampler.prepare(document, classifier));
    }

    public static List<PlannedItem> plan(Collection<FloorSample> floors, Document document,
            double rarity, RandomGenerator random, Function<Entry, LootCategory> classifier,
            Function<Entry, GunClass> gunClassifier, Map<GunClass, Integer> catalogCounts) {
        return plan(floors, document, rarity, random, TwoStageLootSampler.prepare(document, classifier, gunClassifier, catalogCounts));
    }

    private static List<PlannedItem> plan(Collection<FloorSample> floors, Document document,
            double rarity, RandomGenerator random, TwoStageLootSampler weighted) {
        List<String> errors = LootConfigModel.validate(document);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        General settings = document.general();
        List<Plane> planes = planes(floors, settings.samePlaneYTolerance());
        long area = planes.stream().mapToLong(plane -> plane.floors().size()).sum();
        int densityCount = rollDensityCount(area, rarity, settings.lootDensity(), random);
        // Zero coefficients explicitly disable this region, including optional per-plane guarantees.
        if (area == 0 || rarity == 0 || settings.lootDensity() == 0) return List.of();
        // Prepare the two stages once per region, never once per selected floor/item.
        if (weighted.isEmpty()) return List.of();

        List<Candidate> candidates = new ArrayList<>();
        long capacity = 0;
        long minimum = 0;
        for (Plane plane : planes) {
            int cap = Math.min(settings.maxItemsPerPlane(), plane.floors().size());
            if (cap == 0 || random.nextDouble() >= settings.planeSpawnChance()) continue;
            PlaneState state = new PlaneState(cap, Math.min(settings.minItemsPerPlane(), cap));
            capacity += cap;
            minimum += state.minimum;
            for (FloorSample floor : plane.floors()) candidates.add(new Candidate(state, floor));
        }
        int target = (int) Math.min(Math.min(Integer.MAX_VALUE, capacity), Math.max(densityCount, minimum));
        if (target == 0) return List.of();
        for (int i = candidates.size() - 1; i > 0; i--) {
            int other = random.nextInt(i + 1);
            Candidate old = candidates.get(i);
            candidates.set(i, candidates.get(other));
            candidates.set(other, old);
        }

        List<PlannedItem> result = new ArrayList<>();
        double spacingSquared = settings.minItemSpacing() * settings.minItemSpacing();
        // Optional per-plane guarantees consume the same region budget rather than rerolling its area.
        for (Candidate candidate : candidates) {
            if (candidate.plane.anchors.size() < candidate.plane.minimum
                    && canPlace(candidate, spacingSquared)) addPlanned(candidate, weighted, random, result);
        }
        for (Candidate candidate : candidates) {
            if (result.size() >= target) break;
            if (canPlace(candidate, spacingSquared)) addPlanned(candidate, weighted, random, result);
        }
        return List.copyOf(result);
    }

    private static final class PlaneState {
        private final int capacity;
        private final int minimum;
        private final List<FloorSample> anchors = new ArrayList<>();
        private final Set<BlockPos> used = new HashSet<>();
        private PlaneState(int capacity, int minimum) { this.capacity = capacity; this.minimum = minimum; }
    }

    private record Candidate(PlaneState plane, FloorSample floor) {}

    private static boolean canPlace(Candidate candidate, double spacingSquared) {
        PlaneState plane = candidate.plane;
        return plane.anchors.size() < plane.capacity && !plane.used.contains(candidate.floor.floor())
                && plane.anchors.stream().noneMatch(previous ->
                        horizontalDistanceSquared(previous.floor(), candidate.floor.floor()) < spacingSquared);
    }

    private static void addPlanned(Candidate candidate, TwoStageLootSampler weighted, RandomGenerator random,
            List<PlannedItem> result) {
        Entry entry = weighted.choose(random).orElseThrow();
        candidate.plane.anchors.add(candidate.floor);
        candidate.plane.used.add(candidate.floor.floor());
        result.add(new PlannedItem(candidate.floor, entry, random.nextInt(entry.minCount(), entry.maxCount() + 1)));
    }

    private static double horizontalDistanceSquared(BlockPos a, BlockPos b) {
        double x = (double) a.getX() - b.getX(), z = (double) a.getZ() - b.getZ();
        return x * x + z * z;
    }
    private static Column column(BlockPos pos) { return new Column(pos.getX(), pos.getZ()); }
    private static List<Column> neighbours(BlockPos pos) {
        return List.of(new Column(pos.getX() - 1, pos.getZ()), new Column(pos.getX() + 1, pos.getZ()),
                new Column(pos.getX(), pos.getZ() - 1), new Column(pos.getX(), pos.getZ() + 1));
    }
}
