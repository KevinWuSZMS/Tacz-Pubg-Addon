package com.example.examplemod.zones.match;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.match.MatchConfigModel.Phase;
import com.example.examplemod.zones.match.MatchConfigModel.Settings;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/** Pure tick-based game rules. No entities, worlds, inventories or persistent files are touched. */
public final class MatchEngine {
    public record Participant(UUID id, String name, String teamId) {
        public Participant {
            Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(teamId);
            if (name.isBlank() || teamId.isBlank()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_engine.participant_team_identifiers_not_empty"));
        }
    }
    public record Presence(boolean connected, boolean alive, boolean eligible,
            ResourceLocation dimension, double x, double z) {}
    private static final class PlayerState {
        final Participant player;
        boolean alive = true;
        boolean connected = true;
        long disconnectedAt = -1;
        long outsideTicks;
        Presence lastPresence;
        PlayerState(Participant player) { this.player = player; }
    }
    private final MatchSession session;
    private final Settings settings;
    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private final List<Circle> targets;
    private final Circle initialBoundary;
    private final MapZoneModel.FlightPath flightPath;
    private final com.example.examplemod.zones.bombing.BombingTimeline bombing;
    private long ticks;
    private boolean paused;
    private boolean finished;
    private String stage;
    private String result = "";
    private int phase;
    private int secondsRemaining;
    private Circle current;
    private Circle target;
    private double damage;
    private Circle phaseStart;
    private long stageElapsedTicks, stageDurationTicks;

    MatchEngine(MatchSession session, Settings settings, List<Participant> roster, long seed) {
        this.session = Objects.requireNonNull(session);
        this.settings = Objects.requireNonNull(settings);
        List<String> errors = MatchConfigModel.validate(settings);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        if (settings.mapId() != session.map().id()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_engine.match_plan_map_id_do_not_match"));
        if (session.map().safeZone() == null || session.map().safeZone().radius() < .5)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_engine.initial_safe_zone_radius_least_m"));
        if (roster.size() < settings.minPlayers() || roster.size() > settings.maxPlayers())
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_engine.participant_count_outside", roster.size(), settings.minPlayers(), settings.maxPlayers()));
        Map<String, Integer> teamSizes = new HashMap<>();
        for (Participant configured : roster) {
            Participant player = settings.teamSize() == 1
                    ? new Participant(configured.id(), configured.name(), "solo:" + configured.id()) : configured;
            if (players.putIfAbsent(player.id(), new PlayerState(player)) != null)
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_engine.duplicate_participant", player.name()));
            if (teamSizes.merge(player.teamId(), 1, Integer::sum) > settings.teamSize())
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_engine.team_exceeds_players_per_team", player.teamId(), settings.teamSize()));
        }
        if (settings.autoEnd() && teamSizes.size() < 2)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_engine.automatic_victory_requires_least_two_different_teams_disable"));
        targets = generateTargets(session.map(), settings, seed);
        var map = session.map();
        double centerX = (map.minX() + map.maxX()) / 2, centerZ = (map.minZ() + map.maxZ()) / 2;
        initialBoundary = new Circle(centerX, centerZ, Math.min(MapZoneModel.MAX_RADIUS, Math.max(
                Math.hypot(map.maxX() - map.minX(), map.maxZ() - map.minZ()) / 2,
                Math.hypot(map.safeZone().centerX() - centerX, map.safeZone().centerZ() - centerZ) + map.safeZone().radius())));
        flightPath = settings.flightRules().enabled()
                ? MatchFlightPath.generate(map, targets.isEmpty() ? map.safeZone() : targets.getFirst(), seed,
                        settings.flightRules().randomRoute()) : null;
        bombing = new com.example.examplemod.zones.bombing.BombingTimeline(map, settings, seed);
        current = initialBoundary;
        target = current;
        updatePhase();
    }

    public MatchSession session() { return session; }
    public Settings settings() { return settings; }
    public long elapsedTicks() { return ticks; }
    public boolean finished() { return finished; }
    public boolean paused() { return paused; }
    public List<Participant> roster() { return players.values().stream().map(state -> state.player).toList(); }
    public boolean contains(UUID id) { return players.containsKey(id); }
    public boolean alive(UUID id) { PlayerState player = players.get(id); return player != null && player.alive; }

    /** Presence is updated for the entire roster before a tick judges victory, allowing simultaneous draws. */
    public void presence(UUID id, Presence presence) {
        presence(id, presence, false);
    }
    /** Only a server-managed aircraft passenger may briefly cross the map edge in formation. */
    public void presence(UUID id, Presence presence, boolean inAircraft) {
        PlayerState player = players.get(id);
        if (finished || player == null || !player.alive) return;
        player.lastPresence = presence;
        if (!presence.connected()) {
            if (player.connected) { player.connected = false; player.disconnectedAt = ticks; }
            return;
        }
        long grace = settings.disconnectGraceSeconds() * 20L;
        if (!player.connected && ticks - player.disconnectedAt >= grace) {
            eliminate(id); return;
        }
        player.connected = true;
        player.disconnectedAt = -1;
        MapDefinition map = session.map();
        if (!presence.alive() || !presence.eligible() || !map.dimension().equals(presence.dimension())
                || !inAircraft && !map.contains(presence.x(), presence.z())) eliminate(id);
    }

    public boolean eliminate(UUID id) {
        PlayerState player = players.get(id);
        if (finished || player == null || !player.alive) return false;
        player.alive = false;
        return true;
    }

    /** Returns true for stage/phase/finish changes; circle motion is separately published at a bounded cadence. */
    public boolean advance() {
        if (finished || paused) return false;
        String beforeStage = stage;
        int beforePhase = phase;
        ticks++;
        for (PlayerState player : players.values()) {
            if (player.alive && !player.connected
                    && ticks - player.disconnectedAt >= settings.disconnectGraceSeconds() * 20L) player.alive = false;
        }
        updatePhase();
        for (PlayerState player : players.values()) {
            var p = player.lastPresence;
            boolean outside = hasBlueBoundary() && player.alive && p != null && p.connected() && p.eligible()
                    && session.map().dimension().equals(p.dimension()) && session.map().contains(p.x(), p.z())
                    && !current.contains(p.x(), p.z());
            player.outsideTicks = outside ? player.outsideTicks + 1 : 0;
        }
        if (ticks >= settings.preparationSeconds() * 20L) {
            int teams = aliveTeams();
            if (settings.autoEnd() && teams <= 1) {
                if (teams == 0) finish(Texts.tr("message.tacz_pubg.match_engine.all_participants_eliminated_draw"));
                else {
                    List<String> winners = players.values().stream().filter(player -> player.alive)
                            .map(player -> player.player.name()).toList();
                    finish(Texts.tr("message.tacz_pubg.match_engine.winner", String.join("、", winners.stream().limit(4).toList()), (winners.size() > 4 ? Texts.tr("message.tacz_pubg.match_engine.others_survivors_team", winners.size()) : "")));
                }
            } else if (settings.maxMatchSeconds() > 0
                    && battleTicks() >= settings.maxMatchSeconds() * 20L) finish(Texts.tr("message.tacz_pubg.match_engine.match_time_limit_reached_draw"));
        }
        return finished || !Objects.equals(beforeStage, stage) || beforePhase != phase;
    }

    public void pause() {
        if (finished) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_engine.match_has_ended"));
        if (paused) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_engine.match_paused"));
        paused = true;
    }
    public void resume() {
        if (finished) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_engine.match_has_ended"));
        if (!paused) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_engine.match_not_paused"));
        paused = false;
    }
    public void stop() {
        if (finished) return;
        finished = true; paused = false; stage = "stopped"; result = Texts.tr("message.tacz_pubg.match_engine.administrator_ended_match"); secondsRemaining = 0; damage = 0;
    }
    private void finish(String result) {
        finished = true; paused = false; stage = "finished"; this.result = boundedResult(result); secondsRemaining = 0; damage = 0;
    }

    public boolean sameTeam(UUID first, UUID second) {
        PlayerState a = players.get(first), b = players.get(second);
        return a != null && b != null && a.alive && b.alive && a.player.teamId().equals(b.player.teamId());
    }
    public boolean blocksFriendlyFire(UUID attacker, UUID victim) {
        return !finished && !settings.friendlyFire() && !attacker.equals(victim) && sameTeam(attacker, victim);
    }
    /** Any player-involved combat crossing this frozen active roster is isolated from the match. */
    public boolean blocksPlayerDamage(UUID attacker, UUID victim) {
        return blocksPlayerDamage(attacker, victim, false, false, false);
    }
    /** Extra permissions never make an eliminated victim attackable or bypass a paused match. */
    public boolean blocksPlayerDamage(UUID attacker, UUID victim, boolean outsideTargetPrivilege,
            boolean bypassFriendlyFire, boolean bypassPreparationProtection) {
        if (finished || !contains(attacker) && !contains(victim)) return false;
        if (paused || !alive(victim)) return true;
        boolean targetAllowed = alive(attacker) || outsideTargetPrivilege;
        if (!targetAllowed || "preparing".equals(stage) && !bypassPreparationProtection) return true;
        PlayerState a = players.get(attacker), b = players.get(victim);
        // An eliminated OP still has the same frozen team; an outside-attack privilege must not silently enable friendly fire.
        return !bypassFriendlyFire && !settings.friendlyFire() && !attacker.equals(victim)
                && a != null && b != null && a.player.teamId().equals(b.player.teamId());
    }
    public double damageAt(UUID player, ResourceLocation dimension, double x, double z, boolean eligible) {
        if (!eligible || finished || paused || !alive(player) || !settings.blueEnabled()
                || !hasBlueBoundary() || !session.map().dimension().equals(dimension)
                || !session.map().contains(x, z) || current.contains(x, z)) return 0;
        var flow = settings.flowRules();
        return damage * exposureMultiplier(players.get(player).outsideTicks, flow.exposureGraceSeconds(),
                flow.exposureRampSeconds(), flow.exposureMaxMultiplier());
    }
    public static double exposureMultiplier(long ticks, int graceSeconds, int rampSeconds, double maximum) {
        long over = ticks - graceSeconds * 20L;
        if (over <= 0 || maximum <= 1) return 1;
        double progress = rampSeconds == 0 ? 1 : Math.clamp(over / (rampSeconds * 20D), 0, 1);
        return 1 + (maximum - 1) * progress;
    }
    private boolean hasBlueBoundary() {
        return settings.blueEnabled() && !"preparing".equals(stage) && !"no_circle".equals(stage)
                && !(phase == 1 && "waiting".equals(stage));
    }
    public List<MapZoneModel.BombingZone> bombingZones() {
        return finished || "preparing".equals(stage) ? List.of() : bombingFrame().zones();
    }
    public boolean bombingActive() { return !finished && !paused && !"preparing".equals(stage) && bombingFrame().striking(); }
    private com.example.examplemod.zones.bombing.BombingTimeline.Frame bombingFrame() {
        int eventPhase = "final".equals(stage) ? settings.flowRules().bombingLastPhase() + 1 : phase;
        if (!settings.blueEnabled()) {
            long elapsed = combatTicks() - settings.flowRules().firstCircleDelaySeconds() * 20L;
            eventPhase = 0;
            if (elapsed >= 0) {
                eventPhase = settings.flowRules().bombingLastPhase() + 1;
                for (int i = 0; i < settings.phases().size(); i++) {
                    var p = settings.phases().get(i);
                    long length = (p.waitSeconds() + (long) p.shrinkSeconds()) * 20;
                    if (elapsed < length) { eventPhase = i + 1; break; }
                    elapsed -= length;
                }
            }
        }
        return bombing.frame(combatTicks(), eventPhase, eventPhase > 0 ? current : null);
    }
    public double flightProgress() {
        if (finished || "preparing".equals(stage) || flightPath == null || combatTicks() >= flightDurationTicks())
            return -1;
        double progress = flightRouteProgress();
        return progress < 0 ? -1 : progress;
    }
    /** Negative values place the aircraft before the authored start during jump-lock lead-in. */
    public double flightRouteProgress() {
        if (flightPath == null) return -1;
        double length = Math.hypot(flightPath.endX() - flightPath.startX(), flightPath.endZ() - flightPath.startZ());
        return Math.min(1, (combatTicks() - settings.flightRules().jumpDelaySeconds() * 20L)
                * settings.flightRules().speedBlocksPerSecond() / 20 / length);
    }
    public MapZoneModel.FlightPath flightPath() { return flightPath; }
    public long flightDurationTicks() {
        return flightPath == null ? 0 : settings.flightRules().jumpDelaySeconds() * 20L + Math.max(1, (long) Math.ceil(Math.hypot(
                flightPath.endX() - flightPath.startX(), flightPath.endZ() - flightPath.startZ())
                / settings.flightRules().speedBlocksPerSecond() * 20));
    }
    private int stageTotalSeconds() {
        if ("preparing".equals(stage)) return settings.preparationSeconds();
        if ("no_circle".equals(stage)) return settings.flowRules().firstCircleDelaySeconds();
        if (phase > 0 && phase <= settings.phases().size()) {
            var p = settings.phases().get(phase - 1);
            if ("waiting".equals(stage)) return p.waitSeconds();
            if ("shrinking".equals(stage)) return p.shrinkSeconds();
        }
        return secondsRemaining;
    }
    public MapDefinition projectedMap() {
        MapDefinition map = session.map();
        return new MapDefinition(map.id(), map.name(), map.dimension(), map.minX(), map.minZ(), map.maxX(), map.maxZ(),
                hasBlueBoundary() ? current : null, finished || paused || !hasBlueBoundary() ? 0 : damage,
                bombingZones(), flightPath, map.visualStyle(), map.stagingSpawn());
    }
    public MatchStatus status() {
        return new MatchStatus(session.map().id(), session.sessionId(), paused && !finished ? "paused" : stage,
                phase, settings.phases().size(), secondsRemaining, alivePlayers(), aliveTeams(), players.size(), result,
                hasBlueBoundary() ? current : null, phase > 0 && settings.blueEnabled() ? target : null,
                stageTotalSeconds(), flightProgress(), finished || "preparing".equals(stage) ? "none" : bombingFrame().stage(),
                finished || "preparing".equals(stage) ? 0 : bombingFrame().secondsRemaining(),
                phaseStart, finished ? 0 : stageElapsedTicks, finished ? 0 : stageDurationTicks, ticks, stage);
    }
    private int alivePlayers() { return (int) players.values().stream().filter(player -> player.alive).count(); }
    private int aliveTeams() { return (int) players.values().stream().filter(player -> player.alive).map(player -> player.player.teamId()).distinct().count(); }
    private long battleTicks() { return Math.max(0, ticks - settings.preparationSeconds() * 20L); }
    public long combatTicks() { return battleTicks(); }

    private void updatePhase() {
        phaseStart = initialBoundary;
        if (ticks < settings.preparationSeconds() * 20L) {
            stage = "preparing"; phase = 0; damage = 0;
            clock(settings.preparationSeconds() * 20L - ticks, settings.preparationSeconds() * 20L); return;
        }
        long elapsed = battleTicks(), cursor = 0;
        if (!settings.blueEnabled()) {
            stage = "final"; phase = 0; current = session.map().safeZone(); target = current; damage = 0;
            phaseStart = current;
            clock(Math.max(0, settings.maxMatchSeconds() * 20L - elapsed), settings.maxMatchSeconds() * 20L); return;
        }
        long delay = settings.flowRules().firstCircleDelaySeconds() * 20L;
        if (elapsed < delay) {
            stage = "no_circle"; phase = 0; current = initialBoundary; target = current; damage = 0;
            clock(delay - elapsed, delay); return;
        }
        elapsed -= delay;
        Circle from = initialBoundary;
        for (int i = 0; i < settings.phases().size(); i++) {
            Phase configured = settings.phases().get(i);
            Circle to = targets.get(i);
            long waitEnd = cursor + configured.waitSeconds() * 20L;
            long shrinkEnd = waitEnd + configured.shrinkSeconds() * 20L;
            if (elapsed < waitEnd) {
                stage = "waiting"; phase = i + 1; current = from; target = to; damage = i == 0 ? 0 : configured.damagePerSecond();
                phaseStart = from; clock(waitEnd - elapsed, configured.waitSeconds() * 20L); return;
            }
            if (elapsed < shrinkEnd) {
                double progress = (elapsed - waitEnd) / (configured.shrinkSeconds() * 20D);
                stage = "shrinking"; phase = i + 1; current = interpolate(from, to, progress); target = to;
                damage = configured.damagePerSecond(); phaseStart = from;
                clock(shrinkEnd - elapsed, configured.shrinkSeconds() * 20L); return;
            }
            from = to; cursor = shrinkEnd;
        }
        stage = "final"; phase = settings.phases().size(); current = from; target = from;
        damage = settings.phases().isEmpty() ? session.map().blueDamagePerSecond()
                : settings.phases().getLast().damagePerSecond();
        phaseStart = from;
        clock(settings.maxMatchSeconds() > 0 ? Math.max(0, settings.maxMatchSeconds() * 20L - battleTicks()) : 0,
                settings.maxMatchSeconds() * 20L);
    }

    private void clock(long remaining, long duration) {
        stageDurationTicks = Math.max(0, duration);
        stageElapsedTicks = Math.clamp(duration - remaining, 0, stageDurationTicks);
        secondsRemaining = seconds(remaining);
    }

    public static Circle interpolate(Circle from, Circle to, double progress) {
        double value = Math.clamp(progress, 0, 1);
        return new Circle(from.centerX() + (to.centerX() - from.centerX()) * value,
                from.centerZ() + (to.centerZ() - from.centerZ()) * value,
                Math.max(.5, from.radius() + (to.radius() - from.radius()) * value));
    }
    private static List<Circle> generateTargets(MapDefinition map, Settings settings, long seed) {
        Random random = new Random(seed);
        List<Circle> result = new ArrayList<>();
        Circle from = map.safeZone();
        for (Phase phase : settings.phases()) {
            double radius = Math.max(.5, from.radius() * phase.radiusRatio());
            if (radius > from.radius()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_engine.target_circle_not_larger_than_previous_circle"));
            double x = from.centerX(), z = from.centerZ();
            if (phase.randomCenter()) {
                double available = Math.max(0, from.radius() - radius);
                for (int attempt = 0; attempt < 64; attempt++) {
                    double power = result.size() >= 3 ? .5 + settings.flowRules().lateCenterBias() * 2 : .5;
                    double distance = Math.pow(random.nextDouble(), power) * available;
                    double angle = random.nextDouble() * Math.PI * 2;
                    double candidateX = from.centerX() + Math.cos(angle) * distance;
                    double candidateZ = from.centerZ() + Math.sin(angle) * distance;
                    if (map.contains(candidateX, candidateZ)
                            && Math.hypot(candidateX - from.centerX(), candidateZ - from.centerZ()) + radius <= from.radius() + 1e-9) {
                        x = candidateX; z = candidateZ; break;
                    }
                }
            }
            from = new Circle(x, z, radius); result.add(from);
        }
        return List.copyOf(result);
    }
    private static int seconds(long ticks) { return (int) Math.min(Integer.MAX_VALUE, (ticks + 19) / 20); }
    private static String boundedResult(String result) {
        return Texts.limit(result, Texts.MAX_MESSAGE_LENGTH);
    }
}
