package com.example.examplemod.zones.match.flight;

import com.example.examplemod.zones.match.bot.MatchBotService;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Collision-aware descent at twenty ticks per second and one block per metre.
 * These are gameplay tuning targets, not a claim about PUBG's closed-source physics.
 * Vanilla flight abilities and the player's ground/water movement are never modified.
 */
public final class FlightMotion {
    public static final double FREEFALL_LIMIT_METRES_PER_SECOND = FreefallAerodynamics.MAX_DIVE; // 234 km/h
    private static final double TICKS_PER_SECOND = 20.0;
    private static Function<Player, Control> clientControls = player -> null;

    /** Positive forward is W; positive strafe is A, matching vanilla movement input. */
    public record Control(int stage, float forward, float strafe, float yaw, float pitch,
            boolean fast, boolean glide) {}

    private FlightMotion() {}

    public static void setClientControls(Function<Player, Control> controls) {
        clientControls = Objects.requireNonNull(controls);
    }

    /** Return true only when this invocation has replaced vanilla travel. */
    public static boolean travel(Player player) {
        if (!player.isAlive() || player.isSpectator() || player.onGround()
                || player.isInWater() || player.isInLava() || player.isPassenger()
                || !ParachuteItem.isEquipped(player)) return false;
        Control control;
        if (player.level().isClientSide()) {
            // The provider returns null for remote players, menus/contexts without flight input,
            // or players without the server's current descent state.
            control = clientControls.apply(player);
        } else if (player instanceof ServerPlayer serverPlayer) {
            int stage = MatchFlightService.descentStage(serverPlayer);
            if (stage != 2 && stage != 3) return false;
            if (MatchBotService.isCarpetPlayer(serverPlayer))
                control = new Control(stage, 1, 0, player.getYRot(), 90, stage == 2, false);
            else {
                // Movement packets already apply real-player movement and collisions on the server.
                // Running vanilla travel as well builds stale gravity and can report a landing
                // several blocks before the client's canopy actually reaches the ground.
                player.fallDistance = 0;
                player.calculateEntityAnimation(false);
                return true;
            }
        } else {
            return false;
        }
        if (control == null || control.stage() != 2 && control.stage() != 3) return false;
        player.fallDistance = 0;
        player.setDeltaMovement(velocity(player.getDeltaMovement(), control));
        // Keep vanilla collision, landing and the map's one-way movement boundary.
        player.move(MoverType.SELF, player.getDeltaMovement());
        player.fallDistance = 0;
        player.calculateEntityAnimation(false);
        return true;
    }

    /** One tick of velocity in blocks/tick, independent of any world or client instance. */
    public static Vec3 velocity(Vec3 previous, Control control) {
        Vec3 old = finite(previous);
        if (control == null || control.stage() != 2 && control.stage() != 3) return old;
        double forward = clamp(finite(control.forward()), -1, 1);
        double strafe = clamp(finite(control.strafe()), -1, 1);
        double magnitude = Math.hypot(forward, strafe);
        if (magnitude > 1) {
            forward /= magnitude;
            strafe /= magnitude;
        }
        double yaw = Math.toRadians(finite(control.yaw()));
        double x = strafe * Math.cos(yaw) - forward * Math.sin(yaw);
        double z = forward * Math.cos(yaw) + strafe * Math.sin(yaw);
        if (control.stage() == 2) {
            return FreefallAerodynamics.velocity(limit(old, FREEFALL_LIMIT_METRES_PER_SECOND / TICKS_PER_SECOND),
                    x, z, finite(control.pitch()), control.fast());
        }
        // Canopy drag smooths the transition from a dive and retains momentum while turning.
        // No input brakes horizontal movement; Ctrl offers a better glide ratio, Shift sinks faster.
        double horizontal = control.fast() ? 12.0 : control.glide() ? 9.0 : 10.0;
        double descent = control.fast() ? 15.0 : control.glide() ? 3.0 : 6.0;
        double vx = approach(old.x, x * horizontal / TICKS_PER_SECOND, 0.12);
        double vz = approach(old.z, z * horizontal / TICKS_PER_SECOND, 0.12);
        double vy = approach(old.y, -descent / TICKS_PER_SECOND, 0.28);
        return limit(new Vec3(vx, Math.min(0, vy), vz),
                FREEFALL_LIMIT_METRES_PER_SECOND / TICKS_PER_SECOND);
    }

    private static Vec3 finite(Vec3 value) {
        return new Vec3(finite(value.x), finite(value.y), finite(value.z));
    }
    private static double finite(double value) { return Double.isFinite(value) ? value : 0; }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static double approach(double old, double target, double factor) { return old + (target - old) * factor; }
    private static Vec3 limit(Vec3 value, double maximum) {
        double length = value.length();
        return length > maximum ? value.scale(maximum / length) : value;
    }
}
