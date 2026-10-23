package com.example.examplemod.gunbalance.client;

/** Pure geometry and activation checks; no Minecraft or TaCZ initialization. */
public final class ScopeAnchorGeometryChecks {
    private static int assertions;

    public static void main(String[] args) {
        for (int bits = 0; bits < 16; bits++) {
            boolean authored = (bits & 1) != 0;
            boolean external = (bits & 2) != 0;
            boolean enabled = (bits & 4) != 0;
            boolean iron = (bits & 8) != 0;
            require(ScopeAnchorGeometry.shouldUseFallback(authored, external, enabled, iron)
                    == (!authored && external && enabled && iron), "Wrong fallback activation");
        }
        require(ScopeAnchorGeometry.forwardOffsetPixels(1) == 4, "Short-gun minimum");
        require(ScopeAnchorGeometry.forwardOffsetPixels(20) == 8, "Receiver estimate");
        require(ScopeAnchorGeometry.forwardOffsetPixels(60) == 12, "Long-gun maximum");
        require(ScopeAnchorGeometry.forwardOffsetPixels(Double.NaN) == 8, "Absent muzzle fallback");
        require(ScopeAnchorGeometry.forwardOffsetPixels(Double.POSITIVE_INFINITY) == 8, "Non-finite fallback");
        require(ScopeAnchorGeometry.forwardOffsetPixels(-1) == 8, "Invalid distance fallback");
        require(ScopeAnchorGeometry.VERTICAL_CLEARANCE_PIXELS > 0, "Upward mount clearance");
        float previous = 0;
        for (int distance = 1; distance <= 100; distance++) {
            float offset = ScopeAnchorGeometry.forwardOffsetPixels(distance);
            require(offset >= 4 && offset <= 12 && offset >= previous, "Bounded monotonic placement");
            previous = offset;
        }
        System.out.println("Scope anchor geometry checks passed: " + assertions + " assertions.");
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
