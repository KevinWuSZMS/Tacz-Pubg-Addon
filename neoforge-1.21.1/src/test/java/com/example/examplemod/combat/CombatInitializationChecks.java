package com.example.examplemod.combat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Run each mode in a fresh JVM: using the existing wire checks first masks initialization cycles. */
public final class CombatInitializationChecks {
    private static int checks;

    public static void main(String[] args) throws ClassNotFoundException {
        if (args.length != 1) throw new IllegalArgumentException("Expected snapshot, outer, rescue, or actor");
        String first = "com.example.examplemod.combat.CombatPayloads" + switch (args[0]) {
            case "snapshot" -> "$Snapshot";
            case "outer" -> "";
            case "rescue" -> "$Rescue";
            case "actor" -> "$Actor";
            default -> throw new IllegalArgumentException("Unknown initialization order: " + args[0]);
        };
        // Do not reference any CombatPayloads static field before this deliberate first initialization.
        // Snapshot-first previously left latest permanently null. Rescue-first could fail in EMPTY's constructor.
        Class.forName(first, true, CombatInitializationChecks.class.getClassLoader());

        CombatPayloads.Snapshot initial = CombatPayloads.latest();
        check(initial != null, args[0] + " first must expose an initialized snapshot before any world/player exists");
        check(initial == CombatPayloads.Snapshot.EMPTY, "The initial client state is the canonical empty snapshot");
        check(initial.session().equals(CombatPayloads.NO_SESSION), "Startup has no active combat session");
        check(initial.rescue() != null && initial.rescue() == CombatPayloads.Rescue.NONE && !initial.rescue().active(),
                "Startup exposes a fully initialized, inactive rescue");
        check(!CombatPayloads.clientDowned(new UUID(3, 4)), "Checking downed state is safe before joining a world");
        check(CombatPayloads.Actor.NONE.id().equals(CombatPayloads.NO_SESSION), "Actor initialization has a valid no-session identity");

        // A logout/reset must work even before a client listener has registered.
        CombatPayloads.clearClient();
        check(CombatPayloads.latest() == initial, "Clearing startup state preserves the initialized empty snapshot");

        AtomicInteger notices = new AtomicInteger();
        CombatPayloads.setClientRescueHandler(rescue -> {
            check(rescue != null && rescue == CombatPayloads.Rescue.NONE && !rescue.active(),
                    "Clear callbacks receive an initialized inactive rescue");
            check(CombatPayloads.latest() == CombatPayloads.Snapshot.EMPTY,
                    "Clear callbacks observe the already-cleared snapshot");
            notices.incrementAndGet();
        });
        CombatPayloads.clearClient();
        CombatPayloads.clearClient();
        check(notices.get() == 2, "Each reset publishes exactly one rescue-clear notification");
        check(!CombatPayloads.clientDowned(new UUID(5, 6)), "Downed queries remain safe after repeated resets");
        System.out.println("CombatInitializationChecks: " + args[0] + " first, " + checks + " checks passed; no game initialized.");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private CombatInitializationChecks() { }
}
