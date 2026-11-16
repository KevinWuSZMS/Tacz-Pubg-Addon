package com.example.examplemod.lean;

import com.example.examplemod.movement.MovementService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Shared legal eye pose. No velocity, entity position, or physical collision box is changed. */
public final class LeanService {
    private LeanService() { }
    private static LeanStateAccess data(Player player) { return (LeanStateAccess) player; }
    private static LeanState state(Player player) { return data(player).taczPubg$leanState(); }

    public static void register() {
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(EventPriority.LOWEST, (PlayerTickEvent.Post event) -> {
            Player player = event.getEntity();
            if (!(player instanceof ServerPlayer)) return;
            if (!eligible(player)) clear(player);
            else publish(player);
            LeanHitboxes.record(player);
        });
        bus.addListener((LivingDeathEvent event) -> {
            if (event.getEntity() instanceof Player player) forget(player);
        });
        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> forget(event.getEntity()));
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> forget(event.getEntity()));
        bus.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> forget(event.getEntity()));
        bus.addListener((PlayerEvent.Clone event) -> { forget(event.getOriginal()); forget(event.getEntity()); });
        bus.addListener((ServerStoppedEvent event) -> { LeanHitboxes.clearAll(); LeanShots.clearAll(); });
    }

    public static boolean eligible(Player player) {
        return MovementService.eligible(player) && !player.isSprinting();
    }

    public static void input(ServerPlayer player, int entityId, int sequence, int direction) {
        LeanState state = state(player);
        if (entityId != player.getId() || sequence < 0 || sequence <= state.sequence || Math.abs(direction) > 1) return;
        long now = System.nanoTime();
        double current = eligible(player) ? LeanGeometry.clamp(player, state.motion.sample(now)) : 0;
        int accepted = eligible(player) ? direction : 0;
        state.motion.correct(current, accepted, now);
        state.sequence = sequence;
        state.inputNanos = now;
        state.inputs.addLast(new LeanState.InputFrame(sequence, now, current, accepted));
        while (state.inputs.size() > 64) state.inputs.removeFirst();
        publish(player);
    }

    public static void predict(Player player, int sequence, int direction) {
        LeanState state = state(player);
        state.predicted = true;
        clearRender(state);
        state.sequence = sequence;
        long now = System.nanoTime();
        state.inputNanos = now;
        if (!eligible(player)) state.motion.reset(now);
        else state.motion.correct(LeanGeometry.clamp(player, state.motion.sample(now)), direction, now);
    }

    /** One sampling time and body pose for pick, camera and hand/model rendering during a world frame. */
    public static void beginRender(Player player, float partialTick) {
        LeanState state = state(player);
        state.renderNanos = System.nanoTime();
        state.renderFraction = Double.NaN;
        state.renderPose = LeanGeometry.renderSnapshot(player, partialTick);
        state.renderFeet = player.getPosition(partialTick);
    }
    public static void endRender(Player player) { clearRender(state(player)); }

    public static LeanGeometry.Pose pose(Player player) {
        LeanState state = state(player);
        return state.renderNanos != 0 && state.renderPose != null ? state.renderPose : LeanGeometry.snapshot(player);
    }

    public static LeanGeometry.Pose renderPose(Player player, float partialTick) {
        LeanState state = state(player);
        return state.renderNanos != 0 && state.renderPose != null ? state.renderPose : LeanGeometry.renderSnapshot(player, partialTick);
    }

    public static Vec3 renderFeet(Player player, float partialTick) {
        LeanState state = state(player);
        return state.renderNanos != 0 && state.renderFeet != null ? state.renderFeet : player.getPosition(partialTick);
    }

    private static void clearRender(LeanState state) {
        state.renderNanos = 0;
        state.renderFraction = Double.NaN;
        state.renderPose = null;
        state.renderFeet = null;
    }

    public static double fraction(Player player, float partialTick) {
        boolean client = player.level().isClientSide;
        return fraction(player, client ? renderPose(player, partialTick) : pose(player),
                client ? renderFeet(player, partialTick) : player.position());
    }

    private static double fraction(Player player, LeanGeometry.Pose pose, Vec3 feet) {
        if (!eligible(player)) return 0;
        LeanState state = state(player);
        if (state.renderNanos != 0 && Double.isFinite(state.renderFraction)) return state.renderFraction;
        long now = state.renderNanos == 0 ? System.nanoTime() : state.renderNanos;
        if (player.level().isClientSide) reconcile(player, state, now);
        double value = state.motion.sample(now);
        // A server limit describes its earlier feet/body pose, not this local rendered pose.
        // Local prediction validates the displayed geometry here; server shot validation stays authoritative.
        boolean localPrediction = player.level().isClientSide && state.predicted;
        if (!localPrediction && (!state.predicted || data(player).taczPubg$leanSequence() == state.sequence)
                && Integer.signum(state.motion.target()) == data(player).taczPubg$leanTarget()) {
            double limit = data(player).taczPubg$leanLimit();
            value = LeanMotion.limited(value, state.motion.target(), limit);
        }
        double result = player.level().isClientSide
                ? state.predicted ? LeanGeometry.clamp(player, feet, pose, value) : value
                : LeanGeometry.clamp(player, pose, value);
        if (state.renderNanos != 0) state.renderFraction = result;
        return result;
    }

    public static Vec3 offset(Player player, float partialTick) {
        boolean client = player.level().isClientSide;
        LeanGeometry.Pose pose = client ? renderPose(player, partialTick) : pose(player);
        return LeanGeometry.offset(pose, fraction(player, pose,
                client ? renderFeet(player, partialTick) : player.position()));
    }

    public static int inputSequence(Player player) { return state(player).sequence; }
    public static double inputAgeSeconds(Player player) {
        return Math.max(0, System.nanoTime() - state(player).inputNanos) / 1_000_000_000.0;
    }
    public static Vec3 offsetForFraction(Player player, float yaw, double fraction) {
        if (!eligible(player)) return Vec3.ZERO;
        LeanGeometry.Pose pose = LeanGeometry.snapshot(player, yaw);
        return LeanGeometry.offset(pose, LeanGeometry.clamp(player, pose, fraction));
    }
    public static boolean knownSequence(Player player, int sequence) {
        return state(player).inputs.stream().anyMatch(frame -> frame.sequence() == sequence);
    }

    /** Rebuild a bounded historical input transition; arbitrary client offsets are never accepted. */
    public static double sampleInput(Player player, int sequence, double ageSeconds) {
        if (!eligible(player) || !Double.isFinite(ageSeconds) || ageSeconds < 0) return Double.NaN;
        long now = System.nanoTime();
        LeanState.InputFrame found = null;
        for (var frame : state(player).inputs) {
            if (found != null && frame.nanos() < found.nanos() + ageSeconds * 1_000_000_000L - 250_000_000L)
                return Double.NaN;
            if (frame.sequence() == sequence) found = frame;
        }
        if (found != null) {
            var frame = found;
            double elapsed = Math.max(0, now - frame.nanos()) / 1_000_000_000.0;
            if (Math.abs(ageSeconds - elapsed) > 0.25) return Double.NaN;
            // A held input can be old; only its short transition needs time reconstruction.
            long sample = frame.nanos() + (long) (Math.min(ageSeconds, 1) * 1_000_000_000L);
            LeanMotion motion = new LeanMotion();
            motion.correct(frame.fraction(), frame.target(), frame.nanos());
            return motion.sample(sample);
        }
        return Double.NaN;
    }

    private static void reconcile(Player player, LeanState state, long now) {
        LeanStateAccess data = data(player);
        int sequence = data.taczPubg$leanSequence(), target = data.taczPubg$leanTarget();
        float value = data.taczPubg$leanFraction();
        if (sequence == state.wireSequence && target == state.wireTarget && value == state.wireFraction) return;
        state.wireSequence = sequence; state.wireTarget = target; state.wireFraction = value;
        if (!state.predicted) {
            // Remote players interpolate updates; local prediction keeps its original input timing.
            state.motion.correct(value, target, now);
            state.sequence = sequence;
        } else if (sequence >= state.sequence && target != state.motion.target()) {
            state.motion.correct(value, target, now);
        }
    }

    private static void publish(Player player) {
        LeanState state = state(player);
        int target = state.motion.target();
        LeanGeometry.Pose pose = LeanGeometry.snapshot(player);
        float fraction = (float) LeanGeometry.clamp(player, pose, state.motion.sample(System.nanoTime()));
        float limit = (float) Math.abs(LeanGeometry.clamp(player, pose, target));
        data(player).taczPubg$leanMetadata(state.sequence, target, fraction, limit);
    }

    public static void clear(Player player) {
        LeanState state = state(player);
        state.motion.reset(System.nanoTime()); clearRender(state); state.inputs.clear();
        if (!player.level().isClientSide) data(player).taczPubg$leanMetadata(state.sequence, 0, 0, 1);
    }

    private static void forget(Player player) {
        clear(player);
        if (player.level().isClientSide) return;
        state(player).sequence = -1;
        if (!player.level().isClientSide) data(player).taczPubg$leanMetadata(-1, 0, 0, 1);
        LeanHitboxes.clear(player);
        LeanShots.clear(player);
    }
}
