package com.example.examplemod.zones.bombing;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.ZoneSettings.BombingProperties;
import com.example.examplemod.zones.match.MatchEngine;
import com.example.examplemod.zones.match.MatchService;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Real, ownerless TaCZ rockets, scoped to one frozen match and never persisted across server stops. */
public final class BombingService {
    public static final String MARKER = "tacz_pubg:bombing_projectile";
    private static final Map<MinecraftServer, LinkedHashMap<UUID, Run>> RUNS = new WeakHashMap<>();
    private BombingService() {}

    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.LOWEST, BombingService::onDetonate);
    }

    private static final class Run {
        final MatchEngine engine;
        final BombingProperties properties;
        final Random random;
        final Map<Long, BombingPolicy.Rate> rates = new HashMap<>();
        final Set<EntityKineticBullet> inFlight = new HashSet<>();
        final Set<ResourceLocation> passThrough;
        boolean unavailable;
        int nextZone;
        Run(MatchEngine engine, BombingProperties properties) {
            this.engine = engine;
            this.properties = properties;
            passThrough = properties.passThroughBlocks().stream().map(ResourceLocation::parse)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            random = new Random(engine.session().sessionId().getMostSignificantBits()
                    ^ engine.session().sessionId().getLeastSignificantBits());
        }
        boolean active() {
            return !unavailable && !engine.finished() && !engine.paused() && engine.bombingActive();
        }
        void discardProjectiles() { inFlight.forEach(Entity::discard); inFlight.clear(); }
    }

    /** Called exactly when the server commits a match start, before settings may be edited again. */
    public static void started(MinecraftServer server, MatchEngine engine, BombingProperties properties) {
        RUNS.computeIfAbsent(server, ignored -> new LinkedHashMap<>())
                .put(engine.session().sessionId(), new Run(engine, properties));
    }

    public static void stop(MinecraftServer server, UUID session) {
        var runs = RUNS.get(server);
        if (runs == null) return;
        Run run = runs.remove(session);
        if (run != null) run.discardProjectiles();
        if (runs.isEmpty()) RUNS.remove(server);
    }

    public static void suspend(MinecraftServer server, UUID session) {
        var runs = RUNS.get(server);
        Run run = runs == null ? null : runs.get(session);
        if (run != null) {
            run.discardProjectiles();
            run.rates.values().forEach(BombingPolicy.Rate::reset);
        }
    }

    public static void stopped(MinecraftServer server) {
        var runs = RUNS.remove(server);
        if (runs != null) runs.values().forEach(Run::discardProjectiles);
    }

    /** Invoked after the match engine's tick; skipped strikes are spent, never queued for later. */
    public static void tick(MinecraftServer server, List<MatchEngine> engines) {
        var runs = RUNS.get(server);
        if (runs == null) return;
        Set<UUID> live = new HashSet<>();
        engines.forEach(engine -> live.add(engine.session().sessionId()));
        for (Iterator<Map.Entry<UUID, Run>> iterator = runs.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            if (!live.contains(entry.getKey())) { entry.getValue().discardProjectiles(); iterator.remove(); }
        }
        int totalFlight = 0;
        for (Run run : runs.values()) {
            run.inFlight.removeIf(bullet -> {
                boolean expired = BombingPolicy.expired(bullet.getPersistentData().getCompound(MARKER).getLong("born"),
                        server.overworld().getGameTime(), bullet.tickCount);
                if (bullet.isRemoved() || expired || !canTick(bullet)) { bullet.discard(); return true; }
                return false;
            });
            totalFlight += run.inFlight.size();
        }
        int globalBudget = BombingPolicy.MAX_PER_TICK;
        Map<String, ResourceLocation> availableAmmo = BombingAmmoCatalog.launchers();
        // Rotate session order to prevent a dense early-created map starving other active maps.
        List<Run> ordered = new ArrayList<>(runs.values());
        if (!ordered.isEmpty()) java.util.Collections.rotate(ordered, -(int) (server.overworld().getGameTime() % ordered.size()));
        for (Run run : ordered) {
            if (!run.active()) {
                totalFlight -= run.inFlight.size();
                run.discardProjectiles(); run.rates.clear(); continue;
            }
            var zones = run.engine.bombingZones();
            if (zones.isEmpty()) continue;
            Set<Long> zoneIds = zones.stream().map(MapZoneModel.BombingZone::id).collect(java.util.stream.Collectors.toSet());
            run.rates.keySet().retainAll(zoneIds);
            if (!availableAmmo.containsKey(run.properties.ammoId())) {
                run.unavailable = true;
                totalFlight -= run.inFlight.size();
                run.discardProjectiles();
                String message = Texts.tr("message.tacz_pubg.bombing_service.map_bombardment_waiting_rocket_resource_load_resource_start", run.engine.session().map().id(), run.properties.ammoId());
                ExampleMod.LOGGER.error("{}", message);
                for (ServerPlayer player : server.getPlayerList().getPlayers())
                    if (player.hasPermissions(2)) player.sendSystemMessage(Texts.component(message));
                continue;
            }
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, run.engine.session().map().dimension()));
            int localBudget = BombingPolicy.MAX_PER_SESSION_TICK;
            for (int index = 0; index < zones.size(); index++) {
                var zone = zones.get((index + run.nextZone) % zones.size());
                int due = run.rates.computeIfAbsent(zone.id(), ignored -> new BombingPolicy.Rate(0))
                        .advance(zone.missilesPerMinute());
                for (int shot = 0; shot < due; shot++) {
                    if (level == null || globalBudget <= 0 || localBudget <= 0
                            || totalFlight >= BombingPolicy.MAX_IN_FLIGHT_TOTAL
                            || run.inFlight.size() >= BombingPolicy.MAX_IN_FLIGHT_SESSION) continue;
                    // Bound attempts, including unloaded coordinates, rather than repeatedly rerolling toward players.
                    globalBudget--; localBudget--;
                    if (spawn(level, run, zone, availableAmmo.get(run.properties.ammoId()))) totalFlight++;
                }
            }
            run.nextZone = (run.nextZone + 1) % zones.size();
        }
        if (runs.isEmpty()) RUNS.remove(server);
    }

    private static boolean spawn(ServerLevel level, Run run, MapZoneModel.BombingZone zone, ResourceLocation launcher) {
        var point = BombingPolicy.sample(run.engine.session().map(), zone.area(), run.random);
        if (point == null) return false;
        int x = Mth.floor(point.x()), z = Mth.floor(point.z());
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null || !level.isPositionEntityTicking(new BlockPos(x, level.getMinBuildHeight(), z))) return false;
        double centerX = x + .5, centerZ = z + .5;
        if (!run.engine.session().map().contains(centerX, centerZ) || !zone.area().contains(centerX, centerZ)) return false;
        int ceiling = BombingPolicy.ceiling(run.properties.spawnHeight(), level.getMinBuildHeight(), level.getMaxBuildHeight());
        int spawnY = BombingPolicy.highestAir(ceiling, level.getMinBuildHeight(),
                y -> chunk.getBlockState(new BlockPos(x, y, z)).isAir());
        if (spawnY == BombingPolicy.NO_CONTACT) return false;
        Vec3 origin = new Vec3(centerX, spawnY + .5, centerZ);
        EntityKineticBullet bullet = new EntityKineticBullet(EntityKineticBullet.TYPE, level);
        ((BombingProjectile) bullet).taczPubg$configureBombingProjectile(ResourceLocation.parse(run.properties.ammoId()),
                launcher, origin, (float) run.properties.damageRadius());
        CompoundTag marker = new CompoundTag();
        marker.putUUID("session", run.engine.session().sessionId());
        marker.putLong("map", run.engine.session().map().id());
        marker.putLong("zone", zone.id());
        marker.putLong("born", level.getServer().overworld().getGameTime());
        bullet.getPersistentData().put(MARKER, marker);
        bullet.setPos(origin);
        bullet.setDeltaMovement(Vec3.ZERO);
        if (!level.addFreshEntity(bullet)) return false;
        run.inFlight.add(bullet);
        return true;
    }

    public static boolean isBombing(EntityKineticBullet bullet) {
        return bullet.getPersistentData().contains(MARKER, Tag.TAG_COMPOUND);
    }

    private static Run run(EntityKineticBullet bullet) {
        if (!(bullet.level() instanceof ServerLevel level) || !isBombing(bullet)) return null;
        CompoundTag tag = bullet.getPersistentData().getCompound(MARKER);
        if (!tag.hasUUID("session")) return null;
        var runs = RUNS.get(level.getServer());
        Run run = runs == null ? null : runs.get(tag.getUUID("session"));
        if (run == null || !run.inFlight.contains(bullet) || !run.active()) return null;
        if (!run.engine.session().map().dimension().equals(level.dimension().location())) return null;
        var session = MatchService.session(level.getServer(), run.engine.session().map().id());
        return session != null && session.sessionId().equals(run.engine.session().sessionId()) ? run : null;
    }

    /** Guarded before native ray tracing so unloaded terrain cannot be requested accidentally. */
    public static boolean canTick(EntityKineticBullet bullet) {
        if (bullet.tickCount >= BombingPolicy.MAX_LIFE_TICKS || !Double.isFinite(bullet.getY())
                || !(bullet.level() instanceof ServerLevel level)) return false;
        Run run = run(bullet);
        BlockPos pos = bullet.blockPosition();
        return run != null && run.engine.session().map().contains(bullet.getX(), bullet.getZ())
                && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
                && level.isPositionEntityTicking(pos) && bullet.getY() >= level.getMinBuildHeight();
    }

    /** Replaces only the marked server projectile's hit test; TaCZ still handles spawn data and rendering. */
    public static void fallingTick(EntityKineticBullet bullet) {
        if (!canTick(bullet)) { bullet.discard(); return; }
        Run run = run(bullet);
        ServerLevel level = (ServerLevel) bullet.level();
        int x = Mth.floor(bullet.getX()), z = Mth.floor(bullet.getZ());
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (run == null || chunk == null) { bullet.discard(); return; }
        double velocity = BombingPolicy.verticalVelocity(bullet.tickCount);
        double fromY = bullet.getY(), toY = fromY + velocity;
        int contactY = BombingPolicy.firstContactY(fromY, toY, level.getMinBuildHeight(), level.getMaxBuildHeight(), y -> {
            BlockState state = chunk.getBlockState(new BlockPos(x, y, z));
            return state.isAir() || run.passThrough.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        });
        if (contactY != BombingPolicy.NO_CONTACT) {
            Vec3 contact = new Vec3(bullet.getX(), Math.min(fromY, contactY + 1.0), bullet.getZ());
            bullet.setPos(contact);
            bullet.setDeltaMovement(Vec3.ZERO);
            hitBlock(bullet, new BlockHitResult(contact, Direction.UP, new BlockPos(x, contactY, z), false));
            return;
        }
        // Native movement follows this callback. Its fluid current/drag state is replaced on the next step.
        bullet.setDeltaMovement(0, velocity, 0);
    }

    public static void hitBlock(EntityKineticBullet bullet, BlockHitResult hit) {
        if (hit.getType() == HitResult.Type.MISS) return;
        if (NeoForge.EVENT_BUS.post(new AmmoHitBlockEvent(bullet.level(), hit,
                bullet.level().getBlockState(hit.getBlockPos()), bullet)).isCanceled()) { bullet.discard(); return; }
        detonate(bullet, hit.getLocation());
    }

    public static void detonate(EntityKineticBullet bullet, Vec3 hit) {
        Run run = run(bullet);
        bullet.discard();
        if (run == null || !(bullet.level() instanceof ServerLevel level)) return;
        run.inFlight.remove(bullet);
        var properties = run.properties;
        var map = run.engine.session().map();
        if (!map.contains(hit.x, hit.z) || !loadedBlastWindow(level, hit, properties.damageRadius())) return;
        Explosion.BlockInteraction mode = properties.destroyBlocks() ? Explosion.BlockInteraction.DESTROY : Explosion.BlockInteraction.KEEP;
        Predicate<Entity> eligible = entity -> run.active() && entity instanceof ServerPlayer player && player.isAlive()
                        && !player.isSpectator() && !player.isCreative() && run.engine.alive(player.getUUID())
                        && player.serverLevel() == level && map.contains(player.getX(), player.getZ())
                        && player.distanceToSqr(hit) <= properties.damageRadius() * properties.damageRadius();
        Predicate<BlockPos> allowedBlock = pos -> run.active() && pos != null && properties.destroyBlocks()
                && map.contains(pos.getX() + .5, pos.getZ() + .5)
                && Vec3.atCenterOf(pos).distanceToSqr(hit) <= properties.damageRadius() * properties.damageRadius();
        ExplosionDamageCalculator calculator = new ExplosionDamageCalculator() {
            @Override public boolean shouldDamageEntity(Explosion explosion, Entity entity) { return eligible.test(entity); }
            @Override public float getEntityDamageAmount(Explosion explosion, Entity entity) {
                return (float) BombingPolicy.damage(properties.damage(), properties.damageRadius(), properties.edgeDamageMultiplier(),
                        entity.position().distanceTo(hit), Explosion.getSeenPercent(hit, entity));
            }
            @Override public float getKnockbackMultiplier(Entity entity) { return 0; }
            @Override public boolean shouldBlockExplode(Explosion explosion, BlockGetter reader, BlockPos pos, BlockState state, float power) {
                return allowedBlock.test(pos);
            }
        };
        // No gun damage or owner attribution is involved: armor, blast protection and normal hurt events still apply.
        Explosion explosion = new ScopedExplosion(level, bullet, calculator, hit, (float) properties.damageRadius(),
                mode, eligible, allowedBlock);
        if (EventHooks.onExplosionStart(level, explosion) || !run.active()) return;
        explosion.explode();
        explosion.getToBlow().removeIf(pos -> !allowedBlock.test(pos));
        explosion.finalizeExplosion(true);
        if (mode == Explosion.BlockInteraction.KEEP) explosion.clearToBlow();
        // Keep the native packet's large-radius query from producing zero-knockback updates for non-participants.
        explosion.getHitPlayers().clear();
        for (ServerPlayer player : level.players()) if (player.distanceToSqr(hit) < 128 * 128) {
            player.connection.send(new ClientboundExplodePacket(hit.x, hit.y, hit.z, (float) properties.damageRadius(),
                    explosion.getToBlow(), null, mode, explosion.getSmallExplosionParticles(),
                    explosion.getLargeExplosionParticles(), explosion.getExplosionSound()));
        }
    }

    /** Vanilla's block rays travel below 2r; an extra border covers ray/visibility rounding. */
    static boolean loadedBlastWindow(ServerLevel level, Vec3 center, double radius) {
        double extent = radius * 2 + 3;
        int minX = Mth.floor(center.x - extent) >> 4, maxX = Mth.floor(center.x + extent) >> 4;
        int minZ = Mth.floor(center.z - extent) >> 4, maxZ = Mth.floor(center.z + extent) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        return true;
    }

    /** Filtering the native event list also excludes knockback and onExplosionHit side effects for other entities. */
    private static void onDetonate(ExplosionEvent.Detonate event) {
        if (event.getExplosion() instanceof ScopedExplosion explosion) {
            event.getAffectedEntities().removeIf(entity -> !explosion.eligible.test(entity));
            event.getAffectedBlocks().removeIf(pos -> !explosion.allowedBlock.test(pos));
        }
    }

    private static final class ScopedExplosion extends Explosion {
        private final Predicate<Entity> eligible;
        private final Predicate<BlockPos> allowedBlock;
        ScopedExplosion(ServerLevel level, EntityKineticBullet bullet, ExplosionDamageCalculator calculator, Vec3 hit,
                float radius, Explosion.BlockInteraction mode, Predicate<Entity> eligible, Predicate<BlockPos> allowedBlock) {
            super(level, bullet, level.damageSources().explosion(bullet, null), calculator, hit.x, hit.y, hit.z,
                    radius, false, mode, ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, SoundEvents.GENERIC_EXPLODE);
            this.eligible = eligible;
            this.allowedBlock = allowedBlock;
        }
    }
}
