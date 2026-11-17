package com.example.examplemod.throwables.client;

import java.util.EnumSet;

/** Replays release loss and cancellation against the production wheel hold state. */
public final class RadialWheelInputChecks {
    private enum Trigger { KEYBOARD, MOUSE }
    private static int checks;

    public static void main(String[] args) {
        checks = 0;
        for (Trigger trigger : Trigger.values()) {
            lostRelease(trigger);
            cancelledHold(trigger);
            freshPressBeforePoll(trigger);
        }
        independentTriggers();
        System.out.println("Radial wheel input checks passed: " + checks + "; no game launched.");
    }

    private static void lostRelease(Trigger trigger) {
        var input = new RadialWheelInput<Trigger>();
        var cachedEvents = EnumSet.of(trigger);
        var nativeHeld = EnumSet.of(trigger);
        check(input.tryOpen(trigger), "first press opens " + trigger);
        check(!input.release(nativeHeld::contains), "held trigger keeps the wheel open");
        check(!input.tryOpen(trigger), "one hold cannot open a second wheel");

        // No release event reaches the cached set, but the actual key/button has been released.
        nativeHeld.clear();
        check(cachedEvents.contains(trigger), "reproduction retains the stale event state");
        check(input.release(nativeHeld::contains), "native release confirms despite a stale event cache");
        check(!input.blocked(), "release clears the trigger lock");
        check(!input.release(nativeHeld::contains), "tick and render cannot confirm the same release twice");

        nativeHeld.add(trigger);
        check(input.tryOpen(trigger), "next press opens after a lost release callback");
        nativeHeld.clear();
        check(input.release(nativeHeld::contains), "second hold confirms independently");
    }

    private static void cancelledHold(Trigger trigger) {
        var input = new RadialWheelInput<Trigger>();
        var nativeHeld = EnumSet.of(trigger);
        check(input.tryOpen(trigger), "open before right-click cancellation");
        input.cancel();
        check(input.blocked(), "cancellation retains the held trigger lock");
        check(!input.release(nativeHeld::contains), "cancelled wheel never confirms while held");
        check(!input.tryOpen(trigger), "continued hold does not reopen after cancellation");
        // A repeat event must not call pressed(); the next tick still sees the same hold.
        check(!input.tryOpen(trigger), "repeat ticks do not reopen a cancelled wheel");

        nativeHeld.clear();
        check(!input.release(nativeHeld::contains), "release after cancellation does not select an item");
        check(!input.blocked(), "release rearms a cancelled wheel");
        nativeHeld.add(trigger);
        check(input.tryOpen(trigger), "fresh hold opens after cancellation and release");
    }

    private static void freshPressBeforePoll(Trigger trigger) {
        var input = new RadialWheelInput<Trigger>();
        check(input.tryOpen(trigger), "open before same-tick release and press");
        input.cancel();
        // The missing release callback and polling gap are recovered by the next real PRESS.
        input.pressed(trigger);
        check(!input.blocked(), "a fresh press rearms the same cancelled trigger");
        check(input.tryOpen(trigger), "same-tick press opens without waiting for an idle poll");
        input.pressed(trigger);
        check(input.blocked(), "press handling cannot reset an already open wheel");
        check(!input.tryOpen(trigger), "active wheel is not duplicated by another press");
        check(input.release(key -> false), "reopened wheel still confirms on release");
    }

    private static void independentTriggers() {
        var input = new RadialWheelInput<Trigger>();
        check(!input.tryOpen(null), "no candidate cannot create an untracked open wheel");
        check(input.tryOpen(Trigger.KEYBOARD), "keyboard opens the shared wheel state");
        input.cancel();
        input.pressed(Trigger.MOUSE);
        check(input.blocked(), "unrelated mouse press does not clear a held keyboard trigger");
        check(!input.tryOpen(Trigger.MOUSE), "another binding cannot bypass the current hold lock");
        check(!input.release(key -> false), "cancelled keyboard releases without confirmation");
        check(input.tryOpen(Trigger.MOUSE), "mouse binding can open once the keyboard hold ends");
        input.clear();
        check(!input.blocked(), "disconnect or invalid context clears the entire hold state");
        check(!input.release(key -> false), "cleared state does not confirm a discarded wheel");
        check(input.tryOpen(Trigger.KEYBOARD), "clear permits a fresh keyboard hold");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
