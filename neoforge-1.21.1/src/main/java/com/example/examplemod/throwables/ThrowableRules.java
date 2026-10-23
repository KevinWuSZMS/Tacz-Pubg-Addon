package com.example.examplemod.throwables;

/** Bounded gameplay math; one block is the chosen one-metre adaptation. */
public final class ThrowableRules {
    public static final int C4_DEPLOY_TICKS = 80;
    public static final int MAX_TRACKED_PER_LEVEL = 256;
    public static double radius(ThrowableKind kind, int age) {
        if (age < 0 || age >= kind.fieldTicks) return 0;
        if (kind == ThrowableKind.BLUE_ZONE)
            return kind.radius * Math.min(1, Math.min(age / 100.0, (kind.fieldTicks - age) / 100.0));
        if (kind == ThrowableKind.SMOKE)
            return kind.radius * Math.min(1, Math.min(age / 40.0, (kind.fieldTicks - age) / 60.0));
        return kind.radius * Math.min(1, (age + 10) / 50.0);
    }
    public static double damage(double center, double radius, double distance, double exposure, boolean c4) {
        if (!Double.isFinite(distance) || radius <= 0 || distance >= radius || distance < 0) return 0;
        // Same D0*(1-(r/R)^p)^q family as the project's heavy weapons. Values are explicit MC adaptations.
        double normalized = distance / radius;
        return center * Math.pow(1 - normalized * normalized, c4 ? 2 : 1.5) * Math.clamp(exposure, 0, 1);
    }
    public static int flashTicks(double distance, double facingDot, boolean visible) {
        if (!visible || !Double.isFinite(distance) || distance >= 10 || distance < 0) return 0;
        double facing = distance <= 3 ? 1 : facingDot > .5 ? 1 : facingDot > -.25 ? .55 : .2;
        return (int) Math.round(120 * (1 - distance / 10) * facing);
    }
    public static int remainingFuse(ThrowableKind kind, long cookedTicks) {
        return Math.max(0, cookedFuse(kind) - (int) Math.clamp(cookedTicks, 0, 72000));
    }
    public static int cookedFuse(ThrowableKind kind) {
        return kind == ThrowableKind.STUN ? 50 : kind.fuse;
    }
    public static boolean validSession(java.util.UUID saved, java.util.UUID current) {
        return saved == null || saved.equals(current);
    }
    private ThrowableRules() {}
}
