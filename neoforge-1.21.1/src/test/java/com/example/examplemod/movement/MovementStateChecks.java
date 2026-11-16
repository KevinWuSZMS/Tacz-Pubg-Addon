package com.example.examplemod.movement;

import static com.example.examplemod.movement.MovementState.Action.*;
import static com.example.examplemod.movement.MovementState.Mode;

/** Fixed action semantics, edge ordering and jump suppression independently of a Minecraft client. */
public final class MovementStateChecks {
    private static int checks;
    public static void main(String[] args) {
        var state = new MovementState();
        state.press(CROUCH); check(state.mode() == Mode.CROUCH, "C toggles crouch on");
        state.press(CROUCH); check(state.mode() == Mode.CROUCH, "Held C cannot repeat toggle");
        state.release(CROUCH); state.press(CROUCH); check(state.mode() == Mode.STAND, "Next C toggles crouch off");
        state.release(CROUCH); state.press(CROUCH); state.release(CROUCH);
        state.press(STAND); check(state.mode() == Mode.STAND && state.consumeJump(), "Space from crouch stands without jumping");
        state.press(STAND); check(state.consumeJump(), "Holding Space keeps first jump consumed");
        state.release(STAND); state.press(STAND); check(!state.consumeJump(), "Next Space allows ordinary jump");
        state.reset(); state.press(PRONE); state.release(PRONE); state.press(STAND);
        check(state.mode() == Mode.STAND && state.consumeJump(), "Space also stands from prone");
        state.reset(); state.press(SPRINT); check(state.mode() == Mode.SPRINT, "Shift starts sprint");
        state.release(SPRINT); check(state.mode() == Mode.STAND, "Shift release immediately stops sprint");
        state.press(SPRINT); state.press(CROUCH); state.release(CROUCH);
        check(state.mode() == Mode.CROUCH && state.held(SPRINT), "C replaces held sprint");
        state.press(SPRINT); check(state.mode() == Mode.CROUCH, "Older held sprint cannot reassert");
        state.release(SPRINT); state.press(SPRINT); check(state.mode() == Mode.SPRINT, "Fresh Shift replaces crouch");
        state.press(PRONE); check(state.mode() == Mode.PRONE, "Z replaces sprint");
        state.release(SPRINT); check(state.mode() == Mode.PRONE, "Old sprint release cannot undo prone");
        state.press(WALK); check(state.mode() == Mode.WALK, "Ctrl replaces prone with upright walk");
        state.release(WALK); check(state.mode() == Mode.STAND, "Ctrl release ends walk");
        state.reset(); state.press(STAND, true); check(state.consumeJump(), "Stand also clears native crouch/crawl");
        state.reset(); check(state.mode() == Mode.STAND && !state.consumeJump() && !state.held(STAND), "Context reset clears all input latches");
        state.press(SPRINT); state.press(STAND); check(state.mode() == Mode.SPRINT && !state.consumeJump(), "Space while upright preserves sprint-jump");
        state.reset(); state.press(WALK); state.press(CROUCH); state.release(CROUCH); state.press(WALK);
        check(state.mode() == Mode.CROUCH, "Older held walk cannot undo newer crouch");
        state.reset(); state.press(SPRINT); state.adoptRescueCrouch();
        check(state.mode() == Mode.CROUCH && state.held(SPRINT), "Rescue adopts crouch while preserving older held-key latches");
        state.press(SPRINT); check(state.mode() == Mode.CROUCH, "A sprint key held before rescue cannot reassert sprint");
        state.release(SPRINT); check(state.mode() == Mode.CROUCH, "Releasing the old sprint key preserves rescue crouch");
        state.press(SPRINT); check(state.mode() == Mode.SPRINT, "A fresh sprint press changes the rescue crouch stance");
        state.adoptRescueCrouch(); state.press(STAND);
        check(state.mode() == Mode.STAND && state.consumeJump(), "Space from rescue crouch stands without jumping");
        state.adoptRescueCrouch();
        check(state.mode() == Mode.CROUCH && !state.consumeJump() && state.held(STAND), "A new rescue clears jump suppression without inventing a key release");
        state.press(STAND); check(state.mode() == Mode.CROUCH, "An already held Space cannot stand up after a new rescue starts");
        state.release(STAND); state.press(STAND);
        check(state.mode() == Mode.STAND && state.consumeJump(), "A fresh Space changes rescue crouch to standing");
        state.reset(); state.adoptRescueCrouch(); state.press(CROUCH);
        check(state.mode() == Mode.STAND, "C toggles the adopted rescue crouch off");
        state.release(CROUCH); state.adoptRescueCrouch(); state.press(PRONE);
        check(state.mode() == Mode.PRONE, "Z changes adopted rescue crouch to prone");
        System.out.println("MovementStateChecks: " + checks + " movement and input-order scenarios passed");
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
