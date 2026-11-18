package com.example.examplemod.keybindings;

import java.util.List;
import java.util.Set;

/** Physical trigger scenarios; the transformed Minecraft event pipeline is checked in the client. */
public final class BindingInputChecks {
    private static int checks;
    public static void main(String[] args) {
        var chord = InputBinding.chord("ControlAny", "G");
        check(!BindingInput.held(chord, Set.of("G")), "G alone must not trigger Ctrl+G");
        check(!BindingInput.held(chord, Set.of("ControlLeft")), "Ctrl alone must not trigger Ctrl+G");
        check(BindingInput.held(chord, Set.of("G", "ControlLeft")), "Left Ctrl completes generic chord");
        check(BindingInput.held(chord, Set.of("G", "ControlRight")), "Right Ctrl completes generic chord");
        check(!BindingInput.held(InputBinding.chord("ControlLeft", "G"), Set.of("G", "ControlRight")), "Side-specific Ctrl stays specific");
        check(BindingInput.triggerMatches(chord, "G", Set.of("ControlLeft"), Set.of("ControlLeft", "G"), false), "Normal chord order");
        check(BindingInput.triggerMatches(chord, "ControlLeft", Set.of("G"), Set.of("G", "ControlLeft"), false), "Reverse chord order");
        check(BindingInput.triggerMatches(chord, "ControlLeft", Set.of("G", "ControlLeft"), Set.of("G"), true), "Modifier release ends chord");
        check(BindingInput.triggerMatches(chord, "G", Set.of("G", "ControlLeft"), Set.of("ControlLeft"), true), "Primary release ends chord");
        check(!BindingInput.triggerMatches(chord, "G", Set.of("G"), Set.of(), true), "Incomplete chord never emits release");
        check(!BindingInput.triggerMatches(chord, "W", Set.of("G", "ControlLeft"), Set.of("G", "ControlLeft", "W"), false), "Unrelated movement does not retrigger chord");
        var alternatives = new InputBinding(List.of("G", "Mouse4"), InputBinding.Mode.ANY);
        check(BindingInput.held(alternatives, Set.of("Mouse4")), "Mouse alternative independently holds action");
        check(!BindingInput.triggerMatches(alternatives, "G", Set.of("G", "Mouse4"), Set.of("Mouse4"), true), "Releasing one alternative keeps action held");
        check(BindingInput.triggerMatches(alternatives, "Mouse4", Set.of("Mouse4"), Set.of(), true), "Final alternative releases action");
        var wheel = InputBinding.chord("AltAny", "ScrollUp");
        check(!BindingInput.held(wheel, Set.of("AltLeft")), "Wheel chord is an impulse");
        check(BindingInput.triggerMatches(wheel, "ScrollUp", Set.of("AltLeft"), Set.of("AltLeft", "ScrollUp"), false), "Modified wheel impulse");
        check(!BindingInput.held(InputBinding.empty(), Set.of("G")), "Unbound action stays inactive");
        check(BindingInput.modifiedVersion(List.of("ControlAny", "G"), List.of("G")), "Ctrl+G has precedence over G");
        check(BindingInput.modifiedVersion(List.of("ShiftLeft", "Mouse1"), List.of("Mouse1")), "Modified mouse keeps native precedence");
        check(!BindingInput.modifiedVersion(List.of("ControlAny", "G"), List.of("ControlLeft")), "Modifier's own action remains independent");
        check(!BindingInput.modifiedVersion(List.of("ControlAny", "G"), List.of("ControlAny", "G")), "Identical chords can conflict and both fire");
        check(!BindingInput.modifiedVersion(List.of("G", "H"), List.of("G")), "Arbitrary two-key chord does not change modifier precedence");
        var staleEvents = Set.of("G", "ControlLeft", "Mouse4");
        check(BindingInput.held(chord, staleEvents), "reproduce a cached chord with a lost release callback");
        check(!BindingInput.pollHeld(chord, key -> false), "native release wins over the stale event cache");
        check(BindingInput.pollHeld(chord, Set.of("G", "ControlRight")::contains), "native polling supports generic right Ctrl");
        check(!BindingInput.pollHeld(chord, Set.of("G")::contains), "native modifier release ends a chord");
        check(!BindingInput.pollHeld(chord, Set.of("ControlLeft")::contains), "native primary release ends a chord");
        check(BindingInput.pollHeld(alternatives, Set.of("Mouse4")::contains), "remaining native mouse alternative keeps the wheel open");
        check(!BindingInput.pollHeld(alternatives, key -> false), "native release of the final alternative closes the wheel");
        check(!BindingInput.pollHeld(InputBinding.empty(), key -> true), "empty bindings never become held from native input");
        check(!BindingInput.pollHeld(InputBinding.chord("ScanCode:99999"), key -> false), "unresolvable scan codes cannot latch a wheel");
        check(!BindingInput.pollHeld(InputBinding.chord("G"), Set.of("G")::contains, "G"), "a fresh single-key press can rearm after a missed release");
        check(BindingInput.pollHeld(alternatives, Set.of("G", "Mouse4")::contains, "Mouse4"), "a second alternative does not rearm while the first remains held");
        check(BindingInput.pollHeld(chord, Set.of("G", "ControlLeft", "ControlRight")::contains, "ControlRight"), "second Ctrl does not rearm an already complete generic chord");
        check(!BindingInput.pollHeld(chord, Set.of("G", "ControlRight")::contains, "ControlRight"), "completing a previously released chord rearms it");
        check(BindingInput.pollKeyboardHeld(InputBinding.chord("W"), Set.of("W")::contains), "inventory movement retains keyboard direction");
        check(!BindingInput.pollKeyboardHeld(InputBinding.chord("Mouse1"), Set.of("Mouse1")::contains), "inventory clicks cannot become movement");
        check(BindingInput.pollKeyboardHeld(alternatives, Set.of("G", "Mouse4")::contains), "keyboard alternative still works beside a UI mouse press");
        check(!BindingInput.pollKeyboardHeld(alternatives, Set.of("Mouse4")::contains), "mouse alternative is excluded from inventory movement");
        check(!BindingInput.pollKeyboardHeld(InputBinding.chord("W", "Mouse1"), Set.of("W", "Mouse1")::contains), "mouse-containing chord cannot move through inventory");
        check(BindingInput.pollKeyboardHeld(chord, Set.of("G", "ControlRight")::contains), "keyboard-only polling retains either-side modifiers");
        check(!BindingInput.pollKeyboardHeld(chord, Set.of("G")::contains), "inventory modifier release cannot leave movement held");
        check(!BindingInput.pollKeyboardHeld(InputBinding.empty(), key -> true), "empty inventory movement binding stays inactive");
        System.out.println("BindingInputChecks: " + checks + " physical input scenarios passed");
    }

    private static void check(boolean condition, String scenario) {
        checks++;
        if (!condition) throw new AssertionError(scenario);
    }
}
