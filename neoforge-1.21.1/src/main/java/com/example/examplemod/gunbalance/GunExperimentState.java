package com.example.examplemod.gunbalance;

import net.minecraft.world.level.Level;

/** Logical-side separation is essential when an integrated server shares this JVM. */
public final class GunExperimentState {
    private static volatile boolean clientEnabled;
    private static volatile boolean clientMagazineEnabled;
    private static long clientRevision = -1;

    private GunExperimentState() {}

    public static boolean enabled(Level level) {
        if (level == null) return false;
        return level.isClientSide ? clientEnabled : GunBalanceService.isBalanceExperimentActive(level.getServer());
    }

    public static boolean clientEnabled() { return clientEnabled; }

    /** Magazine timing is independent of the damage/balance experiment. */
    public static boolean magazineEnabled(Level level) {
        if (level == null) return false;
        return level.isClientSide ? clientMagazineEnabled
                : GunBalanceService.isMagazineExperimentActive(level.getServer());
    }

    public static boolean clientMagazineEnabled() { return clientMagazineEnabled; }

    /** Called only on the client main thread, after the server's native gun-data sync. */
    public static boolean acceptClient(long revision, boolean enabled) {
        return acceptClient(revision, enabled, false);
    }

    public static boolean acceptClient(long revision, boolean enabled, boolean magazineEnabled) {
        if (revision < 0 || revision <= clientRevision) return false;
        clientRevision = revision;
        clientEnabled = enabled;
        clientMagazineEnabled = magazineEnabled;
        return true;
    }

    public static void clearClient() {
        clientEnabled = false;
        clientMagazineEnabled = false;
        clientRevision = -1;
    }
}
