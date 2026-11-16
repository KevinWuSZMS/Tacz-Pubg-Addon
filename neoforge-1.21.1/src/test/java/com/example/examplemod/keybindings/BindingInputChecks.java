package com.example.examplemod.keybindings;

import java.util.List;
import java.util.Set;

/** Physical trigger scenarios; the transformed Minecraft event pipeline is checked in the client. */
public final class BindingInputChecks {
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
        System.out.println("BindingInputChecks: 22 physical input scenarios passed");
    }

    private static void check(boolean condition, String scenario) {
        if (!condition) throw new AssertionError(scenario);
    }
}
