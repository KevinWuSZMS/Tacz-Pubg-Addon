package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapZoneModel.Circle;
import java.util.UUID;

/** Read-only match progress. Phase numbers are one-based, with zero during preparation. */
public record MatchStatus(long mapId, UUID sessionId, String stage, int phase, int phaseCount,
        int secondsRemaining, int playersAlive, int teamsAlive, int playersTotal, String result,
        Circle currentCircle, Circle targetCircle, int stageTotalSeconds, double flightProgress,
        String bombingStage, int bombingSeconds, Circle phaseStartCircle,
        long stageElapsedTicks, long stageDurationTicks, long matchTicks, String underlyingStage) {
    public MatchStatus(long mapId, UUID sessionId, String stage, int phase, int phaseCount,
            int secondsRemaining, int playersAlive, int teamsAlive, int playersTotal, String result,
            Circle currentCircle, Circle targetCircle, int stageTotalSeconds, double flightProgress,
            String bombingStage, int bombingSeconds) {
        this(mapId, sessionId, stage, phase, phaseCount, secondsRemaining, playersAlive, teamsAlive, playersTotal,
                result, currentCircle, targetCircle, stageTotalSeconds, flightProgress, bombingStage, bombingSeconds,
                currentCircle, Math.max(0L, stageTotalSeconds - secondsRemaining) * 20L,
                Math.max(stageTotalSeconds, secondsRemaining) * 20L, 0,
                stage.equals("paused") ? "waiting" : stage);
    }
    public boolean paused() { return stage.equals("paused"); }
    public String effectiveStage() { return paused() ? underlyingStage : stage; }
    public long remainingTicks() { return Math.max(0, stageDurationTicks - stageElapsedTicks); }
    public MatchStatus(long mapId, UUID sessionId, String stage, int phase, int phaseCount,
            int secondsRemaining, int playersAlive, int teamsAlive, int playersTotal, String result,
            Circle currentCircle, Circle targetCircle) {
        this(mapId, sessionId, stage, phase, phaseCount, secondsRemaining, playersAlive, teamsAlive, playersTotal,
                result, currentCircle, targetCircle, secondsRemaining, -1, "none", 0);
    }
}
