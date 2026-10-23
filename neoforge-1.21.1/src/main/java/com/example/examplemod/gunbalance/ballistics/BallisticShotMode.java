package com.example.examplemod.gunbalance.ballistics;

import com.example.examplemod.localization.Texts;

/**
 * Launch-time mode latch and the versioned suffix of TaCZ's existing complex bullet spawn payload.
 * Wire schema 1 is exactly two unsigned bytes: schema (1), then mode (0 initial / 1 experimental).
 * Initial shots also write the suffix. Both sides must run the same addon's network version.
 */
public final class BallisticShotMode {
    public static final int SPAWN_SCHEMA = 1;
    private boolean locked;
    private boolean experimental;

    public boolean isLocked() { return locked; }
    public boolean experimental() { return experimental; }

    public boolean lock(boolean requested) {
        if (!locked) {
            locked = true;
            experimental = requested;
        }
        return experimental;
    }

    public static boolean decode(int schema, int mode) {
        if (schema != SPAWN_SCHEMA || (mode != 0 && mode != 1))
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ballistic_shot_mode.incompatible_experimental_ballistics_spawn_schema_or_mode"));
        return mode == 1;
    }
}
