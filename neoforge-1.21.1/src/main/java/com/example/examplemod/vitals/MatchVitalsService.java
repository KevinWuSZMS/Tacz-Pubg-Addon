package com.example.examplemod.vitals;

import com.example.examplemod.zones.match.MatchEngine;
import com.example.examplemod.zones.match.MatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Match-local hunger, with authoritative boost in matches and ordinary worlds. */
public final class MatchVitalsService {
    private static final String FOOD_RESTORE = "tacz_pubg_match_food_restore";
    private static final String ORDINARY_BOOST = "tacz_pubg_ordinary_boost";
    private static final ResourceLocation SPEED = ResourceLocation.fromNamespaceAndPath("tacz_pubg", "boost_speed");
    private static final Map<MinecraftServer, Map<UUID, State>> STATES = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, State>> ORDINARY_STATES = new WeakHashMap<>();
    private static final Map<MinecraftServer, Long> ORDINARY_CLOCKS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, UUID>> HUD_SESSIONS = new WeakHashMap<>();
    private static final class State {
        final UUID session;
        double boost;
        long clock;
        State(UUID session, long clock) { this.session = session; this.clock = clock; }
    }
    private MatchVitalsService() {}
    public static void register(IEventBus bus) {
        // Registered after MatchService, so endings and roster elimination are already authoritative.
        bus.addListener(MatchVitalsService::tick);
        bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && activeGame(p) == null) restore(p);
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                restore(p);
                var viewers = HUD_SESSIONS.get(p.server);
                if (viewers != null) viewers.remove(p.getUUID());
                var ordinary = ORDINARY_STATES.get(p.server);
                if (ordinary != null) {
                    State saved = ordinary.remove(p.getUUID());
                    if (saved != null) saveOrdinary(p, saved);
                }
            }
        });
        bus.addListener((PlayerEvent.Clone e) -> {
            var original = e.getOriginal().getPersistentData();
            if (original.contains(FOOD_RESTORE, Tag.TAG_COMPOUND))
                e.getEntity().getPersistentData().put(FOOD_RESTORE, original.getCompound(FOOD_RESTORE).copy());
            if (e.getEntity() instanceof ServerPlayer p) restore(p);
        });
        bus.addListener((LivingDeathEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                p.getPersistentData().remove(ORDINARY_BOOST);
                var ordinary = ORDINARY_STATES.get(p.server);
                if (ordinary != null) ordinary.remove(p.getUUID());
                var states = STATES.get(p.server);
                if (states != null) states.remove(p.getUUID());
                updateSpeed(p, 0);
            }
        });
        bus.addListener((ServerStoppedEvent e) -> {
            STATES.remove(e.getServer()); HUD_SESSIONS.remove(e.getServer());
            ORDINARY_STATES.remove(e.getServer()); ORDINARY_CLOCKS.remove(e.getServer());
        });
    }
    static MatchEngine activeGame(ServerPlayer p) {
        MatchEngine game = MatchService.participantGame(p.server, p.getUUID());
        return game != null && activePolicy(game.alive(p.getUUID()), game.finished(),
                game.elapsedTicks(), game.settings().preparationSeconds()) && p.isAlive() ? game : null;
    }
    public static boolean activePolicy(boolean alive, boolean finished, long elapsedTicks, int preparationSeconds) {
        return alive && !finished && elapsedTicks >= preparationSeconds * 20L;
    }
    /** Used only by hunger and peaceful-regeneration mixins, never by potion/medical heal calls. */
    public static boolean freezeHunger(Player player) {
        if (!(player instanceof ServerPlayer p)) return false;
        MatchEngine game = activeGame(p);
        if (game == null) return false;
        state(p, game);
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(0);
        p.getFoodData().setExhaustion(0);
        return true;
    }
    private static State state(ServerPlayer p, MatchEngine game) {
        Map<UUID, State> states = STATES.computeIfAbsent(p.server, ignored -> new HashMap<>());
        State state = states.get(p.getUUID());
        if (state == null || !state.session.equals(game.session().sessionId())) {
            restore(p);
            state = new State(game.session().sessionId(), game.combatTicks());
            states.put(p.getUUID(), state);
        }
        if (!p.getPersistentData().contains(FOOD_RESTORE, Tag.TAG_COMPOUND)) {
            CompoundTag marker = new CompoundTag(), food = new CompoundTag();
            p.getFoodData().addAdditionalSaveData(food);
            marker.putUUID("session", state.session); marker.put("food", food);
            p.getPersistentData().put(FOOD_RESTORE, marker);
        }
        return state;
    }
    private static void restore(ServerPlayer p) {
        restoreFood(p);
        updateSpeed(p, 0);
    }
    private static void restoreFood(ServerPlayer p) {
        var data = p.getPersistentData();
        if (data.contains(FOOD_RESTORE, Tag.TAG_COMPOUND)) {
            var marker = data.getCompound(FOOD_RESTORE);
            if (marker.contains("food", Tag.TAG_COMPOUND)) p.getFoodData().readAdditionalSaveData(marker.getCompound("food"));
            data.remove(FOOD_RESTORE);
        }
    }
    private static State ordinaryState(ServerPlayer p) {
        var states = ORDINARY_STATES.computeIfAbsent(p.server, ignored -> new HashMap<>());
        return states.computeIfAbsent(p.getUUID(), ignored -> {
            State state = new State(MatchVitalsPayloads.ORDINARY_SESSION, ordinaryClock(p.server));
            state.boost = PubgBoostModel.add(0, p.getPersistentData().getDouble(ORDINARY_BOOST));
            return state;
        });
    }
    private static long ordinaryClock(MinecraftServer server) { return ORDINARY_CLOCKS.getOrDefault(server, 0L); }
    private static void saveOrdinary(ServerPlayer p, State state) {
        if (state.boost > 0) p.getPersistentData().putDouble(ORDINARY_BOOST, state.boost);
        else p.getPersistentData().remove(ORDINARY_BOOST);
    }
    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.tickRateManager().runsNormally()) ORDINARY_CLOCKS.merge(server, 1L, Long::sum);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            MatchEngine game = activeGame(p);
            Map<UUID, State> states = STATES.get(server);
            State old = states == null ? null : states.get(p.getUUID());
            var participant = MatchService.participantGame(server, p.getUUID());
            boolean inMatch = participant != null && !participant.finished();
            State ordinary = ordinaryState(p);
            if (inMatch) ordinary.clock = ordinaryClock(server); // Preserve world boosts across a match without advancing them.
            if (game == null) {
                restoreFood(p);
                if (old != null) {
                    states.remove(p.getUUID());
                }
                if (!inMatch && p.isAlive() && !p.isSpectator()) {
                    advance(p, ordinary, ordinaryClock(server), !server.tickRateManager().runsNormally());
                    saveOrdinary(p, ordinary);
                } else {
                    ordinary.clock = ordinaryClock(server);
                    updateSpeed(p, 0);
                }
                continue;
            }
            State state = state(p, game);
            freezeHunger(p);
            boolean paused = game.paused() || !server.tickRateManager().runsNormally();
            advance(p, state, game.combatTicks(), paused);
        }
        syncHud(server);
        Map<UUID, State> states = STATES.get(server);
        if (states == null) return;
        // Disconnection never grants stored healing. Energy keeps following the same match clock.
        states.entrySet().removeIf(entry -> {
            if (server.getPlayerList().getPlayer(entry.getKey()) != null) return false;
            MatchEngine game = MatchService.participantGame(server, entry.getKey());
            if (game == null || !game.alive(entry.getKey()) || !game.session().sessionId().equals(entry.getValue().session)) return true;
            State state = entry.getValue();
            state.boost = PubgBoostModel.advance(state.boost, Math.max(0, game.combatTicks() - state.clock)).boost();
            state.clock = game.combatTicks();
            return false;
        });
    }
    private static void advance(ServerPlayer p, State state, long clock, boolean paused) {
        long delta = paused ? 0 : Math.max(0, clock - state.clock);
        var step = PubgBoostModel.advance(state.boost, delta);
        state.boost = step.boost();
        // Settle every active tick: healing never remains banked until a later injury.
        if (step.healedFraction() > 0 && p.getHealth() < p.getMaxHealth())
            p.heal((float) (step.healedFraction() * p.getMaxHealth()));
        state.clock = clock;
        updateSpeed(p, !paused && p.isSprinting() ? PubgBoostModel.speedBonus(state.boost) : 0);
    }
    private static void updateSpeed(ServerPlayer p, double amount) {
        var speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        var existing = speed.getModifier(SPEED);
        if (existing != null && existing.amount() == amount) return;
        speed.removeModifier(SPEED);
        if (amount > 0) speed.addTransientModifier(new AttributeModifier(SPEED, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }
    private static void publish(ServerPlayer p, MatchEngine game, State state) {
        PacketDistributor.sendToPlayer(p, new MatchVitalsPayloads.Snapshot(state.session, true, game != null && game.paused(),
                (float) state.boost, p.getHealth(), p.getMaxHealth(), p.getAbsorptionAmount()));
        HUD_SESSIONS.computeIfAbsent(p.server, ignored -> new HashMap<>()).put(p.getUUID(), state.session);
    }
    /** The whole frozen roster switches GUI immediately, including preparation and eliminated observers. */
    private static void syncHud(MinecraftServer server) {
        var viewers = HUD_SESSIONS.computeIfAbsent(server, ignored -> new HashMap<>());
        var states = STATES.get(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            MatchEngine game = MatchService.participantGame(server, p.getUUID());
            if (game == null || game.finished()) {
                State ordinary = ordinaryState(p);
                if (p.isAlive() && !p.isSpectator() && ordinary.boost > 0) {
                    if (!ordinary.session.equals(viewers.get(p.getUUID())) || server.getTickCount() % 10 == 0)
                        publish(p, null, ordinary);
                    continue;
                }
                if (viewers.remove(p.getUUID()) != null)
                    PacketDistributor.sendToPlayer(p, MatchVitalsPayloads.Snapshot.INACTIVE);
                continue;
            }
            UUID session = game.session().sessionId();
            if (!session.equals(viewers.get(p.getUUID())) || server.getTickCount() % 10 == 0) {
                ServerPlayer subject = p;
                if (!game.alive(p.getUUID()) && p.getCamera() instanceof ServerPlayer target
                        && game.alive(target.getUUID())) subject = target;
                State state = states == null ? null : states.get(subject.getUUID());
                float boost = state != null && state.session.equals(session) ? (float) state.boost : 0;
                PacketDistributor.sendToPlayer(p, new MatchVitalsPayloads.Snapshot(session, true, game.paused(), boost,
                        game.alive(subject.getUUID()) ? subject.getHealth() : 0, subject.getMaxHealth(),
                        game.alive(subject.getUUID()) ? subject.getAbsorptionAmount() : 0));
                viewers.put(p.getUUID(), session);
            }
        }
    }
    /** Negative means unusable (dead, eliminated, preparing or paused); ordinary worlds are allowed. */
    static double currentBoost(ServerPlayer p) {
        State state = medicineState(p);
        return state == null ? -1 : state.boost;
    }
    static boolean addBoost(ServerPlayer p, double amount) {
        MatchEngine game = activeGame(p);
        State state = medicineState(p);
        if (state == null || !Double.isFinite(amount) || amount <= 0) return false;
        if (state.boost >= PubgBoostModel.MAX_BOOST) return false;
        state.boost = PubgBoostModel.add(state.boost, amount);
        if (game == null) saveOrdinary(p, state);
        publish(p, game, state);
        return true;
    }
    private static State medicineState(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || !p.server.tickRateManager().runsNormally()) return null;
        MatchEngine game = activeGame(p);
        if (game != null) return game.paused() ? null : state(p, game);
        var participant = MatchService.participantGame(p.server, p.getUUID());
        return participant != null && !participant.finished() ? null : ordinaryState(p);
    }
}
