package com.example.examplemod.lootspawn.client;

import com.example.examplemod.lootspawn.DroppedLootPolicy;
import com.example.examplemod.lootspawn.GroundLootEntity;
import com.example.examplemod.lootspawn.GroundLootFootprints;
import com.example.examplemod.lootspawn.GroundLootPayloads;
import com.example.examplemod.lootspawn.GroundLootPhysics;
import com.example.examplemod.lootspawn.GroundLootWorldPhysics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded, client-only visual handoff; never owns inventory or a server-side entity. */
public final class DroppedLootTransitionClient {
    private static final int MAX_TRANSITIONS = 128;
    private static final int MAX_FRAMES = 256;
    private static final int FRAME_TTL_TICKS = 20;
    private static final Map<UUID, LastFrame> FRAMES = new LinkedHashMap<>();
    private static final Map<UUID, Transition> TARGETS = new LinkedHashMap<>();
    private static final Map<UUID, Transition> SOURCES = new HashMap<>();
    private static ClientLevel level;
    private static long clientTick;
    private static int nextGhostId = -2_000_000_000;
    private static boolean registered;

    private DroppedLootTransitionClient() {}

    public static void register() {
        if (registered) return;
        registered = true;
        GroundLootPayloads.setClientDropHandler(DroppedLootTransitionClient::receive);
        NeoForge.EVENT_BUS.addListener(DroppedLootTransitionClient::tick);
        NeoForge.EVENT_BUS.addListener(DroppedLootTransitionClient::entityLeft);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
    }

    /** Called from the normal entity render pipeline, after it chose the frame's partial tick. */
    static void noteFrame(ItemEntity entity, float partialTicks) {
        if (entity instanceof LandingGhost || !ensureLevel(entity.level())) return;
        Vec3 rendered = new Vec3(Mth.lerp(partialTicks, entity.xOld, entity.getX()),
                Mth.lerp(partialTicks, entity.yOld, entity.getY()),
                Mth.lerp(partialTicks, entity.zOld, entity.getZ()));
        FRAMES.remove(entity.getUUID());
        FRAMES.put(entity.getUUID(), new LastFrame(entity.getId(), rendered, DroppedLootPolicy.yaw(entity), clientTick));
        trimFrames();
    }

    static boolean shouldHideDropped(ItemEntity entity) {
        if (entity instanceof LandingGhost || !ensureLevel(entity.level())) return false;
        Transition transition = SOURCES.get(entity.getUUID());
        return transition != null && transition.sourceId == entity.getId();
    }

    static boolean shouldHideGround(GroundLootEntity entity) {
        if (!ensureLevel(entity.level())) return false;
        Transition transition = TARGETS.get(entity.getUUID());
        return transition != null && transition.targetId == entity.getId() && transition.ghost != null;
    }

    private static void receive(GroundLootPayloads.DropLanded payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !ensureLevel(mc.level) || payload.item().isEmpty()
                || !DroppedLootPolicy.isConvertible(payload.item(), mc.level)
                || !finite(payload.landing()) || !finite(payload.velocity()) || !Float.isFinite(payload.yaw())) return;
        Transition previous = TARGETS.get(payload.targetUuid());
        if (previous != null && previous.targetId == payload.targetEntityId()) return;
        if (previous != null) remove(previous);
        Transition previousSource = SOURCES.get(payload.sourceUuid());
        if (previousSource != null) remove(previousSource);
        while (TARGETS.size() >= MAX_TRANSITIONS) remove(TARGETS.values().iterator().next());

        Entity source = level.getEntity(payload.sourceEntityId());
        boolean sourceMatches = source instanceof ItemEntity && source.getUUID().equals(payload.sourceUuid());
        LastFrame frame = FRAMES.remove(payload.sourceUuid());
        boolean recentFrame = frame != null && frame.entityId == payload.sourceEntityId()
                && clientTick - frame.tick <= FRAME_TTL_TICKS;
        Vec3 start = recentFrame ? frame.position : sourceMatches ? source.position() : payload.landing();
        // Spawn packets quantize rotation to one byte. Keep the last visible yaw;
        // without a visible source, match the exact target spawn-packet convention.
        float yaw = recentFrame ? frame.yaw : sourceMatches ? DroppedLootPolicy.yaw((ItemEntity) source)
                : DroppedLootTransitionMath.spawnYaw(payload.yaw());
        // A stale or very large correction should not sweep a decorative gun across the map.
        if (!finite(start) || start.distanceToSqr(payload.landing()) > 64) start = payload.landing();
        LandingGhost ghost = new LandingGhost(level, nextId(), payload.item(), start, yaw);
        level.addEntity(ghost);
        if (level.getEntity(ghost.getId()) != ghost) {
            ghost.discard();
            return;
        }
        Transition transition = new Transition(payload.sourceEntityId(), payload.sourceUuid(),
                payload.targetEntityId(), payload.targetUuid(), payload.velocity(), ghost, clientTick);
        TARGETS.put(transition.targetUuid, transition);
        SOURCES.put(transition.sourceUuid, transition);
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            clear();
            return;
        }
        ensureLevel(mc.level);
        if (mc.isPaused()) return;
        clientTick++;
        Iterator<Transition> iterator = TARGETS.values().iterator();
        while (iterator.hasNext()) {
            Transition transition = iterator.next();
            Entity target = level.getEntity(transition.targetId);
            boolean matches = target instanceof GroundLootEntity && target.getUUID().equals(transition.targetUuid);
            boolean ready = matches && !target.isRemoved() && !((GroundLootEntity) target).getItem().isEmpty();
            boolean targetGone = transition.targetSeen && (!matches || target.isRemoved())
                    || transition.targetReady && matches && ((GroundLootEntity) target).getItem().isEmpty();
            transition.targetSeen |= matches;
            transition.targetReady |= ready;
            long elapsed = clientTick - transition.started;
            if (targetGone || DroppedLootTransitionMath.expired(elapsed)) {
                removeGhost(transition);
                SOURCES.remove(transition.sourceUuid, transition);
                iterator.remove();
                continue;
            }
            if (transition.ghost != null) {
                if (transition.ghost.isRemoved()) {
                    SOURCES.remove(transition.sourceUuid, transition);
                    iterator.remove();
                    continue;
                }
                transition.ghost.setOldPosAndRot();
                if (ready) {
                    GroundLootEntity ground = (GroundLootEntity) target;
                    if (ground.isPhysicsBlocked()) {
                        // Only an embedded small carrier is blocked; model
                        // overhang alone never suppresses the transition.
                        removeGhost(transition);
                    } else {
                        // Conversion now occurs only after the native ItemEntity
                        // lands. Keep the target's authoritative endpoint, but
                        // begin this frame at the same visible position as the
                        // ghost. Waiting another tick or chasing an old spawn
                        // point would introduce an artificial landing pause.
                        if (DroppedLootTransitionMath.canHandoff(elapsed, 0, true)) {
                            handoff(transition, ground);
                        }
                    }
                } else {
                    predict(transition);
                }
            }
            if (transition.ghost == null) {
                Entity source = level.getEntity(transition.sourceId);
                if (source == null || !source.getUUID().equals(transition.sourceUuid) || source.isRemoved()) {
                    SOURCES.remove(transition.sourceUuid, transition);
                    iterator.remove();
                }
            }
        }
        FRAMES.entrySet().removeIf(entry -> clientTick - entry.getValue().tick > FRAME_TTL_TICKS);
    }

    /** Only the temporary visual carrier predicts; the real client entity follows server packets. */
    private static void predict(Transition transition) {
        LandingGhost ghost = transition.ghost;
        AABB bounds = ghost.getBoundingBox();
        Vec3 desired = GroundLootPhysics.fallingVelocity(transition.velocity);
        var collision = GroundLootWorldPhysics.blockers(ghost, GroundLootPhysics.sweepBounds(bounds, desired));
        if (collision == null) {
            transition.velocity = Vec3.ZERO;
            return; // Missing/oversized collision queries must never guess a free path.
        }
        if (collision.stream().anyMatch(bounds.deflate(1e-6)::intersects)) {
            transition.velocity = Vec3.ZERO;
            return; // Preserve the visible carrier; recovery remains authoritative.
        }
        GroundLootPhysics.Step step = GroundLootPhysics.step(bounds, transition.velocity, collision);
        ghost.setPos(ghost.position().add(step.movement()));
        transition.velocity = step.grounded() ? Vec3.ZERO : step.velocity();
    }

    /** Splice the old render segment into the real entity without moving its server endpoint. */
    private static void handoff(Transition transition, GroundLootEntity ground) {
        LandingGhost ghost = transition.ghost;
        ground.xOld = ghost.xOld;
        ground.yOld = ghost.yOld;
        ground.zOld = ghost.zOld;
        // The first target frame must retain this prepared interpolation segment:
        // LevelRenderer otherwise rewrites old coordinates for tickCount == 0.
        ground.tickCount = Math.max(1, ground.tickCount);
        removeGhost(transition);
    }

    private static void entityLeft(EntityLeaveLevelEvent event) {
        if (event.getLevel() != level || !(event.getEntity() instanceof GroundLootEntity target)) return;
        Transition transition = TARGETS.get(target.getUUID());
        if (transition != null && transition.targetId == target.getId()) remove(transition);
    }

    private static boolean ensureLevel(net.minecraft.world.level.Level candidate) {
        ClientLevel current = Minecraft.getInstance().level;
        if (level != current) {
            clear();
            level = current;
        }
        return current != null && candidate == current;
    }

    private static void clear() {
        for (Transition transition : TARGETS.values()) removeGhost(transition);
        TARGETS.clear();
        SOURCES.clear();
        FRAMES.clear();
        level = null;
        clientTick = 0;
    }

    private static void remove(Transition transition) {
        removeGhost(transition);
        TARGETS.remove(transition.targetUuid, transition);
        SOURCES.remove(transition.sourceUuid, transition);
    }

    private static void removeGhost(Transition transition) {
        if (transition.ghost == null) return;
        LandingGhost ghost = transition.ghost;
        // Check object identity before using ClientLevel's removal API, so ID reuse
        // cannot remove another mod's entity. The old level is valid during a world swap.
        if (ghost.level() instanceof ClientLevel ghostLevel && ghostLevel.getEntity(ghost.getId()) == ghost) {
            ghostLevel.removeEntity(ghost.getId(), Entity.RemovalReason.DISCARDED);
        } else {
            ghost.discard();
        }
        transition.ghost = null;
    }

    private static int nextId() {
        while (level.getEntity(nextGhostId) != null) advanceId();
        int id = nextGhostId;
        advanceId();
        return id;
    }

    private static void advanceId() {
        nextGhostId = nextGhostId == Integer.MIN_VALUE ? -2_000_000_000 : nextGhostId - 1;
    }

    private static void trimFrames() {
        while (FRAMES.size() > MAX_FRAMES) FRAMES.remove(FRAMES.keySet().iterator().next());
    }

    private static boolean finite(Vec3 position) {
        return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z);
    }

    private record LastFrame(int entityId, Vec3 position, float yaw, long tick) {}

    private static final class Transition {
        final int sourceId;
        final UUID sourceUuid;
        final int targetId;
        final UUID targetUuid;
        final long started;
        Vec3 velocity;
        LandingGhost ghost;
        boolean targetSeen;
        boolean targetReady;

        Transition(int sourceId, UUID sourceUuid, int targetId, UUID targetUuid, Vec3 velocity,
                LandingGhost ghost, long started) {
            this.sourceId = sourceId;
            this.sourceUuid = sourceUuid;
            this.targetId = targetId;
            this.targetUuid = targetUuid;
            this.velocity = velocity;
            this.ghost = ghost;
            this.started = started;
        }
    }

    /** Inserted only into ClientLevel; its negative ID is never sent to the server. */
    private static final class LandingGhost extends ItemEntity {
        LandingGhost(ClientLevel clientLevel, int id, ItemStack stack, Vec3 start, float yaw) {
            super(clientLevel, start.x, start.y, start.z, stack.copyWithCount(1), 0, 0, 0);
            setId(id);
            noPhysics = true;
            setNoGravity(true);
            setInvulnerable(true);
            setYRot(yaw);
            setOldPosAndRot();
            // LevelRenderer rewrites old position for tickCount==0. This purely visual
            // carrier is already initialized, so preserve the first blend segment.
            tickCount = 1;
        }

        @Override public void tick() { setDeltaMovement(Vec3.ZERO); }
        @Override protected AABB makeBoundingBox() {
            return GroundLootFootprints.collisionBounds().move(position());
        }
        @Override public AABB getBoundingBoxForCulling() {
            return GroundLootFootprints.worldBounds(GroundLootFootprints.forItem(getItem(), level()), position(), getYRot());
        }
        @Override public void playerTouch(Player player) {}
        @Override public boolean isPickable() { return false; }
        @Override public boolean canBeHitByProjectile() { return false; }
        @Override public boolean isAttackable() { return false; }
        @Override public boolean canBeCollidedWith() { return false; }
        @Override public boolean hurt(DamageSource source, float amount) { return false; }
        @Override public boolean isCurrentlyGlowing() { return false; }
        @Override public boolean shouldBeSaved() { return false; }
        @Override public boolean save(CompoundTag tag) { return false; }
        @Override public boolean saveAsPassenger(CompoundTag tag) { return false; }
    }
}
