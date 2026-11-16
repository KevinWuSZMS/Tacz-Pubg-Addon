package com.example.examplemod.vitals.client;

import java.util.Arrays;

/** A short delayed damage edge whose clock keeps advancing during continuous bleeding. */
public final class DownedHealthTrail {
    private static final int DELAY_TICKS = 12;
    private final double[] history = new double[DELAY_TICKS];
    private int cursor, downCount;
    private double maximum, lastHealth, trail;
    private boolean initialized;

    public void clear() { initialized = false; cursor = 0; }

    public void tick(double health, double maximum, int downCount) {
        health = bounded(health, maximum);
        if (!matches(maximum, downCount) || health > lastHealth) {
            this.maximum = maximum;
            this.downCount = downCount;
            initialized = true;
            cursor = 0;
            Arrays.fill(history, health);
            trail = lastHealth = health;
            return;
        }
        double delayed = history[cursor];
        history[cursor] = health;
        cursor = (cursor + 1) % history.length;
        trail = Math.max(health, trail + (delayed - trail) * .25);
        if (trail - health < Math.max(1e-6, maximum * .0001)) trail = health;
        lastHealth = health;
    }

    /** Fresh snapshots can arrive before the next client tick; never show an old down's tail. */
    public double value(double health, double maximum, int downCount) {
        health = bounded(health, maximum);
        return matches(maximum, downCount) ? Math.max(health, trail) : health;
    }

    private boolean matches(double maximum, int downCount) {
        return initialized && this.downCount == downCount && this.maximum == maximum;
    }

    private static double bounded(double health, double maximum) {
        return PubgVitalsLayout.fraction(health, maximum) * (Double.isFinite(maximum) && maximum > 0 ? maximum : 0);
    }
}
