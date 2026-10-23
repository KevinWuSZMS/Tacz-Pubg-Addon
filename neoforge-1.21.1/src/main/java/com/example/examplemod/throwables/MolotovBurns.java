package com.example.examplemod.throwables;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** A short residual burn, owned by the originating round so no vanilla fire survives round cleanup. */
final class MolotovBurns {
    private static final Map<MinecraftServer, Map<UUID, Burn>> STATES = new WeakHashMap<>();
    private static final class Burn {
        PubgThrowableEntity source;
        final LivingEntity target;
        int remaining = 80, cadence;
        Burn(PubgThrowableEntity source, LivingEntity target) { this.source = source; this.target = target; }
    }
    static void register(IEventBus bus) {
        bus.addListener(MolotovBurns::tick);
        bus.addListener((ServerStoppedEvent event) -> STATES.remove(event.getServer()));
    }
    static void ignite(PubgThrowableEntity source, LivingEntity target) {
        if (target.fireImmune()) return;
        var burns = STATES.computeIfAbsent(source.level().getServer(), ignored -> new HashMap<>());
        Burn burn = burns.computeIfAbsent(target.getUUID(), ignored -> new Burn(source, target));
        burn.source = source;
        burn.remaining = ThrowableSettingsService.profile(source.kind()).integer("burn_ticks", 80);
        burn.cadence = 0;
    }
    static DamageSource damage(PubgThrowableEntity source) {
        return new DamageSource(source.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(DamageTypes.ON_FIRE), source, source.getOwner());
    }
    private static void tick(ServerTickEvent.Post event) {
        var burns = STATES.get(event.getServer());
        if (burns == null) return;
        var iterator = burns.values().iterator();
        while (iterator.hasNext()) {
            Burn burn = iterator.next();
            var target = burn.target;
            var source = burn.source;
            if (!(target.level() instanceof ServerLevel level) || target.level() != source.level()
                    || !target.isAlive() || target.isRemoved() || target.isInWater() || target.fireImmune()
                    || !ThrowableService.scopeExists(level, source.session(), source.mapId(), source.position())
                    || !ThrowableService.scopeExists(level, source.session(), source.mapId(), target.position())
                    || !ThrowableService.mayAffect(source, target)) { iterator.remove(); continue; }
            if (ThrowableService.paused(level, source.session())) continue;
            var profile = ThrowableSettingsService.profile(source.kind());
            if (++burn.cadence % Math.max(1, profile.integer("damage_interval_ticks", 20)) == 0)
                target.hurt(damage(source), (float) profile.fireDamage());
            if (burn.cadence % 5 == 0) level.sendParticles(ParticleTypes.FLAME,
                    target.getX(), target.getY() + .5, target.getZ(), 2, .2, .3, .2, .01);
            if (--burn.remaining <= 0) iterator.remove();
        }
    }
    private MolotovBurns() {}
}
