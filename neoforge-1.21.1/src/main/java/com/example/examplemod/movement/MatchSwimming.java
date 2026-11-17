package com.example.examplemod.movement;

import com.example.examplemod.combat.CombatService;
import com.example.examplemod.inventory.InventoryConfig;
import com.example.examplemod.movement.MovementState.Mode;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Match swimming is determined by the water column, never by sprint or the current pose's height. */
public final class MatchSwimming {
    private static final double EPSILON = 1.0E-6;
    public static final double SWIMMING_DEPTH = 1.5;
    private static final double SURFACE_RETENTION = .25;
    public enum DepthBand { DRY, ANKLE, KNEE, WAIST, DEEP }
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
        if (!inMatch(player) || !player.isAlive() || player.isSpectator()
                || player.getAbilities().flying || player.isPassenger() || player.isSleeping()
                || player.isFallFlying() || player.isAutoSpinAttack() || player.isInLava()
                || CombatService.downed(player) || CombatService.rescuing(player)) return false;
        if (player instanceof ServerPlayer server && MatchFlightService.airborne(server.server, player.getUUID())) return false;
        return (!ParachuteItem.isEquipped(player) || player.onGround())
                && (player.isInWater() || seed(player, player.getX(), player.getZ()) != null);
    }

    public static boolean swimming(Player player) {
        return depthBand(player) == DepthBand.DEEP;
    }

    /** Match-only bands: (0,.5], (.5,1], (1,1.5], then swimming. */
    public static DepthBand depthBand(Player player) {
        if (!controls(player)) return DepthBand.DRY;
        return depthBand(columnDepth(player, seed(player, player.getX(), player.getZ()),
                player.getX(), player.getZ(), SWIMMING_DEPTH));
    }

    public static DepthBand depthBand(double depth) {
        if (!Double.isFinite(depth) || depth <= 0) return DepthBand.DRY;
        if (depth <= .5) return DepthBand.ANKLE;
        if (depth <= 1) return DepthBand.KNEE;
        if (depth <= SWIMMING_DEPTH) return DepthBand.WAIST;
        return DepthBand.DEEP;
    }

    /** Illegal requests return standing; no automatic crouch or walking substitution. */
    public static Mode legalGroundMode(Player player, Mode requested) {
        return switch (depthBand(player)) {
            case DRY, ANKLE -> requested;
            case KNEE -> requested == Mode.SPRINT || requested == Mode.PRONE ? Mode.STAND : requested;
            case WAIST -> requested == Mode.SPRINT || requested == Mode.PRONE || requested == Mode.CROUCH
                    ? Mode.STAND : requested;
            case DEEP -> Mode.STAND;
        };
    }

    /** Actual shallow-water contact, never the brief air gap retained for an existing swimmer. */
    public static boolean wading(Player player) {
        var bounds = player.getBoundingBox();
        return player.isInWater() && controls(player)
                && bodySeed(player, player.getX(), player.getZ(), bounds.minY, bounds.maxY) != null
                && !hasSwimmingDepth(player);
    }

    /** Shallow water keeps ground stance controls, including during a jump into it. */
    public static boolean waterAllowsGround(Player player) {
        if (CombatService.rescuing(player)) return !player.isUnderWater();
        if (inMatch(player) && controls(player)) return !hasSwimmingDepth(player);
        return !player.isSwimming() && !player.isInWater() && !player.isUnderWater();
    }

    public static boolean hasSwimmingDepth(Player player) {
        // Strictly greater than 1.5 blocks; pose, scale and touching the floor do not alter this boundary.
        return columnDepth(player, seed(player, player.getX(), player.getZ()),
                player.getX(), player.getZ(), SWIMMING_DEPTH) > SWIMMING_DEPTH;
    }

    /**
     * Limit only upward motion that would lift the swimming body completely out of its
     * deep water column. A horizontal move onto shallow water or shore is left intact.
     * Call at the actual movement step, after native liquid jumping/look thrust.
     */
    public static Vec3 limitSurfaceRise(Player player, Vec3 movement) {
        if (movement.y <= 0 || !swimming(player)) return movement;
        double x = player.getX() + movement.x, z = player.getZ() + movement.z;
        Segment destination = seed(player, x, z);
        if (columnDepth(player, destination, x, z, SWIMMING_DEPTH) <= SWIMMING_DEPTH) return movement;
        // Keep the body below the surface while the eyes can clear it to breathe.
        double immersion = Math.max(player.getDimensions(Pose.SWIMMING).height() * .1,
                player.getEyeHeight(Pose.SWIMMING) - .04);
        double surface = nearbySurface(player, destination, x, z, player.getY() + movement.y + immersion);
        if (!Double.isFinite(surface)) return movement;
        if (canClimbOut(player, movement, false)) return movement;
        double allowed = Math.max(0, surface - immersion - player.getY());
        return movement.y <= allowed ? movement : new Vec3(movement.x, allowed, movement.z);
    }

    /** A reachable dry/shallow landing ahead permits the ordinary upward bank-climbing motion. */
    public static boolean canClimbOut(Player player) {
        boolean deep = swimming(player);
        return (deep || wading(player)) && canClimbOut(player, player.getDeltaMovement(), !deep);
    }

    private static boolean canClimbOut(Player player, Vec3 movement, boolean shallow) {
        // Looking at a bank while holding only Space must not bypass the surface cap.
        // Input still works when a wall has already reduced horizontal velocity to zero.
        double yaw = player.getYRot() * Math.PI / 180;
        Vec3 heading = new Vec3(player.xxa * Math.cos(yaw) - player.zza * Math.sin(yaw),
                0, player.zza * Math.cos(yaw) + player.xxa * Math.sin(yaw));
        if (heading.lengthSqr() < 1.0E-6) heading = new Vec3(movement.x, 0, movement.z);
        if (heading.lengthSqr() < 1.0E-6) return false;
        heading = heading.normalize();
        var standing = player.getDimensions(Pose.STANDING);
        double halfWidth = standing.width() / 2;
        for (double reach : new double[] { halfWidth + .15, halfWidth + .4, halfWidth + .6 }) {
            double x = player.getX() + heading.x * reach, z = player.getZ() + heading.z * reach;
            int first = Math.max(player.level().getMinBuildHeight(), Mth.floor(player.getY() - .3) - 1);
            int last = Math.min(player.level().getMaxBuildHeight() - 1, Mth.floor(player.getY() + 1.1));
            for (int y = first; y <= last; y++) {
                BlockPos pos = BlockPos.containing(x, y, z);
                if (!player.level().hasChunkAt(pos)) continue;
                var shape = player.level().getBlockState(pos)
                        .getCollisionShape(player.level(), pos, CollisionContext.of(player));
                double localX = x - pos.getX(), localZ = z - pos.getZ();
                for (var support : shape.toAabbs()) {
                    if (localX < support.minX || localX > support.maxX
                            || localZ < support.minZ || localZ > support.maxZ) continue;
                    double top = y + support.maxY;
                    if (top < player.getY() - .3 || top > player.getY() + 1.1) continue;
                    // A level shallow pool floor is not an exit: allow jumping only
                    // toward higher support, while level exits can simply be walked onto.
                    if (shallow && top <= player.getY() + EPSILON) continue;
                    AABB landing = new AABB(x - halfWidth, top + EPSILON, z - halfWidth,
                            x + halfWidth, top + standing.height(), z + halfWidth);
                    if (!player.level().noCollision(player, landing)) continue;
                    Segment water = bodySeed(player, x, z, top, top + standing.height());
                    if (columnDepth(player, water, x, z, SWIMMING_DEPTH) <= SWIMMING_DEPTH) return true;
                }
            }
        }
        return false;
    }

    private static double nearbySurface(Player player, Segment seed, double x, double z, double maximum) {
        double top = seed.top();
        int y = seed.blockY() + 1;
        while (top <= maximum + EPSILON && y < player.level().getMaxBuildHeight()
                && Math.abs(top - y) < EPSILON) {
            Segment next = touching(waterSegments(player, x, z, y), top, true);
            if (next == null) break;
            top = next.top();
            y++;
        }
        return top <= maximum + EPSILON ? top : Double.NaN;
    }

    /**
     * Test the contiguous open water interval at the player's XZ. Start where the body
     * touches that column and stop as soon as the threshold is proved: a deep ocean
     * never requires a scan to its floor. Collision shapes split waterlogged blocks.
     */
    public static boolean depthAtLeast(Player player, double requiredDepth) {
        if (!Double.isFinite(requiredDepth) || requiredDepth <= 0) return false;
        return depthAtLeast(player, seed(player, player.getX(), player.getZ()), player.getX(), player.getZ(), requiredDepth);
    }

    private static Segment seed(Player player, double x, double z) {
        var bounds = player.getBoundingBox();
        Segment contact = bodySeed(player, x, z, bounds.minY, bounds.maxY);
        if (contact != null) return contact;
        int first = Math.max(player.level().getMinBuildHeight(), Mth.floor(bounds.minY + EPSILON));
        // Fluid contact uses the current (short swimming) AABB. A small upward step can
        // clear that native flag for a tick. Retain only an existing swimmer immediately
        // above an unobstructed water surface; never start swimming in unrelated air.
        if (!player.isSwimming() || player.onGround()) return null;
        int below = Math.max(player.level().getMinBuildHeight(), Mth.floor(bounds.minY - SURFACE_RETENTION));
        for (int y = first; y >= below; y--) {
            for (Segment candidate : waterSegments(player, x, z, y)) {
                double gap = bounds.minY - candidate.top();
                if (gap < -EPSILON || gap > SURFACE_RETENTION + EPSILON) continue;
                if (gap > 2 * EPSILON && !player.level().noCollision(player,
                        new AABB(x - EPSILON, candidate.top() + EPSILON, z - EPSILON,
                                x + EPSILON, bounds.minY - EPSILON, z + EPSILON))) continue;
                return candidate;
            }
        }
        return null;
    }

    private static Segment bodySeed(Player player, double x, double z, double minY, double maxY) {
        int first = Math.max(player.level().getMinBuildHeight(), Mth.floor(minY + EPSILON));
        int last = Math.min(player.level().getMaxBuildHeight() - 1, Mth.floor(maxY - EPSILON));
        for (int y = first; y <= last; y++) {
            for (Segment candidate : waterSegments(player, x, z, y)) {
                if (candidate.top() > minY + EPSILON && candidate.bottom() < maxY - EPSILON) return candidate;
            }
        }
        return null;
    }

    private static boolean depthAtLeast(Player player, Segment seed, double x, double z, double requiredDepth) {
        return columnDepth(player, seed, x, z, requiredDepth) + EPSILON >= requiredDepth;
    }

    private static double columnDepth(Player player, Segment seed, double x, double z, double stopAfter) {
        if (seed == null) return 0;
        double bottom = seed.bottom(), top = seed.top();
        if (top - bottom > stopAfter) return top - bottom;

        for (int y = seed.blockY() + 1; y < player.level().getMaxBuildHeight() && Math.abs(top - y) < EPSILON; y++) {
            Segment next = touching(waterSegments(player, x, z, y), top, true);
            if (next == null) break;
            top = next.top();
            if (top - bottom > stopAfter) return top - bottom;
        }
        for (int y = seed.blockY() - 1; y >= player.level().getMinBuildHeight() && Math.abs(bottom - (y + 1)) < EPSILON; y--) {
            Segment next = touching(waterSegments(player, x, z, y), bottom, false);
            if (next == null) break;
            bottom = next.bottom();
            if (top - bottom > stopAfter) return top - bottom;
        }
        return top - bottom;
    }

    private static Segment touching(List<Segment> segments, double boundary, boolean upward) {
        for (Segment segment : segments) {
            if (upward ? Math.abs(segment.bottom() - boundary) < EPSILON
                    : Math.abs(segment.top() - boundary) < EPSILON) return segment;
        }
        return null;
    }

    private static List<Segment> waterSegments(Player player, double x, double z, int y) {
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!player.level().hasChunkAt(pos)) return List.of();
        var block = player.level().getBlockState(pos);
        var water = block.getFluidState();
        if (!water.is(FluidTags.WATER)) return List.of();
        double surface = y + water.getHeight(player.level(), pos);
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(y, y, surface));
        double localX = x - pos.getX(), localZ = z - pos.getZ();
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
