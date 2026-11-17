package com.example.examplemod.lean.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Deterministic delayed-shot interleavings; no Minecraft client or sound device is started. */
public final class LeanShotDispatchChecks {
    private static int checks;

    public static void main(String[] args) {
        sameGunSynchronization();
        realWeaponChanges();
        lifecycleChanges();
        swimmingCancelsBurstsAcrossWaterExit();
        equalOwnersStillHaveDistinctLifetimes();
        staleOwnerDoesNotInvalidateCurrentShots();
        interleavedBursts();
        rejectedRoundsStayCancelled();
        firstPacketIsRequired();
        callbackFailuresCancelTheGroup();
        oneDecisionCoversTheWholeRound();
        System.out.println("LeanShotDispatchChecks: " + checks + " delayed-shot checks passed; no game launched");
    }

    private static void sameGunSynchronization() {
        for (boolean beforeTimer : new boolean[]{true, false}) {
            Fixture fixture = new Fixture();
            GunStack original = fixture.held;
            LeanShotDispatch shot = fixture.group();
            ArrayDeque<Runnable> timer = new ArrayDeque<>(), client = new ArrayDeque<>();
            List<Boolean> accepted = new ArrayList<>();
            timer.add(() -> client.add(() -> accepted.add(fixture.fire(shot, "single", 0))));
            if (beforeTimer) fixture.syncAmmo(29);
            drain(timer);
            if (!beforeTimer) fixture.syncAmmo(29);
            check(original != fixture.held && original.gun() != fixture.held.gun(), "server synchronization replaces stack and key objects");
            check(original.gun().equals(fixture.held.gun()), "replacement retains the same stable gun identity");
            fixture.session.observe(fixture.identity());
            drain(client);
            check(accepted.equals(List.of(true)), "same-gun synchronization survives " + (beforeTimer ? "the timer delay" : "the client queue delay"));
            check(fixture.events.equals(List.of("single.pose0", "single.send", "single.animation0")), "one valid shot sends pose, packet and animation exactly once");
        }
    }

    private static void realWeaponChanges() {
        Fixture fixture = new Fixture();
        LeanShotDispatch old = fixture.group();
        fixture.slot = 1; // Same gun model in another slot must still invalidate the old shot.
        fixture.session.observe(fixture.identity());
        fixture.slot = 0;
        fixture.session.observe(fixture.identity());
        check(!fixture.fire(old, "old", 0), "switching to the same model and back does not revive an old shot");
        check(fixture.events.isEmpty(), "cancelled old weapon sends and plays nothing");
        check(fixture.fire(fixture.group(), "new", 0), "the current weapon session can fire after switching back");

        Fixture draw = new Fixture();
        LeanShotDispatch beforeDraw = draw.group();
        draw.session.invalidate(); // Native draw can distinguish two identical guns in the same slot.
        check(!draw.fire(beforeDraw, "old", 0), "native draw invalidates even an equal slot and gun identity");
        check(draw.events.isEmpty(), "native draw cancellation is silent and sends no packet");
        check(draw.fire(draw.group(), "new", 0), "a newly captured session works after draw");

        Fixture model = new Fixture();
        LeanShotDispatch beforeReplacement = model.group();
        model.held = new GunStack(new GunId("tacz:other_gun"), 30);
        check(!model.fire(beforeReplacement, "old", 0), "different gun identity is rejected even without a slot change");
    }

    private static void lifecycleChanges() {
        Fixture dead = new Fixture();
        LeanShotDispatch dying = dead.group();
        check(!dying.dispatch(dead.session, null, 0, () -> dead.events.add("pose"), () -> dead.events.add("send"), () -> dead.events.add("animation")),
                "dead or disconnected live context rejects the entire first round");
        check(!dead.fire(dying, "revived", 0), "restoring a live context cannot revive the rejected group");
        check(dead.events.isEmpty(), "death rejection produces no network or audiovisual effects");

        for (String changed : List.of("connection", "player", "level")) {
            Fixture fixture = new Fixture();
            LeanShotDispatch old = fixture.group();
            switch (changed) {
                case "connection" -> fixture.connection = new Object();
                case "player" -> fixture.player = new Object();
                case "level" -> fixture.level = new Object();
                default -> throw new AssertionError(changed);
            }
            fixture.session.observe(fixture.identity());
            check(!fixture.fire(old, "old", 0), changed + " replacement invalidates queued fire");
            check(fixture.events.isEmpty(), changed + " replacement leaves no partial shot");
            check(fixture.fire(fixture.group(), "fresh", 0), "fresh " + changed + " session may fire");
        }
    }

    private static void equalOwnersStillHaveDistinctLifetimes() {
        for (String changed : List.of("player", "level", "connection")) {
            Fixture fixture = new Fixture();
            FakeOwner first = new FakeOwner(7), replacement = new FakeOwner(7);
            switch (changed) {
                case "player" -> fixture.player = first;
                case "level" -> fixture.level = first;
                case "connection" -> fixture.connection = first;
                default -> throw new AssertionError(changed);
            }
            LeanShotDispatch old = fixture.group();
            check(first != replacement && first.equals(replacement), "replacement " + changed + " has equal content but a distinct lifetime");
            switch (changed) {
                case "player" -> fixture.player = replacement;
                case "level" -> fixture.level = replacement;
                case "connection" -> fixture.connection = replacement;
                default -> throw new AssertionError(changed);
            }
            fixture.session.observe(fixture.identity());
            LeanShotDispatch fresh = fixture.group();
            check(!fixture.fire(old, "old", 0), "equal-valued replacement " + changed + " still rejects the old ticket");
            check(fixture.events.isEmpty(), "equal-valued " + changed + " replacement leaves no stale effects");
            check(fixture.fire(fresh, "fresh", 0), "replacement " + changed + " retains its already captured new ticket");
            check(fixture.events.equals(List.of("fresh.pose0", "fresh.send", "fresh.animation0")),
                    "new " + changed + " lifetime completes one whole shot");
        }
    }

    private static void swimmingCancelsBurstsAcrossWaterExit() {
        for (boolean roundWhileSwimming : new boolean[]{true, false}) {
            Fixture fixture = new Fixture();
            LeanShotDispatch burst = fixture.group();
            check(fixture.fire(burst, "burst", 0), "land burst starts before entering water");
            int before = fixture.events.size();
            if (roundWhileSwimming) {
                check(!burst.dispatch(fixture.session, null, 1,
                                () -> fixture.events.add("wet.pose"), null, () -> fixture.events.add("wet.animation")),
                        "swimming's unavailable firing context rejects the entire next round");
            } else {
                // ClientTick.Pre observes water even if the burst timer has no due round.
                fixture.session.observe(null);
            }
            fixture.session.observe(fixture.identity());
            check(!fixture.fire(burst, "old", 1) && !fixture.fire(burst, "old", 2),
                    "exiting water cannot resume the previous burst, including between timer callbacks");
            check(fixture.events.size() == before, "interrupted burst adds no stale pose, packet or animation after water exit");
            check(fixture.fire(fixture.group(), "fresh", 0), "a newly requested shot works after leaving water");
        }
    }

    private static void staleOwnerDoesNotInvalidateCurrentShots() {
        Fixture fixture = new Fixture();
        LeanShotDispatch oldPlayerShot = fixture.group();
        fixture.player = new Object();
        LeanShotDispatch currentPlayerShot = fixture.group();
        ArrayDeque<Runnable> client = new ArrayDeque<>();
        List<Boolean> accepted = new ArrayList<>();
        // Both callbacks see the actual live player, even though the first ticket belongs to the old one.
        enqueueRound(client, fixture, oldPlayerShot, "old", 0, accepted);
        enqueueRound(client, fixture, currentPlayerShot, "current", 0, accepted);
        drain(client);
        check(accepted.equals(List.of(false, true)), "rejecting an old player ticket preserves the already captured current player ticket");
        check(fixture.events.equals(List.of("current.pose0", "current.send", "current.animation0")),
                "stale callback produces no effects and current callback still completes its whole shot");
    }

    private static void interleavedBursts() {
        Fixture fixture = new Fixture();
        LeanShotDispatch a = fixture.group(), b = fixture.group();
        ArrayDeque<Runnable> client = new ArrayDeque<>();
        List<Boolean> accepted = new ArrayList<>();
        int[] mutableCounter = {0};
        enqueueRound(client, fixture, a, "A", mutableCounter[0]++, accepted);
        enqueueRound(client, fixture, b, "B", 0, accepted);
        enqueueRound(client, fixture, a, "A", mutableCounter[0]++, accepted);
        enqueueRound(client, fixture, a, "A", mutableCounter[0]++, accepted);
        check(mutableCounter[0] == 3, "all timer callbacks finish before the client drains its queue");
        fixture.syncAmmo(26);
        fixture.session.observe(fixture.identity());
        drain(client);
        check(accepted.equals(List.of(true, true, true, true)), "independent groups and delayed ordinal snapshots remain accepted");
        check(fixture.events.equals(List.of(
                "A.pose0", "A.send", "A.animation0",
                "B.pose0", "B.send", "B.animation0",
                "A.pose1", "A.animation1",
                "A.pose2", "A.animation2")), "interleaved burst groups preserve per-round order and send only their own first packet");

        Fixture interrupted = new Fixture();
        LeanShotDispatch burst = interrupted.group();
        check(interrupted.fire(burst, "burst", 0), "burst first round is accepted");
        int before = interrupted.events.size();
        interrupted.session.invalidate();
        check(!interrupted.fire(burst, "burst", 1), "draw rejects the next burst round");
        check(!interrupted.fire(burst, "burst", 2), "rejected burst remains cancelled");
        check(interrupted.events.size() == before, "later cancelled rounds add no pose or animation");
    }

    private static void rejectedRoundsStayCancelled() {
        for (int firstOrdinal : new int[]{-1, 1, 2}) {
            Fixture fixture = new Fixture();
            LeanShotDispatch shot = fixture.group();
            check(!fixture.fire(shot, "invalid", firstOrdinal), "a group cannot start at ordinal " + firstOrdinal);
            check(!fixture.fire(shot, "retry", 0), "a rejected first ordinal cannot restart its group");
            check(fixture.events.isEmpty(), "invalid ordinals produce no effects");
        }
        for (int nextOrdinal : new int[]{0, 2}) {
            Fixture fixture = new Fixture();
            LeanShotDispatch shot = fixture.group();
            check(fixture.fire(shot, "shot", 0), "first round accepted before an invalid continuation");
            int before = fixture.events.size();
            check(!fixture.fire(shot, "invalid", nextOrdinal), "duplicate or skipped ordinal " + nextOrdinal + " is rejected");
            check(!fixture.fire(shot, "retry", 1), "rejected continuation permanently cancels the group");
            check(fixture.events.size() == before, "duplicate or skipped rounds have no side effects");
        }
    }

    private static void firstPacketIsRequired() {
        Fixture fixture = new Fixture();
        LeanShotDispatch shot = fixture.group();
        boolean accepted = false;
        try {
            accepted = shot.dispatch(fixture.session, fixture.identity(), 0,
                    () -> fixture.events.add("pose"), null, () -> fixture.events.add("animation"));
        } catch (RuntimeException expected) {
            // Missing mandatory work may be rejected by return value or argument validation.
        }
        check(!accepted && fixture.events.isEmpty(), "missing first-shot packet is rejected before any effect");
        check(!fixture.fire(shot, "retry", 0), "a missing first-shot packet cannot be supplied by retrying the same group");

        Fixture later = new Fixture();
        LeanShotDispatch burst = later.group();
        check(later.fire(burst, "burst", 0), "first burst round has its native packet");
        check(burst.dispatch(later.session, later.identity(), 1,
                () -> later.events.add("burst.pose1"), null, () -> later.events.add("burst.animation1")), "later burst rounds need no new native packet");
        check(later.events.equals(List.of("burst.pose0", "burst.send", "burst.animation0", "burst.pose1", "burst.animation1")),
                "later round emits only its pose and animation");
    }

    private static void callbackFailuresCancelTheGroup() {
        for (int failedStage = 0; failedStage < 3; failedStage++) {
            Fixture fixture = new Fixture();
            LeanShotDispatch shot = fixture.group();
            RuntimeException failure = new IllegalStateException("simulated callback failure");
            RuntimeException caught = null;
            try {
                shot.dispatch(fixture.session, fixture.identity(), 0,
                        stage(fixture.events, "pose", failedStage == 0, failure),
                        stage(fixture.events, "send", failedStage == 1, failure),
                        stage(fixture.events, "animation", failedStage == 2, failure));
            } catch (RuntimeException exception) {
                caught = exception;
            }
            check(caught == failure, "callback failure is propagated to the task executor");
            check(fixture.events.equals(List.of("pose", "send", "animation").subList(0, failedStage + 1)), "no later callbacks run after a failed stage");
            int before = fixture.events.size();
            check(!fixture.fire(shot, "retry", 0) && !fixture.fire(shot, "later", 1), "callback failure permanently cancels first and later rounds");
            check(fixture.events.size() == before, "a failed group never repeats completed effects");
        }
    }

    private static void oneDecisionCoversTheWholeRound() {
        Fixture fixture = new Fixture();
        LeanShotDispatch shot = fixture.group();
        check(shot.dispatch(fixture.session, fixture.identity(), 0, () -> {
            fixture.events.add("pose");
            fixture.session.invalidate();
        }, () -> fixture.events.add("send"), () -> fixture.events.add("animation")), "one valid decision owns the entire current round");
        check(fixture.events.equals(List.of("pose", "send", "animation")), "no second validity check splits pose, packet and animation");
        check(!fixture.fire(shot, "later", 1), "changed session still rejects the next round");
    }

    private static void enqueueRound(ArrayDeque<Runnable> client, Fixture fixture, LeanShotDispatch group,
            String name, int ordinal, List<Boolean> accepted) {
        client.add(() -> accepted.add(fixture.fire(group, name, ordinal)));
    }

    private static Runnable stage(List<String> events, String name, boolean fail, RuntimeException failure) {
        return () -> { events.add(name); if (fail) throw failure; };
    }

    private static void drain(ArrayDeque<Runnable> queue) {
        while (!queue.isEmpty()) queue.removeFirst().run();
    }

    private record GunId(String value) { }
    private record GunStack(GunId gun, int ammo) { }
    private record FakeOwner(int id) { }

    private static final class Fixture {
        final LeanShotSession session = new LeanShotSession();
        final List<String> events = new ArrayList<>();
        Object player = new Object(), level = new Object(), connection = new Object();
        int slot;
        GunStack held = new GunStack(new GunId("tacz:test_gun"), 30);

        LeanShotSession.Identity identity() {
            return new LeanShotSession.Identity(player, level, connection, slot, held.gun());
        }

        LeanShotDispatch group() { return new LeanShotDispatch(session.capture(identity())); }

        void syncAmmo(int ammo) { held = new GunStack(new GunId(held.gun().value()), ammo); }

        boolean fire(LeanShotDispatch group, String name, int ordinal) {
            return group.dispatch(session, identity(), ordinal,
                    () -> events.add(name + ".pose" + ordinal),
                    () -> events.add(name + ".send"),
                    () -> events.add(name + ".animation" + ordinal));
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
