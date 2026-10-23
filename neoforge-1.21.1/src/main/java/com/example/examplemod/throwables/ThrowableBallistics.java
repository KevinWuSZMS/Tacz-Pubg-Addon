package com.example.examplemod.throwables;

import me.xjqsh.lrtactical.entity.ThrowableItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared launch parameters. Vanilla supplies the exact rotation and moving-shooter inheritance. */
public final class ThrowableBallistics {
    public static final float GRAVITY = .05F;
    public static final double BOUNCE = .55;
    public static final double PLACEMENT_REACH = 3;
    private ThrowableBallistics() {}

    public static void configure(ThrowableItemEntity projectile) {
        configure(projectile, ThrowableKind.FRAG);
    }
    public static void configure(ThrowableItemEntity projectile, ThrowableKind kind) {
        var profile = ThrowableSettingsService.profile(kind);
        projectile.setLife(-1);
        projectile.setHitDamage((float) profile.hitDamage());
        projectile.setThrowableGravity((float) profile.gravity());
        projectile.setBounceFactor(profile.bounce());
    }
    public static void launch(Projectile projectile, LivingEntity owner, boolean dropped) {
        launch(projectile, owner, dropped, kind(projectile));
    }
    public static void launch(Projectile projectile, LivingEntity owner, boolean dropped, ThrowableKind kind) {
        launch(projectile, owner, dropped, kind, owner.getEyePosition(), owner.getXRot(), owner.getYRot());
    }
    /** Same launch at a render-frame eye position, without moving or rotating the actual owner. */
    public static void launch(Projectile projectile, LivingEntity owner, boolean dropped,
            Vec3 eye, float pitch, float yaw) {
        launch(projectile, owner, dropped, kind(projectile), eye, pitch, yaw);
    }
    public static void launch(Projectile projectile, LivingEntity owner, boolean dropped, ThrowableKind kind,
            Vec3 eye, float pitch, float yaw) {
        var profile = ThrowableSettingsService.profile(kind);
        projectile.setOwner(owner);
        Vec3 look = owner.calculateViewVector(pitch, yaw);
        Vec3 desired = eye.add(look.scale(.25));
        var obstruction = owner.level().clip(new ClipContext(eye, desired,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        // Do not spawn across a thin wall when the player's eye is very close to its surface.
        Vec3 start = obstruction.getType() == HitResult.Type.BLOCK
                ? obstruction.getLocation().subtract(look.scale(.01)) : desired;
        projectile.setPos(start);
        if (dropped) projectile.setDeltaMovement(0, -profile.droppedSpeed(), 0);
        else {
            boolean low = owner.isShiftKeyDown();
            projectile.shootFromRotation(owner, pitch, yaw, low ? 0 : (float) profile.pitchOffset(),
                    (float) (low ? profile.lowSpeed() : profile.speed()), 0);
        }
    }
    private static ThrowableKind kind(Projectile projectile) {
        return projectile instanceof PubgThrowableEntity throwable ? throwable.kind() : ThrowableKind.FRAG;
    }
    /** The server additionally checks its map-reset lock and stationary deployment transaction. */
    public static BlockHitResult placement(LivingEntity owner) {
        return placement(owner, owner.getEyePosition(), owner.getLookAngle());
    }
    public static BlockHitResult placement(LivingEntity owner, Vec3 eye, Vec3 look) {
        var hit = owner.level().clip(new ClipContext(eye, eye.add(look.scale(placementReach())),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }
    public static double placementReach() {
        return ThrowableSettingsService.profile(ThrowableKind.C4).placementReach();
    }
}
