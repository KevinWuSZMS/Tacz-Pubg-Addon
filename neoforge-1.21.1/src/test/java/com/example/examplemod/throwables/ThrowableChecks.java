package com.example.examplemod.throwables;

import java.util.HashSet;
import java.util.UUID;

/** Pure bounds that prevent stale-session damage, early fuses and unbounded effect input. */
public final class ThrowableChecks {
    private static int checks;
    public static void main(String[] args) {
        var ids = new HashSet<String>();
        for (ThrowableKind kind : ThrowableKind.values()) {
            check(ids.add(kind.id), "All seven ordinary loot IDs are unique");
            check(ThrowableKind.byOrdinal(kind.ordinal()) == kind, "Saved kind identity is stable");
            check(kind.radius > 0 && kind.radius <= 25, "Effect queries are bounded");
        }
        check(ids.size() == 7, "Current PC catalog contains seven implemented types");
        check(ThrowableRules.C4_DEPLOY_TICKS == 80 && ThrowableKind.C4.fuse == 320, "C4 is 4s placement plus 16s countdown");
        check(!ThrowableKind.C4.cookable() && !ThrowableKind.STICKY.cookable(), "C4 and sticky bombs cannot use frag cooking");
        check(ThrowableKind.SMOKE.fuse == 20, "Smoke uses PC 6.2's 1 second fuse");
        check(ThrowableKind.STUN.fuse == 100 && ThrowableRules.remainingFuse(ThrowableKind.STUN, 10) == 40,
                "Stun fuse is 5 seconds when thrown uncooked and 2.5 seconds from cooking");
        check(ThrowableRules.remainingFuse(ThrowableKind.FRAG, 60) == 40, "Cooking spends the existing fuse");
        check(ThrowableRules.remainingFuse(ThrowableKind.FRAG, 200) == 0, "Holding too long cannot wrap the fuse");
        check(ThrowableRules.remainingFuse(ThrowableKind.FRAG, -1) == 100, "Negative elapsed input cannot extend a fuse");
        near(ThrowableRules.radius(ThrowableKind.BLUE_ZONE, 50), 5, "BZ grows during its first 5 seconds");
        near(ThrowableRules.radius(ThrowableKind.BLUE_ZONE, 100), 10, "BZ reaches 10 metres");
        near(ThrowableRules.radius(ThrowableKind.BLUE_ZONE, 199), 10, "BZ holds full size for 5 seconds");
        near(ThrowableRules.radius(ThrowableKind.BLUE_ZONE, 250), 5, "BZ shrinks during its final 5 seconds");
        near(ThrowableRules.radius(ThrowableKind.BLUE_ZONE, 300), 0, "Expired BZ has zero damage radius");
        near(ThrowableRules.damage(40, 8, 8, 1, false), 0, "No damage beyond frag radius");
        near(ThrowableRules.damage(40, 8, 1, 0, false), 0, "Solid cover blocks a frag");
        near(ThrowableRules.damage(40, 8, 0, 1, false), 40, "Center damage is independent of terrain power");
        check(ThrowableRules.flashTicks(1, -1, true) > 0, "Very close visible stun also affects a turned-away target");
        check(ThrowableRules.flashTicks(5, 1, true) > ThrowableRules.flashTicks(5, -1, true), "Facing changes stun duration");
        check(ThrowableRules.flashTicks(5, 1, false) == 0, "Opaque cover prevents the flash");
        check(ThrowableRules.flashTicks(10, 1, true) == 0, "Flash range is bounded");
        UUID first = UUID.randomUUID(), next = UUID.randomUUID();
        check(ThrowableRules.validSession(first, first), "Same active match retains thrown ordnance");
        check(!ThrowableRules.validSession(first, next) && !ThrowableRules.validSession(first, null), "Ended/changed matches cannot retain damage");
        rejects(() -> ThrowableKind.byOrdinal(-1));
        rejects(() -> ThrowableKind.byOrdinal(99));
        rejects(() -> new ThrowablePayloads.Action(3));
        rejects(() -> new ThrowablePayloads.QuickAction("../anything"));
        rejects(() -> new ThrowablePayloads.QuickUse(9, 80));
        rejects(() -> new ThrowablePayloads.Effect(1000, 0, -1));
        System.out.println("ThrowableChecks: " + checks + " checks passed; no game launched.");
    }
    private static void near(double actual, double expected, String reason) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) < 1.0e-9, reason);
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Invalid throwable input accepted");
    }
    private static void check(boolean condition, String reason) {
        checks++; if (!condition) throw new AssertionError(reason);
    }
}
