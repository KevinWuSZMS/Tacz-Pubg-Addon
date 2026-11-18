package com.example.examplemod.zones.match;

import com.example.examplemod.localization.Texts;
import net.minecraft.network.chat.Component;

/** One announcement for the latest circle event crossed between HUD snapshots. */
public final class SafeZoneAlerts {
    private static final int[] FINAL_MINUTE_WARNINGS = {5, 10, 20, 30, 40, 60};

    private SafeZoneAlerts() {}

    public static Component message(MatchStatus previous, MatchStatus next) {
        String stage = next.effectiveStage();
        boolean waiting = stage.equals("waiting");
        if (!waiting && !stage.equals("shrinking")) return null;

        boolean sameMatch = previous != null && next.sessionId().equals(previous.sessionId())
                && next.mapId() == previous.mapId();
        // On joining, only announce a phase that began within the current HUD publication interval.
        if (!sameMatch && (next.paused() || next.stageElapsedTicks() >= 20)) return null;
        if (!sameMatch || previous.phase() != next.phase() || !previous.effectiveStage().equals(stage)) {
            return waiting ? Texts.of("screen.tacz_pubg.pubg_hud.new_safe_zone", duration(next.secondsRemaining()))
                    : Texts.of("screen.tacz_pubg.pubg_hud.restricting");
        }
        if (!waiting) return null;

        int remaining = next.secondsRemaining();
        int threshold = Math.ceilDiv(remaining, 120) * 120;
        for (int warning : FINAL_MINUTE_WARNINGS) {
            if (remaining <= warning) {
                threshold = warning;
                break;
            }
        }
        return previous.secondsRemaining() > threshold
                ? Texts.of("screen.tacz_pubg.pubg_hud.restrict_in", duration(threshold)) : null;
    }

    private static Component duration(int seconds) {
        int minutes = seconds / 60, remainder = seconds % 60;
        if (minutes == 0) return Texts.of("screen.tacz_pubg.pubg_hud.time_seconds", remainder);
        if (remainder == 0) return Texts.of("screen.tacz_pubg.pubg_hud.time_minutes", minutes);
        return Texts.of("screen.tacz_pubg.pubg_hud.time_minutes_seconds", minutes, remainder);
    }
}
