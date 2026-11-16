package com.example.examplemod.lean;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** One immutable launch pose per native round, shared by all pellets in that round. */
public final class LeanShots {
    private static final Map<ServerPlayer, LeanShotLedger> LEDGERS = new WeakHashMap<>();
    private static final ThreadLocal<Request> REQUEST = new ThreadLocal<>();
    private static final ThreadLocal<Frame> ROUND = new ThreadLocal<>();
    private LeanShots() {}

    public static void clear(net.minecraft.world.entity.player.Player player) {
        if (player instanceof ServerPlayer) LEDGERS.remove(player);
    }

    public static void clearAll() {
        LEDGERS.clear();
        REQUEST.remove();
        ROUND.remove();
    }

    public record ShotPose(Vec3 baseOrigin, Vec3 origin, float pitch, float yaw) {}
    private record Frame(LivingEntity shooter, ShotPose pose) {}
    public static final class Request {
        private final ServerPlayer player;
        private final long timestamp;
        private int nextRound;
        private Request(ServerPlayer player, long timestamp) { this.player = player; this.timestamp = timestamp; }
    }

    public static void accept(ServerPlayer player, LeanShotPayloads.Pose packet) {
        if (!player.isAlive() || !LeanService.knownSequence(player, packet.sequence())) return;
        LeanShotLedger.Sample sample = new LeanShotLedger.Sample(packet.sequence(), packet.inputAgeSeconds(), packet.pitch(), packet.yaw());
        if (!sample.valid() || !Double.isFinite(LeanService.sampleInput(player, sample.sequence(), sample.inputAgeSeconds()))) return;
        LEDGERS.computeIfAbsent(player, ignored -> new LeanShotLedger())
                .offer(packet.timestamp(), packet.round(), sample, System.nanoTime());
    }

    public static void withRequest(LivingEntity shooter, long timestamp, Runnable action) {
        Request previous = REQUEST.get();
        if (shooter instanceof ServerPlayer player) REQUEST.set(new Request(player, timestamp));
        else REQUEST.remove();
        try { action.run(); }
        finally { if (previous == null) REQUEST.remove(); else REQUEST.set(previous); }
    }

    public static Request currentRequest(LivingEntity shooter) {
        Request request = REQUEST.get();
        return request != null && request.player == shooter ? request : null;
    }

    public static boolean withRound(LivingEntity shooter, Request request, BooleanSupplier action) {
        Frame previous = ROUND.get();
        ROUND.set(new Frame(shooter, capture(shooter, request)));
        try { return action.getAsBoolean(); }
        finally { if (previous == null) ROUND.remove(); else ROUND.set(previous); }
    }

    public static ShotPose current(LivingEntity shooter) {
        Frame frame = ROUND.get();
        return frame != null && frame.shooter == shooter ? frame.pose : null;
    }

    private static ShotPose capture(LivingEntity shooter, Request request) {
        if (!(shooter instanceof ServerPlayer player)) return null;
        float pitch = player.getXRot();
        float yaw = player.getYRot();
        double fraction = LeanService.fraction(player, 1);
        if (request != null && request.player == player) {
            LeanShotLedger.Sample sample = LEDGERS.computeIfAbsent(player, ignored -> new LeanShotLedger())
                    .claim(request.timestamp, request.nextRound++, System.nanoTime());
            if (sample != null) {
                double historical = LeanService.sampleInput(player, sample.sequence(), sample.inputAgeSeconds());
                if (Double.isFinite(historical)) {
                    fraction = historical;
                    pitch = sample.pitch();
                    yaw = sample.yaw();
                }
            }
        }
        Vec3 offset = LeanService.eligible(player) ? LeanService.offsetForFraction(player, yaw, fraction) : Vec3.ZERO;
        // The collision-clamped lean offset belongs to the current authoritative eye position.
        // At rest retain TaCZ's native half-tick interpolation exactly.
        Vec3 baseOrigin = offset.lengthSqr() > 1.0E-12 ? player.getEyePosition()
                : new Vec3(player.xOld + (player.getX() - player.xOld) / 2.0,
                        player.yOld + (player.getY() - player.yOld) / 2.0 + player.getEyeHeight(),
                        player.zOld + (player.getZ() - player.zOld) / 2.0);
        return new ShotPose(baseOrigin, baseOrigin.add(offset), pitch, yaw);
    }
}
