package com.example.examplemod.combat;

import java.util.UUID;

/** Only new elimination/assist credit can start a counter notice; ordinary combat messages cannot. */
public final class CombatCounterNotice {
    private UUID session;
    private int kills, assists, amount;
    private boolean assist;
    private double born = Double.NEGATIVE_INFINITY;
    private int previousAmount;
    private double previousBorn = Double.NEGATIVE_INFINITY;
    private double rollBorn = Double.NEGATIVE_INFINITY, moveDuration = 3.6;

    public record Frame(int amount, int previousAmount, double progress, double opacity, double flash) {}

    /** The first snapshot is a baseline, including when reconnecting to an existing match. */
    public void reset(UUID next, int initialKills, int initialAssists) {
        session = next; kills = initialKills; assists = initialAssists;
        amount = 0; assist = false; born = Double.NEGATIVE_INFINITY;
        previousAmount = 0; previousBorn = Double.NEGATIVE_INFINITY;
        rollBorn = Double.NEGATIVE_INFINITY;
    }

    public boolean snapshot(UUID incoming, int nextKills, int nextAssists, double now) {
        if (!incoming.equals(session)) return false;
        boolean newKill = nextKills > kills, newAssist = nextAssists > assists;
        kills = Math.max(kills, nextKills); assists = Math.max(assists, nextAssists);
        if (newKill) show(false, kills, now);
        else if (newAssist) show(true, assists, now);
        return newKill || newAssist;
    }

    /** Event totals also work before the matching snapshot, without replaying its eventual update. */
    public boolean event(UUID incoming, String type, int nextKills, int nextAssists, double now) {
        if (!incoming.equals(session)) return false;
        if (type.equals("elimination") && nextKills > kills) {
            kills = nextKills; show(false, kills, now); return true;
        }
        if (type.equals("assist") && nextAssists > assists) {
            assists = nextAssists; show(true, assists, now); return true;
        }
        return false;
    }

    private void show(boolean isAssist, int total, double now) {
        // Coalesce credits received in one tick: animate from what was visible, not an unseen intermediate total.
        boolean moving = assist == isAssist && previousAmount > 0 && now - rollBorn < moveDuration;
        if ((now != born && !moving) || assist != isAssist) {
            previousAmount = assist == isAssist ? amount : 0;
            previousBorn = assist == isAssist ? born : Double.NEGATIVE_INFINITY;
            rollBorn = now;
        }
        assist = isAssist; amount = total; born = now;
    }

    /** Only the number rolls; refreshing a visible notice never fades its fixed label back to zero. */
    public Frame frame(double now, double duration, double fadeIn, double fadeOut, double moveTicks) {
        return frame(now,duration,fadeIn,fadeOut,moveTicks,6,.28);
    }
    public Frame frame(double now, double duration, double fadeIn, double fadeOut, double moveTicks,
                       double flashTicks, double flashOpacity) {
        moveDuration = Math.max(0,moveTicks);
        double age = age(now);
        boolean continuing = previousAmount > 0 && born - previousBorn < duration;
        double opacity = CombatTimeline.alpha(age, duration, continuing ? 0 : fadeIn, fadeOut);
        double rollAge = now - rollBorn;
        double progress = continuing ? CombatTimeline.smooth(rollAge, moveTicks) : 1;
        // The reference briefly lights the panel after the arriving digit settles (about 0.3 seconds).
        double flash = continuing && rollAge >= moveTicks && rollAge < moveTicks + flashTicks
                ? flashOpacity * (1 - CombatTimeline.smooth(rollAge - moveTicks, flashTicks)) : 0;
        return new Frame(amount, continuing ? previousAmount : 0, progress, opacity, flash);
    }
    public boolean assist() { return assist; }
    public int amount() { return amount; }
    public double age(double now) { return now - born; }
}
