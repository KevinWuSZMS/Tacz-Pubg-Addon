package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapZoneModel.Circle;

/** A next-circle announcement exists during waiting, shrinking and an active-phase pause. */
public final class MatchCirclePreview {
    private MatchCirclePreview() {}

    public static Circle target(MatchStatus status, long mapId) {
        if (status == null || status.mapId() != mapId || status.phase() <= 0
                || status.targetCircle() == null) return null;
        if (!status.stage().equals("waiting") && !status.stage().equals("shrinking")
                && !status.stage().equals("paused")) return null;
        return status.targetCircle().equals(status.currentCircle()) ? null : status.targetCircle();
    }
}
