package com.example.examplemod.zones.match;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/** Match-local source policy. Existing loot and player drops are never removed by these rules. */
public final class MatchDropService {
    private MatchDropService() {}

    public static void register(IEventBus bus) {
        // Stop drops before another listener can convert them into a different carrier.
        bus.addListener(EventPriority.HIGHEST, MatchDropService::livingDrops);
        bus.addListener(EventPriority.HIGHEST, MatchDropService::livingExperience);
        bus.addListener(EventPriority.HIGHEST, MatchDropService::blockDrops);
    }

    /** Reads the running match snapshot, never the draft or restoration/protection state. */
    public static boolean blocksBlockDrops(Level level, double x, double z) {
        return blocksDrops(level, x, z, true);
    }

    public static boolean blocksMobDrops(Entity source) {
        return source instanceof LivingEntity && !(source instanceof Player)
                && blocksDrops(source.level(), source.getX(), source.getZ(), false);
    }

    private static boolean blocksDrops(Level level, double x, double z, boolean block) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        return blocksDrops(MatchService.activeGames(serverLevel.getServer()), level.dimension().location(), x, z, block);
    }

    static boolean blocksDrops(Iterable<MatchEngine> games, net.minecraft.resources.ResourceLocation dimension,
            double x, double z, boolean block) {
        for (MatchEngine game : games) {
            var map = game.session().map();
            boolean allowed = block ? game.settings().allowBlockDrops() : game.settings().allowMobDrops();
            if (!game.finished() && !allowed && map.dimension().equals(dimension) && map.contains(x, z)) return true;
        }
        return false;
    }

    private static void livingDrops(LivingDropsEvent event) {
        if (blocksMobDrops(event.getEntity())) {
            event.getDrops().clear();
            event.setCanceled(true);
        }
    }

    private static void livingExperience(LivingExperienceDropEvent event) {
        if (blocksMobDrops(event.getEntity())) {
            event.setDroppedExperience(0);
            event.setCanceled(true);
        }
    }

    private static void blockDrops(BlockDropsEvent event) {
        if (blocksBlockDrops(event.getLevel(), event.getPos().getX() + .5, event.getPos().getZ() + .5)) {
            event.getDrops().clear();
            event.setDroppedExperience(0);
            event.setCanceled(true);
        }
    }

}
