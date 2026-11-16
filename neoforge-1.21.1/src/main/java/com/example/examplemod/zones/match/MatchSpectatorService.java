package com.example.examplemod.zones.match;

import com.example.examplemod.zones.match.bot.MatchBotService;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Eliminated participants watch a server-selected player; this is not an administrator's free camera. */
public final class MatchSpectatorService {
    private static final String MARKER = "tacz_pubg_match_spectator";
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> CHANGING_CAMERA = ThreadLocal.withInitial(() -> false);

    public record View(@Nullable UUID sessionId, boolean active, boolean locked,
                       int targetEntityId, String targetName, boolean teammatesOnly) {
        public static final View INACTIVE = new View(null, false, false, -1, "", false);
    }

    private static final class State {
        final Map<UUID, Watching> viewers = new HashMap<>();
        final Map<UUID, Map<UUID, UUID>> killers = new HashMap<>();
    }

    private static final class Watching {
        final UUID session;
        ResourceKey<Level> dimension;
        Vec3 anchor;
        float yaw;
        float pitch;
        @Nullable UUID target;
        @Nullable UUID lastTarget;
        @Nullable UUID killer;
        boolean died;
        boolean teammatesOnly;
        long lastSwitch = Long.MIN_VALUE;

        Watching(UUID session, ServerPlayer player) {
            this.session = session;
            anchorAt(player);
        }

        void anchorAt(ServerPlayer player) {
            dimension = player.level().dimension();
            anchor = player.position();
            yaw = player.getYRot();
            pitch = player.getXRot();
        }
    }

    private MatchSpectatorService() {}

    /** Also records bot victims so a human watching a bot can follow its killer. */
    static void death(ServerPlayer player, MatchEngine game, @Nullable UUID killer) {
        State state = STATES.computeIfAbsent(player.server, ignored -> new State());
        Map<UUID, UUID> killers = state.killers.computeIfAbsent(game.session().sessionId(), ignored -> new HashMap<>());
        if (killer != null && !killer.equals(player.getUUID()) && game.contains(killer)) killers.put(player.getUUID(), killer);
        else killers.remove(player.getUUID());
        if (MatchBotService.isCarpetPlayer(player)) return;
        Watching watching = new Watching(game.session().sessionId(), player);
        watching.died = true;
        watching.killer = killers.get(player.getUUID());
        state.viewers.put(player.getUUID(), watching);
        persist(player, watching);
    }

    /** Runs after the complete presence batch, before victory evaluation can remove the session. */
    static void tick(MinecraftServer server, List<MatchEngine> games) {
        for (MatchEngine game : games) {
            if (game.finished()) continue;
            for (MatchEngine.Participant participant : game.roster()) {
                if (game.alive(participant.id())) continue;
                ServerPlayer player = server.getPlayerList().getPlayer(participant.id());
                if (player == null || MatchBotService.isCarpetPlayer(player) || !shouldSpectate(player, game)) continue;
                Watching watching = findOrCreate(player, game);
                if (!player.isAlive()) {
                    // Death drops and other LivingDeathEvent listeners have completed by this point.
                    ServerPlayer replacement = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
                    replacement.connection.player = replacement;
                    player = replacement;
                }
                MatchService.enterSpectator(player, game);
                if (!player.isSpectator()) continue;
                updateTarget(player, game, watching);
                enforceCamera(player, watching);
                persist(player, watching);
            }
        }
    }

    static void clonePlayer(ServerPlayer original, ServerPlayer replacement) {
        if (original.getPersistentData().contains(MARKER, Tag.TAG_COMPOUND))
            replacement.getPersistentData().put(MARKER, original.getPersistentData().getCompound(MARKER).copy());
    }

    static void login(ServerPlayer player) {
        MatchEngine game = MatchService.participantGame(player.server, player.getUUID());
        if (game == null || game.alive(player.getUUID()) || !shouldSpectate(player, game)
                || MatchBotService.isCarpetPlayer(player)) {
            clear(player);
            return;
        }
        findOrCreate(player, game);
        // Camera changes wait until the player has fully joined and is tracked by the level.
    }

    /** Detach before logout while retaining the round marker needed to resume spectating on reconnect. */
    static void logout(ServerPlayer player) {
        Watching watching = existing(player);
        if (watching != null) persist(player, watching);
        withCameraChanges(() -> player.setCamera(player));
    }

    /** Used before vanilla spawn-block lookup, so death spectating never consumes a respawn anchor. */
    @Nullable public static DimensionTransition respawnTransition(ServerPlayer player, boolean keepEverything) {
        DimensionTransition botTransition = MatchBotService.respawnTransition(player, keepEverything);
        if (botTransition != null) return botTransition;
        if (keepEverything || MatchBotService.isCarpetPlayer(player)) return null;
        MatchEngine game = MatchService.participantGame(player.server, player.getUUID());
        if (game == null || game.alive(player.getUUID()) || !shouldSpectate(player, game)) return null;
        Watching watching = findOrCreate(player, game);
        ServerLevel level = player.server.getLevel(watching.dimension);
        if (level == null) return null;
        return new DimensionTransition(level, watching.anchor, Vec3.ZERO,
                watching.yaw, watching.pitch, DimensionTransition.DO_NOTHING);
    }

    public static View view(ServerPlayer player) {
        Watching watching = existing(player);
        if (watching == null || !managed(player, watching)) return View.INACTIVE;
        ServerPlayer target = watching.target == null ? null : player.server.getPlayerList().getPlayer(watching.target);
        if (target != null && (!target.isAlive() || target.isRemoved())) target = null;
        return new View(watching.session, true, true, target == null ? -1 : target.getId(),
                target == null ? "" : target.getGameProfile().getName(), watching.teammatesOnly);
    }

    public static boolean locked(ServerPlayer player) {
        Watching watching = existing(player);
        return watching != null && managed(player, watching);
    }

    public static boolean allowCameraChange(ServerPlayer player) {
        return CHANGING_CAMERA.get() || !locked(player);
    }

    /** Actual deaths always spectate. The legacy toggle still controls non-death eliminations. */
    static boolean shouldSpectate(ServerPlayer player, MatchEngine game) {
        if (game.settings().spectateEliminated()) return true;
        Watching watching = existing(player);
        if (watching != null && watching.session.equals(game.session().sessionId()) && watching.died) return true;
        CompoundTag marker = player.getPersistentData().getCompound(MARKER);
        return marker.hasUUID("session") && marker.getUUID("session").equals(game.session().sessionId())
                && marker.getBoolean("died");
    }

    /** Only the surviving teammate list can be cycled; enemy spectating follows the killer chain. */
    public static boolean cycle(ServerPlayer player, UUID sessionId, int direction) {
        if (!player.server.isSameThread() || direction != -1 && direction != 1) return false;
        Watching watching = existing(player);
        MatchEngine game = MatchService.participantGame(player.server, player.getUUID());
        if (watching == null || game == null || !managed(player, watching)
                || !watching.session.equals(sessionId) || !player.isAlive() || !player.isSpectator()) return false;
        List<ServerPlayer> candidates = candidates(player, game, watching);
        if (!watching.teammatesOnly || candidates.isEmpty()) return false;
        long tick = player.server.getTickCount();
        if (watching.lastSwitch != Long.MIN_VALUE && tick - watching.lastSwitch < 4) return false;
        int current = -1;
        for (int i = 0; i < candidates.size(); i++)
            if (candidates.get(i).getUUID().equals(watching.target)) { current = i; break; }
        int next = current < 0 ? (direction > 0 ? 0 : candidates.size() - 1)
                : Math.floorMod(current + direction, candidates.size());
        watching.target = candidates.get(next).getUUID();
        watching.lastSwitch = tick;
        enforceCamera(player, watching);
        persist(player, watching);
        return true;
    }

    static void stop(MinecraftServer server, UUID session) {
        State state = STATES.get(server);
        if (state == null) return;
        state.killers.remove(session);
        for (var entry : new ArrayList<>(state.viewers.entrySet())) {
            if (!entry.getValue().session.equals(session)) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) clear(player);
            else state.viewers.remove(entry.getKey());
        }
    }

    static void stopped(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) clear(player);
        STATES.remove(server);
    }

    /** Clears stale markers on restart/end; original-mode restoration remains owned by MatchService. */
    static void clearIfStale(ServerPlayer player) {
        Watching watching = existing(player);
        if (watching != null && !managed(player, watching)
                || watching == null && player.getPersistentData().contains(MARKER)) clear(player);
    }

    private static boolean managed(ServerPlayer player, Watching watching) {
        if (MatchBotService.isCarpetPlayer(player)) return false;
        MatchEngine game = MatchService.participantGame(player.server, player.getUUID());
        return game != null && !game.finished() && game.session().sessionId().equals(watching.session)
                && !game.alive(player.getUUID()) && shouldSpectate(player, game);
    }

    @Nullable private static Watching existing(ServerPlayer player) {
        State state = STATES.get(player.server);
        return state == null ? null : state.viewers.get(player.getUUID());
    }

    private static Watching findOrCreate(ServerPlayer player, MatchEngine game) {
        State state = STATES.computeIfAbsent(player.server, ignored -> new State());
        Watching watching = state.viewers.get(player.getUUID());
        if (watching != null && watching.session.equals(game.session().sessionId())) return watching;
        watching = new Watching(game.session().sessionId(), player);
        CompoundTag tag = player.getPersistentData().getCompound(MARKER);
        if (tag.hasUUID("session") && tag.getUUID("session").equals(watching.session)) {
            ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
            double x = tag.getDouble("x"), y = tag.getDouble("y"), z = tag.getDouble("z");
            if (dimension != null && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                watching.dimension = ResourceKey.create(Registries.DIMENSION, dimension);
                watching.anchor = new Vec3(x, y, z);
                watching.yaw = tag.getFloat("yaw");
                watching.pitch = tag.getFloat("pitch");
            }
            if (tag.hasUUID("target")) watching.target = tag.getUUID("target");
            if (tag.hasUUID("lastTarget")) watching.lastTarget = tag.getUUID("lastTarget");
            if (tag.hasUUID("killer")) watching.killer = tag.getUUID("killer");
            watching.died = tag.getBoolean("died");
        }
        state.viewers.put(player.getUUID(), watching);
        persist(player, watching);
        return watching;
    }

    private static void updateTarget(ServerPlayer player, MatchEngine game, Watching watching) {
        List<ServerPlayer> candidates = candidates(player, game, watching);
        if (candidates.stream().anyMatch(target -> target.getUUID().equals(watching.target))) return;
        UUID previous = watching.target == null ? watching.lastTarget : watching.target;
        watching.target = null;
        if (candidates.isEmpty()) return;
        if (watching.teammatesOnly) {
            watching.target = candidates.getFirst().getUUID();
            return;
        }
        State state = STATES.get(player.server);
        Map<UUID, UUID> killers = state == null ? Map.of() : state.killers.getOrDefault(watching.session, Map.of());
        UUID candidate = previous == null ? watching.killer : killers.get(previous);
        Set<UUID> visited = new HashSet<>();
        while (candidate != null && visited.add(candidate)) {
            UUID selected = candidate;
            if (candidates.stream().anyMatch(target -> target.getUUID().equals(selected))) {
                watching.target = candidate;
                return;
            }
            candidate = killers.get(candidate);
        }
        watching.target = candidates.stream().min(java.util.Comparator
                .comparingDouble(target -> target.position().distanceToSqr(watching.anchor)))
                .orElseThrow().getUUID();
    }

    private static List<ServerPlayer> candidates(ServerPlayer viewer, MatchEngine game, Watching watching) {
        String team = game.roster().stream().filter(entry -> entry.id().equals(viewer.getUUID()))
                .map(MatchEngine.Participant::teamId).findFirst().orElse("");
        // A disconnected teammate retains their match life during the configured reconnect grace period.
        watching.teammatesOnly = game.settings().teamSize() > 1 && game.roster().stream()
                .anyMatch(entry -> entry.teamId().equals(team) && game.alive(entry.id()));
        List<ServerPlayer> candidates = new ArrayList<>();
        for (MatchEngine.Participant entry : game.roster()) {
            if (!game.alive(entry.id()) || watching.teammatesOnly && !entry.teamId().equals(team)) continue;
            ServerPlayer target = viewer.server.getPlayerList().getPlayer(entry.id());
            if (target != null && target.isAlive() && !target.isRemoved() && !target.isSpectator()
                    && target.serverLevel().dimension().location().equals(game.session().map().dimension())
                    && game.session().map().contains(target.getX(), target.getZ())) candidates.add(target);
        }
        return candidates;
    }

    private static void enforceCamera(ServerPlayer player, Watching watching) {
        ServerPlayer target = watching.target == null ? null : player.server.getPlayerList().getPlayer(watching.target);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        player.setShiftKeyDown(false);
        if (target != null) {
            watching.lastTarget = target.getUUID();
            watching.anchorAt(target);
            if (player.getCamera() != target) withCameraChanges(() -> player.setCamera(target));
        } else {
            withCameraChanges(() -> {
                if (player.getCamera() != player) player.setCamera(player);
                ServerLevel level = player.server.getLevel(watching.dimension);
                if (level != null && (level != player.serverLevel() || player.position().distanceToSqr(watching.anchor) > .0001
                        || Math.abs(player.getYRot() - watching.yaw) > .01 || Math.abs(player.getXRot() - watching.pitch) > .01))
                    player.teleportTo(level, watching.anchor.x, watching.anchor.y, watching.anchor.z, watching.yaw, watching.pitch);
            });
        }
    }

    private static void clear(ServerPlayer player) {
        State state = STATES.get(player.server);
        if (state != null) state.viewers.remove(player.getUUID());
        player.getPersistentData().remove(MARKER);
        withCameraChanges(() -> { if (player.getCamera() != player) player.setCamera(player); });
    }

    private static void persist(ServerPlayer player, Watching watching) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("session", watching.session);
        tag.putString("dimension", watching.dimension.location().toString());
        tag.putDouble("x", watching.anchor.x); tag.putDouble("y", watching.anchor.y); tag.putDouble("z", watching.anchor.z);
        tag.putFloat("yaw", watching.yaw); tag.putFloat("pitch", watching.pitch);
        if (watching.target != null) tag.putUUID("target", watching.target);
        if (watching.lastTarget != null) tag.putUUID("lastTarget", watching.lastTarget);
        if (watching.killer != null) tag.putUUID("killer", watching.killer);
        tag.putBoolean("died", watching.died);
        player.getPersistentData().put(MARKER, tag);
    }

    private static void withCameraChanges(Runnable action) {
        boolean previous = CHANGING_CAMERA.get();
        CHANGING_CAMERA.set(true);
        try { action.run(); }
        finally { CHANGING_CAMERA.set(previous); }
    }
}
