package com.example.examplemod.movement;

import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunMeleeEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.damagesource.DamageTypes;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
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

    public static boolean throwable(ItemStack stack) {
        return stack.getItem() instanceof me.xjqsh.lrtactical.api.item.IThrowable;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void melee(GunMeleeEvent event) {
        if (blocked(event.getShooter())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void attack(AttackEntityEvent event) {
        if (blocked(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void meleeDamage(LivingIncomingDamageEvent event) {
        var source = event.getSource();
        if (source.is(DamageTypes.PLAYER_ATTACK) && source.getEntity() instanceof Player player
                && source.getDirectEntity() == player && blocked(player)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void throwableUse(PlayerInteractEvent.RightClickItem event) {
        if (throwable(event.getItemStack()) && blocked(event.getEntity())) event.setCanceled(true);
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
