package com.example.examplemod.combat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Pure attribution rules: user-defined team finishing policy, independent of entity lifecycle. */
public final class CombatRules {
    public record Contributor(UUID id, String team, double damage, long lastHit) {}
    public record Credit(UUID killer, List<UUID> assistants) {}
    public static Credit credit(UUID victim, String victimTeam, UUID downer, String downerTeam,
            UUID finisher, String finisherTeam, List<Contributor> contributors, long now, long window, double minimum) {
        if (finisher != null && !finisher.equals(victim) && victimTeam.equals(finisherTeam)) {
            return new Credit(null, List.of());
        }
        UUID killer = finisher != null && !finisher.equals(victim) && !victimTeam.equals(finisherTeam) ? finisher : null;
        boolean validDowner = downer != null && !downer.equals(victim) && !victimTeam.equals(downerTeam);
        if (validDowner && (killer == null || downerTeam.equals(finisherTeam))) killer = downer;
        String killerTeam = killer != null && killer.equals(downer) ? downerTeam : finisherTeam;
        var assistants = new LinkedHashSet<UUID>();
        if (killer != null) {
            if (finisher != null && !finisher.equals(killer) && !finisher.equals(victim) && !victimTeam.equals(finisherTeam)) assistants.add(finisher);
            for (var c : contributors) if (!c.id.equals(killer) && !c.id.equals(victim) && c.team.equals(killerTeam) && !victimTeam.equals(c.team)
                    && c.damage >= minimum && now >= c.lastHit && now - c.lastHit <= window) assistants.add(c.id);
        }
        return new Credit(killer,List.copyOf(assistants));
    }
    public static int bleedTier(int downs) { return Math.clamp(downs,1,9); }
    public static boolean directDeath(boolean dbnoEnabled, boolean teammateCanRescue, boolean selfRescueAvailable) {
        return !dbnoEnabled || !(teammateCanRescue || selfRescueAvailable);
    }
    public static double pubgDamage(double healthDamage,double maximumHealth) {
        if(!Double.isFinite(healthDamage)||!Double.isFinite(maximumHealth)||healthDamage<0||maximumHealth<=0)return 0;
        return healthDamage*100/maximumHealth;
    }
    public static float bleed(float hp,float maximum,double seconds,boolean frozen) {
        if(frozen)return hp;
        if(!Float.isFinite(hp)||!Float.isFinite(maximum)||maximum<=0||!Double.isFinite(seconds)||seconds<=0)throw new IllegalArgumentException("Invalid bleeding state");
        return (float)Math.max(0,hp-maximum/(20*seconds));
    }
    public static boolean mayRescue(boolean sameTeam, boolean alive, boolean targetDowned, boolean underwater,
            double distanceSquared, double reach, boolean visible, double movedSquared, double tolerance) {
        return sameTeam && alive && targetDowned && !underwater && visible && Double.isFinite(distanceSquared)
                && distanceSquared <= reach*reach+1e-9 && Double.isFinite(movedSquared) && movedSquared <= tolerance*tolerance+1e-9;
    }
    private CombatRules() {}
}
