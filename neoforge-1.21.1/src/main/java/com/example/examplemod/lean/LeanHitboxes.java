package com.example.examplemod.lean;

import com.tacz.guns.config.common.OtherConfig;
import com.tacz.guns.entity.EntityKineticBullet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Transient combat-only geometry and its temporal broad phase. Native movement bounds are untouched. */
public final class LeanHitboxes {
    private static final Map<Level, Index> LEVELS = new IdentityHashMap<>();
    private static final double ACTIVE_EPSILON = 1.0e-5;
    private LeanHitboxes() { }
    private record Sample(long tick, LeanGeometry.Frame frame) { }
    private record Cell(int x, int z) { }
    private static final class History {
        final Player player;
        final Deque<Sample> samples = new ArrayDeque<>();
        Set<Cell> cells = Set.of();
        Vec3 lastPosition;
        AABB union;
        boolean hasLean;
        History(Player player) { this.player = player; }
    }
    private static final class Index {
        final Map<UUID, History> players = new HashMap<>();
        final Map<Cell, Set<UUID>> cells = new HashMap<>();
        final Set<UUID> wide = new HashSet<>();
    }

    /** Called once at player Post tick, after posture and position have been resolved. */
    public static void record(Player player) {
        if (player.level().isClientSide) return;
        if (!player.isAlive() || player.isSpectator()) { clear(player); return; }
        Index index = LEVELS.computeIfAbsent(player.level(), ignored -> new Index());
        History history = index.players.computeIfAbsent(player.getUUID(), ignored -> new History(player));
        if (history.player != player) { clear(player); record(player); return; }
        if (history.lastPosition != null && history.lastPosition.distanceToSqr(player.position()) > 64) history.samples.clear();
        history.lastPosition = player.position();
        long tick = player.level().getGameTime();
        if (!history.samples.isEmpty() && history.samples.getFirst().tick() == tick) history.samples.removeFirst();
        history.samples.addFirst(new Sample(tick, LeanGeometry.frame(player, LeanService.fraction(player, 1))));
        int limit = Math.clamp((int) Math.ceil(OtherConfig.SERVER_HITBOX_LATENCY_MAX_SAVE_MS.get() / 50), 1, 200);
        while (history.samples.size() > limit) history.samples.removeLast();
        refresh(index, history);
    }
    private static void refresh(Index index, History history) {
        removeCells(index, history);
        history.hasLean = history.samples.stream().anyMatch(sample -> Math.abs(sample.frame().fraction()) > ACTIVE_EPSILON);
        history.union = null;
        if (!history.hasLean) return;
        for (Sample sample : history.samples) history.union = history.union == null ? sample.frame().bounds() : history.union.minmax(sample.frame().bounds());
        history.cells = cells(history.union);
        if (history.cells.isEmpty()) index.wide.add(history.player.getUUID());
        for (Cell cell : history.cells) index.cells.computeIfAbsent(cell, ignored -> new HashSet<>()).add(history.player.getUUID());
    }
    private static void removeCells(Index index, History history) {
        index.wide.remove(history.player.getUUID());
        for (Cell cell : history.cells) {
            Set<UUID> members = index.cells.get(cell);
            if (members != null) {
                members.remove(history.player.getUUID());
                if (members.isEmpty()) index.cells.remove(cell);
            }
        }
        history.cells = Set.of();
    }
    public static void clear(Player player) {
        if (player.level().isClientSide) return;
        // A dimension-change event may arrive after the player's level reference changed.
        for (Index index : LEVELS.values()) {
            History history = index.players.remove(player.getUUID());
            if (history != null) removeCells(index, history);
        }
        LEVELS.entrySet().removeIf(entry -> entry.getValue().players.isEmpty());
    }
    public static void clearAll() { LEVELS.clear(); }

    /** Include historical leaned silhouettes even when the original entity AABB misses the broad phase. */
    public static List<Entity> candidates(Level level, List<Entity> nativeCandidates, Entity excluded,
            Predicate<? super Entity> filter, AABB query) {
        Index index = level.isClientSide ? null : LEVELS.get(level);
        var result = new ArrayList<>(nativeCandidates);
        Set<UUID> present = new HashSet<>();
        nativeCandidates.forEach(entity -> present.add(entity.getUUID()));
        // Input and first-shot callbacks can precede Post tick. Cover that current silhouette as well as history.
        for (Player player : level.players()) {
            if (present.contains(player.getUUID()) || player == excluded || !player.isAlive() || !filter.test(player)
                    || !player.getBoundingBox().inflate(1.25 * player.getScale()).intersects(query)) continue;
            double fraction = LeanService.fraction(player, 1);
            if (Math.abs(fraction) > ACTIVE_EPSILON && LeanGeometry.frame(player, fraction).bounds().intersects(query)) {
                result.add(player); present.add(player.getUUID());
            }
        }
        if (index == null) return result;
        Set<Cell> queryCells = cells(query);
        Set<UUID> ids = new HashSet<>(queryCells.isEmpty() ? index.players.keySet() : index.wide);
        for (Cell cell : queryCells) {
            Set<UUID> members = index.cells.get(cell);
            if (members != null) ids.addAll(members);
        }
        for (UUID id : ids) {
            History history = index.players.get(id);
            if (history == null || !history.hasLean || history.union == null || !history.union.intersects(query)) continue;
            Player player = history.player;
            if (player != excluded && player.isAlive() && filter.test(player) && player.level() == level && present.add(id)) result.add(player);
        }
        return result;
    }
    private static Set<Cell> cells(AABB bounds) {
        int minX = (int) Math.floor(bounds.minX / 16), maxX = (int) Math.floor(bounds.maxX / 16);
        int minZ = (int) Math.floor(bounds.minZ / 16), maxZ = (int) Math.floor(bounds.maxZ / 16);
        var result = new LinkedHashSet<Cell>();
        if ((long) maxX - minX + 1 > 64 || (long) maxZ - minZ + 1 > 64) return result;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) result.add(new Cell(x, z));
        return result;
    }
    private static LeanGeometry.Frame sample(Player target, Projectile bullet) {
        Index index = target.level().isClientSide ? null : LEVELS.get(target.level());
        History history = index == null ? null : index.players.get(target.getUUID());
        double current = LeanService.fraction(target, 1);
        if (history == null || !history.hasLean || !OtherConfig.SERVER_HITBOX_LATENCY_FIX.get()
                || !(bullet.getOwner() instanceof ServerPlayer shooter) || !(target instanceof ServerPlayer))
            return Math.abs(current) > ACTIVE_EPSILON ? LeanGeometry.frame(target, current) : null;
        int lag = Math.max(0, (int) Math.floor(shooter.connection.latency() / 50.0 + .5));
        if (lag == 0) return Math.abs(current) > ACTIVE_EPSILON ? LeanGeometry.frame(target, current) : null;
        long wanted = target.level().getGameTime() - lag;
        Sample found = history.samples.getLast();
        for (Sample entry : history.samples) {
            if (entry.tick() <= wanted) { found = entry; break; }
        }
        // Neutral history entries still use TaCZ's original hitbox policy, preserving ordinary controls.
        return Math.abs(found.frame().fraction()) > ACTIVE_EPSILON ? found.frame() : null;
    }
    /** Null hit with customGeometry=true is a real miss, not a request to also hit the unleaned body. */
    public record Trace(boolean customGeometry, EntityKineticBullet.EntityResult hit) { }
    public static Trace trace(Player target, Projectile bullet, Vec3 start, Vec3 end) {
        LeanGeometry.Frame frame = sample(target, bullet);
        if (frame == null) return new Trace(false, null);
        return new Trace(true, frame.intersect(start, end)
                .map(hit -> new EntityKineticBullet.EntityResult(target, hit.point(), hit.head())).orElse(null));
    }
}
