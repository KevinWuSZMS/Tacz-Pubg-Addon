package com.example.examplemod.originalicons;

/** A disconnected client always starts with the bundled models. */
public final class OriginalIconState {
    private static volatile boolean enabled;
    private static volatile boolean lowPoly;
    private static long revision = -1;
    private OriginalIconState() {}
    public static boolean enabled() { return enabled; }
    public static boolean lowPolyModels() { return lowPoly; }
    public static boolean accept(long next, boolean value) {
        return accept(next, value, false);
    }
    public static boolean accept(long next, boolean value, boolean useLowPoly) {
        if (next < 0 || next <= revision) return false;
        revision = next;
        enabled = value;
        lowPoly = useLowPoly;
        return true;
    }
    public static void clear() { enabled = false; lowPoly = false; revision = -1; }
}
