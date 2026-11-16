package com.example.examplemod.lean.client;

import com.example.examplemod.lean.LeanGeometry;
import com.example.examplemod.lean.LeanService;
import java.util.ArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One displayed eye for camera, block picking and entity picking, independent of authoritative shot geometry. */
public final class LeanCameraView {
    private static final LeanViewTransition TRANSITION = new LeanViewTransition();
    private static Player owner, framePlayer;
    private static Level world;
    private static Vec3 previousFeet, frameEye;
    private static float framePartialTick;
    private static int lastTick;
    private static boolean frameLeaning;
    private LeanCameraView() { }

    public static void clear() {
        end(); owner = null; world = null; previousFeet = null; TRANSITION.reset();
    }

    public static void begin(Player player, Camera camera, float partialTick) {
        Vec3 feet = player.getPosition(partialTick);
        if (owner != player || world != player.level() || previousFeet == null
                || previousFeet.distanceToSqr(feet) > 4 || player.tickCount < lastTick
                || player.tickCount - lastTick > 2 || !LeanService.eligible(player)) TRANSITION.reset();
        owner = player; world = player.level(); previousFeet = feet; lastTick = player.tickCount;
        framePlayer = player;
        framePartialTick = partialTick;
        LeanGeometry.Pose pose = LeanService.renderPose(player, partialTick);
        double fraction = LeanService.fraction(player, partialTick);
        frameLeaning = Math.abs(fraction) > 1.0e-7;
        var eyes = new LeanViewTransition.Eyes(player.getEyeHeight(Pose.STANDING),
                player.getEyeHeight(Pose.CROUCHING), player.getEyeHeight(Pose.SWIMMING));
        Vec3 offset = TRANSITION.offset(pose, eyes, fraction, player.tickCount + (double) partialTick);
        // This is Camera.setup's native base, including its tick-based eye-height easing.
        double height = ((LeanCameraAccess) camera).taczPubg$interpolatedEyeHeight(partialTick);
        Vec3 candidate = feet.add(0, height, 0).add(offset);
        if (frameLeaning) {
            // LeanGeometry.clamp validates at the actual feet, not the renderer's interpolated position.
            Vec3 legalEye = player.position().add(0, pose.eyeHeight(), 0).add(LeanGeometry.offset(pose, fraction));
            candidate = constrained(player, legalEye, candidate);
        }
        frameEye = candidate;
    }

    public static void end() { framePlayer = null; frameEye = null; frameLeaning = false; }

    public static float partialTick(Entity entity, float fallback) {
        return entity == framePlayer ? framePartialTick : fallback;
    }

    public static Vec3 pickEye(Player player, float partialTick) {
        partialTick = partialTick(player, partialTick);
        return player == framePlayer && frameLeaning ? frameEye
                : player.getEyePosition(partialTick).add(LeanService.offset(player, partialTick));
    }

    public static Vec3 cameraEye(Player player, Vec3 nativeEye) {
        // The prepared eye is shared with the earlier pick pass; with no lean preserve vanilla exactly.
        return player == framePlayer && frameLeaning ? frameEye : nativeEye;
    }

    private static Vec3 constrained(Player player, Vec3 legalEye, Vec3 candidate) {
        if (candidate.distanceToSqr(legalEye) < 1.0e-12) return candidate;
        double radius = LeanGeometry.EYE_RADIUS * player.getScale();
        var obstacles = new ArrayList<AABB>();
        for (var shape : player.level().getBlockCollisions(player, new AABB(legalEye, candidate).inflate(radius)))
            obstacles.addAll(shape.toAabbs());
        double limit = LeanViewTransition.sweepFraction(legalEye, candidate, obstacles, radius);
        var border = player.level().getWorldBorder();
        Vec3 result = legalEye.lerp(candidate, limit);
        if (!border.isWithinBounds(new AABB(result, result).inflate(radius))) {
            double low = 0, high = limit;
            for (int step = 0; step < 12; step++) {
                double middle = (low + high) / 2;
                Vec3 sample = legalEye.lerp(candidate, middle);
                if (border.isWithinBounds(new AABB(sample, sample).inflate(radius))) low = middle;
                else high = middle;
            }
            result = legalEye.lerp(candidate, low);
        }
        return result;
    }
}
