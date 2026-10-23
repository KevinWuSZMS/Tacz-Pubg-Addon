package com.example.examplemod.zones.match.bot;

import com.example.examplemod.localization.Texts;

import java.util.Set;

/** Deterministic decisions shared by the server controller and offline checks. */
public final class BotRules {
    public static final int MAX_BOTS = 100;
    public static final int MAX_PARTICIPANTS = 100;
    public static final Set<String> LOADOUTS = Set.of("rifle", "smg", "scavenger");
    public static final Set<String> DIFFICULTIES = Set.of("easy", "normal", "hard");
    private BotRules() {}

    public static String botName(java.util.UUID token, int seat) {
        if (seat < 1 || seat > MAX_BOTS) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.bot_rules.bot_slots"));
        return "PUBG_" + token.toString().replace("-", "").substring(0, 7) + "_"
                + String.format(java.util.Locale.ROOT, "%03d", seat);
    }

    public record Skill(int reactionTicks, double range, float turnDegrees, double aimError, double compensation) {}
    public static Skill skill(String difficulty) {
        return switch (difficulty) {
            case "easy" -> new Skill(16, 40, 5, 2.6, .35);
            case "hard" -> new Skill(6, 72, 10, .65, .8);
            case "normal" -> new Skill(10, 56, 7, 1.4, .6);
            default -> throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.bot_rules.choose_easy_normal_hard_bot_difficulty"));
        };
    }
    public static void reserve(int existing, int requested) {
        if (existing < 0 || requested < 0 || existing > MAX_BOTS || requested > MAX_BOTS - existing)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.bot_rules.most_managed_bots_allowed_server_wide_including_slots"));
    }
    public static float wrap(float degrees) {
        float value = degrees % 360;
        if (value >= 180) value -= 360;
        if (value < -180) value += 360;
        return value;
    }
    public static float approach(float current, float target, float maximum) {
        return current + Math.clamp(wrap(target - current), -maximum, maximum);
    }
    /** Squared distance to a finite firing segment, also used for friendly-fire clearance. */
    public static double segmentDistanceSquared(double px, double py, double pz,
            double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double length = dx * dx + dy * dy + dz * dz;
        double t = length <= 1e-12 ? 0 : Math.clamp(((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / length, 0, 1);
        double x = px - ax - t * dx, y = py - ay - t * dy, z = pz - az - t * dz;
        return x * x + y * y + z * z;
    }
    /** Per-server, per-tick shared budgets. Failed attempts still consume their allocated work. */
    public static final class Budget {
        private int rays;
        private int nodes;
        public Budget(int rays, int nodes) {
            if (rays < 0 || nodes < 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.bot_rules.budget_zero_positive_integer"));
            this.rays = rays; this.nodes = nodes;
        }
        public boolean ray() { if (rays == 0) return false; rays--; return true; }
        public boolean node() { if (nodes == 0) return false; nodes--; return true; }
        public int raysLeft() { return rays; }
        public int nodesLeft() { return nodes; }
    }
}
