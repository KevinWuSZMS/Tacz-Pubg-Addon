package com.example.examplemod.throwables;

/** One Minecraft item per authored throwable, so ordinary loot catalog entries retain their identity. */
public enum ThrowableKind {
    FRAG("frag_grenade", 100, 0, 8, 40),
    STUN("stun_grenade", 100, 0, 10, 0),
    SMOKE("smoke_grenade", 20, 1200, 4.5, 0),
    MOLOTOV("molotov", 0, 400, 4.5, 0),
    STICKY("sticky_bomb", 120, 0, 6, 30),
    C4("c4", 320, 0, 25, 60),
    BLUE_ZONE("blue_zone_grenade", 100, 300, 10, 0);

    public final String id;
    public final int fuse, fieldTicks;
    public final double radius, damage;
    ThrowableKind(String id, int fuse, int fieldTicks, double radius, double damage) {
        this.id = id; this.fuse = fuse; this.fieldTicks = fieldTicks; this.radius = radius; this.damage = damage;
    }
    public boolean cookable() { return this == FRAG || this == STUN || this == SMOKE; }
    public boolean explosive() { return this == FRAG || this == STICKY || this == C4; }
    public static ThrowableKind byOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= values().length) throw new IllegalArgumentException("Invalid throwable kind");
        return values()[ordinal];
    }
}
