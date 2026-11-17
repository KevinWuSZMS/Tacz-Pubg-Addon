package com.example.examplemod.movement;

import com.example.examplemod.combat.CombatService;
import com.example.examplemod.inventory.InventoryConfig;
import com.example.examplemod.zones.match.MatchService;
import com.example.examplemod.zones.match.flight.MatchFlightService;
import com.example.examplemod.zones.match.flight.ParachuteItem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Match swimming is determined by the water column, never by sprint or the current pose's height. */
public final class MatchSwimming {
    private static final double EPSILON = 1.0E-6;
    private record Segment(int blockY, double bottom, double top) { }
    private MatchSwimming() { }

    public static boolean inMatch(Player player) {
        if (player instanceof ServerPlayer server)
            return MatchService.participantRunning(server.server, player.getUUID());
        if (!player.level().isClientSide) return false;
        // A local client's inventory state describes only itself, not every tracked player.
        return player.isLocalPlayer() ? InventoryConfig.active(player)
                : ((MovementStateAccess) player).taczPubg$matchParticipant();
    }

    public static void syncParticipation(ServerPlayer player) {
        ((MovementStateAccess) player).taczPubg$matchParticipant(inMatch(player));
    }

    public static boolean controls(Player player) {
        if (!inMatch(player) || !player.isInWater() || !player.isAlive() || player.isSpectator()
                || player.getAbilities().flying || player.isPassenger() || player.isSleeping()
                || player.isFallFlying() || player.isAutoSpinAttack() || player.isInLava()
                || CombatService.downed(player) || CombatService.rescuing(player)) return false;
        if (player instanceof ServerPlayer server && MatchFlightService.airborne(server.server, player.getUUID())) return false;
        return !ParachuteItem.isEquipped(player) || player.onGround();
    }

    public static boolean swimming(Player player) {
        return controls(player) && (!player.onGround() || hasStandingDepth(player));
    }

    /** Shallow water retains ground controls even if crouching puts the eyes underwater. */
    public static boolean waterAllowsGround(Player player) {
        if (CombatService.rescuing(player)) return !player.isUnderWater();
        if (inMatch(player) && player.isInWater()) return player.onGround() && !hasStandingDepth(player);
        return !player.isSwimming() && !player.isInWater() && !player.isUnderWater();
    }

    public static boolean hasStandingDepth(Player player) {
        return depthAtLeast(player, player.getDimensions(Pose.STANDING).height());
    }

    /**
     * Test the contiguous open water interval at the player's XZ. Start where the body
     * touches that column and stop as soon as the threshold is proved: a deep ocean
     * never requires a scan to its floor. Collision shapes split waterlogged blocks.
     */
    public static boolean depthAtLeast(Player player, double requiredDepth) {
        if (!Double.isFinite(requiredDepth) || requiredDepth <= 0) return false;
        var bounds = player.getBoundingBox();
        int first = Math.max(player.level().getMinBuildHeight(), Mth.floor(bounds.minY + EPSILON));
        int last = Math.min(player.level().getMaxBuildHeight() - 1, Mth.floor(bounds.maxY - EPSILON));
        Segment seed = null;
        for (int y = first; y <= last && seed == null; y++) {
            for (Segment candidate : waterSegments(player, y)) {
                if (candidate.top() > bounds.minY + EPSILON && candidate.bottom() < bounds.maxY - EPSILON) {
                    seed = candidate;
                    break;
                }
            }
        }
        if (seed == null) return false;
        double bottom = seed.bottom(), top = seed.top();
        if (top - bottom + EPSILON >= requiredDepth) return true;

        for (int y = seed.blockY() + 1; y < player.level().getMaxBuildHeight() && Math.abs(top - y) < EPSILON; y++) {
            Segment next = touching(waterSegments(player, y), top, true);
            if (next == null) break;
            top = next.top();
            if (top - bottom + EPSILON >= requiredDepth) return true;
        }
        for (int y = seed.blockY() - 1; y >= player.level().getMinBuildHeight() && Math.abs(bottom - (y + 1)) < EPSILON; y--) {
            Segment next = touching(waterSegments(player, y), bottom, false);
            if (next == null) break;
            bottom = next.bottom();
            if (top - bottom + EPSILON >= requiredDepth) return true;
        }
        return false;
    }

    private static Segment touching(List<Segment> segments, double boundary, boolean upward) {
        for (Segment segment : segments) {
            if (upward ? Math.abs(segment.bottom() - boundary) < EPSILON
                    : Math.abs(segment.top() - boundary) < EPSILON) return segment;
        }
        return null;
    }

    private static List<Segment> waterSegments(Player player, int y) {
        BlockPos pos = BlockPos.containing(player.getX(), y, player.getZ());
        if (!player.level().hasChunkAt(pos)) return List.of();
        var block = player.level().getBlockState(pos);
        var water = block.getFluidState();
        if (!water.is(FluidTags.WATER)) return List.of();
        double surface = y + water.getHeight(player.level(), pos);
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(y, y, surface));
        double localX = player.getX() - pos.getX(), localZ = player.getZ() - pos.getZ();
        // Fences and walls can support the feet above their own block's top boundary.
        for (int supportY = Math.max(player.level().getMinBuildHeight(), y - 1); supportY <= y; supportY++) {
            BlockPos supportPos = new BlockPos(pos.getX(), supportY, pos.getZ());
            var shape = player.level().getBlockState(supportPos).getCollisionShape(player.level(), supportPos, CollisionContext.of(player));
            for (var box : shape.toAabbs()) {
                if (localX < box.minX - EPSILON || localX > box.maxX + EPSILON
                        || localZ < box.minZ - EPSILON || localZ > box.maxZ + EPSILON) continue;
                double low = supportY + box.minY, high = supportY + box.maxY;
                List<Segment> remaining = new ArrayList<>();
                for (Segment segment : segments) {
                    if (high <= segment.bottom() + EPSILON || low >= segment.top() - EPSILON) remaining.add(segment);
                    else {
                        if (low > segment.bottom() + EPSILON)
                            remaining.add(new Segment(y, segment.bottom(), Math.min(low, segment.top())));
                        if (high < segment.top() - EPSILON)
                            remaining.add(new Segment(y, Math.max(high, segment.bottom()), segment.top()));
                    }
                }
                segments = remaining;
                if (segments.isEmpty()) return segments;
            }
        }
        return segments;
    }
}
