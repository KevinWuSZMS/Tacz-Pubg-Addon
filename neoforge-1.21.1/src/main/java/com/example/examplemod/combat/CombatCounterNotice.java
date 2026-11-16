package com.example.examplemod.combat;

import java.util.UUID;

/** Only new elimination/assist credit can start a counter notice; ordinary combat messages cannot. */
public final class CombatCounterNotice {
    private UUID session;
    private int kills, assists, amount;
    private boolean assist;
    private double born = Double.NEGATIVE_INFINITY;

    /** The first snapshot is a baseline, including when reconnecting to an existing match. */
    public void reset(UUID next, int initialKills, int initialAssists) {
        session = next; kills = initialKills; assists = initialAssists;
        amount = 0; assist = false; born = Double.NEGATIVE_INFINITY;
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

    private void show(boolean isAssist, int total, double now) { assist = isAssist; amount = total; born = now; }
    public boolean assist() { return assist; }
    public int amount() { return amount; }
    public double age(double now) { return now - born; }
}
