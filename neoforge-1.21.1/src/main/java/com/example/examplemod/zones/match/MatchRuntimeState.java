package com.example.examplemod.zones.match;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Pure match lifecycle and spatial damage selection; no worlds or entities are created. */
final class MatchRuntimeState {
    static final int TICKS_PER_DAMAGE_PULSE = 20;
    private final TreeMap<Long, MatchSession> running = new TreeMap<>();
    private final TreeMap<Long, MatchEngine> engines = new TreeMap<>();
    private final TreeMap<Long, MatchEngine> ended = new TreeMap<>();
    private long revision;
    private long tick;

    long revision() { return revision; }
    long tick() { return tick; }
    MatchSession session(long mapId) { return running.get(mapId); }
    List<MatchSession> sessions() { return List.copyOf(running.values()); }
    MatchEngine engine(long mapId) { return engines.get(mapId); }
    List<MatchEngine> engines() { return List.copyOf(engines.values()); }
    void forgetDeletedMap(long mapId) {
        if (!ended.containsKey(mapId)) return;
        requireRevisionCapacity();
        ended.remove(mapId); revision++;
    }
    MatchStatus status(long mapId) {
        MatchEngine game = engines.get(mapId);
        if (game == null) game = ended.get(mapId);
        return game == null ? null : game.status();
    }
    boolean participantRunning(UUID player) { return engines.values().stream().anyMatch(engine -> engine.contains(player)); }
    boolean hasActiveRosterSession(UUID player, UUID approvedSession) {
        return approvedSession != null && engines.values().stream().anyMatch(engine -> engine.contains(player)
                && engine.session().sessionId().equals(approvedSession));
    }
    List<MapDefinition> activeMaps() {
        return running.values().stream().map(session -> {
            MatchEngine engine = engines.get(session.map().id());
            return engine == null ? session.map() : engine.projectedMap();
        }).toList();
    }
    MapDefinition activeMap(long mapId) {
        MatchSession session = running.get(mapId);
        if (session == null) return null;
        MatchEngine engine = engines.get(mapId);
        return engine == null ? session.map() : engine.projectedMap();
    }
    long elapsedTicks(long mapId) {
        MatchEngine engine = engines.get(mapId);
        if (engine == null) engine = ended.get(mapId);
        MatchSession session = running.get(mapId);
        return engine != null ? engine.elapsedTicks() : session == null ? 0 : Math.max(0, tick - session.startedTick());
    }

    MatchSession start(MapDefinition map, MatchConfigModel.Settings settings, List<MatchEngine.Participant> roster, long seed) {
        requireMap(map);
        for (MatchEngine.Participant player : roster) {
            if (participantRunning(player.id())) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_runtime_state.player_already_another_match", player.name()));
        }
        requireRevisionCapacity();
        MatchSession session = new MatchSession(UUID.randomUUID(), tick, map);
        // Construct and validate the whole roster/profile before committing any runtime state.
        MatchEngine engine = new MatchEngine(session, settings, roster, seed);
        running.put(map.id(), session); engines.put(map.id(), engine); ended.remove(map.id()); revision++;
        return session;
    }

    MatchSession start(MapDefinition map) {
        requireMap(map);
        requireRevisionCapacity();
        MatchSession session = new MatchSession(UUID.randomUUID(), tick, map);
        running.put(map.id(), session);
        ended.remove(map.id());
        revision++;
        return session;
    }

    private void requireMap(MapDefinition map) {
        List<String> errors = MapZoneModel.validate(map);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        if (map.id() <= 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_runtime_state.save_map_before_starting_match"));
        if (map.safeZone() == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_runtime_state.map_has_no_safe_zone_yet", map.id()));
        if (running.containsKey(map.id())) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_runtime_state.match_map_has_already_started_use_endmatch_first", map.id()));
    }

    MatchSession end(long mapId) {
        MatchSession session = running.get(mapId);
        if (session == null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_runtime_state.map_has_no_active_match", mapId));
        requireRevisionCapacity();
        running.remove(mapId);
        MatchEngine engine = engines.remove(mapId);
        if (engine != null) { engine.stop(); ended.put(mapId, engine); }
        revision++;
        return session;
    }

    /** All maps share one pulse so their damage cannot stack at differently phased starts. */
    boolean advanceTick() {
        tick++;
        boolean changed = false;
        List<Long> completed = new ArrayList<>();
        for (Map.Entry<Long, MatchEngine> entry : engines.entrySet()) {
            changed |= entry.getValue().advance();
            if (entry.getValue().finished()) completed.add(entry.getKey());
        }
        for (long mapId : completed) {
            running.remove(mapId); ended.put(mapId, engines.remove(mapId));
        }
        // Bound dynamic map publication to twice per second while stage changes publish immediately.
        if (changed || tick % 10 == 0 && !engines.isEmpty()) changed();
        return tick % TICKS_PER_DAMAGE_PULSE == 0 && !running.isEmpty();
    }

    void changed() { requireRevisionCapacity(); revision++; }
    boolean blocksFriendlyFire(UUID attacker, UUID victim) {
        return engines.values().stream().anyMatch(engine -> engine.blocksFriendlyFire(attacker, victim));
    }
    boolean blocksPlayerDamage(UUID attacker, UUID victim) {
        return engines.values().stream().anyMatch(engine -> engine.blocksPlayerDamage(attacker, victim));
    }
    boolean blocksPlayerDamage(UUID attacker, UUID victim, boolean outsideTargetPrivilege,
            boolean bypassFriendlyFire, boolean bypassPreparationProtection) {
        return engines.values().stream().anyMatch(engine -> engine.blocksPlayerDamage(attacker, victim,
                outsideTargetPrivilege, bypassFriendlyFire, bypassPreparationProtection));
    }
    double damageFor(UUID player, ResourceLocation dimension, double x, double z, boolean eligible) {
        return engines.values().stream().mapToDouble(engine -> engine.damageAt(player, dimension, x, z, eligible)).max().orElse(0);
    }

    /** Returns the strongest active blue-zone damage, not the sum of overlapping maps. */
    double damageAt(ResourceLocation dimension, double x, double z, boolean eligiblePlayer) {
        if (!eligiblePlayer || dimension == null || !Double.isFinite(x) || !Double.isFinite(z)) return 0;
        double damage = 0;
        for (MatchSession session : running.values()) {
            MapDefinition map = session.map();
            if (map.isBlue(dimension, x, z)) damage = Math.max(damage, map.blueDamagePerSecond());
        }
        return damage;
    }

    private void requireRevisionCapacity() {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_runtime_state.match_status_version_exhausted_restart_server"));
    }
}
