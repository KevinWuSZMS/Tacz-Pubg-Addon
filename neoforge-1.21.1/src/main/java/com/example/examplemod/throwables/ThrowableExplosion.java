package com.example.examplemod.throwables;

import com.example.examplemod.backup.MapResetService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.EventHooks;

/** Damage radius is independent of terrain power; frag terrain uses a normal creeper's power 3. */
final class ThrowableExplosion extends Explosion {
    private final PubgThrowableEntity source;
    private final ServerLevel level;
    private ThrowableExplosion(PubgThrowableEntity source, ServerLevel level, float terrainPower, BlockInteraction blocks) {
        super(level, source, level.damageSources().explosion(source,
                        source.getOwner() instanceof LivingEntity living ? living : null),
                new ExplosionDamageCalculator() {
                    @Override public boolean shouldDamageEntity(Explosion explosion, Entity entity) { return false; }
                    @Override public float getKnockbackMultiplier(Entity entity) { return 0; }
                    @Override public boolean shouldBlockExplode(Explosion explosion, BlockGetter world,
                            BlockPos pos, BlockState state, float power) {
                        return !MapResetService.isLocked(level, pos)
                                && ThrowableService.scopeExists(level, source.session(), source.mapId(), net.minecraft.world.phys.Vec3.atCenterOf(pos));
                    }
                }, source.getX(), source.getY(), source.getZ(), terrainPower, false, blocks,
                ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, SoundEvents.GENERIC_EXPLODE);
        this.source = source; this.level = level;
    }
    boolean allowed(BlockPos pos) {
        return !MapResetService.isLocked(level, pos)
                && ThrowableService.scopeExists(level, source.session(), source.mapId(), net.minecraft.world.phys.Vec3.atCenterOf(pos));
    }
    static void detonate(PubgThrowableEntity source, ServerLevel level) {
        var kind = source.kind();
        var profile = ThrowableSettingsService.profile(kind);
        double radius = profile.radius();
        // Do not force unloaded neighbouring chunks into existence for an explosive.
        float terrainPower = (float) profile.number("terrain_power", kind == ThrowableKind.C4 ? 6 : 3);
        boolean loaded = true;
        BlockPos origin = source.blockPosition();
        int extent = (int) (terrainPower * 2 + 3);
        for (int x = (origin.getX() - extent) >> 4; x <= (origin.getX() + extent) >> 4; x++)
            for (int z = (origin.getZ() - extent) >> 4; z <= (origin.getZ() + extent) >> 4; z++)
                if (level.getChunkSource().getChunkNow(x, z) == null) loaded = false;
        var mode = loaded && profile.destroyBlocks() && level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                ? BlockInteraction.DESTROY : BlockInteraction.KEEP;
        var explosion = new ThrowableExplosion(source, level, terrainPower, mode);
        if (EventHooks.onExplosionStart(level, explosion)) return;
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, source.getBoundingBox().inflate(radius))) {
            if (!ThrowableService.mayAffect(source, victim)) continue;
            double exposure = kind == ThrowableKind.C4 ? 1 : loadedBetween(level, source, victim)
                    ? Explosion.getSeenPercent(source.position(), victim) : 0;
            double distance = victim.distanceTo(source);
            double amount = distance >= radius || radius <= 0 ? 0 : profile.damage()
                    * Math.pow(1 - Math.pow(distance / radius, profile.number("damage_curve_p", 2)),
                            profile.number("damage_curve_q", kind == ThrowableKind.C4 ? 2 : 1.5))
                    * Math.clamp(exposure, 0, 1);
            // Minecraft's grounded crawling pose is the prone adaptation; swimming in water is excluded.
            if (kind == ThrowableKind.FRAG && victim.getPose() == net.minecraft.world.entity.Pose.SWIMMING
                    && victim.onGround() && !victim.isInWater()) amount *= profile.number("prone_damage_multiplier", .8);
            if (amount > 0) victim.hurt(level.damageSources().explosion(source,
                    source.getOwner() instanceof LivingEntity living ? living : null), (float) amount);
        }
        if (loaded) explosion.explode();
        else {
            // KEEP alone still traces native block rays and would load chunks. Dispatch entity interaction
            // events explicitly so smoke holes and destruction of armed C4 work without block traversal.
            level.gameEvent(source, net.minecraft.world.level.gameevent.GameEvent.EXPLODE, source.position());
            var affected = level.getEntities(source, source.getBoundingBox().inflate(terrainPower * 2));
            EventHooks.onExplosionDetonate(level, explosion, affected, terrainPower * 2);
        }
        explosion.getToBlow().removeIf(pos -> !explosion.allowed(pos));
        explosion.finalizeExplosion(true);
        if (mode == BlockInteraction.KEEP) explosion.clearToBlow();
        explosion.getHitPlayers().clear();
        for (var player : level.players()) if (player.distanceToSqr(source) < 128 * 128)
            player.connection.send(new ClientboundExplodePacket(source.getX(), source.getY(), source.getZ(), terrainPower,
                    explosion.getToBlow(), null, mode, explosion.getSmallExplosionParticles(),
                    explosion.getLargeExplosionParticles(), explosion.getExplosionSound()));
    }
    private static boolean loadedBetween(ServerLevel level, Entity first, Entity second) {
        int minX = (int) Math.floor(Math.min(first.getX(), second.getBoundingBox().minX)) - 1;
        int maxX = (int) Math.ceil(Math.max(first.getX(), second.getBoundingBox().maxX)) + 1;
        int minZ = (int) Math.floor(Math.min(first.getZ(), second.getBoundingBox().minZ)) - 1;
        int maxZ = (int) Math.ceil(Math.max(first.getZ(), second.getBoundingBox().maxZ)) + 1;
        for (int x = minX >> 4; x <= maxX >> 4; x++)
            for (int z = minZ >> 4; z <= maxZ >> 4; z++)
                if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        return true;
    }
}
