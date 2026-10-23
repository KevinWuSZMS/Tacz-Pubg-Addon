package com.example.examplemod.gunbalance.client;

/** Model-pixel heuristics for guns whose authors supplied an iron view but no scope mount. */
public final class ScopeAnchorGeometry {
    public static final float VERTICAL_CLEARANCE_PIXELS = 1.0f;

    private ScopeAnchorGeometry() {}

    public static boolean shouldUseFallback(boolean authoredMount, boolean externalScope,
            boolean universalScopeEnabled, boolean ironView) {
        return !authoredMount && externalScope && universalScopeEnabled && ironView;
    }

    /** Move from the authored eye point towards the receiver, bounded for pistols and long guns. */
    public static float forwardOffsetPixels(double eyeToMuzzlePixels) {
        if (!Double.isFinite(eyeToMuzzlePixels) || eyeToMuzzlePixels <= 0) return 8.0f;
        return (float) Math.max(4.0, Math.min(12.0, eyeToMuzzlePixels * 0.4));
    }
}
