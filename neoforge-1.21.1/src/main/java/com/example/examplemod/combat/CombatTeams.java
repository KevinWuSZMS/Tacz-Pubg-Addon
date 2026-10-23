package com.example.examplemod.combat;

/** Frozen lobby team numbers; colors come from the independent administrator team palette. */
public final class CombatTeams {
    public static int number(String frozenTeamId) {
        int at=frozenTeamId.lastIndexOf(":team:");if(at<0)return 0;
        try {int n=Integer.parseInt(frozenTeamId.substring(at+6));return n>=1&&n<=100?n:0;}
        catch(NumberFormatException ignored){return 0;}
    }
    private CombatTeams() {}
}
