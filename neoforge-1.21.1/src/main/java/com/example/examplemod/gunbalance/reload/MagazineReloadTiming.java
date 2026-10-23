package com.example.examplemod.gunbalance.reload;

/** Real/virtual time conversion for the nine workbench IDs; no gun capacity changes. */
public final class MagazineReloadTiming {
    private MagazineReloadTiming() {}

    public static double rateForId(String attachmentId) {
        if (attachmentId == null) return 1.0;
        return switch (attachmentId) {
            case "tacz_pubg_workbench:quickdraw_mag_rifle",
                 "tacz_pubg_workbench:quickdraw_mag_smg",
                 "tacz_pubg_workbench:quickdraw_mag_sniper" -> 1.6;
            case "tacz_pubg_workbench:extended_quickdraw_mag_rifle",
                 "tacz_pubg_workbench:extended_quickdraw_mag_smg",
                 "tacz_pubg_workbench:extended_quickdraw_mag_sniper" -> 1.15;
            default -> 1.0;
        };
    }

    /** A shared clock advances faster, preserving the gun's original feed/finish rules. */
    public static long elapsed(long realElapsed, double rate) {
        if (realElapsed <= 0 || !accelerated(rate)) return realElapsed;
        return (long) Math.min(Long.MAX_VALUE, realElapsed * rate);
    }

    /** Remaining countdown is returned to real milliseconds, including -1 sentinels. */
    public static long duration(long virtualDuration, double rate) {
        if (virtualDuration <= 0 || !accelerated(rate)) return virtualDuration;
        return (long) Math.ceil(virtualDuration / rate);
    }

    /** Script-requested progress shifts use virtual milliseconds, including negatives. */
    public static long adjustment(long virtualAdjustment, double rate) {
        return accelerated(rate) ? Math.round(virtualAdjustment / rate) : virtualAdjustment;
    }

    public static boolean accelerated(double rate) {
        return Double.isFinite(rate) && rate > 1.0;
    }
}
