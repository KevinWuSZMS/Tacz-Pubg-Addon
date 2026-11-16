package com.example.examplemod.lean;

/** Offline protocol checks: native shot identity, per-round consumption, late/flooded input. */
public final class LeanShotLedgerChecks {
    private static int checks;

    public static void main(String[] args) {
        LeanShotLedger.Sample left = new LeanShotLedger.Sample(7, .08, -8, 45);
        LeanShotLedger.Sample right = new LeanShotLedger.Sample(8, .02, 12, -90);
        LeanShotLedger ledger = new LeanShotLedger();
        check(ledger.offer(100, 0, left, 0), "first pose accepted");
        check(!ledger.offer(100, 0, right, 1), "duplicate cannot replace first pose");
        check(ledger.claim(100, 0, 2) == left, "native timestamp selects its exact first round");
        check(ledger.claim(100, 0, 3) == null, "one pose is consumed once");
        check(!ledger.offer(100, 0, right, 4), "late packet cannot move a fired round");
        check(ledger.claim(100, 1, 5) == null, "missing round uses caller's authoritative fallback");
        check(!ledger.offer(100, 1, right, 6), "fallback round also rejects late packets");
        check(ledger.offer(100, 2, right, 7), "next burst round can have a new lean and angle");
        check(ledger.offer(101, 0, left, 8), "next native shoot group remains independent");
        check(ledger.claim(100, 2, 9) == right, "burst round uses its own pose");
        check(ledger.claim(101, 0, 10) == left, "overlapping shot group stays associated");
        check(!ledger.offer(102, 64, left, 11), "bounded burst index");
        check(!ledger.offer(-1, 0, left, 11), "negative native identity rejected");
        check(!ledger.offer(102, 0, new LeanShotLedger.Sample(7, Double.NaN, 0, 0), 11), "NaN time rejected");
        check(!ledger.offer(102, 0, new LeanShotLedger.Sample(7, 0, 91, 0), 11), "invalid pitch rejected");
        check(!ledger.offer(102, 0, new LeanShotLedger.Sample(7, 0, 0, Float.POSITIVE_INFINITY), 11), "infinite yaw rejected");
        check(ledger.offer(103, 0, left, 12), "expiry sample accepted");
        check(ledger.claim(103, 0, LeanShotLedger.LIFETIME_NANOS + 13) == null, "expired input falls back");
        LeanShotLedger bounded = new LeanShotLedger();
        for (long id = 0; id < LeanShotLedger.MAX_GROUPS + 1; id++) bounded.offer(id, 0, left, id);
        check(bounded.claim(0, 0, 100) == null, "bounded queue evicts oldest unmatched group");
        check(bounded.claim(64, 0, 101) == left, "recent valid packet survives queue pressure");
        System.out.println("LeanShotLedgerChecks: " + checks + " checks passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
