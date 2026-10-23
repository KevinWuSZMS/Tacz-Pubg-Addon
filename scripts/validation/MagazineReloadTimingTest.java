package com.example.examplemod.gunbalance.reload;

/** Standalone Java check; it intentionally does not start Minecraft. */
public final class MagazineReloadTimingTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        for (String family : new String[]{"rifle", "smg", "sniper"}) {
            check(MagazineReloadTiming.rateForId("tacz_pubg_workbench:quickdraw_mag_" + family) == 1.6, "QuickDraw rate");
            check(MagazineReloadTiming.rateForId("tacz_pubg_workbench:extended_mag_" + family) == 1.0, "Extended unchanged");
            check(MagazineReloadTiming.rateForId("tacz_pubg_workbench:extended_quickdraw_mag_" + family) == 1.15, "EQD rate");
        }
        for (String id : new String[]{"cbrg:quickdraw_mag_rifle", "tacz:extended_mag_1", "other:quickdraw_mag_smg", "tacz_pubg_workbench:quickdraw_mag_unknown"}) {
            check(MagazineReloadTiming.rateForId(id) == 1.0, "Foreign and unknown IDs must remain unchanged");
        }
        // Real server ticks must reach both feed and finish at the same compressed scale.
        for (double rate : new double[]{1.0, 1.15, 1.6}) {
            for (long threshold : new long[]{300, 2000, 2450, 3620}) {
                long deadline = MagazineReloadTiming.duration(threshold, rate);
                check(MagazineReloadTiming.elapsed(deadline, rate) >= threshold, "Deadline must complete phase");
                check(MagazineReloadTiming.elapsed(deadline - 1, rate) < threshold, "No phase may finish a millisecond early");
                for (long elapsed = 0; elapsed < deadline; elapsed += 50) {
                    long virtual = MagazineReloadTiming.elapsed(elapsed, rate);
                    long remaining = MagazineReloadTiming.duration(threshold - virtual, rate);
                    check(Math.abs((elapsed + remaining) - deadline) <= 1, "Synced countdown must use real milliseconds");
                }
            }
            for (long shift : new long[]{-1000, -80, 0, 80, 1000}) {
                double error = Math.abs(MagazineReloadTiming.adjustment(shift, rate) * rate - shift);
                check(error <= rate / 2 + 0.0001, "Script adjustment must not apply the rate twice");
            }
        }
        check(MagazineReloadTiming.duration(-1, 1.6) == -1, "Not-reloading sentinel");
        check(MagazineReloadTiming.elapsed(Long.MAX_VALUE, 1.6) == Long.MAX_VALUE, "Clock cannot overflow");
        System.out.println("MagazineReloadTimingTest passed: nine IDs, foreign isolation, feed/finish, countdown and script adjustment.");
    }
}
