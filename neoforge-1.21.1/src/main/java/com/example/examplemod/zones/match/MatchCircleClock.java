package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapZoneModel.Circle;

/** One bounded client reconstruction for the map and its geometric header. */
public final class MatchCircleClock {
    public record Frame(Circle current, Circle target, double elapsedTicks, long remainingTicks, double animationTicks) {}
    private MatchCircleClock() {}
    public static Frame sample(MatchStatus status, double ticksSinceSnapshot) {
        double prediction = status.paused() ? 0 : Math.clamp(ticksSinceSnapshot, 0, 10);
        double elapsed = Math.min(status.stageDurationTicks(), status.stageElapsedTicks() + prediction);
        Circle current = status.currentCircle();
        if (current != null && status.effectiveStage().equals("shrinking")
                && status.phaseStartCircle() != null && status.targetCircle() != null && status.stageDurationTicks() > 0) {
            Circle from = status.phaseStartCircle(), to = status.targetCircle();
            double progress = Math.clamp(elapsed / status.stageDurationTicks(), 0, 1);
            current = new Circle(from.centerX() + (to.centerX() - from.centerX()) * progress,
                    from.centerZ() + (to.centerZ() - from.centerZ()) * progress,
                    Math.max(.5, from.radius() + (to.radius() - from.radius()) * progress));
        }
        return new Frame(current, MatchCirclePreview.target(status, status.mapId()), elapsed,
                Math.max(0, (long) Math.ceil(status.stageDurationTicks() - elapsed)), status.matchTicks() + prediction);
    }
}
