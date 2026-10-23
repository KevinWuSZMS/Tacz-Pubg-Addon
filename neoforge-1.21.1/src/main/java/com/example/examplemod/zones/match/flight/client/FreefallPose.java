package com.example.examplemod.zones.match.flight.client;

import com.example.examplemod.zones.match.flight.ParachuteItem;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** Rendering-only flight animation; never changes entity pose, hitbox or elytra physics. */
public final class FreefallPose {
    private static final Map<LivingEntity, Integer> START_TICKS = new WeakHashMap<>();

    private FreefallPose() {}

    public static boolean active(LivingEntity entity) {
        boolean active = entity instanceof Player && entity.isAlive() && !entity.isSpectator()
                && !entity.onGround() && !entity.isInWater() && !entity.isInLava()
                && !entity.isPassenger() && !entity.isFallFlying()
                && ParachuteItem.isEquipped(entity) && FlightClient.renderStage(entity) == 2;
        if (!active) START_TICKS.remove(entity);
        return active;
    }

    public static int animationTicks(LivingEntity entity) {
        if (!active(entity)) return 0;
        int started = START_TICKS.computeIfAbsent(entity, ignored -> entity.tickCount);
        // Match vanilla's short transition into its full gliding animation.
        return Math.max(1, entity.tickCount - started + 1);
    }

    static void clear() {
        START_TICKS.clear();
    }
}
