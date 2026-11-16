package com.example.examplemod.lean;

/** Time-based lean interpolation. Sampling never advances state or depends on render/tick count. */
public final class LeanMotion {
    public static final long TRAVEL_NANOS = 180_000_000L;
    private double start;
    private int target;
    private long since;

    public double sample(long now) {
        double distance = Math.max(0, now - since) / (double) TRAVEL_NANOS;
        return start + Math.copySign(Math.min(Math.abs(target - start), distance), target - start);
    }

    public void target(int direction, long now) {
        if (direction < -1 || direction > 1) throw new IllegalArgumentException("lean direction");
        if (target == direction) return;
        start = sample(now);
        target = direction;
        since = now;
    }

    public void correct(double value, int direction, long now) {
        if (!Double.isFinite(value) || Math.abs(value) > 1 || Math.abs(direction) > 1)
            throw new IllegalArgumentException("lean correction");
        start = value; target = direction; since = now;
    }

    public int target() { return target; }
    public void reset(long now) { correct(0, 0, now); }

    /** Bound extrapolation into the requested side while preserving a smooth return to neutral. */
    public static double limited(double value, int target, double limit) {
        return Math.signum(value) == Math.signum(target)
                ? Math.copySign(Math.min(Math.abs(value), Math.clamp(limit, 0, 1)), value) : value;
    }

    /** New press wins; releasing it requires another press to restore the older held side. */
    public static final class Input {
        private boolean left, right;
        private int direction;
        public void press(int side) {
            if (side == -1 && !left) { left = true; direction = -1; }
            if (side == 1 && !right) { right = true; direction = 1; }
        }
        public void release(int side) {
            if (side == -1) left = false;
            if (side == 1) right = false;
            if (direction == side) direction = 0;
        }
        public int direction() { return direction; }
        public void reset() { left = right = false; direction = 0; }
    }
}
