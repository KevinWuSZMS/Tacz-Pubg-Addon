package com.example.examplemod.movement;

import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Uses the match water rule, not Pose.SWIMMING (which also represents land prone). */
@EventBusSubscriber(modid = "tacz_pubg")
public final class SwimmingGunRestrictions {
    private SwimmingGunRestrictions() {}

    public static boolean blocked(LivingEntity shooter) {
        return shooter instanceof Player player && MatchSwimming.swimming(player);
    }

    /** A false result removes TaCZ's pending burst task, including its remaining rounds. */
    public static BooleanSupplier guardRound(LivingEntity shooter, BooleanSupplier nativeRound) {
        return () -> !blocked(shooter) && nativeRound.getAsBoolean();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void shoot(GunShootEvent event) {
        if (blocked(event.getShooter())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void fire(GunFireEvent event) {
        // The client event runs on its main thread before gun sound and animation.
        if (blocked(event.getShooter())) event.setCanceled(true);
    }
}
